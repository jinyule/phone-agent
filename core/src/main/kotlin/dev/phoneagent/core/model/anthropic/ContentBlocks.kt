package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reports protocol violations as [ModelError.BadResponse] with the request's [redactor] masking
 * what the server echoed, and tells which server values masking would change.
 */
internal class Protocol(
    private val redactor: Redactor,
) {
    fun bad(detail: String): Nothing = throw ModelError.BadResponse(redactor.text(detail))

    /**
     * Whether masking would change a member name or a value anywhere in [value]: strings, numbers
     * and booleans alike, as written (a secret such as `123456` or `true` is a JSON literal there).
     */
    fun masks(value: JsonElement): Boolean =
        when (value) {
            is JsonObject -> value.any { (key, member) -> redactor.text(key) != key || masks(member) }
            is JsonArray -> value.any(::masks)
            is JsonPrimitive -> value !is JsonNull && redactor.text(value.content) != value.content
        }

    /**
     * [text] as JSON; [what] names it in the error. The parser's message quotes the input around
     * the error cut to a fixed width, which can cut a secret where masking no longer finds it: only
     * the description before the quote is kept, and the exception is not attached.
     */
    fun parse(
        text: String,
        what: String,
    ): JsonElement =
        try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            val description =
                e.message
                    .orEmpty()
                    .substringBefore(QUOTED_INPUT)
                    .trim()
            throw ModelError.BadResponse(redactor.text("$what is not JSON: $description"))
        }

    /** The `index` of a block event. */
    fun index(event: JsonObject): Int {
        val index = (event["index"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        return index?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: bad("event without a valid block index")
    }
}

/** Makes the [Part.Reasoning] of a stored block: bound to the reply's provider and requested model. */
internal fun interface Bind {
    fun reasoning(
        kind: ReasoningKind,
        opaque: JsonObject,
        summary: String?,
    ): Part.Reasoning
}

/**
 * The content blocks of one reply, and what `onDelta` has shown of them ([MessagesStream] states
 * the contract). Blocks come one after another with growing indexes, and the `tool_use` and
 * `server_tool_use` ids of one reply are distinct; anything else is a protocol violation, not
 * something to sort or deduplicate. Each block takes only its own delta types.
 *
 * Every block start and delta passes the [guard] before anything of it is shown: each member's
 * name (`thinking`, `redacted_thinking` and `compaction` keep their start verbatim, unknown
 * members included), then its value, a string through a window per block and member name (a
 * start's `text` and its deltas' `text` are one channel), anything else whole with every nested
 * name and value. The `input_json_delta` text of every block (calls,
 * `server_tool_use`, any block this adapter does not know, a declined attempt's too) is also
 * checked whole as call arguments ([EchoGuard.arguments]) at the block's stop, or by [abandon]
 * when the reply fails first. Only the `type` members are left out (protocol, not content).
 */
internal class ContentBlocks(
    private val protocol: Protocol,
    private val guard: EchoGuard,
    onDelta: (Delta) -> Unit,
) {
    private val blocks = mutableListOf<Block>()
    private val toolIds = mutableSetOf<String>()
    private val windows = HashMap<Pair<Int, String>, EchoGuard.Window>()
    private val arguments = HashMap<Int, StringBuilder>()
    private val shown = Shown(onDelta)

    /** `content_block_start`. */
    fun start(event: JsonObject) {
        val index = protocol.index(event)
        if (blocks.any { it.index == index }) protocol.bad("block $index started twice")
        blocks.lastOrNull()?.let { last ->
            if (index < last.index) protocol.bad("block $index starts after block ${last.index}")
            if (!last.closed) protocol.bad("block $index starts while block ${last.index} is open")
        }
        val start = event.obj("content_block") ?: protocol.bad("block $index without content_block")
        start.forEach { (key, value) -> if (key != TYPE) screen(index, key, value) }
        val block = Block.of(index, start, protocol)
        block.toolId?.let { if (!toolIds.add(it)) protocol.bad("block $index repeats tool id $it") }
        blocks += block
        if (block is Block.Fallback) shown.newAttempt()
        block.started(shown)
    }

    /** `content_block_delta`. */
    fun delta(event: JsonObject) {
        val block = block(event, "content_block_delta")
        if (block.closed) protocol.bad("delta for block ${block.index} after its content_block_stop")
        val delta = event.obj("delta") ?: protocol.bad("content_block_delta without a delta")
        delta.forEach { (key, value) -> if (key != TYPE) screen(block.index, key, value) }
        val type = delta.str("type")
        if (type == INPUT_JSON_DELTA) {
            delta.str(PARTIAL_JSON)?.let { arguments.getOrPut(block.index, ::StringBuilder).append(it) }
        }
        if (!block.apply(type, delta, shown, protocol)) protocol.bad("unexpected $type for a ${block.type} block")
    }

    /** `content_block_stop`. */
    fun stop(event: JsonObject) = close(block(event, "content_block_stop"))

    /** Starts and stops one complete block (a whole JSON reply). */
    fun whole(
        index: Int,
        block: JsonElement,
    ) {
        start(JsonObject(mapOf("index" to JsonPrimitive(index), "content_block" to block)))
        close(blocks.last())
    }

    /**
     * The reply failed before its end (a cut-off stream, an error event, unparseable data): checks
     * the argument text of the blocks it left open as their stops would have, so a secret in it
     * fails the reply as [CredentialEcho.error] and the shown fragments are discarded.
     */
    fun abandon() = arguments.values.forEach { guard.arguments(it.toString()) }

    /** The block still open, or `null`. */
    fun open(): Int? = blocks.lastOrNull()?.takeUnless { it.closed }?.index

    /**
     * The reply parts, in order. A mid-output fallback drops the declined attempts' thinking, tool
     * calls and unknown blocks before the last `fallback` block (model-migration: echoing fallback
     * turns); `fallback` blocks are only markers. An unknown block anywhere else rejects the reply,
     * as does a kept thinking block without a signature.
     */
    fun assemble(bind: Bind): Assembled {
        val boundary = blocks.indexOfLast { it is Block.Fallback }
        val (dropped, kept) = blocks.withIndex().partition { (at, block) -> at < boundary && block.declinedAttempt }
        val fallbacks = blocks.filterIsInstance<Block.Fallback>()
        return Assembled(
            parts = kept.mapNotNull { it.value.part(bind, protocol) },
            fallbacks = fallbacks.map { it.start },
            dropped = dropped.map { it.value.audit() },
            diverged = shown.divergedBy(dropped.map { it.value }),
            servedBy = fallbacks.lastOrNull()?.target,
        )
    }

    private fun close(block: Block) {
        if (block.closed) protocol.bad("content_block_stop twice for one block")
        block.closed = true
        arguments.remove(block.index)?.let { guard.arguments(it.toString()) }
        block.stopped(shown)
    }

    /**
     * One member of a block's start or delta: its name, then its value, a string joined to that
     * member's earlier fragments, anything else whole.
     */
    private fun screen(
        index: Int,
        key: String,
        value: JsonElement,
    ) {
        guard.check(key)
        if (value is JsonPrimitive && value.isString) {
            windows.getOrPut(index to key) { guard.Window() }.next(value.content)
        } else {
            guard.json(value)
        }
    }

    private fun block(
        event: JsonObject,
        what: String,
    ): Block {
        val index = protocol.index(event)
        return blocks.firstOrNull { it.index == index } ?: protocol.bad("$what for block $index, which never started")
    }

    /**
     * The assembled reply content.
     *
     * @property fallbacks the `fallback` blocks, verbatim.
     * @property dropped what is recorded of each block a fallback dropped ([Block.audit]): which
     *   block it was, never its content, so nothing of a declined attempt can be echoed.
     * @property diverged delta channels the dropped blocks had shown.
     * @property servedBy the model the last fallback switched to, or `null` without one.
     */
    class Assembled(
        val parts: List<Part>,
        val fallbacks: List<JsonObject>,
        val dropped: List<JsonObject>,
        val diverged: List<String>,
        val servedBy: String?,
    )
}

/**
 * What `onDelta` has shown, so a fallback that drops shown blocks can be reported. Reasoning of a
 * new thinking block is separated from earlier reasoning by a blank line, matching how the reply's
 * summaries join.
 */
internal class Shown(
    private val onDelta: (Delta) -> Unit,
) {
    private var reasoningShown = false
    private var sectionPending = false
    private val argsShown = mutableSetOf<ToolCallId>()
    private val callsShown = mutableSetOf<ToolCallId>()

    fun text(fragment: String) {
        if (fragment.isNotEmpty()) onDelta(Delta.Text(fragment))
    }

    fun newReasoningSection() {
        sectionPending = reasoningShown
    }

    /** A fallback block: the next attempt's reasoning starts without a separator, as its summaries do. */
    fun newAttempt() {
        reasoningShown = false
        sectionPending = false
    }

    fun reasoning(fragment: String) {
        if (fragment.isEmpty()) return
        val text = if (sectionPending) SECTION_SEPARATOR + fragment else fragment
        sectionPending = false
        reasoningShown = true
        onDelta(Delta.Reasoning(text))
    }

    fun started(
        id: ToolCallId,
        name: String,
    ) {
        callsShown += id
        onDelta(Delta.ToolCallStarted(id, name))
    }

    fun args(
        id: ToolCallId,
        fragment: String,
    ) {
        if (fragment.isEmpty()) return
        argsShown += id
        onDelta(Delta.ToolCallArgs(id, fragment))
    }

    /** Channels whose deltas no longer add up once [dropped] blocks leave the reply. */
    fun divergedBy(dropped: List<Block>): List<String> =
        listOfNotNull(
            "reasoning".takeIf { dropped.any { block -> block is Block.Thinking && block.text.isNotEmpty() } },
            "calls".takeIf { dropped.any { block -> block is Block.ToolUse && block.id in callsShown } },
            "args".takeIf { dropped.any { block -> block is Block.ToolUse && block.id in argsShown } },
        )

    private companion object {
        const val SECTION_SEPARATOR = "\n\n"
    }
}

/** The member naming a block's or a delta's type: protocol, not content. */
private const val TYPE = "type"

/** The delta type that streams call arguments, and its member carrying them. */
private const val INPUT_JSON_DELTA = "input_json_delta"
private const val PARTIAL_JSON = "partial_json"

/** Where a kotlinx.serialization parse error starts quoting its input. */
private const val QUOTED_INPUT = "JSON input:"
