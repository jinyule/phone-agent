@file:OptIn(ExperimentalSerializationApi::class)

package dev.phoneagent.core.replay

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A field position inside a JSON document, written as steps separated by `.`:
 * - `name`: the member `name` of an object; `name[]`: then every element of that array;
 * - `{sub}`: the current object only when its `type` discriminator is `sub` (a sealed subtype);
 * - `*`: every member of the current object (a map's values).
 *
 * For example `parts[].{tool_call}.id` is the `id` of every `tool_call` part.
 */
data class FieldPath(
    val text: String,
) {
    /** The steps of [text]. */
    val steps: List<Step> = parse(text)

    /** One step of a path. */
    sealed interface Step {
        /** The member [name]. */
        data class Member(
            val name: String,
        ) : Step

        /** Every element of the current array. */
        data object Each : Step

        /** The current object, when its `type` is [name]. */
        data class Type(
            val name: String,
        ) : Step

        /** Every member of the current object. */
        data object AnyMember : Step
    }

    override fun toString(): String = text

    private companion object {
        val SEGMENT = Regex("""(\{[^{}]+}|\*|[A-Za-z_][A-Za-z0-9_]*)((?:\[])*)""")

        fun parse(text: String): List<Step> =
            text.split('.').flatMap { segment ->
                val match = requireNotNull(SEGMENT.matchEntire(segment)) { "bad field path '$text' at '$segment'" }
                val (head, arrays) = match.destructured
                val first =
                    when {
                        head == "*" -> Step.AnyMember
                        head.startsWith("{") -> Step.Type(head.substring(1, head.length - 1))
                        else -> Step.Member(head)
                    }
                listOf(first) + List(arrays.length / 2) { Step.Each }
            }
    }
}

/**
 * Rewrites the primitives of a JSON document at a set of [FieldPath]s, visiting the document in
 * order (so values met first are rewritten first). A rule that ends on an object or an array,
 * or two rules that end on the same value, fail with [IllegalArgumentException]: the paths no
 * longer describe the document.
 */
class FieldRewriter<R>(
    private val rules: List<Pair<FieldPath, R>>,
) {
    /** [root] with each value at a rule's path replaced by [leaf] of it (`null` values are left alone). */
    fun rewrite(
        root: JsonElement,
        leaf: (R, FieldPath, JsonPrimitive) -> JsonElement,
    ): JsonElement = visit(root, rules.map { Cursor(it.first, it.second, 0) }, leaf)

    private fun visit(
        node: JsonElement,
        arriving: List<Cursor<R>>,
        leaf: (R, FieldPath, JsonPrimitive) -> JsonElement,
    ): JsonElement {
        val cursors = arriving.mapNotNull { it.settle(node) }
        val done = cursors.filter { it.complete }
        require(done.size <= 1) { "paths ${done.map { it.path }} all end on the same value" }
        val finished = done.singleOrNull()
        return when {
            finished != null -> {
                require(
                    node is JsonPrimitive,
                ) { "path ${finished.path} ends on ${node::class.simpleName}, not a value" }
                if (node is JsonNull) node else leaf(finished.rule, finished.path, node)
            }

            cursors.isEmpty() -> {
                node
            }

            node is JsonObject -> {
                JsonObject(node.mapValues { (key, child) -> visit(child, cursors.mapNotNull { it.member(key) }, leaf) })
            }

            node is JsonArray -> {
                JsonArray(node.map { visit(it, cursors.mapNotNull(Cursor<R>::each), leaf) })
            }

            else -> {
                node
            }
        }
    }

    private data class Cursor<R>(
        val path: FieldPath,
        val rule: R,
        val at: Int,
    ) {
        val complete: Boolean get() = at == path.steps.size
        private val next: FieldPath.Step? get() = path.steps.getOrNull(at)

        /** This cursor after the type checks at [node], or `null` when [node] is another subtype. */
        fun settle(node: JsonElement): Cursor<R>? {
            var cursor = this
            while (true) {
                val type = cursor.next as? FieldPath.Step.Type ?: return cursor
                val actual = ((node as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (actual != type.name) return null
                cursor = cursor.copy(at = cursor.at + 1)
            }
        }

        fun member(key: String): Cursor<R>? =
            when (val step = next) {
                is FieldPath.Step.Member -> if (step.name == key) copy(at = at + 1) else null
                FieldPath.Step.AnyMember -> copy(at = at + 1)
                else -> null
            }

        fun each(): Cursor<R>? = if (next == FieldPath.Step.Each) copy(at = at + 1) else null
    }
}

/**
 * The numeric and id fields a serial descriptor can produce, as [FieldPath]s with their leaf
 * type: `Long`, `Int`, `Double` … for numbers, the simple name of the value class for ids
 * (`ToolCallId`, `SnapshotId` …). Strings, booleans and enums are left out, and JSON trees
 * (`JsonElement` fields) are not walked. A type that contains itself is walked once per path.
 */
object FieldInventory {
    /** One field: where it is and what it holds. */
    data class Leaf(
        val path: String,
        val type: String,
    )

    /** Every numeric and id field under [descriptor], in declaration order. */
    fun of(descriptor: SerialDescriptor): List<Leaf> = buildList { walk(descriptor, emptyList(), emptySet(), this) }

    private fun walk(
        descriptor: SerialDescriptor,
        path: List<String>,
        stack: Set<String>,
        out: MutableList<Leaf>,
    ) {
        val name = descriptor.serialName.removeSuffix("?")
        val leaf = leafType(descriptor)
        if (leaf != null) {
            out += Leaf(path.joinToString("."), leaf)
            return
        }
        if (name.startsWith("kotlinx.serialization.json.") || name in stack) return
        if (descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT) {
            for (i in 0 until descriptor.elementsCount) {
                element(descriptor.getElementName(i), descriptor.getElementDescriptor(i), path, stack + name, out)
            }
        } else if (descriptor.kind == PolymorphicKind.SEALED) {
            val subtypes = descriptor.getElementDescriptor(1)
            for (i in 0 until subtypes.elementsCount) {
                walk(subtypes.getElementDescriptor(i), path + "{${subtypes.getElementName(i)}}", stack + name, out)
            }
        }
    }

    private fun element(
        name: String,
        child: SerialDescriptor,
        path: List<String>,
        stack: Set<String>,
        out: MutableList<Leaf>,
    ) {
        var segment = name
        var inner = child
        while (inner.kind == StructureKind.LIST) {
            segment += "[]"
            inner = inner.getElementDescriptor(0)
        }
        if (inner.kind == StructureKind.MAP) {
            walk(inner.getElementDescriptor(1), path + segment + "*", stack, out)
        } else {
            walk(inner, path + segment, stack, out)
        }
    }

    private fun leafType(descriptor: SerialDescriptor): String? =
        when {
            descriptor.isInline -> descriptor.serialName.removeSuffix("?").substringAfterLast('.')
            descriptor.kind == PrimitiveKind.LONG -> "Long"
            descriptor.kind == PrimitiveKind.INT -> "Int"
            descriptor.kind == PrimitiveKind.SHORT -> "Short"
            descriptor.kind == PrimitiveKind.BYTE -> "Byte"
            descriptor.kind == PrimitiveKind.DOUBLE -> "Double"
            descriptor.kind == PrimitiveKind.FLOAT -> "Float"
            else -> null
        }
}
