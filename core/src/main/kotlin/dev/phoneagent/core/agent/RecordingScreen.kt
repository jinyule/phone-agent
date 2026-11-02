package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.Status.Failed
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.BlobStore
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The [ScreenPort] a tool call gets: forwards to [port] and records each observation, action,
 * launch and frame as a `screen/…` event of [callId] once it returned, so the policy context and
 * replay can be derived from the log. An observation, frame or wait that throws or is cancelled
 * records nothing. An action or launch that throws or is cancelled may still have reached the
 * device (ScreenPort contract), so it is recorded too, with [ActionOutcome.Effect.UNKNOWN] and no
 * read-back: as [ActionOutcome.Status.Failed] naming the exception's class only, or [UNFINISHED]
 * when a cancellation (or an [Error]) cut it off; one that returns after its call was cancelled
 * is recorded with what it returned. All under NonCancellable: the screen boundary is in the log
 * either way (phase1 §3.8). Then the same exception goes on, unless recording fails: a failed log
 * throws its own exception instead, and the task stops with ERROR. Frames of a mime type the log
 * does not accept pass unrecorded.
 */
internal class RecordingScreen(
    private val port: ScreenPort,
    private val callId: ToolCallId,
    private val blobs: BlobStore,
    private val record: suspend (SessionEvent) -> Unit,
) : ScreenPort {
    override val surface: SurfaceRef get() = port.surface

    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot =
        port.snapshot(options).also { record(ScreenObserved(callId, it)) }

    override suspend fun act(
        action: UiAction,
        on: SnapshotId?,
    ): ActionOutcome = moving({ port.act(action, on) }) { record(ScreenActed(callId, on, action, it)) }

    override suspend fun launch(target: LaunchTarget): ActionOutcome =
        moving({ port.launch(target) }) { record(ScreenLaunched(callId, target, it)) }

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

    override suspend fun frame(
        maxEdge: Int,
        som: Boolean,
    ): Frame? {
        val frame = port.frame(maxEdge, som)
        if (frame != null && frame.mime in Part.IMAGE_MIMES) {
            record(ScreenFrame(blobs.put(frame.bytes), frame.mime, frame.width, frame.height, frame.som))
        }
        return frame
    }

    override suspend fun awaitChange(timeoutMs: Long): Boolean = port.awaitChange(timeoutMs)

    private companion object {
        /** What an action or launch cut off by a cancellation is recorded as. */
        val UNFINISHED: ActionOutcome = ActionOutcome(Failed("did not finish"), Effect.UNKNOWN)
    }
}
