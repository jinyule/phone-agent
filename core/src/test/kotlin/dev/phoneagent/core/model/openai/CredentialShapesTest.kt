package dev.phoneagent.core.model.openai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

/**
 * The credential shapes of [Redactor] (model-layer §2, fix-list r10b): their rules case by case;
 * the same results as the regular expressions they replaced, on random text; and a bounded number
 * of reads of each character on text those expressions took time growing with its square to read.
 */
class CredentialShapesTest {
    private fun masked(text: String) = Redactor.SHAPES_ONLY.text(text)

    private fun openEnd(text: String) = Redactor.SHAPES_ONLY.openEnd(text)

    @Test
    fun `a key starts a word and has eight key characters or more`() =
        assertAll(
            { assertEquals("id *** end", masked("id sk-abcdefgh end")) },
            { assertEquals("id sk-abcdefg end", masked("id sk-abcdefg end"), "seven") },
            { assertEquals("x-***.y", masked("x-sk-ab_d-fgh.y"), "after a dash, up to the dot") },
            { assertEquals("xsk-abcdefgh", masked("xsk-abcdefgh"), "after an ASCII letter") },
            { assertEquals("_sk-abcdefgh", masked("_sk-abcdefgh"), "after an underscore") },
            { assertEquals("é***", masked("ésk-abcdefgh"), "after a letter that is not ASCII") },
            { assertEquals("用***", masked("用sk-abcdefgh"), "after a CJK character") },
            { assertEquals("a${MARK}sk-abcdefgh", masked("a${MARK}sk-abcdefgh"), "after a mark on a letter") },
            { assertEquals("٣$MARK$MARK" + "sk-abcdefgh", masked("٣$MARK${MARK}sk-abcdefgh"), "marks on a digit") },
            { assertEquals("$MARK***", masked("${MARK}sk-abcdefgh"), "after a mark on nothing") },
            { assertEquals(" $MARK***", masked(" ${MARK}sk-abcdefgh"), "after a mark on a space") },
        )

    @Test
    fun `a JWT is three parts joined by dots, the first two of eight key characters or more`() =
        assertAll(
            { assertEquals("jwt *** end", masked("jwt ${jwt("abcdefgh", "abcdefgh", "sig")} end")) },
            { assertEquals("jwt ***", masked("jwt " + jwt("abcdefgh", "abcdefgh", ""))) },
            { assertEquals("***,x", masked(jwt("abcdefgh", "abcdefgh", "s-_9") + ",x")) },
            { jwt("abcdefgh", "abcdefgh", null).let { assertEquals(it, masked(it), "no second dot") } },
            { (JWT + "abcdefghabcdefgh").let { assertEquals(it, masked(it), "no dot") } },
            { jwt("abcdefg", "abcdefgh", "s").let { assertEquals(it, masked(it), "a header of seven") } },
            { jwt("abcdefgh", "abcdefg", "s").let { assertEquals(it, masked(it), "a payload of seven") } },
            { ("x" + jwt("abcdefgh", "abcdefgh", "s")).let { assertEquals(it, masked(it), "after a letter") } },
            { assertEquals("***!", masked("eyJ-".repeat(3) + jwt("abcdefgh", "abcdefgh", "s") + "!"), "in a run") },
        )

    @Test
    fun `a shape open at the end starts a word and holds only its characters up to the end`() =
        assertAll(
            { assertEquals(6, openEnd("cut at sk-abc")) },
            { assertEquals(0, openEnd("cut at sk-abc!")) },
            { assertEquals(8, openEnd("cut at eyJab.cd")) },
            { assertEquals(0, openEnd("cut at sk-ab.cd"), "a dot in a key") },
            { assertEquals(5, openEnd("x.sk-ab"), "a key after a dot") },
            { assertEquals(9, openEnd("eyJa.sk-b"), "the first start wins") },
            { assertEquals(0, openEnd("xsk-ab"), "after an ASCII letter") },
            { assertEquals(5, openEnd("用sk-ab"), "after a CJK character") },
            { assertEquals(0, openEnd("a${MARK}sk-ab"), "after a mark on a letter, before the run") },
            { assertEquals(0, openEnd("a$MARK${MARK}eyJab"), "after marks on a letter, before the run") },
            { assertEquals(5, openEnd("${MARK}eyJab"), "after a mark on nothing") },
            { assertEquals(3, openEnd("eyJ"), "the whole text") },
            { assertEquals(0, openEnd("")) },
        )

    /** Text the replaced expressions took time growing with its square to read. */
    enum class NearMisses(
        val text: String,
    ) {
        JWT_DASHES("eyJ-".repeat(LIMIT / 4 - 1) + "!"),
        JWT_DOTS("eyJ.".repeat(LIMIT / 4 - 1) + "!"),
        JWT_SHORT_PAYLOAD("eyJ-".repeat(LIMIT / 4 - 1) + ".x!"),
        JWT_NO_SECOND_DOT("eyJ-".repeat(LIMIT / 4 - 3) + ".abcdefgh!"),
        KEY_DASHES("sk-".repeat(LIMIT / 3 - 1) + "!"),
        SHORT_KEYS("sk-abcdefg ".repeat(LIMIT / 11)),
        KEYS_IN_WORDS("xsk-".repeat(LIMIT / 4)),
        MARKS("a" + MARK.repeat(LIMIT - 10) + "sk-ab"),
    }

    @ParameterizedTest
    @EnumSource(NearMisses::class)
    fun `each character is read a bounded number of times`(near: NearMisses) {
        fun most(scan: (CharSequence) -> Any?) = CountingChars(near.text).also { scan(it) }.most()
        val reads =
            Shape.entries.associate { shape -> "mask $shape" to most { CredentialShapes.mask(it, shape) } } +
                ("open end" to most { CredentialShapes.openStart(it) })
        assertAll(reads.map { (what, most) -> { assertTrue(most <= MAX_READS, "$near, $what: $most reads") } })
    }

    @Test
    fun `random text is masked as the regular expressions masked it`() {
        val random = Random(SEED)
        repeat(CASES) {
            val text = buildString { repeat(random.nextInt(MAX_PIECES)) { append(PIECES.random(random)) } }
            val from = random.nextInt(text.length + 1)
            assertEquals(oldText(text), masked(text), "text of [$text]")
            assertEquals(oldQuote(text), Redactor.SHAPES_ONLY.quote(text), "quote of [$text]")
            val oldOpen = OLD_OPEN.find(text)?.let { open -> text.length - open.range.first } ?: 0
            assertEquals(oldOpen, openEnd(text), "open end of [$text]")
            for ((shape, old) in Shape.entries.zip(OLD_SHAPES)) {
                val found = CredentialShapes.find(text, from, shape)
                assertEquals(old.find(text, from)?.range, found, "$shape from $from in [$text]")
            }
        }
    }

    /** What [Redactor.text] gave with the replaced expressions, for text without `bearer` and no secrets. */
    private fun oldText(text: String) = OLD_SHAPES.fold(text) { acc, shape -> shape.replace(acc, MASK) }

    /** What [Redactor.quote] gave with the replaced expressions, for text without `bearer` and no secrets. */
    private fun oldQuote(raw: String): String {
        val decoded = JsonEscapes.lenientDecode(raw)
        val hidden = decoded != raw && OLD_SHAPES.any { it.containsMatchIn(decoded) }
        return if (hidden) Redactor.WITHHELD else oldText(raw)
    }

    private companion object {
        /** Built here: tool inputs decode escapes written out. */
        val MARK = Char(COMBINING_ACUTE).toString()
        val BS = Char(BACKSLASH).toString()
        val SUPPLEMENTARY_LETTER = String(Character.toChars(MATH_BOLD_A))
        val SUPPLEMENTARY_MARK = String(Character.toChars(COMBINING_TREMOLO))

        /** Assembled so secret scanners do not take it for a token. */
        const val JWT = "ey" + "J"

        fun jwt(
            header: String,
            payload: String,
            signature: String?,
        ) = JWT + header + "." + payload + signature?.let { ".$it" }.orEmpty()

        /** The rules as they were: `sk-` keys, JWTs, and a shape open at the end. */
        val OLD_SHAPES =
            listOf(
                Regex("""\bsk-[A-Za-z0-9_-]{8,}"""),
                Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*"""),
            )
        val OLD_OPEN = Regex("""(?:\bsk-[A-Za-z0-9_-]*|\beyJ[A-Za-z0-9_.-]*)\z""")

        /**
         * Starts of shapes, their parts and what ends them, characters before a word and the escapes
         * quote decodes; no `r`, so no `bearer` forms and only the shapes change the text.
         */
        val PIECES =
            listOf("sk-", "sk-", "sk", "eyJ", "eyJ", JWT + "abcdefgh.", "ey", "J", "s", "-", "-", ".", ".", "_") +
                listOf("a", "Z", "0", "abcdefgh", "abcdefgh", "Q7_x-9kM", "abcdefg", " ", "!", "*", "\"", ",") +
                listOf("é", "用", "٣", MARK, MARK, SUPPLEMENTARY_LETTER, SUPPLEMENTARY_MARK) +
                listOf(BS + "n", BS + "/", BS + "u0065", BS + "u0073", BS)

        const val MASK = "***"
        const val SEED = 10
        const val CASES = 100_000
        const val MAX_PIECES = 16

        /** What HttpTransport reads of an error body. */
        const val LIMIT = 64 * 1024

        /** Reads of one character the scans stay within, whatever the length. */
        const val MAX_READS = 16

        const val COMBINING_ACUTE = 0x0301
        const val BACKSLASH = 92
        const val MATH_BOLD_A = 0x1D400
        const val COMBINING_TREMOLO = 0x1D167
    }
}
