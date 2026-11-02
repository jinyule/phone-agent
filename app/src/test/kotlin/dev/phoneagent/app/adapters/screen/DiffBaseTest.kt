package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** What an action's diff is against (fix list r1 B2). */
@RunWith(AndroidJUnit4::class)
class DiffBaseTest {
    private val host = FakeHost()

    @Test
    fun aForgottenBaseScreenGivesNoDiffRatherThanAnotherBase() =
        runTest {
            // Codex r1 B3 / Claude x2.
            host.show(button("Alice", Rect(0, 1000, 500, 1100)))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(button("Bob", Rect(0, 1000, 500, 1100)))
            repeat(ScreenTimings().snapshotsKept) { port.snapshot() }

            val outcome = port.act(UiAction.Tap(Target.Point(250, 525)), on = s1.id)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertNull(outcome.diff)
        }

    @Test
    fun withoutABaseScreenTheDiffIsAgainstTheLatestOne() =
        runTest {
            host.show(button("Alice", Rect(0, 1000, 500, 1100)))
            val port = mainPort(host)
            port.snapshot()
            host.onGesture =
                { host.show(button("Alice", Rect(0, 1000, 500, 1100)), button("Bob", Rect(0, 1200, 500, 1300))) }

            val outcome = port.act(UiAction.Tap(Target.Point(250, 525)), on = null)

            assertEquals(listOf("Bob"), outcome.diff?.added?.map { it.name })
        }
}
