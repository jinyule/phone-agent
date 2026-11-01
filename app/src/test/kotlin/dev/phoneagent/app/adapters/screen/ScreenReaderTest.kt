package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenReaderTest {
    private fun settingsTree(): AccessibilityNodeInfo {
        val title = node("android.widget.TextView", Rect(0, 100, 1000, 200)) { text = "Settings" }
        val search =
            node("android.widget.EditText", Rect(0, 200, 1000, 300)) {
                isEditable = true
                isFocusable = true
                isClickable = true
                text = "Search settings"
                hintText = "Search settings"
                isShowingHintText = true
                viewIdResourceName = "com.example.app:id/search"
            }
        val rowLabel = node("android.widget.TextView", Rect(100, 400, 900, 450)) { text = "Network & internet" }
        val rowSummary = node("android.widget.TextView", Rect(100, 450, 900, 500)) { text = "Wi-Fi, mobile" }
        val row =
            node("android.widget.LinearLayout", Rect(0, 400, 1000, 500)) { isClickable = true }
                .with(rowLabel, rowSummary)
        val toggle =
            node("android.widget.Switch", Rect(800, 500, 1000, 600)) {
                isCheckable = true
                isClickable = true
                checked = AccessibilityNodeInfo.CHECKED_STATE_TRUE
                contentDescription = "Airplane mode"
            }
        val hidden = node("android.widget.TextView", Rect(0, 600, 1000, 700)) { text = "Hidden" }
        hidden.isVisibleToUser = false
        val list =
            node("androidx.recyclerview.widget.RecyclerView", Rect(0, 400, 1000, 1800)) { isScrollable = true }
                .with(row, toggle, hidden)
        val password =
            node("android.widget.EditText", Rect(0, 1800, 1000, 1900)) {
                isEditable = true
                isPassword = true
                text = "••••"
                contentDescription = "PIN"
            }
        return node("android.widget.FrameLayout", GEOMETRY.pixels).with(title, search, list, password)
    }

    private fun read(
        detail: SnapshotDetail = SnapshotDetail.BRIEF,
        vararg windows: AccessibilityWindowInfo = arrayOf(window(settingsTree(), id = 7)),
    ): TreeRead = ScreenReader.read(windows.toList(), GEOMETRY, detail)

    private fun TreeRead.named(name: String): UiElement = elements.map { it.element }.single { it.name == name }

    @Test
    fun turnsTheTreeIntoElementsInReadingOrder() {
        val elements = read().elements.map { it.element }

        assertEquals(
            listOf("Settings", null, null, "Network & internet Wi-Fi, mobile", "Network & internet", "Wi-Fi, mobile"),
            elements.take(6).map { it.name },
        )
        assertEquals(elements.indices.map { "e${it + 1}" }, elements.map { it.ref.value })
        assertTrue(elements.all { it.window == 7 })
    }

    @Test
    fun readsRolesNamesValuesAndStates() {
        val read = read()
        val search = read.elements.map { it.element }.single { it.role == ElementRole.INPUT && !it.secret }

        // The hint is shown as text: the field is empty and the hint goes to `hint`, not `value`.
        assertEquals("", search.value)
        assertEquals("Search settings", search.hint)
        assertEquals("search", search.resourceId)
        assertEquals(setOf(ElementState.CLICKABLE, ElementState.EDITABLE), search.states)

        val row = read.named("Network & internet Wi-Fi, mobile")
        assertEquals(ElementRole.BUTTON, row.role)

        val toggle = read.named("Airplane mode")
        assertEquals(ElementRole.SWITCH, toggle.role)
        assertTrue(ElementState.CHECKED in toggle.states)

        val list = read.elements.map { it.element }.single { it.role == ElementRole.LIST }
        assertEquals(setOf(ElementState.SCROLLABLE), list.states)
        assertEquals(list.ref, row.parent)
        assertEquals(list.ref, toggle.parent)
    }

    @Test
    fun passwordFieldsCarryOnlyTheSecretFlag() {
        val pin = read().named("PIN")

        assertTrue(pin.secret)
        assertNull(pin.value)
        assertEquals(ElementRole.INPUT, pin.role)
    }

    @Test
    fun normalizesBoundsToTheGrid() {
        val read = read()

        assertEquals(Bounds(0, 50, 999, 99), read.named("Settings").bounds)
        assertEquals(Bounds(800, 250, 999, 299), read.named("Airplane mode").bounds)
        assertEquals(Rect(800, 500, 1000, 600), read.elements.single { it.element.name == "Airplane mode" }.pixels)
    }

    @Test
    fun skipsInvisibleNodes() {
        assertTrue(read().elements.none { it.element.name == "Hidden" })
    }

    @Test
    fun briefDropsUnnamedLayoutsThatFullKeeps() {
        val brief = read(SnapshotDetail.BRIEF).elements.map { it.element }
        val full = read(SnapshotDetail.FULL).elements.map { it.element }

        assertFalse(brief.any { it.role == ElementRole.CONTAINER })
        assertTrue(full.any { it.role == ElementRole.CONTAINER && it.bounds == Bounds(0, 0, 999, 999) })
    }

    @Test
    fun keepsLargeUnlabelledLeavesSoQualityCanSeeThem() {
        val canvas = node("android.view.View", Rect(0, 200, 1000, 1200))
        val icon = node("android.view.View", Rect(0, 1200, 50, 1250))
        val root = node("android.widget.FrameLayout", GEOMETRY.pixels).with(canvas, icon)

        val elements = read(SnapshotDetail.BRIEF, window(root)).elements.map { it.element }

        assertEquals(listOf(Bounds(0, 100, 999, 599)), elements.map { it.bounds })
        assertEquals(ElementRole.OTHER, elements.single().role)
    }

    @Test
    fun keepsTheOutermostOfNestedViewsWithTheSameText() {
        val inner = node("android.widget.TextView", Rect(0, 0, 500, 100)) { text = "OK" }
        val outer = node("android.widget.FrameLayout", Rect(0, 0, 500, 100)) { text = "OK" }.with(inner)

        val elements = read(SnapshotDetail.BRIEF, window(outer)).elements

        assertEquals(1, elements.size)
        assertEquals("android.widget.FrameLayout", elements.single().node.className)
    }

    @Test
    fun readsWindowsTopMostFirstAndSkipsKeyboardAndOverlays() {
        val dialogText = node("android.widget.TextView", Rect(100, 800, 900, 900)) { text = "Allow?" }
        val dialogRoot = node("android.widget.FrameLayout", Rect(100, 700, 900, 1100)).with(dialogText)
        val dialog = window(dialogRoot, id = 2, layer = 5)
        val keyText = node("android.widget.TextView", Rect(0, 1500, 100, 1600), pkg = "com.ime") { text = "q" }
        val keyboard = window(keyText, id = 3, layer = 9, type = AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        val app = window(settingsTree(), id = 1, layer = 1)

        val read = read(SnapshotDetail.BRIEF, app, keyboard, dialog)

        assertEquals(
            "Allow?",
            read.elements
                .first()
                .element.name,
        )
        assertEquals(
            2,
            read.elements
                .first()
                .element.window,
        )
        assertTrue(read.elements.none { it.element.name == "q" })
        assertTrue(read.keyboard)
        assertEquals(listOf(2, 1), read.windowBounds.map { it.first })
        assertEquals(APP, read.pkg)
    }

    @Test
    fun theTopPackageIsTheHighestApplicationWindow() {
        val bar = node("android.widget.FrameLayout", Rect(0, 0, 1000, 100), pkg = "com.android.systemui")
        val systemUi = window(bar, id = 5, layer = 9, type = AccessibilityWindowInfo.TYPE_SYSTEM)
        val front = window(node("android.widget.FrameLayout", GEOMETRY.pixels, pkg = "com.front"), id = 2, layer = 3)
        val back = window(node("android.widget.FrameLayout", GEOMETRY.pixels, pkg = "com.back"), id = 1, layer = 1)

        assertEquals("com.front", ScreenReader.topPackage(listOf(back, systemUi, front)))
        assertNull(ScreenReader.topPackage(listOf(systemUi)))
    }

    @Test
    fun elementRefsAreValidAndUnique() {
        val refs = read(SnapshotDetail.FULL).elements.map { it.element.ref }

        assertEquals(refs.toSet().size, refs.size)
        assertEquals(ElementRef("e1"), refs.first())
    }
}
