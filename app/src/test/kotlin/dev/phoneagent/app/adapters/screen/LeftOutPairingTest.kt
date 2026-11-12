package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.CollectionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.StaleReason
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

private const val LIST = "androidx.recyclerview.widget.RecyclerView"
private const val LAYOUT = "android.widget.LinearLayout"
private const val FRAME = "android.widget.FrameLayout"

/** Where the Like is, the same before and after; the nodes it moves between overlap there. */
private val LIKE = Rect(700, 700, 900, 800)

/** Where the Share of [LeftOutPairingTest.Shape.CONTENTS_SWAPPED] is. */
private val SHARE = Rect(150, 700, 350, 800)

/** The first and second of two nodes alike, told apart only by their bounds. */
private val FIRST = Rect(0, 300, 1000, 1000)
private val SECOND = Rect(100, 500, 1000, 1300)

/**
 * The nodes above a Like that the read leaves out are paired as they would be were they kept (fix
 * list fwd2 B1, Codex fwd B1): the Like keeps its ref only below the very nodes it was planned
 * below, which are told apart from nodes alike by their bounds as kept nodes are. Each shape runs
 * with those nodes kept and left out, and the two must agree cell by cell. Read by the real walker;
 * each table runs every cell and then fails with all the cells that went wrong.
 */
@RunWith(AndroidJUnit4::class)
class LeftOutPairingTest {
    /** What the Like moves between, or what changes above it. */
    private enum class Shape {
        /** (Codex) Two lists "Items" alike in a list: the Like leaves one, one alike comes in the other. */
        PARENT_SWAPPED,

        /** Three nodes deep, two alike in the middle: the Like goes from below the first to below the second. */
        MIDDLE_SWAPPED,

        /** Two containers alike under one parent swap what they hold: the Like and a Share. */
        CONTENTS_SWAPPED,

        /** As [PARENT_SWAPPED], the lists left out as alike to a kept twin each, not out of view. */
        DEDUPED_PARENT_SWAPPED,

        /** Two containers alike without a name; the one holding the Like grows (the Like stays). */
        ALIKE_GROWN,
    }

    /** How the nodes between are left out ([leftOut]) or kept, and how the Like is pressed. */
    private class Cell(
        val shape: Shape,
        val reversed: Boolean,
        val detail: SnapshotDetail,
        val long: Boolean,
    ) {
        override fun toString(): String = "$shape reversed=$reversed $detail long=$long"
    }

    private fun cells(vararg shapes: Shape): List<Cell> =
        shapes.flatMap { shape ->
            listOf(false, true).flatMap { reversed ->
                SnapshotDetail.entries.flatMap { detail ->
                    listOf(false, true).map { long -> Cell(shape, reversed, detail, long) }
                }
            }
        }

    /** One screen of a cell: its host, and every node its screens were made of. */
    private class Screen(
        val leftOut: Boolean,
    ) {
        val host = FakeHost()
        val made = ArrayList<AccessibilityNodeInfo>()

        fun make(
            cls: String,
            bounds: Rect,
            setup: AccessibilityNodeInfo.() -> Unit = {},
        ): AccessibilityNodeInfo = node(cls, bounds, setup = setup).also { made += it }

        /** A button with view id [id] ("like" for the Like), labelled [name] unless it is `null`. */
        fun button(
            id: String,
            name: String?,
            bounds: Rect,
        ) = make("android.widget.Button", bounds) {
            name?.let { text = it }
            viewIdResourceName = "$APP:id/$id"
            isClickable = true
            isLongClickable = true
        }

        /** A node the cell keeps or leaves out (out of view). */
        fun between(
            cls: String,
            bounds: Rect,
            setup: AccessibilityNodeInfo.() -> Unit = {},
        ) = make(cls, bounds) {
            isVisibleToUser = !leftOut
            setup()
        }

        private fun items(
            bounds: Rect,
            shown: Boolean,
        ) = make(LIST, bounds) {
            text = "Items"
            collectionInfo = CollectionInfo(1, 1, false)
            isVisibleToUser = shown
        }

        /**
         * [shape] with the Like in the first of the nodes it moves between, or in the second when
         * [inSecond]; [grown] for [Shape.ALIKE_GROWN].
         */
        fun show(
            shape: Shape,
            inSecond: Boolean,
            grown: Boolean,
        ) = when (shape) {
            Shape.PARENT_SWAPPED -> showLists(inSecond, deduped = false)
            Shape.DEDUPED_PARENT_SWAPPED -> showLists(inSecond, deduped = true)
            Shape.MIDDLE_SWAPPED -> showMiddles(inSecond)
            Shape.CONTENTS_SWAPPED -> showContents(inSecond)
            Shape.ALIKE_GROWN -> showGrown(grown)
        }

        /** A twin before a list: kept, the list left out as alike to it when the cell leaves out. */
        private fun twin(bounds: Rect) =
            make(LIST, bounds) {
                text = if (leftOut) "Items" else "Shelf"
                viewIdResourceName = "$APP:id/twin"
                collectionInfo = CollectionInfo(1, 1, false)
            }

        private fun showLists(
            inSecond: Boolean,
            deduped: Boolean,
        ) {
            val first = items(FIRST, shown = deduped || !leftOut)
            val second = items(SECOND, shown = deduped || !leftOut)
            (if (inSecond) second else first).with(button("like", "Like", LIKE))
            val outer = make(LIST, Rect(0, 200, 1000, 1800)) { collectionInfo = CollectionInfo(4, 1, false) }
            if (deduped) outer.with(twin(FIRST), first, twin(SECOND), second) else outer.with(first, second)
            host.show(outer)
        }

        private fun showMiddles(inSecond: Boolean) {
            val middles = listOf(FIRST, SECOND).map { between(FRAME, it) }
            // Inset: of the same box, a kept inner node would be left out as alike to its middle one.
            val inners = listOf(FIRST, SECOND).map { between(LAYOUT, Rect(it).apply { inset(10, 10) }) }
            middles.zip(inners).forEach { (middle, inner) -> middle.with(inner) }
            inners[if (inSecond) 1 else 0].with(button("like", "Like", LIKE))
            host.show(between(LAYOUT, Rect(0, 200, 1000, 1800)).with(*middles.toTypedArray()))
        }

        private fun showContents(inSecond: Boolean) {
            // Unlabelled buttons: the window's labels, its context, stay the same.
            val holders = listOf(FIRST, SECOND).map { between(LAYOUT, it) }
            holders[if (inSecond) 1 else 0].with(button("like", null, LIKE))
            holders[if (inSecond) 0 else 1].with(button("share", null, SHARE))
            host.show(*holders.toTypedArray())
        }

        private fun showGrown(grown: Boolean) {
            val grow = if (grown) 50 else 0
            val first = between(LAYOUT, Rect(0, 300, 1000, 400 + grow))
            val second = between(LAYOUT, Rect(0, 500, 1000, 600))
            first.with(button("like", "Like", Rect(700, 300, 1000, 400)))
            second.with(button("share", "Share", Rect(700, 500, 1000, 600)))
            host.show(first, second)
        }
    }

    /** What a press on the Like planned on one screen did on the next, with nothing observed in between. */
    private data class Seen(
        val renumbered: Boolean,
        val removedAndAdded: Boolean,
        val status: ActionOutcome.Status,
        val actions: List<Int>,
        val gestures: Int,
    )

    /**
     * Shows [cell] (its nodes between kept or [leftOut]), plans on it, shows it again changed
     * (unless [changed] is `false`) and presses the planned Like.
     */
    private suspend fun TestScope.press(
        cell: Cell,
        leftOut: Boolean,
        changed: Boolean,
    ): Seen {
        val screen = Screen(leftOut)
        val shape = cell.shape
        val grownShape = shape == Shape.ALIKE_GROWN
        screen.show(shape, inSecond = cell.reversed && !grownShape, grown = cell.reversed && grownShape)
        val port = mainPort(screen.host)
        val options = SnapshotOptions(detail = cell.detail)
        val planned = port.snapshot(options)
        val like = planned.elements.single { it.resourceId == "like" }
        val moved = changed != cell.reversed
        screen.show(shape, inSecond = moved && !grownShape, grown = moved && grownShape)
        val outcome = port.act(UiAction.Tap(Target.Ref(like.ref), long = cell.long), planned.id)
        val fresh = outcome.after ?: port.snapshot(options)
        val freshLike = fresh.elements.single { it.resourceId == "like" }
        val diff = RefDiffAnalyzer().diff(planned, fresh)
        return Seen(
            renumbered = freshLike.ref != like.ref,
            removedAndAdded = like.ref in diff.removed && diff.added.any { it.ref == freshLike.ref },
            status = outcome.status,
            actions = screen.made.flatMap { it.log.ids },
            gestures = screen.host.gestures.size,
        )
    }

    private fun pressed(long: Boolean) =
        Seen(
            renumbered = false,
            removedAndAdded = false,
            status = ActionOutcome.Status.Ok,
            actions = listOf(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK),
            gestures = 0,
        )

    /** Whether [seen] is stale with a new ref for the Like, the old one gone, and nothing touched. */
    private fun staleUntouched(seen: Seen): Boolean =
        seen.status is ActionOutcome.Status.StaleRef &&
            seen.renumbered &&
            seen.removedAndAdded &&
            seen.actions.isEmpty() &&
            seen.gestures == 0

    @Test
    fun aLikeBelowOtherNodesThanPlannedIsStaleWhetherTheyAreKeptOrLeftOut() =
        runTest {
            val shapes = Shape.entries - Shape.ALIKE_GROWN
            val failures =
                cells(*shapes.toTypedArray()).flatMap { cell ->
                    val kept = press(cell, leftOut = false, changed = true)
                    val leftOut = press(cell, leftOut = true, changed = true)
                    listOfNotNull(
                        "kept: $kept, expected stale and untouched".takeUnless { staleUntouched(kept) },
                        "left out: $leftOut, kept: $kept".takeUnless { leftOut == kept },
                    ).map { "$cell: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun aGrownNodeAlikeToAnotherAboveTheLikeMakesItAmbiguousWhetherTheyAreKeptOrLeftOut() =
        runTest {
            // Fix report fwd 4.7, a known difference until fwd2: kept, the containers are told apart by their bounds.
            val failures =
                cells(Shape.ALIKE_GROWN).flatMap { cell ->
                    val kept = press(cell, leftOut = false, changed = true)
                    val leftOut = press(cell, leftOut = true, changed = true)
                    val ambiguous = kept.status == ActionOutcome.Status.StaleRef(StaleReason.AMBIGUOUS)
                    listOfNotNull(
                        "kept: $kept, expected AMBIGUOUS, untouched".takeUnless { ambiguous && staleUntouched(kept) },
                        "left out: $leftOut, kept: $kept".takeUnless { leftOut == kept },
                    ).map { "$cell: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun withNothingChangedTheLikeKeepsItsRefAndIsPressedWhetherTheNodesAboveAreKeptOrLeftOut() =
        runTest {
            // Alike nodes above, kept or left out, are told apart by their bounds and paired.
            val failures =
                cells(*Shape.entries.toTypedArray()).flatMap { cell ->
                    listOf(false, true).mapNotNull { leftOut ->
                        val seen = press(cell, leftOut, changed = false)
                        "$cell leftOut=$leftOut: $seen".takeUnless { seen == pressed(cell.long) }
                    }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun theNodesLeftOutTakeNoRefNumbers() =
        runTest {
            // The lists of PARENT_SWAPPED left out: the root, the outer list and the Like are e1 to
            // e3, and the Like below the other list is the next number, e4.
            val screen = Screen(leftOut = true)
            screen.show(Shape.PARENT_SWAPPED, inSecond = false, grown = false)
            val port = mainPort(screen.host)
            val options = SnapshotOptions(detail = SnapshotDetail.FULL)
            val planned = port.snapshot(options)
            screen.show(Shape.PARENT_SWAPPED, inSecond = true, grown = false)
            val fresh = port.snapshot(options)

            assertEquals(listOf("e1", "e2", "e3"), planned.elements.map { it.ref.value })
            assertEquals(listOf("e1", "e2", "e4"), fresh.elements.map { it.ref.value })
        }
}
