package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.UiElement

/*
 * Facts about single elements shared by the formatter, the differ and the quality check.
 */

/** Roles that are operated by tapping even when the tree does not say CLICKABLE. */
private val ACTIONABLE_ROLES =
    setOf(
        ElementRole.BUTTON,
        ElementRole.INPUT,
        ElementRole.CHECKBOX,
        ElementRole.SWITCH,
        ElementRole.RADIO,
        ElementRole.TAB,
        ElementRole.LINK,
        ElementRole.PICKER,
    )

private val ACTIONABLE_STATES = setOf(ElementState.CLICKABLE, ElementState.LONG_CLICKABLE, ElementState.EDITABLE)

/** Whether the element has any text the model can read (name, value or hint). */
internal fun UiElement.hasText(): Boolean = !name.isNullOrBlank() || !value.isNullOrBlank() || !hint.isNullOrBlank()

/** Whether the model can act on the element directly (tap, long-press or type). */
internal fun UiElement.isActionable(): Boolean = role in ACTIONABLE_ROLES || states.any { it in ACTIONABLE_STATES }

/** Whether the element scrolls its children (printed as a list, children indented). */
internal fun UiElement.isScrollContainer(): Boolean = ElementState.SCROLLABLE in states || role == ElementRole.LIST

/** Whether the element is editable text. */
internal fun UiElement.isEditable(): Boolean = role == ElementRole.INPUT || ElementState.EDITABLE in states

/** [text] on one line: whitespace runs (including newlines) collapsed to one space, trimmed. */
internal fun oneLine(text: String): String = WHITESPACE.replace(text, " ").trim()

/** [text] cut to at most [max] characters, ending in `…` when cut. */
internal fun clip(
    text: String,
    max: Int,
): String = if (text.length <= max) text else text.take(max - 1) + "…"

private val WHITESPACE = Regex("\\s+")
