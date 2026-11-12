package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A press by ref whose element answers its node action `false` at once, with nothing changing, is
 * not made up for with a touch at the center (fix list fwd B2, Codex catch-up B2): the policy judged
 * the element the press goes to, not what a touch there would reach. Each table runs every cell and
 * then fails with all the cells that went wrong.
 */
@RunWith(AndroidJUnit4::class)
class RefusedPressTest {
    /** What lies over the center of the element pressed when the press is made. */
    private enum class Layout {
        /** A sibling button "Delete" of the row covers the text's center; the screen does not change. */
        SIBLING,

        /** After the plan, another package's window "Allow" comes over the center. */
        NEW_WINDOW,
    }

    private class Cell(
        val layout: Layout,
        val detail: SnapshotDetail,
        val long: Boolean,
    ) {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK

        override fun toString(): String = "$layout $detail long=$long"
    }

    private val cells =
        Layout.entries.flatMap { layout ->
            SnapshotDetail.entries.flatMap { detail -> listOf(false, true).map { Cell(layout, detail, it) } }
        }

    /** The nodes of one cell: the text pressed, the row the press goes to, and what lies over the text's center. */
    private class Screen(
        cell: Cell,
        val clickableRow: Boolean = true,
    ) {
        val host = FakeHost()
        val label =
            node("android.widget.TextView", Rect(200, 450, 800, 550)) {
                text = "Details"
                viewIdResourceName = "$APP:id/label"
            }
        val row =
            node("android.widget.LinearLayout", Rect(100, 420, 900, 580)) {
                isClickable = clickableRow
                isLongClickable = clickableRow
                viewIdResourceName = "$APP:id/row"
            }.with(label)
        val delete = button("Delete", Rect(100, 420, 900, 580))
        val allow = button("Allow", Rect(100, 420, 900, 580), pkg = "com.other")
        private val layout = cell.layout

        /** The screen planned on. */
        fun showPlanned() {
            if (layout == Layout.SIBLING) host.show(row, delete) else host.show(row)
        }

        /** The screen the press is made on. */
        fun showPressed() {
            if (layout == Layout.NEW_WINDOW) host.show(row, extra = listOf(window(allow, id = 2, layer = 10)))
        }

        /** Every action on the nodes other than the row's. */
        fun othersTouched(): Boolean = listOf(label, delete, allow).any { it.log.actions.isNotEmpty() }
    }

    /** Plans a press on the text "Details" of [screen] at [cell]'s detail, then makes it on the screen pressed. */
    private suspend fun TestScope.press(
        cell: Cell,
        screen: Screen,
        system: TestSystem = TestSystem(),
    ): ActionOutcome {
        screen.showPlanned()
        val port = mainPort(screen.host, system)
        val planned = port.snapshot(SnapshotOptions(detail = cell.detail))
        val text = planned.elements.single { it.resourceId == "label" }
        screen.showPressed()
        return port.act(UiAction.Tap(Target.Ref(text.ref), cell.long), planned.id)
    }

    @Test
    fun aPressRefusedAtOnceWithNothingChangingIsNotMadeUpForWithATouch() =
        runTest {
            val failures =
                cells.flatMap { cell ->
                    val screen = Screen(cell)
                    screen.row.log.answer = { _, _ -> false }
                    val outcome = press(cell, screen)
                    val what = if (cell.long) "long press" else "click"
                    val refused =
                        ActionOutcome.Status.Failed(
                            "the app refused the $what and nothing changed; it was not made up for with a touch. " +
                                "If it still needs pressing, tap its coordinates instead: that tap is checked for " +
                                "what lies at that point",
                        )
                    listOfNotNull(
                        "not done, got ${outcome.status}".takeUnless { outcome.status == refused },
                        "the effect unknown".takeUnless { outcome.effect == ActionOutcome.Effect.UNKNOWN },
                        "one node action on the row, got ${screen.row.log.ids}".takeUnless {
                            screen.row.log.ids == listOf(cell.action)
                        },
                        "no gesture, got ${screen.host.gestures}".takeUnless { screen.host.gestures.isEmpty() },
                        "nothing else touched".takeUnless { !screen.othersTouched() },
                    ).map { "$cell: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun aPressTheAppTookOrThatChangedTheScreenIsDoneWithoutATouch() =
        runTest {
            val failures =
                cells.flatMap { cell ->
                    val took = Screen(cell)
                    val tookOutcome = press(cell, took)
                    val changed = Screen(cell)
                    changed.row.log.answer = { _, _ ->
                        changed.host.flow.tryEmit(
                            A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, APP, null, 1),
                        )
                        false
                    }
                    val changedOutcome = press(cell, changed)
                    listOf(
                        "answered true" to (took to tookOutcome),
                        "answered false, then a change" to (changed to changedOutcome),
                    ).flatMap { (how, seen) ->
                        val (screen, outcome) = seen
                        listOfNotNull(
                            "Ok, got ${outcome.status}".takeUnless { outcome.status == ActionOutcome.Status.Ok },
                            "one node action on the row, got ${screen.row.log.ids}".takeUnless {
                                screen.row.log.ids == listOf(cell.action)
                            },
                            "no gesture".takeUnless { screen.host.gestures.isEmpty() },
                            "nothing else touched".takeUnless { !screen.othersTouched() },
                        ).map { "$cell $how: $it" }
                    }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun aPressRefusedOnlyAfterThePlatformTimeoutSaysItMayHaveHappened() =
        runTest {
            val failures =
                cells.flatMap { cell ->
                    val screen = Screen(cell)
                    val system = TestSystem()
                    screen.row.log.answer = { _, _ ->
                        system.now += ScreenTimings().clickUncertainMs
                        false
                    }
                    val outcome = press(cell, screen, system)
                    val status = outcome.status as? ActionOutcome.Status.Failed
                    listOfNotNull(
                        "may have happened, got ${outcome.status}".takeUnless {
                            status?.reason?.contains("so it may have happened; it was not repeated") == true
                        },
                        "one node action on the row".takeUnless { screen.row.log.ids == listOf(cell.action) },
                        "no gesture".takeUnless { screen.host.gestures.isEmpty() },
                    ).map { "$cell: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun withNothingToTakeThePressOnEitherScreenItIsStillATouchAtTheCenter() =
        runTest {
            // The known gap of phase1 §3.3 (M2): the center is not checked; unchanged here.
            val failures =
                cells.flatMap { cell ->
                    val screen = Screen(cell, clickableRow = false)
                    val outcome = press(cell, screen)
                    val gesture = screen.host.gestures.singleOrNull()
                    listOfNotNull(
                        "Ok, got ${outcome.status}".takeUnless { outcome.status == ActionOutcome.Status.Ok },
                        "no node action".takeUnless {
                            screen.row.log.actions
                                .isEmpty() && !screen.othersTouched()
                        },
                        "one touch at the center, got ${screen.host.gestures}".takeUnless {
                            gesture?.second?.single()?.points == listOf(PixelPoint(500f, 500f))
                        },
                    ).map { "$cell: $it" }
                }

            assertEquals(emptyList<String>(), failures)
        }
}
