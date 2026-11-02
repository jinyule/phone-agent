package dev.phoneagent.app.adapters.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Direction
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

/**
 * Input focus for tests: Robolectric's `findFocus` needs a sealed node with a live connection, so
 * every node answers with [focused] instead.
 */
@Implements(AccessibilityNodeInfo::class)
class FocusShadow : ShadowAccessibilityNodeInfo() {
    @Implementation
    fun findFocus(
        @Suppress("UNUSED_PARAMETER") focus: Int,
    ): AccessibilityNodeInfo? = focused

    companion object {
        /** The node that holds input focus, or `null`. */
        var focused: AccessibilityNodeInfo? = null
    }
}

/** The own-UI guard also covers actions without a target (fix list r1 B3). */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [FocusShadow::class])
class OwnUiGuardTest {
    private val host = FakeHost()
    private val refused = ActionOutcome.Status.Refused("PhoneAgent does not operate its own screens")

    private val field =
        node("android.widget.EditText", Rect(0, 200, 1000, 300), pkg = OWN) {
            isEditable = true
            isFocused = true
            text = "stop the ta"
            addAction(AccessibilityAction.ACTION_IME_ENTER)
        }

    @After
    fun noFocus() {
        FocusShadow.focused = null
    }

    @Test
    fun keysAndScrollsOnItsOwnScreenAreRefusedWithoutTouchingIt() =
        runTest {
            FocusShadow.focused = field
            host.show(field, pkg = OWN)
            val port = mainPort(host)

            val outcomes =
                listOf(
                    UiAction.Key(SystemKey.BACK),
                    UiAction.Key(SystemKey.HOME),
                    UiAction.Key(SystemKey.ENTER),
                    UiAction.Scroll(null, Direction.DOWN),
                    UiAction.Type(null, "x"),
                ).associateWith { port.act(it, on = null).status }

            outcomes.forEach { (action, status) -> assertEquals("$action", refused, status) }
            assertTrue("no global action", host.globalActions.isEmpty())
            assertTrue("no gesture", host.gestures.isEmpty())
            assertTrue("no IME action, no text", field.log.actions.isEmpty())
        }

    @Test
    fun anotherAppsFocusedFieldStillTakesEnter() =
        runTest {
            val theirs =
                node("android.widget.EditText", Rect(0, 200, 1000, 300)) {
                    isEditable = true
                    isFocused = true
                    addAction(AccessibilityAction.ACTION_IME_ENTER)
                }
            FocusShadow.focused = theirs
            host.show(theirs)

            val outcome = mainPort(host).act(UiAction.Key(SystemKey.ENTER), on = null)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertEquals(listOf(AccessibilityAction.ACTION_IME_ENTER.id), theirs.log.ids)
        }

    @Test
    fun enterIntoItsOwnFieldIsRefusedEvenAboveAnotherApp() =
        runTest {
            // Another app fills the display; PhoneAgent's field has focus in a window above it.
            FocusShadow.focused = field
            val own =
                window(node("android.widget.FrameLayout", Rect(0, 0, 1000, 400), OWN).with(field), id = 2, layer = 5)
            host.show(button("Send", Rect(0, 1000, 500, 1100)), extra = listOf(own))

            val outcome = mainPort(host).act(UiAction.Key(SystemKey.ENTER), on = null)

            assertEquals(refused, outcome.status)
            assertTrue(field.log.actions.isEmpty())
        }

    /** The app's screen in window 1 and PhoneAgent's [own] window above it; [focused] holds focus. */
    private fun appUnderOwnWindow(focused: Int) {
        val app =
            window(
                node("android.widget.FrameLayout", GEOMETRY.pixels).with(button("Send", Rect(0, 1000, 500, 1100))),
                id = 1,
                layer = 1,
            )
        val own = window(node("android.widget.FrameLayout", Rect(0, 0, 1000, 400), OWN), id = 2, layer = 5)
        listOf(app, own).forEach { shadowOf(it).setFocused(it.id == focused) }
        host.windows = mapOf(0 to listOf(app, own))
    }

    @Test
    fun backActsOnTheFocusedWindowEvenUnderItsOwnWindow() =
        runTest {
            appUnderOwnWindow(focused = 1)

            val outcome = mainPort(host).act(UiAction.Key(SystemKey.BACK), on = null)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), host.globalActions)
        }

    @Test
    fun aScrollWithoutTargetIsRefusedWhileItsOwnWindowIsInFront() =
        runTest {
            // Fix list r2 S2 (Codex R2-B6): the app's list is still partly visible below PhoneAgent's window.
            val list =
                node("androidx.recyclerview.widget.RecyclerView", Rect(0, 0, 1000, 2000)) {
                    isScrollable = true
                    addAction(AccessibilityAction.ACTION_SCROLL_DOWN)
                }.with(button("Send", Rect(0, 200, 500, 300)))
            val app = window(node("android.widget.FrameLayout", GEOMETRY.pixels).with(list), id = 1, layer = 1)
            val own = window(node("android.widget.FrameLayout", Rect(0, 1000, 1000, 2000), OWN), id = 2, layer = 5)
            shadowOf(own).setBoundsInScreen(Rect(0, 1000, 1000, 2000))
            listOf(app, own).forEach { shadowOf(it).setFocused(it.id == 2) }
            host.windows = mapOf(0 to listOf(app, own))

            val outcome = mainPort(host).act(UiAction.Scroll(null, Direction.DOWN), on = null)

            assertEquals(refused, outcome.status)
            assertTrue("no node action", list.log.actions.isEmpty())
            assertTrue("no gesture", host.gestures.isEmpty())
        }

    @Test
    fun aTapOnItsOwnBarShapedSystemWindowIsRefused() =
        runTest {
            // Fix list r2 S6 (Claude r2 S3): a full-width strip at the bottom edge, of type
            // TYPE_SYSTEM like a navigation bar, but PhoneAgent's own (a future floating control).
            val strip = Rect(0, 1850, 1000, 2000)
            val own =
                window(
                    node(
                        "android.widget.FrameLayout",
                        strip,
                        OWN,
                    ).with(button("Stop task", Rect(800, 1850, 1000, 2000), OWN)),
                    id = 2,
                    layer = 5,
                    type = android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM,
                )
            shadowOf(own).setBoundsInScreen(strip)
            host.show(button("Send", Rect(0, 1000, 500, 1100)), extra = listOf(own))

            val outcome = mainPort(host).act(UiAction.Tap(Target.Point(950, 962)), on = null)

            assertEquals(refused, outcome.status)
            assertTrue("no gesture", host.gestures.isEmpty())
        }

    @Test
    fun backIsRefusedWhileItsOwnWindowHasFocus() =
        runTest {
            appUnderOwnWindow(focused = 2)

            val outcome = mainPort(host).act(UiAction.Key(SystemKey.HOME), on = null)

            assertEquals(refused, outcome.status)
            assertTrue(host.globalActions.isEmpty())
        }
}
