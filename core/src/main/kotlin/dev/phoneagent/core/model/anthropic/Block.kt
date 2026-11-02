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
 * One content block being assembled ([ContentBlocks]). What it shows goes through `Shown`; text and
 * arguments are masked on the way ([Redactor.Window]), so the shown text and the part agree.
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

    /** Shows what only the stop makes complete, including text held back by a [Redactor.Window]. */
    open fun stopped(shown: Shown) = Unit

    /** What would be echoed verbatim (no masking possible), or `null` for none. */
    open fun verbatim(): JsonObject? = null

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
        private val window: Redactor.Window,
    ) : Block(index, "text") {
        private val first = start.str("text").orEmpty()
        private val value = StringBuilder()

        override fun started(shown: Shown) = show(shown, window.push(first))

        override fun apply(
            type: String?,
            delta: JsonObject,
            shown: Shown,
            protocol: Protocol,
        ): Boolean {
            if (type != "text_delta") return false
            show(shown, window.push(delta.str("text") ?: protocol.bad("text_delta without text")))
            return true
        }

        override fun stopped(shown: Shown) = show(shown, window.finish())

        private fun show(
            shown: Shown,
            visible: String,
        ) {
            value.append(visible)
            shown.text(visible)
        }

        override fun part(
            bind: Bind,
            protocol: Protocol,
        ): Part? = value.toString().takeIf { it.isNotEmpty() }?.let(Part::Text)
    }

    /**
     * Thinking is echoed verbatim with its signature, so a secret in it cannot be masked: the
     * preview holds back what could be the start of one, and a secret that completes rejects the
     * reply before any of it is shown.
     */
    class Thinking(
        index: Int,
        private val start: JsonObject,
        private val protocol: Protocol,
    ) : Block(index, "thinking") {
        val text = StringBuilder(start.str("thinking").orEmpty())
        private var signature: String? = start.str("signature")
        private val preview = protocol.window()

        override val declinedAttempt: Boolean get() = true

        override fun started(shown: Shown) {
            shown.newReasoningSection()
            show(shown, preview.push(text.toString()))
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
                    show(shown, preview.push(fragment))
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

        override fun stopped(shown: Shown) = show(shown, preview.finish())

        private fun show(
            shown: Shown,
            visible: String,
        ) {
            if (preview.found) protocol.echoed()
            shown.reasoning(visible)
        }

        override fun verbatim(): JsonObject {
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

        override fun verbatim(): JsonObject = start

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

        override fun verbatim(): JsonObject = JsonObject(fields)

        override fun part(
            bind: Bind,
            protocol: Protocol,
        ): Part = bind.reasoning(ReasoningKind.ANTHROPIC_COMPACTION, verbatim(), null)
    }

    /**
     * A client tool call. Its arguments are the raw `input_json_delta` text, or without deltas the
     * start's `input` (`{}` when there is none), masked by a JSON-aware [Redactor.Window].
     */
    class ToolUse(
        index: Int,
        val id: ToolCallId,
        val name: String,
        private val startInput: JsonElement?,
        private val window: Redactor.Window,
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
            show(shown, window.push(delta.str("partial_json") ?: protocol.bad("input_json_delta without partial_json")))
            return true
        }

        /** A call whose input came whole in its start shows it now, so argument deltas still add up. */
        override fun stopped(shown: Shown) {
            show(shown, window.finish())
            if (args.isEmpty()) show(shown, window.push(startInput?.orNull()?.toString() ?: "{}") + window.finish())
        }

        private fun show(
            shown: Shown,
            visible: String,
        ) {
            args.append(visible)
            shown.args(id, visible)
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
     * `server_tool_use`). It shows nothing and takes any delta; whether it is refused waits for
     * the end of the reply: before the last `fallback` block it belongs to a declined attempt and
     * is dropped, anywhere else it is a [dev.phoneagent.core.model.ModelError.BadResponse].
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
                "text" -> Text(index, start, protocol.window())
                "thinking" -> Thinking(index, start, protocol)
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
            return ToolUse(index, ToolCallId(id), name, start["input"], protocol.window(json = true))
        }
    }
}
