package dev.phoneagent.app.adapters.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.Direction
import dev.phoneagent.core.ports.SystemKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActuatorTest {
    private val host = FakeHost()
    private val timings = ScreenTimings()

    private class RecordingInjector(
        private val accept: Boolean,
    ) : InputInjector {
        val strokes = ArrayList<Pair<Int, Stroke>>()
        val keys = ArrayList<Pair<Int, SystemKey>>()

        override suspend fun stroke(
            displayId: Int,
            stroke: Stroke,
        ): Boolean {
            strokes += displayId to stroke
            return accept
        }

        override suspend fun key(
            displayId: Int,
            key: SystemKey,
        ): Boolean {
            keys += displayId to key
            return accept
        }
    }

    private fun actuator(
        displayId: Int = 0,
        injector: InputInjector? = null,
        ownAt: (PixelPoint) -> Boolean = { false },
    ) = Actuator(host, displayId, GEOMETRY, timings, injector, ownAt)

    /** A display that never changes, on a clock that stands still. */
    private val unchanged =
        object : ClickProbe {
            override fun nowMs(): Long = 0

            override suspend fun changed(): Boolean = false
        }

    private fun target(node: AccessibilityNodeInfo): NodeTarget = NodeTarget(node, Rect().also(node::getBoundsInScreen))

    @Test
    fun tapsThroughTheClickableAncestor() =
        runTest {
            val label = node("android.widget.TextView", Rect(100, 400, 500, 450)) { text = "Wi-Fi" }
            val row = node("android.widget.LinearLayout", Rect(0, 400, 1000, 500)) { isClickable = true }
            row.with(label)
            val labelInTree = row.getChild(0)

            val result = actuator().tap(target(labelInTree), long = false, unchanged)

            assertEquals(Actuator.Result.Done(Actuator.Via.NODE), result)
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), row.log.ids)
            assertTrue(host.gestures.isEmpty())
        }

    @Test
    fun anAncestorThatDoesNotCoverTheCenterIsNotUsed() =
        runTest {
            // A clickable card whose bounds do not contain the label: tapping the card would hit elsewhere.
            val label = node("android.widget.TextView", Rect(100, 900, 500, 950)) { text = "Detail" }
            val card = node("android.widget.FrameLayout", Rect(0, 0, 1000, 500)) { isClickable = true }
            card.with(label)

            val result = actuator(displayId = 4).tap(target(card.getChild(0)), long = false, unchanged)

            assertEquals(Actuator.Result.Done(Actuator.Via.GESTURE), result)
            assertTrue(card.log.ids.isEmpty())
            val (display, strokes) = host.gestures.single()
            assertEquals(4, display)
            assertEquals(listOf(PixelPoint(300f, 925f)), strokes.single().points)
            assertEquals(timings.tapMs, strokes.single().durationMs)
        }

    @Test
    fun longPressUsesTheLongClickActionOrALongGesture() =
        runTest {
            val item = node("android.widget.TextView", Rect(0, 0, 100, 100)) { isLongClickable = true }
            val plain = node("android.widget.TextView", Rect(0, 100, 100, 200)) { isClickable = true }

            assertEquals(Actuator.Result.Done(Actuator.Via.NODE), actuator().tap(target(item), long = true, unchanged))
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_LONG_CLICK), item.log.ids)

            assertEquals(
                Actuator.Result.Done(Actuator.Via.GESTURE),
                actuator().tap(target(plain), long = true, unchanged),
            )
            assertTrue(plain.log.ids.isEmpty())
            assertEquals(
                timings.longPressMs,
                host.gestures
                    .single()
                    .second
                    .single()
                    .durationMs,
            )
        }

    @Test
    fun aRefusedClickFallsBackToAGesture() =
        runTest {
            val button = node("android.widget.Button", Rect(0, 0, 100, 100)) { isClickable = true }
            button.log.answer = { _, _ -> false }

            assertEquals(
                Actuator.Result.Done(Actuator.Via.GESTURE),
                actuator().tap(target(button), long = false, unchanged),
            )
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), button.log.ids)
            assertEquals(
                listOf(PixelPoint(50f, 50f)),
                host.gestures
                    .single()
                    .second
                    .single()
                    .points,
            )
        }

    @Test
    fun aRejectedGestureFallsBackToTheInjectorWhenThereIsOne() =
        runTest {
            host.acceptGestures = false
            val injector = RecordingInjector(accept = true)

            assertEquals(
                Actuator.Result.Done(Actuator.Via.INJECTED),
                actuator(displayId = 3, injector = injector).tapAt(PixelPoint(10f, 10f), long = false),
            )
            assertEquals(3, injector.strokes.single().first)
            assertEquals(
                Actuator.Result.NotDone("the tap gesture was not accepted by the system"),
                actuator().tapAt(PixelPoint(10f, 10f), long = false),
            )
        }

    @Test
    fun gesturePointsStayOnTheDisplay() =
        runTest {
            actuator().swipe(PixelPoint(-50f, 2500f), PixelPoint(1200f, 10f), durationMs = 200)

            val stroke =
                host.gestures
                    .single()
                    .second
                    .single()
            assertEquals(listOf(PixelPoint(0f, 1999f), PixelPoint(999f, 10f)), stroke.points)
            assertEquals(200, stroke.durationMs)
        }

    @Test
    fun scrollsWithTheDirectionalActionFirst() =
        runTest {
            val list =
                node("androidx.recyclerview.widget.RecyclerView", Rect(0, 200, 1000, 1800)) {
                    isScrollable = true
                    addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
                    addAction(AccessibilityAction.ACTION_SCROLL_DOWN)
                }

            assertEquals(Actuator.Result.Done(Actuator.Via.NODE), actuator().scroll(target(list), Direction.DOWN))
            assertEquals(listOf(AccessibilityAction.ACTION_SCROLL_DOWN.id), list.log.ids)
        }

    @Test
    fun genericScrollActionsFollowTheOrientation() {
        val vertical =
            node("android.widget.ScrollView", Rect(0, 0, 10, 10)) {
                addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
                addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD)
            }
        val pager =
            node("androidx.viewpager.widget.ViewPager", Rect(0, 0, 10, 10)) {
                addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
            }

        assertEquals(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, NodeActions.scrollActionFor(vertical, Direction.DOWN))
        assertEquals(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, NodeActions.scrollActionFor(vertical, Direction.UP))
        assertEquals(null, NodeActions.scrollActionFor(vertical, Direction.RIGHT))
        assertEquals(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, NodeActions.scrollActionFor(pager, Direction.RIGHT))
        assertEquals(null, NodeActions.scrollActionFor(pager, Direction.DOWN))
    }

    @Test
    fun withoutAScrollActionItSwipesInsideTheContainerAgainstTheDirection() =
        runTest {
            val list = node("android.view.View", Rect(0, 400, 1000, 1400)) { isScrollable = true }

            assertEquals(Actuator.Result.Done(Actuator.Via.GESTURE), actuator().scroll(target(list), Direction.DOWN))

            val stroke =
                host.gestures
                    .single()
                    .second
                    .single()
            // Revealing content below: the finger moves up, from 30% below the center to 30% above.
            assertEquals(listOf(PixelPoint(500f, 1200f), PixelPoint(500f, 600f)), stroke.points)
            assertEquals(timings.scrollSwipeMs, stroke.durationMs)
        }

    @Test
    fun aScrollWithoutContainerSwipesOutsideTheSystemBars() =
        runTest {
            actuator().scroll(null, Direction.UP)

            val (from, to) =
                host.gestures
                    .single()
                    .second
                    .single()
                    .points
            // Safe area: 5% from the top (y 100) to 6% from the bottom (y 1880).
            assertEquals(PixelPoint(500f, 990f - 534f), from)
            assertEquals(PixelPoint(500f, 990f + 534f), to)
        }

    @Test
    fun backAndHomeAreGlobalActionsOnlyOnTheMainDisplay() =
        runTest {
            assertEquals(Actuator.Result.Done(Actuator.Via.NODE), actuator().key(SystemKey.BACK, null))
            assertEquals(Actuator.Result.Done(Actuator.Via.NODE), actuator().key(SystemKey.HOME, null))
            assertEquals(
                listOf(AccessibilityService.GLOBAL_ACTION_BACK, AccessibilityService.GLOBAL_ACTION_HOME),
                host.globalActions,
            )

            host.globalActions.clear()
            assertEquals(
                Actuator.Result.NotDone("BACK on a background display needs the privileged helper"),
                actuator(displayId = 2).key(SystemKey.BACK, null),
            )
            assertTrue("a global BACK would hit the user's display", host.globalActions.isEmpty())

            val injector = RecordingInjector(accept = true)
            assertEquals(Actuator.Result.Done(Actuator.Via.INJECTED), actuator(2, injector).key(SystemKey.HOME, null))
            assertEquals(listOf(2 to SystemKey.HOME), injector.keys)
        }

    @Test
    fun aRefusedGlobalActionIsReported() =
        runTest {
            host.acceptGlobal = false

            assertEquals(
                Actuator.Result.NotDone("the system refused the BACK key"),
                actuator().key(SystemKey.BACK, null),
            )
        }

    @Test
    fun enterUsesTheImeActionOfTheField() =
        runTest {
            val withAction =
                node("android.widget.EditText", Rect(0, 0, 10, 10)) {
                    isEditable = true
                    addAction(AccessibilityAction.ACTION_IME_ENTER)
                }
            val without = node("android.widget.EditText", Rect(0, 0, 10, 10)) { isEditable = true }

            assertEquals(Actuator.Result.Done(Actuator.Via.NODE), actuator().key(SystemKey.ENTER, withAction))
            assertEquals(listOf(AccessibilityAction.ACTION_IME_ENTER.id), withAction.log.ids)
            assertEquals(Actuator.Result.NotDone::class, actuator().key(SystemKey.ENTER, without)::class)
            assertEquals(
                Actuator.Result.NotDone("no focused text field to press enter in"),
                actuator().key(SystemKey.ENTER, null),
            )
        }

    @Test
    fun enterFallsBackToTheInputConnectionBoundToTheField() =
        runTest {
            val field =
                node("android.widget.EditText", Rect(0, 0, 10, 10)) {
                    isEditable = true
                    isFocused = true
                }
            var actions = 0
            host.ime =
                object : ImeConnection {
                    override val editorPackage: String? = APP

                    override fun commit(
                        text: String,
                        replace: Boolean,
                    ) = false

                    override fun performEditorAction(): Boolean {
                        actions++
                        return true
                    }

                    override fun setSelection(
                        start: Int,
                        end: Int,
                    ) = false

                    override fun selection(): Pair<Int, Int>? = null

                    override fun cursorAtEnd(): Boolean? = null

                    override fun selectAll(): Boolean? = null
                }

            assertEquals(Actuator.Result.Done(Actuator.Via.NODE), actuator().enter(field))
            assertEquals(1, actions)
        }

    @Test
    fun noGestureOrInjectedStrokeStartsOnItsOwnUi() =
        runTest {
            val injector = RecordingInjector(accept = true)
            val actuator = actuator(injector = injector, ownAt = { it.x <= 500f })

            assertEquals(Actuator.Result.Refused, actuator.tapAt(PixelPoint(10f, 10f), long = false))
            assertEquals(Actuator.Result.Refused, actuator.swipe(PixelPoint(10f, 900f), PixelPoint(900f, 900f), 300))
            assertEquals(Actuator.Result.Refused, actuator.scroll(null, Direction.DOWN))
            assertTrue(host.gestures.isEmpty())
            assertTrue(injector.strokes.isEmpty())
            assertEquals(
                Actuator.Result.Done(Actuator.Via.GESTURE),
                actuator.tapAt(PixelPoint(900f, 900f), long = false),
            )
        }
}
