package dev.phoneagent.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The JSON Schema subset tool parameters may use. [ToolRegistry] refuses any other schema when a
 * tool is registered, and the argument validator (WP02) implements exactly this subset, so a
 * schema the model sees is one the validator enforces in full.
 *
 * Every schema has one `type` (a string, not a list) and may have a string `description`.
 * Other keywords depend on the type:
 *
 * - `object`: `properties` (name → schema), `required` (distinct declared names),
 *   `additionalProperties` (a boolean, or the schema of every other value);
 * - `array`: `items` (a schema; required), `minItems`, `maxItems`;
 * - `string`: `enum` (strings), `minLength`, `maxLength`;
 * - `integer`: `enum` (integers), `minimum`, `maximum` (integers);
 * - `number`: `enum` (numbers), `minimum`, `maximum` (numbers);
 * - `boolean`: nothing else.
 *
 * Counts and lengths are integers >= 0, a minimum is not above its maximum, and an `enum` is a
 * non-empty list of distinct values. Integer bounds and counts are compared exactly (64-bit
 * integers, also above 2^53); number bounds as doubles. The top level is an `object` schema with `properties`.
 * Everything else is refused, for example the `null` type, `pattern`, `format`, `default`,
 * `title`, `const`, `oneOf` / `anyOf` / `allOf` and `$ref`.
 */
internal object ToolSchema {
    /**
     * Problems with the top-level [schema]. Problems inside a property are prefixed with its
     * path (`x`, `x.items`, `x.y`); top-level problems have no prefix.
     */
    fun problems(schema: JsonObject): List<String> =
        buildList {
            if (schema["type"] != JsonPrimitive(OBJECT)) add("schema type must be \"object\"")
            if (schema["properties"] !is JsonObject) add("schema needs a \"properties\" object")
            unsupported(schema, OBJECT).forEach { add("keyword '$it' is not supported at the top level") }
            descriptionProblem(schema)?.let(::add)
            addAll(objectProblems(schema, path = null))
        }

    private fun schemaProblems(
        schema: JsonObject,
        path: String,
    ): List<String> {
        val type = (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val problems =
            when {
                schema["type"] == null -> listOf("needs a \"type\"")
                type == null -> listOf("\"type\" must be one of $TYPES")
                type !in TYPES -> listOf("unknown type '$type'")
                else -> unsupported(schema, type).map { "keyword '$it' is not supported for type $type" }
            } + listOfNotNull(descriptionProblem(schema))
        return problems.map { "$path: $it" } + typeProblems(schema, type, path)
    }

    private fun typeProblems(
        schema: JsonObject,
        type: String?,
        path: String,
    ): List<String> =
        when (type) {
            OBJECT -> objectProblems(schema, path)
            "array" -> arrayProblems(schema, path)
            "string" -> (count(schema, "minLength", "maxLength") + enum(schema, ::isString)).map { "$path: $it" }
            "integer" -> (bounds(schema, ::isInteger, exact = true) + enum(schema, ::isInteger)).map { "$path: $it" }
            "number" -> (bounds(schema, ::isNumber, exact = false) + enum(schema, ::isNumber)).map { "$path: $it" }
            else -> emptyList()
        }

    private fun objectProblems(
        schema: JsonObject,
        path: String?,
    ): List<String> {
        val at = { problem: String -> if (path == null) problem else "$path: $problem" }
        val child = { name: String -> if (path == null) name else "$path.$name" }
        val properties = schema["properties"]
        val declared = (properties as? JsonObject)?.keys.orEmpty()
        return buildList {
            if (properties != null && properties !is JsonObject && path != null) {
                add(at("\"properties\" must be an object"))
            }
            (properties as? JsonObject)?.forEach { (name, property) ->
                if (property is JsonObject) {
                    addAll(schemaProblems(property, child(name)))
                } else {
                    add(at("property '$name' must be a schema object"))
                }
            }
            requiredProblems(schema["required"], declared).forEach { add(at(it)) }
            when (val additional = schema["additionalProperties"]) {
                null -> Unit
                is JsonObject -> addAll(schemaProblems(additional, child("additionalProperties")))
                else -> if (!isBoolean(additional)) add(at("\"additionalProperties\" must be a boolean or a schema"))
            }
        }
    }

    private fun requiredProblems(
        required: JsonElement?,
        declared: Set<String>,
    ): List<String> =
        when {
            required == null -> {
                emptyList()
            }

            required !is JsonArray -> {
                listOf("\"required\" must be an array")
            }

            else -> {
                val names = required.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                buildList {
                    if (names.size != required.size) add("\"required\" must contain only strings")
                    if (names.distinct().size != names.size) add("\"required\" lists a name twice")
                    names.filter { it !in declared }.forEach { add("required property '$it' is not declared") }
                }
            }
        }

    private fun arrayProblems(
        schema: JsonObject,
        path: String,
    ): List<String> =
        when (val items = schema["items"]) {
            null -> listOf("$path: an array needs \"items\"")
            is JsonObject -> schemaProblems(items, "$path.items")
            else -> listOf("$path: \"items\" must be a schema object")
        } + count(schema, "minItems", "maxItems").map { "$path: $it" }

    /** `min`/`max` keywords that are counts: integers >= 0, min not above max. */
    private fun count(
        schema: JsonObject,
        min: String,
        max: String,
    ): List<String> =
        buildList {
            listOf(min, max).filter { it in schema }.forEach { key ->
                val value = (schema[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                if (value == null || value < 0) add("\"$key\" must be an integer >= 0")
            }
            if (above(schema[min], schema[max], exact = true)) add("\"$min\" is above \"$max\"")
        }

    /** `minimum`/`maximum` of the property's own type, minimum not above maximum ([exact] for integers). */
    private fun bounds(
        schema: JsonObject,
        valid: (JsonElement) -> Boolean,
        exact: Boolean,
    ): List<String> =
        buildList {
            listOf("minimum", "maximum").forEach { key ->
                schema[key]?.let { if (!valid(it)) add("\"$key\" must be a value of the property's type") }
            }
            if (above(schema["minimum"], schema["maximum"], exact)) add("\"minimum\" is above \"maximum\"")
        }

    private fun enum(
        schema: JsonObject,
        valid: (JsonElement) -> Boolean,
    ): List<String> {
        val values = schema["enum"] ?: return emptyList()
        return when {
            values !is JsonArray -> listOf("\"enum\" must be an array")
            values.isEmpty() -> listOf("\"enum\" must not be empty")
            !values.all(valid) -> listOf("\"enum\" values must be of the property's type")
            values.distinct().size != values.size -> listOf("\"enum\" values must be distinct")
            else -> emptyList()
        }
    }

    private fun unsupported(
        schema: JsonObject,
        type: String,
    ): List<String> = (schema.keys - COMMON - KEYWORDS.getValue(type)).sorted()

    private const val OBJECT = "object"
    private val COMMON = setOf("type", "description")
    private val KEYWORDS =
        mapOf(
            OBJECT to setOf("properties", "required", "additionalProperties"),
            "array" to setOf("items", "minItems", "maxItems"),
            "string" to setOf("enum", "minLength", "maxLength"),
            "integer" to setOf("enum", "minimum", "maximum"),
            "number" to setOf("enum", "minimum", "maximum"),
            "boolean" to emptySet(),
        )
    private val TYPES = KEYWORDS.keys
}

private fun descriptionProblem(schema: JsonObject): String? =
    schema["description"]?.takeUnless(::isString)?.let { "\"description\" must be a string" }

/**
 * Whether the bound [min] is above [max]; `false` when either is absent or not a number. [exact]
 * compares 64-bit integers (a Double cannot tell integers above 2^53 apart), else doubles.
 */
private fun above(
    min: JsonElement?,
    max: JsonElement?,
    exact: Boolean,
): Boolean {
    val low = (min as? JsonPrimitive)?.takeUnless { it.isString }
    val high = (max as? JsonPrimitive)?.takeUnless { it.isString }
    return if (exact) greater(low?.longOrNull, high?.longOrNull) else greater(low?.doubleOrNull, high?.doubleOrNull)
}

private fun <T : Comparable<T>> greater(
    a: T?,
    b: T?,
): Boolean = a != null && b != null && a > b

private fun isString(element: JsonElement) = element is JsonPrimitive && element.isString

private fun isBoolean(element: JsonElement) =
    element is JsonPrimitive && !element.isString && element.content in setOf("true", "false")

private fun isInteger(element: JsonElement) =
    element is JsonPrimitive && !element.isString && element.longOrNull != null

private fun isNumber(element: JsonElement) =
    element is JsonPrimitive && !element.isString && element.doubleOrNull != null && !isBoolean(element)
