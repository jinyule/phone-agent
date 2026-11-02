package dev.phoneagent.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * The exact value of a JSON number literal, or `null` for anything else (strings, which JSON
 * Schema never coerces, booleans, null). JSON numbers are decimals: no rounding through Double.
 */
internal fun JsonElement?.jsonNumber(): BigDecimal? =
    (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toBigDecimalOrNull()

/** JSON value equality: numbers by value (`1` equals `1.0`), arrays in order, objects by keys. */
internal fun sameJson(
    a: JsonElement,
    b: JsonElement,
): Boolean =
    when {
        a is JsonObject && b is JsonObject -> {
            a.keys == b.keys &&
                a.all { (key, item) -> sameJson(item, b.getValue(key)) }
        }

        a is JsonArray && b is JsonArray -> {
            a.size == b.size && a.indices.all { sameJson(a[it], b[it]) }
        }

        else -> {
            samePrimitive(a, b)
        }
    }

private fun samePrimitive(
    a: JsonElement,
    b: JsonElement,
): Boolean {
    val x = a.jsonNumber()
    val y = b.jsonNumber()
    return if (x != null && y != null) x.compareTo(y) == 0 else a == b
}
