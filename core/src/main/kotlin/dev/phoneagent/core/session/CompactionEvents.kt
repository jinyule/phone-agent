package dev.phoneagent.core.session

import dev.phoneagent.core.model.Part
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `compaction/start`: context reduction began. A start without an end means it crashed midway.
 *
 * @property trigger why.
 * @property inputTokens estimated or reported input size before, or `null`.
 */
@Serializable
@SerialName("compaction/start")
public data class CompactionStarted(
    val trigger: CompactionTrigger,
    val inputTokens: Int? = null,
) : SessionEvent

/** Why compaction ran. */
@Serializable
public enum class CompactionTrigger {
    /** Input passed the pressure threshold (60 % of the window, phase1 §7.2). */
    PRESSURE,

    /** The provider reported a context overflow. */
    OVERFLOW,

    /** The user asked. */
    MANUAL,
}

/**
 * `compaction/summary`: an LLM summary that replaces a range of records in the model view.
 * Always written with `SurfaceOp.Replace`; the model sees one user message with [parts].
 * Client-side only: never used on the Anthropic path (model-layer §5).
 */
@Serializable
@SerialName("compaction/summary")
public data class CompactionSummary(
    /** [Part.Text] and [Part.Image] only. */
    val parts: List<Part>,
) : SessionEvent {
    init {
        require(parts.all { it is Part.Text || it is Part.Image }) { "compaction/summary holds text and images only" }
    }
}

/**
 * `compaction/prune`: deterministic trimming of one record (e.g. an old screenshot replaced by a
 * placeholder). Written with `SurfaceOp.Replace(n, n)`; record n keeps its role and ids in the
 * model view, only its content becomes [parts].
 */
@Serializable
@SerialName("compaction/prune")
public data class CompactionPrune(
    /** Replacement content: [Part.Text] and [Part.Image] only. */
    val parts: List<Part>,
) : SessionEvent {
    init {
        require(parts.all { it is Part.Text || it is Part.Image }) { "compaction/prune holds text and images only" }
    }
}

/**
 * `compaction/end`.
 *
 * @property outcome how it went.
 * @property inputTokens estimated input size after, or `null`.
 */
@Serializable
@SerialName("compaction/end")
public data class CompactionEnded(
    val outcome: CompactionOutcome,
    val inputTokens: Int? = null,
) : SessionEvent

/** How compaction ended. */
@Serializable
public enum class CompactionOutcome {
    /** Context was reduced. */
    COMPLETED,

    /** Nothing worth reducing. */
    SKIPPED,

    /** It failed; the log is unchanged apart from the start/end pair. */
    FAILED,
}
