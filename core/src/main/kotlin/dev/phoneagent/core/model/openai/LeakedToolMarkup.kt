/*
 * Adapted from Mika (https://github.com/wangzan101/Mika), file
 * core/src/main/kotlin/com/wangzan101/mika/core/model/LeakedToolMarkup.kt at commit 9cef375.
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: works on plain strings instead of Mika's reply types (the
 * adapter assigns call ids from its id source, not from a hash); recovers only complete calls of
 * declared tools that end the text, outside code, and otherwise keeps the text untouched instead
 * of stripping markup; adds a streaming preview cut-off; leaves reasoning text alone; English
 * comments.
 */
package dev.phoneagent.core.model.openai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Tool calls some models write into the answer text instead of the `tool_calls` channel
 * (model-layer §3.1): DeepSeek's DSML (`<｜DSML｜invoke name="…">…`) and Qwen-style
 * `<tool_call>{…}</tool_call>`. Recovery is all or nothing ([recover]): the text either ends in
 * nothing but complete calls of declared tools, which become calls, or stays exactly as written.
 * Markup in code, followed by more prose, naming another tool or half written is an example or a
 * mistake, not a call.
 */
internal object LeakedToolMarkup {
    /** A recovered call: tool [name] and raw [argsJson] (a JSON object). */
    data class Invocation(
        val name: String,
        val argsJson: String,
    )

    /** [text] is the answer before the markup, without trailing whitespace; [calls] has at least one call. */
    data class Recovery(
        val text: String,
        val calls: List<Invocation>,
    )

    /** Bars come full-width (`｜`) or ASCII (`|`), one or two of them. */
    private const val BAR = "[｜|]"
    private const val DSML = BAR + "{1,2}\\s*DSML\\s*" + BAR + "{1,2}"

    private val INVOKE =
        Regex(
            "<" + DSML + "\\s*invoke\\s+name\\s*=\\s*\"([^\"]*)\"\\s*>(.*?)</" + DSML + "\\s*invoke\\s*>",
            RegexOption.DOT_MATCHES_ALL,
        )
    private val PARAM =
        Regex(
            "<" + DSML + "\\s*parameter\\s+name\\s*=\\s*\"([^\"]*)\"" +
                "(?:\\s+string\\s*=\\s*\"(true|false)\")?\\s*>(.*?)</" + DSML + "\\s*parameter\\s*>",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** The DSML wrapper around invocations. */
    private val WRAPPER = Regex("</?" + DSML + "\\s*function_calls\\s*>")

    private val TOOL_CALL_BLOCK = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val PIPE_TOOL_CALL_BLOCK =
        Regex(
            "<" + BAR + "{1,2}tool_call" + BAR + "{1,2}\\s*>(.*?)</" + BAR + "{1,2}tool_call" + BAR + "{1,2}\\s*>",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** What each kind of markup piece holds: calls, nothing (a wrapper), or `null` when it is not a whole call. */
    private val PIECES: List<Pair<Regex, (MatchResult) -> List<Invocation>?>> =
        listOf(
            WRAPPER to { _ -> emptyList() },
            INVOKE to ::dsmlCall,
            TOOL_CALL_BLOCK to ::jsonCalls,
            PIPE_TOOL_CALL_BLOCK to ::jsonCalls,
        )

    /** A closed code fence; what remains outside them tells whether a position is in code. */
    private val FENCED = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private const val FENCE = "```"

    /**
     * Half a tag at the end of a streamed text (`<｜`, `<｜｜DSML`, `<｜｜DSML｜｜ invoke name="pho`). Only
     * a bar followed by a prefix of `DSML` counts, so ordinary text (`a<b`) is not cut.
     */
    private val PARTIAL_TAIL = Regex("^/?" + BAR + "{1,2}\\s*(?:D(?:S(?:M(?:L.*)?)?)?)?$", RegexOption.DOT_MATCHES_ALL)

    /** Where a markup tag may begin, for the streaming preview. */
    private val MARKUP_START = Regex("<\\s*/?\\s*(?:" + BAR + "|tool_call\\b)")
    private const val TOOL_CALL_TAG = "tool_call"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The calls leaked at the end of [text], or `null` when [text] must stay as written. Recovered
     * only when everything from the first markup tag to the end is complete calls (DSML
     * invocations with their closing tag, `<tool_call>` JSON with a name and object arguments),
     * DSML wrapper tags and whitespace; that tag is outside code; and every name is in [tools].
     */
    fun recover(
        text: String,
        tools: Set<String>,
    ): Recovery? {
        val start = MARKUP_START.find(text)?.range?.first ?: return null
        val before = text.substring(0, start)
        val calls = if (insideCode(before)) null else callsIn(text.substring(start))
        return calls
            ?.takeIf { found -> found.isNotEmpty() && found.all { it.name in tools } }
            ?.let { Recovery(before.trimEnd(), it) }
    }

    /**
     * How much of a streamed [text] may be shown now: everything before the first possible markup
     * tag, and before a trailing `<` or `</` that could still become one, so no split of the stream
     * shows the start of markup [recover] later removes.
     */
    fun previewEnd(text: String): Int {
        val start = MARKUP_START.find(text)?.range?.first
        val lt = text.lastIndexOf('<')
        val tail = if (lt >= 0 && text.indexOf('>', lt) < 0) text.substring(lt + 1) else null
        val pending =
            tail != null &&
                (tail.isEmpty() || tail == "/" || PARTIAL_TAIL.matches(tail) || TOOL_CALL_TAG.startsWith(tail))
        return start ?: if (pending) lt else text.length
    }

    /** Whether the end of [before] is inside a code fence or an inline code span. */
    private fun insideCode(before: String): Boolean {
        val outside = FENCED.replace(before, "")
        return outside.contains(FENCE) || outside.count { it == '`' } % 2 == 1
    }

    /** The calls of [region] when it is nothing but markup pieces and whitespace, else `null`. */
    private fun callsIn(region: String): List<Invocation>? {
        val pieces =
            PIECES
                .flatMap { (regex, read) -> regex.findAll(region).map { it.range to read(it) }.toList() }
                .sortedBy { it.first.first }
        val whole = pieces.all { it.second != null } && tiles(region, pieces.map { it.first })
        return if (whole) pieces.flatMap { it.second.orEmpty() } else null
    }

    /** Whether sorted [ranges] cover [text] without overlapping, leaving only whitespace between and around them. */
    private fun tiles(
        text: String,
        ranges: List<IntRange>,
    ): Boolean {
        var at = 0
        for (range in ranges) {
            if (range.first < at || text.substring(at, range.first).isNotBlank()) return false
            at = range.last + 1
        }
        return text.substring(at).isBlank()
    }

    /** One DSML invocation; its body must be nothing but distinct, named parameters. */
    private fun dsmlCall(invoke: MatchResult): List<Invocation>? {
        val name = invoke.groupValues[1].trim()
        val body = invoke.groupValues[2]
        val params = PARAM.findAll(body).toList()
        val names = params.map { it.groupValues[1].trim() }
        val complete = name.isNotEmpty() && names.none { it.isEmpty() } && names.toSet().size == names.size
        return if (complete &&
            tiles(body, params.map { it.range })
        ) {
            listOf(Invocation(name, paramsJson(params)))
        } else {
            null
        }
    }

    /** DSML parameters → arguments JSON; `string="false"` values are parsed as JSON (text when they are not). */
    private fun paramsJson(params: List<MatchResult>): String =
        buildJsonObject {
            for (param in params) {
                val raw = param.groupValues[3]
                val isString = param.groupValues[2] != "false"
                put(
                    param.groupValues[1].trim(),
                    if (isString) JsonPrimitive(raw) else parsed(raw) ?: JsonPrimitive(raw),
                )
            }
        }.toString()

    /** A `<tool_call>` block: one call object or a non-empty array of them, each complete. */
    private fun jsonCalls(block: MatchResult): List<Invocation>? {
        val items =
            when (val element = parsed(block.groupValues[1])) {
                is JsonArray -> element.toList()
                is JsonObject -> listOf(element)
                is JsonPrimitive, null -> emptyList()
            }
        val calls = items.map(::jsonCall)
        return calls.filterNotNull().takeIf { it.isNotEmpty() && it.size == calls.size }
    }

    /** `{"name": …, "arguments" | "parameters": {…} or "{…}"}`, else `null`. */
    private fun jsonCall(item: JsonElement): Invocation? {
        val obj = item as? JsonObject
        val name =
            (obj?.get("name") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                .orEmpty()
        val args = argsOf(obj?.get("arguments") ?: obj?.get("parameters"))
        return if (name.isEmpty() || args == null) null else Invocation(name, args)
    }

    /** Arguments as JSON object text: an object, or a string holding one (kept as the model wrote it). */
    private fun argsOf(raw: JsonElement?): String? =
        when (raw) {
            is JsonObject -> raw.toString()
            is JsonPrimitive -> raw.takeIf { it.isString }?.content?.takeIf { parsed(it) is JsonObject }
            is JsonArray, null -> null
        }

    /** [text] as JSON, or `null` when it is not JSON. */
    private fun parsed(text: String): JsonElement? =
        try {
            json.parseToJsonElement(text)
        } catch (ignoredNotJson: SerializationException) {
            // Not JSON is an answer for the callers (plain value, or not a call), not an error.
            null
        }
}
