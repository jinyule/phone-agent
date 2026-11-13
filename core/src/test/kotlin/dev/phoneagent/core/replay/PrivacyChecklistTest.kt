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
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
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
    ): String = PrivacyChecklist.text("sample", log(*events), scenario, scan)

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

    @Test
    fun `an installed app is an item`() {
        assertItem("Chat (com.example.chat)", text())
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
        assertEquals(4, text(scenario = none).lines().count { it == "(none)" })
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
        PrivacyChecklist.write(dir, "sample", log(), ReplaySamples.scenario, Gitleaks.Scan.Clean)
        assertEquals(text(), dir.resolve(PrivacyChecklist.FILE).readText())
    }
}
