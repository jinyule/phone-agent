package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.BlobStore
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.testing.ScriptedScreen
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A call's screen records what it saw and did (phase1 §3.9). */
class RecordingScreenTest {
    private val c1 = ToolCallId("c1")
    private val port = ScriptedScreen(ScriptedScreen.snapshotOf("s1"), ScriptedScreen.snapshotOf("s2"))
    private val blobs: BlobStore = SessionFiles(InMemoryStoragePort(), StoragePath("sessions/s1")).blobs
    private val recorded = mutableListOf<SessionEvent>()
    private val screen = RecordingScreen(port, c1, blobs) { recorded += it }

    @Test
    fun `observations, actions and launches are recorded with the call`() =
        runTest {
            val seen = screen.snapshot()
            val back = UiAction.Key(SystemKey.BACK)
            val acted = screen.act(back, on = SnapshotId("s1"))
            val target = LaunchTarget.App("com.example.chat")
            val launched = screen.launch(target)
            assertEquals(
                listOf(
                    ScreenObserved(c1, seen),
                    ScreenActed(c1, SnapshotId("s1"), back, acted),
                    ScreenLaunched(c1, target, launched),
                ),
                recorded,
            )
            assertEquals(listOf(back to SnapshotId("s1")), port.actions)
            assertEquals(listOf<LaunchTarget>(target), port.launches)
            assertSame(ScriptedScreen.SURFACE, screen.surface)
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
            val failure = ScreenUnavailableException("display 7 gone")
            val broken =
                object : ScreenPort by port {
                    override suspend fun act(
                        action: UiAction,
                        on: SnapshotId?,
                    ): ActionOutcome = throw failure

                    override suspend fun launch(target: LaunchTarget): ActionOutcome = throw failure
                }
            val screen = RecordingScreen(broken, c1, blobs) { recorded += it }
            val back = UiAction.Key(SystemKey.BACK)
            val target = LaunchTarget.App("com.example.chat")

            assertSame(failure, assertThrows<ScreenUnavailableException> { screen.act(back, on = SnapshotId("s1")) })
            assertSame(failure, assertThrows<ScreenUnavailableException> { screen.launch(target) })
            val outcome =
                ActionOutcome(
                    ActionOutcome.Status.Failed("threw ${ScreenUnavailableException::class.java.name}"),
                    ActionOutcome.Effect.UNKNOWN,
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
            val screen = RecordingScreen(broken, c1, blobs) { recorded += it }

            val back = UiAction.Key(SystemKey.BACK)
            assertSame(bug, assertThrows<AssertionError> { screen.act(back, on = null) })
            val unfinished = ActionOutcome(ActionOutcome.Status.Failed("did not finish"), ActionOutcome.Effect.UNKNOWN)
            assertEquals(listOf(ScreenActed(c1, null, back, unfinished)), recorded)
        }

    @Test
    fun `waiting for a change records nothing`() =
        runTest {
            port.changes = false
            assertFalse(screen.awaitChange(timeoutMs = 100))
            assertEquals(emptyList<SessionEvent>(), recorded)
        }
}
