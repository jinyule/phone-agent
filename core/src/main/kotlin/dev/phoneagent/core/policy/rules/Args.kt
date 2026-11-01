package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ScreenCoordinates
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Typed reads of tool arguments; every read returns `null` (or a default) instead of throwing. */
internal object Args {
    private val REF = Regex("@?([a-z]+[1-9][0-9]*)")

    /** The `ref` argument as an [Aim.Ref], or `null` when it is not a valid ref. */
    fun ref(args: JsonObject): Aim.Ref? =
        string(
            args,
            "ref",
        )?.let(REF::matchEntire)?.let { Aim.Ref(ElementRef(it.groupValues[1]), string(args, "screen")) }

    /** Holder so that "no ref given" and "invalid ref" can be told apart. */
    data class OptionalRef(
        val aim: Aim.Ref?,
    )

    fun optionalRef(args: JsonObject): OptionalRef? =
        if (args.containsKey("ref")) ref(args)?.let(::OptionalRef) else OptionalRef(null)

    fun point(
        args: JsonObject,
        xKey: String,
        yKey: String,
    ): Aim.Point? {
        val x = int(args, xKey)
        val y = int(args, yKey)
        return if (x == null ||
            y == null
        ) {
            null
        } else {
            Aim.Point(x, y).takeIf { ScreenCoordinates.isValid(x) && ScreenCoordinates.isValid(y) }
        }
    }

    fun string(
        args: JsonObject,
        key: String,
    ): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun int(
        args: JsonObject,
        key: String,
    ): Int? = (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    fun bool(
        args: JsonObject,
        key: String,
    ): Boolean = (args[key] as? JsonPrimitive)?.booleanOrNull ?: false
}
