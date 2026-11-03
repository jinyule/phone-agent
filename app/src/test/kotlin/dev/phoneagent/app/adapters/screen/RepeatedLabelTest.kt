package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A control whose nodes repeat its own label (a button "Like" holding a text "Like", as Compose
 * and many View buttons report it) is told apart by that label once (fix list r4 B1): the
 * repeat is no more of what tells rows apart. Read by the real walker; the negatives assert that
 * nothing on the device was touched.
 */
@RunWith(AndroidJUnit4::class)
class RepeatedLabelTest {
    private val host = FakeHost()

    /** Every node the screens of a test were made of, to check that none was acted on. */
    private val made = ArrayList<AccessibilityNodeInfo>()

    private fun make(
        cls: String,
        bounds: Rect,
        setup: AccessibilityNodeInfo.() -> Unit = {},
    ): AccessibilityNodeInfo = node(cls, bounds, setup = setup).also { made += it }

    private fun text(
        label: String,
        bounds: Rect,
    ): AccessibilityNodeInfo = make("android.widget.TextView", bounds) { text = label }

    /** A button labelled [label] that holds a text of the same [label]. */
    private fun repeating(
        label: String,
        bounds: Rect,
    ): AccessibilityNodeInfo =
        make("android.widget.Button", bounds) {
            text = label
            isClickable = true
            viewIdResourceName = "$APP:id/${label.lowercase()}"
        }.with(text(label, bounds))

    private fun assertNothingTouched() {
        assertTrue("no gesture", host.gestures.isEmpty())
        assertTrue("no node action", made.all { it.log.actions.isEmpty() })
    }

    private fun ScreenSnapshot.refs() = elements.map { it.ref }.toSet()

    private fun ScreenSnapshot.likes() = elements.filter { it.name == "Like" && it.role == ElementRole.BUTTON }

    /**
     * A lazy list that can scroll both ways, its top row cut by the list's edge down to a Like that
     * repeats its label; [rest] are that row's other nodes.
     */
    private fun cutRow(vararg rest: AccessibilityNodeInfo): AccessibilityNodeInfo =
        make("android.view.View", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
            isScrollable = true
            collectionInfo = AccessibilityNodeInfo.CollectionInfo(-1, 1, false)
            addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD)
            addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
        }.with(
            make("android.view.View", Rect(0, LIST_TOP, 1000, LIST_TOP + 60)).with(
                *rest,
                repeating("Like", Rect(700, LIST_TOP, 1000, LIST_TOP + 60)),
            ),
        )

    @Test
    fun aRowCutDownToAControlThatRepeatsItsLabelIsStale() =
        runTest {
            // Codex r4 B1: two screens with the same residue; nothing but the Like tells the row.
            host.show(cutRow())
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(cutRow())
            val s2 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.likes().first().ref)), s1.id)

            assertStale(outcome.status)
            assertFalse("the residue on the next screen is new", s2.likes().first().ref in s1.refs())
            assertNothingTouched()
        }

    @Test
    fun aCutRowThatAlsoHoldsOtherTextKeepsItsRef() =
        runTest {
            // The control: text besides the Like's own tells the row apart.
            host.show(cutRow(text("Bob", Rect(0, LIST_TOP, 600, LIST_TOP + 60))))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(cutRow(text("Bob", Rect(0, LIST_TOP, 600, LIST_TOP + 60))))
            val s2 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.likes().first().ref)), s1.id)

            assertEquals(s1.likes().first().ref, s2.likes().first().ref)
            assertEquals(ActionOutcome.Status.Ok, outcome.status)
        }

    /** A list whose rows have no node of their own: [product], then an Add that repeats its label. */
    private fun flat(product: String): AccessibilityNodeInfo =
        make("android.view.View", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
            isScrollable = true
            collectionInfo = AccessibilityNodeInfo.CollectionInfo(1, 1, false)
        }.with(
            text(product, Rect(0, 300, 600, 400)),
            repeating("Add", Rect(700, 300, 1000, 400)),
        )

    @Test
    fun aControlThatRepeatsItsLabelIsTakenWithTheTextBeforeIt() =
        runTest {
            // Fix list r4 B1, rule 2: counted twice, the Add was a row of its own, told by "Add" only.
            host.show(flat("Kettle"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(flat("Lamp"))
            val s2 = port.snapshot()
            val add = { snapshot: ScreenSnapshot ->
                snapshot.elements.first { it.name == "Add" && it.role == ElementRole.BUTTON }
            }

            val outcome = port.act(UiAction.Tap(Target.Ref(add(s1).ref)), s1.id)

            assertStale(outcome.status)
            assertFalse("the Lamp's Add is new", add(s2).ref in s1.refs())
            assertNothingTouched()
        }

    /** A card outside lists: [person]'s name, then a Like that repeats its label. */
    private fun card(person: String): AccessibilityNodeInfo =
        make("android.widget.LinearLayout", Rect(0, 300, 1000, 600)).with(
            text(person, Rect(0, 300, 1000, 400)),
            repeating("Like", Rect(0, 500, 400, 600)),
        )

    @Test
    fun outsideListsAControlThatRepeatsItsLabelIsToldApartByItsWindow() =
        runTest {
            // Outside lists the window's labels tell elements apart, the repeat included.
            host.show(card("Bob"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(card("Ada"))
            val s2 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.likes().single().ref)), s1.id)

            assertStale(outcome.status)
            assertFalse("Ada's Like is new", s2.likes().single().ref in s1.refs())
            assertNothingTouched()
        }

    private companion object {
        const val LIST_TOP = Feed.LIST_TOP
        const val LIST_BOTTOM = Feed.LIST_BOTTOM
    }
}
