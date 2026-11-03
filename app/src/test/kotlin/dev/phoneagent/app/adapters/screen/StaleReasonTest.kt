package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.StaleReason
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/**
 * Why an action's ref went stale (fix list r3 B1 "StaleRef 的原因"): the outcome carries the reason
 * to the model (fix list r4, rebase follow-up 1), and the log says the same with nothing of the
 * screen but the ref and the screen id.
 */
@RunWith(AndroidJUnit4::class)
class StaleReasonTest {
    private val host = FakeHost()
    private val feed = Feed()

    private fun ScreenSnapshot.named(name: String) = elements.single { it.name == name }

    /** Taps [ref] of [planned], which must go stale; the reason, the same in the outcome and the log. */
    private suspend fun TestScope.staleTap(
        port: AndroidScreen,
        planned: ScreenSnapshot,
        ref: String,
    ): StaleReason {
        ShadowLog.clear()
        val outcome = port.act(UiAction.Tap(Target.Ref(ElementRef(ref))), planned.id)
        val reason = (outcome.status as ActionOutcome.Status.StaleRef).reason
        assertEquals(ActionOutcome.Effect.UNKNOWN, outcome.effect)
        assertTrue("nothing touched", host.gestures.isEmpty())
        val logged = ShadowLog.getLogsForTag(AndroidScreen.TAG).single().msg
        assertEquals("@$ref planned on ${planned.id} is stale: $reason", logged)
        return reason
    }

    private fun inbox(
        title: String,
        people: List<String>,
        top: Int = 300,
    ) = host.show(node("android.widget.TextView", Rect(0, 60, 1000, 200)) { text = title }, feed.list(people, top))

    @Test
    fun aScreenNoLongerRememberedIsForgotten() =
        runTest {
            inbox("Inbox", listOf("Ada"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            repeat(ScreenTimings().snapshotsKept) { port.snapshot() }

            assertEquals(StaleReason.FORGOTTEN, staleTap(port, s1, s1.likeOf("Ada").ref.value))
        }

    @Test
    fun aChangedLabelOutsideListsIsAChangedWindow() =
        runTest {
            inbox("Inbox", listOf("Ada"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            inbox("Sent", listOf("Ada"))

            assertEquals("outside lists", StaleReason.WINDOW_CHANGED, staleTap(port, s1, s1.named("Inbox").ref.value))
            assertEquals(
                "a row under the list",
                StaleReason.WINDOW_CHANGED,
                staleTap(port, s1, s1.likeOf("Ada").ref.value),
            )
        }

    @Test
    fun aRowReplacedIsAChangedRowAndOneThatMovedIsMoved() =
        runTest {
            inbox("Inbox", listOf("Ada", "Bob"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            inbox("Inbox", listOf("Cy", "Ada"))

            assertEquals(StaleReason.ROW_CHANGED, staleTap(port, s1, s1.likeOf("Bob").ref.value))
            assertEquals(StaleReason.MOVED, staleTap(port, s1, s1.likeOf("Ada").ref.value))
        }

    @Test
    fun rowsAlikeThatAllMovedAreAmbiguous() =
        runTest {
            inbox("Inbox", listOf("Ada", "Ada"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            inbox("Inbox", listOf("Ada", "Ada"), top = 350)
            val first = s1.elements.first { it.name == "Like" }

            assertEquals(StaleReason.AMBIGUOUS, staleTap(port, s1, first.ref.value))
        }

    @Test
    fun anElementGoneFromAWindowWhoseLabelsAreTheSameIsNotFound() =
        runTest {
            val compose = { node("android.widget.ImageButton", Rect(800, 1700, 1000, 1900)) { isClickable = true } }
            val title = { node("android.widget.TextView", Rect(0, 60, 1000, 200)) { text = "Mail" } }
            host.show(title(), compose())
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(title())
            val button = s1.elements.single { it.role == ElementRole.BUTTON }

            assertEquals(StaleReason.NOT_FOUND, staleTap(port, s1, button.ref.value))
        }

    /** A card whose title comes after a chain deeper than the read goes, when [cut]. */
    private fun card(cut: Boolean): AccessibilityNodeInfo {
        val card =
            node(
                "android.widget.LinearLayout",
                Rect(0, 300, 1000, 800),
            ).with(button("Like", Rect(0, 500, 400, 600)))
        val end =
            if (cut) {
                (1..ScreenReader.MAX_DEPTH + 2).fold(card) { parent, _ ->
                    node("android.widget.LinearLayout", Rect(0, 400, 1000, 500)) { isVisibleToUser = false }
                        .also { parent.with(it) }
                }
            } else {
                card
            }
        end.with(node("android.widget.TextView", Rect(0, 400, 1000, 500)) { text = "Bob" })
        return card
    }

    @Test
    fun aScreenReadOnlyInPartIsUnreliableOnEitherSide() =
        runTest {
            host.show(card(cut = true))
            val port = mainPort(host)
            val cut = port.snapshot()
            host.show(card(cut = false))
            val whole = port.snapshot()

            assertEquals("planned on it", StaleReason.UNRELIABLE, staleTap(port, cut, cut.named("Like").ref.value))
            host.show(card(cut = true))
            assertEquals("read now", StaleReason.UNRELIABLE, staleTap(port, whole, whole.named("Like").ref.value))
        }

    @Test
    fun aRowCutDownToItsControlsIsUnreliable() =
        runTest {
            val list = {
                node("android.view.View", Rect(0, 200, 1000, 1800)) {
                    isScrollable = true
                    collectionInfo = AccessibilityNodeInfo.CollectionInfo(-1, 1, false)
                    addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }.with(
                    node("android.view.View", Rect(0, 200, 1000, 260)).with(button("Like", Rect(700, 200, 1000, 260))),
                )
            }
            host.show(list())
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(list())

            assertEquals(StaleReason.UNRELIABLE, staleTap(port, s1, s1.named("Like").ref.value))
        }
}
