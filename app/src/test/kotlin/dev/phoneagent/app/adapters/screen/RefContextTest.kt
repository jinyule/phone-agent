package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
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
 * What an element is told apart by (fix list r3 B1): outside lists, every label of its window
 * outside lists (rule 1); in a list whose rows have no node of their own, the labels from after
 * the previous control up to it (rule 2); nothing that was read only in part (rule 3) or that a
 * list's edge cut down to its controls (rule 4). The negatives assert that nothing on the device
 * was touched.
 */
@RunWith(AndroidJUnit4::class)
class RefContextTest {
    private val host = FakeHost()

    /** Every node the screens of a test were made of, to check that none was acted on. */
    private val made = ArrayList<AccessibilityNodeInfo>()

    private fun make(
        cls: String,
        bounds: Rect,
        setup: AccessibilityNodeInfo.() -> Unit = {},
    ): AccessibilityNodeInfo = node(cls, bounds, setup = setup).also { made += it }

    private fun text(
        label: String,
        bounds: Rect,
    ): AccessibilityNodeInfo = make("android.widget.TextView", bounds) { text = label }

    private fun press(
        label: String,
        bounds: Rect,
        id: String? = null,
    ): AccessibilityNodeInfo =
        make("android.widget.Button", bounds) {
            text = label
            isClickable = true
            id?.let { viewIdResourceName = "$APP:id/$it" }
        }

    private fun layout(bounds: Rect): AccessibilityNodeInfo = make("android.widget.LinearLayout", bounds)

    private fun assertNothingTouched() {
        assertTrue("no gesture", host.gestures.isEmpty())
        assertTrue("no node action", made.all { it.log.actions.isEmpty() })
    }

    private fun ScreenSnapshot.named(name: String): UiElement = elements.single { it.name == name }

    private fun ScreenSnapshot.refs() = elements.map { it.ref }.toSet()

    private val full = SnapshotOptions(detail = SnapshotDetail.FULL)

    // Rule 1: outside lists, the window's labels.

    /** Codex r3 B1: an anonymous card, the title in one layout, its buttons in another. */
    private fun card(person: String): AccessibilityNodeInfo =
        layout(Rect(0, 300, 1000, 600)).with(
            text(person, Rect(0, 300, 1000, 400)),
            layout(Rect(0, 500, 1000, 600)).with(
                press("Like", Rect(0, 500, 400, 600), id = "like"),
                press("Share", Rect(700, 500, 1000, 600), id = "share"),
            ),
        )

    @Test
    fun aCardWhoseTitleAndButtonsSitInTwoLayoutsDoesNotHandItsButtonsToAnother() =
        runTest {
            host.show(card("Bob"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(card("Ada"))
            val s2 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.named("Like").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertFalse("Ada's Like is new", s2.named("Like").ref in s1.refs())
            assertNothingTouched()
        }

    /** Claude r3 B1 (b): a product page, [wrapped] in a NestedScrollView or not. */
    private fun product(
        title: String,
        price: String,
        wrapped: Boolean,
    ): AccessibilityNodeInfo {
        val content =
            layout(Rect(0, 200, 1000, 1800)).with(
                text(title, Rect(0, 200, 1000, 300)),
                text(price, Rect(0, 300, 1000, 400)),
                press("Buy now", Rect(0, 1600, 1000, 1700), id = "buy"),
            )
        return if (wrapped) {
            make("androidx.core.widget.NestedScrollView", Rect(0, 200, 1000, 1800)) { isScrollable = true }
                .with(content)
        } else {
            content
        }
    }

    @Test
    fun aProductReplacedInPlaceInsideAScrollViewIsStale() = productReplacedInPlace(wrapped = true)

    @Test
    fun aProductReplacedInPlaceOutsideAScrollViewIsStale() = productReplacedInPlace(wrapped = false)

    private fun productReplacedInPlace(wrapped: Boolean) =
        runTest {
            host.show(product("Kettle", "25 EUR", wrapped))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(product("Lamp", "40 EUR", wrapped))
            val s2 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.named("Buy now").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertFalse("the Lamp's Buy now is new", s2.named("Buy now").ref in s1.refs())
            assertNothingTouched()
        }

    /** Claude r3 B1 (c): the page body scrolls, the bottom bar stays. */
    private fun page(
        title: String,
        stock: String,
    ): Array<AccessibilityNodeInfo> =
        arrayOf(
            make("androidx.core.widget.NestedScrollView", Rect(0, 200, 1000, 1700)) { isScrollable = true }.with(
                layout(Rect(0, 200, 1000, 1700)).with(
                    text(title, Rect(0, 200, 1000, 300)),
                    text(stock, Rect(0, 300, 1000, 400)),
                ),
            ),
            layout(Rect(0, 1700, 1000, 1800)).with(
                press("Add to cart", Rect(0, 1700, 500, 1800), id = "add"),
                press("Buy now", Rect(500, 1700, 1000, 1800), id = "buy"),
            ),
        )

    @Test
    fun aPageReplacedInPlaceAboveAnUnchangedBottomBarMakesTheBarStale() =
        runTest {
            host.show(*page("Kettle", "In stock"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(*page("Lamp", "In stock"))
            val s2 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.named("Buy now").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertFalse("the bar's buttons are new", s2.named("Buy now").ref in s1.refs())
            assertNothingTouched()
        }

    /** A chain of [depth] invisible layouts under [top]; the deepest one is returned. */
    private fun chain(
        top: AccessibilityNodeInfo,
        depth: Int,
    ): AccessibilityNodeInfo =
        (1..depth).fold(top) { parent, _ ->
            node("android.widget.LinearLayout", Rect(0, 400, 1000, 500)) { isVisibleToUser = false }
                .also { parent.with(it) }
        }

    /**
     * Codex r3 B2: a card whose Like comes first and whose title comes only after a subtree the
     * read cannot finish ([deep]: deeper than its depth budget, else more nodes than its budget).
     */
    private fun cutCard(
        person: String,
        deep: Boolean,
    ): AccessibilityNodeInfo {
        val card = layout(Rect(0, 300, 1000, 800)).with(press("Like", Rect(0, 500, 400, 600), id = "like"))
        val title = text(person, Rect(0, 400, 1000, 500))
        if (deep) {
            chain(card, ScreenReader.MAX_DEPTH + 2).with(title)
        } else {
            repeat(ScreenReader.MAX_NODES) {
                card.with(node("android.view.View", Rect(0, 400, 1000, 500)) { isVisibleToUser = false })
            }
            card.with(title)
        }
        return card
    }

    @Test
    fun aCardCutByTheDepthBudgetBeforeItsTitleIsStale() = cutBeforeTheTitle(deep = true)

    @Test
    fun aCardCutByTheNodeBudgetBeforeItsTitleIsStale() = cutBeforeTheTitle(deep = false)

    private fun cutBeforeTheTitle(deep: Boolean) =
        runTest {
            host.show(cutCard("Bob", deep))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(cutCard("Ada", deep))
            val s2 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.named("Like").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertFalse("Ada's Like is new", s2.named("Like").ref in s1.refs())
            assertNothingTouched()
        }

    // Rule 2: a list whose rows have no node of their own.

    /** Claude r3 B1 (a): a lazy list of [product, "Add to cart"] rows without a row node, from [top] down. */
    private fun flat(
        products: List<String>,
        top: Int = 300,
    ): AccessibilityNodeInfo {
        val list =
            make("android.view.View", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
                isScrollable = true
                collectionInfo = AccessibilityNodeInfo.CollectionInfo(products.size, 1, false)
            }
        products.forEachIndexed { index, product ->
            val y = top + index * ROW_PX
            list.with(
                text(product, Rect(0, y, 600, y + ROW_PX)),
                press("Add to cart", Rect(700, y, 1000, y + ROW_PX), id = "add"),
            )
        }
        return list
    }

    /** The "Add to cart" right after [product] in reading order. */
    private fun ScreenSnapshot.addAfter(product: String): UiElement = elements[elements.indexOf(named(product)) + 1]

    @Test
    fun aFlatRowReplacedInPlaceIsStale() =
        runTest {
            host.show(flat(listOf("Kettle", "Toaster")))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(flat(listOf("Lamp", "Toaster")))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.addAfter("Kettle").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertNothingTouched()
        }

    @Test
    fun theOnlyFlatRowReplacedInPlaceIsStale() =
        runTest {
            host.show(flat(listOf("Kettle")))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(flat(listOf("Lamp")))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.addAfter("Kettle").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertNothingTouched()
        }

    @Test
    fun aFlatRowInsertedOnTopMakesTheRefsBelowStale() =
        runTest {
            host.show(flat(listOf("Kettle", "Toaster")))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(flat(listOf("Lamp", "Kettle", "Toaster")))

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.addAfter("Toaster").ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertNothingTouched()
        }

    @Test
    fun aFlatRowInsertedOnTopDoesNotHandItsRefsToAnotherProduct() =
        runTest {
            // WP06 pairs screens by ref: a ref in both screens must name the same product's button.
            host.show(flat(listOf("Kettle", "Toaster")))
            val port = mainPort(host)
            val s1 = port.snapshot(full)
            host.show(flat(listOf("Lamp", "Kettle", "Toaster")))

            val s2 = port.snapshot(full)

            assertFalse("Lamp's button is new", s2.addAfter("Lamp").ref in s1.refs())
            for (product in listOf("Kettle", "Toaster")) {
                val was = s1.addAfter(product).ref
                val now = s2.elements.firstOrNull { it.ref == was }
                assertTrue("$was is $product's button or gone", now == null || now == s2.addAfter(product))
            }
            val diff = RefDiffAnalyzer().diff(s1, s2)
            val kept = s2.elements.filter { it.ref in s1.refs() } - diff.changed.toSet()
            for (element in kept.filter { it.name == "Add to cart" }) {
                val before = s1.elements.indexOfFirst { it.ref == element.ref }
                assertEquals(
                    "an unchanged ${element.ref} is the same product's",
                    s1.elements[before - 1].name,
                    s2.elements[s2.elements.indexOf(element) - 1].name,
                )
            }
        }

    // Rule 3: a read cut inside a row.

    /**
     * A toolbar, then a list of rows [product, …, "Add to cart"]; the row of [cut] also holds a
     * subtree the read cannot finish ([deep]: too deep, else too many nodes), and comes last.
     */
    private fun cutList(
        products: List<String>,
        cut: String,
        deep: Boolean,
    ): Array<AccessibilityNodeInfo> {
        val list =
            make("androidx.recyclerview.widget.RecyclerView", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
                isScrollable = true
            }
        products.forEachIndexed { index, product ->
            val y = LIST_TOP + index * ROW_PX
            val row = layout(Rect(0, y, 1000, y + ROW_PX)).with(text(product, Rect(0, y, 600, y + ROW_PX)))
            if (product == cut) {
                if (deep) {
                    chain(row, ScreenReader.MAX_DEPTH + 2)
                } else {
                    repeat(ScreenReader.MAX_NODES) {
                        row.with(node("android.view.View", Rect(0, y, 600, y + ROW_PX)) { isVisibleToUser = false })
                    }
                }
            }
            list.with(row.with(press("Add to cart", Rect(700, y, 1000, y + ROW_PX), id = "add")))
        }
        return arrayOf(press("Search", Rect(0, 100, 1000, LIST_TOP), id = "search"), list)
    }

    @Test
    fun aRowCutByTheDepthBudgetIsUnreliableAndNothingElseIs() = rowCut(deep = true)

    @Test
    fun aRowCutByTheNodeBudgetIsUnreliableAndNothingElseIs() = rowCut(deep = false)

    private fun rowCut(deep: Boolean) =
        runTest {
            // Claude r3 S3: through the real reader; the cut row comes last, so the node budget
            // runs out inside it.
            host.show(*cutList(listOf("Kettle", "Toaster"), cut = "Toaster", deep = deep))

            val read = ScreenReader.read(host.windowsOn(0), GEOMETRY, OWN).elements
            val reliable = { name: String -> read.single { it.element.name == name }.identity.reliable }
            val adds = read.filter { it.element.name == "Add to cart" }

            assertFalse("the cut row", reliable("Toaster"))
            assertTrue("a row read before the cut", reliable("Kettle"))
            assertTrue("its button", adds.first().identity.reliable)
            assertTrue("a control outside the list", reliable("Search"))
            if (deep) assertFalse("the cut row's button", adds.last().identity.reliable)
        }

    @Test
    fun aButtonOfARowCutByTheDepthBudgetIsStaleAndTheOthersWork() =
        runTest {
            host.show(*cutList(listOf("Kettle", "Toaster"), cut = "Toaster", deep = true))
            val port = mainPort(host)
            val s1 = port.snapshot()
            val adds = s1.elements.filter { it.name == "Add to cart" }

            val stale = port.act(UiAction.Tap(Target.Ref(adds.last().ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, stale.status)
            assertNothingTouched()
            assertEquals(ActionOutcome.Status.Ok, port.act(UiAction.Tap(Target.Ref(adds.first().ref)), s1.id).status)
            assertEquals(
                ActionOutcome.Status.Ok,
                port.act(UiAction.Tap(Target.Ref(s1.named("Search").ref)), s1.id).status,
            )
        }

    // Rule 4: a row the list's edge cut down to its controls.

    /**
     * A lazy list that can scroll both ways, as Compose leaves it: the card at the top was cut by
     * the list's edge, and its text taken out of the tree, so only its Like is left; [person]'s
     * card below is whole.
     */
    private fun residue(person: String): AccessibilityNodeInfo {
        val list =
            make("android.view.View", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
                isScrollable = true
                collectionInfo = AccessibilityNodeInfo.CollectionInfo(-1, 1, false)
                addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD)
                addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
            }
        return list.with(
            make("android.view.View", Rect(0, LIST_TOP, 1000, LIST_TOP + 60)).with(
                press("Like", Rect(700, LIST_TOP, 1000, LIST_TOP + 60), id = "like"),
            ),
            make("android.view.View", Rect(0, LIST_TOP + 60, 1000, LIST_TOP + 660)).with(
                text(person, Rect(0, LIST_TOP + 60, 1000, LIST_TOP + 160)),
                press("Like", Rect(700, LIST_TOP + 560, 1000, LIST_TOP + 660), id = "like"),
            ),
        )
    }

    @Test
    fun theSameResidueOfTwoCardsOnTwoScreensIsNotTakenForOneCard() =
        runTest {
            // Codex r3 (Compose, reasoned): Ada's card scrolled out, Bob's took its place.
            host.show(residue("Bob"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            val cut = s1.elements.first { it.name == "Like" }
            host.show(residue("Cy"))
            val s2 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(cut.ref)), s1.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertFalse(
                "the residue on the next screen is new",
                s2.elements.first { it.name == "Like" }.ref in s1.refs(),
            )
            assertNothingTouched()
        }

    // Controls: what must keep its ref.

    private fun toolbar(): AccessibilityNodeInfo =
        layout(Rect(0, 60, 1000, LIST_TOP)).with(
            text("Inbox", Rect(0, 60, 700, LIST_TOP)),
            press("Search", Rect(700, 60, 1000, LIST_TOP), id = "search"),
        )

    /** The bottom bar's Settings button, as last made by [bottomBar]. */
    private lateinit var settings: AccessibilityNodeInfo

    private fun bottomBar(): AccessibilityNodeInfo {
        settings = press("Settings", Rect(500, LIST_BOTTOM, 1000, 2000), id = "settings")
        return layout(Rect(0, LIST_BOTTOM, 1000, 2000)).with(
            press("Home", Rect(0, LIST_BOTTOM, 500, 2000), id = "home"),
            settings,
        )
    }

    @Test
    fun theToolbarAndTheBottomBarKeepTheirRefsWhileTheListScrolls() =
        runTest {
            val feed = Feed()
            val people = listOf("Ada", "Bob", "Cy", "Dan")
            host.show(toolbar(), feed.list(people, top = LIST_TOP), bottomBar())
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(toolbar(), feed.list(people.drop(1), top = LIST_TOP - 50), bottomBar())

            val s2 = port.snapshot()
            val outcome = port.act(UiAction.Tap(Target.Ref(s1.named("Settings").ref)), s1.id)

            for (name in listOf("Inbox", "Search", "Home", "Settings")) {
                assertEquals(name, s1.named(name).ref, s2.named(name).ref)
            }
            assertEquals("a row that moved", s1.likeOf("Bob").ref, s2.likeOf("Bob").ref)
            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertTrue(settings.clicked)
        }

    @Test
    fun aRowKeepsItsRefWhenAnotherRowChanges() =
        runTest {
            val feed = Feed()
            host.show(feed.list(listOf("Ada", "Bob"), top = 300))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(feed.list(listOf("Ada", "Cy"), top = 300))

            val s2 = port.snapshot()
            val outcome = port.act(UiAction.Tap(Target.Ref(s1.likeOf("Ada").ref)), s1.id)

            assertEquals(s1.likeOf("Ada").ref, s2.likeOf("Ada").ref)
            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertTrue(feed.likes.getValue("Ada").clicked)
        }

    @Test
    fun aFlatRowKeepsItsRefWhenAnotherRowChanges() =
        runTest {
            host.show(flat(listOf("Kettle", "Toaster")))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(flat(listOf("Kettle", "Lamp")))

            val s2 = port.snapshot()

            assertEquals(s1.addAfter("Kettle").ref, s2.addAfter("Kettle").ref)
            assertFalse(s2.addAfter("Lamp").ref in s1.refs())
        }

    /** A dialog of buttons only, in its own window above the app. */
    private fun showDialog(): AccessibilityNodeInfo {
        val ok = press("OK", Rect(500, 1100, 800, 1200), id = "ok")
        val dialog =
            make("android.widget.FrameLayout", Rect(100, 900, 900, 1200)).with(
                press("Cancel", Rect(200, 1100, 500, 1200), id = "cancel"),
                ok,
            )
        val window = window(dialog, id = 2, layer = 2)
        host.show(text("Mail", Rect(0, 100, 1000, 200)), extra = listOf(window))
        return ok
    }

    @Test
    fun aDialogOfButtonsOnlyIsTappedByRefWhileItsWindowIsUnchanged() =
        runTest {
            showDialog()
            val port = mainPort(host)
            val s1 = port.snapshot()
            val ok = showDialog()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.named("OK").ref)), s1.id)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertTrue(ok.clicked)
        }

    /** A list of two options, buttons only, none cut by the list's edge; returns the first. */
    private fun showOptions(): AccessibilityNodeInfo {
        val small = press("Small", Rect(100, 900, 900, 1100))
        host.show(
            make("android.widget.ListView", Rect(100, 900, 900, 1300)).with(
                small,
                press("Large", Rect(100, 1100, 900, 1300)),
            ),
        )
        return small
    }

    @Test
    fun aListOfButtonsOnlyIsTappedByRefWhileNoneIsCut() =
        runTest {
            // Rule 4 applies only to rows the list's edge cut.
            showOptions()
            val port = mainPort(host)
            val s1 = port.snapshot()
            val small = showOptions()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.named("Small").ref)), s1.id)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertTrue(small.clicked)
        }

    private companion object {
        const val LIST_TOP = Feed.LIST_TOP
        const val LIST_BOTTOM = Feed.LIST_BOTTOM
    }
}
