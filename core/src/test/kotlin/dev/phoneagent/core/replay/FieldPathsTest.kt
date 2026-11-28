package dev.phoneagent.core.replay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FieldPathsTest {
    private fun rewrite(
        document: String,
        vararg paths: String,
    ): String =
        FieldRewriter(paths.map { FieldPath(it) to it })
            .rewrite(Json.parseToJsonElement(document)) { rule, _, _ -> JsonPrimitive("<$rule>") }
            .toString()

    @Test
    fun `a path reads as members, elements and subtypes`() {
        assertEquals(
            listOf(
                FieldPath.Step.Member("parts"),
                FieldPath.Step.Each,
                FieldPath.Step.Type("tool_call"),
                FieldPath.Step.Member("id"),
            ),
            FieldPath("parts[].{tool_call}.id").steps,
        )
    }

    @Test
    fun `nested arrays and map values have their own steps`() {
        assertEquals(
            listOf(FieldPath.Step.Member("nested"), FieldPath.Step.Each, FieldPath.Step.Each, FieldPath.Step.AnyMember),
            FieldPath("nested[][].*").steps,
        )
    }

    @Test
    fun `an empty segment is refused`() {
        assertThrows<IllegalArgumentException> { FieldPath("a..b") }
    }

    @Test
    fun `an empty subtype is refused`() {
        assertThrows<IllegalArgumentException> { FieldPath("{}.id") }
    }

    @Test
    fun `an unclosed array step is refused`() {
        assertThrows<IllegalArgumentException> { FieldPath("parts[.id") }
    }

    @Test
    fun `a member path rewrites that member only`() {
        assertEquals("""{"a":"<a>","b":2}""", rewrite("""{"a":1,"b":2}""", "a"))
    }

    @Test
    fun `an array step rewrites every element`() {
        assertEquals("""{"a":["<a[]>","<a[]>"]}""", rewrite("""{"a":[1,2]}""", "a[]"))
    }

    @Test
    fun `a subtype step skips the other subtypes`() {
        assertEquals(
            """{"p":[{"type":"x","id":"<p[].{x}.id>"},{"type":"y","id":2}]}""",
            rewrite("""{"p":[{"type":"x","id":1},{"type":"y","id":2}]}""", "p[].{x}.id"),
        )
    }

    @Test
    fun `a map step rewrites every value`() {
        assertEquals("""{"m":{"k":"<m.*>","l":"<m.*>"}}""", rewrite("""{"m":{"k":1,"l":2}}""", "m.*"))
    }

    @Test
    fun `null and absent fields are left alone`() {
        assertEquals("""{"a":null}""", rewrite("""{"a":null}""", "a", "b"))
    }

    @Test
    fun `values are visited in document order whatever the rule order`() {
        val seen = mutableListOf<String>()
        FieldRewriter(listOf(FieldPath("b") to "b", FieldPath("a") to "a"))
            .rewrite(Json.parseToJsonElement("""{"a":1,"b":2}""")) { rule, _, value: JsonPrimitive ->
                seen += rule
                value as JsonElement
            }
        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun `a path that ends on an object fails`() {
        assertThrows<IllegalArgumentException> { rewrite("""{"a":{"b":1}}""", "a") }
    }

    @Test
    fun `two paths that end on the same value fail`() {
        assertThrows<IllegalArgumentException> { rewrite("""{"m":{"k":1}}""", "m.*", "m.k") }
    }
}
