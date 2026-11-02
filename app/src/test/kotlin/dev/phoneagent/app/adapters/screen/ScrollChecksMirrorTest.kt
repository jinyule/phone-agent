package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The scroll checks of `AndroidScreenDeviceTest.tapsScrollsAndGoesBack`, on a feed scrolled by
 * three rows under a status bar, so that they are shown to fail when refs are not carried (fix list
 * r1 B10). Keep [movedRows] and [vanishedRefs] in step with the device test.
 */
@RunWith(AndroidJUnit4::class)
class ScrollChecksMirrorTest {
    private val host = FakeHost()
    private val feed = Feed()

    private fun statusBar(): AccessibilityWindowInfo {
        val strip = Rect(0, 0, 1000, 60)
        val root =
            node("android.widget.FrameLayout", strip, SYSTEM_UI).with(
                node("android.widget.TextView", Rect(20, 10, 200, 50), SYSTEM_UI) { text = "10:08" },
            )
        return window(root, id = STATUS_BAR, layer = 10, type = AccessibilityWindowInfo.TYPE_SYSTEM).also {
            shadowOf(it).setBoundsInScreen(strip)
        }
    }

    private fun showFeed(people: List<String>) {
        host.show(feed.list(people, top = Feed.LIST_TOP), extra = listOf(statusBar()))
    }

    @Test
    fun movedRowsKeepTheirRefsAndRowsScrolledAwayLeaveTheirRefsUnused() =
        runTest {
            val port = mainPort(host)
            showFeed(PEOPLE.take(ROWS))
            val before = port.snapshot()
            showFeed(PEOPLE.drop(SCROLLED).take(ROWS))

            val after = port.snapshot()

            assertTrue(
                "the status bar is not part of the screen",
                (before.elements + after.elements).none {
                    it.window ==
                        STATUS_BAR
                },
            )
            val moved = movedRows(before, after)
            assertTrue("some named row stayed on screen and moved", moved.isNotEmpty())
            moved.forEach { (was, now) -> assertEquals("ref of '${was.name}'", was.ref, now.ref) }
            val vanished = vanishedRefs(before, after)
            assertTrue(vanished.isNotEmpty())
            assertTrue("no ref of a row scrolled away is reused", after.elements.none { it.ref in vanished })
        }

    /**
     * The Settings home page as the device shows it: a page (a ScrollView, so a list) holding an
     * app bar and the scrolling content, four full-size wrappers with view ids, then a list whose
     * rows are a clickable container with a title and a summary; [titles] from the top down.
     */
    private fun page(titles: List<String>): AccessibilityNodeInfo {
        val content = Rect(0, Feed.LIST_TOP, 1000, Feed.LIST_BOTTOM)
        val bar = Rect(0, 60, 1000, Feed.LIST_TOP)
        val scroll =
            node("android.widget.ScrollView", content) {
                isScrollable = true
                viewIdResourceName = "$APP:id/main_content_scrollable_container"
            }
        val list =
            node("androidx.recyclerview.widget.RecyclerView", content) {
                isScrollable = true
                viewIdResourceName = "$APP:id/recycler_view"
            }
        val inner =
            (1..WRAPPERS).fold(scroll) { outer, n ->
                node("android.widget.LinearLayout", content) { viewIdResourceName = "$APP:id/wrapper$n" }
                    .also { outer.with(it) }
            }
        inner.with(list)
        titles.forEachIndexed { index, title ->
            val y = Feed.LIST_TOP + index * ROW_PX
            list.with(
                node("android.widget.LinearLayout", Rect(0, y, 1000, y + ROW_PX)) { isClickable = true }.with(
                    node("android.widget.TextView", Rect(200, y, 800, y + ROW_PX / 2)) {
                        text = title
                        viewIdResourceName = "$APP:id/title"
                    },
                    node("android.widget.TextView", Rect(200, y + ROW_PX / 2, 800, y + ROW_PX)) {
                        text = "$title, details"
                        viewIdResourceName = "$APP:id/summary"
                    },
                ),
            )
        }
        return node("android.widget.ScrollView", Rect(0, 60, 1000, Feed.LIST_BOTTOM)) {
            viewIdResourceName = "$APP:id/settings_homepage_container"
        }.with(button("Search Settings", bar, viewId = "search_action_bar"), scroll)
    }

    @Test
    fun rowsOfAListInsideAScrollingPageKeepTheirRefsWhenThePageIsDragged() =
        runTest {
            // Device run r1 (Settings home): the inner list's own row signature was its first labels,
            // which a drag changes, so every row below it got a new ref.
            val port = mainPort(host)
            host.show(page(PEOPLE.take(ROWS)), extra = listOf(statusBar()))
            val before = port.snapshot()
            host.show(page(PEOPLE.drop(SCROLLED).take(ROWS)), extra = listOf(statusBar()))

            val after = port.snapshot()

            val moved = movedRows(before, after)
            assertTrue("some named row stayed on screen and moved", moved.isNotEmpty())
            moved.forEach { (was, now) -> assertEquals("ref of '${was.name}'", was.ref, now.ref) }
        }

    private companion object {
        const val WRAPPERS = 4
        const val SYSTEM_UI = "com.android.systemui"
        const val STATUS_BAR = 50
        const val ROWS = 16
        const val SCROLLED = 3
        val PEOPLE = ('A'..'Z').map { "Person $it" }

        /**
         * Named elements on both screens, unique by name and role on each, with the same height
         * (not cut by the list's edge) and another top: the rows the scroll moved, before and after.
         */
        fun movedRows(
            before: ScreenSnapshot,
            after: ScreenSnapshot,
        ): List<Pair<UiElement, UiElement>> {
            fun ScreenSnapshot.unique(): Map<Pair<String, ElementRole>, UiElement> =
                elements
                    .filter { it.name != null }
                    .groupBy { it.name!! to it.role }
                    .filterValues { it.size == 1 }
                    .mapValues { it.value.single() }
            val now = after.unique()
            return before.unique().mapNotNull { (key, was) ->
                now[key]
                    ?.takeIf {
                        it.bounds.top != was.bounds.top &&
                            it.bounds.bottom - it.bounds.top == was.bounds.bottom - was.bounds.top
                    }?.let {
                        was to it
                    }
            }
        }

        /** Refs of the named elements of [before] whose name is nowhere on [after]. */
        fun vanishedRefs(
            before: ScreenSnapshot,
            after: ScreenSnapshot,
        ): Set<ElementRef> =
            before.elements
                .filter { it.name != null && after.elements.none { now -> now.name == it.name } }
                .map { it.ref }
                .toSet()
    }
}
