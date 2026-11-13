@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.rules.PolicyRules
import dev.phoneagent.core.policy.rules.Refusal
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.screen.ScreenComponents
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.BlobStore
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tools.PhaseOneTools
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `phone_observe` with a frame (policy-rules §6.4), with the WP05 rules and the phase-1 tools of a
 * model that reads images: the page shown is the screen read after the capture (S2), the frame
 * goes with it only when it fits, and the next call is judged on S2. What must not show is a
 * marker (`MARK…`), checked on the requests the model got.
 */
class AgentLoopFramedObserveTest {
    private val rules = PolicyRules(setOf("dev.phoneagent"), setOf("PhoneAgent"))
    private val tools = PhaseOneTools.create(ScreenComponents(lexicon = rules.lexicon, vision = true).presenter)
    private val texts = LoopTexts()
    private val frame = Frame(byteArrayOf(7, 7, 7), "image/png", 10, 20, som = true)

    private companion object {
        const val IMAGE = """{"image":true,"reason":"看"}"""
    }

    /** The chat page as [id], its send button (e10) at the same place labelled [label]. */
    private fun chat(
        id: String,
        label: String = "发送",
    ): ScreenSnapshot =
        Screens.CHAT.copy(
            id = SnapshotId(id),
            elements = Screens.CHAT_ELEMENTS.map { if (it.ref.value == "e10") it.copy(name = label) else it },
        )

    /** A settings page as [id]; the developer options are refused to the model. */
    private fun settings(
        id: String,
        developer: Boolean = false,
    ): ScreenSnapshot {
        val label = if (developer) "MARK_DEV USB 调试" else "显示"
        val activity = if (developer) "DevelopmentSettingsActivity" else "Settings"
        return Screens
            .snapshot(id, listOf(el("e1", ElementRole.TEXT, label, box(40, 200, 700, 260))))
            .copy(pkg = "com.android.settings", activity = "com.android.settings.$activity")
    }

    /** The chat page as [id] with its send button (e10) boxed elsewhere than on [chat]. */
    private fun boxMoved(id: String): ScreenSnapshot {
        val elsewhere = box(880, 860, 980, 910)
        return chat(id).let { page ->
            page.copy(elements = page.elements.map { if (it.ref.value == "e10") it.copy(bounds = elsewhere) else it })
        }
    }

    /** A port reading [reads] in order, capturing [frame]. */
    private fun framing(vararg reads: ScreenSnapshot): ScriptedScreen = ScriptedScreen(*reads).also { it.frame = frame }

    private fun TestScope.assembled(
        model: FakeChatModel,
        screen: ScreenPort,
        policy: PolicyEngine = rules.engine,
    ): LoopHarness = LoopHarness(this, model, tools = tools, policy = policy, intents = rules.resolver, screen = screen)

    /** Runs one observation with a frame on [screen] until the task is done. */
    private suspend fun TestScope.observed(screen: ScreenPort): Pair<LoopHarness, FakeChatModel> {
        val model = FakeChatModel().calls(call("c0", "phone_observe", IMAGE)).answers("好")
        val harness = assembled(model, screen)
        val loop = harness.start()
        loop.followup(text("看看"))
        advanceUntilIdle()
        loop.awaitPhase(Phase.Done)
        return harness to model
    }

    private suspend fun LoopHarness.resultOf(id: String): ToolResult =
        events().only<ToolResultRecorded>().single { it.callId.value == id }.result

    private fun ToolResult.detailOf(key: String): String? = (details?.get(key) as? JsonPrimitive)?.content

    private fun ToolResult.shown(): String = content.filterIsInstance<Part.Text>().joinToString("\n") { it.text }

    private fun leaks(model: FakeChatModel): List<Int> =
        model.requests.indices.filter { "MARK" in model.requests[it].toString() }

    @Test
    fun `the page shown with a frame is the screen read after it, and the next call is judged there`() =
        runTest {
            // At (930,955) the screen read before the capture has a harmless button; after it, 发送.
            val screen = framing(chat("s1", "收起"), chat("s2", "收起"), chat("s3"))
            val model =
                FakeChatModel()
                    .calls(call("c0", "phone_observe", IMAGE))
                    .calls(call("c1", "phone_tap", """{"screen":"s3","x":930,"y":955,"reason":"点这里"}"""))
                    .answers("好")
            val judgedOn = mutableListOf<Pair<String, SnapshotId?>>()
            val policy =
                PolicyEngine { call, intent, ctx ->
                    judgedOn += call.name to ctx.screen?.id
                    rules.engine.evaluate(call, intent, ctx)
                }
            val harness = assembled(model, screen, policy)
            val loop = harness.start()
            loop.followup(text("看看"))
            val prompt = loop.awaitPrompt().promptId!!

            val result = harness.resultOf("c0")
            val image = result.content.filterIsInstance<Part.Image>().single()
            assertEquals(BlobStore.refOf(frame.bytes), image.blob)
            assertArrayEquals(frame.bytes, harness.files.blobs.get(image.blob))
            val text = result.shown()
            assertTrue("screen s3" in text && "发送" in text && "收起" !in text, text)
            assertEquals("s3", result.detailOf(ToolResult.SCREEN))
            assertEquals(
                ScreenFrame(
                    image.blob,
                    "image/png",
                    10,
                    20,
                    som = true,
                    SnapshotId("s2"),
                    checkedOn = SnapshotId("s3"),
                ),
                harness.events().only<ScreenFrame>().single(),
            )
            assertTrue(image.blob.value in model.requests[1].toString(), "the request carries the frame")
            assertEquals(listOf("phone_tap" to SnapshotId("s3")), judgedOn.filter { it.first == "phone_tap" })
            val asked = harness.events().only<ApprovalAsked>().single()
            assertEquals(ToolCallId("c1"), asked.card.callId, "the tap on 发送 asks first")

            assertTrue(loop.answer(prompt, UserReply.Reject))
            advanceUntilIdle()
            loop.awaitPhase(Phase.Done)
            assertEquals(emptyList<Any>(), screen.actions)
        }

    @Test
    fun `a frame goes with no screen read after it that is in another app, withheld or boxed elsewhere`() =
        runTest {
            val moved = boxMoved("s3")
            val cases =
                listOf(
                    Triple("another app", listOf(chat("s1"), chat("s2")), chat("s3").copy(pkg = "com.example.notes")),
                    Triple("sensitive settings", listOf(settings("s1"), settings("s2")), settings("s3", true)),
                    Triple("a box moved", listOf(chat("s1"), chat("s2")), moved),
                )
            val wrong = mutableListOf<String>()
            for ((case, before, after) in cases) {
                val (harness, model) = observed(framing(*(before + after).toTypedArray()))
                val result = harness.resultOf("c0")
                val stored = harness.files.blobs.get(BlobStore.refOf(frame.bytes))
                val text = result.shown()
                val withheld = case == "sensitive settings"
                val said = if (withheld) texts.screenWithheld in text && "（截不到图）" !in text else text.endsWith("（截不到图）")
                val imaged = result.content.any { part -> part is Part.Image }
                val recorded = harness.events().only<ScreenFrame>().isNotEmpty()
                val shows = result.detailOf(ToolResult.SCREEN)
                val problems =
                    listOfNotNull(
                        "an image".takeIf { imaged },
                        "stored".takeIf { stored != null },
                        "recorded".takeIf { recorded },
                        "leaks ${leaks(model)}".takeIf { leaks(model).isNotEmpty() },
                        "shows $shows".takeIf { shows != "s3" },
                        "ends ${text.takeLast(40)}".takeIf { !said },
                    )
                if (problems.isNotEmpty()) wrong += "$case: $problems"
            }
            assertEquals(emptyList<String>(), wrong)
        }

    @Test
    fun `a dropped frame and no frame at all write the same log and give the model the same`() =
        runTest {
            // Session replay gives no frame for a capture whose frame the log did not keep
            // (DeviceScript in the replay tests), so the runtime must not tell the two apart.
            val cases =
                mapOf(
                    "another app" to listOf(chat("s1"), chat("s2"), chat("s3").copy(pkg = "com.example.notes")),
                    "sensitive settings" to listOf(settings("s1"), settings("s2"), settings("s3", developer = true)),
                    "a box moved" to listOf(chat("s1"), chat("s2"), boxMoved("s3")),
                )
            val wrong = mutableListOf<String>()
            for ((case, reads) in cases) {
                val framing = framing(*reads.toTypedArray())
                val (dropped, droppedModel) = observed(framing)
                val (none, noneModel) = observed(ScriptedScreen(*reads.toTypedArray()))
                wrong +=
                    listOfNotNull(
                        "nothing captured".takeIf { framing.frameRequests.isEmpty() },
                        "the logs differ".takeIf { dropped.events() != none.events() },
                        "the results differ".takeIf { dropped.resultOf("c0") != none.resultOf("c0") },
                        "the requests differ".takeIf { droppedModel.requests != noneModel.requests },
                    ).map { "$case: $it" }
            }
            assertEquals(emptyList<String>(), wrong)
        }

    @Test
    fun `a first screen the model may not see is shown withheld with the hint, nothing captured or read after it`() =
        runTest {
            val bank =
                Screens
                    .snapshot("s2", listOf(el("e1", ElementRole.TEXT, "MARK_BALANCE 余额", box(40, 200, 700, 260))))
                    .copy(pkg = "com.icbc")
            val screen = framing(chat("s1"), bank, chat("s3"))

            val (harness, model) = observed(screen)

            val result = harness.resultOf("c0")
            assertEquals(emptyList<Int>(), leaks(model))
            assertEquals("s2", result.detailOf(ToolResult.SCREEN))
            val text = result.shown()
            assertTrue(text.endsWith("\n" + texts.screenWithheld + Refusal.FINANCE_APP.hint), text)
            assertEquals(emptyList<Pair<Int, Boolean>>(), screen.frameRequests, "nothing captured")
            assertEquals(2, screen.snapshotOptions.size, "nothing read after it")
        }

    @Test
    fun `the port's words from a capture or from the screen read after it never reach the model`() =
        runTest {
            val capture = ScreenUnavailableException("MARK_CAPTURE")
            val read = ScreenUnavailableException("MARK_READ")
            val failing =
                object : ScreenPort by ScriptedScreen(chat("s1"), chat("s2"), chat("s3")) {
                    override suspend fun frame(
                        maxEdge: Int,
                        som: Boolean,
                    ): Frame = throw capture
                }
            var reads = 0
            val scripted = ScriptedScreen(chat("s1"), chat("s2"), chat("s3"))
            val lost =
                object : ScreenPort by scripted {
                    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot =
                        if (reads++ < 2) scripted.snapshot(options) else throw read
                }

            val (captured, capturedModel) = observed(failing)
            val (unread, unreadModel) = observed(lost)

            assertEquals(emptyList<Int>(), leaks(capturedModel) + leaks(unreadModel))
            val shown = captured.resultOf("c0").shown()
            assertTrue(shown.endsWith("\n（截不到图）"), shown)
            val failed = unread.resultOf("c0").shown()
            assertTrue(failed.startsWith("屏幕暂时用不了（${texts.reasonWithheld}）"), failed)
            assertTrue(unread.events().only<ScreenFrame>().isEmpty())
        }

    @Test
    fun `a tool asking for a frame on a task without a screen fails as a tool error`() =
        runTest {
            val framing =
                dev.phoneagent.core.testing.FakeTool("framing") { _, ctx ->
                    ToolResult.ok("got ${ctx.framedSnapshot().snapshot.id}")
                }
            val model = FakeChatModel().calls(call("c0", "framing")).answers("好")
            val harness = LoopHarness(this, model, tools = listOf(framing))
            val loop = harness.start()
            loop.followup(text("看"))
            loop.awaitPhase(Phase.Done)

            val result = harness.resultOf("c0")
            assertEquals(dev.phoneagent.core.tool.ToolStatus.ERROR, result.status)
            assertEquals(texts.toolError + IllegalStateException::class.java.name, result.shown())
        }
}
