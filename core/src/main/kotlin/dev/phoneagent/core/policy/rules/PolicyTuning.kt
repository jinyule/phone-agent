package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.ScreenCoordinates

/**
 * The heuristic thresholds of [PolicyRules] (policy-rules.md §8). Each one trades missed risks
 * against needless questions, so they are configuration with checked ranges rather than constants
 * spread over the rules. Not shown to users. Lengths count characters of normalized text;
 * distances are normalized screen coordinates (0–999). The limits bound how long reading one
 * screen takes; a screen beyond them is treated as one that cannot be read.
 *
 * Construction fails with [IllegalArgumentException] naming the first value out of range.
 *
 * @property labelMax longest label (amounts, counts and bracketed notes left out) in which a risk word counts.
 * @property contextMax longest dialog text whose risk word decides what a generic button ("确定") does.
 * @property shortText longest text read as a heading or a label when recognizing a page.
 * @property totalTextMax longest text that may hold a total label ("合计 ¥88.00").
 * @property labelRead risk checks read this many characters of a control's label; a longer label
 *   is never one word that exempts a control ("关闭", "清空").
 * @property titleBand page titles sit at or above this line.
 * @property clearNear a clear button may sit this far left or right of its field.
 * @property rowSwipeMin a sideways swipe at least this long on a list row may delete the row.
 * @property rowMinWidth list rows are at least this wide.
 * @property carouselMax scrollable areas lower than this are carousels: swiping them pages.
 * @property maxElements a screen with more elements and OCR lines than this is not read: actions on
 *   it and reading it are refused, since checking it would take too long.
 * @property maxScreenText a screen with more characters of text than this (names, values, hints,
 *   resource ids and OCR lines together) is not read.
 * @property maxDepth a screen whose tree has longer chains of parents than this, or a cycle, is not
 *   read.
 * @property totalsRead at most this many total labels ("合计", "实付" …) are read on a screen, best
 *   first; when more are shown and none of those has an amount after it, the amount is unclear.
 */
public data class PolicyTuning(
    val labelMax: Int = 12,
    val contextMax: Int = 40,
    val shortText: Int = 20,
    val totalTextMax: Int = 30,
    val labelRead: Int = 120,
    val titleBand: Int = 150,
    val clearNear: Int = 80,
    val rowSwipeMin: Int = 100,
    val rowMinWidth: Int = 600,
    val carouselMax: Int = 400,
    val maxElements: Int = 5_000,
    val maxScreenText: Int = 100_000,
    val maxDepth: Int = 100,
    val totalsRead: Int = 8,
) {
    init {
        val lengths =
            listOf(
                "labelMax" to labelMax,
                "contextMax" to contextMax,
                "shortText" to shortText,
                "totalTextMax" to totalTextMax,
                "labelRead" to labelRead,
            )
        val distances =
            listOf(
                "titleBand" to titleBand,
                "clearNear" to clearNear,
                "rowSwipeMin" to rowSwipeMin,
                "rowMinWidth" to rowMinWidth,
                "carouselMax" to carouselMax,
            )
        val limits =
            listOf(
                "maxElements" to maxElements,
                "maxScreenText" to maxScreenText,
                "maxDepth" to maxDepth,
                "totalsRead" to totalsRead,
            )
        for ((name, value) in lengths) {
            require(value in 1..MAX_LENGTH) { "PolicyTuning.$name must be in 1..$MAX_LENGTH, got $value" }
        }
        for ((name, value) in limits) {
            require(value in 1..MAX_LIMIT) { "PolicyTuning.$name must be in 1..$MAX_LIMIT, got $value" }
        }
        for ((name, value) in distances) {
            require(ScreenCoordinates.isValid(value)) {
                "PolicyTuning.$name must be in 0..${ScreenCoordinates.MAX}, got $value"
            }
        }
    }

    /** Constants. */
    public companion object {
        /** Version of the rules and of these defaults; bumped with every change that can alter a verdict. */
        public const val RULES_VERSION: Int = 3

        private const val MAX_LENGTH = 1000
        private const val MAX_LIMIT = 10_000_000
    }
}
