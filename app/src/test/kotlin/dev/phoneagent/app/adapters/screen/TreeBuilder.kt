package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.UiElement

/** What [TreeBuilder.add] takes beyond name, place and role. */
internal class More(
    val resourceId: String? = null,
    val row: List<String>? = null,
    val value: String? = null,
)

/**
 * One read built by hand in [window], parents before children, with identities given directly:
 * for testing [RefCarrier] and [RefMatcher] without the reader. Needs Robolectric (each element
 * has a node).
 */
internal class TreeBuilder(
    private val window: Int = 1,
) {
    val elements = ArrayList<ReadElement>()

    /** Adds an element under [parent] (an index returned before) and returns its index. */
    fun add(
        name: String?,
        top: Int,
        parent: Int? = null,
        role: ElementRole = if (parent == null) ElementRole.LIST else ElementRole.BUTTON,
        more: More = More(),
    ): Int {
        val index = elements.size
        val element =
            UiElement(
                ref = ElementRef("e${index + 1}"),
                role = role,
                name = name,
                bounds = Bounds(0, top, 999, top + 9),
                parent = parent?.let { ElementRef("e${it + 1}") },
                window = window,
                resourceId = more.resourceId,
                value = more.value,
            )
        elements +=
            ReadElement(
                element = element,
                target = NodeTarget(AccessibilityNodeInfo(), Rect(0, top, 1000, top + 10)),
                place = NodePlace(window, APP, parent, depth = parent?.let { elements[it].depth + 1 } ?: 0),
                brief = true,
                identity = ElementIdentity(window, role, more.resourceId, name, more.row),
            )
        return index
    }

    /** The read, for [RefMatcher]. */
    fun read(): TreeRead =
        TreeRead(
            elements.toList(),
            APP,
            keyboard = false,
            windows = WindowFacts(emptyMap(), emptyList(), top = window, front = APP, bars = emptySet()),
        )
}

/** A read of one list "Chats" (index 0) with a row for each name at its top. */
internal fun chats(vararg rows: Pair<String, Int>): TreeBuilder =
    TreeBuilder().apply {
        val list = add("Chats", top = 0)
        rows.forEach { (name, top) -> add(name, top, parent = list) }
    }
