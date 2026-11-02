package dev.phoneagent.core.tools

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ElementRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads a tool's arguments at the boundary (they come from the model). Every getter returns
 * `null` when the argument is absent or invalid and records a problem for invalid or missing
 * required ones; [finish] turns the problems into one message the model can act on.
 *
 * Lenient where it is unambiguous: JSON `null` counts as absent, numbers and booleans may come as
 * strings, `@e5` / `e5` / `5` all name element `e5`. Unknown argument names are problems.
 */
internal class ArgReader(
    private val args: JsonObject,
    known: Set<String>,
) {
    private val problems = mutableListOf<String>()

    init {
        args.keys
            .filter { it !in known }
            .sorted()
            .forEach { problems += "不认识的参数 `$it`" }
    }

    /** Whether [name] was given (and is not JSON `null`). */
    fun has(name: String): Boolean = args[name].let { it != null && it !is JsonNull }

    /** Records a problem that spans several arguments. */
    fun problem(text: String) {
        problems += text
    }

    /** A string of at most [max] characters; blank only when [allowBlank]. */
    fun string(
        name: String,
        required: Boolean = false,
        max: Int = MAX_STRING,
        allowBlank: Boolean = false,
    ): String? {
        val text = scalar(name, required)?.content
        return when {
            text == null -> null
            text.isBlank() && !allowBlank -> fail("`$name` 不能为空")
            text.length > max -> fail("`$name` 太长（最多 $max 个字）")
            else -> text
        }
    }

    /** An integer in [range]; integral floats and numeric strings are accepted. */
    fun integer(
        name: String,
        range: LongRange,
        required: Boolean = false,
    ): Long? {
        val primitive = scalar(name, required) ?: return null
        val number = primitive.longOrNull ?: primitive.doubleOrNull?.takeIf { it == Math.rint(it) }?.toLong()
        return number?.takeIf { it in range }
            ?: fail("`$name` 应该是 ${range.first}–${range.last} 的整数（收到 ${shown(primitive)}）")
    }

    /** A boolean (`true` / `false`, also as strings). */
    fun boolean(name: String): Boolean? {
        val primitive = scalar(name, required = false) ?: return null
        return primitive.booleanOrNull ?: fail("`$name` 应该是 true 或 false（收到 ${shown(primitive)}）")
    }

    /** One of [options] by its wire name. */
    fun <E : Any> choice(
        name: String,
        options: Map<String, E>,
        required: Boolean = false,
    ): E? {
        val primitive = scalar(name, required) ?: return null
        return options[primitive.content]
            ?: fail("`$name` 只能是 ${options.keys.joinToString(" / ")}（收到 ${shown(primitive)}）")
    }

    /** Distinct non-blank strings, at most [maxItems] of at most [max] characters each. */
    fun strings(
        name: String,
        maxItems: Int,
        max: Int,
    ): List<String>? {
        val element = args[name]?.takeIf { it !is JsonNull } ?: return null
        val array = element as? JsonArray
        val items = array?.mapNotNull { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content }.orEmpty()
        val valid =
            array != null &&
                items.size == array.size &&
                items.size <= maxItems &&
                items.all { it.isNotBlank() && it.length <= max } &&
                items.distinct().size == items.size
        return if (valid) items else fail("`$name` 应该是不重复的文字数组（最多 $maxItems 项，每项最多 $max 个字）")
    }

    /** [value] when nothing went wrong, else the problems with [usage] appended. */
    fun <T : Any> finish(
        usage: String,
        value: T?,
    ): Parsed<T> =
        if (problems.isEmpty() && value != null) {
            Parsed.Ok(value)
        } else {
            val listed = problems.ifEmpty { listOf("参数不完整") }.joinToString("\n") { "- $it" }
            Parsed.Invalid("参数有误，这一步没有执行：\n$listed\n用法：$usage")
        }

    private fun scalar(
        name: String,
        required: Boolean,
    ): JsonPrimitive? {
        val element: JsonElement? = args[name]?.takeIf { it !is JsonNull }
        return when {
            element == null -> if (required) fail("缺少 `$name`") else null
            element is JsonPrimitive -> element
            else -> fail("`$name` 应该是单个值，不是对象或数组")
        }
    }

    private fun <T> fail(problem: String): T? {
        problems += problem
        return null
    }

    private companion object {
        const val MAX_STRING = 2000
    }
}

/** An element ref of the latest screen: `@e5` or `e5` (contracts addendum §6); a bare number is not one. */
internal fun ArgReader.ref(name: String): ElementRef? {
    val text = string(name)?.trim() ?: return null
    val normalized = text.removePrefix("@")
    val valid = REF.matches(normalized)
    if (!valid) problem("`$name` 应该是最近一屏里的元素编号，例如 @e5（收到 \"${clipShown(text)}\"）")
    return if (valid) ElementRef(normalized) else null
}

/** The id of the screen an action is planned on, e.g. `s12`; always required. */
internal fun ArgReader.snapshot(name: String): SnapshotId? {
    val text = string(name, required = true)?.trim() ?: return null
    val valid = text.length <= MAX_ID && text.none(Char::isISOControl) && text.none(Char::isWhitespace)
    if (!valid) problem("`$name` 应该是最近一屏的编号，例如 s12（收到 \"${clipShown(text)}\"）")
    return if (valid) SnapshotId(text) else null
}

/** How a received value is quoted back in problems: strings in quotes, at most [SHOWN] characters. */
private fun shown(primitive: JsonPrimitive): String =
    if (primitive.isString) "\"${clipShown(primitive.content)}\"" else clipShown(primitive.content)

/** [text] as echoed in a problem line: at most [SHOWN] characters. */
internal fun clipShown(text: String): String = if (text.length <= SHOWN) text else text.take(SHOWN) + "…"

private const val MAX_ID = 256
private const val SHOWN = 40
private val REF = Regex("[a-z]+[1-9][0-9]*")

/** Arguments after the boundary check. */
internal sealed interface Parsed<out T> {
    /** Valid arguments as [value]. */
    data class Ok<T>(
        val value: T,
    ) : Parsed<T>

    /** Invalid; [message] tells the model what to fix. */
    data class Invalid(
        val message: String,
    ) : Parsed<Nothing>
}
