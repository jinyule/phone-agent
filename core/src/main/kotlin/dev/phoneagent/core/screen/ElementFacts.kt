package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenCoordinates
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

/**
 * The Unicode White_Space characters, all 25: U+0009–U+000D, U+0020, U+0085, U+00A0, U+1680,
 * U+2000–U+200A, U+2028, U+2029, U+202F, U+205F and U+3000. Listed because [Char.isWhitespace]
 * misses U+0085 (NEXT LINE) and counts U+001C–U+001F, which are not spaces.
 */
internal const val UNICODE_SPACES: String =
    "\t\n\u000B\u000C\r \u0085\u00A0\u1680" +
        "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A" +
        "\u2028\u2029\u202F\u205F\u3000"

/** Whether this is one of [UNICODE_SPACES]. */
internal fun Char.isUnicodeSpace(): Boolean = this in UNICODE_SPACES

/** [text] on one line: runs of [UNICODE_SPACES] (line breaks included) collapsed to one space, trimmed. */
internal fun oneLine(text: String): String = SPACE_RUN.replace(text, " ").trim(Char::isUnicodeSpace)

/** [text] cut to at most [max] characters, ending in `…` when cut. */
internal fun clip(
    text: String,
    max: Int,
): String = if (text.length <= max) text else text.take(max - 1) + "…"

/** Share (0–1] of the screen these bounds cover. */
internal fun Bounds.areaShare(): Double {
    val side = ScreenCoordinates.MAX + 1.0
    return (right - left + 1.0) * (bottom - top + 1.0) / (side * side)
}

private val SPACE_RUN = Regex("[$UNICODE_SPACES]+")
