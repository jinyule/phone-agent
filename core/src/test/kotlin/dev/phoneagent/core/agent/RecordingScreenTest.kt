package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.LogRecords.answered
import dev.phoneagent.core.agent.LogRecords.log
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
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
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
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
    ) = ScreenJudged(c1, SnapshotId(id), JudgmentPurpose.TOOL, outcome, rule, hint)

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
    fun `a screen is judged on the log with its full record in it, as phone_observe of the call`() =
        runTest {
            val asked = mutableListOf<Pair<String, List<SessionEvent>>>()
            val policy =
                PolicyEngine { judgedCall, _, ctx ->
                    asked += judgedCall.name to log.events.toList()
                    assertEquals(s1, ctx.screen)
                    Verdict.Allow
                }

            val seen = screen(policy = policy).snapshot()

            assertEquals(s1, seen)
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
                    "frame" to { screen.frame(maxEdge = 720, som = false) },
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
    fun `an image frame is stored as a blob and recorded, anything else only passed on`() =
        runTest {
            assertEquals(null, screen.frame(maxEdge = 720, som = false))
            port.frame = Frame(byteArrayOf(1, 2), "image/png", 10, 20, som = true)
            val frame = screen.frame(maxEdge = 720, som = true)
            val stored = recorded.single() as ScreenFrame
            assertEquals(ScreenFrame(BlobStore.refOf(byteArrayOf(1, 2)), "image/png", 10, 20, som = true), stored)
            assertArrayEquals(byteArrayOf(1, 2), blobs.get(stored.blob))
            assertSame(port.frame, frame)

            port.frame = Frame(byteArrayOf(3), "image/gif", 10, 20, som = false)
            assertSame(port.frame, screen.frame(maxEdge = 720, som = false))
            assertEquals(1, recorded.size, "a mime the model cannot take is not recorded")
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
    }
}
