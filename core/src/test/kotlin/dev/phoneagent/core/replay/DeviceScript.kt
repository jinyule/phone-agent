package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolExecuted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted

/**
 * Which tool call the device is driven for at a point of a log: the call of the latest record that
 * names a call still without its `tool/result` (`tool/call`, `policy/verdict`, the approval,
 * question or handoff it asked for, `tool/started`, `tool/executed`, its `screen/…` records), or
 * `null`. The re-read after an approval belongs to the approved call, a frame to the call that
 * observed. Recording and replay apply the same rule, to the fixture and to the replayed log.
 */
class OpenCall {
    private val touched = LinkedHashMap<ToolCallId, Long>()
    private var clock = 0L

    /** The open call after the records taken so far. */
    val current: ToolCallId? get() = touched.maxByOrNull { it.value }?.key

    /** Takes [event] into account. */
    fun take(event: SessionEvent) {
        if (event is ToolResultRecorded) {
            touched -= event.callId
            return
        }
        named(event)?.let { touched[it] = ++clock }
    }

    companion object {
        /** The open call after [records]. */
        fun after(records: List<SessionRecord>): ToolCallId? =
            OpenCall().apply { records.forEach { take(it.event) } }.current

        /** The call [event] names, if any. */
        fun named(event: SessionEvent): ToolCallId? =
            when {
                event is ToolCallRecorded -> event.call.id
                event is PolicyVerdictRecorded -> event.callId
                event is ApprovalAsked -> event.card.callId
                event is QuestionAsked -> event.callId
                event is HandoffStarted -> event.callId
                event is ToolStarted -> event.callId
                event is ToolExecuted -> event.callId
                event is ScreenObserved -> event.callId
                event is ScreenActed -> event.callId
                event is ScreenLaunched -> event.callId
                else -> null
            }
    }
}

/** What a replayed port call does once its expectation holds. */
sealed interface DeviceResult {
    /** `snapshot` returns it (fingerprint and quality recomputed by the current analyzer). */
    data class Snapshot(
        val snapshot: ScreenSnapshot,
    ) : DeviceResult

    /** `act` or `launch` returns it (its `after` re-analyzed likewise). */
    data class Outcome(
        val outcome: ActionOutcome,
    ) : DeviceResult

    /** `frame` returns this frame, its bytes from the fixture's blobs. */
    data class Frame(
        val blob: BlobRef,
        val mime: String,
        val width: Int,
        val height: Int,
        val som: Boolean,
    ) : DeviceResult

    /** `awaitChange` returns [changed]. */
    data class Changed(
        val changed: Boolean,
    ) : DeviceResult

    /** `frame` returns `null`. */
    data object NoFrame : DeviceResult

    /** The call throws it. */
    data class Throws(
        val thrown: Thrown,
    ) : DeviceResult

    /** The call never returns; it ends when cancelled. */
    data object Hangs : DeviceResult
}

/**
 * One port call of a recording: the [op]-th port call made for [call], what replay must ask
 * ([port], and for an action or launch the [action] on [on], or the [target]), and what it then
 * does, after [delayMs] of virtual time.
 *
 * @property seq the fixture record it comes from, or `null` for a fact of `replay.override.json`.
 */
data class DeviceStep(
    val call: ToolCallId?,
    val op: Int,
    val port: DevicePort,
    val seq: Long?,
    val result: DeviceResult,
    val action: UiAction? = null,
    val on: SnapshotId? = null,
    val target: LaunchTarget? = null,
    val delayMs: Long = 0,
)

/**
 * The device side of a recording (WP08 design §4): the logged `screen/…` records of each call merged
 * with the facts of `replay.override.json` (engineering-baseline §3.2), as one queue per call.
 */
object DeviceScript {
    /** What `RecordingScreen` writes for an action or launch a cancellation cut off. */
    const val UNFINISHED: String = "did not finish"

    /** What `RecordingScreen` writes, before the class name, for an action or launch that threw. */
    const val THREW: String = "threw "

    /**
     * The port calls of [records] and [override], by call, in order. Fails with
     * [IllegalArgumentException] when a `screen/snapshot` names another call than the open one
     * ([OpenCall]), a fact lies beyond its call's port calls or annotates a call of another port,
     * or an action logged as having thrown has no fact giving the exception's message.
     */
    fun of(
        records: List<SessionRecord>,
        override: ReplayOverride,
    ): Map<ToolCallId?, List<DeviceStep>> {
        val logged = logged(records)
        val facts = override.device.groupBy { it.call?.let(::ToolCallId) }
        return (logged.keys + facts.keys).associateWith { call ->
            merge(call, logged[call].orEmpty(), facts[call].orEmpty())
        }
    }

    private fun logged(records: List<SessionRecord>): Map<ToolCallId?, List<DeviceStep>> {
        val open = OpenCall()
        val steps = LinkedHashMap<ToolCallId?, MutableList<DeviceStep>>()
        for (record in records) {
            val event = record.event
            open.take(event)
            val step = step(record.seq, event) ?: continue
            val call = open.current
            val named = (event as? ScreenObserved)?.callId
            require(event !is ScreenObserved || named == call) {
                "seq ${record.seq}: a screen/snapshot of ${named?.value} while ${call?.value} is the open call; " +
                    "replay cannot place it"
            }
            val list = steps.getOrPut(call) { mutableListOf() }
            list += step.copy(call = call, op = list.size + 1)
        }
        return steps
    }

    private fun step(
        seq: Long,
        event: SessionEvent,
    ): DeviceStep? =
        when {
            event is ScreenObserved -> {
                DeviceStep(null, 0, DevicePort.SNAPSHOT, seq, DeviceResult.Snapshot(event.snapshot))
            }

            event is ScreenActed -> {
                DeviceStep(null, 0, DevicePort.ACT, seq, moved(event.outcome), action = event.action, on = event.on)
            }

            event is ScreenLaunched -> {
                DeviceStep(null, 0, DevicePort.LAUNCH, seq, moved(event.outcome), target = event.target)
            }

            event is ScreenFrame -> {
                DeviceStep(
                    null,
                    0,
                    DevicePort.FRAME,
                    seq,
                    DeviceResult.Frame(event.blob, event.mime, event.width, event.height, event.som),
                )
            }

            else -> {
                null
            }
        }

    /** An `act` or `launch` outcome; one that threw is completed by its fact in [merge]. */
    private fun moved(outcome: ActionOutcome): DeviceResult {
        val reason = (outcome.status as? ActionOutcome.Status.Failed)?.reason
        return when {
            outcome.effect == ActionOutcome.Effect.UNKNOWN && reason == UNFINISHED -> DeviceResult.Hangs
            else -> DeviceResult.Outcome(outcome)
        }
    }

    private fun merge(
        call: ToolCallId?,
        logged: List<DeviceStep>,
        facts: List<DeviceFact>,
    ): List<DeviceStep> {
        val byOp = facts.associateBy { it.op }
        val total = logged.size + facts.count { it.unlogged }
        val beyond = facts.filter { it.op > total }.map { it.op }
        require(
            beyond.isEmpty(),
        ) { "replay.override.json: ${call?.value} makes $total port calls, facts name ops $beyond" }
        val queue = ArrayDeque(logged)
        return (1..total).map { op ->
            val fact = byOp[op]
            if (fact != null && fact.unlogged) {
                DeviceStep(call, op, fact.port, null, unlogged(fact), delayMs = fact.delayMs)
            } else {
                annotated(queue.removeFirst().copy(op = op), fact)
            }
        }
    }

    private fun unlogged(fact: DeviceFact): DeviceResult =
        when {
            fact.changed != null -> DeviceResult.Changed(fact.changed)
            fact.noFrame -> DeviceResult.NoFrame
            fact.throws != null -> DeviceResult.Throws(fact.throws)
            else -> DeviceResult.Hangs
        }

    private fun annotated(
        step: DeviceStep,
        fact: DeviceFact?,
    ): DeviceStep {
        val where = "${step.call?.value} op ${step.op} (seq ${step.seq})"
        require(fact == null || fact.port == step.port) {
            "replay.override.json: $where is a ${step.port}, the fact says ${fact?.port}"
        }
        val threw = threwClass(step.result)
        val thrown = fact?.throws
        if (threw == null) {
            require(thrown == null) { "replay.override.json: $where did not throw" }
            return step.copy(delayMs = fact?.delayMs ?: 0)
        }
        requireNotNull(
            thrown,
        ) { "$where threw $threw: replay.override.json must give its message (port ${step.port}, throws)" }
        require(thrown.type == threw) { "replay.override.json: $where threw $threw, the fact says ${thrown.type}" }
        return step.copy(result = DeviceResult.Throws(thrown), delayMs = fact.delayMs)
    }

    private fun threwClass(result: DeviceResult): String? {
        val unknown = (result as? DeviceResult.Outcome)?.outcome?.takeIf { it.effect == ActionOutcome.Effect.UNKNOWN }
        val reason = (unknown?.status as? ActionOutcome.Status.Failed)?.reason
        return reason?.takeIf { it.startsWith(THREW) }?.removePrefix(THREW)
    }
}
