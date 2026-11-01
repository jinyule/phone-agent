package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.rules.Fx.APP
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.call
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CardsTest {
    private val title = text("t1", "张三", box(0, 40, 500, 100))
    private val send = el("e1", "发送")
    private val tapSend = Step.Tap(Aim.Ref(ElementRef("e1"), "s1"))

    @Test
    fun `an outward tap shows the conversation and the draft it sends`() {
        val draft = field("f1", focused = true).copy(value = "明天见")
        val intent = ActionIntent(IntentKind.OUTWARD, APP, send)
        val card = Cards.forCall(tap("e1"), tapSend, intent, ConfirmReason.OUTWARD, screen(title, send, draft))
        assertEquals(ConfirmCard(ToolCallId("c1"), ConfirmReason.OUTWARD, "给张三发消息", "张三", "明天见", null, APP), card)
        // No title: the button's label; no draft: no content.
        val bare =
            Cards.forCall(
                tap("e1"),
                tapSend,
                intent,
                ConfirmReason.OUTWARD,
                screen(send, field("f1").copy(value = " ")),
            )
        assertEquals("发送", bare.target)
        assertNull(bare.content)
        assertNull(Cards.forCall(tap("e1"), tapSend, intent, ConfirmReason.OUTWARD, screen(send)).content)
    }

    @Test
    fun `other cards show the element and the amount`() {
        val pay = el("e1", "立即支付")
        val intent = ActionIntent(IntentKind.SPEND, APP, pay, Money(3990, "CNY"))
        val draft = field("f1", focused = true).copy(value = "备注")
        val card = Cards.forCall(tap("e1"), tapSend, intent, ConfirmReason.SPEND, screen(title, pay, draft))
        assertEquals(
            ConfirmCard(ToolCallId("c1"), ConfirmReason.SPEND, "给张三发消息", "立即支付", null, Money(3990, "CNY"), APP),
            card,
        )
    }

    @Test
    fun `typed text is the content, masked`() {
        val step = Step.Type(null, "卡号 4111111111111111", true)
        val intent = ActionIntent(IntentKind.OUTWARD, APP)
        val card = Cards.forCall(call("phone_type", "text" to step.text), step, intent, ConfirmReason.OUTWARD, screen())
        assertEquals("卡号 ************1111", card.content)
        assertNull(card.target)
        val toCard = ActionIntent(IntentKind.INPUT, APP, fieldClass = FieldClass.BANK_CARD)
        assertEquals(
            "****5678",
            Cards
                .forCall(
                    call("phone_type"),
                    Step.Type(null, "12345678", false),
                    toCard,
                    ConfirmReason.FIRST_IN_APP,
                    null,
                ).content,
        )
    }

    @Test
    fun `without a screen or a screen step there is no target or draft`() {
        val intent = ActionIntent(IntentKind.OUTWARD, APP)
        val blind = Cards.forCall(tap("e1"), tapSend, intent, ConfirmReason.OUTWARD, null)
        assertEquals(ConfirmCard(ToolCallId("c1"), ConfirmReason.OUTWARD, "给张三发消息", pkg = APP), blind)
        val launch = Step.Launch("com.example.chat", link = false)
        val draft = field("f1", focused = true).copy(value = "明天见")
        val card = Cards.forCall(call("phone_launch"), launch, intent, ConfirmReason.OUTWARD, screen(draft))
        assertNull(card.content)
    }

    @Test
    fun `a requested card comes from the model's arguments and names no app`() {
        val step = Step.Confirmation("转账给张三", "张三", "房租 6222021234567890128", "¥10")
        val card =
            Cards.requested(
                call("request_confirmation"),
                step,
                ActionIntent(IntentKind.SPEND, amount = Money(1000, "CNY")),
            )
        val expected =
            ConfirmCard(
                ToolCallId("c1"),
                ConfirmReason.REQUESTED_BY_MODEL,
                "转账给张三",
                "张三",
                "房租 ***************0128",
                Money(1000, "CNY"),
            )
        assertEquals(expected, card)
        val sparse =
            Cards.requested(
                call("request_confirmation"),
                Step.Confirmation("通知", " ", "", null),
                ActionIntent(IntentKind.OUTWARD),
            )
        assertEquals(ConfirmCard(ToolCallId("c1"), ConfirmReason.REQUESTED_BY_MODEL, "通知"), sparse)
        assertEquals(
            ConfirmCard(ToolCallId("c1"), ConfirmReason.REQUESTED_BY_MODEL, "通知"),
            Cards.requested(
                call("request_confirmation"),
                Step.Confirmation("通知", null, null, null),
                ActionIntent(IntentKind.OUTWARD),
            ),
        )
    }
}
