package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Direction
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Element identity across screens (fix list r1 B1, B8): a ref names one element for as long as
 * the port can tell it is the same one; when it cannot (same name, same view id, other row), the
 * element gets a new ref and the old ref is void. WP06's diff pairs screens only by ref.
 */
@RunWith(AndroidJUnit4::class)
class RefIdentityTest {
    private val host = FakeHost()
    private val feed = Feed()

    private fun ScreenSnapshot.ref(name: String): Target.Ref = Target.Ref(elements.single { it.name == name }.ref)

    private fun ScreenSnapshot.refsOf(person: String): List<ElementRef> =
        listOf(elements.single { it.name == person }.ref, likeOf(person).ref)

    @Test
    fun aLikeScrolledUpOneRowKeepsItsRefAndTheRefNeverHitsTheNextRow() =
        runTest {
            // Claude r1 p1 / Codex r1 B1: every row has the same "Like" (same view id).
            val list = feed.list(listOf("Ada", "Bob", "Cy", "Dee"), top = 300)
            host.show(list)
            list.log.answer = { action, _ ->
                if (action == AccessibilityAction.ACTION_SCROLL_DOWN.id) {
                    host.show(feed.list(listOf("Bob", "Cy", "Dee", "Eve"), top = 300))
                }
                true
            }
            val port = mainPort(host)
            val s1 = port.snapshot()
            val bobLike = s1.likeOf("Bob").ref
            val adaRefs = s1.refsOf("Ada")

            val s2 = port.act(UiAction.Scroll(null, Direction.DOWN), s1.id).after!!

            assertEquals("Bob's Like keeps its ref", bobLike, s2.likeOf("Bob").ref)
            assertTrue("Ada's refs are void, not handed on", adaRefs.none { s2.at(it) != null })
            val tap = port.act(UiAction.Tap(Target.Ref(bobLike)), s2.id)
            assertEquals(ActionOutcome.Status.Ok, tap.status)
            assertTrue(feed.likes.getValue("Bob").clicked)
            assertFalse("the ref never names Cy's Like", feed.likes.getValue("Cy").clicked)
        }

    @Test
    fun aRowInsertedOnTopShiftsTheOthersWithoutTakingTheirRefs() =
        runTest {
            // Claude r1 p2: a new row on top, everything 50 px lower; no element stays in place.
            host.show(feed.list(listOf("Ada", "Bob"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(feed.list(listOf("Zed", "Ada", "Bob"), top = 350))

            val s2 = port.snapshot()

            assertEquals(s1.refsOf("Ada"), s2.refsOf("Ada"))
            assertEquals(s1.refsOf("Bob"), s2.refsOf("Bob"))
            assertTrue("Zed is new", s2.refsOf("Zed").none { s1.at(it) != null })
        }

    @Test
    fun likesUnderUniquelyNamedRowsFollowTheirRowsThroughAScrollOfOneAndAHalfRows() =
        runTest {
            host.show(feed.list(listOf("Ada", "Bob", "Cy", "Dee"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(feed.list(listOf("Bob", "Cy", "Dee", "Eve", "Fay"), top = 250))

            val s2 = port.snapshot()

            for (person in listOf("Bob", "Cy", "Dee")) assertEquals(person, s1.refsOf(person), s2.refsOf(person))
            assertTrue(s1.refsOf("Ada").none { s2.at(it) != null })
            assertTrue(s2.refsOf("Eve").none { s1.at(it) != null })
        }

    @Test
    fun clickableRowsCarryTheirLikesAlong() =
        runTest {
            val rows = Feed(clickableRows = true)
            host.show(rows.list(listOf("Ada", "Bob", "Cy"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(rows.list(listOf("Bob", "Cy", "Dee"), top = 300))

            val s2 = port.snapshot()

            val like = s2.likeOf("Bob")
            assertEquals(s1.likeOf("Bob").ref, like.ref)
            assertEquals("Bob Like", s2.at(like.parent!!)?.name)
        }

    @Test
    fun aRowInsertedBetweenPlanAndActionMakesTheRefStale() =
        runTest {
            // Claude r1 p1b: Ada's Like lands exactly where Bob's was.
            host.show(feed.list(listOf("Ada", "Bob"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(feed.list(listOf("Zed", "Ada", "Bob"), top = 300))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.likeOf("Bob").ref)), s1.id)

            assertStale(outcome.status)
            assertTrue(feed.likes.values.none { it.clicked })
            assertTrue(host.gestures.isEmpty())
        }

    @Test
    fun aButtonReplacedInPlaceByAnotherViewIsStale() =
        runTest {
            // Codex r1 B2: same window, role, name and place; another view id.
            host.show(button("OK", Rect(0, 1000, 500, 1100), viewId = "save"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            val delete = button("OK", Rect(0, 1000, 500, 1100), viewId = "delete")
            host.show(delete)

            val outcome = port.act(UiAction.Tap(s1.ref("OK")), s1.id)

            assertStale(outcome.status)
            assertFalse(delete.clicked)
        }

    @Test
    fun aRowReplacedInPlaceByAnotherPersonIsStale() =
        runTest {
            // Codex x1-1: Bob's row becomes Ada's; the Like keeps window, role, name, view id and place.
            host.show(feed.list(listOf("Bob"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(feed.list(listOf("Ada"), top = 300))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.likeOf("Bob").ref)), s1.id)

            assertStale(outcome.status)
            assertFalse(feed.likes.getValue("Ada").clicked)
        }

    @Test
    fun identicalElementsThatDidNotMoveKeepTheirRefs() =
        runTest {
            val list = { tops: List<Int> ->
                node("androidx.recyclerview.widget.RecyclerView", Rect(0, 200, 1000, 1800)) { isScrollable = true }
                    .with(*tops.map { button("Like", Rect(0, it, 1000, it + ROW_PX)) }.toTypedArray())
            }
            host.show(list(listOf(300, 400)))
            val port = mainPort(host)
            val s1 = port.snapshot()
            // The first one went away; the second did not move.
            host.show(list(listOf(400)))

            val s2 = port.snapshot()

            assertEquals(
                s1.elements
                    .filter { it.name == "Like" }
                    .maxBy { it.bounds.top }
                    .ref,
                s2.elements.single { it.name == "Like" }.ref,
            )
        }

    @Test
    fun refsSurviveAnActionBetweenTwoFullSnapshots() =
        runTest {
            // Claude r1 S2 / Codex x1-2: FULL, tap OK, FULL again; nothing on screen changes.
            val panel =
                node("android.widget.LinearLayout", Rect(0, 0, 1000, 900)).with(
                    node("android.view.View", Rect(0, 0, 500, 400)),
                    node("android.view.View", Rect(500, 0, 1000, 400)),
                )
            host.show(panel, button("OK", Rect(0, 1000, 500, 1100)))
            val port = mainPort(host)
            val s1 = port.snapshot(SnapshotOptions(detail = SnapshotDetail.FULL))

            val tapped = port.act(UiAction.Tap(s1.ref("OK")), s1.id)
            val s3 = port.snapshot(SnapshotOptions(detail = SnapshotDetail.FULL))

            assertEquals(ActionOutcome.Effect.NO_CHANGE, tapped.effect)
            assertEquals("read back at the detail of the screen it was based on", s1.elements, tapped.after!!.elements)
            assertEquals("no false removals", emptyList<ElementRef>(), tapped.diff!!.removed)
            assertEquals(s1.elements.map { it.ref }, s3.elements.map { it.ref })
        }
}
