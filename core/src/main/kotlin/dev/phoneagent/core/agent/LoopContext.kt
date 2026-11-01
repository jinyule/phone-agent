package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.session.SurfaceOp
import dev.phoneagent.core.session.TurnEndReason
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

/** What the parts of one task's loop share. Only the driver coroutine mutates turn state. */
internal class LoopContext(
    val setup: AgentSetup,
    val sessionId: SessionId,
    val log: LoopLog,
    val inbox: Inbox,
    val phase: MutableStateFlow<Phase>,
    private val events: MutableSharedFlow<LoopEvent>,
) {
    val config: LoopConfig get() = setup.config
    val texts: LoopTexts get() = setup.config.texts

    /** Publishes [event] to observers without ever suspending (slow collectors lose the oldest). */
    fun emit(event: LoopEvent) {
        events.tryEmit(event)
    }
}

/** The running turn. */
internal class TurnState(
    val turn: Int,
) {
    var step: Int = 0
    var attempt: Int = 0
    var continuations: Int = 0

    /** Calls with a `tool/call` record that certainly have not started running (repair says NOT_STARTED). */
    val notStarted: MutableSet<ToolCallId> = mutableSetOf()

    /** The attempt in flight, until it records its own outcome; an abnormal end records its prefix. */
    var streaming: Streaming? = null
}

/** What attempt [attempt] of [info] delivered so far. */
internal class Streaming(
    private val attempt: Int,
    private val info: ModelInfo,
) {
    private val text = StringBuilder()

    /** What was delivered, as text. */
    val delivered: String get() = text.toString()

    fun add(delta: Delta) {
        if (delta is Delta.Text) text.append(delta.text)
    }

    /** The delivered text as an interrupted `assistant/message`, or `null` when nothing was delivered. */
    fun interrupted(): AssistantMessage? =
        if (text.isEmpty()) {
            null
        } else {
            AssistantMessage(
                attempt,
                info.provider,
                info.model,
                listOf(Part.Text(delivered)),
                stopReason = null,
                interrupted = true,
            )
        }
}

/** How a turn ends normally. */
internal class TurnEnd(
    val reason: TurnEndReason,
    val detail: String? = null,
)

/** The phase a task settles in after a turn ended for [reason] (or before any turn: `null`). */
internal fun phaseAfter(reason: TurnEndReason?): Phase =
    if (reason == TurnEndReason.COMPLETED || reason == TurnEndReason.END_NOT_DONE) Phase.Done else Phase.Idle

/**
 * The task's session store, as the loop writes it: every append is published as
 * [LoopEvent.Recorded]. After a write or flush failure the store refuses writes; [reopen] replaces
 * it with a freshly opened one (which repairs a torn last line) so the failure can be recorded.
 */
internal class LoopLog(
    private val setup: AgentSetup,
    store: SessionStore,
    private val onRecord: (SessionRecord) -> Unit,
) {
    @Volatile
    private var store: SessionStore = store

    /** Seq of the last record. */
    val lastSeq: Long get() = store.lastSeq

    suspend fun append(
        event: SessionEvent,
        surfaceOp: SurfaceOp = SurfaceOp.Append,
        sources: List<Long> = emptyList(),
    ): SessionRecord = store.append(event, surfaceOp, sources).also(onRecord)

    suspend fun flush(): Unit = store.flush()

    suspend fun records(): List<SessionRecord> = store.records()

    /** Closes the store; a failure to close is ignored because the log is being given up anyway. */
    suspend fun close() {
        try {
            store.close()
        } catch (ignored: IOException) {
            // Closing only releases the handle; everything appended is already with the OS.
        }
    }

    /** Closes the current store and opens the log again; `false` when it cannot be opened. */
    suspend fun reopen(): Boolean {
        close()
        val opened =
            try {
                SessionStore.open(setup.files, setup.clock, setup.codec)
            } catch (ignored: IOException) {
                // Storage itself is failing: nothing more can be recorded; the next open repairs the log.
                null
            }
        opened?.let { store = it.store }
        return opened != null
    }
}
