/*
 * Adapted from Mika (https://github.com/wangzan101/Mika), file
 * core/src/main/kotlin/com/wangzan101/mika/core/model/LeakedToolMarkup.kt at commit 9cef375.
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: works on plain strings instead of Mika's reply types (the
 * adapter assigns call ids from its id source, not from a hash); adds a streaming preview cut-off;
 * leaves reasoning text alone; English comments.
 */
package dev.phoneagent.core.model.openai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Tool calls some models write into the answer text instead of the `tool_calls` channel
 * (model-layer §3.1): DeepSeek's DSML (`<｜DSML｜invoke name="…">…`) and Qwen-style
 * `<tool_call>{…}</tool_call>`. Shown as is they are garbage to the user and get recorded as an
 * answer.
 *
 * [scan] first recovers what it can as calls (DSML parameters become strings, or JSON values when
 * marked `string="false"`), then strips: unrecoverable DSML tags are removed, unrecoverable
 * `<tool_call>` blocks are kept so the reply does not shrink to a lead-in sentence that would be
 * taken as the final answer. Text without markup is returned unchanged, character for character.
 * A `<tool_call>` block with valid call JSON inside is treated as a call on purpose: showing markup
 * costs more than calling a tool, and unknown tools fail in the tool layer as usual.
 */
internal object LeakedToolMarkup {
    /** A recovered call: tool [name] and raw [argsJson]. */
    data class Invocation(
        val name: String,
        val argsJson: String,
    )

    /** [text] is what remains after stripping; [calls] were recovered from the markup (maybe none). */
    data class Scan(
        val text: String,
        val calls: List<Invocation>,
    )

    /** Bars come full-width (`｜`) or ASCII (`|`), one or two of them. */
    private const val BAR = "[｜|]"

    private fun bars(prefix: String) = prefix + BAR + "{1,2}\\s*DSML\\s*" + BAR + "{1,2}"

    private val INVOKE =
        Regex(
            bars("<") + "\\s*invoke\\s+name\\s*=\\s*\"([^\"]*)\"\\s*>(.*?)</" + BAR + "{1,2}\\s*DSML\\s*" + BAR +
                "{1,2}\\s*invoke\\s*>",
            RegexOption.DOT_MATCHES_ALL,
        )
    private val PARAM =
        Regex(
            bars("<") + "\\s*parameter\\s+name\\s*=\\s*\"([^\"]*)\"" +
                "(?:\\s+string\\s*=\\s*\"(true|false)\")?\\s*>(.*?)</" +
                BAR + "{1,2}\\s*DSML\\s*" + BAR + "{1,2}\\s*parameter\\s*>",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** Remaining paired or lone tags (outer `calls`, `function_calls` …). */
    private val TAG = Regex("</?" + BAR + "{1,2}\\s*DSML\\s*" + BAR + "{1,2}[^>]*>")

    private val TOOL_CALL_BLOCK = Regex("<tool_call>\\s*(.*?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val PIPE_TOOL_CALL_BLOCK =
        Regex(
            "<" + BAR + "{1,2}tool_call" + BAR + "{1,2}\\s*>(.*?)</" + BAR + "{1,2}tool_call" + BAR + "{1,2}\\s*>",
            RegexOption.DOT_MATCHES_ALL,
        )

    /**
     * Half a tag at the end of a streamed text (`<｜`, `<｜｜DSML`, `<｜｜DSML｜｜ invoke name="pho`). Only
     * a bar followed by a prefix of `DSML` counts, so ordinary text (`a<b`) is not cut.
     */
    private val PARTIAL_TAIL = Regex("^/?" + BAR + "{1,2}\\s*(?:D(?:S(?:M(?:L.*)?)?)?)?$", RegexOption.DOT_MATCHES_ALL)

    /** Where a markup tag may begin, for the streaming preview. */
    private val MARKUP_START = Regex("<\\s*/?\\s*(?:" + BAR + "|tool_call\\b)")
    private const val TOOL_CALL_TAG = "tool_call"

    private val EXTRA_BLANK_LINES = Regex("\n{3,}")
    private val json = Json { ignoreUnknownKeys = true }

    /** Scans an answer text: strips markup and recovers calls. Without markup: `Scan(text, [])`. */
    fun scan(text: String): Scan {
        val calls = mutableListOf<Invocation>()
        var rest = text
        if (rest.contains("DSML", ignoreCase = true)) {
            for (match in INVOKE.findAll(rest)) {
                val name = match.groupValues[1].trim()
                if (name.isNotEmpty()) calls += Invocation(name, paramsJson(match.groupValues[2]))
            }
            if (calls.isNotEmpty()) rest = rest.replace(INVOKE, " ")
            rest = TAG.replace(rest, " ")
        }
        rest = extractJsonBlocks(rest, TOOL_CALL_BLOCK, calls)
        rest = withoutPartialTail(extractJsonBlocks(rest, PIPE_TOOL_CALL_BLOCK, calls))
        if (calls.isEmpty() && rest == text) return Scan(text, emptyList())
        // Tidy the blank lines the removed tags leave; line breaks inside the text stay.
        val tidy = rest.lines().joinToString("\n") { it.trimEnd() }.replace(EXTRA_BLANK_LINES, "\n\n")
        return Scan(tidy.trim(), calls)
    }

    /** [text] without a trailing half DSML tag. */
    private fun withoutPartialTail(text: String): String {
        val lt = text.lastIndexOf('<')
        val tail = if (lt >= 0) text.substring(lt + 1) else ">"
        return if (!tail.contains('>') && PARTIAL_TAIL.matches(tail)) text.substring(0, lt) else text
    }

    /**
     * How much of a streamed [text] may be shown now: everything before the first possible markup
     * tag, and before a trailing `<` that could still become one. The final reply is scanned in
     * full; this only keeps half-received markup off the screen.
     */
    fun previewEnd(text: String): Int {
        val start = MARKUP_START.find(text)?.range?.first
        val lt = text.lastIndexOf('<')
        val tail = if (lt >= 0 && text.indexOf('>', lt) < 0) text.substring(lt + 1) else null
        val pending = tail != null && (tail.isEmpty() || PARTIAL_TAIL.matches(tail) || TOOL_CALL_TAG.startsWith(tail))
        return start ?: if (pending) lt else text.length
    }

    /** A DSML parameter block → arguments JSON; `string="false"` values are parsed as JSON. */
    private fun paramsJson(block: String): String {
        if (PARAM.find(block) == null) return "{}"
        return buildJsonObject {
            for (match in PARAM.findAll(block)) {
                val name = match.groupValues[1].trim()
                if (name.isEmpty()) continue
                val raw = match.groupValues[3]
                val isString = match.groupValues[2] != "false"
                put(name, if (isString) JsonPrimitive(raw) else jsonValue(raw))
            }
        }.toString()
    }

    private fun jsonValue(raw: String): JsonElement =
        try {
            json.parseToJsonElement(raw)
        } catch (ignoredNotJson: SerializationException) {
            // The model marked a plain value as non-string; keep it as text rather than lose it.
            JsonPrimitive(raw)
        }

    /**
     * Strips only the blocks that yield calls; unrecoverable ones stay, so the reply is not mistaken
     * for a short final answer.
     */
    private fun extractJsonBlocks(
        text: String,
        block: Regex,
        calls: MutableList<Invocation>,
    ): String =
        block.replace(text) { match ->
            val found = invocationsOf(match.groupValues[1])
            if (found.isEmpty()) {
                match.value
            } else {
                calls += found
                " "
            }
        }

    private fun invocationsOf(body: String): List<Invocation> {
        val element =
            try {
                json.parseToJsonElement(body)
            } catch (ignoredNotJson: SerializationException) {
                // Not a call payload: the block stays in the text (see extractJsonBlocks).
                return emptyList()
            }
        val items =
            when (element) {
                is JsonArray -> element.toList()
                is JsonObject -> listOf(element)
                is JsonPrimitive -> emptyList()
            }
        return items.mapNotNull(::invocationOf)
    }

    private fun invocationOf(item: JsonElement): Invocation? {
        val obj = item as? JsonObject
        val name = (obj?.get("name") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val args =
            when (val raw = obj?.get("arguments") ?: obj?.get("parameters")) {
                null -> "{}"
                is JsonPrimitive -> raw.contentOrNull ?: "{}"
                is JsonObject, is JsonArray -> raw.toString()
            }
        return if (name.isEmpty()) null else Invocation(name, args)
    }
}
