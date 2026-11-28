package dev.phoneagent.core.policy

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PolicyContractsTest {
    @Test
    fun `verdicts and contexts round-trip with stable discriminators`() {
        val card =
            ConfirmCard(ToolCallId("c"), ConfirmReason.OUTWARD, "send", "张三", "十点", Money(100, "CNY"), "com.tencent.mm")
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

    @Test
    fun `the risk lexicon is shared through the contract and only flags risky kinds`() {
        val send = element("e10", "发送")
        val back = element("e1", "返回")
        val screen = snapshot(send, back)
        val lexicon =
            RiskLexicon { element, _ ->
                if (element.name == "发送") ElementRisk(IntentKind.OUTWARD, "发送") else null
            }
        assertEquals(ElementRisk(IntentKind.OUTWARD, "发送"), lexicon.activationRisk(send, screen))
        assertEquals(null, lexicon.activationRisk(back, screen))
        assertEquals(
            setOf(IntentKind.OUTWARD, IntentKind.SPEND, IntentKind.DELETE, IntentKind.SETTINGS),
            ElementRisk.RISKY_KINDS,
        )
        ElementRisk.RISKY_KINDS.forEach { assertEquals(it, ElementRisk(it, "词").kind) }
        (IntentKind.entries - ElementRisk.RISKY_KINDS).forEach { kind ->
            assertThrows<IllegalArgumentException>(kind.name) { ElementRisk(kind, "词") }
        }
        assertThrows<IllegalArgumentException> { ElementRisk(IntentKind.SPEND, " ") }
    }

    private fun element(
        ref: String,
        name: String,
    ) = UiElement(ElementRef(ref), ElementRole.BUTTON, name, bounds = Bounds(0, 0, 10, 10))

    private fun snapshot(vararg elements: UiElement) =
        ScreenSnapshot(
            SnapshotId("snap-1"),
            SurfaceRef(displayId = 7, kind = SurfaceKind.AGENT),
            pkg = "com.tencent.mm",
            activity = null,
            elements = elements.toList(),
            ocr = null,
            quality = TreeQuality.GOOD,
            flags = emptySet(),
            fingerprint = "fp",
        )

    @Test
    fun `an intent resolver maps a call to an intent and treats the risk class as a floor`() {
        val resolver =
            IntentResolver { call, risk, screen ->
                when {
                    risk == RiskClass.SPEND -> ActionIntent(IntentKind.SPEND, screen?.pkg)
                    call.name == "phone_observe" -> ActionIntent(IntentKind.READ, screen?.pkg)
                    else -> ActionIntent(IntentKind.INPUT, screen?.pkg)
                }
            }
        val screen = snapshot(element("e10", "发送"))
        val observe =
            ToolCall(ToolCallId("c"), TaskId("t"), SessionId("s"), "phone_observe", JsonObject(emptyMap()), "看")
        assertEquals(ActionIntent(IntentKind.READ, "com.tencent.mm"), resolver.resolve(observe, RiskClass.READ, screen))
        assertEquals(ActionIntent(IntentKind.SPEND), resolver.resolve(observe, RiskClass.SPEND, null))
    }

    @Test
    fun `a context may carry the approved card for the re-check, and each default can be supplied alone`() {
        val card = ConfirmCard(ToolCallId("c"), ConfirmReason.OUTWARD, "发送", "张三", pkg = "com.tencent.mm")
        val base = PolicyContext(TaskId("t"), SecurityLevel.STANDARD, unattended = false, screen = null)
        assertEquals(null, base.approved)
        val approved = PolicyContext(TaskId("t"), SecurityLevel.STANDARD, false, null, approved = card)
        assertEquals(card, approved.approved)
        assertEquals(emptySet<String>(), approved.operatedPackages + approved.outwardConfirmedPackages)
    }
}
