package dev.phoneagent.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.adapters.screen.A11yEvent
import dev.phoneagent.app.adapters.screen.FakeHost
import dev.phoneagent.app.adapters.screen.PixelPoint
import dev.phoneagent.app.adapters.screen.Screenshot
import dev.phoneagent.app.adapters.screen.Stroke
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

@RunWith(AndroidJUnit4::class)
class AgentAccessibilityTest {
    @After
    fun reset() {
        AgentAccessibility.host.value?.let(AgentAccessibility::detach)
    }

    @Test
    fun aLateDetachOfAnOldConnectionKeepsTheNewOne() {
        val old = FakeHost()
        val new = FakeHost()

        AgentAccessibility.attach(old)
        AgentAccessibility.attach(new)
        AgentAccessibility.detach(old)
        assertSame(new, AgentAccessibility.host.value)

        AgentAccessibility.detach(new)
        assertNull(AgentAccessibility.host.value)
    }
}

@RunWith(AndroidJUnit4::class)
class ServiceScreenHostTest {
    private val service = Robolectric.buildService(AgentAccessibilityService::class.java).create().get()
    private val host = ServiceScreenHost(service)

    @After
    fun close() {
        host.close()
    }

    private fun event(
        type: Int,
        displayId: Int,
        className: String? = "com.example.app.Main",
    ): AccessibilityEvent {
        val event = AccessibilityEvent(type)
        event.packageName = "com.example.app"
        event.className = className
        ReflectionHelpers.callInstanceMethod<Unit>(
            event,
            "setDisplayId",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, displayId),
        )
        return event
    }

    @Test
    fun readsTheWindowsOfTheRequestedDisplayOnly() {
        val window = AccessibilityWindowInfo()
        shadowOf(service).setWindowsOnDisplay(3, listOf(window))

        assertEquals(listOf(window), host.windowsOn(3))
        assertTrue(host.windowsOn(0).isEmpty())
        assertNull(host.displaySize(42))
    }

    @Test
    fun republishesEventsWithTheirDisplayAndTracksWindowStates() =
        runTest {
            val next = async(start = CoroutineStart.UNDISPATCHED) { host.events.first() }

            host.onEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, displayId = 2))
            host.onEvent(
                event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, displayId = 2, className = "android.app.Dialog"),
            )

            assertEquals(
                A11yEvent(2, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.app", "com.example.app.Main"),
                next.await(),
            )
            assertEquals(
                listOf("android.app.Dialog", "com.example.app.Main"),
                host.recentWindowStates(2).map { it.className },
            )
            assertTrue(host.recentWindowStates(0).isEmpty())
        }

    @Test
    fun keepsOnlyTheLatestWindowStates() {
        repeat(
            20,
        ) { host.onEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, displayId = 0, className = "c$it")) }

        assertEquals((19 downTo 12).map { "c$it" }, host.recentWindowStates(0).map { it.className })
    }

    @Test
    fun gesturesTargetTheirDisplayAndReportCompletion() =
        runTest {
            val stroke = Stroke(listOf(PixelPoint(10f, 20f), PixelPoint(30f, 40f)), startMs = 0, durationMs = 100)
            val result = async(start = CoroutineStart.UNDISPATCHED) { host.dispatchStrokes(4, listOf(stroke)) }

            val dispatch = shadowOf(service).gesturesDispatched.single()
            assertEquals(4, dispatch.description().displayId)
            val described = dispatch.description().getStroke(0)
            assertEquals(100, described.duration)
            assertTrue(described.path.let { !it.isEmpty && it !== Path() })
            dispatch.callback().onCompleted(dispatch.description())
            assertEquals(true, result.await())
        }

    @Test
    fun aRejectedOrCancelledGestureIsReported() =
        runTest {
            val stroke = Stroke(listOf(PixelPoint(10f, 20f)), startMs = 0, durationMs = 50)
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { host.dispatchStrokes(0, listOf(stroke)) }
            val dispatch = shadowOf(service).gesturesDispatched.single()
            dispatch.callback().onCancelled(dispatch.description())
            assertEquals(false, cancelled.await())

            shadowOf(service).setCanDispatchGestures(false)
            assertEquals(false, host.dispatchStrokes(0, listOf(stroke)))
        }

    @Test
    fun globalActionsGoToTheService() {
        host.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), shadowOf(service).globalActionsPerformed)
    }

    @Test
    fun screenshotFailuresAreMapped() {
        shadowOf(service).setTakeScreenshotErrorCode(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
        assertEquals(Screenshot.Secure, runBlocking { host.screenshot(0) })

        shadowOf(service).setTakeScreenshotErrorCode(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY)
        assertEquals(
            Screenshot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY),
            runBlocking { host.screenshot(7) },
        )
    }

    @Test
    fun aClosedHostIsSilent() =
        runTest {
            host.close()
            host.onEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, displayId = 0))

            assertTrue(host.recentWindowStates(0).isEmpty())
            assertEquals(
                Screenshot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR),
                host.screenshot(0),
            )
        }
}
