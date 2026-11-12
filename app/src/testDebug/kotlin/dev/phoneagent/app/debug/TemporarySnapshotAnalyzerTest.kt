package dev.phoneagent.app.debug

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement
import org.junit.Assert.assertEquals
import org.junit.Test

class TemporarySnapshotAnalyzerTest {
    private fun snapshot(
        id: String,
        vararg names: String,
    ) = ScreenSnapshot(
        id = SnapshotId(id),
        surface = SurfaceRef(displayId = 7, kind = SurfaceKind.AGENT),
        pkg = "com.example.chat",
        activity = null,
        elements =
            names.mapIndexed { i, name ->
                UiElement(
                    ElementRef("e${i + 1}"),
                    ElementRole.TEXT,
                    name,
                    bounds = Bounds(0, i, 1, i + 1),
                )
            },
        ocr = null,
        quality = TreeQuality.SPARSE,
        flags = emptySet(),
        fingerprint = "fp-$id",
    )

    @Test
    fun theDiffIsBasedOnTheEarlierSnapshot() {
        val earlier = snapshot("s4", "Alice")
        val later = snapshot("s7", "Alice", "Bob")
        val diff = TemporarySnapshotAnalyzer.diff(earlier, later)
        assertEquals(SnapshotId("s4"), diff.base)
        assertEquals(listOf("Bob"), diff.added.map { it.name })
        assertEquals(SnapshotId("s7"), TemporarySnapshotAnalyzer.diff(later, earlier).base)
    }
}
