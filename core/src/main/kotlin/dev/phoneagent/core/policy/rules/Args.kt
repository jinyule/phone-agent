package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ScreenCoordinates
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Typed reads of tool arguments, by the rules the tools read them with (WP06 `ArgReader`,
 * policy-rules.md §7): JSON `null` counts as absent; numbers and booleans may come as strings;
 * refs are `@e5` or `e5` with surrounding blanks ignored. Every read returns `null` instead of
 * throwing.
 */
internal object Args {
    private val REF = Regex("[a-z]+[1-9][0-9]*")

    /** Whether [key] was given (and is not JSON `null`). */
    fun has(
        args: JsonObject,
        key: String,
    ): Boolean = given(args, key) != null

    /** The single value of [key] as text (strings, numbers and booleans alike); `null` when absent or not one value. */
    fun text(
        args: JsonObject,
        key: String,
    ): String? = (given(args, key) as? JsonPrimitive)?.content

    /** The `ref` argument as an [Aim.Ref] with the `screen` it names, or `null` when it is not a valid ref. */
    fun ref(args: JsonObject): Aim.Ref? {
        val ref = text(args, "ref").orEmpty().trim().removePrefix("@")
        return if (REF.matches(ref)) Aim.Ref(ElementRef(ref), text(args, "screen")?.trim()) else null
    }

    /** Holder so that "no ref given" and "invalid ref" can be told apart. */
    data class OptionalRef(
        val aim: Aim.Ref?,
    )

    fun optionalRef(args: JsonObject): OptionalRef? =
        if (has(args, "ref")) ref(args)?.let(::OptionalRef) else OptionalRef(null)

    /** The point ([xKey], [yKey]) in 0–999 coordinates, or `null` when either is missing or invalid. */
    fun point(
        args: JsonObject,
        xKey: String,
        yKey: String,
    ): Aim.Point? {
        val x = coordinate(args, xKey)
        val y = coordinate(args, yKey)
        return if (x == null || y == null) null else Aim.Point(x, y)
    }

    /** An integral coordinate (500, 500.0 or "500") in 0–999, or `null`. */
    private fun coordinate(
        args: JsonObject,
        key: String,
    ): Int? {
        val primitive = given(args, key) as? JsonPrimitive
        val number = primitive?.longOrNull ?: primitive?.doubleOrNull?.takeIf { it == Math.rint(it) }?.toLong()
        return number?.takeIf { it in 0L..ScreenCoordinates.MAX.toLong() }?.toInt()
    }

    /** A flag: `false` when absent, its value when `true` / `false` (also as strings), `null` when anything else. */
    fun flag(
        args: JsonObject,
        key: String,
    ): Boolean? = if (has(args, key)) (given(args, key) as? JsonPrimitive)?.booleanOrNull else false

    /** One of [options] by its exact wire name, or `null`. */
    fun <E : Any> choice(
        args: JsonObject,
        key: String,
        options: Map<String, E>,
    ): E? = text(args, key)?.let(options::get)

    private fun given(
        args: JsonObject,
        key: String,
    ): JsonElement? = args[key]?.takeIf { it !is JsonNull }
}
