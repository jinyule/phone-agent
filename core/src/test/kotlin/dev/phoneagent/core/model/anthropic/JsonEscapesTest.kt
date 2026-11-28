package dev.phoneagent.core.model.anthropic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * [JsonEscapes.lenientDecode], the decoding that lets call arguments that are not JSON (cut, or
 * broken) be checked for a secret behind an escape. The same cases as the OpenAI adapters' copy
 * (WP03), plus the last three. In the data `~` stands for a backslash; `<U+XXXX>` in an
 * expectation for that character.
 */
class JsonEscapesTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `decodes the JSON escapes and keeps everything else`(
        case: String,
        raw: String,
        decoded: String,
    ) {
        assertEquals(expected(decoded), JsonEscapes.lenientDecode(raw.replace('~', BACKSLASH)), case)
    }

    @Test
    fun `text without a backslash comes back as it is`() {
        val text = "{\"city\":\"Paris\"}"
        assertSame(text, JsonEscapes.lenientDecode(text))
    }

    private companion object {
        val BACKSLASH = Char(92)
        val CHARACTER = Regex("<U\\+([0-9A-F]{4})>")

        /** [decoded] with `~` as a backslash and each `<U+XXXX>` as its character. */
        fun expected(decoded: String): String =
            CHARACTER.replace(decoded.replace('~', BACKSLASH)) { Char(it.groupValues[1].toInt(HEX)).toString() }

        const val HEX = 16

        @JvmStatic
        fun cases(): List<Arguments> =
            listOf(
                Arguments.of("slash", "a~/b", "a/b"),
                Arguments.of("quote", "a~\"b", "a\"b"),
                Arguments.of("backslash", "a~~b", "a~b"),
                Arguments.of("newline", "a~nb", "a<U+000A>b"),
                Arguments.of("tab", "a~tb", "a<U+0009>b"),
                Arguments.of("carriage return", "a~rb", "a<U+000D>b"),
                Arguments.of("backspace", "a~bb", "a<U+0008>b"),
                Arguments.of("form feed", "a~fb", "a<U+000C>b"),
                Arguments.of("unicode escape", "~u0070lain", "plain"),
                Arguments.of("unicode escape, upper and mixed case hex", "~u004A~u004a~u00e9", "JJ<U+00E9>"),
                Arguments.of("surrogate pair", "~uD83D~uDE00!", "<U+D83D><U+DE00>!"),
                Arguments.of("escaped backslash before u is decoded once", "~~u0070", "~u0070"),
                Arguments.of("escaped backslash then an escape", "~~~u0070", "~p"),
                Arguments.of("incomplete unicode escape", "~u007", "~u007"),
                Arguments.of("unicode escape with a non-hex digit", "~u00G0", "~u00G0"),
                Arguments.of("unicode escape with a sign", "~u+070", "~u+070"),
                Arguments.of("unicode escape cut at the end", "ab~u", "ab~u"),
                Arguments.of("lone high surrogate", "~uD83Dx", "~uD83Dx"),
                Arguments.of("high surrogate before a non-low escape", "~uD83D~u0041", "~uD83DA"),
                Arguments.of("high surrogate before a cut low escape", "~uD83D~uDE0", "~uD83D~uDE0"),
                Arguments.of("lone low surrogate", "~uDE00x", "~uDE00x"),
                Arguments.of("unknown escape", "~x~a~0", "~x~a~0"),
                Arguments.of("backslash at the end", "abc~", "abc~"),
                Arguments.of("cut arguments", "{\"city\":\"~u0070lain~/key", "{\"city\":\"plain/key"),
                Arguments.of("empty", "", ""),
                Arguments.of("high surrogate before another high", "~uD83D~uD83D~uDE00", "~uD83D<U+D83D><U+DE00>"),
                Arguments.of("escaped backslash before slash", "~~~/", "~/"),
                Arguments.of("no escapes, not ASCII", "天气 café", "天气 café"),
            )
    }
}
