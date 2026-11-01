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
 * Manifest order is deterministic and append-only: groups in expansion order, tools within a
 * group in registration order. Expanding a group only appends, so the cached prompt prefix holds.
 *
 * @property tools every registered tool, expanded or not, in registration order.
 */
public class ToolRegistry(
    public val tools: List<Tool>,
    expanded: Collection<ToolGroup>,
) {
    /** Groups whose tools are visible, in expansion order. */
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
         * Problems with [tool]'s definition: blank or over-long description, non-positive timeout,
         * or a schema that is not an object schema whose properties are objects, whose `required`
         * names only declared properties, and which declares a required string `reason`.
         */
        internal fun problemsOf(tool: Tool): List<String> {
            val def = tool.def
            val problems = mutableListOf<String>()
            if (def.description.isBlank()) problems += "${def.name}: description is blank"
            if (def.description.length > MAX_DESCRIPTION_LENGTH) {
                problems += "${def.name}: description longer than $MAX_DESCRIPTION_LENGTH chars"
            }
            if (tool.timeoutMs <= 0) problems += "${def.name}: timeoutMs must be > 0"
            problems += schemaProblems(def.parameters).map { "${def.name}: $it" }
            return problems
        }

        private fun schemaProblems(schema: JsonObject): List<String> {
            val properties = schema["properties"] as? JsonObject
            val required = schema["required"]
            // null = "required" present but not an array; absent "required" means no required names.
            val requiredNames: List<String?>? =
                if (required == null) emptyList() else (required as? JsonArray)?.map(::stringOrNull)
            val reason = properties?.get(ToolCall.REASON) as? JsonObject
            return buildList {
                if (schema["type"] != JsonPrimitive("object")) add("schema type must be \"object\"")
                if (properties == null) add("schema needs a \"properties\" object")
                properties?.filterValues { it !is JsonObject }?.keys?.forEach {
                    add("property '$it' must be a schema object")
                }
                if (requiredNames == null) add("\"required\" must be an array")
                if (requiredNames.orEmpty().contains(null)) add("\"required\" must contain only strings")
                requiredNames.orEmpty().filterNotNull().filter { properties?.containsKey(it) != true }.forEach {
                    add("required property '$it' is not declared")
                }
                if (reason?.get("type") != JsonPrimitive("string") || ToolCall.REASON !in requiredNames.orEmpty()) {
                    add("schema must declare a required string property '${ToolCall.REASON}'")
                }
            }
        }

        private fun stringOrNull(element: JsonElement): String? =
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
