package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.RangeInfo
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeFactsTest {
    private val box = Rect(0, 0, 100, 100)

    private fun roleOf(
        className: String,
        setup: AccessibilityNodeInfo.() -> Unit = {},
    ) = NodeFacts.roleOf(node(className, box, setup = setup))

    @Test
    fun rolesComeFromEditabilityThenClassThenBehaviour() {
        assertEquals(ElementRole.INPUT, roleOf("android.view.View") { isEditable = true })
        assertEquals(ElementRole.INPUT, roleOf("androidx.appcompat.widget.AppCompatEditText"))
        assertEquals(ElementRole.SWITCH, roleOf("com.google.android.material.materialswitch.SwitchMaterial"))
        assertEquals(ElementRole.RADIO, roleOf("android.widget.RadioButton"))
        assertEquals(ElementRole.CHECKBOX, roleOf("android.widget.CheckBox"))
        assertEquals(ElementRole.PICKER, roleOf("android.widget.NumberPicker"))
        assertEquals(ElementRole.PROGRESS, roleOf("android.widget.ProgressBar"))
        assertEquals(ElementRole.TAB, roleOf("com.google.android.material.tabs.TabLayout\$TabView"))
        assertEquals(ElementRole.CHECKBOX, roleOf("android.view.View") { isCheckable = true })
        assertEquals(
            ElementRole.LINK,
            roleOf("android.view.View") { extras.putCharSequence("AccessibilityNodeInfo.chromeRole", "link") },
        )
        assertEquals(ElementRole.LIST, roleOf("android.widget.ListView"))
        assertEquals(ElementRole.LIST, roleOf("android.view.View") { isScrollable = true })
        assertEquals(ElementRole.BUTTON, roleOf("android.widget.ImageButton"))
        assertEquals(ElementRole.BUTTON, roleOf("android.view.View") { isClickable = true })
        assertEquals(ElementRole.IMAGE, roleOf("android.widget.ImageView"))
        assertEquals(ElementRole.TEXT, roleOf("android.view.View") { contentDescription = "Logo" })
        assertEquals(ElementRole.OTHER, roleOf("android.view.View"))
    }

    @Test
    fun statesFollowTheNode() {
        val node =
            node("android.widget.CheckBox", box) {
                isCheckable = true
                checked = AccessibilityNodeInfo.CHECKED_STATE_PARTIAL
                isLongClickable = true
                isSelected = true
                isFocused = true
                isEnabled = false
            }

        assertEquals(
            setOf(ElementState.LONG_CLICKABLE, ElementState.SELECTED, ElementState.FOCUSED, ElementState.DISABLED),
            NodeFacts.statesOf(node),
        )
        node.checked = AccessibilityNodeInfo.CHECKED_STATE_TRUE
        assertEquals(true, ElementState.CHECKED in NodeFacts.statesOf(node))
    }

    @Test
    fun rangesAreInteractiveAndShowTheirPosition() {
        val slider =
            node("android.widget.SeekBar", box) {
                rangeInfo = RangeInfo(RangeInfo.RANGE_TYPE_FLOAT, 0f, 200f, 50f)
            }
        val percent =
            node("android.view.View", box) {
                rangeInfo =
                    RangeInfo(RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, 42f)
            }
        val count = node("android.view.View", box) { rangeInfo = RangeInfo(RangeInfo.RANGE_TYPE_INT, 0f, 10f, 3f) }

        assertEquals(true, NodeFacts.interactive(slider))
        assertEquals("25%", NodeText.valueOf(slider, NodeFacts.roleOf(slider)))
        assertEquals("42%", NodeText.valueOf(percent, ElementRole.OTHER))
        assertEquals("3", NodeText.valueOf(count, ElementRole.OTHER))
    }
}

@RunWith(AndroidJUnit4::class)
class NodeTextTest {
    private val box = Rect(0, 0, 100, 100)

    @Test
    fun aClickableContainerIsNamedByItsVisibleDescendants() {
        val title = node("android.widget.TextView", box) { text = "Alice" }
        val preview = node("android.widget.TextView", box) { text = "See you at 5" }
        val hidden = node("android.widget.TextView", box) { text = "secret" }
        hidden.isVisibleToUser = false
        val row = node("android.widget.LinearLayout", box) { isClickable = true }.with(title, preview, hidden)

        assertEquals("Alice See you at 5", NodeText.nameOf(row, ElementRole.BUTTON))
        assertNull("lists are not named by their rows", NodeText.nameOf(row, ElementRole.LIST))
    }

    @Test
    fun aggregatedNamesAreCapped() {
        val long = node("android.widget.TextView", box) { text = "x".repeat(200) }
        val row = node("android.widget.LinearLayout", box) { isClickable = true }.with(long)

        assertEquals(NodeText.MAX_AGGREGATE + 1, NodeText.nameOf(row, ElementRole.BUTTON)?.length)
    }

    @Test
    fun fieldsAreNamedByDescriptionOrLabelNeverByTheirContent() {
        val label = TextView(ApplicationProvider.getApplicationContext()).apply { text = "Email" }
        val field =
            node("android.widget.EditText", box) {
                isEditable = true
                text = "ada@example.com"
            }
        assertNull(NodeText.nameOf(field, ElementRole.INPUT))
        field.addLabeledBy(label)

        assertEquals("Email", NodeText.nameOf(field, ElementRole.INPUT))
        assertEquals("ada@example.com", NodeText.valueOf(field, ElementRole.INPUT))
        field.contentDescription = "Work email"
        assertEquals("Work email", NodeText.nameOf(field, ElementRole.INPUT))
    }

    @Test
    fun aHintReportedAsTextIsNotContent() {
        val field =
            node("android.widget.EditText", box) {
                isEditable = true
                text = "Search"
                hintText = "Search"
            }

        assertEquals("", NodeText.fieldText(field))
        assertEquals("Search", NodeText.hintOf(field))
    }

    @Test
    fun longTextIsCut() {
        val text = node("android.widget.TextView", box) { this.text = "y".repeat(600) }

        assertEquals(NodeText.MAX_TEXT + 1, NodeText.nameOf(text, ElementRole.TEXT)?.length)
    }
}
