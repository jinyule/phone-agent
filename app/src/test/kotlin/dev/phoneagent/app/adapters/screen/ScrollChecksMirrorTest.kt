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
import org.junit.Assert.assertNotEquals
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
     * The Settings home page as the device shows it: a page (a ScrollView, which holds all of its
     * content and so is not a list: what it holds is outside lists) holding an app bar and the
     * scrolling content, four full-size wrappers with view ids, then a list whose rows are a
     * clickable container with a title and a summary; [titles] from the top down.
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

    /**
     * A list of Settings rows from [top] down, as a RecyclerView reports them: each row is a
     * clickable container with a title and a summary in its upper part and padding below; a row
     * scrolled wholly out is gone, one scrolled partly out has bounds cut at the list's edge, and
     * its children out of view are not visible to the user.
     */
    private fun settingsList(
        titles: List<String>,
        top: Int,
    ): AccessibilityNodeInfo {
        val list =
            node("androidx.recyclerview.widget.RecyclerView", Rect(0, Feed.LIST_TOP, 1000, Feed.LIST_BOTTOM)) {
                isScrollable = true
            }
        titles.forEachIndexed { index, title ->
            val y = top + index * ROW_PX
            if (y + ROW_PX > Feed.LIST_TOP) {
                val part = { label: String, from: Int, to: Int ->
                    node("android.widget.TextView", Rect(200, y + from, 800, y + to)) {
                        text = label
                        isVisibleToUser = y + to > Feed.LIST_TOP
                    }
                }
                list.with(
                    node("android.widget.LinearLayout", Rect(0, maxOf(y, Feed.LIST_TOP), 1000, y + ROW_PX)) {
                        isClickable = true
                    }.with(part(title, 0, LABELS_PX / 2), part("$title, details", LABELS_PX / 2, LABELS_PX)),
                )
            }
        }
        return list
    }

    @Test
    fun aRowWhoseBorrowedNameScrolledAwayKeepsItsRefAndPassesTheDeviceCheck() =
        runTest {
            // Fix list r3 S5: the device's Battery row; its title and summary scrolled out of view,
            // only its padding is left, so the name it borrows from them is gone.
            val port = mainPort(host)
            host.show(settingsList(PEOPLE.take(ROWS), top = Feed.LIST_TOP))
            val before = port.snapshot()
            host.show(settingsList(PEOPLE.take(ROWS), top = Feed.LIST_TOP - ROW_PX - SCROLLED_INTO_PADDING))

            val after = port.snapshot()

            val vanished = vanishedRefs(before, after)
            val reused = reusedRefs(before, after, vanished)
            assertEquals(1, reused.size)
            val (was, now) = reused.single()
            assertEquals("Person B Person B, details", was.name)
            assertTrue("passes the device check: $reused", passesTheDeviceCheck(reused, after))
            assertEquals(null, now.name)
        }

    @Test
    fun anotherRowCutDownTheSameWayDoesNotTakeTheRefOfARowScrolledAway() =
        runTest {
            // The negative: Person B scrolls away wholly, Person C is cut down to its padding at the
            // same place; the device check alone would let C reuse B's ref.
            val port = mainPort(host)
            host.show(settingsList(PEOPLE.take(ROWS), top = Feed.LIST_TOP - SCROLLED_INTO_PADDING))
            val before = port.snapshot()
            host.show(settingsList(PEOPLE.take(ROWS), top = Feed.LIST_TOP - 2 * ROW_PX - SCROLLED_INTO_PADDING))

            val after = port.snapshot()

            val bob = before.elements.single { it.name == "Person B Person B, details" }
            val cy = after.elements.single { it.role == bob.role && it.parent == bob.parent && it.name == null }
            val cyBefore = before.elements.single { it.name == "Person C Person C, details" }
            assertTrue(
                "the device check alone would let C take B's ref",
                passesTheDeviceCheck(listOf(bob to cy), after),
            )
            assertNotEquals("C does not take B's ref", bob.ref, cy.ref)
            assertEquals("C keeps its own", cyBefore.ref, cy.ref)
        }

    private companion object {
        const val WRAPPERS = 4
        const val SYSTEM_UI = "com.android.systemui"
        const val STATUS_BAR = 50
        const val ROWS = 16
        const val SCROLLED = 3
        const val LABELS_PX = 80
        const val SCROLLED_INTO_PADDING = 90
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

        /** The elements of [after] that took a ref of [vanished], with the element of [before] it was. */
        fun reusedRefs(
            before: ScreenSnapshot,
            after: ScreenSnapshot,
            vanished: Set<ElementRef>,
        ): List<Pair<UiElement, UiElement>> =
            after.elements.filter { it.ref in vanished }.map { now ->
                before.elements.single { it.ref == now.ref } to now
            }

        /**
         * The device test's check on [reused] refs of [after]: at most one, a row cut at the top of
         * its list (same role and parent, no name left, lower than before).
         */
        fun passesTheDeviceCheck(
            reused: List<Pair<UiElement, UiElement>>,
            after: ScreenSnapshot,
        ): Boolean {
            val height = { element: UiElement -> element.bounds.bottom - element.bounds.top }
            return reused.size <= 1 &&
                reused.all { (was, now) ->
                    val list = after.elements.singleOrNull { it.ref == now.parent }
                    now.role == was.role && now.parent == was.parent && now.name == null &&
                        height(now) < height(was) && list != null && now.bounds.top <= list.bounds.top
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
