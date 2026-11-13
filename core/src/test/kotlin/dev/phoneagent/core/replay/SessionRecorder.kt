@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.TaskStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import java.nio.file.Path

/**
 * Where record mode gets a session from (WP08 design §7): the model, the device and the user of
 * one scenario, and what `scenario.json` says about them. M1 has one kind, the authored scenarios
 * on the JVM ([AuthoredScenario]); WP11 adds a real model and a real device behind the same
 * interface.
 */
interface RecordingSource {
    /** The fixture directory's name. */
    val name: String

    /** How the session is produced. */
    val recording: Recording

    /** What the scenario shows, for reviewers. */
    val description: String

    /** The main model, as `scenario.json` keeps it. */
    val info: ModelInfo

    /** The task's start settings. */
    val task: ScenarioTask

    /** Product settings that differ from the defaults. */
    val assembly: Assembly

    /** The device side that is not in the log. */
    val device: Device

    /** Keys the session used; each becomes `{{credential:n}}` (N5). */
    val secrets: List<String>

    /** What the user and the app do, in order. */
    val stimuli: List<Stimulus>

    /** A fresh model for one recording. */
    fun model(): ChatModel

    /** A fresh device for one recording. */
    fun screen(): ScreenPort
}

/**
 * The model of a recording: [model]'s replies with their `{{fromRequest:…}}` templates resolved
 * against each request, as [ReplayChatModel] resolves them; [templates] puts them back into the
 * fixture (N7).
 */
class TemplatingModel(
    private val model: ChatModel,
) : ChatModel by model {
    private val resolved = mutableMapOf<ToolCallId, RequestTemplates.Resolved>()

    /** What each reply's templates resolved to, by call. */
    val templates: Map<ToolCallId, RequestTemplates.Resolved> get() = resolved

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        val reply = model.complete(request, onDelta)
        val live = RequestTemplates.resolve(reply, request, "the reply to request #${resolved.size + 1}")
        resolved += RequestTemplates.changes(reply, live)
        return live
    }
}

/**
 * The device of a recording: forwards to [port] and keeps what the log will not hold
 * (`replay.override.json`, [DeviceFact]): an `awaitChange`'s result, a `frame` that gave none,
 * a read, frame or wait that threw or was cut off, the message of an action that threw, and how
 * long each port call took in virtual time ([now]). Calls are counted per tool call as
 * [DeviceScript] counts them, the call being the one the log says is open ([currentCall]); a
 * capture counts whether or not the runtime keeps its frame, as [DeviceScript] reads each capture
 * off the judgment that allowed it.
 * Snapshots and outcomes come back analyzed by [analyzer], as a real port gives them.
 *
 * A port call replay could not give again fails loudly: an exception of a class [ThrownTypes]
 * cannot rebuild, a frame of a mime type the log does not keep.
 */
class FactRecorder(
    private val port: ScreenPort,
    private val analyzer: SnapshotAnalyzer,
    private val currentCall: suspend () -> ToolCallId?,
    private val now: () -> Long,
) : ScreenPort {
    private val ops = mutableMapOf<ToolCallId?, Int>()
    private val kept = mutableListOf<DeviceFact>()

    override val surface: SurfaceRef get() = port.surface

    /** The facts so far, with the calls' ids as the log has them. */
    val facts: List<DeviceFact> get() = kept.toList()

    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot =
        call(DevicePort.SNAPSHOT) { analyzer.analyzed(port.snapshot(options)) }

    override suspend fun act(
        action: UiAction,
        on: SnapshotId?,
    ): ActionOutcome = call(DevicePort.ACT) { analyzer.analyzed(port.act(action, on)) }

    override suspend fun launch(target: LaunchTarget): ActionOutcome =
        call(DevicePort.LAUNCH) { analyzer.analyzed(port.launch(target)) }

    override suspend fun frame(
        maxEdge: Int,
        som: Boolean,
    ): Frame? =
        call(DevicePort.FRAME, { it == null }) {
            port.frame(maxEdge, som)?.also { frame ->
                check(frame.mime in Part.IMAGE_MIMES) {
                    "a frame of ${frame.mime} is not logged, so replay cannot give it again"
                }
            }
        }

    override suspend fun awaitChange(timeoutMs: Long): Boolean =
        call(DevicePort.AWAIT_CHANGE, changed = { it }) { port.awaitChange(timeoutMs) }

    @Suppress("TooGenericExceptionCaught") // Whatever the device threw, replay must throw it again.
    private suspend fun <T> call(
        kind: DevicePort,
        noFrame: (T) -> Boolean = { false },
        changed: (T) -> Boolean? = { null },
        block: suspend () -> T,
    ): T {
        val call = currentCall()
        val op = (ops[call] ?: 0) + 1
        ops[call] = op
        val start = now()
        val moves = kind == DevicePort.ACT || kind == DevicePort.LAUNCH
        var ended = false
        val result =
            try {
                block().also { ended = true }
            } catch (cut: CancellationException) {
                throw cut
            } catch (failure: Exception) {
                ended = true
                val thrown = Thrown(failure.javaClass.name, failure.message.orEmpty())
                keep(DeviceFact(call?.value, op, kind, throws = thrown, delayMs = now() - start))
                throw failure
            } finally {
                // Cut off (or an Error, which fails the recording anyway): an action is logged as
                // not finished; a read, frame or wait is not logged, so it is kept as a hang.
                if (!ended && !moves) keep(DeviceFact(call?.value, op, kind, hangs = true, delayMs = now() - start))
            }
        val delay = now() - start
        val (answer, none) = changed(result) to noFrame(result)
        if (answer != null || none || delay > 0) {
            keep(DeviceFact(call?.value, op, kind, changed = answer, noFrame = none, delayMs = delay))
        }
        return result
    }

    private fun keep(fact: DeviceFact) {
        kept += fact
    }
}

/**
 * Record mode (WP08 design §7): runs a [RecordingSource] through the product loop, then writes it
 * as a fixture: normalized (N1–N5), templates put back (N7), the device facts the log lacks, the
 * blobs, the sidecars and the current policy pin. The fixture is staged outside the sources and
 * scanned there with gitleaks; only then is it installed, and the privacy checklist
 * ([PrivacyChecklist]) is written next to the staged copy. A fixture is only committed after a
 * person went through that list.
 */
object SessionRecorder {
    /** The staged fixture's directory under `<work>/<name>/`. */
    const val STAGED: String = "fixture"

    /**
     * Records [source] in [scope]'s virtual time and installs it as the fixture in [dir], replacing
     * its fixture files. The fixture is first written to `<work>/<name>/fixture/` ([STAGED]; [work]
     * is under `build/`, never committed) and scanned there with [gitleaks] (`null`: not installed);
     * its checklist goes to `<work>/<name>/privacy-checklist.md`. [dir] is touched only when the
     * scan is clean, or when gitleaks is not installed and the source is authored with no keys
     * (a warning on stderr); the staged copy is left for review either way.
     *
     * Fails with [AssertionError] when the run did not use up its stimuli or failed, and with
     * [IllegalStateException], [dir] untouched, when gitleaks found something, failed itself, or
     * is not installed for a live source or one with keys.
     */
    suspend fun record(
        scope: TestScope,
        source: RecordingSource,
        dir: Path,
        work: Path,
        gitleaks: Path? = Gitleaks.onPath(),
    ) {
        val model = TemplatingModel(source.model())
        val scenario =
            Scenario(
                Scenario.FORMAT,
                source.recording,
                source.description,
                source.info,
                source.task,
                PolicyPins.current(PolicyPins.tuning(source.assembly.tuning)),
                source.assembly,
                source.device,
            )
        val now = { scope.testScheduler.currentTime }
        val harness =
            ReplayHarness(scope, scenario, model) { analyzer, current ->
                FactRecorder(source.screen(), analyzer, current, now)
            }
        val driver =
            SessionDriver(scope, harness, source.stimuli, TaskStarted(source.task.level, source.task.unattended))
        driver.run()
        if (harness.uncaught.isNotEmpty()) {
            throw AssertionError(
                "${source.name}: escaped the loop",
                harness.uncaught.first(),
            )
        }
        if (driver.left.isNotEmpty()) throw AssertionError("${source.name}: stimuli never given: ${driver.left}")
        val log = harness.log()
        val requests = harness.tape.requests
        val texts = FixtureTexts.normalized(log, requests, model.templates, source.secrets)
        val facts = (harness.screen as FactRecorder).facts
        val override =
            ReplayOverride(
                device =
                    facts.map { fact ->
                        fact.copy(
                            call = fact.call?.let { requireNotNull(texts.normalizer.tokenOf(FieldClass.CALL, it)) },
                        )
                    },
            )
        val staged = work.resolve(source.name).resolve(STAGED)
        FixtureTexts.write(staged, scenario, texts, override, blobs(harness.files, texts))
        val scan = Gitleaks.scan(staged, gitleaks)
        PrivacyChecklist.write(work.resolve(source.name), source.name, log, scenario, scan)
        check(scan !is Gitleaks.Scan.Found) {
            "${source.name}: gitleaks found secrets in the staged fixture $staged, $dir untouched:\n" +
                (scan as Gitleaks.Scan.Found).report
        }
        val scanOptional = source.recording == Recording.AUTHORED && source.secrets.isEmpty()
        check(scan != Gitleaks.Scan.NotInstalled || scanOptional) {
            "${source.name}: gitleaks is not installed; a ${source.recording.name.lowercase()} recording" +
                " with ${source.secrets.size} key(s) is installed only after a scan, $dir untouched"
        }
        FixtureTexts.install(staged, dir)
    }

    private suspend fun blobs(
        files: SessionFiles,
        texts: FixtureTexts.Normalized,
    ): Map<BlobRef, ByteArray> =
        FixtureTexts.referenced(texts.session, texts.requests).associateWith { ref ->
            requireNotNull(files.blobs.get(ref)) { "blob ${ref.value} is referenced but was never stored" }
        }
}
