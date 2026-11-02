package dev.phoneagent.app.adapters.screen

import dev.phoneagent.app.adapters.screen.Identities.ListKind
import dev.phoneagent.core.ports.ElementRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the row of an element is (fix list r1 B1, r2 B1). */
class ElementIdentityTest {
    private val nodes = ArrayList<Identities.Facts>()

    /** How a node of [add] is: [shown] `false` for one the read leaves out (clipped out of view). */
    private class Kind(
        val list: ListKind? = null,
        val shown: Boolean = true,
        val item: Boolean = false,
        val interactive: Boolean = false,
        val cut: Boolean = false,
    )

    /** Adds a node. */
    private fun add(
        label: String?,
        parent: Int? = null,
        role: ElementRole = ElementRole.CONTAINER,
        kind: Kind = Kind(),
    ): Int {
        nodes +=
            Identities.Facts(
                if (kind.shown) ElementIdentity(1, role, resourceId = null, name = label, row = null) else null,
                label = label,
                shape = Identities.Shape(kind.list, kind.item, kind.interactive),
                parent = parent,
                cut = kind.cut,
            )
        return nodes.lastIndex
    }

    /** A row container under [list] holding [labels] (`-` before a label: clipped out of view). */
    private fun row(
        list: Int,
        vararg labels: String,
    ): Int {
        val row = add(null, parent = list)
        labels.forEach {
            add(it.removePrefix("-"), parent = row, role = ElementRole.TEXT, kind = Kind(shown = !it.startsWith("-")))
        }
        return row
    }

    private fun identities(): List<ElementIdentity?> = Identities.of(nodes).map { it?.identity }

    private fun rowOf(index: Int) = identities()[index]!!.row

    private fun aroundOf(index: Int): Around? = Identities.of(nodes)[index]!!.around

    private fun key(vararg labels: String): RowKey = RowKey.of(labels.toList())

    private fun bag(vararg labels: String): Around = Around(labels.toList())

    @Test
    fun aRowKeyIsEveryLabelInOrder() {
        assertEquals(key("Ada", "Like"), key("Ada", "Like"))
        assertNotEquals(key("Ada", "Like"), key("Like", "Ada"))
        assertNotEquals("labels are not run together", key("ab", "c"), key("a", "bc"))
        assertNotEquals(key("Ada"), key("Ada", "Like"))
    }

    @Test
    fun everyLabelOfARowCountsHoweverManyAndHoweverLong() {
        // Claude r2 B1 / Codex R2-B1: badges first, the product fourth; or a long shared prefix.
        val list = add(null, kind = Kind(list = ListKind.COLLECTION))
        val kettle = row(list, "Official", "Free shipping", "Photo", "Kettle", "Add to cart")
        val lamp = row(list, "Official", "Free shipping", "Photo", "Lamp", "Add to cart")
        val long = "x".repeat(200)
        val bob = row(list, "${long}Bob")
        val ada = row(list, "${long}Ada")

        val identities = identities()

        assertEquals(key("Official", "Free shipping", "Photo", "Kettle", "Add to cart"), identities[kettle + 5]!!.row)
        assertNotEquals(identities[kettle + 5]!!.row, identities[lamp + 5]!!.row)
        assertNotEquals(identities[bob]!!.row, identities[ada]!!.row)
        assertNull("the list is in no row", identities[list]!!.row)
    }

    @Test
    fun aRowKeepsItsKeyWhenPartOfItIsClippedOutOfView() {
        // Claude r2 S1 P2: the card's head scrolled out of view; the card still holds it.
        val list = add(null, kind = Kind(list = ListKind.COLLECTION))
        val whole = row(list, "Ada", "5 min ago", "Hi", "Like")
        val clipped = row(list, "-Ada", "-5 min ago", "Hi", "Like")
        val other = row(list, "-Bob", "-5 min ago", "Hi", "Like")

        val identities = identities()

        assertNull("left out of the read", identities[clipped + 1])
        assertEquals(identities[whole + 4]!!.row, identities[clipped + 4]!!.row)
        assertNotEquals(
            "a clipped label still tells rows apart",
            identities[whole + 4]!!.row,
            identities[other + 4]!!.row,
        )
    }

    @Test
    fun theOnlyChildOfACollectionIsARowEvenWhenItFillsIt() {
        // Codex R2-B2 (b): one page of a pager, one video of a feed.
        val feed = add(null, kind = Kind(list = ListKind.COLLECTION))
        val page = row(feed, "@Bob", "Like")

        assertEquals(key("@Bob", "Like"), rowOf(page + 2))
    }

    @Test
    fun aScrollViewsContentWrapperIsLookedThrough() {
        val scroll = add(null, kind = Kind(list = ListKind.WRAPPED))
        val content = add(null, parent = scroll)
        val ada = row(content, "Ada", "Like")
        row(content, "Bob", "Like")

        assertNull(rowOf(content))
        assertEquals(key("Ada", "Like"), rowOf(ada + 2))
    }

    @Test
    fun onlyTheScrollViewsOwnWrapperIsLookedThrough() {
        // One card in the content: the card is the row, not each of its labels.
        val scroll = add(null, kind = Kind(list = ListKind.WRAPPED))
        val content = add(null, parent = scroll)
        val card = add(null, parent = content)
        add("Bob", parent = card, role = ElementRole.TEXT)
        val like = add("Like", parent = card, role = ElementRole.BUTTON)

        assertEquals(key("Bob", "Like"), rowOf(like))
    }

    @Test
    fun aChildWithCollectionItemInfoIsAnItemNotAWrapper() {
        val scroll = add(null, kind = Kind(list = ListKind.WRAPPED))
        val item = add(null, parent = scroll, kind = Kind(item = true))
        add("Bob", parent = item, role = ElementRole.TEXT)
        val like = add("Like", parent = item, role = ElementRole.BUTTON)

        assertEquals(key("Bob", "Like"), rowOf(like))
    }

    @Test
    fun theOnlyChildOfAnyOtherListIsItsOnlyRow() {
        val list = add(null, kind = Kind(list = ListKind.PLAIN))
        val only = row(list, "Ada", "Like")

        assertEquals(key("Ada", "Like"), rowOf(only + 2))
    }

    @Test
    fun aListInsideARowAddsOnlyItsOwnLabelToTheRow() {
        // Device run r1: a page holding a scrolling list; the page's row must not change when the
        // inner rows scroll.
        val list = add(null, kind = Kind(list = ListKind.COLLECTION))
        val ada = add(null, parent = list)
        add("Ada", parent = ada, role = ElementRole.TEXT)
        val photos = add("Photos", parent = ada, role = ElementRole.LIST, kind = Kind(list = ListKind.COLLECTION))
        val first = row(photos, "Beach")
        row(photos, "Snow")
        add("Like", parent = ada, role = ElementRole.BUTTON)

        val identities = identities()

        assertEquals(key("Ada", "Photos", "Like"), identities[ada]!!.row)
        assertEquals(key("Ada", "Photos", "Like"), identities[photos]!!.row)
        assertEquals("the inner rows keep their own", key("Beach"), identities[first + 1]!!.row)
    }

    @Test
    fun theInnermostListCounts() {
        val outer = add(null, kind = Kind(list = ListKind.COLLECTION))
        val section = row(outer, "Today")
        val inner = add(null, parent = section, kind = Kind(list = ListKind.COLLECTION))
        val ada = row(inner, "Ada")

        assertEquals(key("Ada"), rowOf(ada + 1))
        assertEquals(key("Today"), rowOf(inner))
    }

    @Test
    fun outsideListsAControlIsToldApartByTheLabelsAroundIt() {
        // Codex R2-B2 (a): an anonymous card no list holds.
        val root = add(null)
        val card = add(null, parent = root)
        val bob = add("Bob", parent = card, role = ElementRole.TEXT)
        val like = add("Like", parent = card, role = ElementRole.BUTTON)
        val ok = add("OK", parent = root, role = ElementRole.BUTTON)

        val identities = identities()

        assertNull(identities[like]!!.row)
        assertTrue(aroundOf(like)!!.fits(bag("Bob", "Like")))
        assertFalse("another card in its place", aroundOf(like)!!.fits(bag("Ada", "Like")))
        assertTrue(aroundOf(bob)!!.fits(bag("Bob", "Like")))
        assertTrue("the nearest node holding other labels", aroundOf(ok)!!.fits(bag("Bob", "Like", "OK")))
        assertFalse(aroundOf(ok)!!.fits(bag("Ada", "Like", "OK")))
        assertNull("a container is told apart by its own identity", identities[card]!!.row)
        assertNull(aroundOf(card))
        assertNull(identities[root]!!.row)
    }

    @Test
    fun labelsAroundMayComeGoOrCountOnButNotBeReplaced() {
        val around = bag("Cart", "3 items", "Pay")

        assertTrue("a banner came", around.fits(bag("Sale", "Cart", "3 items", "Pay")))
        assertTrue("a line went", around.fits(bag("Cart", "Pay")))
        assertTrue("digits do not count", around.fits(bag("Cart", "4 items", "Pay")))
        assertFalse("one label replaced", around.fits(bag("Cart", "3 items", "Cancel")))
        assertFalse("a label twice is not once", bag("Like", "Like").fits(bag("Like", "Ada")))
        assertTrue(bag("Like", "Like").fits(bag("Like")))
    }

    @Test
    fun aRowKeyCountsDigitsToo() {
        val list = add(null, kind = Kind(list = ListKind.COLLECTION))
        val third = row(list, "Photo", "3/10", "Delete")
        val fourth = row(list, "Photo", "4/10", "Delete")

        assertNotEquals(rowOf(third + 3), rowOf(fourth + 3))
    }

    @Test
    fun aClickableContainerOutsideListsIsItsOwnRow() {
        val root = add(null)
        val card = add(null, parent = root, kind = Kind(interactive = true))
        add("Bob", parent = card, role = ElementRole.TEXT)
        val like = add("Like", parent = card, role = ElementRole.BUTTON)

        assertEquals(key("Bob", "Like"), rowOf(card))
        assertNull("its parts are in no row", rowOf(like))
        assertTrue(aroundOf(like)!!.fits(bag("Bob", "Like")))
    }

    @Test
    fun aControlAloneHasNothingAroundItAndAListHolderHasNoRow() {
        val root = add(null)
        val ok = add("OK", parent = root, role = ElementRole.BUTTON)
        val list = add(null, parent = root, kind = Kind(list = ListKind.COLLECTION))
        row(list, "Ada")

        val identities = identities()

        assertNull(identities[ok]!!.row)
        assertNull("the list's items do not count", aroundOf(ok))
        assertNull("holds a list", identities[root]!!.row)
        assertNull(aroundOf(root))
        assertNull(identities[list]!!.row)
    }

    @Test
    fun aRowTheReadStoppedInsideIsNotReliable() {
        val list = add(null, kind = Kind(list = ListKind.COLLECTION))
        val whole = row(list, "Ada", "Like")
        val cut = add(null, parent = list, kind = Kind(cut = true))
        add("Bob", parent = cut, role = ElementRole.TEXT)

        val identities = identities()

        assertTrue(identities[whole + 2]!!.reliable)
        assertFalse(identities[cut]!!.reliable)
        assertFalse(identities[cut + 1]!!.reliable)
        assertTrue("the list itself has no row", identities[list]!!.reliable)
    }

    @Test
    fun aControlWhoseSurroundingsTheReadStoppedInsideIsNotReliable() {
        val root = add(null)
        val card = add(null, parent = root, kind = Kind(cut = true))
        add("Bob", parent = card, role = ElementRole.TEXT)
        val like = add("Like", parent = card, role = ElementRole.BUTTON)
        val other = add(null, parent = root)
        add("Ada", parent = other, role = ElementRole.TEXT)
        val ok = add("OK", parent = other, role = ElementRole.BUTTON)

        val identities = identities()

        assertFalse(identities[like]!!.reliable)
        assertTrue(identities[ok]!!.reliable)
    }
}
