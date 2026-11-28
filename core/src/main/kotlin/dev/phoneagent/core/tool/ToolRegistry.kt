package dev.phoneagent.core.tool

import dev.phoneagent.core.model.ToolDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The tools of one session and which groups are expanded (phase1 §3.2). Immutable: [expand]
 * returns a new registry. Every tool is validated when the registry is built; any problem
 * fails construction with an [IllegalArgumentException] listing all of them.
 *
 * Manifest order is deterministic and append-only: groups in expansion order (the order of
 * `expanded`, then of [expand] calls), tools within a group in registration order. Expanding a
 * group only appends, so the cached prompt prefix holds.
 *
 * Parameter schemas must stay within [ToolSchema]'s subset, which the argument validator
 * implements in full.
 *
 * @property tools every registered tool, expanded or not, in registration order.
 */
public class ToolRegistry(
    public val tools: List<Tool>,
    expanded: List<ToolGroup>,
) {
    /** Groups whose tools are visible, in expansion order; repeats in `expanded` are dropped. */
    public val expandedGroups: List<ToolGroup> = expanded.distinct()

    init {
        val problems = tools.flatMap(::problemsOf) + duplicateNames()
        require(problems.isEmpty()) { "Invalid tool registry:\n" + problems.joinToString("\n") { "- $it" } }
    }

    /** Definitions sent to the model, in manifest order. */
    public val manifest: List<ToolDef> =
        expandedGroups.flatMap { group -> tools.filter { it.group == group } }.map { it.def }

    /** A registry with [group]'s tools appended to the manifest; `this` when already expanded. */
    public fun expand(group: ToolGroup): ToolRegistry =
        if (group in expandedGroups) this else ToolRegistry(tools, expandedGroups + group)

    /** The visible tool named [name], or `null` when unknown or in a collapsed group. */
    public operator fun get(name: String): Tool? =
        tools.firstOrNull { it.def.name == name && it.group in expandedGroups }

    private fun duplicateNames(): List<String> =
        tools
            .groupingBy { it.def.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .map { "duplicate tool name '$it'" }

    /** Limits and validation, shared with tests. */
    public companion object {
        /** Longest allowed description (it is static prompt text). */
        public const val MAX_DESCRIPTION_LENGTH: Int = 1024

        /**
         * Problems with [tool]'s definition, each prefixed with the tool name: blank or over-long
         * description, non-positive timeout, a parameter schema outside [ToolSchema]'s subset, or
         * one without a required string property `reason`.
         */
        internal fun problemsOf(tool: Tool): List<String> {
            val def = tool.def
            val problems = mutableListOf<String>()
            if (def.description.isBlank()) problems += "${def.name}: description is blank"
            if (def.description.length > MAX_DESCRIPTION_LENGTH) {
                problems += "${def.name}: description longer than $MAX_DESCRIPTION_LENGTH chars"
            }
            if (tool.timeoutMs <= 0) problems += "${def.name}: timeoutMs must be > 0"
            problems +=
                (ToolSchema.problems(def.parameters) + reasonProblems(def.parameters)).map { "${def.name}: $it" }
            return problems
        }

        private fun reasonProblems(schema: JsonObject): List<String> {
            val reason = (schema["properties"] as? JsonObject)?.get(ToolCall.REASON) as? JsonObject
            val required = (schema["required"] as? JsonArray)?.mapNotNull(::stringOrNull).orEmpty()
            return if (reason?.get("type") == JsonPrimitive("string") && ToolCall.REASON in required) {
                emptyList()
            } else {
                listOf("schema must declare a required string property '${ToolCall.REASON}'")
            }
        }

        private fun stringOrNull(element: JsonElement): String? =
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
