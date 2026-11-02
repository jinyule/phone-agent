package dev.phoneagent.app.adapters.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Refs carried from one screen to the next (WP06: the diff counts a changed ref as a change). */
@RunWith(AndroidJUnit4::class)
class RefCarrierTest {
    private fun RefCarrier.Carried.refOf(
        read: TreeBuilder,
        name: String,
    ): String = refs[read.elements.indexOfFirst { it.element.name == name }].value

    private fun first(read: TreeBuilder): RefCarrier.Carried =
        RefCarrier.carry(previous = null, read.elements, lastUsed = 0)

    private fun RefCarrier.Carried.then(read: TreeBuilder): RefCarrier.Carried =
        RefCarrier.carry(tree, read.elements, lastUsed)

    /** A feed whose rows each have a "Like", told apart by the row's person. */
    private fun feed(vararg rows: Pair<String, Int>): TreeBuilder =
        TreeBuilder().apply {
            val list = add("Feed", top = 0)
            rows.forEach { (person, top) -> add("Like", top, parent = list, more = More(row = listOf(person, "Like"))) }
        }

    /** Top-level "Like" buttons outside any row. */
    private fun likes(vararg tops: Int): TreeBuilder =
        TreeBuilder().apply { tops.forEach { add("Like", it, role = ElementRole.BUTTON) } }

    @Test
    fun theFirstScreenIsNumberedInReadingOrder() {
        val carried = first(chats("Ada" to 100, "Bob" to 200))

        assertEquals(listOf("e1", "e2", "e3"), carried.refs.map { it.value })
        assertEquals(3, carried.lastUsed)
        assertEquals(listOf(null, ElementRef("e1"), ElementRef("e1")), carried.tree.map { it.parent })
    }

    @Test
    fun theSameScreenKeepsItsRefs() {
        val first = first(chats("Ada" to 100, "Bob" to 200))

        val again = first.then(chats("Ada" to 100, "Bob" to 200))

        assertEquals(first.refs, again.refs)
        assertEquals(3, again.lastUsed)
    }

    @Test
    fun anInsertedElementGetsANewRefAndTheOthersKeepTheirs() {
        val next = chats("Cy" to 100, "Ada" to 200, "Bob" to 300)

        val second = first(chats("Ada" to 100, "Bob" to 200)).then(next)

        assertEquals("e2", second.refOf(next, "Ada"))
        assertEquals("e3", second.refOf(next, "Bob"))
        assertEquals("e4", second.refOf(next, "Cy"))
        assertEquals(4, second.lastUsed)
    }

    @Test
    fun scrolledElementsKeepTheirRefsAndVanishedRefsAreNotReused() {
        // Scrolled by one row: Ada is gone, Bob moved up, Cy is new.
        val next = chats("Bob" to 100, "Cy" to 200)

        val second = first(chats("Ada" to 100, "Bob" to 200)).then(next)

        assertEquals("e3", second.refOf(next, "Bob"))
        assertEquals("e4", second.refOf(next, "Cy"))
        assertTrue(second.refs.none { it.value == "e2" })
    }

    @Test
    fun parentsArePairedBeforeTheirChildren() {
        // A new container is read first; the list and its row come after it.
        val next =
            TreeBuilder().apply {
                add("Archived", top = 0)
                val list = add("Chats", top = 0)
                add("Ada", top = 100, parent = list)
            }

        val second = first(chats("Ada" to 100)).then(next)

        assertEquals(listOf("e3", "e1", "e2"), second.refs.map { it.value })
        assertEquals(ElementRef("e1"), second.tree[2].parent)
    }

    @Test
    fun aChildOfAParentThatGotANewRefGetsOneToo() {
        // The row Ada was replaced by Bob; their buttons look alike, but are not the same element.
        val before = TreeBuilder().apply { add("Delete", top = 100, parent = add("Ada", top = 100)) }
        val after = TreeBuilder().apply { add("Delete", top = 100, parent = add("Bob", top = 100)) }

        val second = first(before).then(after)

        assertEquals(listOf("e3", "e4"), second.refs.map { it.value })
    }

    @Test
    fun theViewIdTellsApartElementsThatLookAlike() {
        val before =
            TreeBuilder().apply {
                add(
                    null,
                    top = 100,
                    role = ElementRole.BUTTON,
                    more = More(resourceId = "save"),
                )
            }
        val after =
            TreeBuilder().apply {
                add(
                    null,
                    top = 100,
                    role = ElementRole.BUTTON,
                    more = More(resourceId = "delete"),
                )
            }

        assertNotEquals(first(before).refs, first(before).then(after).refs)
    }

    @Test
    fun theRowTellsApartControlsEveryRowHas() {
        // Scrolled by one row: Bob's Like moved up into Ada's place.
        val second = first(feed("Ada" to 100, "Bob" to 200)).then(feed("Bob" to 100, "Cy" to 200))

        assertEquals(listOf("e1", "e3", "e4"), second.refs.map { it.value })
    }

    @Test
    fun alikeElementsThatMovedAllGetNewRefs() {
        // Two identical Likes outside any row both moved: nothing tells which is which.
        val second = first(likes(100, 200)).then(likes(150, 250))

        assertEquals(listOf("e3", "e4"), second.refs.map { it.value })
    }

    @Test
    fun identicalElementsMatchTheUnmovedOnesFirst() {
        // The first "Like" disappeared; the second did not move.
        val next = chats("Like" to 200)

        val second = first(chats("Like" to 100, "Like" to 200)).then(next)

        assertEquals("e3", second.refs[1].value)
    }

    @Test
    fun anotherWindowStartsFresh() {
        val dialog =
            TreeBuilder(window = 2).apply { add("Ada", top = 100, parent = add("Chats", top = 0)) }

        val second = first(chats("Ada" to 100)).then(dialog)

        assertEquals(listOf("e3", "e4"), second.refs.map { it.value })
        assertEquals(ElementRef("e3"), second.tree[1].parent)
    }

    /** An "OK" outside any row, with [around] the labels around it (`null`: nothing around it). */
    private fun ok(around: List<String>?): TreeBuilder =
        TreeBuilder().apply { add("OK", top = 100, role = ElementRole.BUTTON, more = More(around = around)) }

    private fun ok(vararg around: String): TreeBuilder = ok(around.toList())

    @Test
    fun anElementInNoRowKeepsItsRefWhileTheLabelsAroundItFit() {
        val first = first(ok("Delete Ada?", "OK"))

        assertEquals("a line came", first.refs, first.then(ok("Delete Ada?", "Undo is not possible", "OK")).refs)
        assertEquals("a line went", first.refs, first.then(ok("OK")).refs)
        assertEquals("nothing around it now", first.refs, first.then(ok(null)).refs)
        assertNotEquals("a line replaced", first.refs, first.then(ok("Delete Bob?", "OK")).refs)
    }

    @Test
    fun anElementThatHadNothingAroundItIsComparedByItselfOnly() {
        val first = first(ok(null))

        assertEquals(first.refs, first.then(ok("Delete Bob?", "OK")).refs)
    }

    @Test
    fun anUnreliableElementNeitherGivesNorTakesARef() {
        val first = first(likes(100))

        val unreliable =
            TreeBuilder().apply {
                add(
                    "Like",
                    100,
                    role = ElementRole.BUTTON,
                    more = More(reliable = false),
                )
            }
        val second = first.then(unreliable)

        assertNotEquals(first.refs, second.refs)
        assertNotEquals("nor is it found again", second.refs, second.then(likes(100)).refs)
    }

    @Test
    fun newRefsStayAboveEverythingHandedOutBefore() {
        val carried = RefCarrier.carry(previous = null, chats().elements, lastUsed = 41)

        assertEquals(listOf("e42"), carried.refs.map { it.value })
    }
}
