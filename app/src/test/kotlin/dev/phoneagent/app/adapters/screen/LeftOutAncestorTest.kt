package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.CollectionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.StaleReason
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.IdentityHashMap

private const val LIST = "androidx.recyclerview.widget.RecyclerView"
private const val LAYOUT = "android.widget.LinearLayout"
private const val TEXT = "android.widget.TextView"

/**
 * A Like below nodes the read may leave out (out of view) whose context changes after the plan
 * (fix list fwd B1b): its ref is carried and matched as it would be were those nodes kept. Each
 * shape runs with them kept and with them left out, at both details, and the two must agree cell
 * by cell. Read by the real walker; each table runs every cell and then fails with all the cells
 * that went wrong.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [UnreadableChildShadow::class])
class LeftOutAncestorTest {
    /** Where the change is, above the Like; [stale] is what a press planned before it gets. */
    private enum class Shape(
        val stale: StaleReason,
    ) {
        /** (a) The label "<person>'s items" of a list nested in a list, holding the Like. */
        NESTED_LIST_LABEL(StaleReason.ROW_CHANGED),

        /** (d) As [NESTED_LIST_LABEL], the Like in a wrapper, the nested list's row: the outer of two nodes changes. */
        WRAPPED_IN_A_NESTED_LIST(StaleReason.ROW_CHANGED),

        /** (b) The title <person> of a section, a row of the outer list, holding a list holding the Like. */
        SECTION_TITLE(StaleReason.ROW_CHANGED),

        /** (c) The header "Chat with <person>" of a window, outside its only list, a row of which holds the Like. */
        WINDOW_HEADER(StaleReason.WINDOW_CHANGED),
    }

    private class Cell(
        val shape: Shape,
        val detail: SnapshotDetail,
    ) {
        override fun toString(): String = "$shape $detail"
    }

    private val cells = Shape.entries.flatMap { shape -> SnapshotDetail.entries.map { Cell(shape, it) } }

    /** One screen of a cell: its host, and every node its screens were made of. */
    private class Screen {
        val host = FakeHost()
        val made = ArrayList<AccessibilityNodeInfo>()

        fun make(
            cls: String,
            bounds: Rect,
            setup: AccessibilityNodeInfo.() -> Unit = {},
        ): AccessibilityNodeInfo = node(cls, bounds, setup = setup).also { made += it }

        private fun like(bounds: Rect) =
            make("android.widget.Button", bounds) {
                text = "Like"
                isClickable = true
            }

        private fun list(
            bounds: Rect,
            setup: AccessibilityNodeInfo.() -> Unit = {},
        ) = make(LIST, bounds) {
            collectionInfo = CollectionInfo(1, 1, false)
            setup()
        }

        /**
         * [shape] for [person]; the nodes between the Like and the change are left out of the read
         * when [leftOut] and look as [look] says; the header of [Shape.WINDOW_HEADER] cannot be read
         * unless [readable].
         */
        fun show(
            shape: Shape,
            person: String,
            leftOut: Boolean,
            readable: Boolean = true,
            look: Look = Look(),
        ) {
            val between: AccessibilityNodeInfo.() -> Unit = {
                isVisibleToUser = !leftOut
                look.state(this)
            }

            fun Rect.held() = Rect(left, top + look.scrolled, right, bottom + look.scrolled)

            fun Rect.grown() = Rect(left, top, right, bottom + look.grown)
            when (shape) {
                Shape.NESTED_LIST_LABEL, Shape.WRAPPED_IN_A_NESTED_LIST -> {
                    val like = like(Rect(700, 700, 1000, 800).held())
                    val wrapped =
                        if (shape == Shape.NESTED_LIST_LABEL) {
                            like
                        } else {
                            make(LAYOUT, Rect(0, 700, 1000, 800).held().grown(), between).with(like)
                        }
                    val items =
                        list(Rect(0, 600, 1000, 900).held().grown()) {
                            text = "$person's items"
                            between()
                        }.with(wrapped)
                    host.show(list(Rect(0, 300, 1000, 1800)).with(items))
                }

                Shape.SECTION_TITLE -> {
                    val title = make(TEXT, Rect(0, 300, 1000, 400).held()) { text = person }
                    val items = list(Rect(0, 600, 1000, 900).held().grown(), between)
                    val section = make(LAYOUT, Rect(0, 300, 1000, 900).held().grown(), between)
                    items.with(like(Rect(700, 700, 1000, 800).held()))
                    host.show(list(Rect(0, 300, 1000, 1800)).with(section.with(title, items)))
                }

                Shape.WINDOW_HEADER -> {
                    val header =
                        make(TEXT, Rect(0, 100, 1000, 200)) {
                            text = "Chat with $person"
                            if (!readable) viewIdResourceName = "$APP:id/unreadable"
                        }
                    val song = make(TEXT, Rect(0, 300, 600, 400).held()) { text = "Song" }
                    val like = like(Rect(700, 300, 1000, 400).held())
                    val row = make(LAYOUT, Rect(0, 300, 1000, 400).held()).with(song, like)
                    val list = list(Rect(0, 300, 1000, 1800).grown(), between).with(row)
                    val root = make("android.widget.FrameLayout", GEOMETRY.pixels, between)
                    host.windows = mapOf(0 to listOf(window(root.with(header, list), id = 1, layer = 1)))
                }
            }
        }
    }

    /**
     * How the nodes between the Like and the change look on one screen: [state] set on each, their
     * bounds [grown] at the bottom by that much (the window's root aside: it fills the display),
     * and everything the outer list holds (the only list, for [Shape.WINDOW_HEADER]) [scrolled]
     * down by that much.
     */
    private class Look(
        val state: AccessibilityNodeInfo.() -> Unit = {},
        val grown: Int = 0,
        val scrolled: Int = 0,
    )

    /**
     * A change after the plan of the nodes between alone, from [before] to [after], that leaves a
     * kept node its identity: of their states, or of their bounds. Only a scroll moves the Like.
     */
    private enum class Change(
        val before: Look,
        val after: Look,
        val movesTheLike: Boolean = false,
    ) {
        SELECTED(Look(), Look(state = { isSelected = true })),
        FOCUSED(Look(), Look(state = { isFocused = true })),
        CHECKED(
            Look(state = { isCheckable = true }),
            Look(state = {
                isCheckable = true
                checked = AccessibilityNodeInfo.CHECKED_STATE_TRUE
            }),
        ),
        RESIZED(Look(), Look(grown = 50)),
        SCROLLED(Look(), Look(scrolled = 100), movesTheLike = true),
    }

    /** What a tap on the Like planned on one screen did on the next, with nothing observed in between. */
    private data class Seen(
        val renumbered: Boolean,
        val removedAndAdded: Boolean,
        val status: ActionOutcome.Status,
        val actions: List<Int>,
        val gestures: Int,
    )

    /** The Like kept its ref and was pressed by its node. */
    private val pressed =
        Seen(
            renumbered = false,
            removedAndAdded = false,
            status = ActionOutcome.Status.Ok,
            actions = listOf(AccessibilityNodeInfo.ACTION_CLICK),
            gestures = 0,
        )

    /**
     * Shows [cell] for [before], plans on it, shows it for [after] and taps the planned Like;
     * [leftOut] as in [Screen.show], [readableAfter] as its `readable` for [after].
     */
    private suspend fun TestScope.tapLike(
        cell: Cell,
        leftOut: Boolean,
        before: String,
        after: String,
        readableAfter: Boolean = true,
    ): Seen =
        tapLike(cell.detail) { planned ->
            if (planned) show(cell.shape, before, leftOut) else show(cell.shape, after, leftOut, readableAfter)
        }

    /** Plans on the screen [show] shows when told it is the one planned on, then taps the planned Like on the next. */
    private suspend fun TestScope.tapLike(
        detail: SnapshotDetail,
        show: Screen.(planned: Boolean) -> Unit,
    ): Seen {
        val screen = Screen()
        screen.show(true)
        val port = mainPort(screen.host)
        val options = SnapshotOptions(detail = detail)
        val planned = port.snapshot(options)
        // A checkable node between takes the Like's text for its name: the Like is the button.
        val like = planned.elements.single { it.name == "Like" && it.role == ElementRole.BUTTON }
        screen.show(false)
        val outcome = port.act(UiAction.Tap(Target.Ref(like.ref)), planned.id)
        val actions = screen.made.flatMap { it.log.ids }
        val gestures = screen.host.gestures.size
        val fresh = outcome.after ?: port.snapshot(options)
        val freshLike = fresh.elements.single { it.name == "Like" && it.role == ElementRole.BUTTON }
        val diff = RefDiffAnalyzer().diff(planned, fresh)
        return Seen(
            renumbered = freshLike.ref != like.ref,
            removedAndAdded = like.ref in diff.removed && diff.added.any { it.ref == freshLike.ref },
            status = outcome.status,
            actions = actions,
            gestures = gestures,
        )
    }

    @Test
    fun aChangeAboveTheLikeMakesItStaleWhetherTheNodesBetweenAreKeptOrLeftOut() =
        runTest {
            val failures =
                cells.flatMap { cell ->
                    val kept = tapLike(cell, leftOut = false, before = "Bob", after = "Ada")
                    val leftOut = tapLike(cell, leftOut = true, before = "Bob", after = "Ada")
                    val stale =
                        Seen(
                            renumbered = true,
                            removedAndAdded = true,
                            status = ActionOutcome.Status.StaleRef(cell.shape.stale),
                            actions = emptyList(),
                            gestures = 0,
                        )
                    listOfNotNull(
                        "kept: $kept, expected $stale".takeUnless { kept == stale },
                        "left out: $leftOut, kept: $kept".takeUnless { leftOut == kept },
                    ).map { "$cell: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun aWindowNoLongerReadInFullAboveTheNodesBetweenMakesTheLikeStaleWhetherTheyAreKeptOrLeftOut() =
        runTest {
            // The header outside the list cannot be read after the plan: what the list would be kept.
            val failures =
                SnapshotDetail.entries.flatMap { detail ->
                    val cell = Cell(Shape.WINDOW_HEADER, detail)
                    val kept = tapLike(cell, leftOut = false, before = "Bob", after = "Bob", readableAfter = false)
                    val leftOut = tapLike(cell, leftOut = true, before = "Bob", after = "Bob", readableAfter = false)
                    val stale =
                        Seen(
                            renumbered = true,
                            removedAndAdded = true,
                            status = ActionOutcome.Status.StaleRef(StaleReason.UNRELIABLE),
                            actions = emptyList(),
                            gestures = 0,
                        )
                    listOfNotNull(
                        "kept: $kept, expected $stale".takeUnless { kept == stale },
                        "left out: $leftOut, kept: $kept".takeUnless { leftOut == kept },
                    ).map { "$cell: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun aRowCutDownToItsControlsAboveTheNodesBetweenMakesTheLikeStaleWhetherTheyAreKeptOrLeftOut() =
        runTest {
            // After the plan the row reaches past the top of the list, its only label the Like's own
            // (phase1 rule 4); no label changes, the Like does not move.
            fun Screen.showRow(
                leftOut: Boolean,
                top: Int,
            ) {
                val like =
                    make("android.widget.Button", Rect(700, 450, 1000, 550)) {
                        text = "Like"
                        isClickable = true
                    }
                val wrapper = make(LAYOUT, Rect(600, 400, 1000, 600)) { isVisibleToUser = !leftOut }.with(like)
                val row = make(LAYOUT, Rect(0, top, 1000, 600)) { isVisibleToUser = !leftOut }.with(wrapper)
                val list =
                    make(LIST, Rect(0, 300, 1000, 1800)) { collectionInfo = CollectionInfo(1, 1, false) }.with(row)
                host.show(list)
            }
            val failures =
                SnapshotDetail.entries.flatMap { detail ->
                    val (kept, leftOut) =
                        listOf(false, true).map { left ->
                            tapLike(detail) { planned -> showRow(left, top = if (planned) 400 else 250) }
                        }
                    val stale =
                        Seen(
                            renumbered = true,
                            removedAndAdded = true,
                            status = ActionOutcome.Status.StaleRef(StaleReason.UNRELIABLE),
                            actions = emptyList(),
                            gestures = 0,
                        )
                    listOfNotNull(
                        "kept: $kept, expected $stale".takeUnless { kept == stale },
                        "left out: $leftOut, kept: $kept".takeUnless { leftOut == kept },
                    ).map { "$detail: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun withNothingChangedTheLikeKeepsItsRefAndIsPressedWhetherTheNodesBetweenAreKeptOrLeftOut() =
        runTest {
            val failures =
                cells.flatMap { cell ->
                    listOf(false, true).mapNotNull { leftOut ->
                        val seen = tapLike(cell, leftOut, before = "Bob", after = "Bob")
                        "$cell leftOut=$leftOut: $seen".takeUnless { seen == pressed }
                    }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun aChangeOfTheNodesBetweenThatLeavesAKeptNodeItsIdentityDoesTheSameWhetherTheyAreKeptOrLeftOut() =
        runTest {
            // States and bounds make no part of a kept node's identity: the Like keeps its ref and
            // is pressed, or is stale (MOVED) when the scroll moved it too, the nodes between kept or not.
            val moved =
                Seen(
                    renumbered = false,
                    removedAndAdded = false,
                    status = ActionOutcome.Status.StaleRef(StaleReason.MOVED),
                    actions = emptyList(),
                    gestures = 0,
                )
            val failures =
                Change.entries.flatMap { change ->
                    cells.flatMap { cell ->
                        val (kept, leftOut) =
                            listOf(false, true).map { left ->
                                tapLike(cell.detail) { planned ->
                                    show(cell.shape, "Bob", left, look = if (planned) change.before else change.after)
                                }
                            }
                        val expected = if (change.movesTheLike) moved else pressed
                        listOfNotNull(
                            "kept: $kept, expected $expected".takeUnless { kept == expected },
                            "left out: $leftOut, kept: $kept".takeUnless { leftOut == kept },
                        ).map { "$change $cell: $it" }
                    }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun aReadMakesOneLineagePerNodeItWalksSharingTheNodesAboveHoweverDeepTheyAre() {
        // [levels] nodes out of view, one in the other, the topmost the root of the window, each
        // holding [wide] buttons: those of a level have every level above them in their lineage,
        // which copied into each would make 40 * (1 + 2 + ... + 80) = 129600 nodes.
        val levels = Lineage.MAX
        val wide = 40
        val nested = List(levels) { node(LAYOUT, GEOMETRY.pixels) { isVisibleToUser = false } }
        nested.forEachIndexed { level, layout ->
            repeat(wide) { i -> layout.with(button("B$level-$i", Rect(0, 40 * i, 500, 40 * i + 30))) }
            nested.getOrNull(level + 1)?.let { layout.with(it) }
        }
        val host = FakeHost().apply { windows = mapOf(0 to listOf(window(nested.first(), id = 1, layer = 1))) }

        val elements = ScreenReader.read(host.windowsOn(0), GEOMETRY, OWN).elements

        val made = Collections.newSetFromMap(IdentityHashMap<Lineage, Boolean>())
        elements.forEach { element -> made += generateSequence(element.identity.lineage) { it.outer } }
        assertEquals(levels * wide, elements.size)
        assertEquals((1..levels).associateWith { wide }, elements.groupingBy { it.identity.lineage?.size }.eachCount())
        assertEquals("one per node walked below one left out", levels * (wide + 1) - 1, made.size)
    }

    @Test
    fun theLineageOfTheDeepestNodeReadHoldsEveryNodeAboveIt() {
        // [depth] nodes out of view above the Like, the topmost the root of the window.
        fun read(depth: Int): List<ReadElement> {
            val like = button("Like", Rect(700, 700, 1000, 800))
            val top = node(LAYOUT, GEOMETRY.pixels) { isVisibleToUser = false }
            (2..depth)
                .fold(top) { parent, _ ->
                    node(LAYOUT, GEOMETRY.pixels) { isVisibleToUser = false }.also { parent.with(it) }
                }.with(like)
            val host = FakeHost().apply { windows = mapOf(0 to listOf(window(top, id = 1, layer = 1))) }
            return ScreenReader.read(host.windowsOn(0), GEOMETRY, OWN).elements
        }

        val deepest = read(ScreenReader.MAX_DEPTH).single().identity
        assertEquals(Lineage.MAX, deepest.lineage?.size)
        assertEquals(
            List(Lineage.MAX) { ElementRole.CONTAINER },
            deepest.lineage
                ?.nodes()
                ?.map { it.role }
                ?.toList(),
        )
        assertTrue("below the deepest node walked", read(ScreenReader.MAX_DEPTH + 1).isEmpty())
    }
}
