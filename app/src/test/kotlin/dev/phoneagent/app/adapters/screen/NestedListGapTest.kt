package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private val FULL = SnapshotOptions(detail = SnapshotDetail.FULL)

/**
 * A list nested in a list, right beside a child of the outer list that could not be read (fix
 * list r4 C6, Codex r5 B1): the nested list's row in the outer list is incomplete like any other
 * row beside the gap, so the nested list and what it holds are not carried or matched. Read by the
 * real walker, snapshots at FULL detail; the negatives assert that nothing on the device was touched.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [UnreadableChildShadow::class])
class NestedListGapTest {
    private val host = FakeHost()

    /** Every node the screens of a test were made of, to check that none was acted on. */
    private val made = ArrayList<AccessibilityNodeInfo>()

    private fun make(
        cls: String,
        bounds: Rect,
        setup: AccessibilityNodeInfo.() -> Unit = {},
    ): AccessibilityNodeInfo = node(cls, bounds, setup = setup).also { made += it }

    private fun press(
        label: String,
        bounds: Rect,
    ): AccessibilityNodeInfo =
        make("android.widget.Button", bounds) {
            text = label
            isClickable = true
        }

    /** A row of its own: [person], then a Follow button. */
    private fun row(
        person: String,
        top: Int,
    ): AccessibilityNodeInfo =
        make("android.widget.LinearLayout", Rect(0, top, 1000, top + ROW_PX)).with(
            make("android.widget.TextView", Rect(0, top, 600, top + ROW_PX)) { text = person },
            press("Follow", Rect(700, top, 1000, top + ROW_PX)),
        )

    /**
     * A toolbar with Search above an outer list: Cy's row, Dee's row, then [person]'s title, which
     * cannot be read, and the list "Items" holding a Like; with [titleFirst] false the title and
     * the list swap places, and so do the rows, so that Dee's row is always the one beside the gap.
     */
    private fun show(
        person: String,
        titleFirst: Boolean,
    ) {
        val title =
            make("android.widget.TextView", Rect(0, 500, 1000, 600)) {
                text = person
                viewIdResourceName = "$APP:id/unreadable"
            }
        val items =
            make("androidx.recyclerview.widget.RecyclerView", Rect(0, 600, 1000, 900)) {
                text = "Items"
                collectionInfo = AccessibilityNodeInfo.CollectionInfo(1, 1, false)
            }.with(press("Like", Rect(700, 700, 1000, 800)))
        val rows = listOf(row("Cy", 300), row("Dee", 400))
        val children = if (titleFirst) rows + title + items else listOf(items, title) + rows.reversed()
        val outer =
            make("androidx.recyclerview.widget.RecyclerView", Rect(0, 300, 1000, 1800)) {
                collectionInfo = AccessibilityNodeInfo.CollectionInfo(children.size, 1, false)
            }.with(*children.toTypedArray())
        val toolbar =
            make(
                "android.widget.LinearLayout",
                Rect(0, 60, 1000, 300),
            ).with(press("Search", Rect(700, 60, 1000, 300)))
        host.show(toolbar, outer)
    }

    private fun ScreenSnapshot.button(
        name: String,
        index: Int = 0,
    ) = elements.filter { it.name == name && it.role == ElementRole.BUTTON }[index]

    private fun ScreenSnapshot.items() = elements.single { it.name == "Items" && it.role == ElementRole.LIST }

    private suspend fun TestScope.nestedListBesideTheGapIsNotCarried(titleFirst: Boolean) {
        show("Bob", titleFirst)
        val port = mainPort(host)
        val s1 = port.snapshot(FULL)
        show("Ada", titleFirst)
        val s2 = port.snapshot(FULL)
        val cy = if (titleFirst) 0 else 1

        val diff = RefDiffAnalyzer().diff(s1, s2)
        val outcome = port.act(UiAction.Tap(Target.Ref(s1.button("Like").ref)), s1.id)

        assertStale(outcome.status)
        assertTrue("no gesture", host.gestures.isEmpty())
        assertTrue("no node action", made.all { it.log.actions.isEmpty() })
        assertFalse("the list beside the gap is new", s2.items().ref == s1.items().ref)
        assertFalse("its Like is new", s2.button("Like").ref == s1.button("Like").ref)
        assertTrue("the old Like is removed", s1.button("Like").ref in diff.removed)
        assertTrue("the new Like is added", diff.added.any { it.ref == s2.button("Like").ref })
        // The scopes are as before: a row away from the gap and the toolbar keep their refs.
        assertEquals("Cy's Follow", s1.button("Follow", cy).ref, s2.button("Follow", cy).ref)
        assertEquals("the toolbar's Search", s1.button("Search").ref, s2.button("Search").ref)
        val follow = port.act(UiAction.Tap(Target.Ref(s1.button("Follow", cy).ref)), s1.id)
        assertEquals(ActionOutcome.Status.Ok, follow.status)
    }

    @Test
    fun aNestedListRightAfterAChildThatCannotBeReadIsNotCarried() =
        runTest { nestedListBesideTheGapIsNotCarried(titleFirst = true) }

    @Test
    fun aNestedListRightBeforeAChildThatCannotBeReadIsNotCarried() =
        runTest { nestedListBesideTheGapIsNotCarried(titleFirst = false) }
}
