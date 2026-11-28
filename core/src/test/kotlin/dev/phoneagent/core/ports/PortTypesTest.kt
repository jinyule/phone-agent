package dev.phoneagent.core.ports

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.TaskId
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PortTypesTest {
    @Test
    fun `storage paths stay below the root`() {
        listOf("", "/a", "a/", "a//b", "../a", "a/../b", "./a", "a b", "a\\b").forEach {
            assertThrows<IllegalArgumentException>(it) { StoragePath(it) }
        }
        val path = StoragePath("sessions/s1/session.jsonl")
        assertEquals("session.jsonl", path.name)
        assertEquals(StoragePath("sessions/s1"), path.parent)
        assertNull(StoragePath("top").parent)
        assertEquals(StoragePath("a/b/c"), StoragePath("a").resolve("b/c"))
        assertEquals("a", StoragePath("a").toString())
        assertThrows<IllegalArgumentException> { StoragePath("a").resolve("..") }
    }

    @Test
    fun `coordinates are 0 to 999`() {
        assertTrue(ScreenCoordinates.isValid(0) && ScreenCoordinates.isValid(999))
        assertFalse(ScreenCoordinates.isValid(-1) || ScreenCoordinates.isValid(1000))
        assertThrows<IllegalArgumentException> { Target.Point(1000, 0) }
        assertThrows<IllegalArgumentException> { Target.Point(0, -1) }
        assertThrows<IllegalArgumentException> { Bounds(10, 0, 5, 10) }
        assertThrows<IllegalArgumentException> { Bounds(0, 10, 5, 5) }
        assertThrows<IllegalArgumentException> { Bounds(0, 0, 1000, 5) }
        assertEquals(Target.Point(5, 15), Bounds(0, 10, 10, 20).center())
    }

    @Test
    fun `element refs have no at sign and secret elements carry no value`() {
        listOf("@e1", "e0", "e01", "E1", "e").forEach { assertThrows<IllegalArgumentException>(it) { ElementRef(it) } }
        assertEquals("o12", ElementRef("o12").toString())
        assertThrows<IllegalArgumentException> {
            UiElement(
                ElementRef("e1"),
                ElementRole.INPUT,
                value = "hunter2",
                bounds = Bounds(0, 0, 1, 1),
                secret = true,
            )
        }
    }

    @Test
    fun `snapshots reject duplicate refs`() {
        val element = UiElement(ElementRef("e1"), ElementRole.TEXT, bounds = Bounds(0, 0, 1, 1))
        val error =
            assertThrows<IllegalArgumentException> {
                ScreenSnapshot(
                    SnapshotId("s1"),
                    SurfaceRef(0, SurfaceKind.MAIN),
                    null,
                    null,
                    listOf(element, element),
                    null,
                    TreeQuality.EMPTY,
                    emptySet(),
                    "f",
                )
            }
        assertTrue("e1" in error.message.orEmpty())
    }

    @Test
    fun `value checks on actions, launches, OCR and frames`() {
        assertThrows<IllegalArgumentException> { UiAction.Swipe(Target.Point(0, 0), Target.Point(1, 1), 0) }
        assertThrows<IllegalArgumentException> { LaunchTarget.App("not a package") }
        assertThrows<IllegalArgumentException> { LaunchTarget.App("single") }
        assertThrows<IllegalArgumentException> { LaunchTarget.Link("no-scheme") }
        assertThrows<IllegalArgumentException> { OcrLine("x", Bounds(0, 0, 1, 1), 1.5) }
        assertThrows<IllegalArgumentException> { Frame(ByteArray(1), "image/jpeg", 0, 1, som = false) }
        assertThrows<IllegalArgumentException> { Frame(ByteArray(1), "text/plain", 1, 1, som = false) }
        val frame = Frame(byteArrayOf(9), "image/jpeg", 2, 3, som = true)
        assertEquals(listOf(2, 3), listOf(frame.width, frame.height))
        assertTrue(frame.som && frame.bytes[0] == 9.toByte() && frame.mime == "image/jpeg")
        assertNull(OcrLine("x", Bounds(0, 0, 1, 1)).confidence)
    }

    @Test
    fun `screen diffs know when nothing changed`() {
        val element = UiElement(ElementRef("e1"), ElementRole.TEXT, bounds = Bounds(0, 0, 1, 1))
        assertTrue(ScreenDiff().isEmpty)
        assertFalse(ScreenDiff(added = listOf(element)).isEmpty)
        assertFalse(ScreenDiff(removed = listOf(element.ref)).isEmpty)
        assertFalse(ScreenDiff(changed = listOf(element)).isEmpty)
    }

    @Test
    fun `surface types serialize with stable discriminators`() {
        assertEquals(
            """{"type":"available","concurrentTasks":true}""",
            Json.encodeToString<SurfaceCapability>(SurfaceCapability.Available(true)),
        )
        val capabilities =
            listOf(
                SurfaceCapability.Unknown,
                SurfaceCapability.Unavailable(SurfaceUnavailableReason.API_TOO_OLD, "API 33"),
            )
        capabilities.forEach {
            assertEquals(it, Json.decodeFromString<SurfaceCapability>(Json.encodeToString<SurfaceCapability>(it)))
        }
        val ticket = HandoffTicket(TaskId("t"), HandoffKind.CAPTCHA, SurfaceRef(3, SurfaceKind.AGENT), "task:42")
        assertEquals(ticket, Json.decodeFromString<HandoffTicket>(Json.encodeToString(ticket)))
        val options = SnapshotOptions(SnapshotDetail.FULL, ocr = false)
        assertEquals(options, Json.decodeFromString<SnapshotOptions>(Json.encodeToString(options)))
        assertEquals(SnapshotDetail.BRIEF, SnapshotOptions().detail)
        val allocations =
            listOf(
                SurfaceAllocation.Ready(SurfaceRef(1, SurfaceKind.AGENT)),
                SurfaceAllocation.Unavailable(SurfaceUnavailableReason.PROBE_FAILED, "x"),
            )
        assertEquals(2, allocations.size)
    }

    @Test
    fun `screen port defaults are a brief snapshot and a 1280 px frame without marks`() =
        kotlinx.coroutines.test.runTest {
            val seen = mutableListOf<Any>()
            val port =
                object : ScreenPort {
                    override val surface = SurfaceRef(0, SurfaceKind.MAIN)

                    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot {
                        seen += options
                        return ScreenSnapshot(
                            SnapshotId("s1"),
                            surface,
                            null,
                            null,
                            emptyList(),
                            null,
                            TreeQuality.EMPTY,
                            emptySet(),
                            "f",
                        )
                    }

                    override suspend fun act(
                        action: UiAction,
                        on: SnapshotId?,
                    ) = ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.NO_CHANGE)

                    override suspend fun launch(target: LaunchTarget) =
                        ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.NEW_APP)

                    override suspend fun frame(
                        maxEdge: Int,
                        som: Boolean,
                    ): Frame? {
                        seen += maxEdge to som
                        return null
                    }

                    override suspend fun awaitChange(timeoutMs: Long) = false
                }
            port.snapshot()
            port.frame()
            assertEquals(listOf(SnapshotOptions(), 1280 to false), seen)
            assertEquals(ActionOutcome.Effect.NEW_APP, port.launch(LaunchTarget.App("a.b")).effect)
            assertEquals(ActionOutcome.Effect.NO_CHANGE, port.act(UiAction.Key(SystemKey.HOME), null).effect)
            assertFalse(port.awaitChange(10))
        }

    @Test
    fun `the system clock tracks wall time and screen ports have a frame default`() {
        val before = System.currentTimeMillis()
        val now = Clock.System.epochMillis()
        assertTrue(now >= before && now <= System.currentTimeMillis())
        assertEquals(1280, ScreenPort.DEFAULT_FRAME_EDGE)
        assertEquals("gone", ScreenUnavailableException("gone").message)
    }
}
