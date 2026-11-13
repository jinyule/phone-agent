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
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.JudgmentPurpose
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenJudged
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

        /** The call [event] names, if any. Every event with a call id belongs here. */
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
                event is ScreenJudged -> event.callId
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
 * does, after [delayMs] of virtual time. For a frame, [on] is the screen read before the capture.
 *
 * @property seq the fixture record it comes from (for a frame, its `screen/frame`, or the judgment
 *   that allowed the capture when the log kept no frame), or `null` for a fact of
 *   `replay.override.json`.
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
     * ([OpenCall]), a `screen/frame` follows no capture of its screen, a fact lies beyond its
     * call's port calls or annotates a call of another port, a fact gives an outcome to a capture
     * whose frame the log kept, or an action logged as having thrown has no fact giving the
     * exception's message.
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
        val logged = Logged()
        for (record in records) {
            val event = record.event
            open.take(event)
            val call = open.current
            val named = (event as? ScreenObserved)?.callId
            require(event !is ScreenObserved || named == call) {
                "seq ${record.seq}: a screen/snapshot of ${named?.value} while ${call?.value} is the open call; " +
                    "replay cannot place it"
            }
            if (event is ScreenFrame) {
                logged.kept(call, record.seq, event)
            } else {
                step(record.seq, event)?.let { logged.add(call, it) }
            }
        }
        return logged.steps
    }

    /** The logged port calls of a log by call, as [logged] collects them. */
    private class Logged {
        val steps = LinkedHashMap<ToolCallId?, MutableList<DeviceStep>>()

        /** Where each capture still without its frame is in its call's steps, by the read before it. */
        private val captures = HashMap<Pair<ToolCallId?, SnapshotId>, Int>()

        fun add(
            call: ToolCallId?,
            step: DeviceStep,
        ) {
            val list = steps.getOrPut(call) { mutableListOf() }
            if (step.port == DevicePort.FRAME) step.on?.let { captures[call to it] = list.size }
            list += step.copy(call = call, op = list.size + 1)
        }

        /** Puts [frame], kept by the log, into the capture [call] made after reading [frame]'s first screen. */
        fun kept(
            call: ToolCallId?,
            seq: Long,
            frame: ScreenFrame,
        ) {
            val at =
                requireNotNull(captures.remove(call to frame.snapshotId)) {
                    "seq $seq: a screen/frame of ${frame.snapshotId.value} in ${call?.value} follows no capture of " +
                        "that screen (screen/judged FRAME_PRECHECK SHOWN); replay cannot place it"
                }
            val list = steps.getValue(call)
            list[at] =
                list[at].copy(
                    seq = seq,
                    result = DeviceResult.Frame(frame.blob, frame.mime, frame.width, frame.height, frame.som),
                )
        }
    }

    /**
     * The port call [event] records, or `null` for a record that is not device input.
     *
     * A capture is read off its judgment: the runtime captures a frame only right after it
     * recorded the read before the capture as shown (`screen/judged` FRAME_PRECHECK SHOWN), so
     * each such judgment is one `frame` call, between that read and the read after it, as the
     * port saw them. Its frame is the `screen/frame` of that read, recorded later; a capture
     * the log kept no frame of gives `null` ([DeviceResult.NoFrame]), which the runtime takes
     * as it took the dropped frame: no frame, nothing recorded. Any other judgment is a fact to
     * compare, not input.
     */
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

            event is ScreenJudged && event.purpose == JudgmentPurpose.FRAME_PRECHECK &&
                event.outcome == JudgmentOutcome.SHOWN -> {
                DeviceStep(null, 0, DevicePort.FRAME, seq, DeviceResult.NoFrame, on = event.snapshot)
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
                DeviceStep(call, op, fact.port, null, outcome(fact), delayMs = fact.delayMs)
            } else {
                annotated(queue.removeFirst().copy(op = op), fact)
            }
        }
    }

    private fun outcome(fact: DeviceFact): DeviceResult =
        when {
            fact.changed != null -> DeviceResult.Changed(fact.changed)
            fact.noFrame -> DeviceResult.NoFrame
            fact.throws != null -> DeviceResult.Throws(fact.throws)
            else -> DeviceResult.Hangs
        }

    /**
     * A capture with its [fact]: what the device did where the log kept no frame (gave none,
     * threw, hung), or only the delay; an outcome for a capture whose frame the log kept fails.
     */
    private fun captured(
        step: DeviceStep,
        fact: DeviceFact?,
        where: String,
    ): DeviceStep {
        val said =
            fact?.let {
                listOfNotNull(
                    "noFrame".takeIf { fact.noFrame },
                    "throws".takeIf { fact.throws != null },
                    "hangs".takeIf { fact.hangs },
                ).singleOrNull()
            }
        require(said == null || step.result == DeviceResult.NoFrame) {
            "replay.override.json: $where has its frame in the log, the fact says $said"
        }
        return when {
            fact == null -> step
            said == null -> step.copy(delayMs = fact.delayMs)
            else -> step.copy(result = outcome(fact), delayMs = fact.delayMs)
        }
    }

    private fun annotated(
        step: DeviceStep,
        fact: DeviceFact?,
    ): DeviceStep {
        val where = "${step.call?.value} op ${step.op} (seq ${step.seq})"
        require(fact == null || fact.port == step.port) {
            "replay.override.json: $where is a ${step.port}, the fact says ${fact?.port}"
        }
        return if (step.port == DevicePort.FRAME) captured(step, fact, where) else noted(step, fact, where)
    }

    /** A logged read, action or launch with its [fact]: the delay, and for one that threw the exception. */
    private fun noted(
        step: DeviceStep,
        fact: DeviceFact?,
        where: String,
    ): DeviceStep {
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
