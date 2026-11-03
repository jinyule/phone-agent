package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UiElement
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Ancestors of an element in a snapshot a press by ref may go to, as the policy counts them. */
private const val SNAPSHOT_ANCESTORS = 8

private val TIMINGS = ScreenTimings()

/** A screen made by [screen], pressed on its label ([long]: a long press), and the view id the press goes to. */
private class PressCase(
    val name: String,
    val long: Boolean,
    val expected: String?,
    val screen: () -> AccessibilityNodeInfo,
)

/**
 * A screen shown when a press on its label is planned ([planned]) and the one shown when it is
 * made ([acted]); [from] and [to] are the view ids the policy's rule gives the press to on each
 * (`null`: a press at the center).
 */
private class ChangeCase(
    val name: String,
    val long: Boolean,
    val from: String?,
    val to: String?,
    val planned: () -> AccessibilityNodeInfo,
    val acted: () -> AccessibilityNodeInfo,
)

/**
 * A press by ref goes to the element the snapshot it was planned on gives it to (fix list r4,
 * 11:1x): the element, else the nearest of its ancestors in that snapshot that takes the press, is
 * enabled and covers the element's center, which is the element the policy judges; with none, a
 * press at the center. Ancestors are the snapshot's, at its detail, not the accessibility tree's.
 * When the screen has changed so that the same rule now gives the press to another element (or
 * to one where the snapshot gave none, or the other way round), the press is stale (MOVED) and
 * nothing is pressed (fix list r7 S1).
 */
@RunWith(AndroidJUnit4::class)
class PressTargetTest {
    private val host = FakeHost()
    private val box = Rect(0, 400, 1000, 600)

    /** A row's box inside [box], for the presses whose screen changes. */
    private val rowBox = Rect(100, 420, 900, 580)

    /** The label's box inside [rowBox]. */
    private val textBox = Rect(200, 450, 800, 550)

    /** Every node the screen was made of, to tell which one was acted on. */
    private val made = ArrayList<AccessibilityNodeInfo>()

    private fun layout(
        id: String?,
        bounds: Rect = box,
        setup: AccessibilityNodeInfo.() -> Unit = {},
    ): AccessibilityNodeInfo =
        node("android.widget.LinearLayout", bounds) {
            id?.let { viewIdResourceName = "$APP:id/$it" }
            setup()
        }.also { made += it }

    private fun label(
        bounds: Rect = box,
        setup: AccessibilityNodeInfo.() -> Unit = {},
    ): AccessibilityNodeInfo =
        node("android.widget.TextView", bounds) {
            text = "Delete"
            viewIdResourceName = "$APP:id/label"
            setup()
        }.also { made += it }

    /** [row] holding the label [wrappers] layouts down; [distinct] wrappers each smaller than the one above. */
    private fun nested(
        row: AccessibilityNodeInfo,
        wrappers: Int,
        distinct: Boolean = false,
    ): AccessibilityNodeInfo {
        (1..wrappers)
            .fold(row) { parent, n ->
                val bounds = if (distinct) Rect(n * 10, 400 + n * 5, 1000 - n * 10, 600 - n * 5) else box
                layout(null, bounds).also { parent.with(it) }
            }.with(label())
        return row
    }

    /**
     * The element a press by ref on [element] goes to by [this] snapshot, as the policy reads it:
     * [element], else the nearest of [SNAPSHOT_ANCESTORS] ancestors in the snapshot that takes the
     * press ([long]: a long click), is not disabled and covers [element]'s center; `null` for none.
     */
    private fun ScreenSnapshot.pressed(
        element: UiElement,
        long: Boolean,
    ): UiElement? {
        val byRef = elements.associateBy { it.ref }
        val x = (element.bounds.left + element.bounds.right) / 2
        val y = (element.bounds.top + element.bounds.bottom) / 2
        val takes = if (long) ElementState.LONG_CLICKABLE else ElementState.CLICKABLE
        return generateSequence(element) { it.parent?.let(byRef::get) }
            .take(SNAPSHOT_ANCESTORS + 1)
            .firstOrNull { e ->
                takes in e.states && ElementState.DISABLED !in e.states &&
                    x in e.bounds.left..e.bounds.right && y in e.bounds.top..e.bounds.bottom
            }
    }

    /**
     * Presses the label by ref on a snapshot at [detail], and checks the press went where that
     * snapshot gives it: the node of that element took the click (and no other node any action),
     * or, with none, one gesture at the label's center, held as a press of its kind is. With
     * [then], that screen is shown between the snapshot and the press. Returns the element the
     * snapshot gives the press to.
     */
    private suspend fun TestScope.pressGoesWhereTheSnapshotSays(
        detail: SnapshotDetail,
        long: Boolean,
        then: (() -> AccessibilityNodeInfo)? = null,
    ): UiElement? {
        val port = mainPort(host)
        val planned = port.snapshot(SnapshotOptions(detail = detail))
        val target = planned.elements.single { it.resourceId == "label" }
        val expected = planned.pressed(target, long)
        then?.let { host.show(it()) }

        val outcome = port.act(UiAction.Tap(Target.Ref(target.ref), long = long), planned.id)

        assertEquals(ActionOutcome.Status.Ok, outcome.status)
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val acted = made.filter { it.log.actions.isNotEmpty() }
        val actedIds = acted.map { it.viewIdResourceName?.substringAfter(":id/") }
        if (expected == null) {
            assertEquals("$detail: no node action", emptyList<String?>(), actedIds)
            val stroke =
                host.gestures
                    .single()
                    .second
                    .single()
            // The label shown last, the one pressed.
            val pixels = Rect().also(made.last { it.viewIdResourceName == "$APP:id/label" }::getBoundsInScreen)
            assertEquals(PixelPoint(pixels.exactCenterX(), pixels.exactCenterY()), stroke.points.single())
            assertEquals(if (long) TIMINGS.longPressMs else TIMINGS.tapMs, stroke.durationMs)
        } else {
            assertEquals("$detail: the snapshot's element took it", listOf(expected.resourceId), actedIds)
            assertEquals(listOf(action), acted.single().log.ids)
            assertTrue("no gesture", host.gestures.isEmpty())
        }
        return expected
    }

    /** Shows each of [cases] in turn and presses its label at every detail. */
    private suspend fun TestScope.pressEach(cases: List<PressCase>) {
        for (case in cases) {
            for (detail in SnapshotDetail.entries) {
                made.clear()
                host.gestures.clear()
                host.show(case.screen())

                val expected = pressGoesWhereTheSnapshotSays(detail, case.long)

                assertEquals("${case.name} in $detail", case.expected, expected?.resourceId)
            }
        }
    }

    @Test
    fun thePressGoesToTheNearestElementThatTakesItAndIsEnabled() =
        runTest {
            val inner = Rect(100, 420, 900, 580)
            pressEach(
                listOf(
                    PressCase("the element itself", long = false, expected = "label") {
                        layout("row") { isClickable = true }.with(label(inner) { isClickable = true })
                    },
                    PressCase("a disabled row", long = false, expected = "outer") {
                        val row =
                            layout("row", inner) {
                                isClickable = true
                                isEnabled = false
                            }
                        layout("outer") { isClickable = true }.with(row.with(label(Rect(200, 450, 800, 550))))
                    },
                    PressCase("a long press on a row taking clicks only", long = true, expected = null) {
                        layout("row") { isClickable = true }.with(label(inner))
                    },
                    PressCase("a long press on a row taking long clicks", long = true, expected = "row") {
                        layout("row") { isLongClickable = true }.with(label(inner))
                    },
                    PressCase("a tap on a row taking long clicks only", long = false, expected = null) {
                        layout("row") { isLongClickable = true }.with(label(inner))
                    },
                    PressCase("a tap on a disabled label taking clicks", long = false, expected = "row") {
                        layout("row") { isClickable = true }.with(
                            label(inner) {
                                isClickable = true
                                isEnabled = false
                            },
                        )
                    },
                    PressCase("a long press on a disabled label taking long clicks", long = true, expected = "row") {
                        layout("row") { isLongClickable = true }.with(
                            label(inner) {
                                isLongClickable = true
                                isEnabled = false
                            },
                        )
                    },
                ),
            )
        }

    @Test
    fun anElementThatDoesNotCoverTheCenterIsNotPressedEdgesIncluded() =
        runTest {
            // The label's center in 0–999 is (299, 249); an edge on it covers it, though the pixel the
            // gesture would go to, (300, 500), lies just past the right edge in pixels.
            val label = Rect(100, 400, 500, 600)
            pressEach(
                listOf(
                    PressCase("a card above the label", long = false, expected = null) {
                        layout("card", Rect(0, 0, 1000, 450)) { isClickable = true }.with(label(label))
                    },
                    PressCase("a strip left of the center", long = false, expected = null) {
                        layout("strip", Rect(0, 400, 290, 600)) { isClickable = true }.with(label(label))
                    },
                    PressCase("a strip ending on the center", long = false, expected = "strip") {
                        layout("strip", Rect(0, 400, 300, 600)) { isClickable = true }.with(label(label))
                    },
                    PressCase("a strip starting on the center", long = false, expected = "strip") {
                        layout("strip", Rect(299, 400, 1000, 600)) { isClickable = true }.with(label(label))
                    },
                    PressCase("a band ending on the center", long = false, expected = "band") {
                        layout("band", Rect(0, 0, 1000, 500)) { isClickable = true }.with(label(label))
                    },
                    PressCase("a band starting on the center", long = false, expected = "band") {
                        layout("band", Rect(0, 498, 1000, 2000)) { isClickable = true }.with(label(label))
                    },
                ),
            )
        }

    @Test
    fun aPressByRefPassesWrappersTheReadLeavesOutOnToTheRowTheSnapshotShows() =
        runTest {
            // Nine wrappers alike between the row and its label: the read keeps the outermost only, so
            // the snapshot has the row two elements up (one in BRIEF), past nine accessibility parents.
            for (detail in SnapshotDetail.entries) {
                for (long in listOf(true, false)) {
                    made.clear()
                    host.gestures.clear()
                    val row =
                        layout("row") {
                            isClickable = true
                            isLongClickable = true
                        }
                    host.show(nested(row, wrappers = 9))

                    val expected = pressGoesWhereTheSnapshotSays(detail, long)

                    assertEquals("$detail long=$long", "row", expected?.resourceId)
                }
            }
        }

    @Test
    fun aLongPressByRefIsNotGivenToANodeTheSnapshotLeavesOut() =
        runTest {
            // A clickable row holding, in the same box, one that also takes long clicks: the two are
            // alike to the read, which keeps the outer one, so the snapshot has no element for a long
            // press and the press is held at the label's center.
            for (detail in SnapshotDetail.entries) {
                made.clear()
                host.gestures.clear()
                val outer = layout("outer") { isClickable = true }
                val inner =
                    layout("inner") {
                        isClickable = true
                        isLongClickable = true
                    }
                host.show(outer.with(inner.with(label())))

                assertNull(detail.name, pressGoesWhereTheSnapshotSays(detail, long = true))
            }
        }

    @Test
    fun theAncestorsCountedAreThoseTheSnapshotShowsAtItsDetail() =
        runTest {
            // Wrappers each in its own box are elements in FULL: seven put the row eighth above the
            // label, the last one counted, eight put it past. BRIEF shows none of them, and the row
            // is the label's parent there.
            val expected =
                listOf(
                    Triple(8, SnapshotDetail.BRIEF, "row"),
                    Triple(8, SnapshotDetail.FULL, null),
                    Triple(7, SnapshotDetail.FULL, "row"),
                )
            for ((wrappers, detail, row) in expected) {
                made.clear()
                host.gestures.clear()
                host.show(nested(layout("row") { isLongClickable = true }, wrappers, distinct = true))

                val pressed = pressGoesWhereTheSnapshotSays(detail, long = true)

                assertEquals("$wrappers wrappers in $detail", row, pressed?.resourceId)
            }
        }

    /** View id of the element the policy's rule gives a press on the label to, on [screen] at [detail]. */
    private suspend fun TestScope.policyPress(
        screen: () -> AccessibilityNodeInfo,
        detail: SnapshotDetail,
        long: Boolean,
    ): String? {
        val elsewhere = FakeHost().apply { show(screen()) }
        val snapshot = mainPort(elsewhere).snapshot(SnapshotOptions(detail = detail))
        return snapshot.pressed(snapshot.elements.single { it.resourceId == "label" }, long)?.resourceId
    }

    /**
     * Changes between the snapshot the policy judged a press by and the press, after which the same
     * rule gives the press to another element, or to one where it gave none, or the other way round.
     */
    private val movedPresses =
        listOf(
            ChangeCase(
                "the row is enabled",
                long = false,
                from = "outer",
                to = "row",
                planned = {
                    layout("outer") { isClickable = true }.with(
                        layout("row", rowBox) {
                            isClickable = true
                            isEnabled = false
                        }.with(label(textBox)),
                    )
                },
                acted = {
                    layout("outer") { isClickable = true }.with(
                        layout("row", rowBox) { isClickable = true }.with(label(textBox)),
                    )
                },
            ),
            ChangeCase(
                "the label is enabled",
                long = false,
                from = "row",
                to = "label",
                planned = {
                    layout("row") { isClickable = true }.with(
                        label(textBox) {
                            isClickable = true
                            isEnabled = false
                        },
                    )
                },
                acted = { layout("row") { isClickable = true }.with(label(textBox) { isClickable = true }) },
            ),
            ChangeCase(
                "the label is disabled",
                long = false,
                from = "label",
                to = "row",
                planned = { layout("row") { isClickable = true }.with(label(textBox) { isClickable = true }) },
                acted = {
                    layout("row") { isClickable = true }.with(
                        label(textBox) {
                            isClickable = true
                            isEnabled = false
                        },
                    )
                },
            ),
            ChangeCase(
                "the row takes long clicks too",
                long = true,
                from = "outer",
                to = "row",
                planned = {
                    layout("outer") { isLongClickable = true }.with(
                        layout("row", rowBox) { isClickable = true }.with(label(textBox)),
                    )
                },
                acted = {
                    layout("outer") { isLongClickable = true }.with(
                        layout("row", rowBox) {
                            isClickable = true
                            isLongClickable = true
                        }.with(label(textBox)),
                    )
                },
            ),
            ChangeCase(
                "the row shrinks off the label's center",
                long = false,
                from = "row",
                to = "outer",
                planned = {
                    layout("outer") { isClickable = true }.with(
                        layout("row", rowBox) { isClickable = true }.with(label(textBox)),
                    )
                },
                acted = {
                    layout("outer") { isClickable = true }.with(
                        layout("row", Rect(100, 420, 400, 580)) { isClickable = true }.with(label(textBox)),
                    )
                },
            ),
            ChangeCase(
                "the only row is enabled",
                long = false,
                from = null,
                to = "row",
                planned = {
                    layout("row") {
                        isClickable = true
                        isEnabled = false
                    }.with(label(textBox))
                },
                acted = { layout("row") { isClickable = true }.with(label(textBox)) },
            ),
            ChangeCase(
                "the only row is disabled",
                long = false,
                from = "row",
                to = null,
                planned = { layout("row") { isClickable = true }.with(label(textBox)) },
                acted = {
                    layout("row") {
                        isClickable = true
                        isEnabled = false
                    }.with(label(textBox))
                },
            ),
            ChangeCase(
                "the only row takes long clicks too",
                long = true,
                from = null,
                to = "row",
                planned = { layout("row") { isClickable = true }.with(label(textBox)) },
                acted = {
                    layout("row") {
                        isClickable = true
                        isLongClickable = true
                    }.with(label(textBox))
                },
            ),
        )

    /** Changes between the snapshot and the press after which the rule gives the press where it did. */
    private val keptPresses =
        listOf(
            ChangeCase(
                "the outer is disabled",
                long = false,
                from = "row",
                to = "row",
                planned = {
                    layout("outer") { isClickable = true }.with(
                        layout("row", rowBox) { isClickable = true }.with(label(textBox)),
                    )
                },
                acted = {
                    layout("outer") {
                        isClickable = true
                        isEnabled = false
                    }.with(layout("row", rowBox) { isClickable = true }.with(label(textBox)))
                },
            ),
            ChangeCase(
                "a disabled row shrinks",
                long = false,
                from = null,
                to = null,
                planned = {
                    layout("row") {
                        isClickable = true
                        isEnabled = false
                    }.with(label(textBox))
                },
                acted = {
                    layout("row", rowBox) {
                        isClickable = true
                        isEnabled = false
                    }.with(label(textBox))
                },
            ),
        )

    @Test
    fun aPressByRefTheScreenNowGivesToAnotherElementIsStaleAndNotMade() =
        runTest {
            // Every cell is checked before the test fails, so a failure lists them all.
            val stale = ActionOutcome.Status.StaleRef(ActionOutcome.StaleReason.MOVED)
            val wanted = ArrayList<String>()
            val seen = ArrayList<String>()
            for (case in movedPresses) {
                for (detail in SnapshotDetail.entries) {
                    made.clear()
                    host.gestures.clear()
                    val from = policyPress(case.planned, detail, case.long)
                    val to = policyPress(case.acted, detail, case.long)
                    host.show(case.planned())
                    val port = mainPort(host)
                    val planned = port.snapshot(SnapshotOptions(detail = detail))
                    val target = planned.elements.single { it.resourceId == "label" }
                    host.show(case.acted())

                    val outcome = port.act(UiAction.Tap(Target.Ref(target.ref), long = case.long), planned.id)

                    val acted = made.filter { it.log.actions.isNotEmpty() }.map { it.viewIdResourceName }
                    wanted += "${case.name} in $detail: ${case.from} -> ${case.to}, $stale, nodes [], gestures 0"
                    seen += "${case.name} in $detail: $from -> $to, ${outcome.status}, nodes $acted, " +
                        "gestures ${host.gestures.size}"
                }
            }
            assertEquals(wanted, seen)
        }

    @Test
    fun aChangeThatLeavesThePressWhereItWasDoesNotStopIt() =
        runTest {
            for (case in keptPresses) {
                for (detail in SnapshotDetail.entries) {
                    made.clear()
                    host.gestures.clear()
                    assertEquals("${case.name} in $detail, acted", case.to, policyPress(case.acted, detail, case.long))
                    host.show(case.planned())

                    val expected = pressGoesWhereTheSnapshotSays(detail, case.long, then = case.acted)

                    assertEquals("${case.name} in $detail", case.from, expected?.resourceId)
                }
            }
        }
}
