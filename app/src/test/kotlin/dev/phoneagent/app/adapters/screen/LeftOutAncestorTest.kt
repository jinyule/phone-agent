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

private const val LIST = "androidx.recyclerview.widget.RecyclerView"
private const val LAYOUT = "android.widget.LinearLayout"
private const val TEXT = "android.widget.TextView"

/** How many buttons each layout of [LeftOutAncestorTest.deepAndWide] holds. */
private const val WIDE = 40

/** How many times pairing and explaining a stale ref may read each node of the two reads. */
private const val READS_PER_NODE = 10

/** [list], counting the [reads] of its items. */
private class CountingList<T>(
    private val list: List<T>,
) : AbstractList<T>() {
    var reads = 0
        private set

    override val size: Int get() = list.size

    override fun get(index: Int): T = list[index].also { reads++ }
}

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

    /**
     * [ScreenReader.MAX_DEPTH] layouts out of view, one in the other, the topmost (of view id [top])
     * the root of the window, each holding [WIDE] buttons: 3280 nodes, 80 of them left out.
     */
    private fun deepAndWide(top: String = "top"): TreeRead {
        val nested = List(ScreenReader.MAX_DEPTH) { node(LAYOUT, GEOMETRY.pixels) { isVisibleToUser = false } }
        nested.first().viewIdResourceName = "$APP:id/$top"
        nested.forEachIndexed { level, layout ->
            repeat(WIDE) { i -> layout.with(button("B$level-$i", Rect(0, 40 * i, 500, 40 * i + 30))) }
            nested.getOrNull(level + 1)?.let { layout.with(it) }
        }
        val host = FakeHost().apply { windows = mapOf(0 to listOf(window(nested.first(), id = 1, layer = 1))) }
        return ScreenReader.read(host.windowsOn(0), GEOMETRY, OWN)
    }

    @Test
    fun aReadPairsEachNodeItWalksOnceHoweverManyAreLeftOut() {
        // The tree remembers each node once, and pairing a read with the same screen pairs each
        // node with itself.
        val levels = ScreenReader.MAX_DEPTH
        val first = deepAndWide()
        val carried = RefCarrier.carry(null, first.nodes, lastUsed = 0)
        val paired = RefCarrier.pair(carried.tree, deepAndWide().nodes)

        assertEquals(levels * WIDE, first.elements.size)
        assertEquals(levels * (WIDE + 1), first.nodes.size)
        assertEquals("one entry per node walked", first.nodes.size, carried.tree.size)
        assertEquals("one ref per element", (1..levels * WIDE).map { "e$it" }, carried.refs.map { it.value })
        assertEquals(first.nodes.indices.toList(), paired.toList())
    }

    @Test
    fun pairingAndExplainingAStaleRefReadEachNodeABoundedNumberOfTimes() {
        // A count, not a clock (fix list fwd2 B1.5): pairing the 3280 nodes with those of the same
        // screen, or of one whose root changed (nothing pairs), and then explaining why the deepest
        // button is stale (80 nodes above it, none paired), read each node at most READS_PER_NODE
        // times.
        val planned = RefCarrier.carry(null, deepAndWide().nodes, lastUsed = 0).tree
        val deepest = planned.indices.last { planned[it].ref != null }
        val seen =
            listOf("top", "other").associateWith { top ->
                val tree = CountingList(planned)
                val fresh = CountingList(deepAndWide(top).nodes)
                val paired = RefCarrier.pair(tree, fresh)
                val reason = if (deepest in paired) null else StaleReasons.of(deepest, tree, fresh, paired)
                reason to tree.reads + fresh.reads
            }
        val bound = READS_PER_NODE * 2 * planned.size

        assertEquals(mapOf("top" to null, "other" to StaleReason.NOT_FOUND), seen.mapValues { it.value.first })
        assertEquals(emptyMap<String, Int>(), seen.mapValues { it.value.second }.filterValues { it > bound })
    }

    @Test
    fun theDeepestNodeReadIsPairedBelowEveryNodeAboveIt() {
        // [depth] nodes out of view above the Like, the topmost the root of the window, of view id [top].
        fun read(
            depth: Int,
            top: String = "top",
        ): TreeRead {
            val like = button("Like", Rect(700, 700, 1000, 800))
            val root = node(LAYOUT, GEOMETRY.pixels) { isVisibleToUser = false }
            root.viewIdResourceName = "$APP:id/$top"
            (2..depth)
                .fold(root) { parent, _ ->
                    node(LAYOUT, GEOMETRY.pixels) { isVisibleToUser = false }.also { parent.with(it) }
                }.with(like)
            val host = FakeHost().apply { windows = mapOf(0 to listOf(window(root, id = 1, layer = 1))) }
            return ScreenReader.read(host.windowsOn(0), GEOMETRY, OWN)
        }
        val deepest = read(ScreenReader.MAX_DEPTH)
        val carried = RefCarrier.carry(null, deepest.nodes, lastUsed = 0)
        val again = RefCarrier.carry(carried.tree, read(ScreenReader.MAX_DEPTH).nodes, carried.lastUsed)
        val topChanged = read(ScreenReader.MAX_DEPTH, top = "other").nodes
        val topRenamed = RefCarrier.carry(carried.tree, topChanged, carried.lastUsed)

        assertEquals(ScreenReader.MAX_DEPTH, deepest.nodes.single { it.element != null }.depth)
        assertEquals(listOf("e1"), again.refs.map { it.value })
        assertEquals("the node 80 above changed", listOf("e2"), topRenamed.refs.map { it.value })
        assertTrue("below the deepest node walked", read(ScreenReader.MAX_DEPTH + 1).elements.isEmpty())
    }
}
