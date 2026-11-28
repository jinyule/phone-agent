package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.LogRecords.answered
import dev.phoneagent.core.agent.LogRecords.log
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.Status
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.BlobStore
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.JudgmentPurpose
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** A call's screen records what it saw and did (phase1 §3.9), and shows the tool only what was judged shown (§6.4). */
class RecordingScreenTest {
    private val c1 = ToolCallId("c1")
    private val call =
        ToolCall(c1, LoopHarness.TASK, LoopHarness.SESSION, "phone_tap", JsonObject(emptyMap()), reason = "看看")
    private val s1 = ScriptedScreen.snapshotOf("s1", ocr = listOf(OcrLine("MARK_S1", BOX)))
    private val s2 = ScriptedScreen.snapshotOf("s2", ocr = listOf(OcrLine("MARK_S2", BOX)))
    private val port = ScriptedScreen(s1, s2)
    private val blobs: BlobStore = SessionFiles(InMemoryStoragePort(), StoragePath("sessions/s1")).blobs
    private val log = MemoryLog()
    private val recorded: List<SessionEvent> get() = log.events.drop(1)
    private val resolver = IntentResolver { _, _, seen -> ActionIntent(IntentKind.READ, seen?.pkg) }
    private val refuse = Verdict.Refuse("hard.finance_app", "这个 App 不允许智能体操作。")

    private fun screen(
        port: ScreenPort = this.port,
        policy: PolicyEngine = ScriptedPolicy(),
    ) = RecordingScreen(port, call, blobs, ScreenGate(resolver, policy), WITHHELD_TEXT, log)

    private val screen = screen()

    /** An in-memory log that starts with `task/start` and can fail on one type of event. */
    private class MemoryLog : ScreenLog {
        val events = mutableListOf<SessionEvent>(TaskStarted(SecurityLevel.STANDARD, unattended = false))
        var failOn: Class<*>? = null

        override suspend fun record(event: SessionEvent) {
            if (failOn?.isInstance(event) == true) throw IOException("log full")
            events += event
        }

        override suspend fun records(): List<SessionRecord> = log(*events.toTypedArray())
    }

    private fun judged(
        id: String,
        outcome: JudgmentOutcome = JudgmentOutcome.SHOWN,
        rule: String? = null,
        hint: String? = null,
        purpose: JudgmentPurpose = JudgmentPurpose.TOOL,
    ) = ScreenJudged(c1, SnapshotId(id), purpose, outcome, rule, hint)

    /** Screen [id] with one button [ref] at [e1], and [mark] read on it by OCR. */
    private fun boxed(
        id: String,
        e1: Bounds,
        ref: String = "e1",
        mark: String? = null,
    ) = ScriptedScreen.snapshotOf(
        id,
        elements = listOf(el(ref, ElementRole.BUTTON, "继续", e1)),
        ocr = mark?.let { listOf(OcrLine(it, BOX)) },
    )

    /** Withholds the screens with [mark] in their OCR; allows the rest. */
    private fun withholding(mark: String) =
        PolicyEngine { _, _, ctx ->
            if (ctx.screen
                    ?.ocr
                    .orEmpty()
                    .any { it.text == mark }
            ) {
                refuse
            } else {
                Verdict.Allow
            }
        }

    /** The screen an observation or a judgment is of. */
    private fun SessionEvent.screenId(): SnapshotId? =
        (this as? ScreenObserved)?.snapshot?.id ?: (this as? ScreenJudged)?.snapshot

    @Test
    fun `observations, actions and launches are recorded with the call, each read-back judged after it`() =
        runTest {
            val seen = screen.snapshot()
            val back = UiAction.Key(SystemKey.BACK)
            val acted = screen.act(back, on = SnapshotId("s1"))
            val target = LaunchTarget.App("com.example.chat")
            val launched = screen.launch(target)
            assertEquals(
                listOf(
                    ScreenObserved(c1, seen),
                    judged("s1"),
                    ScreenActed(c1, SnapshotId("s1"), back, acted),
                    judged("s2"),
                    ScreenLaunched(c1, target, launched),
                    judged("s2"),
                ),
                recorded,
            )
            assertEquals(listOf(back to SnapshotId("s1")), port.actions)
            assertEquals(listOf<LaunchTarget>(target), port.launches)
            assertSame(ScriptedScreen.SURFACE, screen.surface)
        }

    @Test
    fun `a screen is judged on itself and the log with its full record in it, as phone_observe of the call`() =
        runTest {
            val earlier = ScriptedScreen.snapshotOf("s0")
            log.events += ScreenObserved(ToolCallId("c0"), earlier)
            log.events += answered("c0", shows("s0"))
            val resolvedOn = mutableListOf<ScreenSnapshot?>()
            val resolver =
                IntentResolver { _, _, seen ->
                    ActionIntent(IntentKind.READ, seen?.pkg).also {
                        resolvedOn +=
                            seen
                    }
                }
            val asked = mutableListOf<Pair<String, List<SessionEvent>>>()
            val policy =
                PolicyEngine { judgedCall, _, ctx ->
                    asked += judgedCall.name to log.events.toList()
                    assertEquals(s1, ctx.screen, "not the earlier screen the policy judges calls on")
                    Verdict.Allow
                }

            val seen = RecordingScreen(port, call, blobs, ScreenGate(resolver, policy), WITHHELD_TEXT, log).snapshot()

            assertEquals(s1, seen)
            assertEquals(listOf<ScreenSnapshot?>(s1), resolvedOn)
            assertEquals("phone_observe", asked.single().first)
            assertEquals(
                ScreenObserved(c1, s1),
                asked.single().second.last(),
                "judged after the full screen is recorded",
            )
        }

    @Test
    fun `a withheld observation is recorded in full, judged withheld, and given to the tool without content`() =
        runTest {
            val seen = screen(policy = ScriptedPolicy(refuse)).snapshot()

            assertEquals(s1.withheld(), seen)
            assertEquals(
                listOf(
                    ScreenObserved(c1, s1),
                    judged("s1", JudgmentOutcome.WITHHELD, refuse.reason, refuse.hintForModel),
                ),
                recorded,
            )
        }

    @Test
    fun `a confirm or a hand-off on a read withholds it, recorded by kind`() =
        runTest {
            val asked =
                screen(
                    policy =
                        ScriptedPolicy(
                            Verdict.Confirm(PolicySamples.sendCard),
                            PolicySamples.handOff,
                            judgesScreens = true,
                        ),
                )

            assertEquals(s1.withheld(), asked.snapshot())
            assertEquals(s2.withheld(), asked.snapshot())
            assertEquals(
                listOf(
                    judged("s1", JudgmentOutcome.WITHHELD, "confirm"),
                    judged("s2", JudgmentOutcome.WITHHELD, "hand_off"),
                ),
                recorded.filterIsInstance<ScreenJudged>(),
            )
        }

    @Test
    fun `an action whose read-back is withheld gives the tool the copy, no diff and no reason of the port`() =
        runTest {
            val diff = ScreenDiff(SnapshotId("s1"), ocrRemoved = s1.ocr!!)
            val statuses =
                listOf(
                    Status.Ok to Status.Ok,
                    Status.Failed("MARK_FAILED") to Status.Failed(WITHHELD_TEXT),
                    Status.Refused("MARK_REFUSED") to Status.Refused(WITHHELD_TEXT),
                    Status.StaleRef(
                        ActionOutcome.StaleReason.MOVED,
                    ) to Status.StaleRef(ActionOutcome.StaleReason.MOVED),
                )
            val wrong = mutableListOf<String>()
            for ((status, shown) in statuses) {
                val outcome = ActionOutcome(status, Effect.CHANGED, s2, diff)
                val acted =
                    screen(
                        ScriptedScreen(s1).answers(outcome),
                        ScriptedPolicy(refuse),
                    ).act(UiAction.Key(SystemKey.BACK), null)
                val expected = ActionOutcome(shown, Effect.CHANGED, s2.withheld(), diff = null)
                if (acted != expected) wrong += "$status: $acted"
            }
            assertEquals(emptyList<String>(), wrong)
            assertTrue(
                log.events.filterIsInstance<ScreenActed>().all {
                    it.outcome.after == s2 &&
                        it.outcome.diff == diff
                },
                "logged in full",
            )
        }

    @Test
    fun `a launch whose read-back is withheld is given the same way`() =
        runTest {
            val outcome = ActionOutcome(Status.Failed("MARK_FAILED"), Effect.NEW_APP, s2, ScreenDiff(SnapshotId("s1")))
            val launched =
                screen(
                    ScriptedScreen(s1).answers(outcome),
                    ScriptedPolicy(refuse),
                ).launch(LaunchTarget.Link("https://bank.example"))

            assertEquals(ActionOutcome(Status.Failed(WITHHELD_TEXT), Effect.NEW_APP, s2.withheld(), null), launched)
            assertEquals(ScreenLaunched(c1, LaunchTarget.Link("https://bank.example"), outcome), recorded.first())
        }

    @Test
    fun `without a read-back the port's reason is replaced and nothing is judged`() =
        runTest {
            val wrong = mutableListOf<String>()
            for ((status, shown) in listOf(
                Status.Failed("MARK_FAILED") to Status.Failed(WITHHELD_TEXT),
                Status.Refused("MARK_REFUSED") to Status.Refused(WITHHELD_TEXT),
                Status.Ok to Status.Ok,
            )) {
                val outcome = ActionOutcome(status, Effect.UNKNOWN, after = null)
                val acted = screen(ScriptedScreen(s1).answers(outcome)).act(UiAction.Key(SystemKey.BACK), null)
                if (acted != ActionOutcome(shown, Effect.UNKNOWN)) wrong += "$status: $acted"
            }
            assertEquals(emptyList<String>(), wrong)
            assertTrue(recorded.none { it is ScreenJudged })
        }

    @Test
    fun `a shown read-back keeps the port's reason, and its diff when the model saw the base`() =
        runTest {
            log.events += ScreenObserved(ToolCallId("c0"), s1)
            log.events += ScreenJudged(ToolCallId("c0"), s1.id, JudgmentPurpose.TOOL, JudgmentOutcome.SHOWN)
            log.events += answered("c0", shows("s1"))
            val diff = ScreenDiff(SnapshotId("s1"), ocrRemoved = s1.ocr!!)
            val outcome = ActionOutcome(Status.Failed("MARK_FAILED"), Effect.CHANGED, s2, diff)

            val acted = screen(ScriptedScreen(s1).answers(outcome)).act(UiAction.Key(SystemKey.BACK), SnapshotId("s1"))

            assertEquals(outcome, acted)
        }

    @Test
    fun `a diff against a screen the model did not see is dropped`() =
        runTest {
            // c0 read s1 and it was shown, but its result named another screen; c9 was never judged.
            log.events += ScreenJudged(ToolCallId("c0"), s1.id, JudgmentPurpose.TOOL, JudgmentOutcome.SHOWN)
            log.events += answered("c0", shows("s0"))
            for (base in listOf("s1", "s9")) {
                val outcome =
                    ActionOutcome(Status.Ok, Effect.CHANGED, s2, ScreenDiff(SnapshotId(base), ocrRemoved = s1.ocr!!))
                val acted =
                    screen(
                        ScriptedScreen(s1).answers(outcome),
                    ).act(UiAction.Key(SystemKey.BACK), SnapshotId(base))
                assertEquals(outcome.copy(diff = null), acted, base)
            }
        }

    @Test
    fun `a screen the port cannot use reaches the tool with a fixed message and no cause, whatever was read`() =
        runTest {
            val cause = IllegalStateException("MARK_CAUSE")
            val failure = ScreenUnavailableException("MARK_MESSAGE on 微信", cause)
            val broken =
                object : ScreenPort by port {
                    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot = throw failure

                    override suspend fun act(
                        action: UiAction,
                        on: SnapshotId?,
                    ): ActionOutcome = throw failure

                    override suspend fun launch(target: LaunchTarget): ActionOutcome = throw failure

                    override suspend fun frame(
                        maxEdge: Int,
                        som: Boolean,
                    ): Frame? = throw failure

                    override suspend fun awaitChange(timeoutMs: Long): Boolean = throw failure
                }
            val screen = screen(broken)
            val calls: List<Pair<String, suspend () -> Any?>> =
                listOf(
                    "snapshot" to { screen.snapshot() },
                    "act" to { screen.act(UiAction.Key(SystemKey.BACK), SnapshotId("s1")) },
                    "launch" to { screen.launch(LaunchTarget.App("com.tencent.mm")) },
                    "framedSnapshot" to { screen.framedSnapshot(SnapshotOptions(), maxEdge = 720, som = false) },
                    "awaitChange" to { screen.awaitChange(100) },
                )
            val wrong = mutableListOf<String>()
            for ((name, run) in calls) {
                val thrown =
                    try {
                        run()
                        null
                    } catch (expected: ScreenUnavailableException) {
                        expected
                    }
                val fixed =
                    thrown is ScreenUnavailableException && thrown.message == WITHHELD_TEXT && thrown.cause == null
                if (!fixed || thrown === failure) wrong += "$name: $thrown, cause ${thrown?.cause}"
            }
            assertEquals(emptyList<String>(), wrong)
            val outcome =
                ActionOutcome(Status.Failed("threw ${ScreenUnavailableException::class.java.name}"), Effect.UNKNOWN)
            assertEquals(
                listOf(
                    ScreenActed(c1, SnapshotId("s1"), UiAction.Key(SystemKey.BACK), outcome),
                    ScreenLaunched(c1, LaunchTarget.App("com.tencent.mm"), outcome),
                ),
                recorded,
            )
        }

    @Test
    fun `a judgment the log cannot take leaves the tool without the screen`() =
        runTest {
            log.failOn = ScreenJudged::class.java

            assertThrows<IOException> { screen.snapshot() }
            assertThrows<IOException> { screen.act(UiAction.Key(SystemKey.BACK), null) }
            assertEquals(listOf(ScreenObserved::class, ScreenActed::class), recorded.map { it::class })
        }

    @Test
    fun `a cancellation before the judgment is recorded leaves the tool without the screen, and it unjudged`() =
        runTest {
            val cancelling =
                object : ScreenLog by log {
                    override suspend fun record(event: SessionEvent) {
                        if (event is ScreenJudged) throw CancellationException("stopped")
                        log.record(event)
                    }
                }
            val screen =
                RecordingScreen(port, call, blobs, ScreenGate(resolver, ScriptedPolicy()), WITHHELD_TEXT, cancelling)

            assertThrows<CancellationException> { screen.snapshot() }
            assertEquals(listOf(ScreenObserved(c1, s1)), recorded, "recorded in full, never judged")
        }

    @Test
    fun `a frame goes with the screen read after it, stored and recorded after both screens`() =
        runTest {
            val first = boxed("s1", e1 = BOX)
            val second = boxed("s2", e1 = BOX).copy(ocr = listOf(OcrLine("later", BOX)))
            val port = ScriptedScreen(first, second).apply { frame = PNG }

            val framed = screen(port).framedSnapshot(SnapshotOptions(SnapshotDetail.FULL), maxEdge = 720, som = true)

            assertEquals(second.copy(detail = SnapshotDetail.FULL), framed.snapshot)
            assertSame(PNG, framed.frame)
            assertEquals(listOf(720 to true), port.frameRequests)
            val blob = BlobStore.refOf(PNG.bytes)
            assertEquals(
                listOf(
                    ScreenObserved(c1, first.copy(detail = SnapshotDetail.FULL)),
                    judged("s1", purpose = JudgmentPurpose.FRAME_PRECHECK),
                    ScreenObserved(c1, second.copy(detail = SnapshotDetail.FULL)),
                    judged("s2"),
                    ScreenFrame(blob, "image/png", 10, 20, som = true, SnapshotId("s1"), checkedOn = SnapshotId("s2")),
                ),
                recorded,
            )
            assertArrayEquals(PNG.bytes, blobs.get(blob))
        }

    @Test
    fun `a frame that does not fit the screen read after it is dropped, unstored and unrecorded`() =
        runTest {
            val first = boxed("s1", e1 = BOX)
            val moved = Bounds(0, 20, 10, 30)
            val cases =
                listOf(
                    Triple("another app", PNG, boxed("s2", e1 = BOX).copy(pkg = "com.tencent.mm")),
                    Triple("withheld", PNG, boxed("s2", e1 = BOX, mark = "MARK_S2")),
                    Triple("a box moved", PNG, boxed("s2", e1 = moved)),
                    Triple("a box renamed", PNG, boxed("s2", e1 = BOX, ref = "e3")),
                    Triple("an image the log does not take", frame(mime = "image/gif"), boxed("s2", e1 = BOX)),
                    Triple("nothing captured", null, boxed("s2", e1 = BOX)),
                )
            val wrong = mutableListOf<String>()
            for ((case, frame, second) in cases) {
                val log = MemoryLog()
                val files = SessionFiles(InMemoryStoragePort(), StoragePath("sessions/" + case.replace(' ', '-'))).blobs
                val port = ScriptedScreen(first, second).apply { this.frame = frame }
                val gate = ScreenGate(resolver, withholding("MARK_S2"))
                val framed =
                    RecordingScreen(port, call, files, gate, WITHHELD_TEXT, log)
                        .framedSnapshot(SnapshotOptions(), maxEdge = 720, som = true)
                val shown = if (case == "withheld") second.withheld() else second
                val stored = frame?.let { files.get(BlobStore.refOf(it.bytes)) }
                val dropped = framed.frame == null && stored == null && log.events.none { it is ScreenFrame }
                if (framed.snapshot != shown ||
                    !dropped
                ) {
                    wrong += "$case: ${framed.snapshot.id} ${framed.frame}, ${log.events}"
                }
            }
            assertEquals(emptyList<String>(), wrong)
        }

    @Test
    fun `a frame without boxes needs only the same app, and any image the log takes is kept`() =
        runTest {
            val wrong = mutableListOf<String>()
            for (mime in Part.IMAGE_MIMES) {
                val frame = frame(mime = mime, som = false)
                val port = ScriptedScreen(boxed("s1", e1 = BOX), boxed("s2", e1 = Bounds(0, 20, 10, 30), ref = "e3"))
                port.frame = frame
                val framed = screen(port).framedSnapshot(SnapshotOptions(), maxEdge = 720, som = false)
                if (framed.frame !== frame) wrong += "$mime: ${framed.frame}"
            }
            assertEquals(emptyList<String>(), wrong)
        }

    @Test
    fun `a capture the port cannot make is no frame, and a cancellation goes on`() =
        runTest {
            val failing =
                object : ScreenPort by port {
                    override suspend fun frame(
                        maxEdge: Int,
                        som: Boolean,
                    ): Frame = throw ScreenUnavailableException("MARK_CAPTURE")
                }
            val framed = screen(failing).framedSnapshot(SnapshotOptions(), maxEdge = 720, som = false)
            assertEquals(s2, framed.snapshot)
            assertNull(framed.frame)
            assertEquals(listOf(s1.id, s1.id, s2.id, s2.id), recorded.map { it.screenId() })

            val stopping =
                object : ScreenPort by ScriptedScreen(s1, s2) {
                    override suspend fun frame(
                        maxEdge: Int,
                        som: Boolean,
                    ): Frame = throw CancellationException("stopped")
                }
            assertThrows<CancellationException> { screen(stopping).framedSnapshot(SnapshotOptions(), 720, false) }
        }

    @Test
    fun `a withheld first screen captures nothing and reads nothing after it`() =
        runTest {
            port.frame = PNG

            val framed =
                screen(policy = withholding("MARK_S1")).framedSnapshot(SnapshotOptions(), maxEdge = 720, som = true)

            assertEquals(s1.withheld(), framed.snapshot)
            assertNull(framed.frame)
            assertEquals(emptyList<Pair<Int, Boolean>>(), port.frameRequests)
            assertEquals(1, port.snapshotOptions.size)
            assertEquals(
                listOf(
                    ScreenObserved(c1, s1),
                    judged(
                        "s1",
                        JudgmentOutcome.WITHHELD,
                        refuse.reason,
                        refuse.hintForModel,
                        JudgmentPurpose.FRAME_PRECHECK,
                    ),
                ),
                recorded,
            )
        }

    @Test
    fun `a second screen that cannot be read drops the frame and reaches the tool with a fixed message`() =
        runTest {
            var reads = 0
            val cause = IllegalStateException("MARK_CAUSE")
            val lost =
                object : ScreenPort by port {
                    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot =
                        if (reads++ == 0) port.snapshot(options) else throw ScreenUnavailableException("MARK_S2", cause)
                }
            port.frame = PNG

            val thrown =
                assertThrows<ScreenUnavailableException> { screen(lost).framedSnapshot(SnapshotOptions(), 720, true) }

            assertEquals(WITHHELD_TEXT to null, thrown.message to thrown.cause)
            assertNull(blobs.get(BlobStore.refOf(PNG.bytes)))
            assertEquals(listOf(ScreenObserved::class, ScreenJudged::class), recorded.map { it::class })
        }

    @Test
    fun `a tool's own frame call captures nothing`() =
        runTest {
            port.frame = PNG
            assertNull(screen.frame(maxEdge = 720, som = true))
            assertEquals(emptyList<Pair<Int, Boolean>>(), port.frameRequests)
            assertEquals(emptyList<SessionEvent>(), recorded)
        }

    @Test
    fun `an action or a launch that throws is recorded as failed with the class only, and the exception goes on`() =
        runTest {
            val failure = IllegalStateException("display 7 gone")
            val broken =
                object : ScreenPort by port {
                    override suspend fun act(
                        action: UiAction,
                        on: SnapshotId?,
                    ): ActionOutcome = throw failure

                    override suspend fun launch(target: LaunchTarget): ActionOutcome = throw failure
                }
            val screen = screen(broken)
            val back = UiAction.Key(SystemKey.BACK)
            val target = LaunchTarget.App("com.example.chat")

            assertSame(failure, assertThrows<IllegalStateException> { screen.act(back, on = SnapshotId("s1")) })
            assertSame(failure, assertThrows<IllegalStateException> { screen.launch(target) })
            val outcome =
                ActionOutcome(
                    Status.Failed("threw ${IllegalStateException::class.java.name}"),
                    Effect.UNKNOWN,
                    after = null,
                )
            assertEquals(
                listOf(ScreenActed(c1, SnapshotId("s1"), back, outcome), ScreenLaunched(c1, target, outcome)),
                recorded,
            )
        }

    @Test
    fun `an error from the port (a bug) is recorded as unfinished and goes on`() =
        runTest {
            val bug = AssertionError("port bug")
            val broken =
                object : ScreenPort by port {
                    override suspend fun act(
                        action: UiAction,
                        on: SnapshotId?,
                    ): ActionOutcome = throw bug
                }
            val screen = screen(broken)

            val back = UiAction.Key(SystemKey.BACK)
            assertSame(bug, assertThrows<AssertionError> { screen.act(back, on = null) })
            val unfinished = ActionOutcome(Status.Failed("did not finish"), Effect.UNKNOWN)
            assertEquals(listOf(ScreenActed(c1, null, back, unfinished)), recorded)
        }

    @Test
    fun `waiting for a change records nothing`() =
        runTest {
            port.changes = false
            assertFalse(screen.awaitChange(timeoutMs = 100))
            assertEquals(emptyList<SessionEvent>(), recorded)
        }

    @Test
    fun `the screens the model saw are those judged shown for the tool and named by the same call's result`() {
        val c0 = ToolCallId("c0")
        val records =
            log(
                TaskStarted(SecurityLevel.STANDARD, unattended = false),
                ScreenJudged(c0, SnapshotId("s1"), JudgmentPurpose.TOOL, JudgmentOutcome.SHOWN),
                ScreenJudged(c0, SnapshotId("s2"), JudgmentPurpose.TOOL, JudgmentOutcome.SHOWN),
                answered("c0", shows("s2")),
                // Named by its result but withheld, or only read before a capture.
                ScreenJudged(c1, SnapshotId("s3"), JudgmentPurpose.TOOL, JudgmentOutcome.WITHHELD, "r"),
                answered("c1", shows("s3")),
                ScreenJudged(ToolCallId("c2"), SnapshotId("s4"), JudgmentPurpose.FRAME_PRECHECK, JudgmentOutcome.SHOWN),
                answered("c2", shows("s4")),
                // Shown, but named by another call's result.
                ScreenJudged(ToolCallId("c3"), SnapshotId("s5"), JudgmentPurpose.TOOL, JudgmentOutcome.SHOWN),
                answered("c4", shows("s5")),
                // A result that names no screen, or not as a string.
                answered("c5", ToolResult.ok("x")),
                answered("c6", ToolResult(ToolStatus.OK, emptyList(), buildJsonObject { put(ToolResult.SCREEN, 5) })),
            )
        assertEquals(setOf(SnapshotId("s2")), seenScreens(records))
    }

    @Test
    fun `the withheld judgment of a result's screen is found only in its own call`() {
        val records =
            log(
                TaskStarted(SecurityLevel.STANDARD, unattended = false),
                ScreenJudged(ToolCallId("c0"), SnapshotId("s3"), JudgmentPurpose.TOOL, JudgmentOutcome.WITHHELD, "r0"),
                ScreenJudged(c1, SnapshotId("s2"), JudgmentPurpose.TOOL, JudgmentOutcome.WITHHELD, "r1"),
                ScreenJudged(c1, SnapshotId("s3"), JudgmentPurpose.TOOL, JudgmentOutcome.SHOWN),
                ScreenJudged(
                    c1,
                    SnapshotId("s4"),
                    JudgmentPurpose.FRAME_PRECHECK,
                    JudgmentOutcome.WITHHELD,
                    "r4",
                    "h4",
                ),
            )
        assertEquals("r1", withheldShown(records, c1, shows("s2"))?.rule)
        assertNull(withheldShown(records, c1, shows("s3")), "shown in this call, withheld only in another")
        assertEquals("h4", withheldShown(records, c1, shows("s4"))?.hint, "the read before a capture counts")
        assertNull(withheldShown(records, c1, ToolResult.ok("no screen")))
        assertNull(withheldShown(records, ToolCallId("c9"), shows("s2")))
    }

    private fun shows(id: String): ToolResult =
        ToolResult(ToolStatus.OK, emptyList(), buildJsonObject { put(ToolResult.SCREEN, id) })

    private companion object {
        const val WITHHELD_TEXT = "（不显示）"
        val BOX = Bounds(0, 0, 10, 10)
        val PNG = frame()

        fun frame(
            mime: String = "image/png",
            som: Boolean = true,
        ) = Frame(byteArrayOf(1, 2), mime, 10, 20, som)
    }
}
