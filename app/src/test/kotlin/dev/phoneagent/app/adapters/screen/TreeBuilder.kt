package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.UiElement

/**
 * One read built by hand, parents before children, with identities given directly: for testing
 * [RefCarrier] and [RefMatcher] without the reader. Needs Robolectric (each element has a node).
 */
internal class TreeBuilder {
    val elements = ArrayList<ReadElement>()

    /** Adds an element under [parent] (an index returned before) and returns its index. */
    fun add(
        name: String?,
        top: Int,
        parent: Int? = null,
        role: ElementRole = if (parent == null) ElementRole.LIST else ElementRole.BUTTON,
        window: Int = 1,
        resourceId: String? = null,
        row: List<String>? = null,
        value: String? = null,
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
                resourceId = resourceId,
                value = value,
            )
        elements +=
            ReadElement(
                element = element,
                node = AccessibilityNodeInfo(),
                pixels = Rect(0, top, 1000, top + 10),
                windowPackage = APP,
                parent = parent,
                depth = parent?.let { elements[it].depth + 1 } ?: 0,
                brief = true,
                identity = ElementIdentity(window, role, resourceId, name, row),
            )
        return index
    }

    /** The read, for [RefMatcher]. */
    fun read(): TreeRead =
        TreeRead(
            elements.toList(),
            APP,
            topWindow = 1,
            keyboard = false,
            windowPackages = emptyMap(),
            windowBounds = emptyList(),
            frontPackage = APP,
            systemBars = emptySet(),
        )
}

/** A read of one list "Chats" (index 0) with a row for each name at its top. */
internal fun chats(vararg rows: Pair<String, Int>): TreeBuilder =
    TreeBuilder().apply {
        val list = add("Chats", top = 0)
        rows.forEach { (name, top) -> add(name, top, parent = list) }
    }
