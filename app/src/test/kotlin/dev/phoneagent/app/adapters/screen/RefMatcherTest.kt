package dev.phoneagent.app.adapters.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefMatcherTest {
    /** [read] as the port showed it: refs from [RefCarrier], every element shown, unless [shown] says otherwise. */
    private fun planned(
        read: TreeBuilder,
        shown: (ReadElement) -> Boolean = { true },
    ): Observed {
        val carried = RefCarrier.carry(null, read.elements, lastUsed = 0)
        val elements =
            read.elements.indices
                .filter {
                    shown(
                        read.elements[it],
                    )
                }.map { read.elements[it].element.copy(ref = carried.refs[it]) }
        return Observed(snapshot(SnapshotId("s1"), elements), SnapshotDetail.BRIEF, carried.tree)
    }

    private fun snapshot(
        id: SnapshotId,
        elements: List<UiElement> = emptyList(),
    ) = ScreenSnapshot(
        id = id,
        surface = SurfaceRef(0, SurfaceKind.MAIN),
        pkg = APP,
        activity = null,
        elements = elements,
        ocr = null,
        quality = TreeQuality.GOOD,
        flags = emptySet(),
        fingerprint = "f",
    )

    private fun stale(reason: StaleReason) = RefMatcher.Match.Stale(reason)

    private fun match(
        planned: Observed,
        ref: String,
        fresh: TreeBuilder,
    ): RefMatcher.Match = RefMatcher.match(planned, ElementRef(ref), fresh.read())

    @Test
    fun findsTheSameElementAtANewIndex() {
        val planned = planned(chats("Ada" to 100, "Bob" to 200))
        val fresh =
            TreeBuilder().apply {
                add("Banner", top = 500)
                val list = add("Chats", top = 0)
                add("Ada", top = 100, parent = list)
                add("Bob", top = 200, parent = list)
            }

        val match = match(planned, "e3", fresh)

        assertSame(fresh.elements.last(), (match as RefMatcher.Match.Found).element)
    }

    @Test
    fun valuesAndStatesMayChange() {
        val switch = TreeBuilder().apply { add("Wi-Fi", top = 100, role = ElementRole.SWITCH) }
        val planned = planned(switch)
        val toggled =
            TreeBuilder().apply {
                add(
                    "Wi-Fi",
                    top = 100,
                    role = ElementRole.SWITCH,
                    more = More(value = "on"),
                )
            }

        assertEquals(RefMatcher.Match.Found::class, match(planned, "e1", toggled)::class)
    }

    @Test
    fun aMovedRenamedReplacedOrReWindowedElementIsStale() {
        val planned = planned(chats("Ada" to 100))
        val matchAda = { fresh: TreeBuilder -> match(planned, "e2", fresh) }

        assertEquals(RefMatcher.Match.Found::class, matchAda(chats("Ada" to 100))::class)
        assertEquals(stale(StaleReason.MOVED), matchAda(chats("Ada" to 101)))
        assertEquals(stale(StaleReason.NOT_FOUND), matchAda(chats("Bob" to 100)))
        assertEquals(
            stale(StaleReason.WINDOW_CHANGED),
            matchAda(
                TreeBuilder(window = 2).apply { add("Ada", top = 100, parent = add("Chats", top = 0)) },
            ),
        )
        assertEquals(stale(StaleReason.WINDOW_CHANGED), matchAda(TreeBuilder()))
    }

    @Test
    fun anElementUnderAReplacedParentIsStale() {
        val planned = planned(TreeBuilder().apply { add("Delete", top = 100, parent = add("Ada", top = 100)) })

        val fresh = TreeBuilder().apply { add("Delete", top = 100, parent = add("Bob", top = 100)) }

        assertEquals(stale(StaleReason.NOT_FOUND), match(planned, "e2", fresh))
    }

    @Test
    fun identicalElementsMatchOnlyWhereTheyWere() {
        val planned = planned(chats("Like" to 100, "Like" to 200))
        val same = chats("Like" to 100, "Like" to 200)

        assertSame(same.elements[2], (match(planned, "e3", same) as RefMatcher.Match.Found).element)
        val oneMore = chats("Like" to 100, "Like" to 200, "Like" to 300)
        assertSame(oneMore.elements[2], (match(planned, "e3", oneMore) as RefMatcher.Match.Found).element)
        // Both moved: nothing tells which is the planned one.
        assertEquals(stale(StaleReason.AMBIGUOUS), match(planned, "e3", chats("Like" to 150, "Like" to 250)))
    }

    @Test
    fun aRefTheSnapshotDidNotShowIsUnknown() {
        val planned = planned(chats("Ada" to 100)) { it.element.name != "Chats" }

        assertEquals(RefMatcher.Match.UnknownRef, match(planned, "e9", chats()))
        assertEquals(RefMatcher.Match.UnknownRef, match(planned, "e1", chats("Ada" to 100)))
    }

    @Test
    fun historyKeepsTheNewestSnapshots() {
        val history = SnapshotHistory(keep = 2)
        val ids = listOf("s1", "s2", "s3").map(::SnapshotId)
        ids.forEach { history.put(Observed(snapshot(it), SnapshotDetail.FULL, emptyList())) }

        assertNull(history.get(ids[0]))
        assertEquals(ids[1], history.get(ids[1])?.snapshot?.id)
        assertEquals(ids[2], history.newest()?.snapshot?.id)
        assertEquals(SnapshotDetail.FULL, history.get(ids[2])?.detail)
        assertThrows(IllegalArgumentException::class.java) { SnapshotHistory(keep = 0) }
    }
}
