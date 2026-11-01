package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefMatcherTest {
    private fun element(
        ref: String,
        name: String?,
        top: Int = 100,
        role: ElementRole = ElementRole.BUTTON,
        window: Int? = 1,
    ) = UiElement(
        ref = ElementRef(ref),
        role = role,
        name = name,
        bounds = Bounds(0, top, 999, top + 50),
        window = window,
    )

    private fun snapshot(vararg elements: UiElement) =
        ScreenSnapshot(
            id = SnapshotId("s1"),
            surface = SurfaceRef(0, SurfaceKind.MAIN),
            pkg = APP,
            activity = null,
            elements = elements.toList(),
            ocr = null,
            quality = TreeQuality.GOOD,
            flags = emptySet(),
            fingerprint = "f",
        )

    private fun fresh(vararg elements: UiElement): List<ReadElement> =
        elements.map { ReadElement(it, AccessibilityNodeInfo(), Rect(0, 0, 10, 10), APP) }

    @Test
    fun findsTheSameElementUnderANewRef() {
        val planned = snapshot(element("e1", "Title", top = 0), element("e2", "OK"))
        val read = fresh(element("e1", "Banner", top = 500), element("e2", "Title", top = 0), element("e3", "OK"))

        val match = RefMatcher.match(planned, ElementRef("e2"), read)

        assertSame(read[2], (match as RefMatcher.Match.Found).element)
    }

    @Test
    fun valuesAndStatesMayChange() {
        val planned = snapshot(element("e1", "Wi-Fi", role = ElementRole.SWITCH))
        val toggled = element("e1", "Wi-Fi", role = ElementRole.SWITCH).copy(states = setOf(ElementState.CHECKED))
        val typed = element("e2", "Name", role = ElementRole.INPUT).copy(value = "Ada")

        assertEquals(RefMatcher.Match.Found::class, RefMatcher.match(planned, ElementRef("e1"), fresh(toggled))::class)
        val field = snapshot(element("e2", "Name", role = ElementRole.INPUT).copy(value = ""))
        assertEquals(RefMatcher.Match.Found::class, RefMatcher.match(field, ElementRef("e2"), fresh(typed))::class)
    }

    @Test
    fun aMovedRenamedOrReWindowedElementIsStale() {
        val planned = snapshot(element("e1", "OK"))
        val matchOk = { now: List<ReadElement> -> RefMatcher.match(planned, ElementRef("e1"), now) }

        assertEquals(RefMatcher.Match.Found::class, matchOk(fresh(element("e7", "OK")))::class)
        assertEquals(RefMatcher.Match.Stale, matchOk(fresh(element("e1", "OK", top = 101))))
        assertEquals(RefMatcher.Match.Stale, matchOk(fresh(element("e1", "Cancel"))))
        assertEquals(RefMatcher.Match.Stale, matchOk(fresh(element("e1", "OK", window = 2))))
        assertEquals(RefMatcher.Match.Stale, matchOk(fresh(element("e1", "OK", role = ElementRole.TEXT))))
        assertEquals(RefMatcher.Match.Stale, matchOk(fresh()))
    }

    @Test
    fun identicalElementsMatchByPositionWhileTheirCountHolds() {
        val planned = snapshot(element("e1", "Like"), element("e2", "Like"))
        val same = fresh(element("e1", "Like"), element("e2", "Like"))

        assertSame(same[1], (RefMatcher.match(planned, ElementRef("e2"), same) as RefMatcher.Match.Found).element)
        assertSame(same[0], (RefMatcher.match(planned, ElementRef("e1"), same) as RefMatcher.Match.Found).element)
        val oneMore = fresh(element("e1", "Like"), element("e2", "Like"), element("e3", "Like"))
        assertEquals(RefMatcher.Match.Stale, RefMatcher.match(planned, ElementRef("e1"), oneMore))
    }

    @Test
    fun aRefTheSnapshotNeverHadIsUnknown() {
        val planned = snapshot(element("e1", "OK"))

        assertEquals(RefMatcher.Match.UnknownRef, RefMatcher.match(planned, ElementRef("e9"), fresh()))
    }

    @Test
    fun historyKeepsTheNewestSnapshots() {
        val history = SnapshotHistory(keep = 2)
        val ids = listOf("s1", "s2", "s3").map(::SnapshotId)
        ids.forEach { history.put(snapshot().copy(id = it)) }

        assertNull(history.get(ids[0]))
        assertEquals(ids[1], history.get(ids[1])?.id)
        assertEquals(ids[2], history.get(ids[2])?.id)
        assertThrows(IllegalArgumentException::class.java) { SnapshotHistory(keep = 0) }
    }
}
