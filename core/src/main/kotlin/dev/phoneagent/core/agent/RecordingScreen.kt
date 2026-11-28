package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.Status.Failed
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.BlobStore
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.JudgmentPurpose
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.tool.FramedSnapshot
import dev.phoneagent.core.tool.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The [ScreenPort] a tool call gets: forwards to [port] and records each observation, action and
 * launch as a `screen/…` event of [call] once it returned, so the policy context and replay can be
 * derived from the log. An observation or wait that throws or is cancelled records nothing. An
 * action or launch that throws or is cancelled may still have reached the device (ScreenPort
 * contract), so it is recorded too, with [ActionOutcome.Effect.UNKNOWN] and no read-back: as
 * [ActionOutcome.Status.Failed] naming the exception's class only, or [UNFINISHED] when a
 * cancellation (or an [Error]) cut it off; one that returns after its call was cancelled is
 * recorded with what it returned. All under NonCancellable: the screen boundary is in the log
 * either way (phase1 §3.8). Then the exception goes on (a [ScreenUnavailableException] replaced,
 * below), unless recording fails: a failed log throws its own exception instead, and the task
 * stops with ERROR.
 *
 * Every screen read for the tool (an observation, an action's or a launch's read-back) is judged
 * by [gate] once its full `screen/…` event is recorded, the judgment recorded as `screen/judged`,
 * and only then does the tool get it (policy-rules §6.4): as read when shown, else [withheld].
 * Free text from the port reaches the tool only with a screen read and shown in this call: a
 * failed or refused outcome keeps its reason only when its own read-back was shown, else the
 * reason is [reasonWithheld]; a [ScreenUnavailableException] from the port is replaced by a new
 * one with [reasonWithheld] and no cause. An outcome's diff reaches the tool only when its
 * read-back was shown and its base is a screen the model saw ([seenScreens]); a cancellation
 * between the two records leaves the tool without the screen.
 *
 * [frame] captures nothing: a frame comes only with [framedSnapshot], which checks it against the
 * screen read after it.
 */
internal class RecordingScreen(
    private val port: ScreenPort,
    private val call: ToolCall,
    private val blobs: BlobStore,
    private val gate: ScreenGate,
    private val reasonWithheld: String,
    private val log: ScreenLog,
) : ScreenPort {
    private val callId: ToolCallId get() = call.id

    override val surface: SurfaceRef get() = port.surface

    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot {
        val read = observed(options)
        return if (judge(read, JudgmentPurpose.TOOL)) read else read.withheld()
    }

    /**
     * `ToolContext.framedSnapshot`: S, judged as the read before a capture; only when S is shown,
     * the frame, then S2, judged for the tool. The frame goes with S2 only when S2 is shown, in
     * S's app, and for a frame with boxes with S's element refs at S's places; it is then stored
     * and recorded as `screen/frame` of S checked on S2, after both screens' records. A capture
     * that throws [ScreenUnavailableException], returns nothing or an image the log does not take
     * counts as no frame (its message is never shown); a failed read of S2 drops the frame.
     *
     * Session replay depends on two things here (`DeviceScript` in the replay tests): S's
     * judgment is recorded before the capture, and nothing is captured without a SHOWN one, since
     * replay finds each capture by that judgment; and a dropped frame leaves the log and the
     * result as a capture that gave none does, since replay gives none for a frame the log lacks.
     */
    suspend fun framedSnapshot(
        options: SnapshotOptions,
        maxEdge: Int,
        som: Boolean,
    ): FramedSnapshot {
        val first = observed(options)
        if (!judge(first, JudgmentPurpose.FRAME_PRECHECK)) return FramedSnapshot(first.withheld(), frame = null)
        val frame = port.captured(maxEdge, som)
        val second = observed(options)
        val shown = judge(second, JudgmentPurpose.TOOL)
        val kept = frame?.takeIf { shown && it.fits(first, second) }
        if (kept != null) {
            val blob = blobs.put(kept.bytes)
            log.record(ScreenFrame(blob, kept.mime, kept.width, kept.height, kept.som, first.id, checkedOn = second.id))
        }
        return FramedSnapshot(if (shown) second else second.withheld(), kept)
    }

    private suspend fun observed(options: SnapshotOptions): ScreenSnapshot {
        val read = unavailable(reasonWithheld) { port.snapshot(options) }
        log.record(ScreenObserved(callId, read))
        return read
    }

    override suspend fun act(
        action: UiAction,
        on: SnapshotId?,
    ): ActionOutcome =
        shown(
            unavailable(reasonWithheld) {
                moving({ port.act(action, on) }) { log.record(ScreenActed(callId, on, action, it)) }
            },
        )

    override suspend fun launch(target: LaunchTarget): ActionOutcome =
        shown(
            unavailable(reasonWithheld) {
                moving({ port.launch(target) }) { log.record(ScreenLaunched(callId, target, it)) }
            },
        )

    /** Judges [screen], read for [purpose], and records the judgment; whether the tool may see it. */
    private suspend fun judge(
        screen: ScreenSnapshot,
        purpose: JudgmentPurpose,
    ): Boolean {
        val judgment = gate.judge(call, screen, log.records())
        val withheld = judgment as? ScreenJudgment.Withheld
        val outcome = if (withheld == null) JudgmentOutcome.SHOWN else JudgmentOutcome.WITHHELD
        log.record(ScreenJudged(callId, screen.id, purpose, outcome, withheld?.rule, withheld?.hint))
        return withheld == null
    }

    /** [outcome] as the tool may see it: its read-back judged, its reason and diff only along with a shown one. */
    private suspend fun shown(outcome: ActionOutcome): ActionOutcome {
        val after = outcome.after
        val withheld =
            outcome.copy(
                status = outcome.status.withheld(reasonWithheld),
                after = after?.withheld(),
                diff = null,
            )
        return when {
            after == null || !judge(after, JudgmentPurpose.TOOL) -> withheld
            else -> outcome.copy(diff = outcome.diff?.takeIf { it.base in seenScreens(log.records()) })
        }
    }

    /**
     * Runs [move] and records under NonCancellable what it returned, else [UNFINISHED] or, for an
     * exception that is no cancellation, that it threw (see the class comment).
     */
    @Suppress("TooGenericExceptionCaught") // Whatever ended it, the move may have reached the device.
    private suspend fun moving(
        move: suspend () -> ActionOutcome,
        recorded: suspend (ActionOutcome) -> Unit,
    ): ActionOutcome {
        var record: ActionOutcome? = null
        try {
            return move().also { record = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            record = ActionOutcome(Failed("threw ${thrown.javaClass.name}"), Effect.UNKNOWN)
            throw thrown
        } finally {
            recordMove(record, recorded)
        }
    }

    private suspend fun recordMove(
        record: ActionOutcome?,
        recorded: suspend (ActionOutcome) -> Unit,
    ) {
        withContext(NonCancellable) { recorded(record ?: UNFINISHED) }
    }

    /** Captures nothing: a frame comes only with [framedSnapshot], checked against a screen read after it. */
    override suspend fun frame(
        maxEdge: Int,
        som: Boolean,
    ): Frame? = null

    override suspend fun awaitChange(timeoutMs: Long): Boolean =
        unavailable(reasonWithheld) { port.awaitChange(timeoutMs) }

    private companion object {
        /** What an action or launch cut off by a cancellation is recorded as. */
        val UNFINISHED: ActionOutcome = ActionOutcome(Failed("did not finish"), Effect.UNKNOWN)
    }
}

/** This status with any reason the port wrote replaced by [reasonWithheld]. */
private fun ActionOutcome.Status.withheld(reasonWithheld: String): ActionOutcome.Status =
    when (this) {
        is Failed -> Failed(reasonWithheld)
        is ActionOutcome.Status.Refused -> ActionOutcome.Status.Refused(reasonWithheld)
        ActionOutcome.Status.Ok, is ActionOutcome.Status.StaleRef -> this
    }

/**
 * Runs [read]; a [ScreenUnavailableException] it throws is replaced by one with [reasonWithheld]
 * and no cause, as its message (or its cause's) may quote a screen nobody judged.
 */
@Suppress("SwallowedException") // Dropped on purpose: neither its message nor its cause may reach the model.
private inline fun <T> unavailable(
    reasonWithheld: String,
    read: () -> T,
): T =
    try {
        read()
    } catch (unjudged: ScreenUnavailableException) {
        throw ScreenUnavailableException(reasonWithheld)
    }

/**
 * A frame of this port, or `null` when it cannot capture: nothing returned, an image the log does
 * not take, or a [ScreenUnavailableException] (its message is never shown). A cancellation goes on.
 */
@Suppress("SwallowedException") // No frame either way; the message may quote a screen nobody judged.
private suspend fun ScreenPort.captured(
    maxEdge: Int,
    som: Boolean,
): Frame? =
    try {
        frame(maxEdge, som)?.takeIf { it.mime in Part.IMAGE_MIMES }
    } catch (unavailable: ScreenUnavailableException) {
        null
    }

/**
 * Whether this frame, captured between [first] and [second], may go with [second]: the same app,
 * and for a frame with boxes (drawn for [first]) the same element refs at the same places.
 */
private fun Frame.fits(
    first: ScreenSnapshot,
    second: ScreenSnapshot,
): Boolean = second.pkg == first.pkg && (!som || second.placed() == first.placed())

private fun ScreenSnapshot.placed(): List<Pair<ElementRef, Bounds>> = elements.map { it.ref to it.bounds }

/** The log a [RecordingScreen] writes to and judges on. */
internal interface ScreenLog {
    /** Appends [event]; throws when the log cannot take it. */
    suspend fun record(event: SessionEvent)

    /** Everything recorded so far. */
    suspend fun records(): List<SessionRecord>
}
