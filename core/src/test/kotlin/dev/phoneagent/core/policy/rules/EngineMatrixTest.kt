package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.SecurityLevel.AUTONOMOUS
import dev.phoneagent.core.policy.SecurityLevel.CAUTIOUS
import dev.phoneagent.core.policy.SecurityLevel.STANDARD
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Fx.APP
import dev.phoneagent.core.policy.rules.Fx.OWN
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.call
import dev.phoneagent.core.policy.rules.Fx.ctx
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * One test per cell of the phase1 §6 matrix (rows × 谨慎 / 标准 / 自主), plus the decision order
 * of policy-rules.md §6. Calls go through the resolver first, as in the runtime.
 */
class EngineMatrixTest {
    private val policy = Fx.rules()

    private fun decide(
        call: ToolCall,
        ctx: PolicyContext,
        risk: RiskClass = RiskClass.WRITE,
    ): Verdict = policy.engine.evaluate(call, policy.resolver.resolve(call, risk, ctx.screen), ctx)

    /** The app's operated already, so 谨慎's first-in-app row does not interfere with the row under test. */
    private fun ctxIn(
        level: SecurityLevel,
        screen: ScreenSnapshot?,
        unattended: Boolean = false,
        outwardConfirmed: Set<String> = emptySet(),
    ): PolicyContext = ctx(level, screen, unattended, setOfNotNull(screen?.pkg), outwardConfirmed)

    private fun refusal(verdict: Verdict): String = assertInstanceOf(Verdict.Refuse::class.java, verdict).reason

    private fun handOff(verdict: Verdict): HandoffKind = assertInstanceOf(Verdict.HandOff::class.java, verdict).kind

    private fun reason(verdict: Verdict): ConfirmReason =
        assertInstanceOf(Verdict.Confirm::class.java, verdict).card.reason

    private val send = el("e1", "发送")
    private val chat = screen(send)

    @Nested
    inner class HardRefusals {
        @ParameterizedTest
        @EnumSource(SecurityLevel::class)
        fun `the app itself`(level: SecurityLevel) {
            val own = screen(el("e1", "设置"), pkg = OWN)
            assertEquals("hard.own_app", refusal(decide(tap("e1"), ctxIn(level, own))))
            assertEquals("hard.own_app", refusal(decide(call("phone_launch", "app" to OWN), ctxIn(level, null))))
            // Its settings page too: force stop and clear data are there.
            val details = call("phone_intent", "kind" to "settings", "page" to "app_details", "package" to OWN)
            assertEquals("hard.own_app", refusal(decide(details, ctxIn(level, null))))
        }

        @ParameterizedTest
        @EnumSource(SecurityLevel::class)
        fun `bank, broker and payment apps, by package - names and app links are not launched at all`(
            level: SecurityLevel,
        ) {
            listOf(
                call("phone_launch", "app" to "com.icbc"),
                call("phone_launch", "app" to "COM.ICBC"),
                call("phone_intent", "kind" to "settings", "page" to "app_details", "package" to "com.icbc"),
            ).forEach { assertEquals("hard.finance_app", refusal(decide(it, ctxIn(level, null))), it.args.toString()) }
            listOf(call("phone_launch", "app" to "富途牛牛"), call("phone_launch", "app" to "支付宝"))
                .forEach {
                    assertEquals(
                        "call.launch_package",
                        refusal(decide(it, ctxIn(level, null))),
                        it.args.toString(),
                    )
                }
            listOf(
                call("phone_launch", "link" to "alipays://platformapi/startapp?appId=20000056"),
                call("phone_launch", "link" to "android-app://cmb.pb/x"),
            ).forEach { assertEquals("call.link_scheme", refusal(decide(it, ctxIn(level, null))), it.args.toString()) }
            val bank = screen(el("e1", "转账"), pkg = "com.icbc")
            assertEquals("hard.finance_app", refusal(decide(tap("e1"), ctxIn(level, bank))))
            // Not even looking: the screen of a blocked app is not shown to the model.
            assertEquals("hard.finance_app", refusal(decide(call("phone_observe"), ctxIn(level, bank), RiskClass.READ)))
        }

        @ParameterizedTest
        @EnumSource(SecurityLevel::class)
        fun `system surfaces - SystemUI, installer, permission controller, security center`(level: SecurityLevel) {
            listOf(
                "com.android.systemui",
                "com.android.packageinstaller",
                "com.android.permissioncontroller",
                "com.miui.securitycenter",
            ).forEach { pkg ->
                val s = screen(el("e1", "允许"), pkg = pkg)
                assertEquals("hard.system_app", refusal(decide(tap("e1"), ctxIn(level, s))), pkg)
            }
        }

        @ParameterizedTest
        @EnumSource(SecurityLevel::class)
        fun `sensitive settings pages - opening, reading, acting, entering`(level: SecurityLevel) {
            val refused = "hard.sensitive_settings"
            val intent = call("phone_intent", "kind" to "settings", "page" to "accessibility")
            assertEquals(refused, refusal(decide(intent, ctxIn(level, null))))
            assertEquals(refused, refusal(decide(call("phone_intent", "kind" to "settings"), ctxIn(level, null))))
            val link =
                call(
                    "phone_launch",
                    "link" to "intent:#Intent;action=android.settings.APPLICATION_DEVELOPMENT_SETTINGS;end",
                )
            assertEquals("call.link_scheme", refusal(decide(link, ctxIn(level, null))))
            val page = screen(text("t1", "无障碍", box(0, 40, 500, 100)), el("e1", "已下载的应用"), pkg = Pages.SETTINGS)
            assertEquals(refused, refusal(decide(tap("e1"), ctxIn(level, page))))
            assertEquals(refused, refusal(decide(call("phone_observe"), ctxIn(level, page), RiskClass.READ)))
            assertEquals(refused, refusal(decide(call("phone_scroll", "direction" to "down"), ctxIn(level, page))))
            val list = screen(text("t1", "设置", box(0, 40, 500, 100)), el("e1", "开发者选项"), pkg = Pages.SETTINGS)
            assertEquals(refused, refusal(decide(tap("e1"), ctxIn(level, list))))
            val byActivity =
                screen(el("e1", "开启"), pkg = Pages.SETTINGS).copy(activity = ".Settings\$SecuritySettingsActivity")
            assertEquals(refused, refusal(decide(tap("e1"), ctxIn(level, byActivity))))
        }
    }

    @Nested
    inner class HandOffs {
        private fun type(vararg args: Pair<String, Any?>) = call("phone_type", "screen" to "s1", *args)

        @ParameterizedTest
        @EnumSource(SecurityLevel::class)
        fun `password, code and payment-password fields`(level: SecurityLevel) {
            val password = screen(field("e1", secret = true))
            assertEquals(
                HandoffKind.PASSWORD,
                handOff(decide(type("ref" to "e1", "text" to "x"), ctxIn(level, password))),
            )
            val code = screen(field("e1", hint = "请输入短信验证码"))
            assertEquals(
                HandoffKind.CAPTCHA,
                handOff(decide(type("ref" to "e1", "text" to "1234"), ctxIn(level, code))),
            )
            val pay = screen(field("e1", hint = "请输入6位支付密码"))
            assertEquals(HandoffKind.PAYMENT, handOff(decide(type("ref" to "e1", "text" to "1"), ctxIn(level, pay))))
            // Even when the tool says it reaches out, the field is handed off.
            assertEquals(
                HandoffKind.PASSWORD,
                handOff(decide(type("text" to "x"), ctxIn(level, password), RiskClass.OUTWARD)),
            )
        }

        @ParameterizedTest
        @EnumSource(SecurityLevel::class)
        fun `login pages, captcha pages and FLAG_SECURE pages`(level: SecurityLevel) {
            val login = screen(text("t1", "账号登录"), field("e2", secret = true), el("e1", "登录"))
            assertEquals(HandoffKind.LOGIN, handOff(decide(tap("e1"), ctxIn(level, login))))
            val captcha = screen(text("t1", "拖动滑块完成拼图"), el("e1", "刷新"))
            assertEquals(HandoffKind.CAPTCHA, handOff(decide(tap("e1"), ctxIn(level, captcha))))
            val secure = screen(el("e1", "下一步"), flags = setOf(ScreenFlag.SECURE))
            assertEquals(HandoffKind.SECURE_PAGE, handOff(decide(tap("e1"), ctxIn(level, secure))))
            val payPassword = screen(text("t1", "请输入支付密码"), el("e1", "1"))
            assertEquals(HandoffKind.PAYMENT, handOff(decide(tap("e1"), ctxIn(level, payPassword))))
            val enter = call("phone_key", "key" to "enter")
            assertEquals(HandoffKind.SECURE_PAGE, handOff(decide(enter, ctxIn(level, secure))))
        }

        @Test
        fun `page hand-offs go payment password, captcha, login, secure`() {
            val all =
                screen(
                    text("t1", "请输入支付密码"),
                    text("t2", "安全验证"),
                    field("e2", secret = true),
                    text("t3", "登录"),
                    el("e1", "确定"),
                    flags = setOf(ScreenFlag.SECURE),
                )
            assertEquals(HandoffKind.PAYMENT, handOff(decide(tap("e1"), ctxIn(STANDARD, all))))
            val noPay = screen(text("t2", "安全验证"), field("e2", secret = true), text("t3", "登录"), el("e1", "确定"))
            assertEquals(HandoffKind.CAPTCHA, handOff(decide(tap("e1"), ctxIn(STANDARD, noPay))))
            val login =
                screen(field("e2", secret = true), text("t3", "登录"), el("e1", "确定"), flags = setOf(ScreenFlag.SECURE))
            assertEquals(HandoffKind.LOGIN, handOff(decide(tap("e1"), ctxIn(STANDARD, login))))
        }

        @Test
        fun `leaving, scrolling and reading a hand-off page are allowed`() {
            val login =
                screen(
                    text("t1", "账号登录"),
                    field("e2", secret = true),
                    el("e1", "暂不登录"),
                    flags = setOf(ScreenFlag.LOGIN),
                )
            assertEquals(Verdict.Allow, decide(tap("e1"), ctxIn(STANDARD, login)))
            assertEquals(Verdict.Allow, decide(call("phone_scroll", "direction" to "down"), ctxIn(STANDARD, login)))
            assertEquals(Verdict.Allow, decide(call("phone_observe"), ctxIn(STANDARD, login), RiskClass.READ))
            assertEquals(Verdict.Allow, decide(call("phone_key", "key" to "back"), ctxIn(STANDARD, login)))
            // A point tap leaves when it hits the escape, and is handed off elsewhere.
            assertEquals(Verdict.Allow, decide(call("phone_tap", "x" to 50, "y" to 425), ctxIn(STANDARD, login)))
            assertEquals(
                HandoffKind.LOGIN,
                handOff(decide(call("phone_tap", "x" to 400, "y" to 930), ctxIn(STANDARD, login))),
            )
        }
    }

    @Nested
    inner class Spending {
        private val checkout =
            screen(text("t1", "实付 ¥88.00", box(0, 900, 400, 960)), el("e1", "提交订单", bounds = box(600, 900, 999, 960)))

        @ParameterizedTest
        @EnumSource(SecurityLevel::class)
        fun `spending is confirmed at every level, with the amount`(level: SecurityLevel) {
            val verdict = assertInstanceOf(Verdict.Confirm::class.java, decide(tap("e1"), ctxIn(level, checkout)))
            assertEquals(ConfirmReason.SPEND, verdict.card.reason)
            assertEquals(Money(8800, null), verdict.card.amount)
            assertEquals("提交订单", verdict.card.target)
        }

        @ParameterizedTest
        @EnumSource(SecurityLevel::class)
        fun `unattended spending is refused at every level, and said so`(level: SecurityLevel) {
            val verdict =
                assertInstanceOf(
                    Verdict.Refuse::class.java,
                    decide(tap("e1"), ctxIn(level, checkout, unattended = true)),
                )
            assertEquals("spend.unattended", verdict.reason)
            assertEquals(Refusal.SPEND_UNATTENDED.hint, verdict.hintForModel)
            // A tool declared SPEND is refused too, even on a screen that shows nothing about money.
            assertEquals(
                "spend.unattended",
                refusal(decide(tap("e1"), ctxIn(level, screen(el("e1", "下一步")), unattended = true), RiskClass.SPEND)),
            )
        }

        @Test
        fun `unattended tasks only change spending`() {
            assertEquals(Verdict.Allow, decide(tap("e1"), ctxIn(AUTONOMOUS, chat, unattended = true)))
            assertEquals(ConfirmReason.OUTWARD, reason(decide(tap("e1"), ctxIn(STANDARD, chat, unattended = true))))
        }
    }

    @Nested
    inner class Outward {
        @Test
        fun `cautious confirms every outward action`() {
            assertEquals(ConfirmReason.OUTWARD, reason(decide(tap("e1"), ctxIn(CAUTIOUS, chat))))
            assertEquals(
                ConfirmReason.OUTWARD,
                reason(decide(tap("e1"), ctxIn(CAUTIOUS, chat, outwardConfirmed = setOf(APP)))),
            )
        }

        @Test
        fun `standard confirms the first outward action per app in the task`() {
            assertEquals(ConfirmReason.OUTWARD, reason(decide(tap("e1"), ctxIn(STANDARD, chat))))
            assertEquals(Verdict.Allow, decide(tap("e1"), ctxIn(STANDARD, chat, outwardConfirmed = setOf(APP))))
            assertEquals(
                ConfirmReason.OUTWARD,
                reason(decide(tap("e1"), ctxIn(STANDARD, chat, outwardConfirmed = setOf("com.other.app")))),
            )
        }

        @Test
        fun `autonomous allows outward actions`() {
            assertEquals(Verdict.Allow, decide(tap("e1"), ctxIn(AUTONOMOUS, chat)))
        }

        @ParameterizedTest
        @EnumSource(SecurityLevel::class, names = ["CAUTIOUS", "STANDARD"])
        fun `typing that submits and enter in a chat box reach out`(level: SecurityLevel) {
            val box = screen(field("e1", hint = "发消息", focused = true))
            val type = call("phone_type", "screen" to "s1", "ref" to "e1", "text" to "明天见", "submit" to true)
            val card = assertInstanceOf(Verdict.Confirm::class.java, decide(type, ctxIn(level, box))).card
            assertEquals(ConfirmReason.OUTWARD to "明天见", card.reason to card.content)
            assertEquals(ConfirmReason.OUTWARD, reason(decide(call("phone_key", "key" to "enter"), ctxIn(level, box))))
        }

        @Test
        fun `a search box submits without asking`() {
            val search = screen(field("e1", hint = "搜索", focused = true))
            val type = call("phone_type", "screen" to "s1", "ref" to "e1", "text" to "猫", "submit" to true)
            assertEquals(Verdict.Allow, decide(type, ctxIn(STANDARD, search)))
        }
    }

    @Nested
    inner class DeleteAndSettings {
        private val delete = screen(el("e1", "删除聊天记录"))
        private val toggle = screen(el("e1", "蓝牙", role = ElementRole.SWITCH), pkg = Pages.SETTINGS)

        @ParameterizedTest
        @EnumSource(SecurityLevel::class, names = ["CAUTIOUS", "STANDARD"])
        fun `cautious and standard confirm deleting and settings changes`(level: SecurityLevel) {
            assertEquals(ConfirmReason.DELETE, reason(decide(tap("e1"), ctxIn(level, delete))))
            assertEquals(ConfirmReason.SETTINGS, reason(decide(tap("e1"), ctxIn(level, toggle))))
        }

        @Test
        fun `autonomous allows deleting and settings changes`() {
            assertEquals(Verdict.Allow, decide(tap("e1"), ctxIn(AUTONOMOUS, delete)))
            assertEquals(Verdict.Allow, decide(tap("e1"), ctxIn(AUTONOMOUS, toggle)))
        }

        @Test
        fun `low-risk deletes and field clearing are not deletes`() {
            val history = screen(el("e1", "删除搜索历史"))
            assertEquals(Verdict.Allow, decide(tap("e1"), ctxIn(STANDARD, history)))
            val input = field("e2", bounds = box(0, 100, 800, 160)).copy(value = "猫")
            val clear = el("e1", "×", bounds = box(700, 110, 790, 150))
            assertEquals(Verdict.Allow, decide(tap("e1"), ctxIn(STANDARD, screen(input, clear))))
        }

        @Test
        fun `a tool declared SETUP is a settings change`() {
            assertEquals(
                ConfirmReason.SETTINGS,
                reason(decide(tap("e1"), ctxIn(STANDARD, screen(el("e1", "下一步"))), RiskClass.SETUP)),
            )
        }
    }

    @Nested
    inner class FirstInApp {
        private val page = screen(el("e1", "下一步"))

        @Test
        fun `cautious confirms the first action in each app`() {
            assertEquals(ConfirmReason.FIRST_IN_APP, reason(decide(tap("e1"), ctx(CAUTIOUS, page))))
            assertEquals(Verdict.Allow, decide(tap("e1"), ctx(CAUTIOUS, page, operated = setOf(APP))))
            val launch = call("phone_launch", "app" to "com.tencent.mm")
            assertEquals(ConfirmReason.FIRST_IN_APP, reason(decide(launch, ctx(CAUTIOUS, null, operated = setOf(APP)))))
            // Reading and talking are not operating; an action with no known app is not "in" one.
            assertEquals(Verdict.Allow, decide(call("phone_observe"), ctx(CAUTIOUS, page), RiskClass.READ))
            assertEquals(
                Verdict.Allow,
                decide(call("ask_user", "question" to "?"), ctx(CAUTIOUS, page), RiskClass.READ),
            )
            val alarm = call("phone_intent", "kind" to "alarm", "time" to "07:00")
            assertEquals(Verdict.Allow, decide(alarm, ctx(CAUTIOUS, null)))
        }

        @ParameterizedTest
        @EnumSource(SecurityLevel::class, names = ["STANDARD", "AUTONOMOUS"])
        fun `standard and autonomous do not ask for the first action`(level: SecurityLevel) {
            assertEquals(Verdict.Allow, decide(tap("e1"), ctx(level, page)))
        }

        @Test
        fun `a level-row reason wins over first-in-app`() {
            assertEquals(ConfirmReason.OUTWARD, reason(decide(tap("e1"), ctx(CAUTIOUS, chat))))
        }
    }

    @Nested
    inner class Order {
        @Test
        fun `refusal beats hand-off beats confirmation`() {
            val ownLogin = screen(field("e1", secret = true), pkg = OWN)
            val type = call("phone_type", "screen" to "s1", "ref" to "e1", "text" to "x")
            assertEquals("hard.own_app", refusal(decide(type, ctxIn(STANDARD, ownLogin))))
            val payOnLogin = screen(text("t1", "登录"), field("e2", secret = true), el("e1", "立即支付"))
            assertEquals(HandoffKind.LOGIN, handOff(decide(tap("e1"), ctxIn(STANDARD, payOnLogin))))
        }

        @Test
        fun `back and home always run, so the agent can leave a blocked screen`() {
            val bank = screen(pkg = "com.icbc")
            assertEquals(Verdict.Allow, decide(call("phone_key", "key" to "back"), ctx(CAUTIOUS, bank)))
            assertEquals(Verdict.Allow, decide(call("phone_key", "key" to "home"), ctx(CAUTIOUS, screen(pkg = OWN))))
            assertEquals("hard.finance_app", refusal(decide(call("phone_key", "key" to "enter"), ctx(CAUTIOUS, bank))))
        }

        @Test
        fun `system intents other than settings only navigate`() {
            assertEquals(
                Verdict.Allow,
                decide(call("phone_intent", "kind" to "alarm", "time" to "07:00"), ctx(STANDARD, null)),
            )
            assertEquals(
                Verdict.Allow,
                decide(call("phone_intent", "kind" to "settings", "page" to "wifi"), ctx(STANDARD, null)),
            )
            val details =
                call(
                    "phone_intent",
                    "kind" to "settings",
                    "page" to "app_details",
                    "package" to "com.tencent.mm",
                )
            assertEquals(Verdict.Allow, decide(details, ctx(STANDARD, null)))
        }

        @Test
        fun `talking is never refused for the screen behind it`() {
            val bank = screen(pkg = "com.icbc")
            assertEquals(
                Verdict.Allow,
                decide(call("ask_user", "question" to "?"), ctx(CAUTIOUS, bank), RiskClass.READ),
            )
        }

        @Test
        fun `calls the policy cannot check are refused`() {
            assertEquals(
                "call.unresolved",
                refusal(decide(call("phone_tap", "x" to 5000, "y" to 1), ctx(AUTONOMOUS, chat))),
            )
            assertEquals("call.unknown_tool", refusal(decide(call("shell_exec", "cmd" to "ls"), ctx(AUTONOMOUS, chat))))
            assertEquals(Verdict.Allow, decide(call("memory_search"), ctx(AUTONOMOUS, chat), RiskClass.READ))
        }

        @Test
        fun `a ref from another snapshot is refused`() {
            assertEquals("call.stale_screen", refusal(decide(tap("e1", screen = "s0"), ctx(AUTONOMOUS, chat))))
            assertEquals("call.stale_screen", refusal(decide(call("phone_tap", "ref" to "e1"), ctx(AUTONOMOUS, chat))))
            // Without any screen, the model must observe first.
            assertEquals("call.no_screen", refusal(decide(tap("e1"), ctx(AUTONOMOUS, null))))
            // On the old screen e1 was "发送"; on the current one e1 is a harmless button. Looking
            // the old ref up in the new screen would allow it, but the tap runs on the old screen.
            val reshuffled = screen(el("e1", "返回"), id = "s2")
            assertEquals(
                "call.stale_screen",
                refusal(decide(tap("e1", screen = "s1"), ctx(STANDARD, reshuffled, operated = setOf(APP)))),
            )
            // Points and steps without a ref carry no snapshot.
            assertEquals(Verdict.Allow, decide(call("phone_tap", "x" to 50, "y" to 425), ctx(AUTONOMOUS, chat)))
            assertEquals(Verdict.Allow, decide(call("phone_scroll", "direction" to "down"), ctx(AUTONOMOUS, chat)))
        }
    }

    @Nested
    inner class Confirmations {
        private val checkout =
            screen(text("t1", "合计 ¥20", box(0, 900, 400, 960)), el("e1", "去结算", bounds = box(600, 900, 999, 960)))

        @Test
        fun `the approved card lets exactly that call through`() {
            val card = assertInstanceOf(Verdict.Confirm::class.java, decide(tap("e1"), ctxIn(STANDARD, checkout))).card
            val approved = ctx(STANDARD, checkout, operated = setOf(APP)).copy(approved = card)
            assertEquals(Verdict.Allow, decide(tap("e1"), approved))
            // Another call id, another amount or another target asks again.
            assertEquals(
                ConfirmReason.SPEND,
                reason(decide(call("phone_tap", "screen" to "s1", "ref" to "e1", id = "c2"), approved)),
            )
            val pricier =
                screen(text("t1", "合计 ¥99", box(0, 900, 400, 960)), el("e1", "去结算", bounds = box(600, 900, 999, 960)))
            assertEquals(ConfirmReason.SPEND, reason(decide(tap("e1"), approved.copy(screen = pricier))))
            val other =
                screen(text("t1", "合计 ¥20", box(0, 900, 400, 960)), el("e1", "立即购买", bounds = box(600, 900, 999, 960)))
            assertEquals(ConfirmReason.SPEND, reason(decide(tap("e1"), approved.copy(screen = other))))
        }

        @Test
        fun `the re-check after approval reads the fresh screen without the stale-ref rule`() {
            val card = assertInstanceOf(Verdict.Confirm::class.java, decide(tap("e1"), ctxIn(STANDARD, checkout))).card
            val fresh =
                screen(
                    text("t1", "合计 ¥20", box(0, 900, 400, 960)),
                    el("e1", "去结算", bounds = box(600, 900, 999, 960)),
                    id = "s2",
                )
            assertEquals(Verdict.Allow, decide(tap("e1"), ctx(STANDARD, fresh).copy(approved = card)))
            // The approval does not lift a refusal or hand-off that appeared meanwhile.
            val pwd = screen(text("t1", "请输入支付密码"), el("e1", "去结算"), id = "s2")
            assertEquals(HandoffKind.PAYMENT, handOff(decide(tap("e1"), ctx(STANDARD, pwd).copy(approved = card))))
        }

        @ParameterizedTest
        @EnumSource(SecurityLevel::class)
        fun `request_confirmation always asks, at every level`(level: SecurityLevel) {
            val ask = call("request_confirmation", "action" to "给张三转账", "target" to "张三", "amount" to "¥10")
            val card = assertInstanceOf(Verdict.Confirm::class.java, decide(ask, ctx(level, chat), RiskClass.READ)).card
            assertEquals(ConfirmReason.REQUESTED_BY_MODEL, card.reason)
            assertEquals(Money(1000, null), card.amount)
            // It names no app, so approving it never stands in for the per-app outward confirmation.
            assertNull(card.pkg)
            assertEquals(Verdict.Allow, decide(ask, ctx(level, chat).copy(approved = card), RiskClass.READ))
        }

        @Test
        fun `request_confirmation only adds confirmations`() {
            val ask = call("request_confirmation", "action" to "发送")
            val card =
                assertInstanceOf(
                    Verdict.Confirm::class.java,
                    decide(ask, ctx(STANDARD, chat), RiskClass.READ),
                ).card
            // Its approval does not approve the outward tap that follows.
            assertEquals(ConfirmReason.OUTWARD, reason(decide(tap("e1"), ctx(STANDARD, chat).copy(approved = card))))
        }
    }
}
