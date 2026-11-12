package dev.phoneagent.core.replay

import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
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

/**
 * A session written as a script on the JVM (WP08 design §10): the replies of a [FakeChatModel],
 * the screens of a [ScriptedScreen] and what the user does. Record mode runs it through the
 * product loop and writes the fixture named [name]; nothing here is device or account data.
 */
@Suppress("LongParameterList") // Callers name every argument; the last three have defaults.
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
) : RecordingSource {
    override val recording: Recording get() = Recording.AUTHORED
    override val secrets: List<String> get() = emptyList()

    override fun model(): ChatModel = replies(FakeChatModel(info))

    override fun screen(): ScreenPort = screens()
}

/** The authored fixtures under `core/src/test/resources/sessions/` (engineering-baseline §3.2), one per protocol. */
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

    /** Every authored scenario, each recorded into the fixture directory of its name. */
    val ALL: List<AuthoredScenario> = listOf(OBSERVE_TAP, CONFIRM_SEND_APPROVED, REFUSED_FINANCE_APP)
}
