package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.Screens.snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Seeded texts with long space runs, line breaks, card words, order labels and numbers. The screen
 * exit (an element's text in [ScreenFormatter.page]) must mask every digit that masking the text
 * folded to one line masks, so its labels may differ (a span across lines is neutral) but never
 * what they cover. With the labels made one mark, both outputs are compared whole: the exit masks
 * exactly those digits, so none fewer.
 */
class ScreenMaskingPropertyTest {
    private val formatter = ScreenFormatter(lexicon = Screens.LEXICON)

    @Test
    fun `the screen exit masks every digit that masking the one-line text masks`() {
        val random = Random(SEED)
        val misses =
            (1..SAMPLES).mapNotNull { _ ->
                val text = sample(random)
                val shown = exit(text)
                val oneLine = SensitiveNumbers.mask(oneLine(text))
                if (unified(shown) == unified(oneLine)) null else "${visible(text)} -> $shown / $oneLine"
            }
        assertEquals(emptyList<String>(), misses.take(SHOWN), "${misses.size} of $SAMPLES samples differ")
    }

    /** The quoted text of a one-element page at `full`, where nothing is clipped. */
    private fun exit(text: String): String {
        val line =
            formatter
                .page(snapshot("s1", listOf(el("e1", ElementRole.TEXT, text))), SnapshotDetail.FULL)
                .lines()
                .single { it.startsWith("@e1 ") }
        return line.removePrefix("@e1 \"").removeSuffix("\"")
    }

    private fun unified(text: String): String = LABEL.replace(text, MARK)

    private fun sample(random: Random): String =
        "字" + (1..2 + random.nextInt(PIECES)).joinToString("") { piece(random) + pick(random, gaps(random)) }

    private fun piece(random: Random): String =
        when (random.nextInt(KINDS)) {
            0 -> grouped(card(random), random)
            1 -> grouped(id(random), random)
            2 -> phone(random)
            3 -> digits(random, 1 + random.nextInt(SHORT_DIGITS))
            4 -> pick(random, CARD_WORDS)
            5 -> pick(random, ORDER_LABELS)
            else -> pick(random, FIELD_WORDS)
        }

    /** Gaps between pieces: one space, long space runs, line breaks of every kind, or nothing. */
    private fun gaps(random: Random): List<String> =
        listOf(
            "",
            " ",
            " ".repeat(1 + random.nextInt(LONG_SPACES)),
            "\n",
            "\r\n",
            "$NEL",
            "$LINE_SEPARATOR",
            "\n".repeat(1 + random.nextInt(LONG_LINES)),
            "\n" + " ".repeat(random.nextInt(LONG_SPACES)),
            "\t\t",
            "$ZERO_WIDTH",
        )

    /** [number] whole or in groups of four, joined by spaces. */
    private fun grouped(
        number: String,
        random: Random,
    ): String = if (random.nextBoolean()) number else number.chunked(GROUP).joinToString(" ")

    /** A Luhn-valid 16-digit card number. */
    private fun card(random: Random): String {
        val body = digits(random, CARD_DIGITS - 1)
        return ('0'..'9').map { body + it }.single(SensitiveNumbers::isLuhnValid)
    }

    /** A valid resident id born in 1950–1999. */
    private fun id(random: Random): String {
        val body =
            "110105" + (FIRST_YEAR + random.nextInt(YEARS)) +
                "${1 + random.nextInt(MONTHS)}".padStart(2, '0') +
                "${1 + random.nextInt(SAFE_DAYS)}".padStart(2, '0') + digits(random, SEQUENCE)
        return "0123456789X".map { body + it }.single(SensitiveNumbers::isResidentIdValid)
    }

    /** A mobile number grouped 3-4-4. */
    private fun phone(random: Random): String {
        val number = "1" + (3 + random.nextInt(PHONE_SECOND)) + digits(random, PHONE_DIGITS - 2)
        return number.substring(0, 3) + " " + number.substring(3, 7) + " " + number.substring(7)
    }

    private fun digits(
        random: Random,
        count: Int,
    ): String = (1..count).joinToString("") { "${random.nextInt(RADIX)}" }

    private fun <T> pick(
        random: Random,
        from: List<T>,
    ): T = from[random.nextInt(from.size)]

    private fun visible(text: String): String =
        text.map { if (it.isWhitespace() || it == NEL || it == ZERO_WIDTH) "<${it.code}>" else "$it" }.joinToString("")
}

private const val SEED = 20_261_103L
private const val SAMPLES = 20_000
private const val SHOWN = 10
private const val PIECES = 6
private const val KINDS = 8
private const val SHORT_DIGITS = 5
private const val LONG_SPACES = 40
private const val LONG_LINES = 30
private const val GROUP = 4
private const val CARD_DIGITS = 16
private const val FIRST_YEAR = 1950
private const val YEARS = 50
private const val MONTHS = 12
private const val SAFE_DAYS = 28
private const val SEQUENCE = 3
private const val PHONE_SECOND = 7
private const val PHONE_DIGITS = 11
private const val RADIX = 10
private const val MARK = "■"
private val NEL = Char(0x85)
private val LINE_SEPARATOR = Char(0x2028)
private val ZERO_WIDTH = Char(0x200B)
private val LABEL = Regex("「(疑似银行卡 尾号|疑似身份证 尾号)[0-9X]{4}」|「已隐藏的号码」")
private val CARD_WORDS = listOf("银行卡", "储蓄卡", "卡号", "（银行卡支付）", "支付方式：信用卡", "card")
private val ORDER_LABELS = listOf("订单号：", "运单号 ", "交易号#", "Order No. ", "流水号：")
private val FIELD_WORDS = listOf("电话", "金额 ¥12.50", "姓名 张三", "，", "备注")
