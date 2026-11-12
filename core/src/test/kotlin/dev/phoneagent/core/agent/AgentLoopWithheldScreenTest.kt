@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.PolicyRules
import dev.phoneagent.core.policy.rules.Refusal
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.Status
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.screen.ScreenComponents
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.JudgmentPurpose
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import dev.phoneagent.core.tools.PhaseOneTools
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A screen a tool reads is judged before the model sees anything of it (policy-rules §6.4), with
 * the loop assembled as the app assembles it: the WP05 rules and the phase-1 tools. What must not
 * show is a marker (`MARK…`), and every check is made on the requests the model got, after
 * `toolPostExecute`.
 */
class AgentLoopWithheldScreenTest {
    private val rules = PolicyRules(setOf("dev.phoneagent"), setOf("PhoneAgent"))
    private val tools = PhaseOneTools.create(ScreenComponents(lexicon = rules.lexicon, vision = false).presenter)
    private val texts = LoopTexts()
    private val finance = Refusal.FINANCE_APP
    private val back = UiAction.Key(SystemKey.BACK)

    private companion object {
        const val BACK = """{"key":"back","reason":"返回"}"""
        const val FULL = """{"detail":"full","reason":"看"}"""
        const val STABLE = """{"condition":"stable","reason":"等"}"""
        const val WECHAT = """{"app":"com.tencent.mm","reason":"开"}"""
    }

    /** A page of a bank app: everything on it is marked. */
    private fun bank(id: String): ScreenSnapshot =
        Screens
            .snapshot(
                id,
                listOf(
                    el("e1", ElementRole.BUTTON, "返回", box(0, 30, 80, 90), setOf(ElementState.CLICKABLE)),
                    el("e2", ElementRole.TEXT, "MARK_ELEMENT 余额 12,345.67", box(40, 200, 700, 260)),
                ),
            ).copy(pkg = "com.icbc", activity = "com.icbc.MARK_ACTIVITY", ocr = listOf(OcrLine("MARK_OCR 账户", line)))

    /** The chat page as screen [id], with [ocr] read on it. */
    private fun chat(
        id: String,
        vararg ocr: String,
    ): ScreenSnapshot = Screens.CHAT.copy(id = SnapshotId(id), ocr = ocr.map { OcrLine(it, line) })

    private val line = box(40, 300, 700, 340)

    private fun TestScope.assembled(
        model: FakeChatModel,
        screen: ScreenPort,
        storage: dev.phoneagent.core.testing.InMemoryStoragePort =
            dev.phoneagent.core.testing
                .InMemoryStoragePort(),
    ): LoopHarness =
        LoopHarness(
            this,
            model,
            tools = tools,
            policy = rules.engine,
            intents = rules.resolver,
            screen = screen,
            storage = storage,
        )

    /** Runs [model] on [screen] until the task is done. */
    private suspend fun TestScope.ran(
        model: FakeChatModel,
        screen: ScreenPort,
    ): LoopHarness {
        val harness = assembled(model, screen)
        val loop = harness.start()
        loop.followup(text("去看看"))
        advanceUntilIdle()
        loop.awaitPhase(Phase.Done)
        return harness
    }

    private suspend fun LoopHarness.resultOf(id: String): ToolResult =
        events().only<ToolResultRecorded>().single { it.callId.value == id }.result

    /** The requests of [model] that carry [mark], by index. */
    private fun leaks(
        model: FakeChatModel,
        mark: String = "MARK",
    ): List<Int> = model.requests.indices.filter { mark in model.requests[it].toString() }

    @Test
    fun `a link that lands in a refused app shows its header and the hint, and the app stays refused but for BACK`() =
        runTest {
            val bank = bank("s13")
            val screen =
                ScriptedScreen(Screens.CHAT)
                    .answers(
                        ActionOutcome(
                            Status.Ok,
                            Effect.NEW_APP,
                            bank,
                            ScreenDiff(Screens.CHAT.id, added = bank.elements),
                        ),
                    ).answers(ActionOutcome(Status.Ok, Effect.NEW_APP, chat("s14")))
            val model =
                FakeChatModel()
                    .calls(call("c0", "phone_observe"))
                    .calls(call("c1", "phone_launch", """{"link":"https://bank.example/pay","reason":"打开链接"}"""))
                    .calls(call("c2", "phone_tap", """{"screen":"s13","ref":"e2","reason":"看余额"}"""))
                    .calls(call("c3", "phone_key", BACK))
                    .answers("好")
            val harness = ran(model, screen)

            assertEquals(emptyList<Int>(), leaks(model))
            val launched = harness.resultOf("c1")
            assertEquals(ToolStatus.OK, launched.status)
            val text = launched.text()
            assertTrue("screen s13 · com.icbc" in text && "按安全规则不显示这一屏的内容" in text, text)
            assertTrue(text.endsWith("\n" + texts.screenWithheld + finance.hint), text)
            val events = harness.events()
            assertEquals(
                bank,
                events
                    .only<ScreenLaunched>()
                    .single()
                    .outcome.after,
                "the log keeps the full screen",
            )
            val judged =
                ScreenJudged(ToolCallId("c1"), bank.id, JudgmentPurpose.TOOL, JudgmentOutcome.WITHHELD, finance.reason)
            assertTrue(judged.copy(hint = finance.hint) in events, "judged withheld, with the rule and its hint")
            // The next call is judged on the full screen: the tap is refused there, BACK goes through.
            assertEquals(ToolStatus.REFUSED, harness.resultOf("c2").status)
            assertEquals(finance.hint, harness.resultOf("c2").text())
            assertEquals(ToolStatus.OK, harness.resultOf("c3").status)
            assertEquals(listOf(back to null), screen.actions)
        }

    @Test
    fun `an action landing in this app, on sensitive settings or on a screen past the limits shows none of it`() =
        runTest {
            val marked = listOf(el("e1", ElementRole.TEXT, "MARK_PAGE USB 调试", box(40, 200, 700, 260)))
            val pages =
                listOf(
                    "this app" to Screens.snapshot("s13", marked).copy(pkg = "dev.phoneagent", activity = null),
                    "sensitive settings" to
                        Screens
                            .snapshot("s13", marked)
                            .copy(
                                pkg = "com.android.settings",
                                activity = "com.android.settings.DevelopmentSettingsActivity",
                            ),
                    "past the limits" to
                        Screens.snapshot("s13", listOf(el("e1", ElementRole.TEXT, "MARK_LONG " + "字".repeat(100_000)))),
                )
            val leaked = mutableListOf<String>()
            for ((name, page) in pages) {
                val screen = ScriptedScreen(Screens.CHAT).answers(ActionOutcome(Status.Ok, Effect.NEW_APP, page))
                val model =
                    FakeChatModel()
                        .calls(
                            call("c0", "phone_observe"),
                        ).calls(call("c1", "phone_key", BACK))
                        .answers("好")
                val harness = ran(model, screen)
                val judged = harness.events().only<ScreenJudged>().single { it.snapshot == page.id }
                if (leaks(model).isNotEmpty() ||
                    judged.outcome != JudgmentOutcome.WITHHELD
                ) {
                    leaked += "$name: ${judged.rule}"
                }
            }
            assertEquals(emptyList<String>(), leaked)
        }

    @Test
    fun `an observation of a refused app is withheld, and so is a wait that ends on one`() =
        runTest {
            val observe = FakeChatModel().calls(call("c0", "phone_observe", FULL)).answers("好")
            val observed = ran(observe, ScriptedScreen(bank("s1")))
            val wait =
                FakeChatModel()
                    .calls(call("c0", "phone_wait", """{"condition":"appear","text":"余额","reason":"等"}"""))
                    .answers("好")
            val waited = ran(wait, ScriptedScreen(chat("s1", "MARK_WAIT_START"), bank("s2")))

            assertEquals(emptyList<Int>(), leaks(observe) + leaks(wait))
            val seen = observed.resultOf("c0")
            assertTrue(seen.text().endsWith(texts.screenWithheld + finance.hint), seen.text())
            val result = waited.resultOf("c0")
            assertEquals(ToolStatus.ERROR, result.status, "a withheld screen meets no condition")
            assertTrue(result.text().startsWith("这一屏不能显示，条件无法核实。"), result.text())
            assertTrue(result.text().endsWith(texts.screenWithheld + finance.hint), result.text())
        }

    @Test
    fun `the port's own words reach the model only with a read-back it was shown`() =
        runTest {
            val unavailable = ScreenUnavailableException("MARK_UNAVAILABLE on com.icbc")
            val cases: List<Pair<String, ScreenPort>> =
                listOf(
                    "failed, read-back withheld" to answering(Status.Failed("MARK_REASON"), bank("s13")),
                    "failed, no read-back" to answering(Status.Failed("MARK_REASON"), after = null),
                    "refused, no read-back" to answering(Status.Refused("MARK_REASON"), after = null),
                    "the action throws" to Throwing(Screens.CHAT, unavailable, act = true),
                    "the next read throws" to Throwing(Screens.CHAT, unavailable, snapshotAfter = 1),
                )
            val leaked = mutableListOf<String>()
            for ((name, screen) in cases) {
                val model =
                    FakeChatModel()
                        .calls(call("c0", "phone_observe"))
                        .calls(call("c1", "phone_key", BACK))
                        .calls(call("c2", "phone_observe"))
                        .answers("好")
                val harness = ran(model, screen)
                if (leaks(model).isNotEmpty()) leaked += name
                val shown = harness.resultOf("c1").text() + harness.resultOf("c2").text()
                if (texts.reasonWithheld !in shown) leaked += "$name: no fixed text in $shown"
            }
            val waiting = FakeChatModel().calls(call("c0", "phone_wait", STABLE)).answers("好")
            ran(waiting, Throwing(Screens.CHAT, unavailable, awaitChange = true))
            if (leaks(waiting).isNotEmpty()) leaked += "the wait throws"
            val launching = FakeChatModel().calls(call("c0", "phone_launch", WECHAT)).answers("好")
            ran(launching, Throwing(Screens.CHAT, unavailable, act = true))
            if (leaks(launching).isNotEmpty()) leaked += "the launch throws"
            assertEquals(emptyList<String>(), leaked)

            // The control: the same reason with a read-back that was shown is the model's to read.
            val shown = answering(Status.Failed("MARK_REASON"), chat("s13"))
            val model = FakeChatModel().calls(call("c0", "phone_key", BACK)).answers("好")
            ran(model, shown)
            assertEquals(listOf(1), leaks(model, "MARK_REASON"))
        }

    @Test
    fun `a diff is shown only against a screen the model saw`() =
        runTest {
            val leaked = mutableListOf<String>()

            // Withheld, then shown: the diff against the bank page would list its lines as gone.
            val fromBank =
                ScriptedScreen(Screens.CHAT)
                    .answers(ActionOutcome(Status.Ok, Effect.NEW_APP, bank("s13")))
                    .answers(ActionOutcome(Status.Ok, Effect.NEW_APP, chat("s14"), against(bank("s13"))))
            val back =
                FakeChatModel()
                    .calls(call("c0", "phone_launch", """{"link":"https://bank.example","reason":"开"}"""))
                    .calls(call("c1", "phone_key", BACK))
                    .answers("好")
            ran(back, fromBank)
            if (leaks(back).isNotEmpty()) leaked += "withheld base"

            // A wait's screens before its last are not seen either.
            val waitThenTap =
                ScriptedScreen(chat("s1", "MARK_WAITING"), chat("s2", "完成"))
                    .answers(ActionOutcome(Status.Ok, Effect.CHANGED, chat("s3"), against(chat("s1", "MARK_WAITING"))))
            val waited =
                FakeChatModel()
                    .calls(call("c0", "phone_wait", """{"condition":"appear","text":"完成","reason":"等"}"""))
                    .calls(call("c1", "phone_key", BACK))
                    .answers("好")
            ran(waited, waitThenTap)
            if (leaks(waited).isNotEmpty()) leaked += "a wait's earlier screen"
            assertEquals(emptyList<String>(), leaked)

            // The control: against the screen the model saw, the diff shows what went away.
            val gone = listOf(OcrLine("CONTROL_GONE", line))
            val seen =
                ScriptedScreen(chat("s12", "CONTROL_GONE"))
                    .answers(
                        ActionOutcome(
                            Status.Ok,
                            Effect.CHANGED,
                            chat("s13"),
                            ScreenDiff(SnapshotId("s12"), ocrRemoved = gone),
                        ),
                    )
            val model =
                FakeChatModel()
                    .calls(call("c0", "phone_observe"))
                    .calls(call("c1", "phone_key", BACK))
                    .answers("好")
            val harness = ran(model, seen)
            val shown = harness.resultOf("c1").text()
            assertTrue(shown.startsWith("已按返回键。\nscreen s13") && "CONTROL_GONE" in shown, shown)
        }

    @Test
    fun `the screens the runtime reads itself never reach the model, before or after a resume`() =
        runTest {
            // The look after a handoff lands in the bank app; BACK then has it as its base.
            val screen =
                ScriptedScreen(Screens.CHAT, bank("s13"))
                    .answers(ActionOutcome(Status.Ok, Effect.CHANGED, chat("s14"), against(bank("s13"))))
                    .answers(ActionOutcome(Status.Ok, Effect.NEW_APP, chat("s15"), against(bank("s13"))))
            val model =
                FakeChatModel()
                    .calls(call("c0", "phone_observe"))
                    .calls(call("c1", "hand_to_user", """{"kind":"login","reason":"请登录"}"""))
                    .calls(call("c2", "phone_key", BACK))
                    .calls(call("c3", "phone_launch", WECHAT))
                    .answers("好")
            val harness = assembled(model, screen)
            val loop = harness.start()
            loop.followup(text("登录"))
            val prompt = loop.awaitPrompt().promptId!!
            assertTrue(loop.answer(prompt, UserReply.HandoffDone))
            advanceUntilIdle()
            loop.awaitPhase(Phase.Done)
            assertEquals(listOf(back to null), screen.actions.take(1))
            loop.stop(StopCause.USER)
            loop.awaitStopped()

            val later = FakeChatModel().answers("好了")
            val resumed = assembled(later, ScriptedScreen(Screens.CHAT), harness.storage).resume()
            resumed.followup(text("继续"))
            advanceUntilIdle()

            assertEquals(emptyList<Int>(), leaks(model) + leaks(later))
            assertEquals(1, later.requests.size)
        }

    @Test
    fun `a screen withheld by a confirm or a hand-off gets the note alone, and a result showing no screen none`() =
        runTest {
            // A screen judgment never asks anybody: any verdict but Allow only withholds.
            val policy =
                dev.phoneagent.core.policy.PolicyEngine { call, _, _ ->
                    if (call.name ==
                        dev.phoneagent.core.policy.SCREEN_JUDGMENT_TOOL
                    ) {
                        PolicySamples.handOff
                    } else {
                        Verdict.Allow
                    }
                }
            val look =
                dev.phoneagent.core.testing.FakeTool("look") { _, ctx ->
                    val seen = ctx.screen!!.snapshot()
                    ToolResult(
                        ToolStatus.OK,
                        listOf(Part.Text("看了")),
                        buildJsonObject { put(ToolResult.SCREEN, seen.id.value) },
                    )
                }
            val blind =
                dev.phoneagent.core.testing.FakeTool(
                    "blind",
                ) { _, ctx -> ToolResult.ok("看了 ${ctx.screen!!.snapshot().id}") }
            val model = FakeChatModel().calls(call("c0", "look")).calls(call("c1", "blind")).answers("好")
            val harness =
                LoopHarness(
                    this,
                    model,
                    tools = listOf(look, blind),
                    policy = policy,
                    screen = ScriptedScreen(chat("s1")),
                )
            val loop = harness.start()
            loop.followup(text("看"))
            loop.awaitPhase(Phase.Done)

            assertEquals("看了\n" + texts.screenWithheld, harness.resultOf("c0").text())
            assertEquals("看了 s1", harness.resultOf("c1").text(), "only a result naming the screen gets the note")
            assertTrue(harness.events().only<dev.phoneagent.core.session.HandoffStarted>().isEmpty(), "nobody is asked")
        }

    @Test
    fun `a result that toolPostExecute strips of its screen makes that screen unseen for later diffs`() =
        runTest {
            val strip =
                Interceptor<PostExecuteInput, PostExecuteResult> { input, chain ->
                    val post = chain.proceed(input)
                    PostExecuteResult(post.result.copy(details = null), post.context)
                }
            val screen =
                ScriptedScreen(chat("s12", "MARK_STRIPPED"))
                    .answers(
                        ActionOutcome(Status.Ok, Effect.CHANGED, chat("s13"), against(chat("s12", "MARK_STRIPPED"))),
                    )
            val model =
                FakeChatModel()
                    .calls(
                        call("c0", "phone_observe"),
                    ).calls(call("c1", "phone_key", BACK))
                    .answers("好")
            val harness =
                LoopHarness(
                    this,
                    model,
                    tools = tools,
                    policy = rules.engine,
                    intents = rules.resolver,
                    screen = screen,
                    interceptors = Interceptors(requestError = emptyList(), toolPostExecute = listOf(strip)),
                )
            val loop = harness.start()
            loop.followup(text("看"))
            loop.awaitPhase(Phase.Done)

            val tapped = harness.resultOf("c1").text()
            assertTrue(tapped.startsWith("已按返回键。\nscreen s13 · com.tencent.mm") && "MARK" !in tapped, tapped)
        }

    /** The chat page, whose next action ends [status] with [after]. */
    private fun answering(
        status: Status,
        after: ScreenSnapshot?,
    ): ScriptedScreen = ScriptedScreen(Screens.CHAT).answers(ActionOutcome(status, Effect.UNKNOWN, after))

    /** A diff against [base] that says its OCR lines are gone. */
    private fun against(base: ScreenSnapshot): ScreenDiff = ScreenDiff(base.id, ocrRemoved = base.ocr.orEmpty())

    /**
     * [ScriptedScreen] on [first] that throws [failure] from its action or launch, its wait, or
     * its reads after the first [snapshotAfter].
     */
    private class Throwing(
        first: ScreenSnapshot,
        private val failure: ScreenUnavailableException,
        private val act: Boolean = false,
        private val awaitChange: Boolean = false,
        private val snapshotAfter: Int = Int.MAX_VALUE,
    ) : ScreenPort by ScriptedScreen(first) {
        private val scripted = ScriptedScreen(first)
        private var reads = 0

        override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot {
            if (reads++ >= snapshotAfter) throw failure
            return scripted.snapshot(options)
        }

        override suspend fun act(
            action: UiAction,
            on: SnapshotId?,
        ): ActionOutcome = if (act) throw failure else scripted.act(action, on)

        override suspend fun launch(target: LaunchTarget): ActionOutcome {
            if (act) throw failure
            return scripted.launch(target)
        }

        override suspend fun awaitChange(timeoutMs: Long): Boolean {
            if (awaitChange) throw failure
            return scripted.awaitChange(timeoutMs)
        }
    }
}
