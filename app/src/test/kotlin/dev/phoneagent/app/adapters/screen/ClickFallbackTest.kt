package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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

    @Test
    fun aWindowOpenedByAClickThatAnsweredFalseCountsAsTheClick() =
        runTest {
            // The click opened a chooser in a new window of another package: no second tap.
            val share = button("Share", bounds)
            host.show(share)
            share.log.answer = { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    host.flow.tryEmit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "android", null, 7))
                }
                false
            }
            val port = mainPort(host)
            val s1 = port.snapshot()

            port.act(UiAction.Tap(Target.Ref(s1.elements.single().ref)), s1.id)

            assertTrue("no second tap", host.gestures.isEmpty())
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
