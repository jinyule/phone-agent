package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a row is, and what tells two rows apart (fix list r2 B1): every label of the row counts,
 * also beyond the first few and beyond any length, also where the row is clipped; a card outside
 * any list is a row too, and so is the only row of a list.
 */
@RunWith(AndroidJUnit4::class)
class RowIdentityTest {
    private val host = FakeHost()

    /** "Add to cart" buttons of the last [shop], by product. */
    private val adds = HashMap<String, AccessibilityNodeInfo>()

    /**
     * A product list whose rows all start with the same three labels (badges and a photo
     * description) and whose container is not clickable; the product is the fourth label.
     */
    private fun shop(
        products: List<String>,
        top: Int,
    ): AccessibilityNodeInfo {
        adds.clear()
        val list =
            node("androidx.recyclerview.widget.RecyclerView", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
                isScrollable = true
            }
        products.forEachIndexed { index, product ->
            val y = top + index * ROW_PX
            val add = button("Add to cart", Rect(700, y, 1000, y + ROW_PX), viewId = "add")
            adds[product] = add
            list.with(
                node("android.widget.LinearLayout", Rect(0, y, 1000, y + ROW_PX)).with(
                    node("android.widget.TextView", Rect(0, y, 100, y + ROW_PX)) { text = "Official" },
                    node("android.widget.TextView", Rect(100, y, 200, y + ROW_PX)) { text = "Free shipping" },
                    node("android.widget.ImageView", Rect(200, y, 300, y + ROW_PX)) { contentDescription = "Photo" },
                    node("android.widget.TextView", Rect(300, y, 700, y + ROW_PX)) { text = product },
                    add,
                ),
            )
        }
        return list
    }

    /** The "Add to cart" of [product]'s row (found by the row's top). */
    private fun ScreenSnapshot.addOf(product: String): UiElement {
        val title = elements.single { it.name == product }
        return elements.single { it.name == "Add to cart" && it.bounds.top == title.bounds.top }
    }

    /** The container of [product]'s row (the title's parent; FULL snapshots only). */
    private fun ScreenSnapshot.rowOf(product: String): ElementRef = elements.single { it.name == product }.parent!!

    private fun ScreenSnapshot.refs(): Set<ElementRef> = elements.map { it.ref }.toSet()

    @Test
    fun aRowReplacedInPlaceThatDiffersOnlyFromItsFourthLabelOnIsStale() =
        runTest {
            // Claude r2 B1 (a) / Codex R2-B1.
            host.show(shop(listOf("Kettle"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(shop(listOf("Lamp"), top = 300))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.addOf("Kettle").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertFalse(adds.getValue("Lamp").clicked)
            assertTrue(host.gestures.isEmpty())
        }

    @Test
    fun aRowReplacedByAnotherThatAlsoMovedIsStale() =
        runTest {
            // Pairing an element that moved compares every label, not the first three.
            host.show(shop(listOf("Kettle"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(shop(listOf("Lamp"), top = 300 + ROW_PX))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.addOf("Kettle").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertFalse(adds.getValue("Lamp").clicked)
            assertTrue(host.gestures.isEmpty())
        }

    @Test
    fun aRowWhoseNumbersChangedGetsNewRefsRatherThanAGuess() =
        runTest {
            // "Photo 3/10" and "Photo 4/10" are other rows; a row cannot tell a counter from them.
            host.show(shop(listOf("Kettle (12 sold)", "Lamp (3 sold)"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(shop(listOf("Kettle (13 sold)", "Lamp (3 sold)"), top = 300))

            val s2 = port.snapshot()

            assertFalse(s2.addOf("Kettle (13 sold)").ref in s1.refs())
            assertEquals("the row that did not change", s1.addOf("Lamp (3 sold)").ref, s2.addOf("Lamp (3 sold)").ref)
        }

    /** A player's controls, no list around them: the title, the time played, the length and [pause]. */
    private fun player(
        played: String,
        title: String = "Song",
        pause: AccessibilityNodeInfo = button("Pause", Rect(400, 1400, 600, 1500), viewId = "pause"),
    ): AccessibilityNodeInfo =
        node("android.widget.LinearLayout", Rect(0, 1200, 1000, 1500)).with(
            node("android.widget.TextView", Rect(0, 1200, 1000, 1300)) { text = title },
            node("android.widget.TextView", Rect(0, 1300, 200, 1400)) { text = played },
            node("android.widget.TextView", Rect(800, 1300, 1000, 1400)) { text = "3:45" },
            pause,
        )

    @Test
    fun aControlNextToATickingTimeKeepsItsRef() =
        runTest {
            host.show(player("0:12"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            val pause = button("Pause", Rect(400, 1400, 600, 1500), viewId = "pause")
            host.show(player("0:15", pause = pause))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.elements.single { it.name == "Pause" }.ref)), s1.id)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertTrue(pause.clicked)
        }

    @Test
    fun aControlWhoseSurroundingsWereReplacedIsStale() =
        runTest {
            host.show(player("0:12", title = "Song"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(player("0:12", title = "Another song"))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.elements.single { it.name == "Pause" }.ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertTrue(host.gestures.isEmpty())
        }

    @Test
    fun aRowInsertedOnTopBetweenPlanAndActionMakesTheRefStale() =
        runTest {
            // Claude r2 B1 (b): Kettle's button lands where Toaster's was.
            host.show(shop(listOf("Kettle", "Toaster"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(shop(listOf("Lamp", "Kettle", "Toaster"), top = 300))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.addOf("Toaster").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertTrue(adds.values.none { it.clicked })
            assertTrue(host.gestures.isEmpty())
        }

    @Test
    fun aRowInsertedOnTopDoesNotHandItsRefsToAnotherProduct() =
        runTest {
            // Claude r2 B1 (c): WP06's diff pairs by ref; a ref must not move to another product.
            val full = SnapshotOptions(detail = SnapshotDetail.FULL)
            host.show(shop(listOf("Kettle", "Toaster"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot(full)
            host.show(shop(listOf("Lamp", "Kettle", "Toaster"), top = 300))

            val s2 = port.snapshot(full)

            assertFalse("Lamp's button is new", s2.addOf("Lamp").ref in s1.refs())
            assertFalse("Lamp's row is new", s2.rowOf("Lamp") in s1.refs())
            assertNotEquals(s1.addOf("Toaster").ref, s2.addOf("Kettle").ref)
            assertNotEquals(s1.rowOf("Toaster"), s2.rowOf("Kettle"))
        }

    @Test
    fun labelsThatShareTheirFirst64CharactersStillTellRowsApart() =
        runTest {
            // Codex R2-B1: the labels differ only after the 64th character.
            val feed = Feed()
            val prefix = "x".repeat(64)
            host.show(feed.list(listOf("${prefix}Bob"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(feed.list(listOf("${prefix}Ada"), top = 300))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.likeOf("${prefix}Bob").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertFalse(feed.likes.getValue("${prefix}Ada").clicked)
        }

    @Test
    fun aCardOutsideAnyListIsARow() =
        runTest {
            // Codex R2-B2 (a): an anonymous card that no list holds.
            val card = { person: String, like: AccessibilityNodeInfo ->
                node("android.widget.LinearLayout", Rect(0, 300, 1000, 400)).with(
                    node("android.widget.TextView", Rect(0, 300, 600, 400)) { text = person },
                    like,
                )
            }
            host.show(card("Bob", button("Like", Rect(700, 300, 1000, 400), viewId = "like")))
            val port = mainPort(host)
            val s1 = port.snapshot()
            val adasLike = button("Like", Rect(700, 300, 1000, 400), viewId = "like")
            host.show(card("Ada", adasLike))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.likeOf("Bob").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertFalse(adasLike.clicked)
        }

    @Test
    fun theOnlyRowOfAListIsARowEvenWhenItFillsTheList() =
        runTest {
            // Codex R2-B2 (b): a full-page feed (short videos, a pager) shows one row at a time.
            val page = { person: String, like: AccessibilityNodeInfo ->
                node("androidx.recyclerview.widget.RecyclerView", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
                    isScrollable = true
                    addAction(AccessibilityAction.ACTION_SCROLL_DOWN)
                }.with(
                    node("android.widget.FrameLayout", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)).with(
                        node("android.widget.TextView", Rect(0, 1500, 600, 1600)) { text = person },
                        like,
                    ),
                )
            }
            host.show(page("@Bob", button("Like", Rect(800, 1000, 1000, 1100), viewId = "like")))
            val port = mainPort(host)
            val s1 = port.snapshot()
            val bobsLike = s1.elements.single { it.name == "Like" }.ref
            val adasLike = button("Like", Rect(800, 1000, 1000, 1100), viewId = "like")
            host.show(page("@Ada", adasLike))

            val outcome = port.act(UiAction.Tap(Target.Ref(bobsLike)), s1.id)
            val s2 = port.snapshot()

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertFalse(adasLike.clicked)
            assertNotEquals(
                "the next screen does not hand the ref on",
                bobsLike,
                s2.elements.single { it.name == "Like" }.ref,
            )
        }

    @Test
    fun aClickableRowReplacedInPlaceIsStaleAsBefore() =
        runTest {
            // Control: the row container is clickable and its first labels differ.
            val feed = Feed(clickableRows = true)
            host.show(feed.list(listOf("Bob"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(feed.list(listOf("Ada"), top = 300))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.likeOf("Bob").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertFalse(feed.likes.getValue("Ada").clicked)
        }

    /**
     * A feed of tall cards ([author, time, text, Like], [CARD_PX] high) from [top] down; parts
     * outside the list are not visible to the user, as a RecyclerView reports a clipped child.
     */
    private fun cards(
        people: List<String>,
        top: Int,
        clickable: Boolean = false,
    ): AccessibilityNodeInfo {
        val list =
            node("androidx.recyclerview.widget.RecyclerView", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
                isScrollable = true
            }
        people.forEachIndexed { index, person ->
            val y = top + index * CARD_PX
            val part = { label: String, from: Int, to: Int, cls: String ->
                node(cls, Rect(0, y + from, 1000, y + to)) {
                    text = label
                    isVisibleToUser = y + to > LIST_TOP && y + from < LIST_BOTTOM
                }
            }
            list.with(
                node("android.widget.LinearLayout", Rect(0, y, 1000, y + CARD_PX)) { isClickable = clickable }.with(
                    part(person, 0, 100, "android.widget.TextView"),
                    part("5 min ago", 100, 150, "android.widget.TextView"),
                    part("$person's text", 150, 500, "android.widget.TextView"),
                    part("Like", 500, CARD_PX, "android.widget.Button").apply { isClickable = true },
                ),
            )
        }
        return list
    }

    @Test
    fun aCardWhoseHeadScrolledOutOfViewKeepsItsRefs() = cardScrolledUp(clickable = false)

    @Test
    fun aClickableCardWhoseHeadScrolledOutOfViewKeepsItsRefs() = cardScrolledUp(clickable = true)

    private fun cardScrolledUp(clickable: Boolean) =
        runTest {
            // Claude r2 S1 P2: scrolled up 150 px, Ada's author and time are clipped away.
            host.show(cards(listOf("Ada", "Bob", "Cy"), top = LIST_TOP, clickable))
            val port = mainPort(host)
            val full = SnapshotOptions(detail = SnapshotDetail.FULL)
            val s1 = port.snapshot(full)
            host.show(cards(listOf("Ada", "Bob", "Cy"), top = LIST_TOP - 150, clickable))

            val s2 = port.snapshot(full)

            val text = s1.elements.single { it.name == "Ada's text" }
            assertEquals("the card", text.parent, s2.elements.single { it.name == "Ada's text" }.parent)
            assertEquals("its text", text.ref, s2.elements.single { it.name == "Ada's text" }.ref)
            val like = { s: ScreenSnapshot -> s.elements.single { it.name == "Like" && it.parent == text.parent }.ref }
            assertEquals("its Like", like(s1), like(s2))
        }

    /** A home page of sections, each a title and a row of three products sideways ([SECTION_PX] high). */
    private fun sections(top: Int): AccessibilityNodeInfo {
        val page =
            node("androidx.recyclerview.widget.RecyclerView", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
                isScrollable = true
            }
        listOf("Deals", "New").forEachIndexed { index, section ->
            val y = top + index * SECTION_PX
            val carousel =
                node("androidx.recyclerview.widget.RecyclerView", Rect(0, y + 100, 1000, y + SECTION_PX)) {
                    isScrollable = true
                }
            (1..3).forEach { n ->
                val x = (n - 1) * 333
                carousel.with(
                    node("android.widget.LinearLayout", Rect(x, y + 100, x + 333, y + SECTION_PX)).with(
                        node("android.widget.TextView", Rect(x, y + 100, x + 333, y + 200)) { text = "$section $n" },
                        button("Add", Rect(x, y + 300, x + 333, y + SECTION_PX), viewId = "add"),
                    ),
                )
            }
            page.with(
                node("android.widget.LinearLayout", Rect(0, y, 1000, y + SECTION_PX)).with(
                    node("android.widget.TextView", Rect(0, y, 1000, y + 100)) {
                        text = section
                        isVisibleToUser = y + 100 > LIST_TOP
                    },
                    carousel,
                ),
            )
        }
        return page
    }

    @Test
    fun productsOfASectionWhoseTitleScrolledOutOfViewKeepTheirRefs() =
        runTest {
            // Claude r2 S1 P3: the "Deals" title is clipped away; its products are fully visible.
            host.show(sections(top = LIST_TOP))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(sections(top = LIST_TOP - 110))

            val s2 = port.snapshot()

            for (n in 1..3) {
                val name = "Deals $n"
                assertEquals(
                    name,
                    s1.elements.single { it.name == name }.ref,
                    s2.elements.single { it.name == name }.ref,
                )
                val add = { s: ScreenSnapshot ->
                    val title = s.elements.single { it.name == name }
                    s.elements
                        .single {
                            it.name == "Add" && it.bounds.left == title.bounds.left &&
                                it.parent == title.parent
                        }.ref
                }
                assertEquals("Add of $name", add(s1), add(s2))
            }
        }

    private companion object {
        const val LIST_TOP = 200
        const val LIST_BOTTOM = 1800
        const val CARD_PX = 600
        const val SECTION_PX = 400
    }
}
