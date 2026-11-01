package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenFlag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PagesTest {
    private val settings = Pages.SETTINGS

    @Test
    fun `a login page is flagged, or asks for a secret under a login heading`() {
        assertTrue(Pages.isLogin(screen(flags = setOf(ScreenFlag.LOGIN))))
        val password = field("f1", secret = true)
        assertTrue(Pages.isLogin(screen(text("t1", "账号登录"), password)))
        assertTrue(Pages.isLogin(screen(text("t1", "Sign in"), field("f1", hint = "Enter code"))))
        assertTrue(Pages.isLogin(screen(text("t1", "验证码登录"), field("f1", hint = "请输入验证码"))))
        // A login word without a secret field is a button somewhere ("登录后查看更多").
        assertFalse(Pages.isLogin(screen(text("t1", "登录"), field("f1", hint = "搜索"))))
        // A code field without a login word is not a login page.
        assertFalse(Pages.isLogin(screen(field("f1", hint = "请输入验证码"))))
        // A non-editable element whose hint mentions a code is not asking for one.
        assertFalse(Pages.isLogin(screen(text("t1", "登录"), el("b1", "发送").copy(hint = "验证码"))))
        // A login word buried in long content does not count.
        assertFalse(Pages.isLogin(screen(text("t1", "这是一段很长的说明文字，告诉你登录以后可以看到更多内容"), password)))
    }

    @Test
    fun `captcha pages are flagged or say so`() {
        assertTrue(Pages.isCaptcha(screen(flags = setOf(ScreenFlag.CAPTCHA))))
        assertTrue(Pages.isCaptcha(screen(text("t1", "向右滑动完成验证"))))
        assertTrue(Pages.isCaptcha(screen(ocr = listOf(ocr("I'm not a robot")))))
        assertFalse(Pages.isCaptcha(screen(text("t1", "安全中心"))))
    }

    @Test
    fun `payment password and payment pages`() {
        assertTrue(Pages.isPaymentPassword(screen(text("t1", "请输入支付密码"))))
        assertTrue(Pages.isPaymentPassword(screen(ocr = listOf(ocr("Payment PIN")))))
        assertFalse(Pages.isPaymentPassword(screen(text("t1", "支付成功"))))
        assertTrue(Pages.isPayment(screen(flags = setOf(ScreenFlag.PAYMENT))))
        assertTrue(Pages.isPayment(screen(text("t1", "实付 ¥20"))))
        assertTrue(Pages.isPayment(screen(text("t1", "选择付款方式"))))
        assertFalse(Pages.isPayment(screen(text("t1", "商品详情"))))
    }

    @Test
    fun `sensitive settings pages are told by activity or title, only in the settings app`() {
        val byActivity =
            screen(pkg = settings).copy(activity = "com.android.settings.Settings\$AccessibilitySettingsActivity")
        assertTrue(Pages.isSensitiveSettings(byActivity))
        assertTrue(Pages.isSensitiveSettings(screen(text("t1", "开发者选项", box(0, 50, 500, 100)), pkg = settings)))
        assertTrue(Pages.isSensitiveSettings(screen(text("t1", "Accessibility", box(0, 50, 500, 100)), pkg = settings)))
        // A harmless page, a page without a title, a title out of the top band.
        assertFalse(Pages.isSensitiveSettings(screen(text("t1", "WLAN", box(0, 50, 500, 100)), pkg = settings)))
        assertFalse(
            Pages.isSensitiveSettings(screen(pkg = settings).copy(activity = "com.android.settings.WifiSettings")),
        )
        assertFalse(Pages.isSensitiveSettings(screen(pkg = settings)))
        assertFalse(Pages.isSensitiveSettings(screen(text("t1", "隐私", box(0, 500, 500, 540)), pkg = settings)))
        // Another app's privacy page is that app's business.
        assertFalse(Pages.isSensitiveSettings(screen(text("t1", "隐私设置", box(0, 50, 500, 100)), pkg = Fx.APP)))
    }

    @Test
    fun `sensitive entries are named by their label in the settings app`() {
        val entry = el("b1", "无障碍")
        assertEquals("无障碍", Pages.sensitiveEntry(entry, screen(entry, pkg = settings)))
        val row = el("b2")
        val rowText = text("t1", "Developer options", parent = "b2")
        assertEquals("developeroptions", Pages.sensitiveEntry(row, screen(row, rowText, pkg = settings)))
        val wifi = el("b3", "WLAN")
        assertNull(Pages.sensitiveEntry(wifi, screen(wifi, pkg = settings)))
        assertNull(Pages.sensitiveEntry(entry, screen(entry)))
    }

    @Test
    fun `the title is the first named text in the top band`() {
        val back = el("b1", "返回", bounds = box(0, 40, 80, 100))
        val blank = text("t9", " ", box(0, 0, 9, 9))
        val unnamed = el("t2", null, ElementRole.TEXT, box(0, 0, 10, 10))
        val title = text("t1", "张三", box(0, 40, 500, 100))
        assertEquals("张三", Pages.title(screen(back, blank, unnamed, title)))
        assertNull(Pages.title(screen(text("t1", "正文", box(0, 400, 500, 440)))))
    }
}
