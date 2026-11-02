package dev.phoneagent.core.tool

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail

/**
 * How screens read to the model (phase1 §4). Implemented by the screen capability and injected
 * into the phone tools by the app, so tools never format screens themselves and the format has one
 * owner. Every function is pure and deterministic: the text goes into `tool/result`, and replay
 * must reproduce it byte for byte.
 */
public interface ScreenPresenter {
    /**
     * Whether the main model reads images; the one place this capability is configured. Without it
     * no page note suggests a screenshot, and `phone_observe` never attaches a frame (it says so
     * instead), so the notes and the frames always agree.
     */
    public val vision: Boolean

    /** [snapshot] as one page at [detail] (header, zones, OCR text; folded to the format's budget). */
    public fun page(
        snapshot: ScreenSnapshot,
        detail: SnapshotDetail,
    ): String

    /**
     * What the model reads about the screen after an action planned on [plannedOn] (`null` when the
     * action names no snapshot, e.g. a swipe or a key). The tool adds its status line before it.
     *
     * - [ActionOutcome.after] is `null`: a one-line note telling the model to observe.
     * - The full page of `after` (as [page] at `BRIEF`; with a diff and the same app, elements new
     *   since the earlier snapshot marked `+`) when [ActionOutcome.diff] is `null`, the effect is
     *   `NEW_APP`, more than a configured share (by default half) of the page's lines were added,
     *   removed or changed, the diff names an element `after` does not have, or the diff text
     *   would not fit the page budget.
     * - Otherwise only the diff ([dev.phoneagent.core.ports.ScreenDiff]): `+` added and `~`
     *   changed elements as they are now, then the refs the earlier page showed that are no longer
     *   valid (a removed ref is left out only when the diff prints its new binding), then OCR lines
     *   that appeared, moved or went away. An element that is not listed keeps its ref, line and
     *   place, so the model may keep using what it saw before.
     *
     * Whenever `after` exists the text starts with `screen <id of after>`, so the model always
     * knows the id of the latest snapshot.
     */
    public fun afterAction(
        outcome: ActionOutcome,
        plannedOn: SnapshotId?,
    ): String

    /**
     * [text] with bank-card and national-id numbers masked: for device-side text in tool results
     * (failure and refusal reasons, exception messages) and for step cards.
     */
    public fun mask(text: String): String
}
