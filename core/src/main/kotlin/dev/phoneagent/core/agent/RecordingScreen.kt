package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ActionOutcome
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

/**
 * The [ScreenPort] a tool call gets: forwards to [port] and records each observation, action,
 * launch and frame as a `screen/…` event of [callId] once it returned, so the policy context and
 * replay can be derived from the log. A call that throws or is cancelled records nothing. Frames
 * of a mime type the log does not accept are passed through unrecorded.
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
    ): ActionOutcome = port.act(action, on).also { record(ScreenActed(callId, on, action, it)) }

    override suspend fun launch(target: LaunchTarget): ActionOutcome =
        port.launch(target).also { record(ScreenLaunched(callId, target, it)) }

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
}
