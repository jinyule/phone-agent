package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.rules.Fx.APP
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.call
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ResolverTest {
    private val resolver = RulesResolver(Reading(PolicyTuning()))

    private fun resolve(
        call: ToolCall,
        screen: ScreenSnapshot?,
        risk: RiskClass = RiskClass.WRITE,
    ): ActionIntent = resolver.resolve(call, risk, screen)

    private fun kind(
        call: ToolCall,
        screen: ScreenSnapshot?,
        risk: RiskClass = RiskClass.WRITE,
    ): IntentKind = resolve(call, screen, risk).kind

    @Nested
    inner class WithoutTheScreen {
        @Test
        fun `talking, reading and unknown tools`() {
            assertEquals(
                ActionIntent(IntentKind.READ),
                resolve(call("ask_user", "question" to "?"), null, RiskClass.READ),
            )
            assertEquals(ActionIntent(IntentKind.READ, APP), resolve(call("phone_observe"), screen(), RiskClass.READ))
            assertEquals(ActionIntent(IntentKind.READ), resolve(call("phone_wait", "ms" to 500), null, RiskClass.READ))
            assertEquals(ActionIntent(IntentKind.READ), resolve(call("memory_search"), null, RiskClass.READ))
            // An unknown tool that does more than read never resolves to READ.
            assertEquals(ActionIntent(IntentKind.NAVIGATE), resolve(call("memory_write"), null, RiskClass.WRITE))
            assertEquals(IntentKind.OUTWARD, kind(call("mail_send"), null, RiskClass.OUTWARD))
        }

        @Test
        fun `malformed calls keep the screen's app`() {
            assertEquals(ActionIntent(IntentKind.NAVIGATE, APP), resolve(call("phone_tap", "ref" to "?"), screen()))
            assertEquals(ActionIntent(IntentKind.NAVIGATE), resolve(call("phone_tap", "ref" to "?"), null))
        }

        @Test
        fun `launches name the app they open, by package name only`() {
            val launch = { arg: Pair<String, Any?> -> resolve(call("phone_launch", arg), null) }
            assertEquals(ActionIntent(IntentKind.NAVIGATE, "com.tencent.mm"), launch("app" to " com.tencent.mm "))
            // A name is no package (the engine refuses it); a link names no app.
            assertEquals(ActionIntent(IntentKind.NAVIGATE), launch("app" to "工商银行"))
            assertEquals(ActionIntent(IntentKind.NAVIGATE), launch("link" to "android-app://com.icbc/x"))
            assertEquals(ActionIntent(IntentKind.NAVIGATE), launch("link" to "https://example.com"))
        }

        @Test
        fun `system intents navigate, and settings intents land in the settings app`() {
            val settings = call("phone_intent", "kind" to "settings", "page" to "wifi")
            assertEquals(ActionIntent(IntentKind.NAVIGATE, Pages.SETTINGS), resolve(settings, null))
            assertEquals(
                ActionIntent(IntentKind.NAVIGATE),
                resolve(call("phone_intent", "kind" to "alarm", "time" to "07:00"), null),
            )
        }

        @Test
        fun `request_confirmation is SPEND with an amount, else what its action names, else OUTWARD`() {
            fun confirm(vararg args: Pair<String, Any?>) =
                resolve(call("request_confirmation", *args), null, RiskClass.READ)
            assertEquals(
                ActionIntent(IntentKind.SPEND, amount = Money(1000, null)),
                confirm(
                    "action" to "转账",
                    "amount" to "¥10",
                ),
            )
            assertEquals(
                ActionIntent(IntentKind.SPEND, amount = Money(1000, null)),
                confirm(
                    "action" to "转给他",
                    "amount" to 10,
                ),
            )
            // An amount that does not read as money is an argument to fix.
            assertEquals(ActionIntent(IntentKind.NAVIGATE), confirm("action" to "付款给张三", "amount" to "一些"))
            // Without an amount argument a spend takes the amount the screen shows.
            val checkout = screen(text("t1", "实付 ¥20"))
            val fromScreen = resolve(call("request_confirmation", "action" to "付款给张三"), checkout, RiskClass.READ)
            assertEquals(ActionIntent(IntentKind.SPEND, amount = Money(2000, null)), fromScreen)
            assertEquals(ActionIntent(IntentKind.DELETE), confirm("action" to "删除聊天记录"))
            assertEquals(ActionIntent(IntentKind.OUTWARD), confirm("action" to "通知张三明天开会"))
        }

        @Test
        fun `screen steps without a screen assume the worst that can be told`() {
            assertEquals(
                ActionIntent(IntentKind.OUTWARD),
                resolve(call("phone_type", "text" to "hi", "submit" to true), null),
            )
            assertEquals(ActionIntent(IntentKind.OUTWARD), resolve(call("phone_key", "key" to "enter"), null))
            val typing = ActionIntent(IntentKind.INPUT, fieldClass = FieldClass.PLAIN)
            assertEquals(typing, resolve(call("phone_type", "text" to "hi"), null))
            assertEquals(ActionIntent(IntentKind.NAVIGATE), resolve(call("phone_key", "key" to "back"), null))
            assertEquals(ActionIntent(IntentKind.NAVIGATE), resolve(tap("e1"), null))
        }
    }

    @Nested
    inner class Floors {
        @Test
        fun `a tool's risk class is a floor`() {
            val swipe = call("phone_swipe", "x1" to 1, "y1" to 1, "x2" to 2, "y2" to 2)
            assertEquals(IntentKind.SPEND, kind(swipe, null, RiskClass.SPEND))
            assertEquals(IntentKind.SETTINGS, kind(swipe, null, RiskClass.SETUP))
            assertEquals(IntentKind.NAVIGATE, kind(swipe, null, RiskClass.WRITE))
            assertEquals(IntentKind.NAVIGATE, kind(swipe, null, RiskClass.READ))
            // Raising a typed INPUT drops its field class; a more severe kind stays.
            val typing = call("phone_type", "text" to "hi")
            assertEquals(ActionIntent(IntentKind.OUTWARD), resolve(typing, null, RiskClass.OUTWARD))
            val pay = call("request_confirmation", "action" to "付款", "amount" to "¥1")
            assertEquals(IntentKind.SPEND, kind(pay, null, RiskClass.OUTWARD))
        }

        @Test
        fun `a hand-off field is never raised, so it stays handed off`() {
            val password = field("f1", secret = true, focused = true)
            val typing = call("phone_type", "screen" to "s1", "text" to "x")
            val expected = ActionIntent(IntentKind.INPUT, APP, password, fieldClass = FieldClass.PASSWORD)
            assertEquals(expected, resolve(typing, screen(password), RiskClass.OUTWARD))
        }
    }

    @Nested
    inner class Taps {
        @Test
        fun `a tap is what the lexicon says about its target`() {
            val send = el("e1", "发送")
            assertEquals(ActionIntent(IntentKind.OUTWARD, APP, send), resolve(tap("e1"), screen(send)))
            val back = el("e1", "返回")
            assertEquals(ActionIntent(IntentKind.NAVIGATE, APP, back), resolve(tap("e1"), screen(back)))
            val pay = el("e1", "立即支付 ¥39.90")
            assertEquals(ActionIntent(IntentKind.SPEND, APP, pay, Money(3990, null)), resolve(tap("e1"), screen(pay)))
        }

        @Test
        fun `a point counts every innermost control under it, the riskiest first`() {
            val card = el("e1", "商品卡片", bounds = box(0, 0, 999, 500))
            val buy = el("e2", "立即购买", bounds = box(800, 400, 999, 500))
            val label = el("e3", "价格", ElementRole.TEXT, box(800, 400, 999, 500), emptySet())
            val s = screen(card, buy, label, text("t1", "¥5"))
            val intent = resolve(call("phone_tap", "x" to 900, "y" to 450), s)
            assertEquals(ActionIntent(IntentKind.SPEND, APP, buy, Money(500, null)), intent)
            // Equal risks: the smaller control.
            val plain = el("e2", "详情", bounds = box(800, 400, 999, 500))
            assertEquals(plain, resolve(call("phone_tap", "x" to 900, "y" to 450), screen(card, plain)).target)
            val decor = el("e4", "装饰", ElementRole.IMAGE, box(0, 600, 100, 700), emptySet())
            assertEquals(decor, resolve(call("phone_tap", "x" to 50, "y" to 650), screen(decor)).target)
        }

        @Test
        fun `an unlabeled target is judged by the OCR at the point, then by the page`() {
            val canvas = screen(ocr = listOf(ocr("发送", box(800, 900, 999, 960)), ocr("你好", box(0, 100, 300, 160))))
            assertEquals(IntentKind.OUTWARD, kind(call("phone_tap", "x" to 900, "y" to 930), canvas))
            assertEquals(IntentKind.NAVIGATE, kind(call("phone_tap", "x" to 100, "y" to 130), canvas))
            assertEquals(IntentKind.NAVIGATE, kind(call("phone_tap", "x" to 500, "y" to 500), canvas))
            // A ref that is not on the screen has no label either.
            assertEquals(ActionIntent(IntentKind.NAVIGATE, APP), resolve(tap("e9"), screen()))
            val sheet = screen(text("t1", "实付 ¥12", box(0, 800, 500, 840)), flags = setOf(ScreenFlag.PAYMENT))
            val expected = ActionIntent(IntentKind.SPEND, APP, amount = Money(1200, null))
            assertEquals(expected, resolve(call("phone_tap", "x" to 900, "y" to 950), sheet))
            assertEquals(IntentKind.SPEND, kind(tap("e9"), sheet))
            // A labelled element on a payment page is judged by its label.
            val detail = el("e1", "查看明细")
            assertEquals(IntentKind.NAVIGATE, kind(tap("e1"), screen(detail, flags = setOf(ScreenFlag.PAYMENT))))
            assertEquals(IntentKind.SPEND, kind(tap("e1"), screen(el("e1"), flags = setOf(ScreenFlag.PAYMENT))))
            assertEquals(IntentKind.NAVIGATE, kind(tap("e1"), screen(el("e1"))))
        }

        @Test
        fun `an unlabeled control hit by a point is judged by the OCR there, fields and clear buttons are not`() {
            val web = el("e1", role = ElementRole.CONTAINER, bounds = box(0, 0, 999, 999), states = emptySet())
            val page = screen(web, ocr = listOf(ocr("发布", box(800, 900, 999, 960))))
            assertEquals(
                ActionIntent(IntentKind.OUTWARD, APP, web),
                resolve(call("phone_tap", "x" to 900, "y" to 930), page),
            )
            assertEquals(IntentKind.NAVIGATE, kind(call("phone_tap", "x" to 100, "y" to 100), page))
            val input = field("e2", bounds = box(0, 100, 800, 160))
            val clear = el("e3", bounds = box(700, 110, 790, 150))
            val drawn = listOf(ocr("发送给张三", box(0, 100, 600, 160)), ocr("清除", box(700, 110, 790, 150)))
            val form = screen(input, clear, ocr = drawn)
            assertEquals(IntentKind.NAVIGATE, kind(call("phone_tap", "x" to 300, "y" to 130), form))
            assertEquals(IntentKind.NAVIGATE, kind(call("phone_tap", "x" to 745, "y" to 130), form))
            // "删除" drawn on the icon is a delete word, not a clearing one.
            val deletes = screen(input, clear, ocr = listOf(ocr("删除", box(700, 110, 790, 150))))
            assertEquals(IntentKind.DELETE, kind(call("phone_tap", "x" to 745, "y" to 130), deletes))
        }
    }

    @Nested
    inner class Typing {
        private fun type(vararg args: Pair<String, Any?>) = call("phone_type", "screen" to "s1", *args)

        @Test
        fun `sensitive fields are INPUT with their class`() {
            val code = field("e1", hint = "请输入验证码")
            val expected = ActionIntent(IntentKind.INPUT, APP, code, fieldClass = FieldClass.VERIFICATION_CODE)
            assertEquals(expected, resolve(type("ref" to "e1", "text" to "1234", "submit" to true), screen(code)))
        }

        @Test
        fun `submitting reaches out, unless the field searches`() {
            val chat = field("e1", hint = "发消息")
            assertEquals(
                ActionIntent(IntentKind.OUTWARD, APP, chat),
                resolve(
                    type(
                        "ref" to "e1",
                        "text" to "hi",
                        "submit" to true,
                    ),
                    screen(chat),
                ),
            )
            val search = field("e1", hint = "搜索")
            val searching = ActionIntent(IntentKind.INPUT, APP, search, fieldClass = FieldClass.PLAIN)
            assertEquals(searching, resolve(type("ref" to "e1", "text" to "猫", "submit" to true), screen(search)))
        }

        @Test
        fun `without a ref, typing goes to the focused or only field`() {
            val a = field("e1", hint = "标题")
            val b = field("e2", hint = "密码", focused = true)
            assertEquals(FieldClass.PASSWORD, resolve(type("text" to "x"), screen(a, b)).fieldClass)
            assertEquals(
                ActionIntent(IntentKind.INPUT, APP, a, fieldClass = FieldClass.PLAIN),
                resolve(
                    type("text" to "x"),
                    screen(a),
                ),
            )
            // Nowhere to type: plain input; a missing ref likewise.
            assertEquals(
                ActionIntent(IntentKind.INPUT, APP, fieldClass = FieldClass.PLAIN),
                resolve(type("text" to "x"), screen()),
            )
            assertEquals(
                ActionIntent(IntentKind.INPUT, APP, fieldClass = FieldClass.PLAIN),
                resolve(
                    type(
                        "ref" to "e9",
                        "text" to "x",
                    ),
                    screen(),
                ),
            )
        }

        @Test
        fun `typing in the settings app changes settings`() {
            val name = field("e1", hint = "设备名称")
            val s = screen(name, pkg = Pages.SETTINGS)
            assertEquals(
                ActionIntent(IntentKind.SETTINGS, Pages.SETTINGS, name),
                resolve(
                    type(
                        "ref" to "e1",
                        "text" to "x",
                    ),
                    s,
                ),
            )
        }
    }

    @Nested
    inner class Gestures {
        private fun swipe(
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
        ) = call("phone_swipe", "x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "durationMs" to 300)

        private val row = el("e1", "张三：明天见", bounds = box(0, 300, 999, 400))

        @Test
        fun `scrolling navigates`() {
            val list =
                el("e1", role = ElementRole.LIST, bounds = box(0, 0, 999, 999), states = setOf(ElementState.SCROLLABLE))
            val scroll = call("phone_scroll", "screen" to "s1", "direction" to "down", "ref" to "e1")
            assertEquals(ActionIntent(IntentKind.NAVIGATE, APP, list), resolve(scroll, screen(list)))
            assertEquals(
                ActionIntent(IntentKind.NAVIGATE, APP),
                resolve(call("phone_scroll", "direction" to "down"), screen(list)),
            )
        }

        @Test
        fun `a sideways swipe on a list row may delete it`() {
            val list =
                el("e8", role = ElementRole.LIST, bounds = box(0, 0, 999, 999), states = setOf(ElementState.SCROLLABLE))
            assertEquals(
                ActionIntent(IntentKind.DELETE, APP, row),
                resolve(swipe(900, 350, 100, 350), screen(list, row)),
            )
            assertEquals(IntentKind.DELETE, kind(swipe(100, 350, 600, 360), screen(row)))
        }

        @Test
        fun `other swipes navigate`() {
            assertEquals(IntentKind.NAVIGATE, kind(swipe(900, 350, 850, 350), screen(row)))
            assertEquals(IntentKind.NAVIGATE, kind(swipe(500, 350, 0, 900), screen(row)))
            assertEquals(ActionIntent(IntentKind.NAVIGATE, APP), resolve(swipe(900, 650, 100, 650), screen(row)))
            val narrow = el("e1", "标签", bounds = box(0, 300, 400, 400))
            assertEquals(IntentKind.NAVIGATE, kind(swipe(50, 350, 500, 350), screen(narrow)))
            val static = el("e1", "横幅", bounds = box(0, 300, 999, 400), states = emptySet())
            assertEquals(IntentKind.NAVIGATE, kind(swipe(900, 350, 100, 350), screen(static)))
            // Paging a carousel is not deleting its card.
            val carousel = el("e8", bounds = box(0, 250, 999, 450), states = setOf(ElementState.SCROLLABLE))
            assertEquals(IntentKind.NAVIGATE, kind(swipe(900, 350, 100, 350), screen(carousel, row)))
            // A carousel elsewhere on the page does not matter.
            val banner = el("e8", bounds = box(0, 500, 999, 700), states = setOf(ElementState.SCROLLABLE))
            assertEquals(IntentKind.DELETE, kind(swipe(900, 350, 100, 350), screen(banner, row)))
        }

        @Test
        fun `in the settings app a swipe from a value control sets it, from anything else it does not`() {
            val toggle = el("e1", "蓝牙", ElementRole.SWITCH, box(800, 300, 999, 400))
            assertEquals(IntentKind.SETTINGS, kind(swipe(850, 350, 950, 350), screen(toggle, pkg = Pages.SETTINGS)))
            val entry = el("e1", "显示", bounds = box(0, 300, 999, 400))
            assertEquals(IntentKind.NAVIGATE, kind(swipe(500, 380, 500, 100), screen(entry, pkg = Pages.SETTINGS)))
        }

        @Test
        fun `a swipe on a pay slider pays`() {
            val slider = el("e1", "向右滑动支付", bounds = box(0, 800, 999, 900))
            assertEquals(IntentKind.SPEND, kind(swipe(50, 850, 950, 850), screen(slider)))
        }

        @Test
        fun `enter sends from a field that does not search`() {
            val enter = call("phone_key", "key" to "enter")
            val chat = field("e1", hint = "发消息", focused = true)
            assertEquals(ActionIntent(IntentKind.OUTWARD, APP, chat), resolve(enter, screen(chat)))
            val search = field("e1", hint = "搜索", focused = true)
            assertEquals(IntentKind.NAVIGATE, kind(enter, screen(search)))
            // A focused button is activated.
            val send = el("e1", "发送", states = setOf(ElementState.FOCUSED, ElementState.CLICKABLE))
            assertEquals(IntentKind.OUTWARD, kind(enter, screen(send)))
            val other = el("e1", "更多", states = setOf(ElementState.FOCUSED))
            assertEquals(ActionIntent(IntentKind.NAVIGATE, APP, other), resolve(enter, screen(other)))
            // Nothing focused: it navigates only when every field searches.
            assertEquals(IntentKind.NAVIGATE, kind(enter, screen(field("e1", hint = "搜索"))))
            assertEquals(IntentKind.OUTWARD, kind(enter, screen(field("e1", hint = "搜索"), field("e2", hint = "评论"))))
            assertEquals(IntentKind.OUTWARD, kind(enter, screen()))
            assertEquals(
                ActionIntent(IntentKind.NAVIGATE, APP, chat),
                resolve(call("phone_key", "key" to "home"), screen(chat)),
            )
        }
    }
}
