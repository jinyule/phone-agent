package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Fx.OWN
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.call
import dev.phoneagent.core.policy.rules.Fx.ctx
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.tool.RiskClass
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class PolicyRulesTest {
    private val policy = PolicyRules(setOf(OWN, "$OWN.debug"))

    private val screens =
        listOf(
            screen(
                text("t1", "张三", box(0, 40, 500, 100)),
                el("e1", "发送", bounds = box(820, 900, 999, 960)),
                field("e2", hint = "发消息", focused = true).copy(value = "明天见"),
                el("e3", "删除").copy(window = 1),
                el("e4", "确定").copy(window = 1),
                text("t2", "确定删除这条消息？", window = 1),
                el("e5").copy(resourceId = "com.x:id/btn_send"),
                el("e6", "×", bounds = box(700, 910, 790, 950)),
            ),
            screen(
                text("t1", "合计 ¥20", box(0, 900, 400, 960)),
                el("e1", "去结算", bounds = box(600, 900, 999, 960)),
                el("e2", "删除搜索历史"),
                el("e3", "发送验证码"),
                el("e4", "评论(23)"),
                el("e5", "确定"),
            ),
            screen(
                text("t1", "设置", box(0, 40, 500, 100)),
                el("e1", "蓝牙", role = ElementRole.SWITCH),
                el("e2", "开发者选项"),
                el("e3", "WLAN"),
                pkg = Pages.SETTINGS,
            ),
            screen(text("t1", "账号登录"), field("e1", secret = true), el("e2", "暂不登录"), flags = setOf(ScreenFlag.LOGIN)),
            screen(el("e1", "设置"), pkg = OWN),
            screen(el("e1", "转账"), pkg = "com.icbc"),
            screen(ocr = listOf(ocr("立即支付", box(0, 800, 999, 900))), flags = setOf(ScreenFlag.SECURE), pkg = null),
            screen(
                el(
                    "e8",
                    role = ElementRole.LIST,
                    bounds = box(0, 0, 999, 999),
                    states = setOf(ElementState.SCROLLABLE),
                ),
                el("e1", "李四：好的", bounds = box(0, 300, 999, 400)),
            ),
        )

    @Test
    fun `configuration is checked when the policy is built`() {
        assertThrows<IllegalArgumentException> { PolicyRules(emptySet()) }
        val e = assertThrows<IllegalArgumentException> { PolicyRules(setOf(OWN, "not a package")) }
        assertEquals("Invalid own package names: [not a package]", e.message)
    }

    @Test
    fun `the warning the screen shows and the verdict agree`() {
        for (s in screens) {
            for (element in s.elements) {
                val call = tap(element.ref.value)
                val risk = policy.lexicon.activationRisk(element, s)
                val intent = policy.resolver.resolve(call, RiskClass.WRITE, s)
                val where = "${element.ref.value} '${element.name}' on ${s.pkg}"
                assertEquals(risk?.kind ?: IntentKind.NAVIGATE, intent.kind, where)
                val verdict =
                    policy.engine.evaluate(
                        call,
                        intent,
                        ctx(SecurityLevel.AUTONOMOUS, s, operated = setOfNotNull(s.pkg)),
                    )
                if (risk?.kind == IntentKind.SPEND && verdict is Verdict.Confirm) {
                    assertEquals(ConfirmReason.SPEND, verdict.card.reason, where)
                }
                if (risk?.kind == IntentKind.SPEND) assertFalse(verdict == Verdict.Allow, where)
            }
        }
    }

    @Test
    fun `refusal hints point to a next step and reveal nothing of the lists`() {
        val list = javaClass.getResource("/dev/phoneagent/core/policy/rules/blocked-apps.txt")?.readText().orEmpty()
        val names =
            list
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .flatMap { line ->
                    val (pkg, _, labels) = line.trim().split(Regex("\\s+"), limit = 3)
                    listOf(pkg) + labels.split('|')
                }
        assertTrue(names.size > 100, "list read")
        for (r in Refusal.entries) {
            val hint = r.verdict().hintForModel
            names.forEach { name ->
                val word = Regex("(?<![A-Za-z])${Regex.escape(name)}(?![A-Za-z])", RegexOption.IGNORE_CASE)
                assertFalse(word.containsMatchIn(hint), "${r.name} names '$name'")
            }
            assertFalse(hint.any(Char::isDigit), "${r.name} names a threshold")
            assertFalse(r.reason in hint, r.name)
            val next = listOf("phone_", "end_not_done", "user", "Fix the arguments", "another tool")
            assertTrue(next.any { it in hint }, "${r.name} gives no next step")
        }
        HandOffs.entries.forEach { assertTrue(it.verdict().hint.endsWith("tap Done."), it.name) }
    }

    @Test
    fun `random calls on any screen never throw`() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val name = (Steps.KNOWN + "other_tool").random(random)
            val args = KEYS.shuffled(random).take(random.nextInt(0, 6)).associateWith { value(random) }
            val call = call(name, *args.toList().toTypedArray(), id = "c$i")
            val screen = (screens + null).random(random)
            val risk = RiskClass.entries.random(random)
            val ctx =
                ctx(
                    SecurityLevel.entries.random(random),
                    screen,
                    random.nextBoolean(),
                    outwardConfirmed = setOf(Fx.APP),
                )
            assertDoesNotThrow("$name $args") {
                val verdict = policy.engine.evaluate(call, policy.resolver.resolve(call, risk, screen), ctx)
                if (verdict is Verdict.Confirm) {
                    assertInstanceOf(
                        Verdict.Allow::class.java,
                        policy.engine.evaluate(
                            call,
                            policy.resolver.resolve(call, risk, screen),
                            ctx.copy(approved = verdict.card),
                        ),
                    )
                }
            }
        }
    }

    private fun value(random: Random): Any? =
        when (random.nextInt(6)) {
            0 -> STRINGS.random(random)
            1 -> NUMBERS.random(random)
            2 -> random.nextBoolean()
            3 -> null
            4 -> JsonArray(listOf(JsonPrimitive("e1")))
            else -> JsonObject(mapOf("k" to JsonPrimitive(1)))
        }

    private companion object {
        const val SEED = 20261101
        const val ITERATIONS = 5000

        val KEYS =
            listOf(
                "screen",
                "ref",
                "x",
                "y",
                "long",
                "text",
                "append",
                "submit",
                "direction",
                "x1",
                "y1",
                "x2",
                "y2",
                "durationMs",
                "key",
                "app",
                "link",
                "cold",
                "kind",
                "page",
                "package",
                "time",
                "action",
                "target",
                "content",
                "amount",
            )

        val STRINGS =
            listOf(
                "e1",
                "@e2",
                "e3",
                "e9",
                "s1",
                "s0",
                "",
                " ",
                "发送",
                "支付",
                "back",
                "enter",
                "HOME",
                "settings",
                "alarm",
                "wifi",
                "accessibility",
                "alipays://x",
                "android-app://com.icbc/x",
                "intent:#Intent;package=cmb.pb;end",
                "¥10",
                "10",
                "com.icbc",
                "支付宝",
                OWN,
                "x".repeat(300),
                "4111111111111111",
            )

        val NUMBERS: List<Number> = listOf(-1, 0, 5, 425, 999, 1000, Int.MAX_VALUE, Long.MAX_VALUE, 5.5, Double.NaN)
    }
}
