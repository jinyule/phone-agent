package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ToolInteraction
import dev.phoneagent.core.ports.forTools
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolExecuted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.tool.Tool
import dev.phoneagent.core.tool.ToolArguments
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolControl
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
 * 3. when allowed: the whole `toolExecute` chain under the call's single timeout (`Tool.timeoutMs`,
 *    waiting for the user excluded). The chain's end runs the judged call only, once, and writes
 *    the execution facts around it ([ExecutionEnd]): `tool/started` is flushed before the tool
 *    runs, READ tools included. A failed flush stops the task before the tool runs; the log
 *    already says it started, so repair gives it OUTCOME_UNKNOWN. A timed-out or faulted call
 *    that started on a non-READ verdict adds [LoopTexts.mayHaveTakenEffect], as the repair does;
 * 4. a prompt the call asked and left open gets `prompt/withdrawn`, as a repair would write it (a
 *    wait the call cut off is closed at once by [Prompts]; this catches one an unexpected failure
 *    left open); a call that handed off and whose screen could not be read afterwards gets
 *    [LoopTexts.screenUnknown] after its result's text; then `toolPostExecute`, `tool/result`, and
 *    the attached context queued for the next step.
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
        part: Part.ToolCall,
        replySeq: Long,
    ): ToolControl? =
        when (val parsed = parse(part)) {
            is Parsed.Invalid -> {
                answer(part, ToolResult.failure(ToolStatus.INVALID_ARGS, parsed.problem), replySeq)
                null
            }

            is Parsed.Valid -> {
                dispatch(parsed.tool, parsed.call, replySeq)
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
        val tool = loop.setup.registry[part.name] ?: return Parsed.Invalid("${loop.texts.unknownTool}${part.name}")
        return jsonOf(part.argsJson).fold(
            onSuccess = { args -> checked(tool, part, args) },
            onFailure = { malformed -> Parsed.Invalid("${loop.texts.invalidJson}${malformed.message}") },
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
        if (problems.isNotEmpty()) return Parsed.Invalid("${loop.texts.invalidArgs}${problems.joinToString("；")}")
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
        tool: Tool,
        call: ToolCall,
        replySeq: Long,
    ): ToolControl? {
        val dispatched = loop.log.append(ToolCallRecorded(call), sources = listOf(replySeq))
        loop.emit(LoopEvent.Card(call.id, tool.presentCall(call)))
        val outcome =
            when (val ruling = gate.check(call, tool, prompts.interaction(call.id, UserWait.None))) {
                is Ruling.Run -> execute(tool, call, ruling.verdictSeq)
                is Ruling.Answered -> ruling.result
            }
        // A failure (not a cut, Prompts closes those) left a prompt open; the turn goes on: close it.
        withContext(NonCancellable) {
            for (prompt in openPromptsOf(loop.log.records(), call.id)) loop.log.append(PromptWithdrawn(prompt))
        }
        val result =
            if (screenLostAfterHandoff(
                    loop.log.records(),
                    call.id,
                )
            ) {
                outcome.noted(loop.texts.screenUnknown)
            } else {
                outcome
            }
        val post = postChain.proceed(PostExecuteInput(call, tool, result))
        loop.log.append(ToolResultRecorded(call.id, call.name, post.result), sources = listOf(dispatched.seq))
        loop.emit(LoopEvent.Card(call.id, tool.presentResult(call, post.result)))
        for (context in post.context) {
            loop.inbox.add(InboxQueue.NEXT_STEP, InboxEntry(InboxEntryKind.INJECT, context.parts, context.source))
        }
        return post.result.control
    }

    private suspend fun execute(
        tool: Tool,
        call: ToolCall,
        verdictSeq: Long,
    ): ToolResult {
        val budget = TimeoutBudget(tool.timeoutMs, loop.setup.timeSource)
        val judged = ExecuteInput(call, tool, CallContext(call, prompts.interaction(call.id, budget).forTools()))
        val end = ExecutionEnd(judged, verdictSeq)
        val chain = interceptors.toolExecute.chain(end::run)
        val result = budget.run { chain.proceed(judged) }
        if (result != null && !end.faulted) return result
        val text = if (end.faulted) loop.texts.interceptorFault else loop.texts.timeout
        // As the repair says it, from the log: only a call started on a non-READ verdict may have acted.
        val effect = if (call.id in actingCalls(loop.log.records())) loop.texts.mayHaveTakenEffect else ""
        return ToolResult.failure(if (end.faulted) ToolStatus.ERROR else ToolStatus.TIMEOUT, "$text$effect")
    }

    /**
     * The end of the `toolExecute` chain: runs [judged] (the recorded, judged call with its own
     * context) at most once. The first `proceed` uses the end up, whatever it passes: any other
     * input runs nothing, and neither does any later `proceed`, not even with [judged]. Each such
     * refusal marks the call [faulted]: it is then recorded ERROR whatever the chain returns.
     *
     * The execution facts are written here, so nothing else produces them (an interceptor that
     * answers without `proceed` runs nothing): `tool/started` on [verdictSeq], flushed, then
     * `Tool.execute`, then `tool/executed` with the status it returned (ERROR when it threw),
     * before `toolPostExecute`. A cancelled or timed-out execute gets no `tool/executed`. A stop or
     * timeout that arrives while `tool/started` is written or flushed runs nothing; the log still
     * says the call started, so its result says it may have taken effect (on a non-READ verdict).
     */
    private inner class ExecutionEnd(
        private val judged: ExecuteInput,
        private val verdictSeq: Long,
    ) {
        private var used = false
        var faulted = false
            private set

        @Suppress("TooGenericExceptionCaught") // Tools throw only for bugs, recorded as ERROR (Tool contract).
        suspend fun run(input: ExecuteInput): ToolResult {
            val same = input.call == judged.call && input.tool === judged.tool && input.context === judged.context
            val first = !used
            used = true
            if (!first || !same) {
                faulted = true
                return ToolResult.failure(ToolStatus.ERROR, loop.texts.interceptorFault)
            }
            val id = judged.call.id
            loop.log.append(ToolStarted(id, verdictSeq))
            loop.log.flush()
            // The flush finishes even when a stop or the budget cancels it meanwhile: then run nothing.
            currentCoroutineContext().ensureActive()
            val result =
                try {
                    judged.tool.execute(judged.call, judged.context)
                } catch (bug: Exception) {
                    // A stop propagates; a tool that throws a cancellation of its own has a bug like any other.
                    if (bug is CancellationException) currentCoroutineContext().ensureActive()
                    // The class only: message, cause and toString may quote the screen, input or credentials.
                    ToolResult.failure(ToolStatus.ERROR, "${loop.texts.toolError}${bug.javaClass.name}")
                }
            loop.log.append(ToolExecuted(id, result.status))
            return result
        }
    }

    private fun screenFor(call: ToolCall): ScreenPort? =
        loop.setup.screen?.let { port ->
            RecordingScreen(port, call.id, blobs) { event -> loop.log.append(event) }
        }

    /** A call's own context: its tool may ask and hand off, never confirm (cards come from the policy only). */
    private inner class CallContext(
        call: ToolCall,
        override val interaction: ToolInteraction,
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

/** This result with [note] on a line of its own after its last text (or as a text of its own). */
internal fun ToolResult.noted(note: String): ToolResult {
    val last = content.lastOrNull() as? Part.Text
    return if (last ==
        null
    ) {
        copy(content = content + Part.Text(note))
    } else {
        copy(
            content =
                content.dropLast(1) + Part.Text("${last.text}\n$note"),
        )
    }
}
