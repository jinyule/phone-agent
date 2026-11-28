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
    private val reading = Reading(PolicyTuning())
    private val pages = reading.pages
    private val settingsPages = reading.settings

    @Test
    fun `a login page is flagged, or asks for a secret under a login heading`() {
        assertTrue(pages.isLogin(screen(flags = setOf(ScreenFlag.LOGIN))))
        val password = field("f1", secret = true)
        assertTrue(pages.isLogin(screen(text("t1", "账号登录"), password)))
        assertTrue(pages.isLogin(screen(text("t1", "Sign in"), field("f1", hint = "Enter code"))))
        assertTrue(pages.isLogin(screen(text("t1", "验证码登录"), field("f1", hint = "请输入验证码"))))
        // A login word without a secret field is a button somewhere ("登录后查看更多").
        assertFalse(pages.isLogin(screen(text("t1", "登录"), field("f1", hint = "搜索"))))
        // A code field without a login word is not a login page.
        assertFalse(pages.isLogin(screen(field("f1", hint = "请输入验证码"))))
        // A non-editable element whose hint mentions a code is not asking for one.
        assertFalse(pages.isLogin(screen(text("t1", "登录"), el("b1", "发送").copy(hint = "验证码"))))
        // Login flows that need no password box (A11).
        assertTrue(pages.isLogin(screen(el("b1", "本机号码一键登录"))))
        assertTrue(pages.isLogin(screen(text("t1", "手机号登录"))))
        assertTrue(pages.isLogin(screen(text("t1", "登录"), field("f1", hint = "请输入手机号"))))
        assertTrue(pages.isLogin(screen(text("t1", "Log in"), field("f1").copy(name = "Email"))))
        // A login link alone, or a login word with an ordinary field, is not a login page.
        assertFalse(pages.isLogin(screen(el("b1", "立即登录"))))
        assertFalse(pages.isLogin(screen(text("t1", "登录"), el("b1", "手机号").copy(hint = "手机号"))))
        // A login word buried in long content does not count.
        assertFalse(pages.isLogin(screen(text("t1", "这是一段很长的说明文字，告诉你登录以后可以看到更多内容"), password)))
    }

    @Test
    fun `captcha pages are flagged or say so`() {
        assertTrue(pages.isCaptcha(screen(flags = setOf(ScreenFlag.CAPTCHA))))
        assertTrue(pages.isCaptcha(screen(text("t1", "向右滑动完成验证"))))
        // A pay slider is not a captcha (A6, r2 B8): sliding counts only with a word of checking.
        assertFalse(pages.isCaptcha(screen(text("t1", "向右滑动支付"))))
        assertFalse(pages.isCaptcha(screen(text("t1", "拖动滑块付款"))))
        assertTrue(pages.isCaptcha(screen(text("t1", "请拖动下方滑块完成拼图"))))
        assertTrue(pages.isCaptcha(screen(text("t1", "安全验证"))))
        assertTrue(pages.isCaptcha(screen(ocr = listOf(ocr("I'm not a robot")))))
        assertFalse(pages.isCaptcha(screen(text("t1", "安全中心"))))
    }

    @Test
    fun `payment password and payment pages`() {
        assertTrue(pages.isPaymentPassword(screen(text("t1", "请输入支付密码"))))
        assertTrue(pages.isPaymentPassword(screen(ocr = listOf(ocr("Payment PIN")))))
        assertFalse(pages.isPaymentPassword(screen(text("t1", "支付成功"))))
        assertTrue(pages.isPayment(screen(flags = setOf(ScreenFlag.PAYMENT))))
        assertTrue(pages.isPayment(screen(text("t1", "实付 ¥20"))))
        assertTrue(pages.isPayment(screen(text("t1", "选择付款方式"))))
        assertFalse(pages.isPayment(screen(text("t1", "商品详情"))))
    }

    @Test
    fun `sensitive settings pages are told by activity or title, only in the settings app`() {
        val byActivity =
            screen(pkg = settings).copy(activity = "com.android.settings.Settings\$AccessibilitySettingsActivity")
        assertTrue(settingsPages.isSensitive(byActivity))
        assertTrue(settingsPages.isSensitive(screen(text("t1", "开发者选项", box(0, 50, 500, 100)), pkg = settings)))
        assertTrue(settingsPages.isSensitive(screen(text("t1", "Accessibility", box(0, 50, 500, 100)), pkg = settings)))
        // A harmless page, a page without a title, a title out of the top band.
        assertFalse(settingsPages.isSensitive(screen(text("t1", "WLAN", box(0, 50, 500, 100)), pkg = settings)))
        assertFalse(
            settingsPages.isSensitive(screen(pkg = settings).copy(activity = "com.android.settings.WifiSettings")),
        )
        assertFalse(settingsPages.isSensitive(screen(pkg = settings)))
        assertFalse(settingsPages.isSensitive(screen(text("t1", "隐私", box(0, 500, 500, 540)), pkg = settings)))
        // Another app's privacy page is that app's business.
        assertFalse(settingsPages.isSensitive(screen(text("t1", "隐私设置", box(0, 50, 500, 100)), pkg = Fx.APP)))
    }

    @Test
    fun `sensitive pages are told by any title candidate, from the tree or OCR`() {
        val second =
            screen(text("t1", "12:00", box(0, 10, 200, 40)), text("t2", "开发者选项", box(0, 60, 500, 120)), pkg = settings)
        assertTrue(settingsPages.isSensitive(second))
        assertTrue(settingsPages.isSensitive(screen(pkg = settings, ocr = listOf(ocr("无障碍", box(0, 50, 400, 110))))))
        assertFalse(settingsPages.isSensitive(screen(pkg = settings, ocr = listOf(ocr("无障碍", box(0, 400, 400, 460))))))
        // An entry in the top band is a control, not the page title.
        assertFalse(settingsPages.isSensitive(screen(el("b1", "安全", bounds = box(0, 100, 999, 140)), pkg = settings)))
        val entryText = text("t1", "隐私", box(0, 100, 500, 140), parent = "b1")
        assertFalse(
            settingsPages.isSensitive(screen(el("b1", bounds = box(0, 100, 999, 140)), entryText, pkg = settings)),
        )
        // Long text in the top band is no title.
        assertFalse(
            settingsPages.isSensitive(
                screen(text("t1", "这里是一段很长很长很长的说明文字提到了隐私两个字", box(0, 50, 999, 100)), pkg = settings),
            ),
        )
    }

    @Test
    fun `sensitive entries are named by their text in the settings app`() {
        assertEquals("无障碍", settingsPages.sensitiveEntry(listOf("通用", "无障碍"), screen(pkg = settings)))
        assertEquals(
            "developeroptions",
            settingsPages.sensitiveEntry(listOf("Developer options"), screen(pkg = settings)),
        )
        assertNull(settingsPages.sensitiveEntry(listOf("WLAN"), screen(pkg = settings)))
        assertNull(settingsPages.sensitiveEntry(listOf("无障碍"), screen()))
    }

    @Test
    fun `an app's settings page and what manages the app there`() {
        assertTrue(settingsPages.isAppDetails(screen(text("t1", "应用信息"), pkg = settings)))
        assertTrue(settingsPages.isAppDetails(screen(el("b1", "强行停止"), pkg = settings)))
        val byActivity =
            screen(
                pkg = settings,
            ).copy(activity = "com.android.settings.applications.InstalledAppDetailsTop")
        assertTrue(settingsPages.isAppDetails(byActivity))
        assertFalse(settingsPages.isAppDetails(screen(text("t1", "应用信息"))))
        assertFalse(settingsPages.isAppDetails(screen(text("t1", "WLAN"), pkg = settings)))
        assertTrue(settingsPages.managesApp(listOf("卸载")))
        assertTrue(settingsPages.managesApp(listOf("存储", "Clear storage")))
        assertFalse(settingsPages.managesApp(listOf("通知", "存储和缓存")))
        assertFalse(settingsPages.managesApp(emptyList()))
    }

    @Test
    fun `the title is the first named text in the top band`() {
        val back = el("b1", "返回", bounds = box(0, 40, 80, 100))
        val blank = text("t9", " ", box(0, 0, 9, 9))
        val unnamed = el("t2", null, ElementRole.TEXT, box(0, 0, 10, 10))
        val title = text("t1", "张三", box(0, 40, 500, 100))
        assertEquals("张三", pages.title(screen(back, blank, unnamed, title)))
        assertNull(pages.title(screen(text("t1", "正文", box(0, 400, 500, 440)))))
    }
}
