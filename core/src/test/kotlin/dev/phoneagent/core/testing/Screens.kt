package dev.phoneagent.core.testing

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.policy.ElementRisk
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.RiskLexicon
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement

/** Builders for screen snapshots in tests. */
object Screens {
    val AGENT = SurfaceRef(7, SurfaceKind.AGENT)

    fun box(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = Bounds(left, top, right, bottom)

    /** An element in window 1; set other fields with `copy`, e.g. `.under("e5")`. */
    fun el(
        ref: String,
        role: ElementRole,
        name: String? = null,
        bounds: Bounds = box(0, 400, 999, 450),
        states: Set<ElementState> = emptySet(),
    ) = UiElement(ref = ElementRef(ref), role = role, name = name, bounds = bounds, states = states, window = 1)

    /** This element with [parent] as its parent. */
    fun UiElement.under(parent: String) = copy(parent = ElementRef(parent))

    /** A good-quality snapshot of WeChat's chat page on the agent screen; set other fields with `copy`. */
    fun snapshot(
        id: String,
        elements: List<UiElement>,
    ) = ScreenSnapshot(
        id = SnapshotId(id),
        surface = AGENT,
        pkg = "com.tencent.mm",
        activity = "com.tencent.mm.ui.chatting.ChattingUI",
        elements = elements,
        ocr = null,
        quality = TreeQuality.GOOD,
        flags = emptySet(),
        fingerprint = "fp-$id",
    )

    /** Marks elements named 发送 / 支付 as risky, like the policy lexicon would. */
    val LEXICON =
        RiskLexicon { element, _ ->
            when (element.name) {
                "发送" -> ElementRisk(IntentKind.OUTWARD, "发送")
                "支付" -> ElementRisk(IntentKind.SPEND, "支付")
                else -> null
            }
        }

    /** The chat page of phase1 §4. */
    val CHAT_ELEMENTS =
        listOf(
            el("e1", ElementRole.BUTTON, "返回", box(0, 30, 80, 90), setOf(ElementState.CLICKABLE)),
            el("e2", ElementRole.TEXT, "张三", box(400, 40, 600, 80)),
            el(
                "e3",
                ElementRole.BUTTON,
                null,
                box(900, 30, 980, 90),
                setOf(ElementState.CLICKABLE),
            ).copy(resourceId = "more"),
            el("e4", ElementRole.IMAGE, "更多", box(910, 40, 970, 80)).under("e3"),
            el("e5", ElementRole.LIST, "聊天记录", box(0, 100, 999, 900), setOf(ElementState.SCROLLABLE)),
            el("e6", ElementRole.TEXT, "张三：明天几点到？", box(40, 200, 700, 260)).under("e5"),
            el("e7", ElementRole.TEXT, "我：十点", box(600, 300, 960, 360)).under("e5"),
            el(
                "e9",
                ElementRole.INPUT,
                bounds = box(100, 930, 860, 980),
                states = setOf(ElementState.EDITABLE, ElementState.FOCUSED, ElementState.CLICKABLE),
            ).copy(hint = "发送消息"),
            el("e10", ElementRole.BUTTON, "发送", box(880, 930, 980, 980), setOf(ElementState.CLICKABLE)),
        )

    val CHAT = snapshot("s12", CHAT_ELEMENTS).copy(flags = setOf(ScreenFlag.KEYBOARD))
}
