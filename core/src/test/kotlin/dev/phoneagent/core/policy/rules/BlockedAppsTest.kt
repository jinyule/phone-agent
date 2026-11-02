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

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "com.dbs.sg.dbsmbanking, BANK",
        "com.ocbc.mobile, BANK",
        "com.cib.cibmb, BANK",
        "com.cgbchina.xpt, BANK",
        "com.hxb.mobile.client, BANK",
        "com.czbank.mbank, BANK",
        "com.zxscnew, BROKER",
        "com.cmschina.stock, BROKER",
    )
    fun `the list covers joint-stock banks, Singapore banks and traditional brokers`(
        pkg: String,
        category: AppCategory,
    ) {
        assertEquals(category, list.categoryOf(pkg))
    }

    @Test
    fun `every shipped line says whether, when and where its package was checked`() {
        val url = requireNotNull(BlockedApps::class.java.getResource("blocked-apps.txt"))
        val entries = url.readText().lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        val note = Regex("# (已核实 \\d{4}-\\d{2}-\\d{2} (play|mi|qq|aosp)|未核实 \\d{4}-\\d{2}-\\d{2}：)")
        assertTrue(entries.size > 90, "${entries.size} entries")
        entries.forEach { assertTrue(note.containsMatchIn(it), it) }
    }

    @Test
    fun `ordinary apps are not on the list`() {
        listOf("com.tencent.mm", "com.taobao.taobao", "com.android.settings", "com.android.chrome")
            .forEach { assertNull(list.categoryOf(it), it) }
    }

    @Test
    fun `packages compare in any case`() {
        assertEquals(AppCategory.PAYMENT, list.categoryOf("COM.EG.ANDROID.ALIPAYGPHONE"))
        assertEquals(AppCategory.BANK, list.categoryOf("com.bankcomm.bankcomm"))
    }

    @Test
    fun `labels find the category, normalized, whole name only`() {
        assertEquals(AppCategory.PAYMENT, list.categoryNamed("支付宝"))
        assertEquals(AppCategory.PAYMENT, list.categoryNamed("ALIPAY"))
        assertEquals(AppCategory.BANK, list.categoryNamed(" 招商 银行 "))
        assertNull(list.categoryNamed("支付"))
        assertNull(list.categoryNamed("微信"))
    }

    @Test
    fun `the format skips comments and blank lines`() {
        val parsed = BlockedApps.parse("# header\n\n  # indented comment\ncom.a.B  bank  甲|A Bank  # 已核实\n", "test")
        assertEquals(AppCategory.BANK, parsed.categoryOf("com.a.b"))
        assertEquals(AppCategory.BANK, parsed.categoryNamed("a bank"))
        assertEquals(AppCategory.BANK, parsed.categoryNamed("甲"))
    }

    @Test
    fun `a malformed list fails loudly, naming the line`() {
        fun error(text: String): String =
            assertThrows<IllegalArgumentException> { BlockedApps.parse(text, "t.txt") }.message.orEmpty()
        assertTrue("t.txt line 2" in error("# c\ncom.a.b bank"), "missing labels")
        assertTrue("invalid package" in error("not_a_package bank X"))
        assertTrue("invalid package" in error("com.1a bank X"))
        assertTrue("unknown category 'shop'" in error("com.a.b shop X"))
        assertTrue("empty label" in error("com.a.b bank X||Y"))
        assertTrue("duplicate packages [com.a.b]" in error("com.a.b bank X\ncom.A.b broker Y"))
    }

    @Test
    fun `a missing resource fails loudly`() {
        val e = assertThrows<IllegalArgumentException> { BlockedApps.load("missing.txt") }
        assertEquals("Missing resource missing.txt", e.message)
    }
}
