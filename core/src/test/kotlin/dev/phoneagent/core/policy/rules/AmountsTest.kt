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
    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource(
        delimiter = '|',
        nullValues = ["-"],
        value = [
            "¥39.90|3990|CNY",
            "￥ 39.9|3990|CNY",
            "支付 ¥1,234.56|123456|CNY",
            "39.90元|3990|CNY",
            "128 块|12800|CNY",
            "RMB 5|500|CNY",
            "¥1.2万|1200000|CNY",
            "1.5万元|1500000|CNY",
            "$12.99|1299|USD",
            "US$ 3|300|USD",
            "HK$88|8800|HKD",
            "NT$100|10000|TWD",
            "€9,99 nope; €9|900|EUR",
            "£4.5|450|GBP",
            "100 JPY|10000|JPY",
            "50 美元|5000|USD",
            "20港币|2000|HKD",
            "立减5元 实付¥95|9500|CNY",
            "-¥5.00 ¥20|2000|CNY",
            "- ¥5 ¥7|700|CNY",
        ],
    )
    fun `amounts with a currency are parsed`(
        text: String,
        minor: Long,
        currency: String,
    ) {
        assertEquals(Money(minor, currency), Amounts.find(text))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["39.90", "共 3 件", "12.345元", "1,2345元", "订单号 1234567890123", "account5", "", "¥"])
    fun `numbers without a currency or with a bad shape are not money`(text: String) {
        assertNull(Amounts.find(text))
    }

    @Test
    fun `bare numbers count next to a total label`() {
        assertEquals(Money(12800, null), Amounts.find("128", bare = true))
        assertEquals(Money(12800, "CNY"), Amounts.find("128元", bare = true))
    }

    @Test
    fun `the spend amount prefers the button, then the best total, then the only amount`() {
        val checkout =
            screen(
                text("t1", "合计", box(0, 900, 100, 940)),
                text("t2", "¥", box(110, 900, 130, 940)),
                text("t3", "100.00", box(140, 900, 300, 940)),
                text("t4", "实付", box(0, 950, 100, 990)),
                text("t5", "¥88.00", box(110, 950, 300, 990)),
                text("t6", "运费 ¥10", box(0, 500, 300, 540)),
            )
        assertEquals(Money(1, "CNY"), Amounts.forSpend("支付 ¥0.01", checkout))
        assertEquals(Money(8800, "CNY"), Amounts.forSpend("支付", checkout))
        // Among equal labels the last one in reading order wins (the bottom bar).
        val twoTotals =
            screen(
                text("t1", "合计 ¥50", box(0, 300, 500, 340)),
                text("t2", "合计 ¥60", box(0, 900, 500, 940)),
            )
        assertEquals(Money(6000, "CNY"), Amounts.forSpend("提交订单", twoTotals))
        // A total whose amount has not loaded yet gives no amount; the only other amount is used.
        val loading = screen(text("t1", "合计", box(0, 900, 100, 940)), text("t2", "¥20", box(0, 100, 100, 140)))
        assertEquals(Money(2000, "CNY"), Amounts.forSpend("支付", loading))
        // Several different amounts and no total: unknown.
        val list = screen(text("t1", "¥10"), text("t2", "¥20"), text("t3", "¥10"))
        assertNull(Amounts.forSpend("购买", list))
        val repeated = screen(text("t1", "¥10"), text("t2", "¥10"))
        assertEquals(Money(1000, "CNY"), Amounts.forSpend("购买", repeated))
        // OCR lines count like element text.
        val drawn = screen(ocr = listOf(ocr("应付 ¥66", box(0, 800, 500, 840))))
        assertEquals(Money(6600, "CNY"), Amounts.forSpend("确认", drawn))
    }

    @Test
    fun `a total label marks a checkout only in short text`() {
        assertTrue(Amounts.showsTotal(screen(text("t1", "合计"))))
        assertTrue(Amounts.showsTotal(screen(ocr = listOf(ocr("Total: $5")))))
        assertFalse(Amounts.showsTotal(screen(text("t1", "这是一段很长的商品介绍文字，里面顺带提到了合计这个词但它不是结算栏"))))
        assertFalse(Amounts.showsTotal(screen(text("t1", "¥10"))))
    }
}
