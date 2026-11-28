package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Part
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * `{{fromRequest:REGEX}}` templates in the tool arguments of a scripted reply (WP08 design §3): a
 * reply that refers to what the request showed (the ref of the row with "张三") keeps working when
 * the screen format renumbers refs, and a refresh re-resolves it.
 *
 * Grammar: a template is a whole JSON string value of `argsJson`, `"{{fromRequest:REGEX}}"`, with
 * REGEX written JSON-escaped like any string value, and at least one group. It resolves to group 1
 * of the last match of REGEX in [searchText] of the live request, inserted as a JSON string. The
 * rest of `argsJson` is kept byte for byte (never re-encoded: kotlinx rewrites numbers).
 */
object RequestTemplates {
    private val TEMPLATE = Regex(""""\{\{fromRequest:((?:[^"\\]|\\.)*)}}"""")

    /** Whether [argsJson] holds a template. */
    fun holdsTemplate(argsJson: String): Boolean = TEMPLATE.containsMatchIn(argsJson)

    /** The regexes of the templates in [argsJson]; fails with [IllegalArgumentException] for a bad one. */
    fun patterns(argsJson: String): List<Regex> =
        TEMPLATE
            .findAll(argsJson)
            .map { match ->
                val source = Json.decodeFromString<String>("\"${match.groupValues[1]}\"")
                val regex = Regex(source)
                require(regex.toPattern().matcher("").groupCount() >= 1) { "{{fromRequest:$source}} needs a group" }
                regex
            }.toList()

    /** The text templates search: every text of [request]'s messages, tool results included, joined by `\n`. */
    fun searchText(request: ChatRequest): String =
        request.messages
            .flatMap { it.parts }
            .flatMap { part ->
                when (part) {
                    is Part.Text -> listOf(part.text)
                    is Part.ToolResult -> part.parts.filterIsInstance<Part.Text>().map { it.text }
                    is Part.Image, is Part.ToolCall, is Part.Reasoning -> emptyList()
                }
            }.joinToString("\n")

    /**
     * [argsJson] with each template resolved against [request]. Fails with [AssertionError] when a
     * template matches nothing: the request no longer shows what the recorded reply referred to.
     */
    fun resolve(
        argsJson: String,
        request: ChatRequest,
        where: String,
    ): String {
        val text = searchText(request)
        return TEMPLATE.replace(argsJson) { match ->
            val source = Json.decodeFromString<String>("\"${match.groupValues[1]}\"")
            val found =
                Regex(source).findAll(text).lastOrNull()
                    ?: throw AssertionError("$where: {{fromRequest:$source}} matches nothing in the request's text")
            JsonPrimitive(found.groupValues[1]).toString()
        }
    }

    /** [response] with the templates of its tool calls resolved against [request]. */
    fun resolve(
        response: ChatResponse,
        request: ChatRequest,
        where: String,
    ): ChatResponse =
        response.copy(
            parts =
                response.parts.map { part ->
                    if (part is Part.ToolCall && holdsTemplate(part.argsJson)) {
                        part.copy(argsJson = resolve(part.argsJson, request, "$where call ${part.id.value}"))
                    } else {
                        part
                    }
                },
        )

    /**
     * What resolving [template] gave in [reply] ([resolve]): the arguments of each call that
     * changed, by call.
     */
    fun changes(
        template: ChatResponse,
        reply: ChatResponse,
    ): Map<ToolCallId, Resolved> =
        template.parts
            .zip(reply.parts)
            .mapNotNull { (before, after) ->
                if (before is Part.ToolCall && after is Part.ToolCall && before.argsJson != after.argsJson) {
                    before.id to Resolved(before.argsJson, after.argsJson)
                } else {
                    null
                }
            }.toMap()

    /**
     * [parts] with the arguments of each call in [templates] put back to its template, when they
     * are what the template resolved to (N7: the fixture keeps the template, not the value).
     */
    fun restore(
        parts: List<Part>,
        templates: Map<ToolCallId, Resolved>,
    ): List<Part> =
        parts.map { part ->
            val resolved = (part as? Part.ToolCall)?.let { templates[it.id] }
            if (part is Part.ToolCall && resolved != null &&
                part.argsJson == resolved.value
            ) {
                part.copy(argsJson = resolved.template)
            } else {
                part
            }
        }

    /** A template and what it resolved to in one replayed reply. */
    data class Resolved(
        val template: String,
        val value: String,
    )
}
