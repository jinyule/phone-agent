package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.openai.LeakedToolMarkup.Invocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeakedToolMarkupTest {
    @Test
    fun `text without markup is returned unchanged`() {
        val text = "a < b and c > d\n\n\n  keep   spacing  "
        assertEquals(LeakedToolMarkup.Scan(text, emptyList()), LeakedToolMarkup.scan(text))
    }

    @Test
    fun `DSML invocations become calls with typed parameters`() {
        val text =
            "I will check.\n\n<｜DSML｜function_calls>\n<｜DSML｜invoke name=\"get_weather\">\n" +
                "<｜DSML｜parameter name=\"city\" string=\"true\">Paris</｜DSML｜parameter>\n" +
                "<｜DSML｜parameter name=\"days\" string=\"false\">3</｜DSML｜parameter>\n" +
                "<｜DSML｜parameter name=\"note\" string=\"false\">not json</｜DSML｜parameter>\n" +
                "<｜DSML｜parameter name=\"\">skipped</｜DSML｜parameter>\n" +
                "</｜DSML｜invoke>\n<|DSML|invoke name=\"tap\"></|DSML|invoke>\n</｜DSML｜function_calls>"
        val scan = LeakedToolMarkup.scan(text)
        assertEquals("I will check.", scan.text)
        assertEquals(
            listOf(
                Invocation("get_weather", "{\"city\":\"Paris\",\"days\":3,\"note\":\"not json\"}"),
                Invocation("tap", "{}"),
            ),
            scan.calls,
        )
    }

    @Test
    fun `stray DSML tags are stripped even without a call`() {
        val scan = LeakedToolMarkup.scan("Done.\n<｜DSML｜function_calls>\n</｜DSML｜function_calls>")
        assertEquals(LeakedToolMarkup.Scan("Done.", emptyList()), scan)
        // An invoke without a name is not a call.
        val unnamed = LeakedToolMarkup.scan("x <｜DSML｜invoke name=\" \">y</｜DSML｜invoke>")
        assertTrue(unnamed.calls.isEmpty())
    }

    @Test
    fun `tool_call JSON blocks become calls, others stay`() {
        val text =
            "Let me look.\n<tool_call>\n" +
                """{"name": "get_weather", "arguments": {"city": "Paris"}}""" +
                "\n</tool_call>\n<tool_call>" +
                """[{"name":"a","parameters":{"x":1}},{"name":"b","arguments":"{}"},{"name":"c"},""" +
                """{"name":""},{"name":"d","arguments":null}]</tool_call>"""
        val scan = LeakedToolMarkup.scan(text)
        assertEquals("Let me look.", scan.text)
        assertEquals(
            listOf(
                Invocation("get_weather", "{\"city\":\"Paris\"}"),
                Invocation("a", "{\"x\":1}"),
                Invocation("b", "{}"),
                Invocation("c", "{}"),
                Invocation("d", "{}"),
            ),
            scan.calls,
        )
        val kept = "Plan:\n<tool_call>not json</tool_call>\n<tool_call>42</tool_call>"
        assertEquals(LeakedToolMarkup.Scan(kept, emptyList()), LeakedToolMarkup.scan(kept))
    }

    @Test
    fun `pipe tool_call blocks and half tags at the end are handled`() {
        val scan = LeakedToolMarkup.scan("Go <|tool_call|>{\"name\":\"tap\",\"arguments\":[1]}</|tool_call|> now <｜DS")
        assertEquals(LeakedToolMarkup.Scan("Go   now", listOf(Invocation("tap", "[1]"))), scan)
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
