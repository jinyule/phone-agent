package dev.phoneagent.core.policy

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.tool.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PolicyContractsTest {
    @Test
    fun `money is non-negative with an optional ISO currency`() {
        assertEquals(Money(0), Money(0, null))
        assertEquals("CNY", Money(1999, "CNY").currency)
        assertThrows<IllegalArgumentException> { Money(-1) }
        assertThrows<IllegalArgumentException> { Money(1, "yuan") }
        assertThrows<IllegalArgumentException> { Money(1, "cny") }
    }

    @Test
    fun `verdicts and contexts round-trip with stable discriminators`() {
        val card =
            ConfirmCard(ToolCallId("c"), IntentKind.OUTWARD, "send", "张三", "十点", Money(100, "CNY"), "com.tencent.mm")
        val verdicts =
            listOf(
                Verdict.Allow,
                Verdict.Confirm(card),
                Verdict.HandOff(HandoffKind.LOGIN, "log in"),
                Verdict.Refuse("bank", "Banking apps are off limits."),
            )
        verdicts.forEach { assertEquals(it, Json.decodeFromString<Verdict>(Json.encodeToString<Verdict>(it))) }
        assertEquals("""{"type":"allow"}""", Json.encodeToString<Verdict>(Verdict.Allow))
        val ctx =
            PolicyContext(
                TaskId("t"),
                SecurityLevel.STANDARD,
                unattended = false,
                screen = null,
                operatedPackages = setOf("a.b"),
                outwardConfirmedPackages = setOf("a.b"),
            )
        assertEquals(setOf("a.b"), ctx.operatedPackages intersect ctx.outwardConfirmedPackages)
        val intent = ActionIntent(IntentKind.INPUT, "a.b", fieldClass = FieldClass.BANK_CARD)
        assertEquals(intent, Json.decodeFromString<ActionIntent>(Json.encodeToString(intent)))
    }

    @Test
    fun `an engine is a pure function of call, intent and context`() {
        val engine =
            PolicyEngine { _, intent, ctx ->
                if (intent.kind == IntentKind.SPEND &&
                    ctx.unattended
                ) {
                    Verdict.Refuse("unattended spend", "Cannot pay now.")
                } else {
                    Verdict.Allow
                }
            }
        val call = ToolCall(ToolCallId("c"), TaskId("t"), SessionId("s"), "phone_tap", JsonObject(emptyMap()), "pay")
        val ctx = PolicyContext(TaskId("t"), SecurityLevel.AUTONOMOUS, unattended = true, screen = null)
        assertEquals(
            Verdict.Refuse("unattended spend", "Cannot pay now."),
            engine.evaluate(call, ActionIntent(IntentKind.SPEND), ctx),
        )
        assertEquals(Verdict.Allow, engine.evaluate(call, ActionIntent(IntentKind.READ), ctx))
    }

    @Test
    fun `each context default can be supplied on its own`() {
        val operated =
            PolicyContext(TaskId("t"), SecurityLevel.STANDARD, false, null, operatedPackages = setOf("a.b"))
        val confirmed =
            PolicyContext(TaskId("t"), SecurityLevel.STANDARD, false, null, outwardConfirmedPackages = setOf("a.b"))
        assertEquals(setOf("a.b") to emptySet<String>(), operated.operatedPackages to operated.outwardConfirmedPackages)
        assertEquals(
            emptySet<String>() to setOf("a.b"),
            confirmed.operatedPackages to confirmed.outwardConfirmedPackages,
        )
    }
}
