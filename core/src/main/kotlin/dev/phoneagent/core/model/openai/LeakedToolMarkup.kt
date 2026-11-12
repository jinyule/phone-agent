/*
 * Adapted from Mika (https://github.com/wangzan101/Mika), file
 * core/src/main/kotlin/com/wangzan101/mika/core/model/LeakedToolMarkup.kt at commit 9cef375.
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: works on plain strings instead of Mika's reply types (the
 * adapter assigns call ids from its id source, not from a hash); recovers only a text that is
 * nothing but complete calls of declared tools in one enabled format, and otherwise keeps the text
 * untouched instead of stripping markup; DSML only inside its function_calls wrapper; no pipe
 * variant of the tool_call tag; leaves reasoning text alone; pairs each open tag with the first
 * close tag after it in one pass instead of a lazy pattern per block; English comments.
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
 * (model-layer §3.1), in the [LeakedCallFormat]s: DeepSeek's DSML
 * (`<｜DSML｜function_calls><｜DSML｜invoke name="…">…`) and Qwen-style `<tool_call>{…}</tool_call>`.
 * Recovery is all or nothing ([recover]): the whole text, apart from whitespace, is complete calls
 * of declared tools in one format, or it stays exactly as written. Any words around the markup
 * (an explanation, an example, a quote, "do not run this") make it text, not a call.
 */
internal object LeakedToolMarkup {
    /** A recovered call: tool [name] and raw [argsJson] (a JSON object). */
    data class Invocation(
        val name: String,
        val argsJson: String,
    )

    /** The block of each format, and the calls a block holds (`null` when it is not wholly calls). */
    private val BLOCKS: Map<LeakedCallFormat, Pair<Tag, (Tagged) -> List<Invocation>?>> =
        mapOf(
            LeakedCallFormat.DSML to (Tag.FUNCTION_CALLS to ::dsmlCalls),
            LeakedCallFormat.TOOL_CALL_TAG to (Tag.TOOL_CALL to ::jsonCalls),
        )

    private val json = Json { ignoreUnknownKeys = true }

    /** The only key sets a `<tool_call>` call object may have. */
    private val SHELLS = setOf(setOf("name", "arguments"), setOf("name", "parameters"))

    /**
     * The calls [text] consists of, or `null` when it must stay as written. Recovered only when,
     * for one of [formats], the text is nothing but its blocks and whitespace, every block is
     * wholly complete calls (DSML invocations with distinct named parameters; `<tool_call>` JSON
     * with a name and object arguments and no other key), and every name is in [tools]. A text mixing formats, or
     * holding anything else, is kept.
     */
    fun recover(
        text: CharSequence,
        tools: Set<String>,
        formats: Set<LeakedCallFormat>,
    ): List<Invocation>? =
        formats
            .firstNotNullOfOrNull { callsIn(text, it) }
            ?.takeIf { found -> found.isNotEmpty() && found.all { it.name in tools } }

    /** The calls of [text] when it is nothing but whole blocks of [format] and whitespace, else `null`. */
    private fun callsIn(
        text: CharSequence,
        format: LeakedCallFormat,
    ): List<Invocation>? {
        val (tag, read) = BLOCKS.getValue(format)
        val blocks = pairs(text, tag)
        val calls = blocks.map(read)
        val whole = calls.all { it != null } && tiles(text, blocks.map { it.range })
        return if (whole) calls.flatMap { it.orEmpty() } else null
    }

    /** Whether sorted [ranges] cover [text] without overlapping, leaving only whitespace between and around them. */
    private fun tiles(
        text: CharSequence,
        ranges: List<IntRange>,
    ): Boolean {
        var at = 0
        for (range in ranges) {
            if (range.first < at || text.substring(at, range.first).isNotBlank()) return false
            at = range.last + 1
        }
        return text.substring(at).isBlank()
    }

    /** A DSML `function_calls` block: one or more invocations and whitespace, each complete. */
    private fun dsmlCalls(block: Tagged): List<Invocation>? {
        val body = block.body
        val invokes = pairs(body, Tag.INVOKE)
        val calls = invokes.map(::dsmlCall)
        val whole = calls.isNotEmpty() && calls.all { it != null } && tiles(body, invokes.map { it.range })
        return if (whole) calls.filterNotNull() else null
    }

    /** One DSML invocation; its body must be nothing but distinct, named parameters. */
    private fun dsmlCall(invoke: Tagged): Invocation? {
        val name = invoke.open.groupValues[1].trim()
        val body = invoke.body
        val params = pairs(body, Tag.PARAMETER)
        val names = params.map { it.open.groupValues[1].trim() }
        val complete = name.isNotEmpty() && names.none { it.isEmpty() } && names.toSet().size == names.size
        return if (complete &&
            tiles(body, params.map { it.range })
        ) {
            Invocation(name, paramsJson(params))
        } else {
            null
        }
    }

    /** DSML parameters → arguments JSON; `string="false"` values are parsed as JSON (text when they are not). */
    private fun paramsJson(params: List<Tagged>): String =
        buildJsonObject {
            for (param in params) {
                val raw = param.body.toString()
                val isString = param.open.groupValues[2] != "false"
                put(
                    param.open.groupValues[1].trim(),
                    if (isString) JsonPrimitive(raw) else parsed(raw) ?: JsonPrimitive(raw),
                )
            }
        }.toString()

    /** A `<tool_call>` block: one call object or a non-empty array of them, each complete. */
    private fun jsonCalls(block: Tagged): List<Invocation>? {
        val items =
            when (val element = parsed(block.body.toString())) {
                is JsonArray -> element.toList()
                is JsonObject -> listOf(element)
                is JsonPrimitive, null -> emptyList()
            }
        val calls = items.map(::jsonCall)
        return calls.filterNotNull().takeIf { it.isNotEmpty() && it.size == calls.size }
    }

    /**
     * `{"name": …, "arguments" | "parameters": {…} or "{…}"}`, else `null`. The shell holds exactly
     * those two keys ([SHELLS]): any other key, or both argument keys, keeps the text. What the
     * arguments hold is not looked at.
     */
    private fun jsonCall(item: JsonElement): Invocation? {
        val obj = (item as? JsonObject)?.takeIf { it.keys in SHELLS }
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

/** Bars come full-width (`｜`) or ASCII (`|`), one or two of them. */
private const val BAR = "[｜|]"
private const val DSML = BAR + "{1,2}\\s*DSML\\s*" + BAR + "{1,2}"

/** A tag of the markup: the open tag (its attributes as groups) and the close tag. */
internal enum class Tag(
    val open: Regex,
    val close: Regex,
) {
    FUNCTION_CALLS(Regex("<" + DSML + "\\s*function_calls\\s*>"), Regex("</" + DSML + "\\s*function_calls\\s*>")),
    INVOKE(
        Regex("<" + DSML + "\\s*invoke\\s+name\\s*=\\s*\"([^\"]*)\"\\s*>"),
        Regex("</" + DSML + "\\s*invoke\\s*>"),
    ),
    PARAMETER(
        Regex("<" + DSML + "\\s*parameter\\s+name\\s*=\\s*\"([^\"]*)\"(?:\\s+string\\s*=\\s*\"(true|false)\")?\\s*>"),
        Regex("</" + DSML + "\\s*parameter\\s*>"),
    ),
    TOOL_CALL(Regex("<tool_call>"), Regex("</tool_call>")),
}

/** An [open] tag, what it holds ([body]) and its close tag; [range] covers all three. */
internal class Tagged(
    val open: MatchResult,
    val body: CharSequence,
    val range: IntRange,
)

/**
 * Each [tag] in [text] from the left, as `<open>(.*?)</close>` finds them: an open tag and the
 * first close tag after it, then the next open tag after that close tag. An open tag with no close
 * tag after it ends the search, since no later open tag has one either. Each character is looked
 * at a bounded number of times, however many open tags have no close tag.
 */
internal fun pairs(
    text: CharSequence,
    tag: Tag,
): List<Tagged> {
    val found = mutableListOf<Tagged>()
    var opened = tag.open.find(text)
    while (opened != null) {
        val closed = tag.close.find(text, opened.range.last + 1) ?: break
        val body = text.subSequence(opened.range.last + 1, closed.range.first)
        found += Tagged(opened, body, opened.range.first..closed.range.last)
        opened = tag.open.find(text, closed.range.last + 1)
    }
    return found
}
