package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.openai.LeakedToolMarkup.Invocation
import dev.phoneagent.core.model.openai.LeakedToolMarkup.Recovery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LeakedToolMarkupTest {
    private val tools = setOf("get_weather", "tap", "a", "b")

    private fun recover(text: String) = LeakedToolMarkup.recover(text, tools)

    private fun call(body: String) = "Go.\n<tool_call>$body</tool_call>"

    @Test
    fun `text without markup is not a recovery`() {
        assertNull(recover("a < b and c > d\n\n\n  keep   spacing  "))
    }

    @Test
    fun `DSML invocations become calls with typed parameters`() {
        val text =
            "I will check.\n\n<｜DSML｜function_calls>\n<｜DSML｜invoke name=\"get_weather\">\n" +
                "<｜DSML｜parameter name=\"city\" string=\"true\">Paris</｜DSML｜parameter>\n" +
                "<｜DSML｜parameter name=\"days\" string=\"false\">3</｜DSML｜parameter>\n" +
                "<｜DSML｜parameter name=\"note\" string=\"false\">not json</｜DSML｜parameter>\n" +
                "</｜DSML｜invoke>\n<|DSML|invoke name=\"tap\"></|DSML|invoke>\n</｜DSML｜function_calls>\n"
        assertEquals(
            Recovery(
                "I will check.",
                listOf(
                    Invocation("get_weather", "{\"city\":\"Paris\",\"days\":3,\"note\":\"not json\"}"),
                    Invocation("tap", "{}"),
                ),
            ),
            recover(text),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // Unnamed invocation, unnamed or repeated parameter, stray text in the body, no closing tag.
            "x <｜DSML｜invoke name=\" \"></｜DSML｜invoke>",
            "x <｜DSML｜invoke name=\"tap\"><｜DSML｜parameter name=\"\">1</｜DSML｜parameter></｜DSML｜invoke>",
            "x <｜DSML｜invoke name=\"tap\"><｜DSML｜parameter name=\"p\">1</｜DSML｜parameter>" +
                "<｜DSML｜parameter name=\"p\">2</｜DSML｜parameter></｜DSML｜invoke>",
            "x <｜DSML｜invoke name=\"tap\">p=1</｜DSML｜invoke>",
            "x <｜DSML｜invoke name=\"tap\"><｜DSML｜parameter name=\"p\">1</｜DSML｜parameter>",
            // Wrapper tags alone hold no call.
            "Done.\n<｜DSML｜function_calls>\n</｜DSML｜function_calls>",
        ],
    )
    fun `incomplete DSML is not a call`(text: String) {
        assertNull(recover(text))
    }

    @Test
    fun `tool_call JSON blocks become calls`() {
        val text =
            "Let me look.\n<tool_call>\n" +
                """{"name": "get_weather", "arguments": {"city": "Paris"}}""" +
                "\n</tool_call>\n<tool_call>" +
                """[{"name":"a","parameters":{"x":1}},{"name":"b","arguments":"{\"y\": 2}"}]</tool_call>"""
        assertEquals(
            Recovery(
                "Let me look.",
                listOf(
                    Invocation("get_weather", "{\"city\":\"Paris\"}"),
                    Invocation("a", "{\"x\":1}"),
                    Invocation("b", "{\"y\": 2}"),
                ),
            ),
            recover(text),
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

    @Test
    fun `pipe tool_call blocks become calls`() {
        assertEquals(
            Recovery("Go", listOf(Invocation("tap", "{\"x\":1}"))),
            recover("Go <|tool_call|>{\"name\":\"tap\",\"arguments\":{\"x\":1}}</|tool_call|>"),
        )
    }

    @Test
    fun `undeclared tools keep the text`() {
        assertNull(recover(call("""{"name": "delete_all", "arguments": {}}""")))
        assertNull(LeakedToolMarkup.recover(call("""{"name": "tap", "arguments": {}}"""), emptySet()))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "<tool_call>{\"name\":\"tap\",\"arguments\":{}}</tool_call>\nThat is the format.",
            "<tool_call>{\"name\":\"tap\",\"arguments\":{}}</tool_call> or <tool_call>{}</tool_call>",
            "<tool_call>{\"name\":\"tap\",\"arguments\":{}}</tool_call>\n<｜DS",
            "<tool_call><tool_call>{\"name\":\"tap\",\"arguments\":{}}</tool_call></tool_call>",
            "< tool_call>{\"name\":\"tap\",\"arguments\":{}}</tool_call>",
        ],
    )
    fun `anything but calls after the first tag keeps the text`(text: String) {
        assertNull(recover("Note: $text"))
    }

    @Test
    fun `markup in code is an example`() {
        val markup = "<tool_call>{\"name\":\"tap\",\"arguments\":{}}</tool_call>"
        assertNull(recover("Like this:\n```\n$markup"), "inside an unclosed fence")
        assertNull(recover("Like this:\n```xml\n$markup\n```"), "inside a closed fence")
        assertNull(recover("Write `$markup`"), "inline code")
        assertEquals(
            Recovery("Use `tap`:\n```\nx `y`\n```", listOf(Invocation("tap", "{}"))),
            recover("Use `tap`:\n```\nx `y`\n```\n$markup"),
            "code before the call does not hide it",
        )
    }

    @Test
    fun `the preview stops before possible markup`() {
        assertEquals(5, LeakedToolMarkup.previewEnd("Hello"))
        assertEquals(6, LeakedToolMarkup.previewEnd("Hello <｜DSML｜invoke"))
        assertEquals(6, LeakedToolMarkup.previewEnd("Hello <tool_call>{"))
        assertEquals(6, LeakedToolMarkup.previewEnd("Hello <"))
        assertEquals(6, LeakedToolMarkup.previewEnd("Hello <tool_"))
        assertEquals(6, LeakedToolMarkup.previewEnd("Hello <｜DS"))
        // Ordinary comparisons are not markup.
        assertEquals(9, LeakedToolMarkup.previewEnd("a<b and c"))
        assertEquals(11, LeakedToolMarkup.previewEnd("a <b> and c"))
    }
}
