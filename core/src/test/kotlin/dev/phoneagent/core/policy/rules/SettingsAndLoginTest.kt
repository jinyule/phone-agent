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
    }
}
