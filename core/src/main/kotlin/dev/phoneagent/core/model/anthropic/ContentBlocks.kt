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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reports protocol violations as [ModelError.BadResponse] and masks what the server echoed, both
 * with the request's [redactor].
 */
internal class Protocol(
    private val redactor: Redactor,
) {
    fun bad(detail: String): Nothing = throw ModelError.BadResponse(redactor.text(detail))

    /**
     * Rejects a reply that repeats this request's credential where it cannot be masked (a block
     * echoed verbatim): the message is fixed, so nothing of the reply is in it (model-layer §2).
     */
    fun echoed(): Nothing = throw ModelError.BadResponse(ECHO_REJECTED)

    /** [echoed] when a string or key anywhere in [block] holds a request secret. */
    fun requireUnechoed(block: JsonObject) {
        if (echoes(block)) echoed()
    }

    private fun echoes(value: JsonElement): Boolean =
        when (value) {
            is JsonObject -> value.any { (key, member) -> redactor.echoes(key) || echoes(member) }
            is JsonArray -> value.any(::echoes)
            is JsonPrimitive -> value.isString && redactor.echoes(value.content)
        }

    /** A [Redactor.Window] over this request's secrets. */
    fun window(json: Boolean = false): Redactor.Window = redactor.window(json)

    /** [value] with every string in it masked; structure, keys and other values are kept. */
    fun mask(value: JsonObject): JsonObject = JsonObject(value.mapValues { (_, member) -> masked(member) })

    private fun masked(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> mask(value)
            is JsonArray -> JsonArray(value.map(::masked))
            is JsonPrimitive -> if (value.isString) JsonPrimitive(redactor.text(value.content)) else value
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
 */
internal class ContentBlocks(
    private val protocol: Protocol,
    onDelta: (Delta) -> Unit,
) {
    private val blocks = mutableListOf<Block>()
    private val toolIds = mutableSetOf<String>()
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
        val type = delta.str("type")
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

    /** The block still open, or `null`. */
    fun open(): Int? = blocks.lastOrNull()?.takeUnless { it.closed }?.index

    /**
     * The reply parts, in order. A mid-output fallback drops the declined attempts' thinking, tool
     * calls and unknown blocks before the last `fallback` block (model-migration: echoing fallback
     * turns); `fallback` blocks are only markers. A request secret in any block that would be echoed
     * verbatim, dropped or not, rejects the reply; so does an unknown block anywhere else, or a kept
     * thinking block without a signature.
     */
    fun assemble(bind: Bind): Assembled {
        blocks.forEach { block -> block.verbatim()?.let(protocol::requireUnechoed) }
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
        block.stopped(shown)
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

/** The message of a reply rejected for repeating the request's credential ([Protocol.echoed]). */
internal const val ECHO_REJECTED: String =
    "the reply repeats this request's credential in a block that must be echoed verbatim"

/** Where a kotlinx.serialization parse error starts quoting its input. */
private const val QUOTED_INPUT = "JSON input:"
