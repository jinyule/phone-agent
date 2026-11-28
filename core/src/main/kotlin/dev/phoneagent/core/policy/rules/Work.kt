package dev.phoneagent.core.policy.rules

import java.util.concurrent.atomic.LongAdder

/**
 * Counts of the basic steps on the paths whose cost grows with a screen (policy-rules.md §8), for
 * the tests that prove those costs bounded; they read them in a JVM of their own. Counting never
 * changes what is read. Shared by every reading in the process.
 */
internal object Work {
    /** Touches looked up by point: calls of [ScreenIndex.under]. */
    val touches: LongAdder = LongAdder()

    /** Elements and ancestors [ScreenIndex.under] looks at. */
    val under: LongAdder = LongAdder()

    /** Points whose drawn text is collected: once per point and snapshot. */
    val points: LongAdder = LongAdder()

    /** OCR lines looked at to collect what is drawn at a point or inside a control. */
    val ocr: LongAdder = LongAdder()

    /** Elements whose own evidence is read ([Controls]): once per element and snapshot. */
    val owns: LongAdder = LongAdder()

    /** Controls judged at a touch: one for each control and point. */
    val judged: LongAdder = LongAdder()

    /** Texts read into what a control or a point says ([Said]). */
    val texts: LongAdder = LongAdder()

    /** Dialog texts read: for generic buttons and for app-management dialogs. */
    val dialog: LongAdder = LongAdder()

    /** Elements looked at on the rows of fields ([Searches]). */
    val row: LongAdder = LongAdder()

    /** Fields looked at for clear buttons ([Fields.clearing]). */
    val fields: LongAdder = LongAdder()

    /** Elements looked at to collect the content of controls ([ScreenIndex.content]). */
    val content: LongAdder = LongAdder()

    /** Texts gathered as what a touch shows ([Assessment.texts]), before duplicates are dropped. */
    val gathered: LongAdder = LongAdder()

    /**
     * Characters given to [Text]'s normalization, the step under every reading of a text: a
     * measure in characters, bounded in proportion to the texts read, so not one of [counts].
     */
    val characters: LongAdder = LongAdder()

    /** Every count by name. */
    fun counts(): Map<String, Long> =
        mapOf(
            "touches" to touches,
            "under" to under,
            "points" to points,
            "ocr" to ocr,
            "owns" to owns,
            "judged" to judged,
            "texts" to texts,
            "dialog" to dialog,
            "row" to row,
            "fields" to fields,
            "content" to content,
            "gathered" to gathered,
        ).mapValues { it.value.sum() }

    /** Sets every count to 0. */
    fun reset() {
        listOf(
            touches,
            under,
            points,
            ocr,
            owns,
            judged,
            texts,
            dialog,
            row,
            fields,
            content,
            gathered,
            characters,
        ).forEach(LongAdder::reset)
    }
}
