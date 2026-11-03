package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

private const val UNREADABLE = "$APP:id/unreadable"

/**
 * `getChild()` answers `null` for children whose view id is [UNREADABLE], as it does for a child
 * the app could not hand over (the platform returns `null` when the node cannot be obtained).
 */
@Implements(AccessibilityNodeInfo::class)
class UnreadableChildShadow : ShadowAccessibilityNodeInfo() {
    @Implementation
    override fun getChild(index: Int): AccessibilityNodeInfo? =
        super.getChild(index)?.takeUnless { it.viewIdResourceName == UNREADABLE }
}

/**
 * A child the tree has but that could not be read makes what it belongs to incomplete, as a cut
 * by the read's budgets does (fix list r4 B2): outside lists the window, in a row that row, and
 * beside it in a list the rows of the children before and after it. The negatives assert that
 * nothing on the device was touched.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [UnreadableChildShadow::class])
class UnreadableChildTest {
    private val host = FakeHost()

    /** Every node the screens of a test were made of, to check that none was acted on. */
    private val made = ArrayList<AccessibilityNodeInfo>()

    private fun make(
        cls: String,
        bounds: Rect,
        setup: AccessibilityNodeInfo.() -> Unit = {},
    ): AccessibilityNodeInfo = node(cls, bounds, setup = setup).also { made += it }

    /** A text of [label]; one that cannot be read when [unreadable]. */
    private fun text(
        label: String,
        bounds: Rect,
        unreadable: Boolean = false,
    ): AccessibilityNodeInfo =
        make("android.widget.TextView", bounds) {
            text = label
            if (unreadable) viewIdResourceName = UNREADABLE
        }

    private fun press(
        label: String,
        bounds: Rect,
        id: String,
    ): AccessibilityNodeInfo =
        make("android.widget.Button", bounds) {
            text = label
            isClickable = true
            viewIdResourceName = "$APP:id/$id"
        }

    private fun assertNothingTouched() {
        assertTrue("no gesture", host.gestures.isEmpty())
        assertTrue("no node action", made.all { it.log.actions.isEmpty() })
    }

    private fun ScreenSnapshot.refs() = elements.map { it.ref }.toSet()

    private fun ScreenSnapshot.buttons(name: String) =
        elements.filter { it.name == name && it.role == ElementRole.BUTTON }

    /** Codex r4 B2: a card outside lists whose [person] cannot be read. */
    private fun card(person: String): AccessibilityNodeInfo =
        make("android.widget.LinearLayout", Rect(0, 300, 1000, 800)).with(
            press("Like", Rect(0, 500, 400, 600), id = "like"),
            text(person, Rect(0, 400, 1000, 500), unreadable = true),
        )

    @Test
    fun outsideListsAChildThatCannotBeReadLeavesTheWindowUnreliable() =
        runTest {
            host.show(card("Bob"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(card("Ada"))
            val s2 = port.snapshot()

            val outcome = port.act(UiAction.Tap(Target.Ref(s1.buttons("Like").single().ref)), s1.id)

            assertStale(outcome.status)
            assertFalse("the Like on Ada's card is new", s2.buttons("Like").single().ref in s1.refs())
            assertNothingTouched()
        }

    /** A toolbar with Search, then a list with a row for each of [people]; [hidden]'s name cannot be read. */
    private fun inbox(
        people: List<String>,
        hidden: String,
    ): List<AccessibilityNodeInfo> {
        val list =
            make("androidx.recyclerview.widget.RecyclerView", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
                isScrollable = true
                collectionInfo = AccessibilityNodeInfo.CollectionInfo(people.size, 1, false)
            }
        people.forEachIndexed { index, person ->
            val y = LIST_TOP + index * ROW_PX
            list.with(
                make("android.widget.LinearLayout", Rect(0, y, 1000, y + ROW_PX)).with(
                    text(person, Rect(0, y, 600, y + ROW_PX), unreadable = person == hidden),
                    press("Like", Rect(700, y, 1000, y + ROW_PX), id = "like"),
                ),
            )
        }
        val toolbar =
            make("android.widget.LinearLayout", Rect(0, 60, 1000, LIST_TOP))
                .with(press("Search", Rect(700, 60, 1000, LIST_TOP), id = "search"))
        return listOf(toolbar, list)
    }

    @Test
    fun aChildOfARowThatCannotBeReadLeavesOnlyThatRowUnreliable() =
        runTest {
            host.show(*inbox(listOf("Bob", "Cy"), hidden = "Bob").toTypedArray())
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(*inbox(listOf("Ada", "Cy"), hidden = "Ada").toTypedArray())
            val s2 = port.snapshot()
            val (bobs, cys) = s1.buttons("Like")

            val outcome = port.act(UiAction.Tap(Target.Ref(bobs.ref)), s1.id)

            assertStale(outcome.status)
            assertNothingTouched()
            // The other row, read in full, and the toolbar outside the list keep their refs.
            assertEquals("Cy's Like", cys.ref, s2.buttons("Like")[1].ref)
            assertEquals("the toolbar's Search", s1.buttons("Search").single().ref, s2.buttons("Search").single().ref)
            assertEquals(ActionOutcome.Status.Ok, port.act(UiAction.Tap(Target.Ref(cys.ref)), s1.id).status)
        }

    /** A list whose rows have no node of their own: each of [products], then its Add; [hidden] cannot be read. */
    private fun flat(
        products: List<String>,
        hidden: String,
    ): AccessibilityNodeInfo {
        val list =
            make("android.view.View", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
                isScrollable = true
                collectionInfo = AccessibilityNodeInfo.CollectionInfo(products.size, 1, false)
            }
        products.forEachIndexed { index, product ->
            val y = LIST_TOP + index * ROW_PX
            list.with(
                text(product, Rect(0, y, 600, y + ROW_PX), unreadable = product == hidden),
                press("Add", Rect(700, y, 1000, y + ROW_PX), id = "add"),
            )
        }
        return list
    }

    @Test
    fun aChildOfAFlatListThatCannotBeReadLeavesTheRowsBesideItUnreliable() =
        runTest {
            host.show(flat(listOf("Kettle", "Toaster", "Mixer"), hidden = "Toaster"))

            val read = ScreenReader.read(host.windowsOn(0), GEOMETRY, OWN)

            val adds = read.elements.filter { it.element.name == "Add" }.map { it.identity.reliable }
            assertEquals("Kettle's Add before the gap, the Add after it, Mixer's", listOf(false, false, true), adds)
            assertTrue(
                "the texts read in full",
                read.elements
                    .single { it.element.name == "Mixer" }
                    .identity.reliable,
            )
        }

    @Test
    fun aFlatRowWhoseTextCannotBeReadIsNotTakenForAnother() =
        runTest {
            host.show(flat(listOf("Kettle", "Toaster"), hidden = "Kettle"))
            val port = mainPort(host)
            val s1 = port.snapshot()
            host.show(flat(listOf("Lamp", "Toaster"), hidden = "Lamp"))
            val s2 = port.snapshot()
            val (kettles, toasters) = s1.buttons("Add")

            val outcome = port.act(UiAction.Tap(Target.Ref(kettles.ref)), s1.id)

            assertStale(outcome.status)
            assertNothingTouched()
            assertEquals("Toaster's Add, away from the gap", toasters.ref, s2.buttons("Add")[1].ref)
        }

    private companion object {
        const val LIST_TOP = Feed.LIST_TOP
        const val LIST_BOTTOM = Feed.LIST_BOTTOM
    }
}
