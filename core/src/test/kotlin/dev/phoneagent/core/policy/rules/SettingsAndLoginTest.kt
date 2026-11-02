package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.ctx
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Pages the matrix recognizes beyond their flags (policy-rules.md §2, §6): settings and login flows. */
class SettingsAndLoginTest {
    private val policy = Fx.rules()

    private fun decide(
        call: ToolCall,
        ctx: PolicyContext,
        risk: RiskClass = RiskClass.WRITE,
    ): Verdict = policy.engine.evaluate(call, policy.resolver.resolve(call, risk, ctx.screen), ctx)

    private fun on(
        s: ScreenSnapshot,
        level: SecurityLevel,
    ): PolicyContext = ctx(level, s, operated = setOfNotNull(s.pkg))

    private fun refusal(verdict: Verdict): String = assertInstanceOf(Verdict.Refuse::class.java, verdict).reason

    /** An app-details page in the settings app, titled [title]. */
    private fun details(
        title: String,
        vararg more: UiElement,
    ): ScreenSnapshot =
        screen(
            text("t1", "应用信息", box(0, 40, 500, 100)),
            text("t2", title, box(0, 110, 500, 150)),
            el("e1", "强行停止", bounds = box(0, 300, 480, 360)),
            el("e2", "卸载", bounds = box(520, 300, 999, 360)),
            el("e3", "通知", bounds = box(0, 400, 999, 460)),
            el("e4", "存储和缓存", bounds = box(0, 480, 999, 540)),
            *more,
            pkg = Pages.SETTINGS,
        ).copy(activity = "com.android.settings.SubSettings")

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `the settings page of this app or of a blocked app is refused like the app itself`(level: SecurityLevel) {
        for (ref in listOf("e1", "e3")) {
            assertEquals("hard.own_app", refusal(decide(tap(ref), on(details("PhoneAgent"), level))), ref)
            assertEquals("hard.finance_app", refusal(decide(tap(ref), on(details("招商银行"), level))), ref)
        }
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `when the managed app is not known, stopping, clearing and uninstalling are refused`(level: SecurityLevel) {
        val unknown = details("某个应用", el("e5", "清除数据", bounds = box(0, 600, 999, 660)))
        for (ref in listOf("e1", "e2", "e5")) {
            assertEquals("hard.app_management", refusal(decide(tap(ref), on(unknown, level))), ref)
        }
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `on the settings page of an app it may operate, only managing the app is refused`(level: SecurityLevel) {
        val chat = details("微信")
        assertEquals("hard.app_management", refusal(decide(tap("e1"), on(chat, level))))
        for (ref in listOf("e3", "e4")) {
            val verdict = decide(tap(ref), on(chat, level))
            assertFalse(verdict is Verdict.Refuse, "$ref: $verdict")
        }
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `an app's settings page refuses its permission entry and disabling, also for an unknown app`(
        level: SecurityLevel,
    ) {
        val unknown =
            details(
                "某个应用",
                el("e5", "权限", bounds = box(0, 600, 999, 660)),
                el("e6", "停用", bounds = box(0, 680, 999, 740)),
            )
        assertEquals("hard.sensitive_settings", refusal(decide(tap("e5"), on(unknown, level))))
        assertEquals("hard.app_management", refusal(decide(tap("e6"), on(unknown, level))))
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `the managed app is known by OCR too`(level: SecurityLevel) {
        val drawn =
            screen(
                text("t1", "应用信息", box(0, 40, 500, 100)),
                el("e1", "强行停止", bounds = box(0, 300, 480, 360)),
                el("e3", "通知", bounds = box(0, 400, 999, 460)),
                pkg = Pages.SETTINGS,
                ocr = listOf(ocr("招商银行", box(0, 110, 500, 150))),
            ).copy(activity = "com.android.settings.SubSettings")
        for (ref in listOf("e1", "e3")) {
            assertEquals("hard.finance_app", refusal(decide(tap(ref), on(drawn, level))), ref)
        }
    }

    /** The storage page of one app, as AOSP shows it, for the app named [app]. */
    private fun storage(
        app: String,
        vararg more: UiElement,
        activity: String = "com.android.settings.Settings\$AppStorageSettingsActivity",
    ): ScreenSnapshot =
        screen(
            text("t1", "Storage & cache", box(0, 40, 500, 100)),
            text("t2", app, box(0, 160, 500, 200)),
            el("e1", "Clear storage", bounds = box(0, 300, 480, 360)),
            el("e2", "Clear cache", bounds = box(520, 300, 999, 360)),
            *more,
            pkg = Pages.SETTINGS,
        ).copy(activity = activity)

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `an app's storage page is an app's settings page`(level: SecurityLevel) {
        for (ref in listOf("e1", "e2")) {
            assertEquals("hard.own_app", refusal(decide(tap(ref), on(storage("PhoneAgent"), level))), ref)
        }
        val chat = storage("微信")
        assertEquals("hard.app_management", refusal(decide(tap("e1"), on(chat, level))))
        assertFalse(decide(tap("e2"), on(chat, level)) is Verdict.Refuse)
        val nothing = Fx.call("phone_tap", "screen" to "s1", "x" to 990, "y" to 990)
        assertFalse(decide(nothing, on(chat, level)) is Verdict.Refuse)
        // Known by its texts on any activity, and without the app's name.
        val bare =
            screen(
                text("t1", "存储和缓存", box(0, 40, 500, 100)),
                el("e1", "清除存储空间", bounds = box(0, 300, 480, 360)),
                pkg = Pages.SETTINGS,
            ).copy(activity = "com.android.settings.SubSettings")
        assertEquals("hard.app_management", refusal(decide(tap("e1"), on(bare, level))))
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `an app's storage page is known by its activity alone`(level: SecurityLevel) {
        // No text here names an app's settings page ("存储" alone, a harmless "清除缓存").
        val s =
            screen(
                text("t1", "存储", box(0, 40, 500, 100)),
                text("t2", "PhoneAgent", box(0, 160, 500, 200)),
                el("e1", "清除缓存", bounds = box(0, 300, 480, 360)),
                pkg = Pages.SETTINGS,
            )
        val byActivity = s.copy(activity = "com.android.settings.Settings\$AppStorageSettingsActivity")
        assertEquals("hard.own_app", refusal(decide(tap("e1"), on(byActivity, level))))
        val other = s.copy(activity = "com.android.settings.SubSettings")
        assertFalse(decide(tap("e1"), on(other, level)) is Verdict.Refuse)
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `confirming a dialog that clears, stops or disables an app is managing it`(level: SecurityLevel) {
        val dialog =
            arrayOf(
                text("t3", "要删除应用数据吗？", box(100, 400, 900, 450)).copy(window = 2),
                text("t4", "此应用的所有数据都将被永久删除。", box(100, 460, 900, 520)).copy(window = 2),
                el("e8", "取消", bounds = box(100, 600, 450, 660)).copy(window = 2),
                el("e9", "确定", bounds = box(550, 600, 900, 660)).copy(window = 2),
            )
        val chat = storage("微信", *dialog)
        assertEquals("hard.app_management", refusal(decide(tap("e9"), on(chat, level))))
        assertFalse(decide(tap("e8"), on(chat, level)) is Verdict.Refuse)
        val alone = screen(*dialog, pkg = Pages.SETTINGS).copy(activity = "com.android.settings.SubSettings")
        assertEquals("hard.app_management", refusal(decide(tap("e9"), on(alone, level))))
        val delete =
            screen(*dialog, el("e7", "删除", bounds = box(550, 600, 900, 660)).copy(window = 2), pkg = Pages.SETTINGS)
        assertEquals("hard.app_management", refusal(decide(tap("e7"), on(delete, level))))
        val stop =
            screen(
                text("t3", "要强行停止吗？", box(100, 400, 900, 450)).copy(window = 2),
                el("e9", "确定", bounds = box(550, 600, 900, 660)).copy(window = 2),
                pkg = Pages.SETTINGS,
            )
        assertEquals("hard.app_management", refusal(decide(tap("e9"), on(stop, level))))
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `a sensitive page is known by any title candidate, not only the first text`(level: SecurityLevel) {
        val second =
            screen(
                text("t1", "12:00", box(0, 10, 200, 40)),
                text("t2", "开发者选项", box(0, 60, 500, 120)),
                el("e1", "窗口动画缩放", role = ElementRole.PICKER, bounds = box(0, 400, 999, 460)),
                pkg = Pages.SETTINGS,
            ).copy(activity = "com.android.settings.SubSettings")
        assertEquals("hard.sensitive_settings", refusal(decide(tap("e1"), on(second, level))))
        val drawn =
            screen(
                el("e1", "开启", bounds = box(0, 400, 999, 460)),
                pkg = Pages.SETTINGS,
                ocr = listOf(ocr("无障碍", box(0, 50, 400, 110))),
            ).copy(activity = "com.android.settings.SubSettings")
        assertEquals("hard.sensitive_settings", refusal(decide(tap("e1"), on(drawn, level))))
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `login flows without a password box are handed off`(level: SecurityLevel) {
        val oneTap =
            screen(
                text("t1", "登录", box(0, 40, 500, 100)),
                field("e2", hint = "请输入手机号"),
                el("e1", "本机号码一键登录", bounds = box(100, 600, 900, 680)),
            )
        assertEquals(
            HandoffKind.LOGIN,
            assertInstanceOf(Verdict.HandOff::class.java, decide(tap("e1"), on(oneTap, level))).kind,
        )
        val phone = screen(text("t1", "手机号登录", box(0, 40, 500, 100)), field("e2", hint = "手机号"), el("e1", "获取验证码"))
        assertEquals(
            HandoffKind.LOGIN,
            assertInstanceOf(Verdict.HandOff::class.java, decide(tap("e1"), on(phone, level))).kind,
        )
        // A touch on nothing in particular still interacts with the page.
        val blank = Fx.call("phone_tap", "screen" to "s1", "x" to 990, "y" to 10)
        assertEquals(
            HandoffKind.LOGIN,
            assertInstanceOf(Verdict.HandOff::class.java, decide(blank, on(phone, level))).kind,
        )
        // A profile page that only links to the login page is not one.
        assertEquals(Verdict.Allow, decide(tap("e1"), on(screen(el("e1", "立即登录")), SecurityLevel.AUTONOMOUS)))
        assertEquals(Verdict.Allow, decide(tap("e1"), on(screen(el("e1", "登录/注册")), SecurityLevel.AUTONOMOUS)))
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `a login flow named on a control counts at any length`(level: SecurityLevel) {
        val long = "本机号码一键登录并同意用户服务协议和隐私政策"
        val named = screen(el("e1", long, bounds = box(100, 600, 900, 680)))
        assertEquals(
            HandoffKind.LOGIN,
            assertInstanceOf(Verdict.HandOff::class.java, decide(tap("e1"), on(named, level))).kind,
        )
        val inside =
            screen(
                el("e1", bounds = box(100, 600, 900, 680)),
                text("t2", long, box(120, 610, 880, 670), parent = "e1"),
            )
        assertEquals(
            HandoffKind.LOGIN,
            assertInstanceOf(Verdict.HandOff::class.java, decide(tap("e1"), on(inside, level))).kind,
        )
        // Body text that mentions a login flow is not a login page.
        val article = screen(text("t1", "教程：怎样在设置里关闭本机号码一键登录功能和相关的授权"), el("e1", "返回"))
        assertEquals(Verdict.Allow, decide(tap("e1"), on(article, SecurityLevel.AUTONOMOUS)))
    }
}
