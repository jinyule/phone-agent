package dev.phoneagent.core.replay

import dev.phoneagent.core.agent.RecordingScreen
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.session.BlobStore
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.testing.FakeScreen
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.testing.Screens
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
 * [DeviceScript]'s spellings of a thrown and a cut-off action meet `RecordingScreen`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingRoundTripTest {
    private val call = ToolCallId("call-1")
    private val tap = UiAction.Tap(Target.Ref(ElementRef("e10")))
    private val device = FakeScreen()

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
            ): ScreenDiff = ScreenDiff()
        }

    /** A recording of [calls] on [device]: its log records and its facts with concrete call ids. */
    private suspend fun TestScope.recorded(
        calls: suspend (RecordingScreen) -> Unit,
    ): Pair<List<SessionRecord>, ReplayOverride> {
        val records = mutableListOf<SessionRecord>()
        val facts = FactRecorder(device, analyzer, { call }) { currentTime }
        val blobs = BlobStore(InMemoryStoragePort(), StoragePath("blobs"))
        val screen =
            RecordingScreen(facts, call, blobs) { event: SessionEvent ->
                records +=
                    SessionRecord(records.size + 1L, 0, event)
            }
        calls(screen)
        return records to ReplayOverride(device = facts.facts)
    }

    private fun replay(
        records: List<SessionRecord>,
        override: ReplayOverride,
    ) = ReplayScreen(Screens.AGENT, DeviceScript.of(records, override), emptyMap(), analyzer) { call }

    @Test
    fun `an action that threw is thrown again with its message`() =
        runTest {
            device.onAct = { _, _ -> throw IOException("connection reset") }
            val (records, override) =
                recorded { screen ->
                    assertThrows<IOException> { screen.act(tap, Screens.CHAT.id) }
                }
            val failed = (records.single().event as ScreenActed).outcome.status
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
            val failed = (records.single().event as ScreenActed).outcome.status
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
            val (records, override) =
                recorded { screen ->
                    assertThrows<ScreenUnavailableException> { screen.snapshot() }
                }
            assertEquals(emptyList<SessionRecord>(), records, "a failed read is not logged")
            val error = assertThrows<ScreenUnavailableException> { replay(records, override).snapshot() }
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
    fun `a frame that gave none gives none again`() =
        runTest {
            device.frame = null
            val (records, override) = recorded { screen -> screen.frame() }
            assertNull(replay(records, override).frame())
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
}
