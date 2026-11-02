package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.TreeMap

/** Reports protocol violations as [ModelError.BadResponse], masked with the request's [redactor]. */
internal class Protocol(
    private val redactor: Redactor,
) {
    fun bad(detail: String): Nothing = throw ModelError.BadResponse(redactor.text(detail))

    /** [text] as JSON; [what] names it in the error. */
    fun parse(
        text: String,
        what: String,
    ): JsonElement =
        try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ModelError.BadResponse(redactor.text("$what is not JSON: ${e.message}"), redactor.cause(e))
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
 * The content blocks of one reply by index, and what `onDelta` has shown of them ([MessagesStream]
 * states the contract). Each block takes only its own delta types.
 */
internal class ContentBlocks(
    private val protocol: Protocol,
    onDelta: (Delta) -> Unit,
) {
    private val blocks = TreeMap<Int, Block>()
    private val shown = Shown(onDelta)

    /** `content_block_start`. */
    fun start(event: JsonObject) {
        val index = protocol.index(event)
        if (index in blocks) protocol.bad("block $index started twice")
        val start = event.obj("content_block") ?: protocol.bad("block $index without content_block")
        val block = Block.of(index, start, protocol)
        blocks[index] = block
        block.started(shown)
    }

    /** `content_block_delta`. */
    fun delta(event: JsonObject) {
        val block = block(event, "content_block_delta")
        if (block.closed) protocol.bad("delta for block ${protocol.index(event)} after its content_block_stop")
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
        close(blocks.getValue(index))
    }

    /** The first block still open, or `null`. */
    fun open(): Int? = blocks.entries.firstOrNull { !it.value.closed }?.key

    /**
     * The reply parts, in index order. A mid-output fallback drops the declined attempt's
     * thinking and tool calls before the last `fallback` block; `fallback` blocks are only markers.
     */
    fun assemble(bind: Bind): Assembled {
        val ordered = blocks.values.toList()
        val boundary = ordered.indexOfLast { it is Block.Fallback }
        val discarded = ordered.filterIndexed { index, block -> index < boundary && block.declinedAttempt }
        return Assembled(
            parts = ordered.filter { it !in discarded }.mapNotNull { it.part(bind) },
            fallbacks = ordered.filterIsInstance<Block.Fallback>().map { it.start },
            discarded = discarded.size,
            diverged = shown.divergedBy(discarded),
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
        return blocks[index] ?: protocol.bad("$what for block $index, which never started")
    }

    /**
     * The assembled reply content.
     *
     * @property fallbacks the `fallback` blocks, verbatim.
     * @property discarded how many blocks of a declined attempt were dropped.
     * @property diverged delta channels the dropped blocks had shown.
     */
    class Assembled(
        val parts: List<Part>,
        val fallbacks: List<JsonObject>,
        val discarded: Int,
        val diverged: List<String>,
    )
}

/** One content block being assembled. */
internal sealed class Block(
    /** The wire `type` of the block. */
    val type: String,
) {
    var closed = false

    /** Whether a mid-output fallback drops it when it comes before the last `fallback` block. */
    open val declinedAttempt: Boolean get() = false

    /** Shows what the start carried. */
    open fun started(shown: Shown) = Unit

    /** Applies a delta of [type]; `false` when this block takes no such delta. */
    open fun apply(
        type: String?,
        delta: JsonObject,
        shown: Shown,
        protocol: Protocol,
    ): Boolean = false

    /** Shows what only the stop makes complete. */
    open fun stopped(shown: Shown) = Unit

    /** The reply part it becomes, or `null` for none. */
    abstract fun part(bind: Bind): Part?

    class Text(
        start: JsonObject,
    ) : Block("text") {
        private val value = StringBuilder(start.str("text").orEmpty())

        override fun started(shown: Shown) = shown.text(value.toString())

        override fun apply(
            type: String?,
            delta: JsonObject,
            shown: Shown,
            protocol: Protocol,
        ): Boolean {
            if (type != "text_delta") return false
            val fragment = delta.str("text") ?: protocol.bad("text_delta without text")
            value.append(fragment)
            shown.text(fragment)
            return true
        }

        override fun part(bind: Bind): Part? = value.toString().takeIf { it.isNotEmpty() }?.let(Part::Text)
    }

    class Thinking(
        private val start: JsonObject,
    ) : Block("thinking") {
        val text = StringBuilder(start.str("thinking").orEmpty())
        private var signature: String? = start.str("signature")

        override val declinedAttempt: Boolean get() = true

        override fun started(shown: Shown) {
            shown.newReasoningSection()
            shown.reasoning(text.toString())
        }

        override fun apply(
            type: String?,
            delta: JsonObject,
            shown: Shown,
            protocol: Protocol,
        ): Boolean {
            when (type) {
                "thinking_delta" -> {
                    val fragment = delta.str("thinking") ?: protocol.bad("thinking_delta without thinking")
                    text.append(fragment)
                    shown.reasoning(fragment)
                }

                "signature_delta" -> {
                    signature = delta.str("signature") ?: protocol.bad("signature_delta without signature")
                }

                else -> {
                    return false
                }
            }
            return true
        }

        override fun part(bind: Bind): Part {
            val fields = LinkedHashMap(start)
            fields["thinking"] = JsonPrimitive(text.toString())
            signature?.let { fields["signature"] = JsonPrimitive(it) }
            return bind.reasoning(
                ReasoningKind.ANTHROPIC_THINKING,
                JsonObject(fields),
                text.toString().ifEmpty { null },
            )
        }
    }

    class Redacted(
        private val start: JsonObject,
    ) : Block("redacted_thinking") {
        override val declinedAttempt: Boolean get() = true

        override fun part(bind: Bind): Part = bind.reasoning(ReasoningKind.ANTHROPIC_REDACTED_THINKING, start, null)
    }

    class Compaction(
        start: JsonObject,
    ) : Block("compaction") {
        private val fields = LinkedHashMap(start)

        /** Each compaction delta carries the whole value, as the SDK's accumulator reads it. */
        override fun apply(
            type: String?,
            delta: JsonObject,
            shown: Shown,
            protocol: Protocol,
        ): Boolean {
            if (type != "compaction_delta") return false
            delta["content"]?.let { fields["content"] = it }
            delta["encrypted_content"]?.let { fields["encrypted_content"] = it }
            return true
        }

        override fun part(bind: Bind): Part =
            bind.reasoning(ReasoningKind.ANTHROPIC_COMPACTION, JsonObject(fields), null)
    }

    class ToolUse(
        val id: ToolCallId,
        val name: String,
        private val startInput: JsonElement?,
    ) : Block("tool_use") {
        val args = StringBuilder()

        override val declinedAttempt: Boolean get() = true

        override fun started(shown: Shown) = shown.started(id, name)

        override fun apply(
            type: String?,
            delta: JsonObject,
            shown: Shown,
            protocol: Protocol,
        ): Boolean {
            if (type != "input_json_delta") return false
            val fragment = delta.str("partial_json") ?: protocol.bad("input_json_delta without partial_json")
            args.append(fragment)
            shown.args(id, fragment)
            return true
        }

        /** A call whose input came whole in its start shows it now, so argument deltas still add up. */
        override fun stopped(shown: Shown) {
            if (args.isEmpty()) shown.args(id, argsJson())
        }

        /** The raw argument text: the streamed fragments, else the start's `input`. */
        private fun argsJson(): String = args.toString().ifEmpty { startInput?.orNull()?.toString() ?: "{}" }

        override fun part(bind: Bind): Part = Part.ToolCall(id, name, argsJson())
    }

    class Fallback(
        val start: JsonObject,
    ) : Block("fallback") {
        override fun part(bind: Bind): Part? = null
    }

    companion object {
        /** The block a `content_block_start` opens; unknown types are refused (no server tools are enabled). */
        fun of(
            index: Int,
            start: JsonObject,
            protocol: Protocol,
        ): Block =
            when (val type = start.str("type")) {
                "text" -> Text(start)
                "thinking" -> Thinking(start)
                "redacted_thinking" -> Redacted(start)
                "compaction" -> Compaction(start)
                "tool_use" -> toolUse(index, start, protocol)
                "fallback" -> Fallback(start)
                else -> protocol.bad("block $index has unsupported type '$type'")
            }

        private fun toolUse(
            index: Int,
            start: JsonObject,
            protocol: Protocol,
        ): ToolUse {
            val id = start.str("id")?.takeIf { it.isNotBlank() } ?: protocol.bad("tool_use block $index without an id")
            val name =
                start.str("name")?.takeIf { it.isNotBlank() } ?: protocol.bad("tool_use block $index without a name")
            return ToolUse(ToolCallId(id), name, start["input"])
        }
    }
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
        if (callsShown.add(id)) onDelta(Delta.ToolCallStarted(id, name))
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
