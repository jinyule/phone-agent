package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.JudgmentPurpose
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionLog
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The privacy checklist of a recording lists, as items to tick, everything of the session that
 * could come from a real account: each kind of source in its own test.
 */
class PrivacyChecklistTest {
    @TempDir
    lateinit var temp: Path

    private val userImage = BlobRef("a".repeat(64))
    private val screenshot = BlobRef("b".repeat(64))
    private val call = ToolCallId("call-1")
    private val tap = UiAction.Tap(Target.Ref(ElementRef("e1")))

    private fun screen(
        id: String,
        name: String,
    ): ScreenSnapshot = Screens.snapshot(id, listOf(el("e1", ElementRole.TEXT, name)))

    private fun after(snapshot: ScreenSnapshot) =
        ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.CHANGED, after = snapshot)

    private fun log(vararg events: SessionEvent) =
        SessionLog(ReplaySamples.header, events.mapIndexed { i, event -> SessionRecord(i + 1L, 0, event) })

    private fun text(
        vararg events: SessionEvent,
        scan: Gitleaks.Scan = Gitleaks.Scan.Clean,
        scenario: Scenario = ReplaySamples.scenario,
        override: ReplayOverride = ReplayOverride(),
    ): String = PrivacyChecklist.text("sample", log(*events), scenario, override, scan)

    private fun device(device: Device) = ReplaySamples.scenario.copy(device = device)

    private fun judged(
        snapshot: String,
        outcome: JudgmentOutcome,
        rule: String? = null,
    ) = ScreenJudged(call, SnapshotId(snapshot), JudgmentPurpose.TOOL, outcome, rule, hint = null)

    /** The items of the section headed [title], as written. */
    private fun section(
        title: String,
        text: String,
    ): List<String> =
        text
            .lines()
            .dropWhile { it != "## $title" }
            .drop(1)
            .takeWhile { !it.startsWith("## ") }
            .filter { it.startsWith("- [ ] ") }

    private fun assertItem(
        item: String,
        text: String,
    ) = assertTrue(text.lines().contains("- [ ] $item"), "no item '$item' in:\n$text")

    @Test
    fun `a screenshot is an item`() {
        val frame = ScreenFrame(screenshot, "image/jpeg", 1080, 2400, som = false, SnapshotId("s1"), SnapshotId("s2"))
        assertItem("blobs/${screenshot.value} (screenshot, image/jpeg, 1080×2400)", text(frame))
    }

    @Test
    fun `an image the user sent is an item, and so is the text sent with it`() {
        val input =
            InboxEntry(InboxEntryKind.FOLLOWUP, listOf(Part.Text("给张三发这张图"), Part.Image(userImage, "image/png", 4, 3)))
        val text = text(InboxSpliced(InboxQueue.NEXT_TURN, removed = 0, added = listOf(input)))
        assertItem("blobs/${userImage.value} (from the user, image/png, 4×3)", text)
        assertItem("给张三发这张图", text)
    }

    @Test
    fun `an answer the user typed is an item`() {
        assertItem("十点半", text(QuestionAnswered(PromptId("prompt-1"), AskResult.Answered("十点半"))))
    }

    @Test
    fun `the names, values, hints and OCR lines of an observed screen are items`() {
        val field = el("e2", ElementRole.INPUT, "回复").copy(value = "晚上见", hint = "输入消息")
        val snapshot =
            Screens
                .snapshot("s1", listOf(el("e1", ElementRole.TEXT, "张三"), field))
                .copy(ocr = listOf(OcrLine("王五的头像", box(0, 0, 10, 10))))
        val text = text(ScreenObserved(call, snapshot))
        listOf("张三", "回复", "晚上见", "输入消息", "王五的头像").forEach { assertItem(it, text) }
    }

    @Test
    fun `the screen after an action is read for items`() {
        assertItem("李四", text(ScreenActed(call, null, tap, after(screen("s2", "李四")))))
    }

    @Test
    fun `the screen after a launch is read for items`() {
        assertItem("赵六", text(ScreenLaunched(call, LaunchTarget.App("com.example.chat"), after(screen("s3", "赵六")))))
    }

    private fun outcome(
        status: ActionOutcome.Status,
        after: ScreenSnapshot? = null,
    ) = ActionOutcome(status, ActionOutcome.Effect.UNKNOWN, after = after)

    @Test
    fun `the reason of a failed action is an item, with the screen read back after it`() {
        val text =
            text(
                ScreenActed(
                    call,
                    null,
                    tap,
                    outcome(ActionOutcome.Status.Failed("MARK-A1 发给张三失败"), screen("s2", "李四")),
                ),
                judged("s2", JudgmentOutcome.SHOWN),
            )
        assertEquals(
            listOf("- [ ] MARK-A1 发给张三失败 (failed, screen/action of call-1; with screen s2)"),
            section(PrivacyChecklist.REASONS, text),
        )
    }

    @Test
    fun `the reason of a refused action is an item, also with nothing read back`() {
        val text = text(ScreenActed(call, null, tap, outcome(ActionOutcome.Status.Refused("MARK-A2 不能点 李四的头像"))))
        assertEquals(
            listOf(
                "- [ ] MARK-A2 不能点 李四的头像 (refused, screen/action of call-1; nothing read back, not shown to the model)",
            ),
            section(PrivacyChecklist.REASONS, text),
        )
    }

    @Test
    fun `the reason of a failed action is an item, also with the screen read back withheld`() {
        val text =
            text(
                ScreenActed(
                    call,
                    null,
                    tap,
                    outcome(ActionOutcome.Status.Failed("MARK-A3 余额 1000"), screen("s3", "余额 1000")),
                ),
                judged("s3", JudgmentOutcome.WITHHELD, "hard.finance_app"),
            )
        assertEquals(
            listOf(
                "- [ ] MARK-A3 余额 1000 (failed, screen/action of call-1; " +
                    "screen s3 withheld by hard.finance_app, not shown to the model)",
            ),
            section(PrivacyChecklist.REASONS, text),
        )
    }

    @Test
    fun `the reason of a failed launch is an item, also with the screen read back withheld`() {
        val launch = LaunchTarget.App("com.example.chat")
        val text =
            text(
                ScreenLaunched(call, launch, outcome(ActionOutcome.Status.Failed("MARK-L1 王五的会话"), screen("s4", "王五"))),
                judged("s4", JudgmentOutcome.WITHHELD, ScreenJudged.CONFIRM),
            )
        assertEquals(
            listOf(
                "- [ ] MARK-L1 王五的会话 (failed, screen/launch of call-1; " +
                    "screen s4 withheld by ${ScreenJudged.CONFIRM}, not shown to the model)",
            ),
            section(PrivacyChecklist.REASONS, text),
        )
    }

    @Test
    fun `the reason of a refused launch is an item, also with nothing read back`() {
        val launch = LaunchTarget.App("com.example.chat")
        val text = text(ScreenLaunched(call, launch, outcome(ActionOutcome.Status.Refused("MARK-L2 赵六的设置"))))
        assertEquals(
            listOf("- [ ] MARK-L2 赵六的设置 (refused, screen/launch of call-1; nothing read back, not shown to the model)"),
            section(PrivacyChecklist.REASONS, text),
        )
    }

    @Test
    fun `an action done or on a stale ref gives no reason item`() {
        val stale = ActionOutcome.Status.StaleRef(ActionOutcome.StaleReason.FORGOTTEN)
        val text =
            text(ScreenActed(call, null, tap, after(screen("s2", "李四"))), ScreenActed(call, null, tap, outcome(stale)))
        assertEquals(emptyList<String>(), section(PrivacyChecklist.REASONS, text))
    }

    @Test
    fun `an installed app is an item`() {
        assertItem("Chat (com.example.chat)", text())
    }

    @Test
    fun `a withheld screen comes first, as never shown to the model, and not among the texts read`() {
        val text =
            text(
                ScreenObserved(call, screen("s1", "余额 1000")),
                judged("s1", JudgmentOutcome.WITHHELD, "hard.finance_app"),
                ScreenObserved(call, screen("s2", "张三")),
                judged("s2", JudgmentOutcome.SHOWN),
            )
        assertEquals("## ${PrivacyChecklist.WITHHELD}", text.lines().first { it.startsWith("## ") })
        assertTrue("没给模型看过" in PrivacyChecklist.WITHHELD)
        assertEquals(
            listOf("- [ ] screen s1 of com.tencent.mm (withheld by hard.finance_app): 余额 1000"),
            section(PrivacyChecklist.WITHHELD, text),
        )
        assertEquals(listOf("- [ ] 张三"), section("Text read from the device", text))
    }

    @Test
    fun `a screen withheld after an action is listed as withheld`() {
        val text =
            text(
                ScreenActed(call, null, tap, after(screen("s3", "转账成功"))),
                judged("s3", JudgmentOutcome.WITHHELD, ScreenJudged.CONFIRM),
            )
        assertEquals(
            listOf("- [ ] screen s3 of com.tencent.mm (withheld by ${ScreenJudged.CONFIRM}): 转账成功"),
            section(PrivacyChecklist.WITHHELD, text),
        )
        assertEquals(emptyList<String>(), section("Text read from the device", text))
    }

    @Test
    fun `an app version is an item`() {
        val scenario = device(ReplaySamples.scenario.device.copy(packages = mapOf("com.tencent.mm" to "8.0.50")))
        assertItem("com.tencent.mm 8.0.50", text(scenario = scenario))
    }

    @Test
    fun `the ROM build is an item`() {
        val build = "google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.005/12228598:userdebug/dev-keys"
        assertItem(build, text(scenario = device(ReplaySamples.scenario.device.copy(build = build))))
    }

    @Test
    fun `the message of what the device threw is an item, an empty one none`() {
        val type = ScreenUnavailableException::class.java.name
        val override =
            ReplayOverride(
                device =
                    listOf(
                        DeviceFact("call-1", 2, DevicePort.ACT, throws = Thrown(type, "张三的聊天窗口已关闭")),
                        DeviceFact("call-2", 1, DevicePort.SNAPSHOT, throws = Thrown(type, "")),
                    ),
            )
        val items = section("What the device threw (replay.override.json throws.message)", text(override = override))
        assertEquals(listOf("- [ ] 张三的聊天窗口已关闭 ($type from ACT of call-1, op 2)"), items)
    }

    @Test
    fun `a text with a card number in it is flagged, a plain one is not`() {
        val text =
            text(ScreenObserved(call, screen("s1", "卡号 4111 1111 1111 1111")), ScreenObserved(call, screen("s2", "张三")))
        assertItem("卡号 4111 1111 1111 1111 — suspected number", text)
        assertItem("张三", text)
    }

    @Test
    fun `a text seen twice is one item`() {
        val text = text(ScreenObserved(call, screen("s1", "张三")), ScreenObserved(call, screen("s2", "张三")))
        assertEquals(1, text.lines().count { it == "- [ ] 张三" }, text)
    }

    @Test
    fun `a section with nothing in it says so`() {
        val none = ReplaySamples.scenario.copy(device = ReplaySamples.scenario.device.copy(apps = emptyList()))
        assertEquals(9, text(scenario = none).lines().count { it == "(none)" })
    }

    @Test
    fun `the scan's outcome is stated`() {
        assertTrue(text(scan = Gitleaks.Scan.NotInstalled).contains("gitleaks: not scanned"))
        assertTrue(text(scan = Gitleaks.Scan.Clean).contains("gitleaks: clean"))
        val found = text(scan = Gitleaks.Scan.Found("leak"))
        assertTrue(found.contains("gitleaks: FOUND"), found)
        assertFalse(found.contains("leak\n"), "the report is not copied into the checklist")
    }

    @Test
    fun `the checklist is written into the directory given`() {
        val dir = temp.resolve("sample")
        PrivacyChecklist.write(dir, text())
        assertEquals(text(), dir.resolve(PrivacyChecklist.FILE).readText())
    }
}
