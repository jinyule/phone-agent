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
