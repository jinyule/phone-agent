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
            shown: Boolean = true,
            setup: AccessibilityNodeInfo.() -> Unit = {},
        ) = make(LIST, bounds) {
            isVisibleToUser = shown
            collectionInfo = CollectionInfo(1, 1, false)
            setup()
        }

        /**
         * [shape] for [person]; the nodes between the Like and the change are left out of the read
         * when [leftOut]; the header of [Shape.WINDOW_HEADER] cannot be read unless [readable].
         */
        fun show(
            shape: Shape,
            person: String,
            leftOut: Boolean,
            readable: Boolean = true,
        ) {
            val shown = !leftOut
            when (shape) {
                Shape.NESTED_LIST_LABEL, Shape.WRAPPED_IN_A_NESTED_LIST -> {
                    val like = like(Rect(700, 700, 1000, 800))
                    val wrapped =
                        if (shape == Shape.NESTED_LIST_LABEL) {
                            like
                        } else {
                            make(LAYOUT, Rect(0, 700, 1000, 800)) { isVisibleToUser = shown }.with(like)
                        }
                    val items = list(Rect(0, 600, 1000, 900), shown) { text = "$person's items" }.with(wrapped)
                    host.show(list(Rect(0, 300, 1000, 1800)).with(items))
                }

                Shape.SECTION_TITLE -> {
                    val title = make(TEXT, Rect(0, 300, 1000, 400)) { text = person }
                    val items = list(Rect(0, 600, 1000, 900), shown).with(like(Rect(700, 700, 1000, 800)))
                    val section = make(LAYOUT, Rect(0, 300, 1000, 900)) { isVisibleToUser = shown }.with(title, items)
                    host.show(list(Rect(0, 300, 1000, 1800)).with(section))
                }

                Shape.WINDOW_HEADER -> {
                    val header =
                        make(TEXT, Rect(0, 100, 1000, 200)) {
                            text = "Chat with $person"
                            if (!readable) viewIdResourceName = "$APP:id/unreadable"
                        }
                    val song = make(TEXT, Rect(0, 300, 600, 400)) { text = "Song" }
                    val row = make(LAYOUT, Rect(0, 300, 1000, 400)).with(song, like(Rect(700, 300, 1000, 400)))
                    val list = list(Rect(0, 300, 1000, 1800), shown).with(row)
                    val root = make("android.widget.FrameLayout", GEOMETRY.pixels) { isVisibleToUser = shown }
                    host.windows = mapOf(0 to listOf(window(root.with(header, list), id = 1, layer = 1)))
                }
            }
        }
    }

    /** What a tap on the Like planned on one screen did on the next, with nothing observed in between. */
    private data class Seen(
        val renumbered: Boolean,
        val removedAndAdded: Boolean,
        val status: ActionOutcome.Status,
        val actions: List<Int>,
        val gestures: Int,
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
    ): Seen {
        val screen = Screen()
        screen.show(cell.shape, before, leftOut)
        val port = mainPort(screen.host)
        val options = SnapshotOptions(detail = cell.detail)
        val planned = port.snapshot(options)
        val like = planned.elements.single { it.name == "Like" }
        screen.show(cell.shape, after, leftOut, readableAfter)
        val outcome = port.act(UiAction.Tap(Target.Ref(like.ref)), planned.id)
        val actions = screen.made.flatMap { it.log.ids }
        val gestures = screen.host.gestures.size
        val fresh = outcome.after ?: port.snapshot(options)
        val freshLike = fresh.elements.single { it.name == "Like" }
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
    fun withNothingChangedTheLikeKeepsItsRefAndIsPressedWhetherTheNodesBetweenAreKeptOrLeftOut() =
        runTest {
            val pressed =
                Seen(
                    renumbered = false,
                    removedAndAdded = false,
                    status = ActionOutcome.Status.Ok,
                    actions = listOf(AccessibilityNodeInfo.ACTION_CLICK),
                    gestures = 0,
                )
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
