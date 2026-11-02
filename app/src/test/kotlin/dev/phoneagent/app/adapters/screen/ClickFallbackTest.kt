package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
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
                    host.flow.tryEmit(contentChanged())
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
