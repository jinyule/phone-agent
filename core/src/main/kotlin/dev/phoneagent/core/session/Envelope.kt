package dev.phoneagent.core.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One line of `session.jsonl` (phase1 §3.9): the event payload in [data], tagged by [type].
 * Fields at their default value are omitted on disk.
 *
 * Invariants (checked on construction, so a malformed line fails where it is read):
 * [seq] >= 1; [type] follows [TYPE_PATTERN]; every source and the [surfaceOp] range point to
 * earlier records.
 *
 * @property seq position in the log, from 1, contiguous.
 * @property time wall-clock milliseconds when the record was appended (zeroed in fixtures).
 * @property type the event type, e.g. `tool/result`.
 * @property ignorable a reader that does not know [type] may skip this record and still derive a
 *   correct model view; otherwise it must refuse the whole log.
 * @property surfaceOp how the record affects the model-visible surface.
 * @property sources seqs of the records this one answers or derives from (e.g. `tool/result` → `tool/call`).
 * @property data the payload.
 */
@Serializable
public data class Envelope(
    val seq: Long,
    val time: Long,
    val type: String,
    val ignorable: Boolean = false,
    val surfaceOp: SurfaceOp = SurfaceOp.Append,
    val sources: List<Long> = emptyList(),
    val data: JsonElement,
) {
    init {
        require(seq >= 1) { "seq must be >= 1, got $seq" }
        require(TYPE_PATTERN.matches(type)) { "seq $seq: event type '$type' must match ${TYPE_PATTERN.pattern}" }
        require(sources.all { it in 1 until seq }) { "seq $seq: sources $sources must point to earlier records" }
        val op = surfaceOp
        require(op !is SurfaceOp.Replace || op.endSeq < seq) { "seq $seq: replace range must end before it" }
    }

    /** Constants. */
    public companion object {
        /** Event types are `<domain>/<name>`, lowercase, words joined by `-`. */
        public val TYPE_PATTERN: Regex = Regex("[a-z]+(-[a-z]+)*/[a-z]+(-[a-z]+)*")
    }
}

/** How a record changes the model-visible message surface. */
@Serializable
public sealed interface SurfaceOp {
    /** Adds to the end (every ordinary event). */
    @Serializable
    @SerialName("append")
    public data object Append : SurfaceOp

    /**
     * Replaces what records [startSeq]..[endSeq] (inclusive) contribute to the model view with
     * this record's content (compaction, pruning). The replaced records stay in the log for the UI.
     */
    @Serializable
    @SerialName("replace")
    public data class Replace(
        /** First replaced seq, >= 1. */
        val startSeq: Long,
        /** Last replaced seq, >= [startSeq]. */
        val endSeq: Long,
    ) : SurfaceOp {
        init {
            require(startSeq in 1..endSeq) { "Invalid replace range $startSeq..$endSeq" }
        }
    }
}
