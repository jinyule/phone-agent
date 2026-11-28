package dev.phoneagent.core.replay

import dev.phoneagent.core.session.SessionLog
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.testing.GoldenFiles
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.fail
import java.nio.file.Path

/**
 * One fixture's session run again through the product loop (engineering-baseline §3.2): the
 * fixture's model ([ReplayChatModel]), device ([ReplayScreen]) and user ([UserScript]) in
 * [ReplayHarness], driven by [SessionDriver]. [run] gives a [Replayed] to compare or write back.
 */
object SessionReplay {
    /** How a fixture that no longer matches is accepted, after review. */
    const val ACCEPT: String =
        "./gradlew :core:test -PsessionSnapshot=refresh, then review git diff core/src/test/resources/sessions"

    /**
     * Replays [fixture] in [scope]'s virtual time. A replay failure (a mismatch of the model or
     * the device, a stimulus that never came due) does not throw: it is in [Replayed.failures].
     * A fixture whose log replay cannot drive fails with [IllegalArgumentException] ([UserScript]).
     */
    suspend fun run(
        scope: TestScope,
        fixture: SessionFixture,
    ): Replayed {
        val started =
            requireNotNull(
                fixture.log.records
                    .firstOrNull()
                    ?.event as? TaskStarted,
            ) {
                "${fixture.name}: the log does not start with task/start"
            }
        val stimuli = UserScript.of(fixture.log.records)
        val model = ReplayChatModel.of(fixture)
        val harness =
            ReplayHarness(scope, fixture.scenario, model) { analyzer, current ->
                ReplayScreen.of(fixture, analyzer, current)
            }
        val driver = SessionDriver(scope, harness, stimuli, started)
        val failure =
            try {
                driver.run()
                null
            } catch (failed: AssertionError) {
                // A replay failure: reported with the comparisons, which show where the run went.
                failed
            }
        return Replayed(fixture, harness, model, driver, harness.log(), listOfNotNull(failure))
    }
}

/**
 * The outcome of [SessionReplay.run]: the replayed log and requests, normalized like the fixture's,
 * and the checks of each mode.
 *
 * @property failures replay failures the run ended in (more are in [ReplayHarness.uncaught]).
 */
class Replayed(
    val fixture: SessionFixture,
    val harness: ReplayHarness,
    private val model: ReplayChatModel,
    private val driver: SessionDriver,
    val log: SessionLog,
    val failures: List<Throwable>,
) {
    private val screen = harness.screen as ReplayScreen

    /** The replayed session as record mode would write it. */
    val texts: FixtureTexts.Normalized = FixtureTexts.normalized(log, harness.tape.requests, model.templates)

    /** The fixture's session, decoded and written again the same way. */
    val expected: FixtureTexts.Normalized = FixtureTexts.normalized(fixture.log, fixture.requests)

    /** The rules the replay judged by. */
    val pin: PolicyPin = PolicyPins.current(harness.tuning)

    /**
     * What every mode checks, refresh included: the run did what the recording did. No replay
     * failure, the strict rule-1 check of every request, and everything recorded used up: the
     * model's script, the device's queues (with the facts of `replay.override.json`) and the
     * stimuli. Also that `scenario.json` says what the log's `task/start` does.
     */
    fun runChecks(): List<() -> Unit> =
        failures.map { failure -> { throw failure } } +
            harness.uncaught.map { failure -> { throw AssertionError("escaped the loop: $failure", failure) } } +
            listOf(
                { assertEquals(emptyList<String>(), harness.checker.mismatches, "requests the log does not rebuild") },
                { model.assertConsumed() },
                { screen.assertConsumed() },
                { assertEquals(emptyList<Stimulus>(), driver.left, "stimuli never given") },
                {
                    assertEquals(
                        fixture.scenario.task,
                        ScenarioTask(driver.settings.level, driver.settings.unattended),
                        "scenario.json task against the log's task/start",
                    )
                },
            )

    /**
     * What replay mode checks besides [runChecks]: the policy pin, the requests and the events
     * against the fixture's (first difference named), the decisions (with the hint when the rules
     * version did not move), and the sidecars. Mismatching files are kept under [goldens]' actual
     * directory.
     */
    fun compareChecks(goldens: GoldenFiles): List<() -> Unit> =
        listOf(
            {
                val differences = PolicyPins.differences(fixture.scenario.policy, pin)
                if (differences.isNotEmpty()) fail(differences.joinToString("\n") + "\n${SessionReplay.ACCEPT}")
            },
            {
                goldens.assertEquivalent(ReplayFiles.REQUESTS, expected.requests, texts.requests) { want, got ->
                    FixtureTexts.firstDifference(want, got, FixtureTexts::requestLine)
                }
            },
            {
                goldens.assertEquivalent(ReplayFiles.SESSION, expected.session, texts.session) { want, got ->
                    FixtureTexts.firstDifference(want, got, FixtureTexts::recordLine)
                }
            },
            {
                assertEquals(
                    recordedDecisions.joinToString("\n"),
                    replayedDecisions.joinToString("\n"),
                    "policy/verdict and screen/judged, one line per decision",
                )
            },
            {
                if (recordedDecisions != replayedDecisions &&
                    fixture.scenario.policy.rulesVersion == pin.rulesVersion
                ) {
                    fail(
                        "verdicts changed while RULES_VERSION is still ${pin.rulesVersion}: if the rules changed, " +
                            "bump PolicyTuning.RULES_VERSION, then refresh",
                    )
                }
            },
        ) + sidecars().map { (name, text) -> { goldens.assertMatches(name, text) } }

    private val recordedDecisions = FixtureTexts.decisions(fixture.log.records)
    private val replayedDecisions = FixtureTexts.decisions(log.records)

    private fun sidecars(): Map<String, String> =
        texts.sidecars.ifEmpty {
            mapOf(FixtureTexts.SYSTEM_PROMPT to "(no request)\n", FixtureTexts.TOOL_SCHEMAS to "(no request)\n")
        }

    /**
     * Writes the replayed session over the fixture in [dir] (refresh): `session.jsonl`, the
     * requests, the sidecars and the policy pin. The model's replies, the device facts and the
     * user's input stay what the fixture says. Call it only when [runChecks] pass.
     */
    fun writeBack(dir: Path) {
        FixtureTexts.write(
            dir,
            fixture.scenario.copy(policy = pin),
            texts,
            fixture.override.renamed { concrete ->
                requireNotNull(texts.normalizer.tokenOf(FieldClass.CALL, concrete))
            },
            fixture.blobs,
        )
    }
}
