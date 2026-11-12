package dev.phoneagent.app.adapters.screen

import dev.phoneagent.app.adapters.screen.IdentityContext.Scope
import dev.phoneagent.core.ports.ElementRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What tells an element apart, and when that cannot be trusted (fix list r1 B1, r2 B1, r3 B1). */
class ElementIdentityTest {
    private val nodes = ArrayList<Identities.Facts>()

    /**
     * How a node of [add] is: [shown] `false` for one the read leaves out (out of view); [cut],
     * [clipped] and [besideGap] as [Identities.Loss] has them.
     */
    private class Kind(
        val list: Boolean = false,
        val shown: Boolean = true,
        val interactive: Boolean = false,
        val cut: Boolean = false,
        val clipped: Boolean = false,
        val besideGap: Boolean = false,
    )

    private val list = Kind(list = true)
    private val control = Kind(interactive = true)

    /** Adds a node. */
    private fun add(
        label: String?,
        parent: Int? = null,
        role: ElementRole = ElementRole.CONTAINER,
        kind: Kind = Kind(),
    ): Int {
        nodes +=
            Identities.Facts(
                if (kind.shown) ElementIdentity(1, role, resourceId = null, name = label, context = null) else null,
                label = label,
                shape = Identities.Shape(kind.list, kind.interactive),
                parent = parent,
                loss = Identities.Loss(cut = kind.cut, clipped = kind.clipped, besideGap = kind.besideGap),
            )
        return nodes.lastIndex
    }

    /** A row container under [list] holding [labels] (`-` before a label: out of view); its last label is a button. */
    private fun row(
        list: Int,
        vararg labels: String,
        kind: Kind = Kind(),
    ): Int {
        val row = add(null, parent = list, kind = kind)
        labels.forEachIndexed { index, label ->
            val button = index == labels.lastIndex && labels.size > 1
            add(
                label.removePrefix("-"),
                parent = row,
                role = if (button) ElementRole.BUTTON else ElementRole.TEXT,
                kind = Kind(shown = !label.startsWith("-"), interactive = button),
            )
        }
        return row
    }

    private fun identities(): List<ElementIdentity?> = Identities.of(nodes)

    private fun contextOf(index: Int): IdentityContext? = identities()[index]!!.context

    private fun reliable(index: Int): Boolean = identities()[index]!!.reliable

    private fun inWindow(vararg labels: String) = IdentityContext(Scope.WINDOW, LabelKey.of(labels.toList()))

    private fun inRow(vararg labels: String) = IdentityContext(Scope.ROW, LabelKey.of(labels.toList()))

    @Test
    fun aLabelKeyIsEveryLabelInOrder() {
        val key = { labels: List<String> -> LabelKey.of(labels) }
        assertEquals(key(listOf("Ada", "Like")), key(listOf("Ada", "Like")))
        assertNotEquals(key(listOf("Ada", "Like")), key(listOf("Like", "Ada")))
        assertNotEquals("labels are not run together", key(listOf("ab", "c")), key(listOf("a", "bc")))
        assertNotEquals(key(listOf("Ada")), key(listOf("Ada", "Like")))
        assertNotEquals("digits count", key(listOf("0:12")), key(listOf("0:15")))
    }

    @Test
    fun aNodeTheReadLeavesOutHasNoIdentity() {
        val root = add(null)
        val hidden = add("Draft", parent = root, kind = Kind(shown = false))

        assertNull(identities()[hidden])
        assertEquals("its label still counts", inWindow("Draft"), contextOf(root))
    }

    // Rule 1: outside lists, every label of the window outside lists.

    @Test
    fun outsideListsTheContextIsEveryLabelOfTheWindowOutsideLists() {
        // Codex r3 B1 and Claude r3 B1 (c): the title and the buttons in two layouts, a bottom bar.
        val root = add(null)
        val toolbar = add(null, parent = root)
        add("Inbox", parent = toolbar, role = ElementRole.TEXT)
        val card = add(null, parent = root)
        add("Bob", parent = card, role = ElementRole.TEXT)
        val actions = add(null, parent = card)
        val like = add("Like", parent = actions, role = ElementRole.BUTTON, kind = control)
        add("Share", parent = actions, role = ElementRole.BUTTON, kind = control)
        val feed = add("Feed", parent = root, role = ElementRole.LIST, kind = list)
        val ada = row(feed, "Ada", "Like")
        add("Draft", parent = root, kind = Kind(shown = false))

        val window = inWindow("Inbox", "Bob", "Like", "Share", "Feed", "Draft")
        assertEquals(window, contextOf(like))
        assertEquals("a list is in no list", window, contextOf(feed))
        assertEquals(window, contextOf(toolbar))
        assertEquals("a row has its own", inRow("Ada", "Like"), contextOf(ada + 2))
    }

    @Test
    fun aScrollViewIsNoList() {
        // Claude r3 B1 (b): what a scroll view holds is outside lists.
        val root = add(null)
        val scroll = add(null, parent = root, role = ElementRole.LIST)
        val content = add(null, parent = scroll)
        add("Kettle", parent = content, role = ElementRole.TEXT)
        val buy = add("Buy now", parent = content, role = ElementRole.BUTTON, kind = control)

        assertEquals(inWindow("Kettle", "Buy now"), contextOf(buy))
    }

    @Test
    fun eachWindowHasItsOwn() {
        val app = add(null)
        add("Mail", parent = app, role = ElementRole.TEXT)
        val dialog = add(null)
        val ok = add("OK", parent = dialog, role = ElementRole.BUTTON, kind = control)

        assertEquals(inWindow("Mail"), contextOf(app))
        assertEquals(inWindow("OK"), contextOf(ok))
    }

    // Rows of a list.

    @Test
    fun everyLabelOfARowCountsHoweverManyAndHoweverLong() {
        // Claude r2 B1 / Codex R2-B1: badges first, the product fourth; or a long shared prefix.
        val feed = add(null, kind = list)
        val kettle = row(feed, "Official", "Free shipping", "Photo", "Kettle", "Add to cart")
        val lamp = row(feed, "Official", "Free shipping", "Photo", "Lamp", "Add to cart")
        val long = "x".repeat(200)
        val bob = row(feed, "${long}Bob")
        val ada = row(feed, "${long}Ada")

        assertEquals(inRow("Official", "Free shipping", "Photo", "Kettle", "Add to cart"), contextOf(kettle + 5))
        assertNotEquals(contextOf(kettle + 5), contextOf(lamp + 5))
        assertNotEquals(contextOf(bob), contextOf(ada))
        assertNotEquals("digits count", contextOf(row(feed, "Photo", "3/10")), contextOf(row(feed, "Photo", "4/10")))
    }

    @Test
    fun aRowKeepsItsKeyWhenPartOfItIsOutOfView() {
        // Claude r2 S1 P2: the card's head scrolled out of view; the card still holds it.
        val feed = add(null, kind = list)
        val whole = row(feed, "Ada", "5 min ago", "Hi", "Like")
        val scrolled = row(feed, "-Ada", "-5 min ago", "Hi", "Like")
        val other = row(feed, "-Bob", "-5 min ago", "Hi", "Like")

        assertEquals(contextOf(whole + 4), contextOf(scrolled + 4))
        assertNotEquals("a label out of view still tells rows apart", contextOf(whole + 4), contextOf(other + 4))
    }

    @Test
    fun theOnlyChildOfAListIsARowEvenWhenItFillsIt() {
        // Codex R2-B2 (b): one page of a pager, one video of a feed.
        val feed = add(null, kind = list)
        val page = row(feed, "@Bob", "Like")

        assertEquals(inRow("@Bob", "Like"), contextOf(page + 2))
    }

    @Test
    fun aListInsideARowAddsOnlyItsOwnLabelToTheRow() {
        // Device run r1: the row must not change when the inner rows scroll.
        val feed = add(null, kind = list)
        val ada = add(null, parent = feed)
        add("Ada", parent = ada, role = ElementRole.TEXT)
        val photos = add("Photos", parent = ada, role = ElementRole.LIST, kind = list)
        val beach = row(photos, "Beach")
        row(photos, "Snow")
        add("Like", parent = ada, role = ElementRole.BUTTON, kind = control)

        assertEquals(inRow("Ada", "Photos", "Like"), contextOf(ada))
        assertEquals(inRow("Ada", "Photos", "Like"), contextOf(photos))
        assertEquals("the inner rows keep their own", inRow("Beach"), contextOf(beach + 1))
    }

    // Rule 2: rows without a node of their own.

    @Test
    fun aFlatRowRunsFromAfterOneControlToTheNext() {
        // Claude r3 B1 (a): [Kettle, Add, Toaster, Add] straight under the list.
        val feed = add(null, kind = list)
        val kettle = add("Kettle", parent = feed, role = ElementRole.TEXT)
        val first = add("Add", parent = feed, role = ElementRole.BUTTON, kind = control)
        val toaster = add("Toaster", parent = feed, role = ElementRole.TEXT)
        val second = add("Add", parent = feed, role = ElementRole.BUTTON, kind = control)

        assertEquals(inRow("Kettle", "Add"), contextOf(kettle))
        assertEquals(inRow("Kettle", "Add"), contextOf(first))
        assertEquals(inRow("Toaster", "Add"), contextOf(toaster))
        assertEquals(inRow("Toaster", "Add"), contextOf(second))
    }

    @Test
    fun aControlHoldingItsOneLabelBelowItEndsAFlatRow() {
        // A Compose button whose text is a node of its own.
        val feed = add(null, kind = list)
        add("Kettle", parent = feed, role = ElementRole.TEXT)
        val first = add(null, parent = feed, role = ElementRole.BUTTON, kind = control)
        add("Add", parent = first, role = ElementRole.TEXT)
        add("Toaster", parent = feed, role = ElementRole.TEXT)
        val second = add(null, parent = feed, role = ElementRole.BUTTON, kind = control)
        val text = add("Add", parent = second, role = ElementRole.TEXT)

        assertEquals(inRow("Kettle", "Add"), contextOf(first))
        assertEquals(inRow("Toaster", "Add"), contextOf(text))
    }

    @Test
    fun aChildWithMoreThanOneLabelOrAListIsARowOfItsOwnAndEndsTheOpenOne() {
        val feed = add(null, kind = list)
        val header = add("Fruits", parent = feed, role = ElementRole.TEXT)
        val apple = row(feed, "Apple", "Buy")
        val deals = add("Deals", parent = feed, role = ElementRole.TEXT)
        val carousel = add(null, parent = feed)
        val inner = add(null, parent = carousel, role = ElementRole.LIST, kind = list)
        row(inner, "Pear")
        val nested = add("Photos", parent = feed, role = ElementRole.LIST, kind = list)

        assertEquals(inRow("Fruits"), contextOf(header))
        assertEquals(inRow("Apple", "Buy"), contextOf(apple))
        assertEquals("a child holding a list", inRow("Deals"), contextOf(deals))
        assertEquals(inRow(), contextOf(carousel))
        assertEquals("a list child", inRow("Photos"), contextOf(nested))
    }

    // Rule 3: what the read did not finish.

    @Test
    fun aCutOutsideListsMakesEverythingOfTheWindowOutsideListsUnreliable() {
        // Codex r3 B2: the read stopped before the card's title; "Like" was all it had read.
        val root = add(null, kind = Kind(cut = true))
        val card = add(null, parent = root, kind = Kind(cut = true))
        val like = add("Like", parent = card, role = ElementRole.BUTTON, kind = control)
        val ok = add("OK", parent = root, role = ElementRole.BUTTON, kind = control)
        val feed = add(null, parent = root, kind = list)
        val ada = row(feed, "Ada", "Like")

        assertFalse(reliable(like))
        assertFalse("however whole it looks", reliable(ok))
        assertFalse(reliable(feed))
        assertFalse("below the list, which is not reliable", reliable(ada + 2))
    }

    @Test
    fun aCutInARowMakesOnlyThatRowUnreliable() {
        val root = add(null)
        val ok = add("OK", parent = root, role = ElementRole.BUTTON, kind = control)
        val feed = add(null, parent = root, kind = list)
        val whole = row(feed, "Ada", "Like")
        val cut = add(null, parent = feed, kind = Kind(cut = true))
        add("Bob", parent = cut, role = ElementRole.TEXT)
        add("Hi", parent = cut, role = ElementRole.TEXT)
        val flatCut = add(null, parent = feed, role = ElementRole.BUTTON, kind = Kind(interactive = true, cut = true))
        val after = row(feed, "Cy", "Like")

        assertTrue(reliable(whole + 2))
        assertFalse(reliable(cut))
        assertFalse(reliable(cut + 1))
        assertFalse("a flat row with a cut child", reliable(flatCut))
        assertTrue(reliable(after + 2))
        assertTrue(reliable(feed))
        assertTrue(reliable(ok))
    }

    @Test
    fun whatANodeLacksPassesDownThroughNodesTheReadLeavesOut() {
        // Fix list fwd B1: the nested lists are out of view; the read gives their rows the outer list as parent.
        val root = add(null)
        val outer = add(null, parent = root, kind = list)
        val items =
            add(
                "Items",
                parent = outer,
                role = ElementRole.LIST,
                kind = Kind(list = true, shown = false, besideGap = true),
            )
        val wrapper = add(null, parent = items, kind = Kind(shown = false))
        val like = add("Like", parent = wrapper, role = ElementRole.BUTTON, kind = control)
        val photos = add("Photos", parent = outer, role = ElementRole.LIST, kind = Kind(list = true, shown = false))
        val share = add("Share", parent = photos, role = ElementRole.BUTTON, kind = control)
        val cut = add(null, parent = outer, kind = Kind(cut = true))
        add("Cy", parent = cut, role = ElementRole.TEXT)
        val inCut = add("Songs", parent = cut, role = ElementRole.LIST, kind = list)
        val play = add("Play", parent = inCut, role = ElementRole.BUTTON, kind = control)

        assertNull(identities()[items])
        assertTrue("the outer list", reliable(outer))
        assertFalse("below the list beside the gap, two nodes down", reliable(like))
        assertTrue("below a list away from the gap", reliable(share))
        assertFalse("below a kept list in a row cut", reliable(play))
    }

    @Test
    fun aListThatLostChildrenMakesItsOpenLastRowUnreliable() {
        val open = add(null, kind = Kind(list = true, cut = true))
        val kettle = add("Kettle", parent = open, role = ElementRole.TEXT)
        add("Add", parent = open, role = ElementRole.BUTTON, kind = control)
        val toaster = add("Toaster", parent = open, role = ElementRole.TEXT)
        val closed = add(null, kind = Kind(list = true, cut = true))
        val lamp = add("Lamp", parent = closed, role = ElementRole.TEXT)
        add("Add", parent = closed, role = ElementRole.BUTTON, kind = control)
        val rows = add(null, kind = Kind(list = true, cut = true))
        val ada = row(rows, "Ada", "Like")

        assertTrue(reliable(kettle))
        assertFalse("its button may be among the lost", reliable(toaster))
        assertTrue("a row closed by its control", reliable(lamp))
        assertTrue(reliable(ada + 2))
        assertTrue("a list whose children were lost is whole itself", reliable(open))
    }

    // Rule 4: rows the list's edge cut down to their controls.

    @Test
    fun aRowTheEdgeCutDownToItsControlsIsUnreliable() {
        // Codex r3 (Compose): the card's text was taken out of the tree, its Like is left.
        val feed = add(null, kind = list)
        val residue = add(null, parent = feed, kind = Kind(clipped = true))
        add("Like", parent = residue, role = ElementRole.BUTTON, kind = control)
        val withText = row(feed, "Ada", "Like", kind = Kind(clipped = true))
        val whole = add(null, parent = feed)
        val like = add("Like", parent = whole, role = ElementRole.BUTTON, kind = control)
        val empty = add(null, parent = feed, kind = Kind(clipped = true))
        add(null, parent = empty, role = ElementRole.IMAGE)

        assertFalse(reliable(residue))
        assertTrue("text of its own left", reliable(withText + 2))
        assertTrue("not cut", reliable(like))
        assertFalse("nothing left to tell it by", reliable(empty))
    }

    @Test
    fun theLabelOfAControlHoldingOnlyThatLabelIsTheControls() {
        val feed = add(null, kind = list)
        val card = add(null, parent = feed, kind = Kind(clipped = true))
        val button = add(null, parent = card, role = ElementRole.BUTTON, kind = control)
        add("Like", parent = button, role = ElementRole.TEXT)
        val clickable = add(null, parent = feed, kind = Kind(interactive = true, clipped = true))
        val ada = add("Ada", parent = clickable, role = ElementRole.TEXT)
        add("Hi", parent = clickable, role = ElementRole.TEXT)
        val single = add(null, parent = feed, kind = Kind(interactive = true, clipped = true))
        add("Wi-Fi", parent = single, role = ElementRole.TEXT)

        assertFalse(reliable(card))
        assertTrue("a clickable row's texts are its own", reliable(ada))
        assertFalse("a clickable row with one label is a control", reliable(single))
    }

    @Test
    fun aFlatRowWithACutChildIsCutToo() {
        val feed = add(null, kind = list)
        val first =
            add("Add", parent = feed, role = ElementRole.BUTTON, kind = Kind(interactive = true, clipped = true))
        val toaster = add("Toaster", parent = feed, role = ElementRole.TEXT)
        val second = add("Add", parent = feed, role = ElementRole.BUTTON, kind = control)

        assertFalse(reliable(first))
        assertTrue(reliable(toaster))
        assertTrue(reliable(second))
    }
}
