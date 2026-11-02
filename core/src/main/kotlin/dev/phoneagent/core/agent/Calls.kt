package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.UserInteraction
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.Tool
import dev.phoneagent.core.tool.ToolArguments
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolControl
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One tool call of a reply, from the model's [Part.ToolCall] to its `tool/result` (phase1 §7.2
 * steps 5–7):
 * 1. validate at the boundary: known tool, JSON arguments, schema, non-blank `reason`; a call that
 *    fails gets an INVALID_ARGS result and is never dispatched;
 * 2. `tool/call`, then the [Gate] (`toolPreExecute` + policy, confirmation, handoff);
 * 3. when allowed: flush first for anything but a READ tool (phase1 §3.9 rule 3; a failed flush
 *    stops the task and the call stays NOT_STARTED), then the whole `toolExecute` chain under the
 *    call's single timeout (`Tool.timeoutMs`, waiting for the user excluded). The chain's end runs
 *    the judged call only, once ([ExecutionEnd]);
 * 4. `toolPostExecute`, `tool/result`, and the attached context queued for the next step.
 */
internal class Calls(
    private val loop: LoopContext,
    private val prompts: Prompts,
) {
    private val interceptors = loop.setup.interceptors
    private val blobs = loop.setup.files.blobs
    private val gate = Gate(loop)
    private val postChain = interceptors.toolPostExecute.chain { PostExecuteResult(it.result) }

    /** Runs [part] of the reply recorded at [replySeq]; returns its result's control signal. */
    suspend fun run(
        state: TurnState,
        part: Part.ToolCall,
        replySeq: Long,
    ): ToolControl? =
        when (val parsed = parse(part)) {
            is Parsed.Invalid -> {
                answer(part, ToolResult.failure(ToolStatus.INVALID_ARGS, parsed.problem), replySeq)
                null
            }

            is Parsed.Valid -> {
                dispatch(state, parsed.tool, parsed.call, replySeq)
            }
        }

    /** Answers [part] without running it (another call of the reply ran, or the reply was cut off). */
    suspend fun skip(
        part: Part.ToolCall,
        text: String,
        replySeq: Long,
    ) {
        answer(part, ToolResult.failure(ToolStatus.SKIPPED, text), replySeq)
    }

    private suspend fun answer(
        part: Part.ToolCall,
        result: ToolResult,
        replySeq: Long,
    ) {
        loop.log.append(ToolResultRecorded(part.id, part.name, result), sources = listOf(replySeq))
    }

    private fun parse(part: Part.ToolCall): Parsed {
        val tool = loop.setup.registry[part.name] ?: return Parsed.Invalid("${loop.texts.unknownTool} ${part.name}")
        return jsonOf(part.argsJson).fold(
            onSuccess = { args -> checked(tool, part, args) },
            onFailure = { malformed -> Parsed.Invalid("${loop.texts.invalidJson} ${malformed.message}") },
        )
    }

    private fun jsonOf(text: String): Result<JsonElement> =
        try {
            Result.success(Json.parseToJsonElement(text))
        } catch (malformed: SerializationException) {
            Result.failure(malformed)
        }

    private fun checked(
        tool: Tool,
        part: Part.ToolCall,
        args: JsonElement,
    ): Parsed {
        val problems = ToolArguments.problems(tool.def.parameters, args)
        if (problems.isNotEmpty()) return Parsed.Invalid("${loop.texts.invalidArgs} ${problems.joinToString("; ")}")
        // The registry only admits object schemas with a required string `reason`, so both hold here.
        val fields = args.jsonObject
        val reason = fields.getValue(ToolCall.REASON).jsonPrimitive.content
        val arguments = JsonObject(fields - ToolCall.REASON)
        return if (reason.isBlank()) {
            Parsed.Invalid(loop.texts.blankReason)
        } else {
            Parsed.Valid(tool, ToolCall(part.id, loop.setup.taskId, loop.sessionId, part.name, arguments, reason))
        }
    }

    private suspend fun dispatch(
        state: TurnState,
        tool: Tool,
        call: ToolCall,
        replySeq: Long,
    ): ToolControl? {
        val dispatched = loop.log.append(ToolCallRecorded(call), sources = listOf(replySeq))
        state.notStarted += call.id
        loop.emit(LoopEvent.Card(call.id, tool.presentCall(call)))
        val result = gate.check(call, tool, prompts.interaction(call.id, UserWait.None)) ?: execute(state, tool, call)
        val post = postChain.proceed(PostExecuteInput(call, tool, result))
        loop.log.append(ToolResultRecorded(call.id, call.name, post.result), sources = listOf(dispatched.seq))
        loop.emit(LoopEvent.Card(call.id, tool.presentResult(call, post.result)))
        for (context in post.context) {
            loop.inbox.add(InboxQueue.NEXT_STEP, InboxEntry(InboxEntryKind.INJECT, context.parts, context.source))
        }
        return post.result.control
    }

    private suspend fun execute(
        state: TurnState,
        tool: Tool,
        call: ToolCall,
    ): ToolResult {
        if (tool.risk != RiskClass.READ) loop.log.flush()
        val budget = TimeoutBudget(tool.timeoutMs, loop.setup.timeSource)
        val judged = ExecuteInput(call, tool, CallContext(call, prompts.interaction(call.id, budget)))
        val end = ExecutionEnd(state, judged)
        val chain = interceptors.toolExecute.chain(end::run)
        val result = budget.run { chain.proceed(judged) }
        val mayHaveRun = end.started && tool.risk != RiskClass.READ
        return when {
            end.faulted -> ToolResult.failure(ToolStatus.ERROR, withEffect(loop.texts.interceptorFault, mayHaveRun))
            result == null -> ToolResult.failure(ToolStatus.TIMEOUT, withEffect(loop.texts.timeout, mayHaveRun))
            else -> result
        }
    }

    private fun withEffect(
        text: String,
        mayHaveRun: Boolean,
    ): String = if (mayHaveRun) "$text ${loop.texts.mayHaveTakenEffect}" else text

    /**
     * The end of the `toolExecute` chain: runs [judged] (the recorded, judged call with its own
     * context) at most once. Any other input, or a second `proceed`, runs nothing and marks the
     * call [faulted]: it is then recorded ERROR whatever the chain returns.
     */
    private inner class ExecutionEnd(
        private val state: TurnState,
        private val judged: ExecuteInput,
    ) {
        private var used = false
        var started = false
            private set
        var faulted = false
            private set

        @Suppress("TooGenericExceptionCaught") // Tools throw only for bugs, recorded as ERROR (Tool contract).
        suspend fun run(input: ExecuteInput): ToolResult {
            val same = input.call == judged.call && input.tool === judged.tool && input.context === judged.context
            if (used || !same) {
                faulted = true
                return ToolResult.failure(ToolStatus.ERROR, loop.texts.interceptorFault)
            }
            used = true
            started = true
            state.notStarted -= judged.call.id
            return try {
                judged.tool.execute(judged.call, judged.context)
            } catch (bug: Exception) {
                // A stop propagates; a tool that throws a cancellation of its own has a bug like any other.
                if (bug is CancellationException) currentCoroutineContext().ensureActive()
                ToolResult.failure(ToolStatus.ERROR, "${loop.texts.toolError} $bug")
            }
        }
    }

    private fun screenFor(call: ToolCall): ScreenPort? =
        loop.setup.screen?.let { port ->
            RecordingScreen(port, call.id, blobs) { event -> loop.log.append(event) }
        }

    private inner class CallContext(
        call: ToolCall,
        override val interaction: UserInteraction,
    ) : ToolContext {
        override val taskId: TaskId get() = loop.setup.taskId
        override val sessionId: SessionId get() = loop.sessionId
        override val screen: ScreenPort? = screenFor(call)
        override val clock: Clock get() = loop.setup.clock
        override val apps: AppCatalog? get() = loop.setup.apps

        override suspend fun storeBlob(bytes: ByteArray): BlobRef = blobs.put(bytes)
    }

    private sealed interface Parsed {
        class Invalid(
            val problem: String,
        ) : Parsed

        class Valid(
            val tool: Tool,
            val call: ToolCall,
        ) : Parsed
    }
}
