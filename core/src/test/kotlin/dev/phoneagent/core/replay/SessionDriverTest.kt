package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.SessionResumed
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskStopped
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.GoldenFiles
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.ScriptedScreen
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The app's side of a session survives a recording and its replay: a stop or a shutdown between two
 * turns, and the resume after it, are recorded, read back from the log ([UserScript]) and given
 * again at the same place, so the replayed log is the recorded one.
 */
class SessionDriverTest {
    @TempDir
    lateinit var temp: Path

    private val info =
        ModelInfo(
            ProviderId("test-anthropic"),
            ModelId("claude-test"),
            ModelProtocol.ANTHROPIC_MESSAGES,
            ModelCapabilities(tools = true, vision = false, contextTokens = 128_000),
        )

    private fun followup(text: String) = Stimulus.Input(InboxEntry(InboxEntryKind.FOLLOWUP, listOf(Part.Text(text))))

    /** Observes and answers, is stopped by [stop], resumes, and observes and answers again. */
    private fun stopAndResume(stop: Stimulus) =
        AuthoredScenario(
            name = "stop-resume",
            description = "Two turns with a stop and a resume between them.",
            info = info,
            stimuli = listOf(followup("看看屏幕"), stop, Stimulus.Resume(), followup("再看一次")),
            replies = { model ->
                model
                    .calls(call("c1", "phone_observe", """{"reason":"看看"}"""))
                    .answers("是聊天页。")
                    .calls(call("c2", "phone_observe", """{"reason":"再看看"}"""))
                    .answers("还是聊天页。")
            },
            screens = { ScriptedScreen(Screens.CHAT) },
        )

    /** Records [source], replays the fixture, and returns the log's stimuli and what failed. */
    private fun roundTrip(source: AuthoredScenario): Pair<List<Stimulus>, List<String>> {
        val dir = temp.resolve(source.name)
        runTest { SessionRecorder.record(this, source, dir, temp.resolve("checklists")) }
        val fixture = ReplayFiles.load(dir)
        var failed = emptyList<String>()
        runTest {
            val replayed = SessionReplay.run(this, fixture)
            val goldens = GoldenFiles(dir, temp.resolve("actual"), SessionReplay.ACCEPT)
            failed =
                (
                    replayed.runChecks() +
                        replayed.compareChecks(
                            goldens,
                        )
                ).mapNotNull { runCatching(it).exceptionOrNull()?.message }
        }
        return UserScript.of(fixture.log.records) to failed
    }

    @Test
    fun `a stop by the user between turns and the resume replay where they were given`() {
        val (stimuli, failed) = roundTrip(stopAndResume(Stimulus.Stop(StopCause.USER)))
        assertEquals(emptyList<String>(), failed)
        assertEquals(
            listOf(Stimulus.Input::class, Stimulus.Stop::class, Stimulus.Resume::class, Stimulus.Input::class),
            stimuli.map { it::class },
        )
    }

    @Test
    fun `a shutdown between turns and the resume replay where they were given`() {
        val (stimuli, failed) = roundTrip(stopAndResume(Stimulus.Shutdown()))
        assertEquals(emptyList<String>(), failed)
        assertEquals(
            listOf(Stimulus.Input::class, Stimulus.Shutdown::class, Stimulus.Resume::class, Stimulus.Input::class),
            stimuli.map { it::class },
        )
    }

    @Test
    fun `the resume follows the stop in the recorded log`() {
        val dir = temp.resolve("stop")
        runTest {
            SessionRecorder.record(
                this,
                stopAndResume(Stimulus.Stop(StopCause.USER)),
                dir,
                temp.resolve("checklists"),
            )
        }
        val events =
            ReplayFiles
                .load(dir)
                .log.records
                .map { it.event }
        val stopped = events.indexOfFirst { it is TaskStopped }
        assertEquals(TaskStopped(StopCause.USER), events[stopped])
        assertEquals(SessionResumed(0), events[stopped + 1])
    }
}
