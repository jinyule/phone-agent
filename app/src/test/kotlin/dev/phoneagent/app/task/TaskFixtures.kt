package dev.phoneagent.app.task

import dev.phoneagent.core.agent.ContextInput
import dev.phoneagent.core.agent.LoopEvent
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.TaskRuntime
import dev.phoneagent.core.agent.UserInput
import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.assembly.ProductConfig
import dev.phoneagent.core.assembly.assemble
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.RequestSettings
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionFormat
import dev.phoneagent.core.session.SessionHeader
import dev.phoneagent.core.session.SessionLog
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val TASK = TaskId("task-1")
internal val SESSION = SessionId("session-1")

/** A main model that only states what it can do; tests that use it send nothing. */
internal object CapabilitiesOnly : ChatModel {
    override val info: ModelInfo =
        ModelInfo(
            ProviderId("test"),
            ModelId("test-model"),
            ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            ModelCapabilities(tools = true, vision = true, contextTokens = 128_000),
        )

    override fun prepare(reasoning: ReasoningLevel?): RequestSettings = error("no requests in this test")

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse = error("no requests in this test")
}

/** The product's tools, as a task of the app has them. */
internal val PRODUCT_TOOLS: ToolRegistry =
    assemble(CapabilitiesOnly, ProductConfig(setOf("dev.phoneagent"), setOf("PhoneAgent"))).registry

/** The product's cards. */
internal val PRODUCT_CARDS: StepCards = StepCards.of(PRODUCT_TOOLS)

/** A call of [name] with [args] (strings, numbers and booleans). */
internal fun call(
    id: String,
    name: String,
    vararg args: Pair<String, Any>,
): ToolCall =
    ToolCall(
        ToolCallId(id),
        TASK,
        SESSION,
        name,
        JsonObject(
            args.associate { (key, value) ->
                key to
                    when (value) {
                        is Number -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    }
            },
        ),
        "reason of $id",
    )

/** [events] as records with seq 1, 2, … */
internal fun records(vararg events: SessionEvent): List<SessionRecord> =
    events.mapIndexed { index, event -> SessionRecord(index + 1L, index * 10L, event) }

/** The user's command [text]. */
internal fun command(text: String): UserMessage = UserMessage(UserMessageKind.USER, listOf(Part.Text(text)))

/** One run of a [FakeTask]: what the controller gave it, and its phase and events as the test sets them. */
internal class FakeRuntime : TaskRuntime {
    override val id: TaskId = TASK
    override val phase: MutableStateFlow<Phase> = MutableStateFlow(Phase.Idle)
    override val events: MutableSharedFlow<LoopEvent> = MutableSharedFlow(extraBufferCapacity = 64)
    val followups = mutableListOf<String>()
    val stops = mutableListOf<StopCause>()
    val answers = mutableListOf<Pair<PromptId, UserReply>>()
    var answerTaken: Boolean = true

    override fun followup(input: UserInput) {
        followups += input.parts.filterIsInstance<Part.Text>().joinToString { it.text }
    }

    override fun steer(input: UserInput): Unit = error("the app does not steer")

    override fun inject(context: ContextInput): Unit = error("the app does not inject")

    override fun changeLevel(level: SecurityLevel): Unit = error("the app does not change levels yet")

    override fun stop(cause: StopCause) {
        stops += cause
    }

    override fun answer(
        promptId: PromptId,
        reply: UserReply,
    ): Boolean {
        answers += promptId to reply
        return answerTaken
    }
}

/**
 * A task whose log the test writes: [append] adds a record and, like the runtime, sends it to the
 * current run's watchers after it is in the log. A [gate] holds every log read (each read sees the
 * log as it was when the read began).
 */
internal class FakeTask(
    override val cards: StepCards = StepCards.NONE,
) : TaskSession {
    val log = mutableListOf(SessionRecord(1, 0, TaskStarted(SecurityLevel.STANDARD, unattended = false)))
    val runs = mutableListOf(FakeRuntime())
    var gate: CompletableDeferred<Unit>? = null

    /** Holds read number n (from 1) until completed, instead of [gate]. */
    val readGates = mutableMapOf<Int, CompletableDeferred<Unit>>()

    /** Holds [resume] until completed. */
    var resumeGate: CompletableDeferred<Unit>? = null
    var readFailure: Exception? = null
    var reads = 0

    val run: FakeRuntime get() = runs.last()

    /** The started task, its first run. */
    val started: StartedTask get() = StartedTask(this, runs.first())

    override suspend fun resume(scope: CoroutineScope): FakeRuntime {
        resumeGate?.await()
        return FakeRuntime().also { runs += it }
    }

    override suspend fun log(): SessionLog {
        reads++
        val seen = log.toList()
        (readGates[reads] ?: gate)?.await()
        readFailure?.let { throw it }
        return SessionLog(SessionHeader(SessionFormat.FORMAT_ID, SessionFormat.CURRENT_VERSION, SESSION, TASK, 0), seen)
    }

    /** Appends [event]; tells the current run's watchers unless [silently]. */
    fun append(
        event: SessionEvent,
        silently: Boolean = false,
    ): SessionRecord {
        val record = SessionRecord(log.size + 1L, log.size * 10L, event)
        log += record
        if (!silently) check(run.events.tryEmit(LoopEvent.Recorded(record))) { "event buffer full" }
        return record
    }
}
