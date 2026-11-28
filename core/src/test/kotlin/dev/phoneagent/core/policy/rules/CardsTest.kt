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
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.SystemKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardsTest {
    private val cards = Cards(Reading(PolicyTuning()))
    private val title = text("t1", "张三", box(0, 40, 500, 100))
    private val send = el("e1", "发送")
    private val tapSend = Step.Tap(Aim.Ref(ElementRef("e1"), "s1"), long = false)

    @Test
    fun `an outward tap shows the conversation and the draft it sends`() {
        val draft = field("f1", focused = true).copy(value = "明天见")
        val intent = ActionIntent(IntentKind.OUTWARD, APP, send)
        val card = cards.forCall(tap("e1"), tapSend, intent, ConfirmReason.OUTWARD, screen(title, send, draft))
        assertEquals(ConfirmCard(ToolCallId("c1"), ConfirmReason.OUTWARD, "给张三发消息", "张三", "明天见", null, APP), card)
        // No title: the button's label; no draft: no content.
        val bare =
            cards.forCall(
                tap("e1"),
                tapSend,
                intent,
                ConfirmReason.OUTWARD,
                screen(send, field("f1").copy(value = " ")),
            )
        assertEquals("发送", bare.target)
        assertNull(bare.content)
        assertNull(cards.forCall(tap("e1"), tapSend, intent, ConfirmReason.OUTWARD, screen(send)).content)
    }

    @Test
    fun `other cards show the element and the amount`() {
        val pay = el("e1", "立即支付")
        val intent = ActionIntent(IntentKind.SPEND, APP, pay, Money(3990, "CNY"))
        val draft = field("f1", focused = true).copy(value = "备注")
        val card = cards.forCall(tap("e1"), tapSend, intent, ConfirmReason.SPEND, screen(title, pay, draft))
        assertEquals(
            ConfirmCard(ToolCallId("c1"), ConfirmReason.SPEND, "给张三发消息", "立即支付", null, Money(3990, "CNY"), APP),
            card,
        )
    }

    @Test
    fun `a card asked for something else shows whom a send goes to, what the control says, and the draft`() {
        val both = el("e1", "发送并删除原图")
        val draft = field("f1", focused = true).copy(value = "明天见")
        val delete = ActionIntent(IntentKind.DELETE, APP, both)
        val card = cards.forCall(tap("e1"), tapSend, delete, ConfirmReason.DELETE, screen(title, both, draft))
        val expected = ConfirmCard(ToolCallId("c1"), ConfirmReason.DELETE, "给张三发消息", "张三（发送并删除原图）", "明天见", null, APP)
        assertEquals(expected, card)
        // No title: the label alone, and still the draft.
        val untitled = cards.forCall(tap("e1"), tapSend, delete, ConfirmReason.DELETE, screen(both, draft))
        assertEquals("发送并删除原图" to "明天见", untitled.target to untitled.content)
        // A delete that sends nothing: its label, no draft.
        val only = el("e1", "删除")
        val plain = ActionIntent(IntentKind.DELETE, APP, only)
        val deleted = cards.forCall(tap("e1"), tapSend, plain, ConfirmReason.DELETE, screen(title, only, draft))
        assertEquals("删除" to null, deleted.target to deleted.content)
        // A red packet pays and sends.
        val packet = el("e1", "发红包")
        val spend = ActionIntent(IntentKind.SPEND, APP, packet)
        val paid = cards.forCall(tap("e1"), tapSend, spend, ConfirmReason.SPEND, screen(title, packet, draft))
        assertEquals("张三（发红包）" to "明天见", paid.target to paid.content)
    }

    @Test
    fun `the recipient is named once, and a send seen only in drawn text or an icon counts too`() {
        val draft = field("f1", focused = true).copy(value = "明天见")
        val drawn = screen(title, draft, ocr = listOf(ocr("发送并删除", box(0, 600, 999, 650))))
        val there = Step.Tap(Aim.Point(500, 625), long = false)
        val delete = ActionIntent(IntentKind.DELETE, APP)
        val card = cards.forCall(tap("e1"), there, delete, ConfirmReason.DELETE, drawn)
        assertEquals("张三" to "明天见", card.target to card.content)
        val icon = el("e1").copy(resourceId = "com.x:id/btn_send_delete")
        val iconic = ActionIntent(IntentKind.DELETE, APP, icon)
        val byIcon = cards.forCall(tap("e1"), tapSend, iconic, ConfirmReason.DELETE, screen(title, icon, draft))
        assertEquals("张三" to "明天见", byIcon.target to byIcon.content)
        // A title that says what the control says is not repeated.
        val transfer = el("e1", "转账")
        val titled = screen(text("t1", "转账", box(0, 40, 500, 100)), transfer, draft)
        val pay = ActionIntent(IntentKind.SPEND, APP, transfer)
        assertEquals("转账", cards.forCall(tap("e1"), tapSend, pay, ConfirmReason.SPEND, titled).target)
        // Steps that are not on the screen send nothing.
        val launch = Step.Launch(APP, link = false)
        val opened =
            cards.forCall(
                tap("e1"),
                launch,
                ActionIntent(IntentKind.NAVIGATE, APP),
                ConfirmReason.FIRST_IN_APP,
                drawn,
            )
        assertEquals(null to null, opened.target to opened.content)
    }

    @Test
    fun `on a screen that cannot be read, typing that submits and Enter send`() {
        val small = Cards(Reading(PolicyTuning(maxElements = 2)))
        val draft = field("f1", focused = true).copy(value = "明天见")
        val crowded = screen(title, send, draft)
        val settings = ActionIntent(IntentKind.SETTINGS, APP)
        val enter =
            small.forCall(
                call("phone_key"),
                Step.Key(SystemKey.ENTER),
                settings,
                ConfirmReason.SETTINGS,
                crowded,
            )
        assertEquals("张三" to "明天见", enter.target to enter.content)
        val tapped = small.forCall(tap("e1"), tapSend, settings, ConfirmReason.SETTINGS, crowded)
        assertEquals(null to null, tapped.target to tapped.content)
    }

    @Test
    fun `of the keys only Enter activates the focused control and may send`() {
        val intents = ScreenIntents(Reading(PolicyTuning()))
        val focused = screen(el("e1", "发送并删除", states = Fx.CLICK + ElementState.FOCUSED))
        assertTrue(intents.sends(Step.Key(SystemKey.ENTER), focused))
        assertFalse(intents.sends(Step.Key(SystemKey.BACK), focused))
        assertFalse(intents.sends(Step.Scroll(null), focused))
    }

    @Test
    fun `typed text is the content, masked`() {
        val step = Step.Type(null, "卡号 4111111111111111", submit = true, append = false)
        val intent = ActionIntent(IntentKind.OUTWARD, APP)
        val card = cards.forCall(call("phone_type", "text" to step.text), step, intent, ConfirmReason.OUTWARD, screen())
        assertEquals("卡号 ************1111", card.content)
        assertNull(card.target)
        val toCard = ActionIntent(IntentKind.INPUT, APP, fieldClass = FieldClass.BANK_CARD)
        assertEquals(
            "****5678",
            cards
                .forCall(
                    call("phone_type"),
                    Step.Type(null, "12345678", submit = false, append = false),
                    toCard,
                    ConfirmReason.FIRST_IN_APP,
                    null,
                ).content,
        )
    }

    @Test
    fun `typing that appends shows what the field will hold`() {
        val box = field("f1", focused = true).copy(value = "hello")
        val intent = ActionIntent(IntentKind.OUTWARD, APP, box)
        val append = Step.Type(Aim.Ref(ElementRef("f1"), "s1"), " world", submit = true, append = true)
        assertEquals(
            "hello world",
            cards.forCall(call("phone_type"), append, intent, ConfirmReason.OUTWARD, screen(box)).content,
        )
        val empty = ActionIntent(IntentKind.OUTWARD, APP, field("f1"))
        assertEquals(
            " world",
            cards.forCall(call("phone_type"), append, empty, ConfirmReason.OUTWARD, screen()).content,
        )
        assertEquals(
            " world",
            cards
                .forCall(
                    call("phone_type"),
                    append,
                    ActionIntent(IntentKind.OUTWARD),
                    ConfirmReason.OUTWARD,
                    null,
                ).content,
        )
        val replace = append.copy(append = false)
        assertEquals(
            " world",
            cards.forCall(call("phone_type"), replace, intent, ConfirmReason.OUTWARD, screen(box)).content,
        )
    }

    @Test
    fun `without a screen or a screen step there is no target or draft`() {
        val intent = ActionIntent(IntentKind.OUTWARD, APP)
        val blind = cards.forCall(tap("e1"), tapSend, intent, ConfirmReason.OUTWARD, null)
        assertEquals(ConfirmCard(ToolCallId("c1"), ConfirmReason.OUTWARD, "给张三发消息", pkg = APP), blind)
        val launch = Step.Launch("com.example.chat", link = false)
        val draft = field("f1", focused = true).copy(value = "明天见")
        val card = cards.forCall(call("phone_launch"), launch, intent, ConfirmReason.OUTWARD, screen(draft))
        assertNull(card.content)
    }

    @Test
    fun `a requested card comes from the model's arguments and names no app`() {
        val step = Step.Confirmation("转账给张三", "张三", "房租 6222021234567890128", Money(1000, null))
        val card =
            cards.requested(
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
            cards.requested(
                call("request_confirmation"),
                Step.Confirmation("通知", " ", "", null),
                ActionIntent(IntentKind.OUTWARD),
            )
        assertEquals(ConfirmCard(ToolCallId("c1"), ConfirmReason.REQUESTED_BY_MODEL, "通知"), sparse)
        assertEquals(
            ConfirmCard(ToolCallId("c1"), ConfirmReason.REQUESTED_BY_MODEL, "通知"),
            cards.requested(
                call("request_confirmation"),
                Step.Confirmation("通知", null, null, null),
                ActionIntent(IntentKind.OUTWARD),
            ),
        )
    }
}
