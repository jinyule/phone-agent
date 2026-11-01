package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ScreenCoordinates

/**
 * Tuning of [ScreenFormatter] (phase1 §4); validated on construction.
 *
 * @property briefBudget characters of a `brief` page (≈ 3500): beyond it the middle zone is folded,
 *   while the header, the top zone and the bottom (navigation) zone are always kept.
 * @property fullBudget characters of a `full` page; folded the same way, only later.
 * @property labelMax longest quoted label, value or hint, in characters.
 * @property rowMax longest label of a tappable row that absorbed its child texts.
 * @property topZoneEnd elements centered above this y (0–999) are in the top zone.
 * @property bottomZoneStart elements centered below this y are in the bottom zone.
 * @property fullPageShare after an action, the full page replaces the diff when more than this
 *   share (0–1) of the elements was added, removed or changed.
 */
public data class ScreenFormatConfig(
    val briefBudget: Int = 3500,
    val fullBudget: Int = 20_000,
    val labelMax: Int = 60,
    val rowMax: Int = 100,
    val topZoneEnd: Int = 150,
    val bottomZoneStart: Int = 850,
    val fullPageShare: Double = 0.5,
) {
    init {
        require(briefBudget >= MIN_BUDGET) { "briefBudget must be >= $MIN_BUDGET, got $briefBudget" }
        require(fullBudget >= briefBudget) { "fullBudget must be >= briefBudget, got $fullBudget" }
        require(labelMax >= MIN_LABEL) { "labelMax must be >= $MIN_LABEL, got $labelMax" }
        require(rowMax >= labelMax) { "rowMax must be >= labelMax, got $rowMax" }
        require(topZoneEnd in 0..bottomZoneStart && bottomZoneStart <= ScreenCoordinates.MAX) {
            "zones need 0 <= topZoneEnd <= bottomZoneStart <= ${ScreenCoordinates.MAX}"
        }
        require(fullPageShare > 0.0 && fullPageShare < 1.0) { "fullPageShare must be in (0, 1), got $fullPageShare" }
    }

    private companion object {
        const val MIN_BUDGET = 500
        const val MIN_LABEL = 8
    }
}
