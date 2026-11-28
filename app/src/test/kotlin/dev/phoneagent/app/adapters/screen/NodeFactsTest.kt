package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeInfo.RangeInfo
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun shapeOf(
        className: String,
        setup: AccessibilityNodeInfo.() -> Unit = {},
    ): Identities.Shape {
        val node = node(className, box, setup = setup)
        return NodeFacts.shapeOf(node, NodeFacts.roleOf(node))
    }

    @Test
    fun onlyCollectionsThatRecycleOrLoadLazilyAreLists() {
        // Fix list r3 B1 rule 1: a scroll view holds all of its content and is no list.
        assertTrue(shapeOf("androidx.recyclerview.widget.RecyclerView").list)
        assertTrue(shapeOf("android.widget.ListView").list)
        assertTrue(shapeOf("androidx.viewpager2.widget.ViewPager2") { isScrollable = true }.list)
        assertTrue(
            shapeOf("android.view.View") { collectionInfo = AccessibilityNodeInfo.CollectionInfo(3, 1, false) }.list,
        )
        assertFalse(shapeOf("android.widget.ScrollView").list)
        assertFalse(shapeOf("androidx.core.widget.NestedScrollView") { isScrollable = true }.list)
        assertFalse(shapeOf("android.view.View") { isScrollable = true }.list)
        assertFalse(shapeOf("android.widget.LinearLayout").list)
        assertFalse(
            "a list's class with another role",
            shapeOf("androidx.recyclerview.widget.RecyclerView") { isEditable = true }.list,
        )
    }

    @Test
    fun aShapeTellsWhatTakesClicks() {
        assertFalse(shapeOf("android.widget.LinearLayout").interactive)
        assertTrue(shapeOf("android.widget.LinearLayout") { isClickable = true }.interactive)
        assertTrue(shapeOf("android.widget.LinearLayout") { isLongClickable = true }.interactive)
        assertTrue(shapeOf("android.widget.CheckBox") { isCheckable = true }.interactive)
    }

    private val listBox = Rect(0, 200, 1000, 1800)

    /** The viewport of a list at [listBox] of [className], configured by [setup]. */
    private fun viewport(
        className: String = "androidx.recyclerview.widget.RecyclerView",
        setup: AccessibilityNodeInfo.() -> Unit = {},
    ): Viewport = NodeFacts.viewportOf(node(className, listBox, setup = setup))

    @Test
    fun aChildIsCutWhereTheListCanStillScroll() {
        // Fix list r3 B1 rule 4: a child touching an edge the list can scroll past may have lost
        // nodes there; so may the first child of a list that can scroll back.
        val both =
            viewport {
                addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD)
                addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
            }
        val atTop = viewport { addAction(AccessibilityAction.ACTION_SCROLL_DOWN) }
        val still = viewport()
        val top = Rect(0, 200, 1000, 300)
        val middle = Rect(0, 900, 1000, 1000)
        val bottom = Rect(0, 1700, 1000, 1800)

        assertTrue(both.clips(top, first = false))
        assertTrue(both.clips(bottom, first = false))
        assertTrue("the first child", both.clips(middle, first = true))
        assertFalse(both.clips(middle, first = false))
        assertFalse("nothing above to scroll back to", atTop.clips(top, first = true))
        assertTrue(atTop.clips(bottom, first = false))
        assertFalse(still.clips(top, first = true))
        assertFalse(still.clips(bottom, first = false))
        assertTrue("reaching past the list", still.clips(Rect(0, 100, 1000, 300), first = false))
        assertTrue("out of view", still.clips(Rect(), first = false))
        assertTrue(viewport { addAction(AccessibilityAction.ACTION_SCROLL_UP) }.clips(top, first = false))
    }

    @Test
    fun aListThatScrollsSidewaysCutsAtItsSidesEitherWay() {
        val row = viewport { collectionInfo = AccessibilityNodeInfo.CollectionInfo(1, 5, false) }
        val forward =
            viewport {
                collectionInfo = AccessibilityNodeInfo.CollectionInfo(1, -1, false)
                addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
            }
        val pager =
            viewport("androidx.viewpager.widget.ViewPager") { addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD) }
        val left = Rect(0, 900, 300, 1000)
        val right = Rect(700, 900, 1000, 1000)

        assertFalse(row.clips(left, first = true))
        assertTrue("right to left, forward is to the left", forward.clips(left, first = false))
        assertTrue(forward.clips(right, first = false))
        assertTrue(pager.clips(right, first = false))
        assertFalse("not along the scrolling axis", forward.clips(Rect(300, 200, 700, 300), first = false))
        assertTrue(
            viewport {
                addAction(AccessibilityAction.ACTION_SCROLL_RIGHT)
            }.clips(Rect(0, 1700, 1000, 1800), first = false),
        )
    }
}
