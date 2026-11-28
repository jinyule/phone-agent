package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.openai.LeakedCallFormat.DSML
import dev.phoneagent.core.model.openai.LeakedCallFormat.TOOL_CALL_TAG
import dev.phoneagent.core.model.openai.LeakedToolMarkup.Invocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LeakedToolMarkupTest {
    private val tools = setOf("get_weather", "tap", "a", "b")
    private val both = setOf(DSML, TOOL_CALL_TAG)

    private fun recover(
        text: String,
        formats: Set<LeakedCallFormat> = both,
    ) = LeakedToolMarkup.recover(text, tools, formats)

    private fun call(body: String) = "<tool_call>$body</tool_call>"

    private val tapCall = call("""{"name":"tap","arguments":{}}""")

    private fun dsml(vararg invokes: String) =
        "<｜DSML｜function_calls>\n" + invokes.joinToString("\n") + "\n</｜DSML｜function_calls>"

    @Test
    fun `text without markup is not a recovery`() {
        assertNull(recover("a < b and c > d\n\n\n  keep   spacing  "))
        assertNull(recover(""))
        assertNull(recover(" \n "))
    }

    @Test
    fun `DSML blocks become calls with typed parameters`() {
        val text =
            "\n\n" +
                dsml(
                    "<｜DSML｜invoke name=\"get_weather\">\n" +
                        "<｜DSML｜parameter name=\"city\" string=\"true\">Paris</｜DSML｜parameter>\n" +
                        "<｜DSML｜parameter name=\"days\" string=\"false\">3</｜DSML｜parameter>\n" +
                        "<｜DSML｜parameter name=\"note\" string=\"false\">not json</｜DSML｜parameter>\n" +
                        "</｜DSML｜invoke>",
                    "<|DSML|invoke name=\"tap\"></|DSML|invoke>",
                ) + "\n" + dsml("<｜DSML｜invoke name=\"a\"></｜DSML｜invoke>") + "\n"
        assertEquals(
            listOf(
                Invocation("get_weather", "{\"city\":\"Paris\",\"days\":3,\"note\":\"not json\"}"),
                Invocation("tap", "{}"),
                Invocation("a", "{}"),
            ),
            recover(text, setOf(DSML)),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // Unnamed invocation, unnamed or repeated parameter, stray text in the body, no closing tag.
            "<｜DSML｜invoke name=\" \"></｜DSML｜invoke>",
            "<｜DSML｜invoke name=\"tap\"><｜DSML｜parameter name=\"\">1</｜DSML｜parameter></｜DSML｜invoke>",
            "<｜DSML｜invoke name=\"tap\"><｜DSML｜parameter name=\"p\">1</｜DSML｜parameter>" +
                "<｜DSML｜parameter name=\"p\">2</｜DSML｜parameter></｜DSML｜invoke>",
            "<｜DSML｜invoke name=\"tap\">p=1</｜DSML｜invoke>",
            "<｜DSML｜invoke name=\"tap\"><｜DSML｜parameter name=\"p\">1</｜DSML｜parameter>",
            // Wrapper tags alone hold no call; words between invocations are not calls.
            "",
            "<｜DSML｜invoke name=\"tap\"></｜DSML｜invoke>\nand then\n<｜DSML｜invoke name=\"a\"></｜DSML｜invoke>",
        ],
    )
    fun `incomplete DSML is not a call`(invokes: String) {
        assertNull(recover(dsml(invokes)))
    }

    @Test
    fun `DSML invocations outside their wrapper are not calls`() {
        assertNull(recover("<｜DSML｜invoke name=\"tap\"></｜DSML｜invoke>"))
        assertNull(recover("</｜DSML｜function_calls><｜DSML｜invoke name=\"tap\"></｜DSML｜invoke>"))
    }

    @Test
    fun `tool_call JSON blocks become calls`() {
        val text =
            "<tool_call>\n" +
                """{"name": "get_weather", "arguments": {"city": "Paris"}}""" +
                "\n</tool_call>\n<tool_call>" +
                """[{"name":"a","parameters":{"x":1}},{"name":"b","arguments":"{\"y\": 2}"}]</tool_call>""" +
                "\n"
        assertEquals(
            listOf(
                Invocation("get_weather", "{\"city\":\"Paris\"}"),
                Invocation("a", "{\"x\":1}"),
                Invocation("b", "{\"y\": 2}"),
            ),
            recover(text, setOf(TOOL_CALL_TAG)),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "not json",
            "42",
            "[]",
            """{"name": "tap"}""",
            """{"name": "tap", "arguments": null}""",
            """{"name": "tap", "arguments": [1]}""",
            """{"name": "tap", "arguments": "{x"}""",
            """{"name": "tap", "arguments": "[1]"}""",
            """{"name": "", "arguments": {}}""",
            """{"name": 7, "arguments": {}}""",
            """["tap"]""",
            """[{"name": "tap", "arguments": {}}, {"name": "a"}]""",
        ],
    )
    fun `incomplete tool_call JSON is not a call`(body: String) {
        assertNull(recover(call(body)))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"name": "tap", "arguments": {}, "note": "an example, do not run it"}""",
            """{"name": "tap", "arguments": {"x": 1}, "parameters": {"x": 1}}""",
            """{"name": "tap", "parameters": {}, "id": "call_1"}""",
            """{"type": "function", "name": "tap", "arguments": {}}""",
            """[{"name": "tap", "arguments": {}}, {"name": "tap", "arguments": {}, "example": true}]""",
        ],
    )
    fun `a call shell with any key besides the name and one argument key is not a call`(body: String) {
        assertNull(recover(call(body)))
    }

    @Test
    fun `keys inside the arguments are not checked`() {
        assertEquals(
            listOf(Invocation("tap", """{"note":"an example","parameters":{}}""")),
            recover(call("""{"name": "tap", "arguments": {"note": "an example", "parameters": {}}}""")),
        )
    }

    @Test
    fun `only the enabled formats are read`() {
        val dsmlTap = dsml("<｜DSML｜invoke name=\"tap\"></｜DSML｜invoke>")
        assertNull(recover(tapCall, setOf(DSML)))
        assertNull(recover(dsmlTap, setOf(TOOL_CALL_TAG)))
        assertNull(recover(tapCall, emptySet()))
        assertNull(recover("$tapCall\n$dsmlTap"), "one reply, one format")
        assertNull(recover("<|tool_call|>{\"name\":\"tap\",\"arguments\":{}}</|tool_call|>"), "no pipe variant")
    }

    @Test
    fun `undeclared tools keep the text`() {
        assertNull(recover(call("""{"name": "delete_all", "arguments": {}}""")))
        assertNull(recover("$tapCall\n" + call("""{"name": "delete_all", "arguments": {}}""")), "all or nothing")
        assertNull(LeakedToolMarkup.recover(tapCall, emptySet(), both))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Go.\n%s",
            "For example: %s",
            "仅为示例，请勿执行：%s",
            "%s\nThat is the format.",
            "%s\n不要执行。",
            "> %s",
            "%s or <tool_call>{}</tool_call>",
            "%s\n<｜DS",
            "<tool_call>%s</tool_call>",
            "```\n%s\n```",
            "`%s`",
            "< %s",
        ],
    )
    fun `anything but calls and whitespace keeps the text`(template: String) {
        assertNull(recover(template.replace("%s", tapCall)))
    }

    @Test
    fun `a call is recovered whatever whitespace surrounds it`() {
        assertEquals(listOf(Invocation("tap", "{}")), recover(" \n\t$tapCall\n\n"))
    }
}
