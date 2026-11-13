package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.ScriptedScreen
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * Record mode stages a fixture under the work directory and scans it there; the fixture directory
 * in the sources is touched only after a clean scan, or when gitleaks is not installed and the
 * source is authored with no keys (engineering-baseline §3.2). gitleaks is a fake executable that
 * keeps what it was given to scan and exits as told.
 */
class SessionRecorderTest {
    @TempDir
    lateinit var temp: Path

    private val sources: Path get() = temp.resolve("src/sessions")
    private val dir: Path get() = sources.resolve(NAME)
    private val work: Path get() = temp.resolve("build/session-record")
    private val staged: Path get() = work.resolve(NAME).resolve(SessionRecorder.STAGED)
    private val scanned: Path get() = temp.resolve("scanned.txt")

    private val info =
        ModelInfo(
            ProviderId("test-anthropic"),
            ModelId("claude-test"),
            ModelProtocol.ANTHROPIC_MESSAGES,
            ModelCapabilities(tools = true, vision = false, contextTokens = 128_000),
        )

    /** Observes the chat page and answers [answer]. */
    private fun source(answer: String = "是聊天页。") =
        AuthoredScenario(
            name = NAME,
            description = "Observes and answers.",
            info = info,
            stimuli = listOf(Stimulus.Input(InboxEntry(InboxEntryKind.FOLLOWUP, listOf(Part.Text("看看屏幕"))))),
            replies = { model -> model.calls(call("c1", "phone_observe", """{"reason":"看看"}""")).answers(answer) },
            screens = { ScriptedScreen(Screens.CHAT) },
        )

    /**
     * A fake gitleaks that writes the directory it was given (its last argument) and then the files
     * in it into [scanned], and exits with [exit]; one executable per exit code.
     */
    private fun gitleaks(exit: Int): Path {
        val script = Files.createDirectories(temp.resolve("bin-$exit")).resolve("gitleaks")
        script.writeText(
            "#!/bin/sh\nfor last; do :; done\nprintf '%s\\n' \"\$last\" > '$scanned'\n" +
                "(cd \"\$last\" && find . -type f | sort) >> '$scanned'\nexit $exit\n",
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        return script
    }

    private fun record(
        source: RecordingSource,
        gitleaks: Path?,
    ) = runTest { SessionRecorder.record(this, source, dir, work, gitleaks) }

    /** Every path under [root] with its bytes (`null` for a directory); `null` when [root] does not exist. */
    private fun tree(root: Path): Map<String, List<Byte>?>? =
        if (!root.exists()) {
            null
        } else {
            Files.walk(root).use { it.toList() }.associate { path ->
                root.relativize(path).toString() to if (path.isDirectory()) null else Files.readAllBytes(path).toList()
            }
        }

    /** Records an older fixture into the sources, then the current one with [gitleaks]; returns the failure. */
    private fun overOlder(
        gitleaks: Path?,
        source: RecordingSource = source(),
    ): IllegalStateException {
        record(source(answer = "旧的回答。"), gitleaks(exit = 0))
        val before = tree(sources)
        val failure = assertThrows<IllegalStateException> { record(source, gitleaks) }
        assertEquals(before, tree(sources), "the sources are as they were, byte for byte")
        assertNotEquals(tree(dir), tree(staged), "the staged fixture is the new recording")
        return failure
    }

    @Test
    fun `a clean scan of the staged fixture installs it byte for byte`() {
        record(source(), gitleaks(exit = 0))
        val scan = scanned.readLines()
        assertEquals(staged.toString(), scan.first(), "gitleaks scans the staged copy")
        assertTrue(
            scan.containsAll(listOf("./scenario.json", "./session.jsonl", "./requests.expected.jsonl")),
            "the whole fixture is staged before the scan: $scan",
        )
        assertEquals(tree(staged), tree(dir))
        ReplayFiles.load(dir)
    }

    @Test
    fun `leaks leave the sources as they were`() {
        val failure = overOlder(gitleaks(exit = Gitleaks.LEAKS_EXIT))
        assertTrue("gitleaks found secrets" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `leaks in a first recording create nothing in the sources`() {
        Files.createDirectories(sources)
        assertThrows<IllegalStateException> { record(source(), gitleaks(exit = Gitleaks.LEAKS_EXIT)) }
        assertEquals(mapOf<String, List<Byte>?>("" to null), tree(sources))
    }

    @Test
    fun `a failure of gitleaks itself leaves the sources as they were`() {
        val failure = overOlder(gitleaks(exit = 1))
        assertTrue("gitleaks failed" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a staged fixture that does not load is not installed`() {
        record(source(answer = "旧的回答。"), gitleaks(exit = 0))
        val broken = temp.resolve("broken")
        FixtureTexts.install(staged, broken)
        Files.delete(broken.resolve(ReplayFiles.REQUESTS))
        val before = tree(sources)
        val failure = assertThrows<IllegalArgumentException> { FixtureTexts.install(broken, dir) }
        assertTrue("is missing" in failure.message.orEmpty(), failure.message)
        assertEquals(before, tree(sources))
    }

    @Test
    fun `without gitleaks an authored source with no keys is installed`() {
        record(source(), gitleaks = null)
        assertEquals(tree(staged), tree(dir))
    }

    @Test
    fun `without gitleaks a source with keys is not installed`() {
        val keyed =
            object : RecordingSource by source() {
                override val secrets: List<String> = listOf("test-key-not-real")
            }
        val failure = overOlder(gitleaks = null, source = keyed)
        assertTrue("gitleaks is not installed" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `without gitleaks a live source is not installed`() {
        val live =
            object : RecordingSource by source() {
                override val recording: Recording = Recording.LIVE
            }
        val failure = overOlder(gitleaks = null, source = live)
        assertTrue("a live recording" in failure.message.orEmpty(), failure.message)
    }
}

private const val NAME = "chat"
