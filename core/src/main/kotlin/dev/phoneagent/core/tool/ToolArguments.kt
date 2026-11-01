package dev.phoneagent.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Checks a model's tool arguments against the tool's JSON Schema at the boundary (phase1 §3.2:
 * `Tool.execute` only ever sees arguments that passed). Problems are English sentences meant for
 * the model, naming the offending path, so it can fix the call.
 *
 * Supported keywords: `type` (a name or a list of names: object, array, string, integer, number,
 * boolean, null), `enum`, `const`, `properties`, `required`, `additionalProperties` (`false` or a
 * schema), `items`, `minimum`, `maximum`, `minLength`, `maxLength`, `minItems`, `maxItems`,
 * `anyOf` and `oneOf`. Other keywords (descriptions, formats, `$ref`) and unknown type names are
 * ignored, so a schema using them is checked less strictly, never more.
 */
public object ToolArguments {
    private val types: Map<String, (JsonElement) -> Boolean> =
        mapOf(
            "object" to { it is JsonObject },
            "array" to { it is JsonArray },
            "null" to { it is JsonNull },
            "string" to { it is JsonPrimitive && it.isString },
            "boolean" to { it.literal()?.booleanOrNull != null },
            "integer" to { value -> value.number()?.let { it % 1.0 == 0.0 } == true },
            "number" to { it.number() != null },
        )

    /** Problems of [value] against [schema]; empty when it conforms. */
    public fun problems(
        schema: JsonObject,
        value: JsonElement,
    ): List<String> = check(schema, value, "arguments")

    private fun check(
        schema: JsonObject,
        value: JsonElement,
        path: String,
    ): List<String> {
        val typeProblem = typeProblem(schema["type"], value, path)
        if (typeProblem != null) return listOf(typeProblem)
        return buildList {
            val allowed = schema["enum"] as? JsonArray
            if (allowed != null && value !in allowed) add("$path must be one of $allowed, got $value")
            val expected = schema["const"]
            if (expected != null && value != expected) add("$path must be $expected, got $value")
            addAll(bounds(schema, value, path))
            if (value is JsonObject) addAll(objectProblems(schema, value, path))
            if (value is JsonArray) addAll(arrayProblems(schema, value, path))
            addAll(alternatives(schema, value, path))
        }
    }

    private fun typeProblem(
        type: JsonElement?,
        value: JsonElement,
        path: String,
    ): String? {
        val names =
            when {
                type is JsonPrimitive -> listOfNotNull(type.string())
                type is JsonArray -> type.mapNotNull { it.string() }
                else -> emptyList()
            }
        val matches = names.isEmpty() || names.any { name -> types[name]?.invoke(value) ?: true }
        return if (matches) null else "$path must be ${names.joinToString(" or ")}, got $value"
    }

    private fun bounds(
        schema: JsonObject,
        value: JsonElement,
        path: String,
    ): List<String> {
        val number = value.number()
        val length =
            (value as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.length
                ?.toDouble()
        val size = (value as? JsonArray)?.size?.toDouble()
        val checks =
            listOf(
                Bound("minimum", number, atLeast = true) { "$path must be >= $it, got $value" },
                Bound("maximum", number, atLeast = false) { "$path must be <= $it, got $value" },
                Bound("minLength", length, atLeast = true) { "$path must have at least $it characters" },
                Bound("maxLength", length, atLeast = false) { "$path must have at most $it characters" },
                Bound("minItems", size, atLeast = true) { "$path must have at least $it items" },
                Bound("maxItems", size, atLeast = false) { "$path must have at most $it items" },
            )
        return checks.mapNotNull { it.problem(schema) }
    }

    private fun objectProblems(
        schema: JsonObject,
        value: JsonObject,
        path: String,
    ): List<String> {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { it.string() }
        val additional = schema["additionalProperties"]
        val closed = additional == JsonPrimitive(false)
        return buildList {
            required.filter { it !in value }.forEach { add("$path.$it is required") }
            for ((key, child) in value) {
                val childSchema = properties[key] as? JsonObject ?: additional as? JsonObject
                when {
                    childSchema != null -> addAll(check(childSchema, child, "$path.$key"))
                    closed && key !in properties -> add("$path.$key is not a known argument")
                    else -> Unit
                }
            }
        }
    }

    private fun arrayProblems(
        schema: JsonObject,
        value: JsonArray,
        path: String,
    ): List<String> {
        val items = schema["items"] as? JsonObject ?: return emptyList()
        return value.flatMapIndexed { index, item -> check(items, item, "$path[$index]") }
    }

    private fun alternatives(
        schema: JsonObject,
        value: JsonElement,
        path: String,
    ): List<String> =
        buildList {
            val anyOf = (schema["anyOf"] as? JsonArray)?.schemas()
            if (anyOf != null) {
                val failures = anyOf.map { check(it, value, path) }
                if (failures.none { it.isEmpty() }) {
                    add("$path matches none of the allowed forms: ${failures.flatten().distinct()}")
                }
            }
            val oneOf = (schema["oneOf"] as? JsonArray)?.schemas()
            if (oneOf != null) {
                val matching = oneOf.count { check(it, value, path).isEmpty() }
                if (matching != 1) add("$path must match exactly one of the allowed forms, matches $matching")
            }
        }

    private fun JsonArray.schemas(): List<JsonObject> = filterIsInstance<JsonObject>()

    /** A JSON number or boolean (not a string, which JSON Schema never coerces). */
    private fun JsonElement?.literal(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { !it.isString }

    private fun JsonElement?.number(): Double? = literal()?.doubleOrNull

    private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** One `minimum`-style keyword: [actual] (null when the keyword does not apply) against the schema's limit. */
    private class Bound(
        private val key: String,
        private val actual: Double?,
        private val atLeast: Boolean,
        private val message: (String) -> String,
    ) {
        fun problem(schema: JsonObject): String? {
            val limit = schema[key].number() ?: return null
            return actual?.takeIf { if (atLeast) it < limit else it > limit }?.let { message(format(limit)) }
        }

        private fun format(number: Double): String =
            if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
    }
}
