package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rows of a list tell apart the controls every row has (fix list r1 B1). */
class ElementIdentityTest {
    private val nodes = ArrayList<Identities.Facts>()

    private fun add(
        label: String?,
        bounds: Bounds,
        parent: Int? = null,
        role: ElementRole = ElementRole.CONTAINER,
        list: Boolean = false,
    ): Int {
        nodes +=
            Identities.Facts(
                1,
                role,
                resourceId = null,
                name = label,
                label = label,
                list = list,
                bounds = bounds,
                parent = parent,
            )
        return nodes.lastIndex
    }

    private fun row(
        list: Int,
        top: Int,
        vararg labels: String,
    ): Int {
        val row = add(null, Bounds(0, top, 999, top + 49), parent = list)
        labels.forEach { add(it, Bounds(0, top, 999, top + 49), parent = row, role = ElementRole.TEXT) }
        return row
    }

    private val screen = Bounds(0, 0, 999, 999)

    @Test
    fun everyNodeOfARowCarriesTheRowsFirstLabels() {
        val list = add(null, screen, list = true)
        val ada = row(list, 100, "Ada", "Hi", "10:08", "Like")
        val bob = row(list, 200, "Bob", "Hi", "10:09", "Like")

        val identities = Identities.of(nodes)

        assertEquals(listOf("Ada", "Hi", "10:08"), identities[ada].row)
        assertEquals(listOf("Ada", "Hi", "10:08"), identities[ada + 4].row)
        assertEquals(listOf("Bob", "Hi", "10:09"), identities[bob + 4].row)
        assertNull("the list is in no row", identities[list].row)
    }

    @Test
    fun aWrapperFillingTheListIsLookedThrough() {
        val scroll = add(null, screen, list = true)
        val content = add(null, screen, parent = scroll)
        val ada = row(content, 100, "Ada")

        val identities = Identities.of(nodes)

        assertNull(identities[content].row)
        assertEquals(listOf("Ada"), identities[ada + 1].row)
    }

    @Test
    fun aSingleSmallerChildIsTheOnlyRow() {
        val list = add(null, screen, list = true)
        val only = row(list, 100, "Ada", "Like")

        assertEquals(listOf("Ada", "Like"), Identities.of(nodes)[only + 2].row)
    }

    @Test
    fun theInnermostListCounts() {
        val outer = add(null, screen, list = true)
        val section = row(outer, 0, "Today")
        val inner = add(null, Bounds(0, 100, 999, 500), parent = section, list = true)
        val ada = row(inner, 100, "Ada")

        assertEquals(listOf("Ada"), Identities.of(nodes)[ada + 1].row)
    }

    @Test
    fun longLabelsAreCut() {
        val list = add(null, screen, list = true)
        val long = "x".repeat(Identities.LABEL_CHARS + 10)
        val item = row(list, 100, long)

        assertEquals(listOf("x".repeat(Identities.LABEL_CHARS)), Identities.of(nodes)[item].row)
    }

    @Test
    fun outsideListsThereIsNoRow() {
        val root = add(null, screen)
        val ok = add("OK", Bounds(0, 100, 999, 149), parent = root, role = ElementRole.BUTTON)

        val identity = Identities.of(nodes)[ok]

        assertEquals(ElementIdentity(1, ElementRole.BUTTON, null, "OK", null), identity)
    }
}
