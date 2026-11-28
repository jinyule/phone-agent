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
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Tools other than a plain tap, and how their arguments are read (policy-rules.md §2, §7): the
 * same written forms the tools accept.
 */
class CallsTest {
    private val policy = Fx.rules()

    private fun decide(
        call: ToolCall,
        ctx: PolicyContext,
        risk: RiskClass = RiskClass.WRITE,
    ): Verdict = policy.engine.evaluate(call, policy.resolver.resolve(call, risk, ctx.screen), ctx)

    private fun on(
        s: ScreenSnapshot?,
        level: SecurityLevel = STANDARD,
    ): PolicyContext = ctx(level, s, operated = setOfNotNull(s?.pkg))

    private fun reason(verdict: Verdict): ConfirmReason =
        assertInstanceOf(Verdict.Confirm::class.java, verdict).card.reason

    private fun refused(verdict: Verdict): Verdict.Refuse = assertInstanceOf(Verdict.Refuse::class.java, verdict)

    private fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ): ToolCall = call("phone_swipe", "x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "durationMs" to 500)

    @Test
    fun `submitting a form on a payment page pays`() {
        val pay = screen(field("e1", hint = "备注", focused = true), flags = setOf(ScreenFlag.PAYMENT))
        val type = call("phone_type", "screen" to "s1", "ref" to "e1", "text" to "x", "submit" to true)
        assertEquals(ConfirmReason.SPEND, reason(decide(type, on(pay, AUTONOMOUS))))
        assertEquals(ConfirmReason.SPEND, reason(decide(call("phone_key", "key" to "enter"), on(pay, AUTONOMOUS))))
    }

    @Test
    fun `dragging a slider or turning a picker in the settings app changes settings`() {
        val slider =
            screen(el("e1", "亮度", role = ElementRole.PROGRESS, bounds = box(100, 300, 900, 340)), pkg = Pages.SETTINGS)
        assertEquals(ConfirmReason.SETTINGS, reason(decide(swipe(200, 320, 260, 320), on(slider))))
        val picker = screen(el("e1", "屏幕超时", role = ElementRole.PICKER), pkg = Pages.SETTINGS)
        val scroll = call("phone_scroll", "screen" to "s1", "ref" to "e1", "direction" to "down")
        assertEquals(ConfirmReason.SETTINGS, reason(decide(scroll, on(picker))))
    }

    @Test
    fun `a sideways swipe on a list row may delete it, however short`() {
        val inbox = screen(el("e1", "张三 周报", bounds = box(0, 300, 999, 400)))
        assertEquals(ConfirmReason.DELETE, reason(decide(swipe(800, 350, 500, 350), on(inbox))))
        assertEquals(Verdict.Allow, decide(swipe(500, 350, 500, 900), on(inbox)))
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `phone_launch takes package names only`(level: SecurityLevel) {
        for (name in listOf("微信", "PhoneAgent", "招商银行")) {
            val verdict = refused(decide(call("phone_launch", "app" to name), on(null, level)))
            assertEquals("call.launch_package", verdict.reason, name)
            assertTrue("phone_apps" in verdict.hintForModel, name)
        }
    }

    @Test
    fun `a package-shaped name of any length is checked without throwing`() {
        val long = "a" + ".a".repeat(10_000)
        assertEquals("call.launch_package", refused(decide(call("phone_launch", "app" to long), on(null))).reason)
        val link = call("phone_launch", "link" to "android-app://$long/x")
        assertEquals("call.link_scheme", refused(decide(link, on(null))).reason)
        val details = call("phone_intent", "kind" to "settings", "page" to "app_details", "package" to long)
        assertEquals("call.unresolved", refused(decide(details, on(null))).reason)
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `links open only web, map, mail and store addresses`(level: SecurityLevel) {
        listOf(
            "intent:#Intent;component=cmb.pb/.MainActivity;end",
            "alipays://platformapi/startapp?appId=20000056",
            "android-app://cmb.pb/x",
            "weixin://dl/scan",
            "file:///sdcard/a.apk",
        ).forEach {
            assertEquals(
                "call.link_scheme",
                refused(decide(call("phone_launch", "link" to it), on(null, level))).reason,
                it,
            )
        }
        listOf(
            "https://www.example.com/a",
            "http://example.com",
            "geo:0,0?q=西湖",
            "mailto:a@example.com",
            "market://details?id=x.y",
        ).forEach {
            assertEquals(
                Verdict.Allow,
                decide(call("phone_launch", "link" to it), on(null, AUTONOMOUS)),
                it,
            )
        }
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `a launcher icon named like a blocked app or this app is refused`(level: SecurityLevel) {
        val home = screen(el("e1", "招商银行"), el("e2", "PhoneAgent"), el("e3", "微信"), pkg = "com.android.launcher3")
        assertEquals("hard.finance_app", refused(decide(tap("e1"), on(home, level))).reason)
        assertEquals("hard.own_app", refused(decide(tap("e2"), on(home, level))).reason)
        assertTrue(decide(tap("e3"), on(home, level)) !is Verdict.Refuse)
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `without a screen, screen actions are refused until the model observes`(level: SecurityLevel) {
        listOf(
            call("phone_tap", "screen" to "s1", "x" to 500, "y" to 500),
            call("phone_type", "screen" to "s1", "text" to "x"),
            call("phone_scroll", "screen" to "s1", "direction" to "down"),
            swipe(100, 500, 900, 500),
            call("phone_key", "key" to "enter"),
        ).forEach { c ->
            val verdict = refused(decide(c, ctx(level, null)))
            assertEquals("call.no_screen", verdict.reason, c.name)
            assertTrue("phone_observe" in verdict.hintForModel, c.name)
        }
        assertEquals(Verdict.Allow, decide(call("phone_key", "key" to "back"), ctx(level, null)))
        assertEquals(Verdict.Allow, decide(call("phone_observe"), ctx(level, null), RiskClass.READ))
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `observing this app, a blocked app, a system surface or a sensitive page is refused`(level: SecurityLevel) {
        val pages =
            mapOf(
                screen(el("e1", "设置"), pkg = OWN) to "hard.own_app",
                screen(el("e1", "转账"), pkg = "com.icbc") to "hard.finance_app",
                screen(el("e1", "允许"), pkg = "com.android.permissioncontroller") to "hard.system_app",
                screen(text("t1", "无障碍", box(0, 40, 500, 100)), pkg = Pages.SETTINGS) to "hard.sensitive_settings",
            )
        for ((page, expected) in pages) {
            for (look in listOf(call("phone_observe"), call("phone_wait", "ms" to 500))) {
                assertEquals(
                    expected,
                    refused(decide(look, ctx(level, page), RiskClass.READ)).reason,
                    "${look.name} ${page.pkg}",
                )
            }
        }
    }

    @Test
    fun `cautious - launching an app is its first action there, so the next tap runs`() {
        val launch = call("phone_launch", "app" to "com.tencent.mm")
        assertEquals(ConfirmReason.FIRST_IN_APP, reason(decide(launch, ctx(CAUTIOUS, null))))
        val wechat = screen(el("e1", "通讯录"), pkg = "com.tencent.mm")
        assertEquals(Verdict.Allow, decide(tap("e1"), ctx(CAUTIOUS, wechat, operated = setOf("com.tencent.mm"))))
        assertEquals(
            ConfirmReason.FIRST_IN_APP,
            reason(decide(tap("e1"), ctx(CAUTIOUS, wechat, operated = setOf(APP)))),
        )
    }

    @Test
    fun `cautious - a launch the user declined did not run, so the app's first action still asks`() {
        val launch = call("phone_launch", "app" to "com.tencent.mm")
        assertEquals(ConfirmReason.FIRST_IN_APP, reason(decide(launch, ctx(CAUTIOUS, null, operated = setOf(APP)))))
        // Declined: the launch never ran, so the runtime does not count it; the app is reached another way.
        val wechat = screen(el("e1", "通讯录"), pkg = "com.tencent.mm")
        val next = decide(tap("e1"), ctx(CAUTIOUS, wechat, operated = setOf(APP)))
        assertEquals(ConfirmReason.FIRST_IN_APP, reason(next))
        assertEquals("com.tencent.mm", assertInstanceOf(Verdict.Confirm::class.java, next).card.pkg)
    }

    @Test
    fun `arguments are read the way the tools read them`() {
        val chat = screen(el("e1", "发送"))
        for (ref in listOf("e1", "@e1", " e1 ", " @e1")) {
            assertEquals(
                ConfirmReason.OUTWARD,
                reason(decide(call("phone_tap", "screen" to " s1 ", "ref" to ref), on(chat))),
                ref,
            )
        }
        val point = call("phone_tap", "screen" to "s1", "ref" to JsonNull, "x" to 50.0, "y" to "425")
        assertEquals(ConfirmReason.OUTWARD, reason(decide(point, on(chat))))
        for (app in listOf(" com.icbc ", "COM.ICBC")) {
            assertEquals("hard.finance_app", refused(decide(call("phone_launch", "app" to app), on(null))).reason, app)
        }
        val details =
            call("phone_intent", "kind" to "settings", "page" to "app_details", "package" to "Dev.PhoneAgent ")
        assertEquals("hard.own_app", refused(decide(details, on(null))).reason)
        // Choices are the exact wire names; anything else the tool rejects too.
        for (c in listOf(
            call("phone_key", "key" to "ENTER"),
            call("phone_intent", "kind" to "ALARM", "time" to "07:00"),
        )) {
            assertEquals("call.unresolved", refused(decide(c, on(chat))).reason, c.args.toString())
        }
        val wifi = call("phone_intent", "kind" to "settings", "page" to "WIFI")
        assertEquals("hard.sensitive_settings", refused(decide(wifi, on(null))).reason)
        val badSubmit = call("phone_type", "screen" to "s1", "text" to "x", "submit" to "yes")
        assertEquals("call.unresolved", refused(decide(badSubmit, on(screen(field("e1", focused = true))))).reason)
    }

    @Test
    fun `the own package names are copied when the policy is built`() {
        val own = mutableSetOf(OWN)
        val rules = PolicyRules(own, setOf(Fx.OWN_LABEL))
        own.clear()
        own += "com.example.other"
        val launch = call("phone_launch", "app" to OWN)
        val verdict =
            rules.engine.evaluate(
                launch,
                rules.resolver.resolve(launch, RiskClass.WRITE, null),
                ctx(STANDARD, null),
            )
        assertEquals("hard.own_app", refused(verdict).reason)
    }
}
