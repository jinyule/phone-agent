package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.rules.Fx.call
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.SystemIntentKind
import dev.phoneagent.core.ports.SystemKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StepsTest {
    private fun parse(
        name: String,
        vararg args: Pair<String, Any?>,
    ): Step = Steps.parse(call(name, *args))

    private val e5 = Aim.Ref(ElementRef("e5"), "s1")

    @Test
    fun `reading and talking tools`() {
        listOf("phone_observe", "phone_wait").forEach { assertEquals(Step.Observe, parse(it), it) }
        listOf(
            "phone_apps",
            "ask_user",
            "hand_to_user",
            "end_not_done",
        ).forEach { assertEquals(Step.Talk, parse(it), it) }
        assertEquals(Step.Unknown, parse("shell_exec", "cmd" to "ls"))
        assertEquals(14, Steps.KNOWN.size)
    }

    @Test
    fun `phone_tap takes a ref or a point`() {
        assertEquals(Step.Tap(e5), parse("phone_tap", "screen" to "s1", "ref" to "@e5"))
        assertEquals(Step.Tap(e5), parse("phone_tap", "screen" to "s1", "ref" to "e5"))
        assertEquals(Step.Tap(Aim.Ref(ElementRef("e5"), null)), parse("phone_tap", "ref" to "e5"))
        assertEquals(Step.Tap(Aim.Point(0, 999)), parse("phone_tap", "x" to 0, "y" to 999, "long" to true))
        // A ref key wins over coordinates, and must then be valid.
        assertEquals(Step.Malformed, parse("phone_tap", "ref" to "button", "x" to 5, "y" to 5))
        assertEquals(Step.Malformed, parse("phone_tap", "ref" to "e0"))
        assertEquals(Step.Malformed, parse("phone_tap", "ref" to 5))
        assertEquals(Step.Malformed, parse("phone_tap", "ref" to null))
        assertEquals(Step.Malformed, parse("phone_tap", "x" to 1000, "y" to 5))
        assertEquals(Step.Malformed, parse("phone_tap", "x" to 5, "y" to -1))
        assertEquals(Step.Malformed, parse("phone_tap", "x" to "5", "y" to 5))
        assertEquals(Step.Malformed, parse("phone_tap", "x" to 5.5, "y" to 5))
        assertEquals(Step.Malformed, parse("phone_tap", "x" to 5))
        assertEquals(Step.Malformed, parse("phone_tap"))
    }

    @Test
    fun `phone_type takes text and an optional ref`() {
        assertEquals(Step.Type(e5, "你好", false), parse("phone_type", "screen" to "s1", "ref" to "e5", "text" to "你好"))
        assertEquals(Step.Type(null, "hi", true), parse("phone_type", "text" to "hi", "submit" to true))
        assertEquals(Step.Type(null, "hi", false), parse("phone_type", "text" to "hi", "submit" to "maybe"))
        assertEquals(
            Step.Type(null, "hi", false),
            parse(
                "phone_type",
                "text" to "hi",
                "submit" to JsonArray(emptyList()),
            ),
        )
        assertEquals(Step.Type(null, "", false), parse("phone_type", "text" to "", "append" to true))
        assertEquals(Step.Malformed, parse("phone_type", "ref" to "e5"))
        assertEquals(Step.Malformed, parse("phone_type", "text" to 5))
        assertEquals(Step.Malformed, parse("phone_type", "ref" to "#5", "text" to "hi"))
    }

    @Test
    fun `phone_scroll takes a direction and an optional ref`() {
        assertEquals(Step.Scroll(null), parse("phone_scroll", "screen" to "s1", "direction" to "down"))
        assertEquals(Step.Scroll(e5), parse("phone_scroll", "screen" to "s1", "direction" to "down", "ref" to "@e5"))
        assertEquals(Step.Malformed, parse("phone_scroll", "direction" to "down", "ref" to ""))
    }

    @Test
    fun `phone_swipe takes two points`() {
        val swipe = parse("phone_swipe", "x1" to 900, "y1" to 500, "x2" to 100, "y2" to 500, "durationMs" to 300)
        assertEquals(Step.Swipe(Aim.Point(900, 500), Aim.Point(100, 500)), swipe)
        assertEquals(Step.Malformed, parse("phone_swipe", "x1" to 900, "y1" to 500))
        assertEquals(Step.Malformed, parse("phone_swipe", "x2" to 900, "y2" to 500))
    }

    @Test
    fun `phone_key takes back, home or enter in any case`() {
        assertEquals(Step.Key(SystemKey.BACK), parse("phone_key", "key" to "back"))
        assertEquals(Step.Key(SystemKey.ENTER), parse("phone_key", "key" to "ENTER"))
        assertEquals(Step.Malformed, parse("phone_key", "key" to "volume_up"))
        assertEquals(Step.Malformed, parse("phone_key"))
    }

    @Test
    fun `phone_launch takes exactly one of app and link`() {
        assertEquals(Step.Launch("微信", link = false), parse("phone_launch", "app" to "微信", "cold" to true))
        assertEquals(Step.Launch("weixin://", link = true), parse("phone_launch", "link" to "weixin://"))
        assertEquals(Step.Malformed, parse("phone_launch", "app" to "微信", "link" to "weixin://"))
        assertEquals(Step.Malformed, parse("phone_launch", "app" to " "))
        assertEquals(Step.Malformed, parse("phone_launch", "link" to ""))
        assertEquals(Step.Malformed, parse("phone_launch"))
    }

    @Test
    fun `phone_intent takes a kind and primitive fields`() {
        val timer =
            parse(
                "phone_intent",
                "kind" to "timer",
                "seconds" to 90,
                "label" to "泡面",
                "extra" to JsonArray(emptyList()),
            )
        assertEquals(Step.Intent(SystemIntentKind.TIMER, mapOf("seconds" to "90", "label" to "泡面")), timer)
        assertEquals(
            Step.Intent(SystemIntentKind.SETTINGS, mapOf("page" to "wifi")),
            parse(
                "phone_intent",
                "kind" to "SETTINGS",
                "page" to "wifi",
            ),
        )
        assertEquals(Step.Malformed, parse("phone_intent", "kind" to "install"))
        assertEquals(Step.Malformed, parse("phone_intent"))
    }

    @Test
    fun `request_confirmation takes an action and optional details`() {
        val full =
            parse(
                "request_confirmation",
                "action" to "转账给张三",
                "target" to "张三",
                "content" to "房租",
                "amount" to "¥10",
            )
        assertEquals(Step.Confirmation("转账给张三", "张三", "房租", "¥10"), full)
        assertEquals(
            Step.Confirmation("付款", null, null, "10"),
            parse(
                "request_confirmation",
                "action" to "付款",
                "amount" to 10,
            ),
        )
        assertEquals(
            Step.Confirmation("付款", null, null, null),
            parse(
                "request_confirmation",
                "action" to "付款",
                "amount" to JsonObject(emptyMap()),
            ),
        )
        assertEquals(Step.Malformed, parse("request_confirmation", "action" to ""))
        assertEquals(Step.Malformed, parse("request_confirmation", "target" to "张三"))
    }

    @Test
    fun `only ref-taking steps name a ref`() {
        assertEquals(e5, Step.Tap(e5).ref)
        assertNull(Step.Tap(Aim.Point(1, 1)).ref)
        assertEquals(e5, Step.Type(e5, "x", false).ref)
        assertEquals(e5, Step.Scroll(e5).ref)
        assertNull(Step.Swipe(Aim.Point(1, 1), Aim.Point(2, 2)).ref)
        assertNull(Step.Key(SystemKey.HOME).ref)
    }

    @Test
    fun `json null reads as absent for strings and numbers`() {
        assertNull(Args.string(JsonObject(mapOf("k" to JsonNull)), "k"))
        assertNull(Args.int(JsonObject(mapOf("k" to JsonNull)), "k"))
        assertNull(Args.int(JsonObject(mapOf("k" to JsonPrimitive("7"))), "k"))
    }
}
