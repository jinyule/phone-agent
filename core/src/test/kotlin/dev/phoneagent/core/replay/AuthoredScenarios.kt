package dev.phoneagent.core.replay

import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.anthropic.AnthropicSettings
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.AppEntry
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.Screens.under
import dev.phoneagent.core.testing.ScriptedScreen
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A session written as a script on the JVM (WP08 design §10): the replies of a [FakeChatModel],
 * the screens of a [ScriptedScreen] and what the user does. Record mode runs it through the
 * product loop and writes the fixture named [name]; nothing here is device or account data.
 */
@Suppress("LongParameterList") // Callers name every argument; the last four have defaults.
class AuthoredScenario(
    override val name: String,
    override val description: String,
    override val info: ModelInfo,
    override val stimuli: List<Stimulus>,
    private val replies: (FakeChatModel) -> FakeChatModel,
    private val screens: () -> ScreenPort,
    override val task: ScenarioTask = ScenarioTask(SecurityLevel.STANDARD, unattended = false),
    override val assembly: Assembly = Assembly(),
    override val device: Device = Device(ScriptedScreen.SURFACE, widthPx = 1080, heightPx = 2400, densityDpi = 440),
    /** The context edits the model's `prepare` gives (the provider's server-side context management). */
    private val contextEdits: ContextEditPolicy = ContextEditPolicy.ClientSide,
) : RecordingSource {
    override val recording: Recording get() = Recording.AUTHORED
    override val secrets: List<String> get() = emptyList()

    override fun model(): ChatModel = replies(FakeChatModel(info, contextEdits = contextEdits))

    override fun screen(): ScreenPort = screens()
}

/**
 * The authored fixtures under `core/src/test/resources/sessions/` (engineering-baseline §3.2): each
 * protocol at least once, and each scenario's KDoc names the paths it guards.
 */
object AuthoredScenarios {
    private fun model(
        provider: String,
        model: String,
        protocol: ModelProtocol,
    ) = ModelInfo(
        ProviderId(provider),
        ModelId(model),
        protocol,
        ModelCapabilities(tools = true, vision = false, contextTokens = 128_000),
    )

    private fun followup(text: String) = Stimulus.Input(InboxEntry(InboxEntryKind.FOLLOWUP, listOf(Part.Text(text))))

    /** A chat's row in the list [chats] shows. */
    private fun row(
        ref: String,
        name: String,
        bounds: Bounds,
    ): UiElement = el(ref, ElementRole.CONTAINER, name, bounds, setOf(ElementState.CLICKABLE)).under("e2")

    /** WeChat's list of chats: 张三 and 李四. */
    private val chats: ScreenSnapshot =
        Screens
            .snapshot(
                "s7",
                listOf(
                    el("e1", ElementRole.TEXT, "微信", box(400, 40, 600, 80)),
                    el("e2", ElementRole.LIST, "会话列表", box(0, 100, 999, 900), setOf(ElementState.SCROLLABLE)),
                    row("e3", "张三", box(0, 120, 999, 220)),
                    row("e4", "李四", box(0, 230, 999, 330)),
                ),
            ).copy(activity = "com.tencent.mm.ui.LauncherUI")

    /** The chat with 张三 ([Screens.CHAT], s12) with [draft] in the input field, as [id]. */
    private fun drafted(
        id: String,
        draft: String,
    ): ScreenSnapshot =
        Screens.CHAT.copy(
            id = SnapshotId(id),
            elements = Screens.CHAT.elements.map { if (it.ref.value == "e9") it.copy(value = draft) else it },
        )

    /** The chat after 十点半 was sent: one more line in the history, the field empty again. */
    private val sent: ScreenSnapshot =
        Screens.CHAT.copy(
            id = SnapshotId("s15"),
            elements =
                Screens.CHAT.elements +
                    el("e11", ElementRole.TEXT, "我：十点半", box(600, 370, 960, 430)).under("e5"),
        )

    /**
     * Reads the list of chats and opens 张三's: the ref comes from the screen the model saw
     * (`{{fromRequest:…}}`), so a renumbering of refs re-resolves it. Two verdicts, both Allow.
     */
    val OBSERVE_TAP: AuthoredScenario =
        AuthoredScenario(
            name = "observe-tap",
            description = "Observe the list of chats, tap 张三's row (its ref read from the screen), then answer.",
            info = model("test-anthropic", "claude-test", ModelProtocol.ANTHROPIC_MESSAGES),
            stimuli = listOf(followup("打开和张三的聊天")),
            replies = { model ->
                model
                    .calls(call("c1", "phone_observe", """{"reason":"看看现在的屏幕"}"""))
                    .calls(
                        call(
                            "c2",
                            "phone_tap",
                            """{"screen":"s7","ref":"{{fromRequest:@(e\\d+)[^\\n]*\"张三\"}}","reason":"打开张三的聊天"}""",
                        ),
                    ).answers("已经打开了和张三的聊天。")
            },
            screens = { ScriptedScreen(chats, Screens.CHAT) },
        )

    /**
     * Types a reply and sends it: the send asks first (OUTWARD, Confirm), the user approves, the
     * policy checks the re-read screen S′ (Allow) and the tap runs. Four verdicts.
     */
    val CONFIRM_SEND_APPROVED: AuthoredScenario =
        AuthoredScenario(
            name = "confirm-send-approved",
            description = "Reply to 张三 and send it: the send asks, the user approves, the re-read screen allows it.",
            info = model("test-openai", "gpt-test", ModelProtocol.OPENAI_RESPONSES),
            stimuli = listOf(followup("回复张三：十点半"), Stimulus.Answer(1, UserReply.Approve)),
            replies = { model ->
                model
                    .calls(call("c1", "phone_observe", """{"reason":"看看聊天页"}"""))
                    .calls(call("c2", "phone_type", """{"screen":"s12","ref":"e9","text":"十点半","reason":"输入回复"}"""))
                    .calls(call("c3", "phone_tap", """{"screen":"s13","ref":"e10","reason":"发送回复"}"""))
                    .answers("已经回复张三：十点半。")
            },
            screens = { ScriptedScreen(Screens.CHAT, drafted("s13", "十点半"), drafted("s14", "十点半"), sent) },
        )

    /**
     * Asks to open a bank app: the policy refuses the launch (`hard.finance_app`) before the device
     * is touched, and the model ends the task as blocked. The replayed device has no port call at
     * all, so any would fail.
     */
    val REFUSED_FINANCE_APP: AuthoredScenario =
        AuthoredScenario(
            name = "refused-finance-app",
            description = "Launching a bank app is refused before the device moves; the model ends the task blocked.",
            info = model("test-chat", "chat-test", ModelProtocol.OPENAI_CHAT_COMPLETIONS),
            stimuli = listOf(followup("打开工商银行查一下余额")),
            replies = { model ->
                model
                    .calls(call("c1", "phone_launch", """{"app":"com.icbc","reason":"打开工商银行"}"""))
                    .calls(
                        call(
                            "c2",
                            "end_not_done",
                            """{"cause":"blocked","summary":"银行 App 只能由你自己操作。","reason":"安全规则不允许操作银行 App"}""",
                        ),
                    )
            },
            screens = { ScriptedScreen() },
            device =
                Device(
                    ScriptedScreen.SURFACE,
                    widthPx = 1080,
                    heightPx = 2400,
                    densityDpi = 440,
                    apps =
                        listOf(
                            AppEntry("com.icbc", "中国工商银行", launchable = true),
                            AppEntry("com.tencent.mm", "微信", launchable = true),
                        ),
                ),
        )

    /** A block of the model's reasoning of [kind] with [text] (all test data). */
    private fun block(
        kind: ReasoningKind,
        type: String,
        text: String,
        summary: String? = null,
    ) = Part.Reasoning(
        ProviderId("test-anthropic"),
        ModelId("claude-test"),
        kind,
        buildJsonObject {
            put("type", JsonPrimitive(type))
            put(if (kind == ReasoningKind.ANTHROPIC_COMPACTION) "content" else "thinking", JsonPrimitive(text))
            if (kind == ReasoningKind.ANTHROPIC_THINKING) put("signature", JsonPrimitive("test-signature-of-$text"))
        },
        summary,
    )

    private fun thinking(text: String) = block(ReasoningKind.ANTHROPIC_THINKING, "thinking", text, summary = text)

    /**
     * The Anthropic path (AGENTS §3.7, model-layer §5): the main requests ask for an output cap of
     * 4096 and reasoning HIGH, and `prepare` gives the server-side context management the Claude
     * API runs with by default (compaction with the product's instructions). Guards:
     * - thinking blocks sent back with their replies, and a compaction block kept in the history;
     * - a reply cut by a network failure mid-stream and retried in the same step: the prefix is
     *   UI-only and stays out of the retry (review D2);
     * - a stale thinking signature (PrefixBindingMismatch): the default recovery records
     *   `context/reasoning-dropped` up to the reply that holds it, and the retry leaves out that
     *   reply's thinking block and nothing else (D4);
     * - the output cap and the level in every request (R1, R3).
     */
    val ANTHROPIC_THINKING: AuthoredScenario =
        AuthoredScenario(
            name = "anthropic-thinking",
            description =
                "Claude path with thinking, server-side compaction, a cap of 4096 and level HIGH: a reply cut " +
                    "mid-stream is retried, a stale signature drops the thinking up to it, a compaction block stays.",
            info =
                ModelInfo(
                    ProviderId("test-anthropic"),
                    ModelId("claude-test"),
                    ModelProtocol.ANTHROPIC_MESSAGES,
                    ModelCapabilities(
                        tools = true,
                        vision = false,
                        contextTokens = 200_000,
                        reasoningLevels = listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
                    ),
                ),
            stimuli = listOf(followup("看看微信里有谁"), followup("好的，谢谢")),
            replies = { model ->
                model
                    .replies(
                        ChatResponse(
                            listOf(thinking("先读屏"), call("c1", "phone_observe", """{"reason":"看看会话列表"}""")),
                            StopReason.TOOL_USE,
                        ),
                    ).fails(ModelError.Network("stream cut"), delivered = "列表里有")
                    .fails(ModelError.PrefixBindingMismatch("thinking signature no longer valid"))
                    .replies(
                        ChatResponse(
                            listOf(
                                block(ReasoningKind.ANTHROPIC_COMPACTION, "compaction", "用户想知道微信里有谁。"),
                                thinking("对照列表"),
                                Part.Text("会话列表里有张三和李四。"),
                            ),
                            StopReason.END_TURN,
                        ),
                    ).answers("不客气。")
            },
            screens = { ScriptedScreen(chats) },
            assembly = Assembly(maxOutputTokens = 4096, reasoning = ReasoningLevel.HIGH),
            contextEdits =
                ContextEditPolicy.ServerSide(
                    clearToolUsesAboveTokens = null,
                    keepToolUses = 3,
                    compactAboveTokens = AnthropicSettings.COMPACT_ABOVE_TOKENS,
                    compactInstructions = AnthropicSettings.COMPACTION_INSTRUCTIONS,
                ),
        )

    /** A shop's cart as [id]: one row and its 删除 button per item of [items] (name to price), then 立即支付 [total]. */
    private fun cart(
        id: String,
        total: String,
        vararg items: Pair<String, String>,
    ): ScreenSnapshot {
        val rows =
            items.flatMapIndexed { i, (item, price) ->
                val top = 120 + i * 110
                listOf(
                    el("e${3 + 2 * i}", ElementRole.CONTAINER, "$item $price", box(0, top, 999, top + 100))
                        .under("e2"),
                    el("e${4 + 2 * i}", ElementRole.BUTTON, "删除", box(860, top + 20, 980, top + 80), clickable)
                        .under("e${3 + 2 * i}"),
                )
            }
        return ScriptedScreen.snapshotOf(
            id,
            pkg = SHOP,
            elements =
                listOf(
                    el("e1", ElementRole.TEXT, "购物车", box(400, 40, 600, 80)),
                    el("e2", ElementRole.LIST, "商品", box(0, 100, 999, 800), setOf(ElementState.SCROLLABLE)),
                ) + rows +
                    el("e9", ElementRole.BUTTON, "立即支付 $total", box(600, 900, 980, 980), clickable),
        )
    }

    private val clickable = setOf(ElementState.CLICKABLE)
    private const val SHOP = "com.example.shop"
    private val earphones = "蓝牙耳机" to "¥39.90"
    private val cable = "数据线" to "¥19.90"

    private val shopDevice =
        Device(
            ScriptedScreen.SURFACE,
            widthPx = 1080,
            heightPx = 2400,
            densityDpi = 440,
            apps = listOf(AppEntry(SHOP, "示例商城", launchable = true)),
        )

    /** The model's side of both cart scenarios: read the cart, delete the cable, pay. */
    private fun cartReplies(
        model: FakeChatModel,
        paid: String,
    ): FakeChatModel =
        model
            .calls(call("c1", "phone_observe", """{"reason":"看看购物车"}"""))
            .calls(call("c2", "phone_tap", """{"screen":"s21","ref":"e6","reason":"删除数据线"}"""))
            .calls(call("c3", "phone_tap", """{"screen":"s23","ref":"e9","reason":"支付剩下的订单"}"""))
            .answers(paid)

    /**
     * 谨慎 (CAUTIOUS), the strictest level: deleting a cart item asks (DELETE) and is approved,
     * the re-read screen allows it; paying asks (SPEND, with the amount) and is rejected. Guards
     * the DELETE and SPEND rows of the policy matrix at this level.
     */
    val CART_CAUTIOUS: AuthoredScenario =
        AuthoredScenario(
            name = "cart-cautious",
            description =
                "CAUTIOUS: deleting a cart item asks and is approved; paying asks with the amount and is rejected.",
            info = model("test-openai", "gpt-test", ModelProtocol.OPENAI_RESPONSES),
            stimuli =
                listOf(
                    followup("把购物车里的数据线删掉，然后付款"),
                    Stimulus.Answer(1, UserReply.Approve),
                    Stimulus.Answer(2, UserReply.Reject),
                ),
            replies = { cartReplies(it, "数据线已经删掉了；你没有同意付款，订单还没付。") },
            screens = {
                ScriptedScreen(
                    cart("s21", "¥59.80", earphones, cable),
                    cart("s22", "¥59.80", earphones, cable),
                    cart("s23", "¥39.90", earphones),
                )
            },
            task = ScenarioTask(SecurityLevel.CAUTIOUS, unattended = false),
            device = shopDevice,
        )

    /**
     * 自主 (AUTONOMOUS), the loosest level: deleting a cart item runs without asking; paying still
     * asks (SPEND, with the amount), is approved, and the re-read screen allows it. Guards the
     * DELETE row (a rule that asked here too would fail it: review P4) and the SPEND row.
     */
    val CART_AUTONOMOUS: AuthoredScenario =
        AuthoredScenario(
            name = "cart-autonomous",
            description =
                "AUTONOMOUS: deleting a cart item does not ask; paying asks with the amount, is approved and runs.",
            info = model("test-chat", "chat-test", ModelProtocol.OPENAI_CHAT_COMPLETIONS),
            stimuli = listOf(followup("把购物车里的数据线删掉，然后付款"), Stimulus.Answer(1, UserReply.Approve)),
            replies = { cartReplies(it, "数据线已经删掉，剩下的订单已经付了 ¥39.90。") },
            screens = {
                ScriptedScreen(
                    cart("s21", "¥59.80", earphones, cable),
                    cart("s23", "¥39.90", earphones),
                    cart("s24", "¥39.90", earphones),
                    ScriptedScreen.snapshotOf(
                        "s25",
                        pkg = SHOP,
                        elements =
                            listOf(
                                el("e1", ElementRole.TEXT, "支付成功", box(400, 300, 600, 360)),
                                el("e2", ElementRole.TEXT, "实付 ¥39.90", box(380, 380, 620, 430)),
                                el("e3", ElementRole.BUTTON, "查看订单", box(100, 900, 480, 980), clickable),
                                el("e4", ElementRole.BUTTON, "完成", box(520, 900, 900, 980), clickable),
                            ),
                    ),
                )
            },
            task = ScenarioTask(SecurityLevel.AUTONOMOUS, unattended = false),
            device = shopDevice,
        )

    /**
     * A steer while the reply streams: the user's correction is given at the request's log
     * position (seq 8, the request header), so it cuts the reply in flight. Guards the cut prefix
     * joining the view before the steer message, without its reasoning (DeriveMessages), and the
     * steer reaching the next request.
     */
    val STEER_MID_REPLY: AuthoredScenario =
        AuthoredScenario(
            name = "steer-mid-reply",
            description =
                "A steer given while the first reply streams cuts it; the prefix and the steer reach the next request.",
            info = model("test-openai", "gpt-test", ModelProtocol.OPENAI_RESPONSES),
            stimuli =
                listOf(
                    followup("看看微信里有谁"),
                    Stimulus.Input(
                        InboxEntry(InboxEntryKind.STEER, listOf(Part.Text("只要告诉我有没有张三"))),
                        after = 8,
                    ),
                ),
            replies = { model ->
                model
                    .hangs(delivered = "我先看看")
                    .calls(call("c1", "phone_observe", """{"reason":"看看会话列表里有没有张三"}"""))
                    .answers("有，会话列表里有张三。")
            },
            screens = { ScriptedScreen(chats) },
        )

    /** Every authored scenario, each recorded into the fixture directory of its name. */
    val ALL: List<AuthoredScenario> =
        listOf(
            OBSERVE_TAP,
            CONFIRM_SEND_APPROVED,
            REFUSED_FINANCE_APP,
            ANTHROPIC_THINKING,
            CART_CAUTIOUS,
            CART_AUTONOMOUS,
            STEER_MID_REPLY,
        )
}
