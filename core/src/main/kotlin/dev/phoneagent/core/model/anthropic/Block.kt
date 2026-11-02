package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One content block being assembled ([ContentBlocks]). What it shows goes through `Shown`, as it
 * comes; its content is never rewritten (a request secret in it rejects the reply: [EchoGuard]).
 */
internal sealed class Block(
    /** The `index` of its `content_block_start`. */
    val index: Int,
    /** The wire `type` of the block. */
    val type: String,
) {
    var closed = false

    /** Whether a mid-output fallback drops it when it comes before the last `fallback` block. */
    open val declinedAttempt: Boolean get() = false

    /** The id it claims among the reply's `tool_use` and `server_tool_use` ids, or `null`. */
    open val toolId: String? get() = null

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

    /** What `providerMetadata` records of it when a fallback drops it: never its content. */
    open fun audit(): JsonObject =
        buildJsonObject {
            put("index", index)
            put("type", type)
        }

    /** The reply part it becomes, or `null` for none; [protocol] refuses one that cannot be echoed. */
    abstract fun part(
        bind: Bind,
        protocol: Protocol,
    ): Part?

    class Text(
        index: Int,
        start: JsonObject,
    ) : Block(index, "text") {
        private val value = StringBuilder()
        private val first = start.str("text").orEmpty()

        override fun started(shown: Shown) = show(shown, first)

        override fun apply(
            type: String?,
            delta: JsonObject,
            shown: Shown,
            protocol: Protocol,
        ): Boolean {
            if (type != "text_delta") return false
            show(shown, delta.str("text") ?: protocol.bad("text_delta without text"))
            return true
        }

        private fun show(
            shown: Shown,
            fragment: String,
        ) {
            value.append(fragment)
            shown.text(fragment)
        }

        override fun part(
            bind: Bind,
            protocol: Protocol,
        ): Part? = value.toString().takeIf { it.isNotEmpty() }?.let(Part::Text)
    }

    /** Thinking, echoed verbatim with its signature; its text is shown as reasoning. */
    class Thinking(
        index: Int,
        private val start: JsonObject,
    ) : Block(index, "thinking") {
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

        private fun verbatim(): JsonObject {
            val fields = LinkedHashMap(start)
            fields["thinking"] = JsonPrimitive(text.toString())
            signature?.let { fields["signature"] = JsonPrimitive(it) }
            return JsonObject(fields)
        }

        /** Without a signature the server refuses the block when it is echoed (B3): not a valid reply. */
        override fun part(
            bind: Bind,
            protocol: Protocol,
        ): Part {
            if (signature.isNullOrEmpty()) protocol.bad("thinking block $index has no signature")
            return bind.reasoning(ReasoningKind.ANTHROPIC_THINKING, verbatim(), text.toString().ifEmpty { null })
        }
    }

    class Redacted(
        index: Int,
        private val start: JsonObject,
    ) : Block(index, "redacted_thinking") {
        override val declinedAttempt: Boolean get() = true

        override fun part(
            bind: Bind,
            protocol: Protocol,
        ): Part {
            if (start.str("data").isNullOrEmpty()) protocol.bad("redacted_thinking block $index has no data")
            return bind.reasoning(ReasoningKind.ANTHROPIC_REDACTED_THINKING, start, null)
        }
    }

    class Compaction(
        index: Int,
        start: JsonObject,
    ) : Block(index, "compaction") {
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

        override fun part(
            bind: Bind,
            protocol: Protocol,
        ): Part = bind.reasoning(ReasoningKind.ANTHROPIC_COMPACTION, JsonObject(fields), null)
    }

    /**
     * A client tool call. Its arguments are the raw `input_json_delta` text, or without deltas the
     * start's `input` (`{}` when there is none).
     */
    class ToolUse(
        index: Int,
        val id: ToolCallId,
        val name: String,
        private val startInput: JsonElement?,
    ) : Block(index, "tool_use") {
        private val args = StringBuilder()

        override val declinedAttempt: Boolean get() = true

        override val toolId: String get() = id.value

        override fun started(shown: Shown) = shown.started(id, name)

        override fun apply(
            type: String?,
            delta: JsonObject,
            shown: Shown,
            protocol: Protocol,
        ): Boolean {
            if (type != "input_json_delta") return false
            show(shown, delta.str("partial_json") ?: protocol.bad("input_json_delta without partial_json"))
            return true
        }

        /** A call whose input came whole in its start shows it now, so argument deltas still add up. */
        override fun stopped(shown: Shown) {
            if (args.isEmpty()) show(shown, startInput?.orNull()?.toString() ?: "{}")
        }

        private fun show(
            shown: Shown,
            fragment: String,
        ) {
            args.append(fragment)
            shown.args(id, fragment)
        }

        override fun audit(): JsonObject =
            buildJsonObject {
                put("index", index)
                put("type", type)
                put("id", id.value)
                put("name", name)
            }

        override fun part(
            bind: Bind,
            protocol: Protocol,
        ): Part = Part.ToolCall(id, name, args.toString())
    }

    /** A marker of a server fallback; [target] is the model the attempt after it ran on. */
    class Fallback(
        index: Int,
        val start: JsonObject,
    ) : Block(index, "fallback") {
        val target: String? get() = start.obj("to")?.str("model")

        override fun part(
            bind: Bind,
            protocol: Protocol,
        ): Part? = null
    }

    /**
     * A block type this adapter does not know (no server tools are enabled, so also
     * `server_tool_use`). It shows nothing and takes any delta, though [ContentBlocks] still checks
     * what it carries; whether it is refused waits for the end of the reply: before the last
     * `fallback` block it belongs to a declined attempt and is dropped, anywhere else it is a
     * [dev.phoneagent.core.model.ModelError.BadResponse].
     */
    class Internal(
        index: Int,
        type: String,
        private val start: JsonObject,
    ) : Block(index, type) {
        override val declinedAttempt: Boolean get() = true

        override val toolId: String? get() = start.str("id")?.takeIf { type == "server_tool_use" && it.isNotBlank() }

        override fun apply(
            type: String?,
            delta: JsonObject,
            shown: Shown,
            protocol: Protocol,
        ): Boolean = true

        override fun audit(): JsonObject =
            buildJsonObject {
                put("index", index)
                put("type", type)
                start.str("id")?.let { put("id", it) }
                start.str("name")?.let { put("name", it) }
            }

        override fun part(
            bind: Bind,
            protocol: Protocol,
        ): Part = protocol.bad("block $index has unsupported type '$type'")
    }

    companion object {
        /** The block a `content_block_start` opens. */
        fun of(
            index: Int,
            start: JsonObject,
            protocol: Protocol,
        ): Block =
            when (val type = start.str("type")) {
                "text" -> Text(index, start)
                "thinking" -> Thinking(index, start)
                "redacted_thinking" -> Redacted(index, start)
                "compaction" -> Compaction(index, start)
                "tool_use" -> toolUse(index, start, protocol)
                "fallback" -> Fallback(index, start)
                else -> Internal(index, type.toString(), start)
            }

        private fun toolUse(
            index: Int,
            start: JsonObject,
            protocol: Protocol,
        ): ToolUse {
            val id = start.str("id")?.takeIf { it.isNotBlank() } ?: protocol.bad("tool_use block $index without an id")
            val name =
                start.str("name")?.takeIf { it.isNotBlank() } ?: protocol.bad("tool_use block $index without a name")
            return ToolUse(index, ToolCallId(id), name, start["input"])
        }
    }
}
