package dev.phoneagent.core.session

import kotlinx.serialization.Serializable

/**
 * A typed session event (phase1 §3.9, D13). Each subclass has a stable `@SerialName` that is
 * its `type` on disk, following `Envelope.TYPE_PATTERN`; its properties are the `data` payload.
 *
 * Events are facts in the order they happened; the log is the only source for rebuilding model
 * requests (`request/header` + `deriveMessages`), the trajectory UI and crash repair. Adding a
 * type: add it to `SessionEventTypes`, decide whether it is ignorable, and add a round-trip
 * sample to the tests. Renaming a type or property is a format change (bump the version and
 * write a migration).
 *
 * Model view: `deriveMessages(records)` (in this package) is a pure function of the records, in
 * seq order, one message per contributing record:
 * - [UserMessage] → a `Role.USER` message with its parts.
 * - [AssistantMessage] → a `Role.ASSISTANT` message with its parts. An interrupted one counts
 *   only once a [UserMessage] follows it before any other [AssistantMessage]: a prefix cut by an
 *   error and superseded by a retry of the same step is UI-only (the retry resends the request
 *   that produced it), a prefix cut by steer stays (the steer input follows it in the reopened
 *   step), and a prefix cut by stop or a final failure joins the view with the next user input.
 *   A prefix that counts joins without its `Part.Reasoning`s (they stay in the log for the UI),
 *   and is left out when nothing else remains.
 * - [ToolResultRecorded] → a `Role.TOOL` message holding `result.toPart(callId)`.
 * - [CompactionSummary] / [CompactionPrune] replace what their `SurfaceOp.Replace` range
 *   contributes (on the Anthropic path only in the UI projection, model-layer §5).
 * - [ReasoningDropped] removes the `Part.Reasoning`s of replies with seq <= its `upToSeq`, from
 *   its own record on; several drops add up. A message left without parts is left out.
 * - Every other type, [SideRequest] and [SideResponse] included, contributes nothing.
 *
 * Rule samples ("given these records, these messages") are in the core test sources
 * (`DeriveRuleSamples`); `deriveMessages` is tested against them.
 */
@Serializable
public sealed interface SessionEvent

/**
 * A decoded record: an event with its position and links.
 *
 * @property seq position in the log, from 1.
 * @property time append time, epoch milliseconds.
 * @property event the payload.
 * @property surfaceOp how it changes the model-visible surface.
 * @property sources seqs it answers or derives from.
 */
public data class SessionRecord(
    val seq: Long,
    val time: Long,
    val event: SessionEvent,
    val surfaceOp: SurfaceOp = SurfaceOp.Append,
    val sources: List<Long> = emptyList(),
)
