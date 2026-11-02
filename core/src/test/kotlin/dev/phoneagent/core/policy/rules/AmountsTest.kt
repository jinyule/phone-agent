package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class AmountsTest {
    private val amounts = Amounts(PolicyTuning())

    private fun known(
        minor: Long,
        currency: String? = null,
    ): Price = Price.Known(Money(minor, currency))

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource(
        delimiter = '|',
        nullValues = ["-"],
        value = [
            "¥39.90|3990|-",
            "￥ 39.9|3990|-",
            "支付 ¥1,234.56|123456|-",
            "39.90元|3990|CNY",
            "128 块|12800|CNY",
            "RMB 5|500|CNY",
            "¥1.2万|1200000|-",
            "1.5万元|1500000|CNY",
            "$12.99|1299|-",
            "US$ 3|300|USD",
            "HK$88|8800|HKD",
            "NT$100|10000|TWD",
            "€9,99 nope; €9|900|EUR",
            "£4.5|450|GBP",
            "100 JPY|10000|JPY",
            "JPY ¥100|10000|JPY",
            "$20 HKD|2000|HKD",
            "USD 20 HKD|2000|-",
            "50 美元|5000|USD",
            "20港币|2000|HKD",
            "立减5元 实付¥95|9500|-",
            "-¥5.00 ¥20|2000|-",
            "- ¥5 ¥7|700|-",
            "共3件 ¥88|8800|-",
            "1,000,|100000|-",
        ],
    )
    fun `amounts are read with the currency they state`(
        text: String,
        minor: Long,
        currency: String?,
    ) {
        assertEquals(known(minor, currency), Prices.stated(text), text)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "39.90", "共 3 件", "3 items", "12.345元", "1,2345元", "1234,567元", "订单号 1234567890123", "account5", "",
            "¥",
        ],
    )
    fun `a label holds an amount only with a currency mark and a sound shape`(text: String) {
        assertNull(Prices.inLabel(text), text)
    }

    @Test
    fun `bare numbers are amounts where an amount is expected`() {
        assertEquals(known(12800), Prices.stated("128"))
        assertEquals(known(12800, "CNY"), Prices.stated("128元"))
        assertNull(Prices.stated("一些钱"))
    }

    @Test
    fun `amounts too large to hold are unclear, never cut down`() {
        for (text in listOf(
            "¥1234567890123456",
            "¥92,233,720,368,547,758.08",
            "¥999,999,999,999,999万",
            "¥" + "999,".repeat(1000) + "999",
        )) {
            assertEquals(Price.Unclear, Prices.stated(text), text.take(40))
        }
        assertEquals(known(99999999999999900), Prices.stated("¥999,999,999,999,999"))
        assertNull(Price.Unclear.money)
        assertNull(Price.Absent.money)
    }

    @Test
    fun `the spend amount prefers the control, then the best total, then the only amount`() {
        val checkout =
            screen(
                text("t1", "合计", box(0, 900, 100, 940)),
                text("t2", "¥", box(110, 900, 130, 940)),
                text("t3", "100.00", box(140, 900, 300, 940)),
                text("t4", "实付", box(0, 950, 100, 990)),
                text("t5", "¥88.00", box(110, 950, 300, 990)),
                text("t6", "运费 ¥10", box(0, 500, 300, 540)),
            )
        assertEquals(known(1), amounts.forSpend("支付 ¥0.01", checkout))
        assertEquals(known(8800), amounts.forSpend("支付", checkout))
        // Among equal labels the last one in reading order wins (the bottom bar).
        val twoTotals =
            screen(
                text("t1", "合计 ¥50", box(0, 300, 500, 340)),
                text("t2", "合计 ¥60", box(0, 900, 500, 940)),
            )
        assertEquals(known(6000), amounts.forSpend("提交订单", twoTotals))
        // After a total label, a number without a mark counts too.
        assertEquals(known(4200), amounts.forSpend("提交订单", screen(text("t1", "合计 42"))))
        // A total whose amount has not loaded yet gives no amount; the only other amount is used.
        val loading = screen(text("t1", "合计", box(0, 900, 100, 940)), text("t2", "¥20", box(0, 100, 100, 140)))
        assertEquals(known(2000), amounts.forSpend("支付", loading))
        // Several different amounts and no total: unclear, which is not the same as none.
        val list = screen(text("t1", "¥10"), text("t2", "¥20"), text("t3", "¥10"))
        assertEquals(Price.Unclear, amounts.forSpend("购买", list))
        val repeated = screen(text("t1", "¥10"), text("t2", "¥10"))
        assertEquals(known(1000), amounts.forSpend("购买", repeated))
        assertEquals(Price.Absent, amounts.forSpend("购买", screen(text("t1", "共 3 件"))))
        assertEquals(Price.Absent, amounts.forSpend("购买", null))
        // OCR lines count like element text.
        val drawn = screen(ocr = listOf(ocr("应付 ¥66", box(0, 800, 500, 840))))
        assertEquals(known(6600), amounts.forSpend("确认", drawn))
    }

    @Test
    fun `a total label marks a checkout only in short text`() {
        assertTrue(amounts.showsTotal(screen(text("t1", "合计"))))
        assertTrue(amounts.showsTotal(screen(ocr = listOf(ocr("Total: $5")))))
        assertFalse(amounts.showsTotal(screen(text("t1", "这是一段很长的商品介绍文字，里面顺带提到了合计这个词但它不是结算栏"))))
        assertFalse(amounts.showsTotal(screen(text("t1", "¥10"))))
    }
}
