package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.CollectionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.StaleReason
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Bounds of the outer list, and of the nested one when it is alike. */
private val WHOLE = Rect(0, 300, 1000, 1800)

/**
 * A list nested in a list, beside a child of the outer list that could not be read, that the read
 * leaves out (fix list fwd B1, Codex catch-up B1): what the nested list holds is as unreliable as
 * the nested list would be, although the read gives it the outer list as parent. Read by the real
 * walker; each table runs every cell and then fails with all the cells that went wrong.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [UnreadableChildShadow::class])
class OmittedNestedListTest {
    /** How the read leaves the nested list "Items" out. */
    private enum class Omitted {
        /** Not visible, though its Like is. */
        INVISIBLE,

        /** The outer list's role, name, value, interactivity and bounds: the read keeps only the outer one. */
        ALIKE,
    }

    private class Cell(
        val omitted: Omitted,
        val titleFirst: Boolean,
        val detail: SnapshotDetail,
    ) {
        override fun toString(): String = "$omitted titleFirst=$titleFirst $detail"
    }

    private val cells =
        Omitted.entries.flatMap { omitted ->
            listOf(true, false).flatMap { titleFirst ->
                SnapshotDetail.entries.map { Cell(omitted, titleFirst, it) }
            }
        }

    /** One screen of a cell: its host, and every node its screens were made of. */
    private class Screen {
        val host = FakeHost()
        val made = ArrayList<AccessibilityNodeInfo>()
        val likes = ArrayList<AccessibilityNodeInfo>()

        fun make(
            cls: String,
            bounds: Rect,
            setup: AccessibilityNodeInfo.() -> Unit = {},
        ): AccessibilityNodeInfo = node(cls, bounds, setup = setup).also { made += it }

        /**
         * An outer list holding the title of [person] and the nested list "Items" holding a Like,
         * in that order when [Cell.titleFirst], else the other way; the title cannot be read when
         * [unreadable]. The nested list is left out of the read as [Cell.omitted] says.
         */
        fun show(
            cell: Cell,
            person: String,
            unreadable: Boolean,
        ) {
            val title =
                make("android.widget.TextView", Rect(0, 300, 1000, 400)) {
                    text = person
                    if (unreadable) viewIdResourceName = "$APP:id/unreadable"
                }
            val like =
                make("android.widget.Button", Rect(700, 700, 1000, 800)) {
                    text = "Like"
                    isClickable = true
                }.also { likes += it }
            val alike = cell.omitted == Omitted.ALIKE
            val items =
                make("androidx.recyclerview.widget.RecyclerView", if (alike) WHOLE else Rect(0, 600, 1000, 900)) {
                    text = "Items"
                    isVisibleToUser = cell.omitted != Omitted.INVISIBLE
                    collectionInfo = CollectionInfo(1, 1, false)
                }.with(like)
            val outer =
                make("androidx.recyclerview.widget.RecyclerView", WHOLE) {
                    if (alike) text = "Items"
                    collectionInfo = CollectionInfo(2, 1, false)
                }
            if (cell.titleFirst) outer.with(title, items) else outer.with(items, title)
            host.show(outer)
        }

        fun touched(): Boolean = host.gestures.isNotEmpty() || made.any { it.log.actions.isNotEmpty() }

        /** Whether the press was one click of a Like and nothing else. */
        fun likeClickedOnly(): Boolean =
            host.gestures.isEmpty() &&
                made.sumOf { it.log.actions.size } == 1 &&
                likes.any { it.log.ids == listOf(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    /** What a tap on the Like planned on [Cell]'s first screen did, with the screen read after. */
    private class Tapped(
        val planned: ScreenSnapshot,
        val outcome: ActionOutcome,
        val fresh: ScreenSnapshot,
        val touched: Boolean,
        val likeClickedOnly: Boolean,
    ) {
        val plannedLike = planned.elements.single { it.name == "Like" }
        val freshLike = fresh.elements.single { it.name == "Like" }
    }

    /**
     * Shows [cell] with the title of [before], plans on it, shows the title of [after] and taps
     * the planned Like with no observation in between; [unreadable] as in [Screen.show].
     */
    private suspend fun TestScope.tapLike(
        cell: Cell,
        before: String,
        after: String,
        unreadable: Boolean,
    ): Tapped {
        val screen = Screen()
        screen.show(cell, before, unreadable)
        val port = mainPort(screen.host)
        val options = SnapshotOptions(detail = cell.detail)
        val planned = port.snapshot(options)
        val like = planned.elements.single { it.name == "Like" }
        screen.show(cell, after, unreadable)
        val outcome = port.act(UiAction.Tap(Target.Ref(like.ref)), planned.id)
        val touched = screen.touched()
        val likeClickedOnly = screen.likeClickedOnly()
        return Tapped(planned, outcome, outcome.after ?: port.snapshot(options), touched, likeClickedOnly)
    }

    @Test
    fun theLikeOfANestedListTheReadLeavesOutBesideAGapIsNeitherCarriedNorMatched() =
        runTest {
            val failures =
                cells.flatMap { cell ->
                    val tapped = tapLike(cell, before = "Bob", after = "Ada", unreadable = true)
                    val diff = RefDiffAnalyzer().diff(tapped.planned, tapped.fresh)
                    listOfNotNull(
                        "renumbered".takeUnless { tapped.freshLike.ref != tapped.plannedLike.ref },
                        "the old Like removed".takeUnless { tapped.plannedLike.ref in diff.removed },
                        "the new Like added".takeUnless {
                            diff.added.any { added ->
                                added.ref == tapped.freshLike.ref
                            }
                        },
                        "stale (UNRELIABLE), got ${tapped.outcome.status}".takeUnless {
                            tapped.outcome.status == ActionOutcome.Status.StaleRef(StaleReason.UNRELIABLE)
                        },
                        "nothing touched".takeUnless { !tapped.touched },
                    ).map { "$cell: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun theLikeOfANestedListTheReadLeavesOutIsPressedWhenItsRowCanBeRead() =
        runTest {
            val failures =
                cells.flatMap { cell ->
                    val tapped = tapLike(cell, before = "Bob", after = "Bob", unreadable = false)
                    listOfNotNull(
                        "kept its ref".takeUnless { tapped.freshLike.ref == tapped.plannedLike.ref },
                        "Ok, got ${tapped.outcome.status}".takeUnless {
                            tapped.outcome.status == ActionOutcome.Status.Ok
                        },
                        "one click of the Like, no gesture".takeUnless { tapped.likeClickedOnly },
                    ).map { "$cell: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }

    /**
     * A window whose root and only list the read leaves out (both out of view): a header of
     * [person] outside the list, which cannot be read when [unreadable], and a row of the list
     * holding a song and a Like.
     */
    private fun Screen.showWindowAroundAListLeftOut(
        person: String,
        unreadable: Boolean,
    ) {
        val header =
            make("android.widget.TextView", Rect(0, 100, 1000, 200)) {
                text = person
                if (unreadable) viewIdResourceName = "$APP:id/unreadable"
            }
        val like =
            make("android.widget.Button", Rect(700, 300, 1000, 400)) {
                text = "Like"
                isClickable = true
            }.also { likes += it }
        val row =
            make("android.widget.LinearLayout", Rect(0, 300, 1000, 400)).with(
                make("android.widget.TextView", Rect(0, 300, 600, 400)) { text = "Song" },
                like,
            )
        val list =
            make("androidx.recyclerview.widget.RecyclerView", Rect(0, 300, 1000, 1800)) {
                isVisibleToUser = false
                collectionInfo = CollectionInfo(1, 1, false)
            }.with(row)
        val root = make("android.widget.FrameLayout", GEOMETRY.pixels) { isVisibleToUser = false }.with(header, list)
        host.windows = mapOf(0 to listOf(window(root, id = 1, layer = 1)))
    }

    /** As [tapLike], on [Screen.showWindowAroundAListLeftOut]. */
    private suspend fun TestScope.tapLikeInWindow(
        detail: SnapshotDetail,
        before: String,
        after: String,
        unreadable: Boolean,
    ): Tapped {
        val screen = Screen()
        screen.showWindowAroundAListLeftOut(before, unreadable)
        val port = mainPort(screen.host)
        val options = SnapshotOptions(detail = detail)
        val planned = port.snapshot(options)
        val like = planned.elements.single { it.name == "Like" }
        screen.showWindowAroundAListLeftOut(after, unreadable)
        val outcome = port.act(UiAction.Tap(Target.Ref(like.ref)), planned.id)
        val touched = screen.touched()
        val likeClickedOnly = screen.likeClickedOnly()
        return Tapped(planned, outcome, outcome.after ?: port.snapshot(options), touched, likeClickedOnly)
    }

    @Test
    fun aRowOfAListTheReadLeavesOutInAWindowReadInPartIsNeitherCarriedNorMatched() =
        runTest {
            // The list would be unreliable when kept (its window lost a label), and its rows with it.
            val failures =
                SnapshotDetail.entries.flatMap { detail ->
                    val tapped = tapLikeInWindow(detail, before = "Bob", after = "Ada", unreadable = true)
                    listOfNotNull(
                        "renumbered".takeUnless { tapped.freshLike.ref != tapped.plannedLike.ref },
                        "stale (UNRELIABLE), got ${tapped.outcome.status}".takeUnless {
                            tapped.outcome.status == ActionOutcome.Status.StaleRef(StaleReason.UNRELIABLE)
                        },
                        "nothing touched".takeUnless { !tapped.touched },
                    ).map { "$detail: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun aRowOfAListTheReadLeavesOutIsPressedWhenItsWindowCanBeRead() =
        runTest {
            val failures =
                SnapshotDetail.entries.flatMap { detail ->
                    val tapped = tapLikeInWindow(detail, before = "Bob", after = "Bob", unreadable = false)
                    listOfNotNull(
                        "kept its ref".takeUnless { tapped.freshLike.ref == tapped.plannedLike.ref },
                        "Ok, got ${tapped.outcome.status}".takeUnless {
                            tapped.outcome.status == ActionOutcome.Status.Ok
                        },
                        "one click of the Like, no gesture".takeUnless { tapped.likeClickedOnly },
                    ).map { "$detail: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }
}
