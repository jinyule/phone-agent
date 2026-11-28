package dev.phoneagent.core.tools

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.AppEntry
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.screen.ScreenDiffer
import dev.phoneagent.core.screen.ScreenFormatter
import dev.phoneagent.core.screen.TreeQualityAssessor
import dev.phoneagent.core.testing.FakeCatalog
import dev.phoneagent.core.testing.FakeContext
import dev.phoneagent.core.testing.FakeInteraction
import dev.phoneagent.core.testing.FakeScreen
import dev.phoneagent.core.testing.Golden
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.Screens.snapshot
import dev.phoneagent.core.testing.Screens.under
import dev.phoneagent.core.testing.call
import dev.phoneagent.core.testing.text
import dev.phoneagent.core.tool.ToolContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The prompt surface (engineering-baseline §3.1 L3): system prompt, tool schemas, typical screens
 * and typical tool results, byte for byte against `src/test/resources/prompt-surface/`.
 * A failure means model-visible text changed: review the diff (see [Golden]).
 */
class PromptSurfaceTest {
    private val formatter = ScreenFormatter(lexicon = Screens.LEXICON)
    private val tools = PhaseOneTools.create(formatter).associateBy { it.def.name }

    @Test
    fun `golden screens carry the quality the assessor gives their tree`() {
        val assessor = TreeQualityAssessor()
        val samples =
            listOf(
                Screens.CHAT,
                Samples.chatWith("s13", draft = "十点半"),
                Samples.chatInfo,
                Samples.longList,
                Samples.login,
                Samples.numberForms,
                Samples.sparse,
            )
        for (sample in samples) {
            assertEquals(assessor.assess(sample.elements), sample.quality, "${sample.id}")
        }
        assertEquals(TreeQuality.SPARSE, Samples.sparse.quality)
    }

    @Test
    fun `system prompt`() {
        val text = SystemPrompt.BLOCKS.joinToString("\n\n") { "<!-- block: ${it.id} -->\n${it.text}" }
        Golden.assertMatches("system-prompt.expected.md", text)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `tool schemas`() {
        val json =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }
        val defs = PhaseOneTools.create(formatter).map { it.def }
        Golden.assertMatches("tool-schemas.expected.json", json.encodeToString(serializer<List<ToolDef>>(), defs))
    }

    @Test
    fun `screens - chat page`() {
        Golden.assertMatches("screens/chat-page.expected.txt", formatter.page(Screens.CHAT, SnapshotDetail.BRIEF))
    }

    @Test
    fun `screens - long list folded with the bottom bar kept`() {
        Golden.assertMatches(
            "screens/long-list-folded.expected.txt",
            formatter.page(Samples.longList, SnapshotDetail.BRIEF),
        )
    }

    @Test
    fun `screens - masked numbers and a secret field`() {
        Golden.assertMatches(
            "screens/masked-and-secret.expected.txt",
            formatter.page(Samples.login, SnapshotDetail.BRIEF),
        )
    }

    @Test
    fun `screens - number forms masked at the output boundary`() {
        Golden.assertMatches(
            "screens/masked-number-forms.expected.txt",
            formatter.page(Samples.numberForms, SnapshotDetail.FULL),
        )
    }

    @Test
    fun `screens - numbers joined across fields are masked without a kind`() {
        Golden.assertMatches(
            "screens/masked-joined-numbers.expected.txt",
            formatter.page(Samples.joinedNumbers, SnapshotDetail.FULL),
        )
    }

    @Test
    fun `screens - sparse tree with OCR`() {
        Golden.assertMatches(
            "screens/sparse-with-ocr.expected.txt",
            formatter.page(Samples.sparse, SnapshotDetail.FULL),
        )
    }

    @Test
    fun `screens - poor trees for a model that cannot read images`() {
        val blind = ScreenFormatter(lexicon = Screens.LEXICON, vision = false)
        val empty = Samples.sparse.copy(elements = emptyList(), quality = TreeQuality.EMPTY)
        Golden.assertMatches(
            "screens/poor-trees-without-vision.expected.txt",
            blind.page(
                Samples.sparse,
                SnapshotDetail.BRIEF,
            ) + "\n\n=====\n\n" + blind.page(empty, SnapshotDetail.BRIEF),
        )
    }

    @Test
    fun `results - type shows only the diff`() =
        runTest {
            val after = Samples.chatWith(id = "s13", draft = "十点半")
            val result = act("phone_type", """{"screen":"s12","ref":"@e9","text":"十点半"}""", Screens.CHAT, after)
            Golden.assertMatches("results/type-diff.expected.txt", result)
        }

    @Test
    fun `results - tap send adds a message`() =
        runTest {
            val after = Samples.chatWith(id = "s14", sent = "我：十点半")
            val result =
                act("phone_tap", """{"screen":"s13","ref":"e10"}""", Samples.chatWith("s13", draft = "十点半"), after)
            Golden.assertMatches("results/tap-diff.expected.txt", result)
        }

    @Test
    fun `results - a diff lists OCR changes and refs that went away`() =
        runTest {
            // The picture's OCR text changed, its caption moved down, and the send button e10 is
            // gone: its ref now names a plain container that is not printed, so e10 must be dropped.
            val picture = OcrLine("二维码加载中", box(300, 400, 700, 440), 0.9)
            val caption = OcrLine("长按识别二维码", box(300, 450, 700, 490), 0.9)
            val before = Screens.CHAT.copy(id = SnapshotId("s15"), ocr = listOf(picture, caption))
            val overlay = el("e10", ElementRole.CONTAINER, null, box(880, 930, 980, 980))
            val after =
                before.copy(
                    id = SnapshotId("s16"),
                    elements = Screens.CHAT_ELEMENTS.dropLast(1) + overlay,
                    ocr = listOf(picture.copy(text = "二维码已过期，点击刷新"), caption.copy(bounds = box(300, 500, 700, 540))),
                )
            val result = act("phone_tap", """{"screen":"s15","x":500,"y":420}""", before, after)
            Golden.assertMatches("results/tap-diff-ocr-and-gone.expected.txt", result)
        }

    @Test
    fun `results - a screen read without OCR leaves the earlier OCR lines unverified`() =
        runTest {
            val picture = OcrLine("二维码已过期，点击刷新", box(300, 400, 700, 440), 0.9)
            val before = Screens.CHAT.copy(id = SnapshotId("s15"), ocr = listOf(picture))
            val typed = Screens.CHAT_ELEMENTS.map { if (it.ref.value == "e9") it.copy(value = "好的") else it }
            val after = before.copy(id = SnapshotId("s16"), elements = typed, ocr = null)
            val result = act("phone_type", """{"screen":"s15","ref":"e9","text":"好的"}""", before, after)
            Golden.assertMatches("results/type-diff-ocr-unverified.expected.txt", result)
        }

    @Test
    fun `results - no change from the port, but changes since the model's read`() =
        runTest {
            // The port saw no change from right before to right after the tap, yet the diff to the
            // screen the model read is not empty: only a parent changed, only a ref changed, or a
            // message came in before the tap.
            val reparented = Screens.CHAT_ELEMENTS.map { if (it.parent?.value == "e5") it.copy(parent = null) else it }
            val renamed =
                Screens.CHAT_ELEMENTS.map {
                    if (it.ref.value ==
                        "e9"
                    ) {
                        it.copy(ref = ElementRef("e12"))
                    } else {
                        it
                    }
                }
            val message = el("e11", ElementRole.TEXT, "张三：在吗", box(40, 400, 700, 460)).copy(parent = ElementRef("e5"))
            val text =
                listOf(reparented, renamed, Screens.CHAT_ELEMENTS + message)
                    .map { elements ->
                        val after = Screens.CHAT.copy(id = SnapshotId("s13"), elements = elements)
                        val diff = ScreenDiffer().diff(Screens.CHAT, after)
                        val outcome =
                            ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.NO_CHANGE, after, diff)
                        run("phone_tap", """{"screen":"s12","ref":"e7"}""", outcome)
                    }.joinToString("\n\n=====\n\n")
            Golden.assertMatches("results/tap-diff-without-port-change.expected.txt", text)
        }

    @Test
    fun `results - tap opens a new page in full`() =
        runTest {
            val result = act("phone_tap", """{"screen":"s12","ref":"e3"}""", Screens.CHAT, Samples.chatInfo)
            Golden.assertMatches("results/tap-big-change.expected.txt", result)
        }

    @Test
    fun `results - tap without visible change`() =
        runTest {
            val after = Samples.chatWith(id = "s13")
            val outcome =
                ActionOutcome(
                    ActionOutcome.Status.Ok,
                    ActionOutcome.Effect.NO_CHANGE,
                    after,
                    ScreenDiffer().diff(Screens.CHAT, after),
                )
            Golden.assertMatches(
                "results/tap-no-change.expected.txt",
                run("phone_tap", """{"screen":"s12","x":500,"y":230}""", outcome),
            )
        }

    @Test
    fun `results - stale ref, refused, failed`() =
        runTest {
            val stale =
                ActionOutcome(
                    ActionOutcome.Status.StaleRef,
                    ActionOutcome.Effect.UNKNOWN,
                    Samples.chatWith("s13", sent = "张三：在吗"),
                )
            val refused =
                ActionOutcome(
                    ActionOutcome.Status.Refused("the target is PhoneAgent's own window"),
                    ActionOutcome.Effect.UNKNOWN,
                )
            val failed =
                ActionOutcome(ActionOutcome.Status.Failed("element is not clickable"), ActionOutcome.Effect.UNKNOWN)
            val tap = """{"screen":"s12","ref":"e7"}"""
            val text =
                listOf(stale, refused, failed)
                    .map { outcome ->
                        run("phone_tap", tap, outcome)
                    }.joinToString("\n\n=====\n\n")
            Golden.assertMatches("results/tap-stale-refused-failed.expected.txt", text)
        }

    @Test
    fun `results - device text with spread-out numbers is masked`() =
        runTest {
            // Reasons and messages come from the device as they are: line breaks, U+0085, space runs.
            val failed =
                ActionOutcome(
                    ActionOutcome.Status.Failed("卡号 6222\r\n    0212\r\n    3456\r\n    7894 不可用"),
                    ActionOutcome.Effect.UNKNOWN,
                )
            val refused =
                ActionOutcome(
                    ActionOutcome.Status.Refused("身份证 110105\u008519491231\u0085002X 不能填"),
                    ActionOutcome.Effect.UNKNOWN,
                )
            val lost =
                FakeScreen().apply {
                    unavailable =
                        ScreenUnavailableException("lost card 6222   0212   3456   7894", null)
                }
            val tap = """{"screen":"s12","ref":"e7"}"""
            val text =
                listOf(
                    run("phone_tap", tap, failed),
                    run("phone_tap", tap, refused),
                    tools.getValue("phone_tap").execute(call("phone_tap", tap), FakeContext(lost)).text(),
                ).joinToString("\n\n=====\n\n")
            Golden.assertMatches("results/masked-device-text.expected.txt", text)
        }

    @Test
    fun `results - a refusal shows the screen read after it`() =
        runTest {
            // A message arrived while the phone refused the tap: the model must see that screen.
            val after = Samples.chatWith("s13", sent = "张三：在吗")
            val refused =
                ActionOutcome(
                    ActionOutcome.Status.Refused("the target is PhoneAgent's own window"),
                    ActionOutcome.Effect.UNKNOWN,
                    after,
                    ScreenDiffer().diff(Screens.CHAT, after),
                )
            Golden.assertMatches(
                "results/tap-refused-with-screen.expected.txt",
                run("phone_tap", """{"screen":"s12","ref":"e7"}""", refused),
            )
        }

    @Test
    fun `results - invalid arguments and no screen`() =
        runTest {
            val invalid =
                tools
                    .getValue(
                        "phone_tap",
                    ).execute(call("phone_tap", """{"ref":"@x","x":1200,"speed":3}"""), FakeContext())
            val noScreen =
                tools
                    .getValue(
                        "phone_tap",
                    ).execute(call("phone_tap", """{"screen":"s12","ref":"e1"}"""), FakeContext(screen = null))
            Golden.assertMatches(
                "results/invalid-and-no-screen.expected.txt",
                invalid.text() + "\n\n=====\n\n" + noScreen.text(),
            )
        }

    @Test
    fun `results - observe attaches a frame for a sparse tree, never on a secure page`() =
        runTest {
            val sparse =
                tools
                    .getValue(
                        "phone_observe",
                    ).execute(call("phone_observe"), FakeContext(FakeScreen(Samples.sparse)))
            val secure = Samples.sparse.copy(id = SnapshotId("s31"), flags = setOf(ScreenFlag.SECURE))
            val refused =
                tools
                    .getValue(
                        "phone_observe",
                    ).execute(call("phone_observe", """{"image":true}"""), FakeContext(FakeScreen(secure)))
            val unboxed =
                tools
                    .getValue(
                        "phone_observe",
                    ).execute(
                        call("phone_observe"),
                        FakeContext(
                            FakeScreen(Samples.sparse).apply {
                                frame = Frame(byteArrayOf(1, 2, 3), "image/jpeg", 720, 1280, som = false)
                            },
                        ),
                    )
            Golden.assertMatches(
                "results/observe-images.expected.txt",
                sparse.text() + "\n\n=====\n\n" + refused.text() + "\n\n=====\n\n" + unboxed.text(),
            )
        }

    @Test
    fun `results - wait that times out`() =
        runTest {
            val screen =
                FakeScreen(Screens.CHAT).apply {
                    onAwaitChange = {
                        delay(it)
                        false
                    }
                }
            val result =
                tools
                    .getValue(
                        "phone_wait",
                    ).execute(
                        call("phone_wait", """{"condition":"appear","text":"已送达","timeoutMs":3000}"""),
                        FakeContext(screen),
                    )
            Golden.assertMatches("results/wait-timeout.expected.txt", result.text())
        }

    @Test
    fun `results - apps and launching by package`() =
        runTest {
            val catalog = FakeCatalog(Samples.apps)
            val ctx = FakeContext(apps = catalog)
            val list = tools.getValue("phone_apps").execute(call("phone_apps", """{"query":"微"}"""), ctx)
            val byName = tools.getValue("phone_launch").execute(call("phone_launch", """{"app":"微信"}"""), ctx)
            val missing =
                tools
                    .getValue(
                        "phone_launch",
                    ).execute(call("phone_launch", """{"app":"com.example.missing"}"""), ctx)
            val launched =
                tools
                    .getValue(
                        "phone_launch",
                    ).execute(call("phone_launch", """{"app":"com.tencent.mm"}"""), ctx)
            Golden.assertMatches(
                "results/apps-and-launch.expected.txt",
                listOf(
                    list,
                    byName,
                    missing,
                    launched,
                ).joinToString("\n\n=====\n\n") {
                    it.text()
                },
            )
        }

    @Test
    fun `results - user interaction and ending the turn`() =
        runTest {
            val ctx = FakeContext(interaction = FakeInteraction())
            val asked =
                tools
                    .getValue(
                        "ask_user",
                    ).execute(call("ask_user", """{"question":"几点到？","options":["十点","十一点"]}"""), ctx)
            val handed =
                tools
                    .getValue(
                        "hand_to_user",
                    ).execute(call("hand_to_user", """{"kind":"login"}""", reason = "请登录微信"), ctx)
            // Moving the app failed, either to the user or back from them.
            val handFailed =
                tools
                    .getValue(
                        "hand_to_user",
                    ).execute(
                        call("hand_to_user", """{"kind":"login"}""", reason = "请登录微信"),
                        FakeContext(interaction = FakeInteraction(handoffResult = HandoffResult.FAILED)),
                    )
            val confirmed =
                tools.getValue("request_confirmation").execute(
                    call("request_confirmation", """{"action":"发送消息","target":"张三","content":"十点半到"}"""),
                    ctx,
                )
            val ended =
                tools
                    .getValue(
                        "end_not_done",
                    ).execute(call("end_not_done", """{"cause":"blocked","summary":"需要登录"}"""), ctx)
            Golden.assertMatches(
                "results/interaction-and-end.expected.txt",
                listOf(asked, handed, handFailed, confirmed, ended).joinToString("\n\n=====\n\n") { it.text() },
            )
        }

    private suspend fun act(
        tool: String,
        args: String,
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): String =
        run(
            tool,
            args,
            ActionOutcome(
                ActionOutcome.Status.Ok,
                ActionOutcome.Effect.CHANGED,
                after,
                ScreenDiffer().diff(before, after),
            ),
        )

    private suspend fun run(
        tool: String,
        args: String,
        outcome: ActionOutcome,
    ): String {
        val screen = FakeScreen().apply { onAct = { _, _ -> outcome } }
        val ctx: ToolContext = FakeContext(screen)
        return tools.getValue(tool).execute(call(tool, args), ctx).text()
    }
}

/** Sample screens for the golden files (synthetic data only). */
internal object Samples {
    fun chatWith(
        id: String,
        draft: String? = null,
        sent: String? = null,
    ): ScreenSnapshot {
        val message = sent?.let { el("e11", ElementRole.TEXT, it, box(600, 400, 960, 460)).under("e5") }
        val elements =
            Screens.CHAT_ELEMENTS.flatMap { element ->
                when {
                    element.ref.value == "e9" && draft != null -> listOf(element.copy(value = draft))
                    element.ref.value == "e7" && message != null -> listOf(element, message)
                    else -> listOf(element)
                }
            }
        return Screens.CHAT.copy(id = SnapshotId(id), elements = elements).assessed()
    }

    private val click = setOf(ElementState.CLICKABLE)

    /** This snapshot with the quality the assessor gives its tree, as a real port would set it. */
    private fun ScreenSnapshot.assessed(): ScreenSnapshot = copy(quality = TreeQualityAssessor().assess(elements))

    val chatInfo: ScreenSnapshot =
        snapshot(
            "s13",
            listOf(
                el("e1", ElementRole.BUTTON, "返回", box(0, 30, 80, 90), click),
                el("e20", ElementRole.TEXT, "聊天信息", box(380, 40, 620, 80)),
                el("e21", ElementRole.CONTAINER, null, box(0, 120, 999, 260), click),
                el("e22", ElementRole.IMAGE, "张三", box(40, 140, 160, 240)).under("e21"),
                el("e23", ElementRole.SWITCH, "消息免打扰", box(0, 300, 999, 380), click),
                el("e24", ElementRole.SWITCH, "置顶聊天", box(0, 380, 999, 460), click + ElementState.CHECKED),
                el("e25", ElementRole.BUTTON, "清空聊天记录", box(0, 600, 999, 680), click),
            ),
        ).copy(activity = "com.tencent.mm.ui.SingleChatInfoUI").assessed()

    val longList: ScreenSnapshot =
        snapshot(
            "s20",
            buildList {
                add(el("e1", ElementRole.TEXT, "微信(3)", box(400, 40, 600, 80)))
                add(el("e2", ElementRole.BUTTON, "搜索", box(800, 30, 880, 90), click))
                add(el("e3", ElementRole.LIST, null, box(0, 100, 999, 880), setOf(ElementState.SCROLLABLE)))
                var next = 4
                for (i in 1..60) {
                    val top = 100 + (i - 1) * 13
                    val row = "e${next++}"
                    val time = "${8 + i / 30}:${(i * 2 % 60).toString().padStart(2, '0')}"
                    add(
                        el(
                            row,
                            ElementRole.CONTAINER,
                            null,
                            box(0, top, 999, top + 12),
                            click + ElementState.LONG_CLICKABLE,
                        ).under("e3"),
                    )
                    add(el("e${next++}", ElementRole.TEXT, "联系人$i", box(150, top, 500, top + 6)).under(row))
                    add(
                        el(
                            "e${next++}",
                            ElementRole.TEXT,
                            "最近一条消息 $i：周末一起吃饭吗",
                            box(150, top + 6, 800, top + 12),
                        ).under(row),
                    )
                    add(el("e${next++}", ElementRole.TEXT, time, box(850, top, 980, top + 6)).under(row))
                }
                for ((i, tab) in listOf("微信", "通讯录", "发现", "我").withIndex()) {
                    val states = if (i == 0) click + ElementState.SELECTED else click
                    add(el("e${next++}", ElementRole.TAB, tab, box(i * 250, 900, i * 250 + 249, 999), states))
                }
            },
        ).copy(activity = "com.tencent.mm.ui.LauncherUI").assessed()

    val login: ScreenSnapshot =
        snapshot(
            "s30",
            listOf(
                el("e1", ElementRole.TEXT, "绑定银行卡", box(350, 40, 650, 80)),
                el("e2", ElementRole.INPUT, "手机号", box(40, 200, 960, 260), click + ElementState.EDITABLE)
                    .copy(value = "13800138000"),
                el("e3", ElementRole.INPUT, "支付密码", box(40, 280, 960, 340), click + ElementState.EDITABLE)
                    .copy(secret = true),
                el("e4", ElementRole.TEXT, "卡号 6222 0212 3456 7894，持卡人身份证 11010519491231002X", box(40, 380, 960, 440)),
                el("e5", ElementRole.TEXT, "订单号 6222021234567894 已提交", box(40, 460, 960, 500)),
                el("e6", ElementRole.CHECKBOX, "同意《用户协议》", box(40, 540, 600, 580), click),
                el("e7", ElementRole.BUTTON, "支付", box(40, 880, 960, 960), click + ElementState.DISABLED),
            ),
        ).copy(
            pkg = "com.example.wallet",
            activity = "com.example.wallet.BindCardActivity",
            flags = setOf(ScreenFlag.PAYMENT, ScreenFlag.KEYBOARD),
        ).assessed()

    /**
     * Card and id numbers in the forms apps print them (Unicode spaces, U+0085, zero-width marks,
     * minus signs); only the two order numbers stay readable.
     */
    val numberForms: ScreenSnapshot =
        snapshot(
            "s31",
            listOf(
                el("e1", ElementRole.TEXT, "卡号\u00A06222\u00A00212\u00A03456\u00A07894", box(40, 200, 960, 240)),
                el("e2", ElementRole.TEXT, "储蓄卡 6222\u20090212\u20093456\u20097894", box(40, 250, 960, 290)),
                el("e3", ElementRole.TEXT, "信用卡 4111\u202F1111\u202F1111\u202F1111", box(40, 300, 960, 340)),
                el("e4", ElementRole.TEXT, "卡号　６２２２　０２１２　３４５６　７８９４", box(40, 350, 960, 390)),
                el("e5", ElementRole.TEXT, "身份证 110105 19491231 002X", box(40, 400, 960, 440)),
                el("e6", ElementRole.TEXT, "证件号 1101 0519 4912 3100 2X", box(40, 450, 960, 490)),
                el("e7", ElementRole.TEXT, "订单支付 银行卡 6222021234567894", box(40, 500, 960, 540)),
                el("e8", ElementRole.TEXT, "银行卡编号 6222 0212 3456 7894", box(40, 550, 960, 590)),
                el("e9", ElementRole.TEXT, "订单号：6222021234567894", box(40, 600, 960, 640)),
                el("e10", ElementRole.TEXT, "运单号 6222 0212 3456 7894 已签收", box(40, 650, 960, 690)),
                el("e11", ElementRole.TEXT, "电话 138 0013 8000 · 金额 ¥6,222.02", box(40, 700, 960, 740)),
                el("e12", ElementRole.TEXT, "卡号 6222\u00850212\u00853456\u00857894", box(40, 750, 960, 790)),
                el("e13", ElementRole.TEXT, "卡号 6222\u200B0212\u200B3456\u200B7894", box(40, 800, 960, 840)),
                el("e14", ElementRole.TEXT, "身份证 110105\u221219491231\u2212002X", box(40, 850, 960, 890)),
                el("e15", ElementRole.TEXT, "信用卡 4111\uFE631111\uFE631111\uFE631111", box(40, 900, 960, 940)),
            ),
        ).copy(pkg = "com.example.wallet", activity = "com.example.wallet.CardsActivity").assessed()

    /** Numbers only whitespace separates: two phones on two lines, two cards, a neighbour; with controls. */
    val joinedNumbers: ScreenSnapshot =
        snapshot(
            "s32",
            listOf(
                el("e1", ElementRole.TEXT, "联系人 张三\n138 0013 8000\n130 0222 0546", box(40, 200, 960, 280)),
                el("e2", ElementRole.TEXT, "电话 138 0013 8000\n电话 130 0222 0546", box(40, 300, 960, 380)),
                el("e3", ElementRole.TEXT, "8686 7755 3960 0040\n3064 3457 1231 4471", box(40, 400, 960, 480)),
                el("e4", ElementRole.TEXT, "第 105 6222 0212 3456 7894", box(40, 500, 960, 540)),
                el("e5", ElementRole.TEXT, "卡号 6222 0212 3456 7894", box(40, 550, 960, 590)),
                el("e6", ElementRole.TEXT, "身份证 110105 19491231 002X", box(40, 600, 960, 640)),
            ),
        ).copy(pkg = "com.example.contacts", activity = "com.example.contacts.DetailActivity").assessed()

    val sparse: ScreenSnapshot =
        snapshot(
            "s40",
            listOf(
                el("e1", ElementRole.BUTTON, "关闭", box(900, 30, 980, 90), click),
                el("e2", ElementRole.OTHER, null, box(0, 100, 999, 900)),
                el("e3", ElementRole.BUTTON, null, box(400, 920, 600, 980), click),
            ),
        ).copy(
            pkg = "com.example.game",
            activity = "com.example.game.GameActivity",
            ocr = listOf(OcrLine("开始游戏", box(400, 500, 600, 560), 0.93), OcrLine("排行榜", box(400, 620, 600, 680), 0.9)),
        ).assessed()

    val apps: List<AppEntry> =
        listOf(
            AppEntry("com.tencent.mm", "微信", launchable = true),
            AppEntry("com.tencent.weread", "微信读书", launchable = true),
            AppEntry("com.sina.weibo", "微博", launchable = true),
            AppEntry("com.autonavi.minimap", "高德地图", launchable = true),
            AppEntry("com.android.providers.media", "媒体存储", launchable = false),
        )
}
