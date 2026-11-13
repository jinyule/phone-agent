package dev.phoneagent.core.replay

import dev.phoneagent.core.agent.RecordingScreen
import dev.phoneagent.core.agent.ScreenGate
import dev.phoneagent.core.agent.ScreenLog
import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.session.BlobStore
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.testing.FakeScreen
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.tool.FramedSnapshot
import dev.phoneagent.core.tool.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * What record mode writes and replay reads agree: port calls go through [FactRecorder] under the
 * runtime's [RecordingScreen], as in a recording; the logged `screen/…` records and the kept facts
 * then give a [ReplayScreen] that answers the same calls the same way. This is also where
 * [DeviceScript]'s spellings of a thrown and a cut-off action meet `RecordingScreen`'s, and where
 * its reading of a framed observe (a capture per judgment that allowed one) meets the runtime's:
 * the same calls run again under `RecordingScreen` on the replay write the same log ([rerun]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingRoundTripTest {
    private val call = ToolCallId("call-1")
    private val toolCall =
        ToolCall(call, TaskId("t-1"), SessionId("session-1"), "phone_observe", JsonObject(emptyMap()), reason = "看看")
    private val tap = UiAction.Tap(Target.Ref(ElementRef("e10")))
    private val device = FakeScreen()
    private val resolver = IntentResolver { _, _, seen -> ActionIntent(IntentKind.READ, seen?.pkg) }
    private val allow = PolicyEngine { _, _, _ -> Verdict.Allow }
    private val checked = Screens.CHAT.copy(id = SnapshotId("s2"))

    private val analyzer =
        object : SnapshotAnalyzer {
            override fun fingerprint(
                pkg: String?,
                activity: String?,
                elements: List<UiElement>,
                ocr: List<OcrLine>?,
            ): String = "fp"

            override fun quality(
                elements: List<UiElement>,
                surface: SurfaceRef,
                flags: Set<ScreenFlag>,
            ): TreeQuality = TreeQuality.GOOD

            override fun diff(
                before: ScreenSnapshot,
                after: ScreenSnapshot,
            ): ScreenDiff = ScreenDiff(before.id)
        }

    /** A log that starts with `task/start`, as the runtime's does. */
    private class Log : ScreenLog {
        val all = mutableListOf(SessionRecord(1, 0, TaskStarted(SecurityLevel.STANDARD, unattended = false)))

        override suspend fun record(event: SessionEvent) {
            all += SessionRecord(all.size + 1L, 0, event)
        }

        override suspend fun records(): List<SessionRecord> = all.toList()
    }

    /** What a recording of some calls wrote: its log, its facts with concrete call ids, its blobs. */
    private data class Recording(
        val records: List<SessionRecord>,
        val override: ReplayOverride,
        val blobs: Map<BlobRef, ByteArray>,
    ) {
        /** The records after `task/start`. */
        val screen: List<SessionRecord> get() = records.drop(1)
    }

    private fun recordingScreen(
        port: ScreenPort,
        log: Log,
        blobs: BlobStore = BlobStore(InMemoryStoragePort(), StoragePath("blobs")),
    ) = RecordingScreen(port, toolCall, blobs, ScreenGate(resolver, allow), WITHHELD, log)

    /** A recording of [calls] on [device]. */
    private suspend fun TestScope.recorded(calls: suspend (RecordingScreen) -> Unit): Recording {
        val log = Log()
        val facts = FactRecorder(device, analyzer, { call }) { currentTime }
        val blobs = BlobStore(InMemoryStoragePort(), StoragePath("blobs"))
        calls(recordingScreen(facts, log, blobs))
        val frames = log.all.mapNotNull { (it.event as? ScreenFrame)?.blob }
        val stored = frames.associateWith { requireNotNull(blobs.get(it)) }
        return Recording(log.all.toList(), ReplayOverride(device = facts.facts), stored)
    }

    private fun replay(
        records: List<SessionRecord>,
        override: ReplayOverride,
        blobs: Map<BlobRef, ByteArray> = emptyMap(),
    ) = ReplayScreen(Screens.AGENT, DeviceScript.of(records, override), blobs, analyzer) { call }

    /**
     * [calls] run again under `RecordingScreen` on the replay of [recording]: the log it writes.
     * Every recorded port call must be made again.
     */
    private suspend fun rerun(
        recording: Recording,
        calls: suspend (RecordingScreen) -> Unit,
    ): List<SessionRecord> {
        val log = Log()
        val replay = replay(recording.records, recording.override, recording.blobs)
        calls(recordingScreen(replay, log))
        replay.assertConsumed()
        return log.all.toList()
    }

    private suspend fun RecordingScreen.framed(): FramedSnapshot =
        framedSnapshot(SnapshotOptions(), MAX_EDGE, som = true)

    /** Reads that give [screens] in turn, the second after [secondDelayMs]. */
    private fun reads(
        vararg screens: ScreenSnapshot,
        secondDelayMs: Long = 0,
    ): suspend () -> ScreenSnapshot {
        val left = ArrayDeque(screens.toList())
        return {
            if (left.size < screens.size) delay(secondDelayMs)
            left.removeFirst()
        }
    }

    private fun FakeScreen.bytes(): ByteArray = requireNotNull(frame).bytes

    @Test
    fun `an action that threw is thrown again with its message`() =
        runTest {
            device.onAct = { _, _ -> throw IOException("connection reset") }
            val (records, override) =
                recorded { screen ->
                    assertThrows<IOException> { screen.act(tap, Screens.CHAT.id) }
                }
            val failed = (records.last().event as ScreenActed).outcome.status
            assertEquals(ActionOutcome.Status.Failed(DeviceScript.THREW + IOException::class.java.name), failed)
            val error = assertThrows<IOException> { replay(records, override).act(tap, Screens.CHAT.id) }
            assertEquals("connection reset", error.message)
        }

    @Test
    fun `an action cut off hangs again until cancelled`() =
        runTest {
            device.onAct = { _, _ -> throw CancellationException("stopped") }
            val (records, override) =
                recorded { screen -> assertThrows<CancellationException> { screen.act(tap, Screens.CHAT.id) } }
            val failed = (records.last().event as ScreenActed).outcome.status
            assertEquals(ActionOutcome.Status.Failed(DeviceScript.UNFINISHED), failed)
            assertEquals(emptyList<DeviceFact>(), override.device, "the log says it all")
            val screen = replay(records, override)
            val acting = async(start = CoroutineStart.UNDISPATCHED) { screen.act(tap, Screens.CHAT.id) }
            advanceUntilIdle()
            assertTrue(acting.isActive)
            acting.cancel()
        }

    @Test
    fun `a read that threw throws again`() =
        runTest {
            device.unavailable = ScreenUnavailableException("display released")
            val recording =
                recorded { screen ->
                    assertThrows<ScreenUnavailableException> { screen.snapshot() }
                }
            assertEquals(emptyList<SessionRecord>(), recording.screen, "a failed read is not logged")
            val error =
                assertThrows<ScreenUnavailableException> { replay(recording.records, recording.override).snapshot() }
            assertEquals("display released", error.message)
        }

    @Test
    fun `a read cut off hangs again`() =
        runTest {
            device.onSnapshot = { awaitCancellation() }
            val (records, override) =
                recorded { screen ->
                    val reading = async { screen.snapshot() }
                    advanceUntilIdle()
                    reading.cancel()
                    assertThrows<CancellationException> { reading.await() }
                }
            assertEquals(listOf(DeviceFact(call.value, 1, DevicePort.SNAPSHOT, hangs = true)), override.device)
            val reading = async { replay(records, override).snapshot() }
            advanceUntilIdle()
            assertTrue(reading.isActive)
            reading.cancel()
        }

    @Test
    fun `a kept frame is captured again between the read before it and the read it is checked on`() =
        runTest {
            device.onSnapshot = reads(Screens.CHAT, checked)
            val recording = recorded { screen -> assertTrue(screen.framed().frame != null) }
            val frame = recording.screen.last().event as ScreenFrame
            assertEquals(Screens.CHAT.id to checked.id, frame.snapshotId to frame.checkedOn)
            var again: FramedSnapshot? = null
            assertEquals(recording.records, rerun(recording) { screen -> again = screen.framed() })
            assertArrayEquals(device.bytes(), again?.frame?.bytes)
        }

    @Test
    fun `a frame the runtime dropped leaves the port calls after it in place`() =
        runTest {
            // The read after the capture is of another app, so the frame does not go with it; that
            // read took 300 ms, a fact on the port call after the capture.
            device.onSnapshot = reads(Screens.CHAT, checked.copy(pkg = "com.example.other"), secondDelayMs = 300)
            val recording = recorded { screen -> assertNull(screen.framed().frame) }
            assertTrue(recording.screen.none { it.event is ScreenFrame }, "the frame was dropped")
            assertEquals(
                listOf(DeviceFact(call.value, 3, DevicePort.SNAPSHOT, delayMs = 300)),
                recording.override.device,
            )
            val start = currentTime
            assertEquals(recording.records, rerun(recording) { screen -> assertNull(screen.framed().frame) })
            assertEquals(300L, currentTime - start)
        }

    @Test
    fun `a read that failed after a dropped frame fails again`() =
        runTest {
            var reads = 0
            device.onSnapshot = {
                if (reads++ > 0) throw ScreenUnavailableException("display released")
                Screens.CHAT
            }
            val recording =
                recorded { screen -> assertThrows<ScreenUnavailableException> { screen.framed() } }
            assertEquals(
                listOf(DevicePort.SNAPSHOT to 3),
                recording.override.device.map { it.port to it.op },
                "the failed read is the port call after the capture",
            )
            assertEquals(
                recording.records,
                rerun(recording) { screen -> assertThrows<ScreenUnavailableException> { screen.framed() } },
            )
        }

    @Test
    fun `a frame that gave none gives none again`() =
        runTest {
            device.onSnapshot = reads(Screens.CHAT, checked)
            device.frame = null
            val recording = recorded { screen -> assertNull(screen.framed().frame) }
            assertEquals(listOf(DeviceFact(call.value, 2, DevicePort.FRAME, noFrame = true)), recording.override.device)
            assertEquals(recording.records, rerun(recording) { screen -> assertNull(screen.framed().frame) })
        }

    @Test
    fun `a frame that threw throws again and gives none`() =
        runTest {
            device.onSnapshot = reads(Screens.CHAT, checked)
            device.frameError = ScreenUnavailableException("no surface")
            val recording = recorded { screen -> assertNull(screen.framed().frame) }
            val thrown = Thrown(ScreenUnavailableException::class.java.name, "no surface")
            assertEquals(
                listOf(DeviceFact(call.value, 2, DevicePort.FRAME, throws = thrown)),
                recording.override.device,
            )
            assertEquals(recording.records, rerun(recording) { screen -> assertNull(screen.framed().frame) })
        }

    @Test
    fun `a frame asked for outside a framed observe captures nothing`() =
        runTest {
            val recording = recorded { screen -> assertNull(screen.frame()) }
            assertEquals(emptyList<Boolean>(), device.frames)
            assertEquals(recording.records, rerun(recording) { screen -> assertNull(screen.frame()) })
        }

    @Test
    fun `a wait gives its answer again`() =
        runTest {
            device.onAwaitChange = { true }
            val (records, override) = recorded { screen -> screen.awaitChange(2_000) }
            assertEquals(true, replay(records, override).awaitChange(2_000))
        }

    @Test
    fun `a slow read is as slow again, analyzed alike`() =
        runTest {
            device.onSnapshot = {
                delay(300)
                Screens.CHAT
            }
            val (records, override) = recorded { screen -> screen.snapshot() }
            val start = currentTime
            val again = replay(records, override).snapshot()
            assertEquals(300L, currentTime - start)
            assertEquals(analyzer.analyzed(Screens.CHAT), again)
        }

    @Test
    fun `an exception replay cannot rebuild fails the recording`() =
        runTest {
            device.onAct = { _, _ -> throw UnsupportedOperationException("x") }
            assertThrows<IllegalArgumentException> { recorded { screen -> screen.act(tap, Screens.CHAT.id) } }
        }

    private companion object {
        const val WITHHELD = "(withheld)"
        const val MAX_EDGE = 1280
    }
}
