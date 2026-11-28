package dev.phoneagent.core.tools

import dev.phoneagent.core.tool.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds a tool's JSON Schema (phase1 §5): an object schema without extra properties whose first
 * property is the required `reason` every tool takes. Property order is the order of the calls,
 * so the schema text is stable.
 */
internal class ToolSchema private constructor() {
    private val properties = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    /** Names of all declared properties, including `reason`. */
    val names: Set<String> get() = properties.keys

    /** A string property; [enum] restricts its values. */
    fun string(
        name: String,
        description: String,
        required: Boolean = false,
        enum: List<String>? = null,
    ) = add(name, required) {
        put("type", "string")
        put("description", description)
        enum?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
    }

    /** An integer property in [range]. */
    fun integer(
        name: String,
        description: String,
        range: LongRange,
        required: Boolean = false,
    ) = add(name, required) {
        put("type", "integer")
        put("description", description)
        put("minimum", range.first)
        put("maximum", range.last)
    }

    /** A boolean property. */
    fun boolean(
        name: String,
        description: String,
    ) = add(name, required = false) {
        put("type", "boolean")
        put("description", description)
    }

    /** An array of at most [maxItems] strings. */
    fun strings(
        name: String,
        description: String,
        maxItems: Int,
    ) = add(name, required = false) {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "string") }
        put("maxItems", maxItems)
    }

    private fun add(
        name: String,
        required: Boolean,
        body: JsonObjectBuilder.() -> Unit,
    ) {
        require(name !in properties) { "Duplicate schema property '$name'" }
        properties[name] = buildJsonObject(body)
        if (required) this.required += name
    }

    private fun build(): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema) } }
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            put("additionalProperties", false)
        }

    /** Construction. */
    companion object {
        /** Description of the `reason` property every tool shares. */
        const val REASON_DESCRIPTION: String = "这一步要做什么、为什么。用用户的语言写一句话，会显示给用户看。"

        /** The schema made by [declare], with `reason` first; returns it with the declared names. */
        fun of(declare: ToolSchema.() -> Unit): Pair<JsonObject, Set<String>> {
            val schema = ToolSchema()
            schema.string(ToolCall.REASON, REASON_DESCRIPTION, required = true)
            schema.declare()
            return schema.build() to schema.names - ToolCall.REASON
        }
    }
}
