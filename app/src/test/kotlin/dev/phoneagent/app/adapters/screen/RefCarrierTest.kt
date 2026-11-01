package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refs carried from one screen to the next (WP06: the diff counts a changed ref as a change). */
class RefCarrierTest {
    /** Elements as the reader numbers them: e1… in reading order. */
    private fun read(vararg elements: Triple<String, Int, String?>): List<UiElement> =
        elements.mapIndexed { index, (name, top, parent) ->
            UiElement(
                ref = ElementRef("e${index + 1}"),
                role = if (parent == null) ElementRole.LIST else ElementRole.BUTTON,
                name = name,
                bounds = Bounds(0, top, 999, top + 9),
                parent = parent?.let(::ElementRef),
                window = 1,
            )
        }

    private fun row(
        name: String,
        top: Int,
    ) = Triple(name, top, "e1")

    private val list = Triple("Chats", 0, null)

    private fun List<UiElement>.refOf(name: String): String = single { it.name == name }.ref.value

    @Test
    fun theFirstScreenIsNumberedInReadingOrder() {
        val carried = RefCarrier.carry(previous = null, read(list, row("Ada", 100), row("Bob", 200)), lastUsed = 0)

        assertEquals(listOf("e1", "e2", "e3"), carried.elements.map { it.ref.value })
        assertEquals(3, carried.lastUsed)
    }

    @Test
    fun theSameScreenKeepsItsRefs() {
        val first = RefCarrier.carry(null, read(list, row("Ada", 100), row("Bob", 200)), 0)

        val again = RefCarrier.carry(first.elements, read(list, row("Ada", 100), row("Bob", 200)), first.lastUsed)

        assertEquals(first.elements, again.elements)
        assertEquals(3, again.lastUsed)
    }

    @Test
    fun anInsertedElementGetsANewRefAndTheOthersKeepTheirs() {
        val first = RefCarrier.carry(null, read(list, row("Ada", 100), row("Bob", 200)), 0)

        val second = RefCarrier.carry(first.elements, read(list, row("Cy", 100), row("Ada", 200), row("Bob", 300)), 3)

        assertEquals("e2", second.elements.refOf("Ada"))
        assertEquals("e3", second.elements.refOf("Bob"))
        assertEquals("e4", second.elements.refOf("Cy"))
        assertEquals(4, second.lastUsed)
    }

    @Test
    fun scrolledElementsKeepTheirRefsAndVanishedRefsAreNotReused() {
        val first = RefCarrier.carry(null, read(list, row("Ada", 100), row("Bob", 200)), 0)

        // Scrolled by one row: Ada is gone, Bob moved up, Cy is new.
        val second = RefCarrier.carry(first.elements, read(list, row("Bob", 100), row("Cy", 200)), 3)

        assertEquals("e3", second.elements.refOf("Bob"))
        assertEquals("e4", second.elements.refOf("Cy"))
        assertTrue(second.elements.none { it.ref.value == "e2" })
    }

    @Test
    fun parentsFollowTheirElements() {
        val first = RefCarrier.carry(null, read(list, row("Ada", 100)), 0)
        val header = Triple("Archived", 0, null)

        // A new container is read first; the list and its row come after it.
        val second = RefCarrier.carry(first.elements, read(header, list, Triple("Ada", 100, "e2")), 2)

        assertEquals("e3", second.elements.refOf("Archived"))
        assertEquals(ElementRef("e1"), second.elements.single { it.name == "Ada" }.parent)
    }

    @Test
    fun identicalElementsMatchTheUnmovedOnesFirst() {
        val first = RefCarrier.carry(null, read(list, row("Like", 100), row("Like", 200)), 0)

        // The first "Like" disappeared; the second did not move.
        val second = RefCarrier.carry(first.elements, read(list, row("Like", 200)), 3)

        assertEquals(
            "e3",
            second.elements
                .single { it.name == "Like" }
                .ref.value,
        )
    }

    @Test
    fun anotherWindowStartsFresh() {
        val first = RefCarrier.carry(null, read(list, row("Ada", 100)), 0)
        val dialog = read(list, row("Ada", 100)).map { it.copy(window = 2) }

        val second = RefCarrier.carry(first.elements, dialog, 2)

        assertEquals(listOf("e3", "e4"), second.elements.map { it.ref.value })
        assertEquals(ElementRef("e3"), second.elements[1].parent)
    }

    @Test
    fun newRefsStayAboveEverythingHandedOutBefore() {
        val carried = RefCarrier.carry(previous = null, read(list), lastUsed = 41)

        assertEquals(listOf("e42"), carried.elements.map { it.ref.value })
    }
}
