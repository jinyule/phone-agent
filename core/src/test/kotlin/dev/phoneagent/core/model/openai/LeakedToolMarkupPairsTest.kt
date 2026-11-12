package dev.phoneagent.core.model.openai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

/**
 * How [LeakedToolMarkup] finds its blocks (fix-list r10b): each open tag with the first close tag
 * after it, as the lazy patterns it replaced found them, on random text; and a bounded number of
 * reads of each character on open tags without close tags, which those patterns took time growing
 * with its square to read.
 */
class LeakedToolMarkupPairsTest {
    /** Open tags without close tags, in about 64 KiB of answer text. */
    enum class OpenTags(
        val text: String,
    ) {
        TOOL_CALL("<tool_call>".repeat(TOOL_CALL_OPENS)),
        FUNCTION_CALLS(FUNCTION_CALLS_OPEN.repeat(LIMIT / FUNCTION_CALLS_OPEN.length)),
        INVOKE(block(INVOKE_OPEN.repeat(LIMIT / INVOKE_OPEN.length - 2))),
        PARAMETER(block(INVOKE_OPEN + PARAMETER_OPEN.repeat(LIMIT / PARAMETER_OPEN.length - 3) + INVOKE_CLOSE)),
        UNQUOTED_NAMES(block(UNQUOTED_NAME.repeat(LIMIT / UNQUOTED_NAME.length - 2))),
    }

    @ParameterizedTest
    @EnumSource(OpenTags::class)
    fun `each character is read a bounded number of times`(tags: OpenTags) {
        val reads =
            LeakedCallFormat.entries.associateWith { format ->
                val text = CountingChars(tags.text)
                assertNull(LeakedToolMarkup.recover(text, TOOLS, setOf(format)), "$tags, $format")
                text.most()
            }
        assertAll(reads.map { (format, most) -> { assertTrue(most <= MAX_READS, "$tags, $format: $most reads") } })
    }

    @Test
    fun `a close tag written in an open tag's name does not close it`() {
        val invoke = "<|DSML|invoke name=\"</|DSML|invoke>\">x</|DSML|invoke>"
        val parameter = "<|DSML|parameter name=\"</|DSML|parameter>\" string=\"true\">1</|DSML|parameter>"
        assertAll(
            { assertEquals(listOf(invoke.indices to listOf("</|DSML|invoke>", "x")), seen(invoke, Tag.INVOKE)) },
            {
                val expected = listOf(parameter.indices to listOf("</|DSML|parameter>", "true", "1"))
                assertEquals(expected, seen(parameter, Tag.PARAMETER))
            },
        )
    }

    @Test
    fun `random text is paired as the lazy patterns paired it`() {
        val random = Random(SEED)
        repeat(CASES) { _ ->
            val text = buildString { repeat(random.nextInt(MAX_PIECES)) { append(PIECES.random(random)) } }
            for ((tag, old) in OLD_BLOCKS) {
                val expected = old.findAll(text).map { it.range to it.groupValues.drop(1) }.toList()
                assertEquals(expected, seen(text, tag), "$tag in [$text]")
            }
        }
    }

    /** Each pair of [tag] in [text]: where it stands, the open tag's groups and the body. */
    private fun seen(
        text: String,
        tag: Tag,
    ) = pairs(text, tag).map { it.range to it.open.groupValues.drop(1) + it.body.toString() }

    private companion object {
        const val TOOL_CALL_OPENS = 5957
        const val FUNCTION_CALLS_OPEN = "<｜DSML｜function_calls>"
        const val FUNCTION_CALLS_CLOSE = "</｜DSML｜function_calls>"
        const val INVOKE_OPEN = "<｜DSML｜invoke name=\"tap\">"
        const val INVOKE_CLOSE = "</｜DSML｜invoke>"
        const val PARAMETER_OPEN = "<｜DSML｜parameter name=\"x\" string=\"false\">"
        const val UNQUOTED_NAME = "<｜DSML｜invoke name=\""

        fun block(body: String) = FUNCTION_CALLS_OPEN + body + FUNCTION_CALLS_CLOSE

        val TOOLS = setOf("tap")

        /** The blocks as they were found: an open tag, `(.*?)`, a close tag. */
        const val BAR = "[｜|]"
        const val DSML = BAR + "{1,2}\\s*DSML\\s*" + BAR + "{1,2}"
        val OLD_BLOCKS =
            mapOf(
                Tag.FUNCTION_CALLS to
                    Regex(
                        "<" + DSML + "\\s*function_calls\\s*>(.*?)</" + DSML + "\\s*function_calls\\s*>",
                        RegexOption.DOT_MATCHES_ALL,
                    ),
                Tag.INVOKE to
                    Regex(
                        "<" + DSML + "\\s*invoke\\s+name\\s*=\\s*\"([^\"]*)\"\\s*>(.*?)</" + DSML + "\\s*invoke\\s*>",
                        RegexOption.DOT_MATCHES_ALL,
                    ),
                Tag.PARAMETER to
                    Regex(
                        "<" + DSML + "\\s*parameter\\s+name\\s*=\\s*\"([^\"]*)\"" +
                            "(?:\\s+string\\s*=\\s*\"(true|false)\")?\\s*>(.*?)</" + DSML + "\\s*parameter\\s*>",
                        RegexOption.DOT_MATCHES_ALL,
                    ),
                Tag.TOOL_CALL to Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL),
            )

        /** Whole tags in their spellings, their pieces, and what stands between them. */
        val PIECES =
            listOf("<tool_call>", "<tool_call>", "</tool_call>", "</tool_call>", "<tool_call", "tool_call>") +
                listOf(FUNCTION_CALLS_OPEN, FUNCTION_CALLS_CLOSE) +
                listOf("<|DSML|function_calls >", "</||DSML||function_calls>") +
                listOf(INVOKE_OPEN, INVOKE_CLOSE, "<| DSML |invoke name = \"a b\" >", "</|DSML|invoke\n>") +
                listOf(PARAMETER_OPEN, "<｜DSML｜parameter name=\"y\">") +
                listOf("<|DSML|parameter name=\"z\" string=\"true\">") +
                listOf("<|DSML|parameter name=\"q\" string=\"maybe\">") +
                listOf("</｜DSML｜parameter>", "</|DSML|parameter>") +
                listOf("<|DSML|invoke name=\"</|DSML|invoke>\">", "<|DSML|parameter name=\"</|DSML|parameter>\">") +
                listOf("<", "</", "<|", "<｜", "｜", "|", "||", "DSML", "｜DSML｜", "|DSML|", ">", "\"", "\"a\"") +
                listOf("function_calls", "invoke", "parameter", " name=", " name=\"", " string=\"false\"") +
                listOf(" ", "\n", "\t", "x", "{}", "{\"name\":\"tap\",\"arguments\":{}}", "[", "]", "1", "<<", ">>")

        const val SEED = 10
        const val CASES = 100_000
        const val MAX_PIECES = 16

        /** About as much as the cells hold. */
        const val LIMIT = 64 * 1024

        /** Reads of one character the search stays within, whatever the length. */
        const val MAX_READS = 16
    }
}
