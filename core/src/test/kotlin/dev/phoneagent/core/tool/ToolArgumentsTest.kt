package dev.phoneagent.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Argument checks against a tool's schema (phase1 §3.2). */
class ToolArgumentsTest {
    private fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun value(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun problems(
        schema: String,
        value: String,
    ): List<String> = ToolArguments.problems(schema(schema), value(value))

    private fun conforms(
        schema: String,
        vararg values: String,
    ) {
        for (value in values) assertEquals(emptyList<String>(), problems(schema, value), value)
    }

    @Test
    fun `types are checked strictly and never coerced`() {
        conforms("""{"type":"string"}""", "\"x\"")
        conforms("""{"type":"integer"}""", "3", "3.0", "-1")
        conforms("""{"type":"number"}""", "3.5", "3")
        conforms("""{"type":"boolean"}""", "true")
        conforms("""{"type":"null"}""", "null")
        conforms("""{"type":"object"}""", "{}")
        conforms("""{"type":"array"}""", "[]")
        assertEquals(listOf("arguments must be integer, got 3.5"), problems("""{"type":"integer"}""", "3.5"))
        assertEquals(listOf("arguments must be integer, got \"3\""), problems("""{"type":"integer"}""", "\"3\""))
        assertEquals(listOf("arguments must be number, got \"3\""), problems("""{"type":"number"}""", "\"3\""))
        assertEquals(listOf("arguments must be boolean, got \"true\""), problems("""{"type":"boolean"}""", "\"true\""))
        assertEquals(listOf("arguments must be boolean, got 1"), problems("""{"type":"boolean"}""", "1"))
        assertEquals(listOf("arguments must be string, got 1"), problems("""{"type":"string"}""", "1"))
        assertEquals(listOf("arguments must be null, got {}"), problems("""{"type":"null"}""", "{}"))
        assertEquals(listOf("arguments must be object, got []"), problems("""{"type":"object"}""", "[]"))
        assertEquals(listOf("arguments must be array, got {}"), problems("""{"type":"array"}""", "{}"))
    }

    @Test
    fun `a list of types accepts any of them and unknown names are ignored`() {
        conforms("""{"type":["string","null"]}""", "\"x\"", "null")
        assertEquals(listOf("arguments must be string or null, got 1"), problems("""{"type":["string","null"]}""", "1"))
        conforms("""{"type":"date"}""", "1", "\"x\"")
        conforms("""{"type":["integer","date"]}""", "\"x\"")
        conforms("""{"type":[{"nested":true}]}""", "1")
        conforms("""{"type":{"weird":true}}""", "1")
        conforms("""{}""", "1")
    }

    @Test
    fun `enum and const`() {
        conforms("""{"enum":["a","b"]}""", "\"a\"")
        assertEquals(
            listOf("arguments must be one of [\"a\",\"b\"], got \"c\""),
            problems("""{"enum":["a","b"]}""", "\"c\""),
        )
        conforms("""{"enum":"not a list"}""", "\"c\"")
        conforms("""{"const":7}""", "7")
        assertEquals(listOf("arguments must be 7, got 8"), problems("""{"const":7}""", "8"))
    }

    @Test
    fun `bounds on numbers, lengths and sizes`() {
        val number = """{"minimum":1,"maximum":2.5}"""
        conforms(number, "1", "2.5")
        assertEquals(listOf("arguments must be >= 1, got 0"), problems(number, "0"))
        assertEquals(listOf("arguments must be <= 2.5, got 3"), problems(number, "3"))
        val text = """{"minLength":2,"maxLength":3}"""
        conforms(text, "\"ab\"", "\"abc\"", "5", "[1]")
        assertEquals(listOf("arguments must have at least 2 characters"), problems(text, "\"a\""))
        assertEquals(listOf("arguments must have at most 3 characters"), problems(text, "\"abcd\""))
        val list = """{"minItems":1,"maxItems":2}"""
        conforms(list, "[1]", "[1,2]", "\"\"")
        assertEquals(listOf("arguments must have at least 1 items"), problems(list, "[]"))
        assertEquals(listOf("arguments must have at most 2 items"), problems(list, "[1,2,3]"))
        conforms("""{"minimum":"5","maxItems":null}""", "1", "[1,2]")
        conforms(number, "\"0\"")
    }

    @Test
    fun `objects check required, declared and additional properties`() {
        val closed =
            """{"type":"object","properties":{"n":{"type":"integer"},"reason":{"type":"string"},"free":true},
               "required":["n","reason"],"additionalProperties":false}"""
        assertEquals(
            listOf(
                "arguments.reason is required",
                "arguments.n must be integer, got \"x\"",
                "arguments.extra is not a known argument",
            ),
            problems(closed, """{"n":"x","free":1,"extra":2}"""),
        )
        conforms(closed, """{"n":1,"reason":"r","free":"anything"}""")
        val typed = """{"properties":{"n":{"type":"integer"}},"additionalProperties":{"type":"string"}}"""
        conforms(typed, """{"n":1,"x":"y"}""")
        assertEquals(listOf("arguments.x must be string, got 1"), problems(typed, """{"x":1}"""))
        conforms("""{"required":"n","properties":[]}""", """{"x":1}""")
        conforms("""{"required":[1]}""", "{}")
    }

    @Test
    fun `array items are checked with their index`() {
        val nested = """{"type":"array","items":{"type":"object","properties":{"k":{"type":"string"}}}}"""
        assertEquals(listOf("arguments[1].k must be string, got 2"), problems(nested, """[{"k":"a"},{"k":2}]"""))
        conforms("""{"type":"array","items":[{"type":"string"}]}""", "[1]")
    }

    @Test
    fun `anyOf needs one match and oneOf exactly one`() {
        val any = """{"anyOf":[{"type":"string"},{"type":"integer","minimum":0}, "junk"]}"""
        conforms(any, "\"x\"", "3")
        assertEquals(
            listOf(
                "arguments matches none of the allowed forms: " +
                    "[arguments must be string, got -1, arguments must be >= 0, got -1]",
            ),
            problems(any, "-1"),
        )
        val one = """{"oneOf":[{"type":"integer"},{"type":"number"}]}"""
        conforms(one, "1.5")
        assertEquals(listOf("arguments must match exactly one of the allowed forms, matches 2"), problems(one, "1"))
        assertEquals(listOf("arguments must match exactly one of the allowed forms, matches 0"), problems(one, "\"x\""))
        conforms("""{"anyOf":{},"oneOf":"x"}""", "1")
    }

    @Test
    fun `numbers are compared exactly, as their decimal values`() {
        assertEquals(
            listOf("arguments must be integer, got 1.00000000000000001"),
            problems("""{"type":"integer"}""", "1.00000000000000001"),
        )
        conforms("""{"type":"integer"}""", "1e2", "9007199254740993", "-0.0")
        assertEquals(
            listOf("arguments must be <= 9007199254740992, got 9007199254740993"),
            problems("""{"type":"integer","maximum":9007199254740992}""", "9007199254740993"),
        )
        assertEquals(
            listOf("arguments must be >= 0.1, got 0.09999999999999999"),
            problems("""{"type":"number","minimum":0.1}""", "0.09999999999999999"),
        )
        conforms("""{"type":"number","minimum":1e-1,"maximum":1E2}""", "0.1", "100", "1.0e2")
    }

    @Test
    fun `enum and const compare JSON values, so 1 and 1 point 0 are equal`() {
        conforms("""{"enum":[1,"a",[1,{"k":2}]]}""", "1.0", "1", "\"a\"", "[1.0,{\"k\":2.00}]")
        conforms("""{"const":{"n":10}}""", "{\"n\":1e1}")
        assertEquals(listOf("arguments must be one of [1], got \"1\""), problems("""{"enum":[1]}""", "\"1\""))
        assertEquals(listOf("arguments must be one of [[1,2]], got [1]"), problems("""{"enum":[[1,2]]}""", "[1]"))
        assertEquals(
            listOf("arguments must be {\"n\":1}, got {\"m\":1}"),
            problems("""{"const":{"n":1}}""", "{\"m\":1}"),
        )
        assertEquals(listOf("arguments must be true, got 1"), problems("""{"const":true}""", "1"))
        assertEquals(listOf("arguments must be null, got 0"), problems("""{"const":null}""", "0"))
    }

    @Test
    fun `string lengths count characters, not UTF-16 units`() {
        conforms("""{"type":"string","maxLength":1}""", "\"😀\"", "\"好\"")
        assertEquals(
            listOf("arguments must have at least 2 characters"),
            problems("""{"type":"string","minLength":2}""", "\"😀\""),
        )
    }
}
