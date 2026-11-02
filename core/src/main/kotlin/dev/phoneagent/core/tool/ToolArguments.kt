package dev.phoneagent.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal

/**
 * Checks a model's tool arguments against the tool's JSON Schema at the boundary (phase1 §3.2:
 * `Tool.execute` only ever sees arguments that passed). Problems are Chinese sentences meant for
 * the model, naming the offending path, so it can fix the call; they are model-visible (AGENTS.md
 * §5: the loop's golden files show them).
 *
 * Supported keywords: `type` (a name or a list of names: object, array, string, integer, number,
 * boolean, null), `enum`, `const`, `properties`, `required`, `additionalProperties` (`false` or a
 * schema), `items`, `minimum`, `maximum`, `minLength`, `maxLength`, `minItems`, `maxItems`,
 * `anyOf` and `oneOf`. Other keywords (descriptions, formats, `$ref`) and unknown type names are
 * ignored, so a schema using them is checked less strictly, never more.
 *
 * Numbers are exact decimals, as JSON writes them: `integer` means no fractional part (`1.0` is an
 * integer, `1.00000000000000001` is not) and bounds compare exactly, also past 2^53. `enum` and
 * `const` compare JSON values (`1` equals `1.0`). Lengths count Unicode code points.
 */
public object ToolArguments {
    private val types: Map<String, (JsonElement) -> Boolean> =
        mapOf(
            "object" to { it is JsonObject },
            "array" to { it is JsonArray },
            "null" to { it is JsonNull },
            "string" to { it is JsonPrimitive && it.isString },
            "boolean" to { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull != null },
            "integer" to { value -> value.jsonNumber()?.let { it.stripTrailingZeros().scale() <= 0 } == true },
            "number" to { it.jsonNumber() != null },
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
            if (allowed != null &&
                allowed.none { sameJson(it, value) }
            ) {
                add("$path 必须是 $allowed 之一，实际是 $value")
            }
            val expected = schema["const"]
            if (expected != null && !sameJson(expected, value)) add("$path 必须是 $expected，实际是 $value")
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
        return if (matches) null else "$path 的类型必须是 ${names.joinToString(" 或 ")}，实际是 $value"
    }

    private fun bounds(
        schema: JsonObject,
        value: JsonElement,
        path: String,
    ): List<String> {
        val number = value.jsonNumber()
        val length = value.string()?.let { BigDecimal.valueOf(it.codePointCount(0, it.length).toLong()) }
        val size = (value as? JsonArray)?.let { BigDecimal.valueOf(it.size.toLong()) }
        val checks =
            listOf(
                Bound("minimum", number, atLeast = true) { "$path 必须 >= $it，实际是 $value" },
                Bound("maximum", number, atLeast = false) { "$path 必须 <= $it，实际是 $value" },
                Bound("minLength", length, atLeast = true) { "$path 至少要有 $it 个字符，实际有 $length 个" },
                Bound("maxLength", length, atLeast = false) { "$path 最多只能有 $it 个字符，实际有 $length 个" },
                Bound("minItems", size, atLeast = true) { "$path 至少要有 $it 项，实际有 $size 项" },
                Bound("maxItems", size, atLeast = false) { "$path 最多只能有 $it 项，实际有 $size 项" },
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
            required.filter { it !in value }.forEach { add("缺少必填参数 $path.$it") }
            for ((key, child) in value) {
                val childSchema = properties[key] as? JsonObject ?: additional as? JsonObject
                when {
                    childSchema != null -> addAll(check(childSchema, child, "$path.$key"))
                    closed && key !in properties -> add("$path.$key 不是已知的参数")
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
                    add("$path 不符合任何一种允许的形式（${failures.flatten().distinct().joinToString("；")}）")
                }
            }
            val oneOf = (schema["oneOf"] as? JsonArray)?.schemas()
            if (oneOf != null) {
                val matching = oneOf.count { check(it, value, path).isEmpty() }
                if (matching != 1) add("$path 必须恰好符合一种允许的形式，实际符合 $matching 种")
            }
        }

    private fun JsonArray.schemas(): List<JsonObject> = filterIsInstance<JsonObject>()

    private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** One `minimum`-style keyword: [actual] (null when the keyword does not apply) against the schema's limit. */
    private class Bound(
        private val key: String,
        private val actual: BigDecimal?,
        private val atLeast: Boolean,
        private val message: (String) -> String,
    ) {
        fun problem(schema: JsonObject): String? {
            val limit = schema[key].jsonNumber() ?: return null
            val outside = actual?.compareTo(limit)?.let { if (atLeast) it < 0 else it > 0 } == true
            return if (outside) message(limit.stripTrailingZeros().toPlainString()) else null
        }
    }
}
