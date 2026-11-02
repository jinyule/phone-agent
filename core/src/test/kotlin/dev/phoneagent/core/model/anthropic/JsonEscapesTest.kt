package dev.phoneagent.core.model.anthropic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * [JsonEscapes.lenientDecode]: one left-to-right pass that decodes the JSON string escapes and
 * keeps anything it does not recognize as written. The cases are shared with the OpenAI adapters'
 * copy (WP03); keep the two lists the same.
 */
class JsonEscapesTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `decodes what JSON would and keeps the rest`(
        name: String,
        raw: String,
        decoded: String,
    ) = assertEquals(decoded, JsonEscapes.lenientDecode(raw), name)

    companion object {
        /** `(name, raw, decoded)`; written with [BS] so every backslash is visible. */
        @JvmStatic
        fun cases(): List<Array<String>> =
            listOf(
                case("no escapes", "plain text 中文", "plain text 中文"),
                case("unicode escape", "${BS}u0070lain", "plain"),
                case("unicode escape, hex in either case", "${BS}u004a${BS}u004A", "JJ"),
                case("escaped slash", "slash$BS/key", "slash/key"),
                case("escaped quote", "$BS\"q$BS\"", "\"q\""),
                case("escaped backslash", "a$BS${BS}b", "a${BS}b"),
                case("control escapes", "$BS" + "n$BS" + "t$BS" + "r$BS" + "b$BS" + "f", "\n\t\r\b\u000C"),
                case("escaped backslash before u is decoded once", "$BS${BS}u0070", "${BS}u0070"),
                case("escaped backslash before slash", "$BS$BS$BS/", "$BS/"),
                case("surrogate pair", "${BS}uD83D${BS}uDE00", "😀"),
                case("lone high surrogate", "${BS}uD83Dx", "${BS}uD83Dx"),
                case("high surrogate before another high", "${BS}uD83D${BS}uD83D${BS}uDE00", "${BS}uD83D😀"),
                case("lone low surrogate", "${BS}uDE00", "${BS}uDE00"),
                case("incomplete unicode escape at the end", "key${BS}u00", "key${BS}u00"),
                case("unicode escape with a non-hex digit", "${BS}u00G0", "${BS}u00G0"),
                case("unknown escape", "${BS}x41${BS}a", "${BS}x41${BS}a"),
                case("backslash at the end", "abc$BS", "abc$BS"),
            )

        /** One backslash. */
        private const val BS = "\\"

        private fun case(
            name: String,
            raw: String,
            decoded: String,
        ) = arrayOf(name, raw, decoded)
    }
}
