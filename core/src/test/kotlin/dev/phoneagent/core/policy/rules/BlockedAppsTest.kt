package dev.phoneagent.core.policy.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class BlockedAppsTest {
    private val list = BlockedApps.DEFAULT

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "com.eg.android.AlipayGphone, PAYMENT",
        "com.unionpay, PAYMENT",
        "com.icbc, BANK",
        "cmb.pb, BANK",
        "cn.futu.trader, BROKER",
        "com.android.systemui, SYSTEM",
        "com.android.permissioncontroller, SYSTEM",
        "com.miui.securitycenter, SYSTEM",
        "com.paypal.android.p2pmobile, PAYMENT",
    )
    fun `the shipped list blocks finance apps and system surfaces`(
        pkg: String,
        category: AppCategory,
    ) {
        assertEquals(category, list.categoryOf(pkg))
    }

    @Test
    fun `ordinary apps are not on the list`() {
        listOf("com.tencent.mm", "com.taobao.taobao", "com.android.settings", "com.android.chrome")
            .forEach { assertNull(list.categoryOf(it), it) }
    }

    @Test
    fun `labels find the package, normalized, whole name only`() {
        assertEquals("com.eg.android.AlipayGphone", list.packageNamed("支付宝"))
        assertEquals("com.eg.android.AlipayGphone", list.packageNamed("ALIPAY"))
        assertEquals("cmb.pb", list.packageNamed(" 招商 银行 "))
        assertNull(list.packageNamed("支付"))
        assertNull(list.packageNamed("微信"))
    }

    @Test
    fun `the format skips comments and blank lines`() {
        val parsed = BlockedApps.parse("# header\n\n  # indented comment\ncom.a.b  bank  甲|A Bank\n", "test")
        assertEquals(AppCategory.BANK, parsed.categoryOf("com.a.b"))
        assertEquals("com.a.b", parsed.packageNamed("a bank"))
        assertEquals("com.a.b", parsed.packageNamed("甲"))
    }

    @Test
    fun `a malformed list fails loudly, naming the line`() {
        fun error(text: String): String =
            assertThrows<IllegalArgumentException> { BlockedApps.parse(text, "t.txt") }.message.orEmpty()
        assertTrue("t.txt line 2" in error("# c\ncom.a.b bank"), "missing labels")
        assertTrue("invalid package" in error("not_a_package bank X"))
        assertTrue("unknown category 'shop'" in error("com.a.b shop X"))
        assertTrue("empty label" in error("com.a.b bank X||Y"))
        assertTrue("duplicate packages [com.a.b]" in error("com.a.b bank X\ncom.a.b broker Y"))
    }

    @Test
    fun `a missing resource fails loudly`() {
        val e = assertThrows<IllegalArgumentException> { BlockedApps.load("missing.txt") }
        assertEquals("Missing resource missing.txt", e.message)
    }
}
