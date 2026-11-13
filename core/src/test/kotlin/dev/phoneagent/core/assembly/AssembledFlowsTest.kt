@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.assembly

import dev.phoneagent.core.agent.AgentLoop
import dev.phoneagent.core.agent.LoopTexts
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.agent.awaitPhase
import dev.phoneagent.core.agent.awaitPrompt
import dev.phoneagent.core.agent.text
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Refusal
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.Status
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The confirmation paths of the assembled agent (policy-rules §6.3, §6.4; contracts addendum §5),
 * with the real policy and tools on scripted screens: what runs only after the user approved it,
 * what is asked again, and what the model never sees.
 */
class AssembledFlowsTest {
    private val texts = LoopTexts()

    /** The main model reads no images, so an observation is one read of the screen. */
    private fun blind(): FakeChatModel =
        FakeChatModel(
            info =
                FakeChatModel.INFO.copy(
                    capabilities = ModelCapabilities(tools = true, vision = false, contextTokens = 200_000),
                ),
        )

    private fun TestScope.assembled(
        model: FakeChatModel,
        screen: ScriptedScreen,
        level: SecurityLevel = SecurityLevel.STANDARD,
        unattended: Boolean = false,
    ) = AssembledLoop(this, model, screen, settings = TaskStarted(level, unattended))

    private suspend fun AssembledLoop.started(id: String): Boolean =
        events().filterIsInstance<ToolStarted>().any { it.callId == ToolCallId(id) }

    private suspend fun AssembledLoop.cardsAsked(): List<ApprovalAsked> = events().filterIsInstance<ApprovalAsked>()

    /**
     * The next prompt after [after], or `null` once the task is done. Waits on the phase only, so
     * virtual time does not run on to a pending prompt's expiry.
     */
    private suspend fun AgentLoop.nextPrompt(after: PromptId?): PromptId? =
        when (val next = phase.first { it == Phase.Done || (it is Phase.Waiting && it.promptId != after) }) {
            is Phase.Waiting -> next.promptId
            else -> null
        }

    // request_confirmation (contracts addendum §5)

    private val confirmation =
        """{"action":"删除购物清单","target":"购物清单","content":"买菜、买水果","reason":"先问用户"}"""

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `request_confirmation runs only after the user approved it, at every level`(level: SecurityLevel) =
        runTest {
            val model = blind().calls(call("c1", "request_confirmation", confirmation)).answers("好")
            val harness = assembled(model, ScriptedScreen(Screens.CHAT), level)
            val loop = harness.start()
            loop.followup(text("删掉购物清单"))
            val prompt = loop.awaitPrompt().promptId!!

            val card = harness.cardsAsked().single().card
            assertEquals(ConfirmReason.REQUESTED_BY_MODEL, card.reason)
            assertEquals("删除购物清单", card.action)
            assertEquals(Verdict.Confirm(card), harness.verdictsOf("c1").single().verdict)
            assertFalse(harness.started("c1"), "nothing runs while it waits")
            assertTrue(loop.answer(prompt, UserReply.Approve))
            loop.awaitPhase(Phase.Done)

            val result = harness.resultOf("c1")
            assertEquals(ToolStatus.OK, result.status)
            assertTrue(result.text().startsWith("用户已确认：删除购物清单"), result.text())
            assertEquals(Verdict.Allow, harness.lastVerdictOf("c1"), "the re-check after the approval")
        }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `a rejected request_confirmation never runs, and nobody can approve an unattended one`(level: SecurityLevel) =
        runTest {
            val rejected =
                assembled(
                    blind().calls(call("c1", "request_confirmation", confirmation)).answers("好"),
                    ScriptedScreen(Screens.CHAT),
                    level,
                )
            val loop = rejected.start()
            loop.followup(text("删掉购物清单"))
            assertTrue(loop.answer(loop.awaitPrompt().promptId!!, UserReply.Reject))
            loop.awaitPhase(Phase.Done)
            assertFalse(rejected.started("c1"))
            assertEquals(ToolStatus.REFUSED, rejected.resultOf("c1").status)
            assertEquals(texts.rejected, rejected.resultOf("c1").text())

            val model = blind().calls(call("c1", "request_confirmation", confirmation)).answers("好")
            val alone = assembled(model, ScriptedScreen(Screens.CHAT), level, unattended = true).ran()
            assertEquals(
                ConfirmResult.UNAVAILABLE,
                alone
                    .events()
                    .filterIsInstance<ApprovalDecided>()
                    .single()
                    .result,
            )
            assertFalse(alone.started("c1"))
            assertEquals(ToolStatus.REFUSED, alone.resultOf("c1").status)
            assertEquals(texts.confirmUnavailable, alone.resultOf("c1").text())
        }

    // The re-check after an approval (policy-rules §6.3, §6.4)

    /** The chat page as screen [id], talking to [peer] (the page title, the card's target). */
    private fun chat(
        id: String,
        peer: String = "张三",
    ): ScreenSnapshot =
        Screens.CHAT.copy(
            id = SnapshotId(id),
            elements = Screens.CHAT_ELEMENTS.map { if (it.ref.value == "e2") it.copy(name = peer) else it },
        )

    private val send = """{"screen":"s12","ref":"e10","reason":"发送"}"""

    private suspend fun TestScope.sendOn(vararg rechecks: ScreenSnapshot): Pair<AssembledLoop, ScriptedScreen> {
        val screen = ScriptedScreen(chat("s12"), *rechecks)
        val model = blind().calls(call("c0", "phone_observe")).calls(call("c1", "phone_tap", send)).answers("发了")
        val harness = assembled(model, screen)
        val loop = harness.start()
        loop.followup(text("把消息发出去"))
        var prompt = loop.nextPrompt(after = null)
        while (prompt != null) {
            assertTrue(loop.answer(prompt, UserReply.Approve))
            prompt = loop.nextPrompt(after = prompt)
        }
        return harness to screen
    }

    @Test
    fun `an approved send runs after one question when the chat did not change`() =
        runTest {
            val (harness, screen) = sendOn(chat("s13"))
            assertEquals(listOf("张三"), harness.cardsAsked().map { it.card.target })
            assertEquals(ToolStatus.OK, harness.resultOf("c1").status)
            assertEquals(1, screen.actions.size)
        }

    @Test
    fun `a send whose chat changed under the approval is asked again, then runs`() =
        runTest {
            val (harness, screen) = sendOn(chat("s13", peer = "李四"), chat("s14", peer = "李四"))
            assertEquals(
                listOf("张三", "李四"),
                harness.cardsAsked().map { it.card.target },
                "the new card is the new chat's",
            )
            assertEquals(ToolStatus.OK, harness.resultOf("c1").status)
            assertEquals(1, screen.actions.size)
        }

    @Test
    fun `a send whose chat keeps changing is asked again at most once, then refused`() =
        runTest {
            val (harness, screen) = sendOn(chat("s13", peer = "李四"), chat("s14", peer = "王五"))
            assertEquals(listOf("张三", "李四"), harness.cardsAsked().map { it.card.target })
            val result = harness.resultOf("c1")
            assertEquals(ToolStatus.REFUSED, result.status)
            assertEquals(texts.changedSinceApproval, result.text())
            assertTrue(screen.actions.isEmpty(), "never runs")
            assertFalse(harness.started("c1"))
        }

    // FIRST_IN_APP after a refused launch (policy-rules §6.4)

    private val notes: ScreenSnapshot =
        ScriptedScreen.snapshotOf(
            "s40",
            pkg = "com.example.notes",
            elements =
                listOf(
                    el("e1", ElementRole.BUTTON, "新建", box(800, 50, 980, 120), setOf(ElementState.CLICKABLE)),
                ),
        )

    /**
     * At the cautious level, from a chat (c0): c1 launches the notes app ([approve] or not); c2 then
     * finds the notes app in front either way, and c3 taps 新建 there.
     */
    private suspend fun TestScope.launchThenTap(approve: Boolean): AssembledLoop {
        val screen = ScriptedScreen(chat("s12"), notes)
        val model =
            blind()
                .calls(call("c0", "phone_observe"))
                .calls(call("c1", "phone_launch", """{"app":"com.example.notes","reason":"打开笔记"}"""))
                .calls(call("c2", "phone_observe"))
                .calls(call("c3", "phone_tap", """{"screen":"s40","ref":"e1","reason":"新建"}"""))
                .answers("好")
        val harness = assembled(model, screen, SecurityLevel.CAUTIOUS)
        val loop = harness.start()
        loop.followup(text("记一条笔记"))
        val launch = loop.awaitPrompt().promptId!!
        assertTrue(loop.answer(launch, if (approve) UserReply.Approve else UserReply.Reject))
        // Whatever c3 asks is rejected: the question is the point.
        var prompt = loop.nextPrompt(after = launch)
        while (prompt != null) {
            assertTrue(loop.answer(prompt, UserReply.Reject))
            prompt = loop.nextPrompt(after = prompt)
        }
        assertEquals(
            listOf(ConfirmReason.FIRST_IN_APP),
            harness.cardsAsked().take(1).map { it.card.reason },
            "the launch asks",
        )
        assertEquals(if (approve) listOf(LaunchTarget.App("com.example.notes")) else emptyList(), screen.launches)
        return harness
    }

    @Test
    fun `after a refused launch the first action in that app still asks FIRST_IN_APP`() =
        runTest {
            val harness = launchThenTap(approve = false)
            val asked = harness.cardsAsked()
            assertEquals(listOf(ConfirmReason.FIRST_IN_APP, ConfirmReason.FIRST_IN_APP), asked.map { it.card.reason })
            assertEquals("com.example.notes", asked.last().card.pkg)
            assertEquals(ToolCallId("c3"), asked.last().card.callId)
        }

    @Test
    fun `after an approved launch the first action in that app runs without asking again`() =
        runTest {
            val harness = launchThenTap(approve = true)
            assertEquals(1, harness.cardsAsked().size)
            assertEquals(ToolStatus.OK, harness.resultOf("c3").status)
        }

    // A tap that lands in a refused app (policy-rules §6.4, WITHHELD)

    /**
     * A bank page: each kind of text core is given carries the marker, an element's name, value,
     * hint (shown for a field without a value) and resource id, the activity and OCR. The app maps
     * a content description into the name and passes no window title (the app's `TextSourcesTest`).
     */
    private val bank: ScreenSnapshot =
        Screens
            .snapshot(
                "s13",
                listOf(
                    el("e1", ElementRole.BUTTON, "返回", box(0, 30, 80, 90), setOf(ElementState.CLICKABLE)),
                    el("e2", ElementRole.TEXT, "MARK_ELEMENT 余额 12,345.67", box(40, 200, 700, 260)),
                    el("e3", ElementRole.INPUT, "MARK_NAME 转账金额", box(40, 400, 700, 460), setOf(ElementState.EDITABLE))
                        .copy(value = "MARK_VALUE 500", resourceId = "com.icbc:id/MARK_RESOURCE"),
                    el("e4", ElementRole.INPUT, bounds = box(40, 500, 700, 560), states = setOf(ElementState.EDITABLE))
                        .copy(hint = "MARK_HINT 备注"),
                ),
            ).copy(
                pkg = "com.icbc",
                activity = "com.icbc.MARK_ACTIVITY",
                ocr = listOf(OcrLine("MARK_OCR 账户", box(40, 300, 700, 340))),
            )

    /** The markers on [bank]. */
    private val markers =
        listOf("MARK_ELEMENT", "MARK_NAME", "MARK_VALUE", "MARK_HINT", "MARK_RESOURCE", "MARK_ACTIVITY", "MARK_OCR")

    /** A run whose tap on the chat lands on [page]; the model reads it and answers. */
    private suspend fun TestScope.tapInto(page: ScreenSnapshot): Pair<AssembledLoop, FakeChatModel> {
        val screen =
            ScriptedScreen(Screens.CHAT)
                .answers(
                    ActionOutcome(Status.Ok, Effect.NEW_APP, page, ScreenDiff(Screens.CHAT.id, added = page.elements)),
                )
        val model =
            blind()
                .calls(call("c0", "phone_observe"))
                .calls(call("c1", "phone_tap", """{"screen":"s12","ref":"e3","reason":"更多"}"""))
                .answers("好")
        return assembled(model, screen).ran() to model
    }

    /** The control for the next test: what of the same page the model sees when the app is not refused. */
    @Test
    fun `the bank page in an app that is not refused shows the model every marker but the resource id`() =
        runTest {
            val (_, model) = tapInto(bank.copy(pkg = "com.example.notes"))
            val seen = model.requests.last().toString()
            // A resource id only feeds the policy's reading of icon buttons; the format never shows it.
            assertEquals(markers - "MARK_RESOURCE", markers.filter { it in seen })
        }

    @Test
    fun `a tap that lands in a bank app shows none of it in the result, the cards or the next request`() =
        runTest {
            val (harness, model) = tapInto(bank)

            val result = harness.resultOf("c1")
            assertEquals(ToolStatus.OK, result.status)
            val text = result.text()
            assertFalse("MARK" in text, text)
            assertTrue(text.endsWith("\n" + texts.screenWithheld + Refusal.FINANCE_APP.hint), text)
            assertTrue(harness.cards.isNotEmpty())
            assertEquals(emptyList<String>(), harness.cards.map { it.toString() }.filter { "MARK" in it })
            assertEquals(emptyList<Int>(), model.requests.indices.filter { "MARK" in model.requests[it].toString() })
            assertEquals(3, model.requests.size, "the bank page was read, and the model went on")
        }
}
