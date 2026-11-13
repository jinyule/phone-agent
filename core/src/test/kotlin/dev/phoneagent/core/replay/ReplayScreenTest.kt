package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UiElement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayScreenTest {
    private val call = ToolCallId("call-1")
    private val second = ToolCallId("call-2")
    private val tap = UiAction.Tap(Target.Ref(ElementRef("e2")))
    private val after = ReplaySamples.snapshot.copy(id = SnapshotId("s2"))
    private val moved =
        ActionOutcome(
            ActionOutcome.Status.Ok,
            ActionOutcome.Effect.CHANGED,
            after,
            ScreenDiff(ReplaySamples.snapshot.id, removed = listOf(ElementRef("e9"))),
        )
    private val launchTarget = LaunchTarget.App("com.example.chat", cold = false)

    /** The current analysis: a fingerprint that names the element count, and every tree sparse. */
    private val analyzer =
        object : SnapshotAnalyzer {
            override fun fingerprint(
                pkg: String?,
                activity: String?,
                elements: List<UiElement>,
                ocr: List<OcrLine>?,
            ): String = "now-${elements.size}"

            override fun quality(
                elements: List<UiElement>,
                surface: SurfaceRef,
                flags: Set<ScreenFlag>,
            ): TreeQuality = TreeQuality.SPARSE

            override fun diff(
                before: ScreenSnapshot,
                after: ScreenSnapshot,
            ): ScreenDiff = throw AssertionError("replay gives diffs as recorded")
        }

    private var current: ToolCallId? = call

    private fun step(
        op: Int,
        port: DevicePort,
        result: DeviceResult,
        delayMs: Long = 0,
        id: ToolCallId = call,
    ) = DeviceStep(id, op, port, op.toLong(), result, delayMs = delayMs)

    private fun screen(vararg steps: DeviceStep) =
        ReplayScreen(
            ReplaySamples.surface,
            steps.groupBy { it.call },
            mapOf(ReplaySamples.blob to ReplaySamples.frameBytes),
            analyzer,
        ) { current }

    private fun acting(result: DeviceResult = DeviceResult.Outcome(moved)) =
        screen(DeviceStep(call, 1, DevicePort.ACT, 1, result, action = tap, on = ReplaySamples.snapshot.id))

    /** The message of the replay failure [block] ends in; the screen keeps it among its problems. */
    private suspend fun failure(
        screen: ReplayScreen,
        block: suspend () -> Unit,
    ): String {
        val error =
            try {
                block()
                null
            } catch (expected: AssertionError) {
                // The replay failure under test.
                expected
            }
        assertTrue(error != null, "expected a replay failure")
        assertTrue(screen.problems.isNotEmpty())
        return error?.message.orEmpty()
    }

    @Test
    fun `a read returns the recorded tree analyzed by the current analyzer`() =
        runTest {
            val read = screen(step(1, DevicePort.SNAPSHOT, DeviceResult.Snapshot(ReplaySamples.snapshot))).snapshot()
            assertEquals(ReplaySamples.snapshot.copy(fingerprint = "now-1", quality = TreeQuality.SPARSE), read)
        }

    @Test
    fun `a read at the recorded detail returns the recorded tree`() =
        runTest {
            val full = ReplaySamples.snapshot.copy(detail = SnapshotDetail.FULL)
            val read =
                screen(step(1, DevicePort.SNAPSHOT, DeviceResult.Snapshot(full)))
                    .snapshot(SnapshotOptions(detail = SnapshotDetail.FULL))
            assertEquals(SnapshotDetail.FULL, read.detail)
        }

    @Test
    fun `a read at another detail than the recording's fails`() =
        runTest {
            val full = ReplaySamples.snapshot.copy(detail = SnapshotDetail.FULL)
            val screen = screen(step(1, DevicePort.SNAPSHOT, DeviceResult.Snapshot(full)))
            val message = failure(screen) { screen.snapshot(SnapshotOptions(detail = SnapshotDetail.BRIEF)) }
            assertTrue("the recording read FULL detail, replay BRIEF" in message, message)
        }

    @Test
    fun `the surface is the recording's`() {
        assertEquals(ReplaySamples.surface, screen().surface)
    }

    @Test
    fun `an action returns its outcome with the after tree re-analyzed and the diff as recorded`() =
        runTest {
            val outcome = acting().act(tap, ReplaySamples.snapshot.id)
            assertEquals(moved.copy(after = after.copy(fingerprint = "now-1", quality = TreeQuality.SPARSE)), outcome)
        }

    @Test
    fun `another action than the recorded one fails`() =
        runTest {
            val screen = acting()
            val message =
                failure(screen) { screen.act(UiAction.Tap(Target.Ref(ElementRef("e3"))), ReplaySamples.snapshot.id) }
            assertTrue("the recording did" in message, message)
        }

    @Test
    fun `the same action on another snapshot fails`() =
        runTest {
            val screen = acting()
            failure(screen) { screen.act(tap, SnapshotId("s9")) }
        }

    @Test
    fun `a launch of another target fails`() =
        runTest {
            val screen =
                screen(DeviceStep(call, 1, DevicePort.LAUNCH, 1, DeviceResult.Outcome(moved), target = launchTarget))
            failure(screen) { screen.launch(LaunchTarget.App("com.example.mail", cold = false)) }
        }

    @Test
    fun `a launch of the recorded target returns its outcome`() =
        runTest {
            val screen =
                screen(
                    DeviceStep(
                        call,
                        1,
                        DevicePort.LAUNCH,
                        1,
                        DeviceResult.Outcome(moved.copy(after = null)),
                        target = launchTarget,
                    ),
                )
            assertEquals(moved.copy(after = null), screen.launch(launchTarget))
        }

    @Test
    fun `another method than the recorded one fails`() =
        runTest {
            val screen = screen(step(1, DevicePort.SNAPSHOT, DeviceResult.Snapshot(ReplaySamples.snapshot)))
            val message = failure(screen) { screen.frame() }
            assertTrue("the recording called SNAPSHOT, replay FRAME" in message, message)
        }

    @Test
    fun `a port call beyond the recording fails`() =
        runTest {
            val screen = screen()
            assertTrue("no recorded port call left" in failure(screen) { screen.snapshot() })
        }

    @Test
    fun `each call reads its own queue`() =
        runTest {
            val screen =
                screen(
                    step(1, DevicePort.AWAIT_CHANGE, DeviceResult.Changed(true), id = second),
                    step(1, DevicePort.FRAME, DeviceResult.NoFrame),
                )
            current = second
            assertTrue(screen.awaitChange(1_000))
            current = call
            assertNull(screen.frame())
            screen.assertConsumed()
        }

    @Test
    fun `a frame returns the fixture's bytes`() =
        runTest {
            val frame =
                screen(
                    step(1, DevicePort.FRAME, DeviceResult.Frame(ReplaySamples.blob, "image/png", 2, 2, som = true)),
                ).frame(som = true)
            assertArrayEquals(ReplaySamples.frameBytes, frame?.bytes)
            assertEquals(listOf("image/png", 2, 2, true), listOf(frame?.mime, frame?.width, frame?.height, frame?.som))
        }

    @Test
    fun `a wait that saw no change takes its whole timeout`() =
        runTest {
            assertEquals(
                false,
                screen(step(1, DevicePort.AWAIT_CHANGE, DeviceResult.Changed(false), delayMs = 10)).awaitChange(3_000),
            )
            assertEquals(3_000L, currentTime)
        }

    @Test
    fun `a step waits its delay before it returns`() =
        runTest {
            screen(
                step(1, DevicePort.SNAPSHOT, DeviceResult.Snapshot(ReplaySamples.snapshot), delayMs = 700),
            ).snapshot()
            assertEquals(700L, currentTime)
        }

    @Test
    fun `a step that threw throws the recorded exception`() =
        runTest {
            val thrown = Thrown(ScreenUnavailableException::class.java.name, "display released")
            val error =
                assertThrows<ScreenUnavailableException> {
                    screen(step(1, DevicePort.SNAPSHOT, DeviceResult.Throws(thrown))).snapshot()
                }
            assertEquals("display released", error.message)
        }

    @Test
    fun `a step that hung waits until cancelled`() =
        runTest {
            val screen = acting(DeviceResult.Hangs)
            val acting = async { screen.act(tap, ReplaySamples.snapshot.id) }
            advanceUntilIdle()
            assertTrue(acting.isActive)
            acting.cancel()
        }

    @Test
    fun `steps not replayed fail the consumption check`() {
        val message = assertThrows<AssertionError> { acting().assertConsumed() }.message.orEmpty()
        assertTrue("not replayed: call-1 op 1: ACT (seq 1)" in message, message)
    }

    @Test
    fun `a mismatch fails the consumption check too`() =
        runTest {
            val screen = acting()
            failure(screen) { screen.act(tap, SnapshotId("s9")) }
            val message = assertThrows<AssertionError> { screen.assertConsumed() }.message.orEmpty()
            assertTrue("call-1 op 1: the recording did" in message, message)
        }
}
