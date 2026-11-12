package dev.phoneagent.core.replay

import dev.phoneagent.core.agent.AgentLoop
import dev.phoneagent.core.agent.ContextInput
import dev.phoneagent.core.agent.LoopEvent
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.TaskRuntime
import dev.phoneagent.core.agent.UserInput
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.TaskStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays the user's and the app's side of a session into [harness]'s loop (WP08 design §5): starts
 * the task with [settings], then gives [stimuli] in order, then lets the loop run until nothing is
 * left to run. Record mode and replay use it alike, so a recording's input lands where its replay
 * puts it again.
 *
 * When a stimulus is given:
 * - with [Stimulus.after] (from a fixture): once the log has reached that seq, i.e. right after
 *   the record it followed in the recording. A log already past it fails: the replay no longer
 *   waits where the recording did;
 * - an [Stimulus.Answer]: once its prompt waits ([Phase.Waiting] with the id of the session's
 *   n-th prompt), without letting time pass, so no expiry runs first;
 * - an authored one (no `after`): a [Stimulus.Resume] once the runtime stopped, any other once the
 *   loop is idle or done after everything runnable has run.
 *
 * A stimulus that does not come due fails with [AssertionError]: the runtime stopped before it, or
 * [HORIZON_MS] of virtual time passed (the loop waits for something that never comes).
 */
class SessionDriver(
    private val scope: TestScope,
    private val harness: ReplayHarness,
    private val stimuli: List<Stimulus>,
    /** The `task/start` the task is started with. */
    val settings: TaskStarted,
) {
    private val seq = MutableStateFlow(0L)
    private var given = 0
    private lateinit var runtimeScope: CoroutineScope

    /** The runtime now driving the session (a resume replaces it). */
    lateinit var runtime: TaskRuntime
        private set

    /** The stimuli not given (yet). */
    val left: List<Stimulus> get() = stimuli.drop(given)

    /** Starts the task, gives every stimulus, and runs the loop until it waits for nothing. */
    suspend fun run() {
        runtimeScope = harness.runtimeScope()
        adopt(AgentLoop.start(harness.setup, ReplayHarness.SESSION, settings, runtimeScope))
        for (stimulus in stimuli) {
            give(stimulus)
            given++
        }
        scope.testScheduler.advanceUntilIdle()
    }

    private suspend fun adopt(next: TaskRuntime) {
        runtime = next
        runtimeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            next.events.collect { event ->
                if (event is LoopEvent.Recorded) seq.update { maxOf(it, event.record.seq) }
            }
        }
        seq.update { maxOf(it, harness.log().lastSeq) }
    }

    private suspend fun give(stimulus: Stimulus) {
        when (stimulus) {
            is Stimulus.Answer -> answer(stimulus)
            is Stimulus.Resume -> resume(stimulus)
            is Stimulus.Input, is Stimulus.Level, is Stimulus.Stop, is Stimulus.Shutdown -> act(stimulus)
        }
    }

    private suspend fun answer(answer: Stimulus.Answer) {
        val phase =
            due(answer) {
                combine(runtime.phase, seq) { phase, _ -> phase }.first { phase ->
                    phase is Phase.Stopped || (phase is Phase.Waiting && phase.promptId == prompt(answer.prompt))
                }
            }
        val waiting = phase as? Phase.Waiting ?: stopped(answer, phase)
        val id = checkNotNull(waiting.promptId)
        if (!runtime.answer(id, answer.reply)) {
            throw AssertionError("$answer: the runtime did not take it as the answer to ${id.value}")
        }
    }

    private suspend fun resume(resume: Stimulus.Resume) {
        due(resume) { runtime.phase.first { it is Phase.Stopped } }
        resume.after?.let { reached(resume, it) }
        runtimeScope = harness.runtimeScope()
        adopt(AgentLoop.resume(harness.setup, runtimeScope))
    }

    private suspend fun act(stimulus: Stimulus) {
        val after = stimulus.after
        val phase =
            if (after == null) {
                scope.testScheduler.runCurrent()
                due(stimulus) { runtime.phase.first { it is Phase.Idle || it is Phase.Done || it is Phase.Stopped } }
            } else {
                due(stimulus) {
                    combine(runtime.phase, seq) { phase, last -> phase.takeIf { it is Phase.Stopped || last >= after } }
                        .first { it != null }
                }
            }
        if (phase is Phase.Stopped) stopped(stimulus, phase)
        after?.let { reached(stimulus, it) }
        val runtime = runtime
        when (stimulus) {
            is Stimulus.Input -> input(runtime, stimulus)
            is Stimulus.Level -> runtime.changeLevel(stimulus.level)
            is Stimulus.Stop -> runtime.stop(stimulus.cause)
            is Stimulus.Shutdown -> runtimeScope.cancel()
            is Stimulus.Answer, is Stimulus.Resume -> error("given elsewhere")
        }
    }

    private fun input(
        runtime: TaskRuntime,
        input: Stimulus.Input,
    ) {
        val entry = input.entry
        when (entry.kind) {
            InboxEntryKind.FOLLOWUP -> runtime.followup(UserInput(entry.parts))
            InboxEntryKind.STEER -> runtime.steer(UserInput(entry.parts))
            InboxEntryKind.INJECT -> runtime.inject(ContextInput(checkNotNull(entry.source), entry.parts))
        }
    }

    /** Fails unless the log is exactly at [after]: the recording gave [stimulus] right there. */
    private suspend fun reached(
        stimulus: Stimulus,
        after: Long,
    ) {
        val last = harness.log().lastSeq
        if (last != after) {
            throw AssertionError("$stimulus: the recording gave it after seq $after, the replayed log is at $last")
        }
    }

    /** The id of the session's [n]-th prompt, if it was asked yet. */
    private suspend fun prompt(n: Int): PromptId? =
        harness
            .log()
            .records
            .mapNotNull { record ->
                when (val event = record.event) {
                    is ApprovalAsked -> event.promptId
                    is QuestionAsked -> event.promptId
                    is HandoffStarted -> event.promptId
                    else -> null
                }
            }.getOrNull(n - 1)

    private suspend fun <T> due(
        stimulus: Stimulus,
        wait: suspend () -> T,
    ): T =
        withTimeoutOrNull(HORIZON_MS) { wait() }
            ?: throw AssertionError(
                "$stimulus never came due within $HORIZON_MS ms of virtual time: " +
                    "the log is at seq ${harness.log().lastSeq}, the runtime ${runtime.phase.value}",
            )

    private suspend fun stopped(
        stimulus: Stimulus,
        phase: Phase,
    ): Nothing =
        throw AssertionError("$stimulus: the runtime is $phase at seq ${harness.log().lastSeq}, before it was given")

    companion object {
        /** How long a stimulus may take to come due, in virtual time: longer than any wait of the loop. */
        const val HORIZON_MS: Long = 7 * 24 * 60 * 60 * 1000L
    }
}
