package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The status and navigation bars are not part of the screen (fix list r1 S2). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SystemBarsTest {
    private val host = FakeHost()

    private fun systemWindow(
        root: AccessibilityNodeInfo,
        id: Int,
        bounds: Rect,
    ): AccessibilityWindowInfo =
        window(root, id = id, layer = 10, type = AccessibilityWindowInfo.TYPE_SYSTEM).also {
            shadowOf(it).setBoundsInScreen(bounds)
        }

    private fun statusBar(clock: String): AccessibilityWindowInfo {
        val strip = Rect(0, 0, 1000, 60)
        val root =
            node("android.widget.FrameLayout", strip, SYSTEM_UI).with(
                node("android.widget.TextView", Rect(20, 10, 200, 50), SYSTEM_UI) { text = clock },
                node("android.widget.ImageView", Rect(900, 10, 980, 50), SYSTEM_UI) {
                    contentDescription = "Battery charging, 100 percent."
                },
            )
        return systemWindow(root, id = STATUS_BAR, bounds = strip)
    }

    private fun navigationBar(): AccessibilityWindowInfo {
        val strip = Rect(0, 1900, 1000, 2000)
        val root =
            node(
                "android.widget.FrameLayout",
                strip,
                SYSTEM_UI,
            ).with(button("Home", Rect(400, 1900, 600, 2000), SYSTEM_UI))
        return systemWindow(root, id = NAVIGATION_BAR, bounds = strip)
    }

    private fun showApp(clock: String) {
        host.show(button("OK", Rect(0, 1000, 500, 1100)), extra = listOf(statusBar(clock), navigationBar()))
    }

    @Test
    fun barsAreLeftOutAndATickOfTheClockChangesNothing() =
        runTest {
            showApp("10:08")
            val port = mainPort(host)
            val before = port.snapshot()
            showApp("10:09")

            val after = port.snapshot()

            assertEquals(listOf("OK"), after.elements.mapNotNull { it.name })
            assertEquals(before.fingerprint, after.fingerprint)
        }

    @Test
    fun aClockTickDuringAnActionIsNoChange() =
        runTest {
            showApp("10:08")
            val port = mainPort(host)
            val planned = port.snapshot()
            host.onGesture = { showApp("10:09") }

            val outcome = port.act(UiAction.Tap(Target.Point(800, 525)), planned.id)

            assertEquals(ActionOutcome.Effect.NO_CHANGE, outcome.effect)
        }

    @Test
    fun systemDialogsAndNotificationsAreKept() =
        runTest {
            val dialogBounds = Rect(100, 700, 900, 1300)
            val dialog =
                systemWindow(
                    node(
                        "android.widget.FrameLayout",
                        dialogBounds,
                        SYSTEM_UI,
                    ).with(button("Allow", Rect(500, 1200, 900, 1300), SYSTEM_UI)),
                    id = 60,
                    bounds = dialogBounds,
                )
            val headsUp = Rect(30, 40, 970, 240)
            val notification =
                systemWindow(
                    node(
                        "android.widget.FrameLayout",
                        headsUp,
                        SYSTEM_UI,
                    ) { contentDescription = "New message from Ada" },
                    id = 61,
                    bounds = headsUp,
                )
            host.show(button("OK", Rect(0, 1000, 500, 1100)), extra = listOf(statusBar("10:08"), dialog, notification))

            val names = mainPort(host).snapshot().elements.mapNotNull { it.name }

            assertTrue(names.toString(), names.containsAll(listOf("Allow", "New message from Ada", "OK")))
            assertTrue(names.none { it == "10:08" })
        }

    @Test
    fun theClockDoesNotWakeAWaitForChange() =
        runTest {
            showApp("10:08")
            val port = mainPort(host)
            val waiting = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { port.awaitChange(WAIT_MS) }
            runCurrent()

            host.flow.emit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, SYSTEM_UI, null, STATUS_BAR))
            advanceTimeBy(WAIT_MS + 1)

            assertEquals(false, waiting.getCompleted())
        }

    @Test
    fun theAppStillWakesAWaitForChange() =
        runTest {
            showApp("10:08")
            val port = mainPort(host)
            val waiting = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { port.awaitChange(WAIT_MS) }
            runCurrent()

            host.flow.emit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, SYSTEM_UI, null, STATUS_BAR))
            host.flow.emit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, APP, null, 1))
            advanceTimeBy(WAIT_MS + 1)

            assertEquals(true, waiting.getCompleted())
        }

    @Test
    fun barsAreThinSystemWindowsAlongAnEdge() {
        val bar = {
            bounds: Rect,
            type: Int,
            ->
            window(null, type = type).also { shadowOf(it).setBoundsInScreen(bounds) }
        }
        val system = AccessibilityWindowInfo.TYPE_SYSTEM

        assertTrue("status bar", ScreenReader.isSystemBar(bar(Rect(0, 0, 1000, 60), system), GEOMETRY))
        assertTrue("navigation bar", ScreenReader.isSystemBar(bar(Rect(0, 1900, 1000, 2000), system), GEOMETRY))
        assertTrue("side navigation bar", ScreenReader.isSystemBar(bar(Rect(950, 0, 1000, 2000), system), GEOMETRY))
        assertFalse(
            "an app's toolbar",
            ScreenReader.isSystemBar(bar(Rect(0, 0, 1000, 60), AccessibilityWindowInfo.TYPE_APPLICATION), GEOMETRY),
        )
        assertFalse("the notification shade", ScreenReader.isSystemBar(bar(Rect(0, 0, 1000, 2000), system), GEOMETRY))
        assertFalse("a heads-up notification", ScreenReader.isSystemBar(bar(Rect(30, 40, 970, 240), system), GEOMETRY))
        assertFalse(
            "a full-width banner off the edges",
            ScreenReader.isSystemBar(bar(Rect(0, 900, 1000, 1000), system), GEOMETRY),
        )
    }

    private companion object {
        const val WAIT_MS = 1_000L
        const val SYSTEM_UI = "com.android.systemui"
        const val STATUS_BAR = 50
        const val NAVIGATION_BAR = 51
    }
}
