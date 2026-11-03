package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** ACTION_CLICK answered `false` is not proof that nothing happened (fix list r1 B4). */
@RunWith(AndroidJUnit4::class)
class ClickFallbackTest {
    private val host = FakeHost()
    private val bounds = Rect(0, 1000, 500, 1100)

    @Test
    fun aClickThatWorkedButAnsweredFalseIsNotRepeatedWithAGesture() =
        runTest {
            val like = button("Like", bounds)
            host.show(like)
            like.log.answer = { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    host.show(button("Liked", bounds))
                    host.flow.tryEmit(changedIn(window = 1))
                }
                false
            }
            val port = mainPort(host)
            val s1 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.elements.single().ref)), s1.id)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertEquals(ActionOutcome.Effect.CHANGED, outcome.effect)
            assertEquals(1, like.log.ids.count { it == AccessibilityNodeInfo.ACTION_CLICK })
            assertTrue("no second tap", host.gestures.isEmpty())
        }

    @Test
    fun aClickRefusedAtOnceWithoutAnyChangeFallsBackToOneGesture() =
        runTest {
            val like = button("Like", bounds)
            host.show(like)
            like.log.answer = { _, _ -> false }
            val port = mainPort(host)
            val s1 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.elements.single().ref)), s1.id)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertEquals(1, host.gestures.size)
        }

    /** A content change of window [window] (the fixtures' app window is 1). */
    private fun changedIn(
        window: Int,
        pkg: String = APP,
    ) = A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, pkg, null, window)

    @Test
    fun aChangeInAnotherWindowIsNoProofThatTheClickHappened() =
        runTest {
            // Fix list r2 S4 (a), Codex R2-S2: only another app's window changes meanwhile.
            val like = button("Like", bounds)
            host.show(like)
            like.log.answer = { action, _ ->
                if (action ==
                    AccessibilityNodeInfo.ACTION_CLICK
                ) {
                    host.flow.tryEmit(changedIn(window = 9, pkg = "com.other"))
                }
                false
            }
            val port = mainPort(host)
            val s1 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.elements.single().ref)), s1.id)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertEquals("the click is made up for with one gesture", 1, host.gestures.size)
        }

    /** A full-display window [id] of [pkg] at [layer] holding one text, of [type]. */
    private fun windowOf(
        pkg: String,
        id: Int,
        layer: Int,
        type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
    ): AccessibilityWindowInfo =
        window(
            node("android.widget.FrameLayout", GEOMETRY.pixels, pkg).with(
                node("android.widget.TextView", Rect(100, 900, 900, 1000), pkg) { text = "Choose" },
            ),
            id = id,
            layer = layer,
            type = type,
        )

    /**
     * Taps a "Share" button, which [show] puts on the display, whose click answers `false` after
     * [click] ran (which may change the windows and send events); returns how many gestures made
     * up for it.
     */
    private suspend fun TestScope.gesturesAfterAFalseClick(
        show: (share: AccessibilityNodeInfo) -> Unit = { host.show(it) },
        click: (shown: List<AccessibilityWindowInfo>) -> Unit,
    ): Int {
        val share = button("Share", bounds)
        show(share)
        share.log.answer = { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) click(host.windowsOn(0))
            false
        }
        val port = mainPort(host)
        val s1 = port.snapshot()
        port.act(UiAction.Tap(Target.Ref(s1.elements.single { it.name == "Share" }.ref)), s1.id)
        return host.gestures.size
    }

    @Test
    fun aDialogTheClickOpenedCountsAsTheClick() =
        runTest {
            // Fix list r3 S4: the app's own new window, which the windows now really hold.
            val gestures =
                gesturesAfterAFalseClick { shown ->
                    host.windows = mapOf(0 to shown + windowOf(APP, id = 7, layer = 2))
                    host.flow.tryEmit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, null, 7))
                }

            assertEquals("no second tap", 0, gestures)
        }

    @Test
    fun anotherAppTheClickBroughtToTheFrontCountsAsTheClick() =
        runTest {
            // Claude r3 MS4a: only the windows-changed event tells; the app's window is no longer in front.
            val gestures =
                gesturesAfterAFalseClick { shown ->
                    host.windows = mapOf(0 to shown + windowOf("com.other", id = 8, layer = 3))
                    host.flow.tryEmit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 8))
                }

            assertEquals("no second tap", 0, gestures)
        }

    @Test
    fun anUnrelatedWindowsChangeIsNoProofThatTheClickHappened() =
        runTest {
            // Fix list r3 S4 (Codex r3 S1): another package's window 9 changed; the windows did not.
            val gestures =
                gesturesAfterAFalseClick {
                    host.flow.tryEmit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOWS_CHANGED, "com.other", null, 9))
                }

            assertEquals("the click is made up for with one gesture", 1, gestures)
        }

    @Test
    fun anotherPackagesWindowOpeningBehindOrBesideTheAppIsNoProof() =
        runTest {
            // A system window of another package (a heads-up) appears; the app's window stays in front.
            val gestures =
                gesturesAfterAFalseClick { shown ->
                    val other = windowOf("com.other", id = 9, layer = 5, type = AccessibilityWindowInfo.TYPE_SYSTEM)
                    host.windows = mapOf(0 to shown + other)
                    host.flow.tryEmit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOWS_CHANGED, "com.other", null, 9))
                    host.flow.tryEmit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.other", null, 9))
                }

            assertEquals("the click is made up for with one gesture", 1, gestures)
        }

    @Test
    fun anotherPackagesStateChangeIsNoProofThatTheClickHappened() =
        runTest {
            // A toast of another app, in a window of its own.
            val gestures =
                gesturesAfterAFalseClick {
                    host.flow.tryEmit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.other", null, 9))
                }

            assertEquals("the click is made up for with one gesture", 1, gestures)
        }

    /** Shows [share] in the app's window 1 below another app's window 2 in front, on the top half (split screen). */
    private fun besideAnotherAppInFront(share: AccessibilityNodeInfo) {
        val other = windowOf("com.other", id = 2, layer = 2)
        shadowOf(other).setBoundsInScreen(Rect(0, 0, 1000, 900))
        host.show(share, extra = listOf(other))
    }

    @Test
    fun aDialogTheClickOpenedBelowAnotherAppInFrontCountsAsTheClick() =
        runTest {
            // Fix list r3 S4: the app's window was not in front; only the windows tell of its new dialog.
            val gestures =
                gesturesAfterAFalseClick(show = ::besideAnotherAppInFront) { shown ->
                    host.windows = mapOf(0 to shown + windowOf(APP, id = 7, layer = 3))
                    host.flow.tryEmit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 7))
                }

            assertEquals("no second tap", 0, gestures)
        }

    @Test
    fun aWindowNotInFrontBeforeTheClickIsNoProofForNotBeingInFrontAfter() =
        runTest {
            // Fix list r3 S4: the app in front changed something of its own; the windows are as they were.
            val gestures =
                gesturesAfterAFalseClick(show = ::besideAnotherAppInFront) {
                    host.flow.tryEmit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 2))
                }

            assertEquals("the click is made up for with one gesture", 1, gestures)
        }

    @Test
    fun aWindowOfUnknownPackageTakesNoWindowWithoutAPackageForItsOwn() =
        runTest {
            // Fix list r3 S4: neither the clicked window's package nor the other window's is known.
            val anonymous = { bounds: Rect -> node("android.widget.FrameLayout", bounds) { packageName = null } }
            val overlay =
                window(anonymous(Rect(0, 0, 1000, 200)), id = 9, layer = 5, type = AccessibilityWindowInfo.TYPE_SYSTEM)
            val showAnonymously = { share: AccessibilityNodeInfo ->
                host.windows = mapOf(0 to listOf(window(anonymous(GEOMETRY.pixels).with(share))))
            }
            val gestures =
                gesturesAfterAFalseClick(show = showAnonymously) { shown ->
                    host.windows = mapOf(0 to shown + overlay)
                    host.flow.tryEmit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 9))
                    host.flow.tryEmit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, null, null, 9))
                }

            assertEquals("the click is made up for with one gesture", 1, gestures)
        }

    @Test
    fun aClickThatAnsweredFalseIsReadBackOnlyOnceTheScreenSettled() =
        runTest {
            // Fix list r2 S4 (b), Claude r2 S2: "Loading" for 900 ms (an event every 100 ms), then "Page 2".
            val next = button("Next", bounds)
            host.show(next)
            next.log.answer = { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    host.show(node("android.widget.TextView", bounds) { text = "Loading" })
                    host.flow.tryEmit(changedIn(window = 1))
                    backgroundScope.launch {
                        repeat(8) {
                            delay(100)
                            host.flow.emit(changedIn(window = 1))
                        }
                        delay(100)
                        host.show(node("android.widget.TextView", bounds) { text = "Page 2" })
                        host.flow.emit(changedIn(window = 1))
                    }
                }
                false
            }
            val port = mainPort(host)
            val s1 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.elements.single().ref)), s1.id)

            assertEquals(listOf("Page 2"), outcome.after!!.elements.mapNotNull { it.name })
            assertTrue("no second tap", host.gestures.isEmpty())
        }

    @Test
    fun aClickAnsweredFalseOnlyAfterThePlatformTimeoutIsNotRepeated() =
        runTest {
            // Codex r1 B4: performAction blocks until the platform gives up, then answers false.
            val like = button("Like", bounds)
            host.show(like)
            val system = TestSystem()
            like.log.answer = { _, _ ->
                system.now += ScreenTimings().clickUncertainMs
                false
            }
            val port = mainPort(host, system)
            val s1 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.elements.single().ref)), s1.id)

            val status = outcome.status as ActionOutcome.Status.Failed
            assertTrue(status.reason, status.reason.contains("not repeated"))
            assertTrue("no second tap", host.gestures.isEmpty())
        }
}
