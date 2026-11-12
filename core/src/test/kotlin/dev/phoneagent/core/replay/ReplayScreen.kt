package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * The device of a replayed session (WP08 design §4), passed as `AgentSetup.screen`: each port call
 * takes the next step of the current call's queue ([DeviceScript]) and must be the call the
 * recording made there — same method, and for an action or launch the same action on the same
 * snapshot, or the same target. Then, after the step's delay in virtual time, it returns what was
 * recorded, throws what was recorded, or hangs until cancelled.
 *
 * Snapshots (and an outcome's `after`) get their fingerprint and quality from [analyzer], the
 * current one, so a change of the analysis shows in replay; diffs are given as recorded (they
 * depend on a tree re-read before the action, which the log does not hold).
 *
 * A mismatch fails with [AssertionError] and is kept in [problems], so it is reported even where
 * the runtime turns an error into a tool result.
 *
 * @param surface the recording's display ([Device.surface]).
 * @param currentCall the call being executed, from the replayed log ([OpenCall]).
 */
class ReplayScreen(
    override val surface: SurfaceRef,
    script: Map<ToolCallId?, List<DeviceStep>>,
    private val blobs: Map<BlobRef, ByteArray>,
    private val analyzer: SnapshotAnalyzer,
    private val currentCall: suspend () -> ToolCallId?,
) : ScreenPort {
    private val queues = script.mapValues { ArrayDeque(it.value) }
    private val failed = mutableListOf<String>()

    /** The mismatches so far. */
    val problems: List<String> get() = failed.toList()

    /**
     * A read must ask for the detail the recording's read was taken at (`ScreenSnapshot.detail`,
     * which the port fills in): a re-check that reads BRIEF where the recording read FULL sees
     * other elements. Steps from `replay.override.json` (a read that threw or hung) carry no
     * snapshot to compare with. The other option, `ocr`, is not in the log and is not compared.
     */
    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot {
        val step =
            next(DevicePort.SNAPSHOT) { recorded ->
                val read = (recorded.result as? DeviceResult.Snapshot)?.snapshot?.detail
                "the recording read ${read?.name} detail, replay ${options.detail.name}"
                    .takeIf { read != null && read != options.detail }
            }
        return finish(step) { analyzer.analyzed((it as DeviceResult.Snapshot).snapshot) }
    }

    override suspend fun act(
        action: UiAction,
        on: SnapshotId?,
    ): ActionOutcome {
        val step =
            next(DevicePort.ACT) { recorded ->
                "the recording did ${recorded.action} on ${recorded.on?.value}, replay $action on ${on?.value}"
                    .takeIf { recorded.action != action || recorded.on != on }
            }
        return finish(step) { analyzer.analyzed((it as DeviceResult.Outcome).outcome) }
    }

    override suspend fun launch(target: LaunchTarget): ActionOutcome {
        val step =
            next(DevicePort.LAUNCH) { recorded ->
                "the recording launched ${recorded.target}, replay $target".takeIf { recorded.target != target }
            }
        return finish(step) { analyzer.analyzed((it as DeviceResult.Outcome).outcome) }
    }

    override suspend fun frame(
        maxEdge: Int,
        som: Boolean,
    ): Frame? {
        val step = next(DevicePort.FRAME) { null }
        return finish(step) { result ->
            if (result == DeviceResult.NoFrame) {
                null
            } else {
                val frame = result as DeviceResult.Frame
                val bytes =
                    blobs[frame.blob]
                        ?: fail(
                            "frame of ${step.call?.value} op ${step.op}: " +
                                "blob ${frame.blob.value} is not in the fixture",
                        )
                Frame(bytes, frame.mime, frame.width, frame.height, frame.som)
            }
        }
    }

    /** A wait that saw no change takes its whole [timeoutMs]; one that saw a change takes the step's delay. */
    override suspend fun awaitChange(timeoutMs: Long): Boolean {
        val step = next(DevicePort.AWAIT_CHANGE) { null }
        val changed = (step.result as? DeviceResult.Changed)?.changed
        return finish(
            if (changed ==
                false
            ) {
                step.copy(delayMs = timeoutMs)
            } else {
                step
            },
        ) { (it as DeviceResult.Changed).changed }
    }

    /** Fails with [AssertionError] when a mismatch happened or a recorded port call was not made. */
    fun assertConsumed() {
        val left =
            queues.values.flatten().map {
                "${it.call?.value} op ${it.op}: ${it.port} (seq ${it.seq ?: "override"})"
            }
        val all = failed + left.map { "not replayed: $it" }
        if (all.isNotEmpty()) throw AssertionError("device replay:\n" + all.joinToString("\n"))
    }

    private suspend fun next(
        port: DevicePort,
        mismatch: (DeviceStep) -> String?,
    ): DeviceStep {
        val call = currentCall()
        val step =
            queues[call]?.removeFirstOrNull() ?: fail("${call?.value}: no recorded port call left, replay called $port")
        if (step.port != port) fail("${call?.value} op ${step.op}: the recording called ${step.port}, replay $port")
        mismatch(step)?.let { fail("${call?.value} op ${step.op}: $it") }
        return step
    }

    private suspend fun <T> finish(
        step: DeviceStep,
        value: (DeviceResult) -> T,
    ): T {
        delay(step.delayMs)
        return when (val result = step.result) {
            is DeviceResult.Throws -> throw ThrownTypes.build(result.thrown.type, result.thrown.message)
            DeviceResult.Hangs -> awaitCancellation()
            else -> value(result)
        }
    }

    private fun fail(problem: String): Nothing {
        failed += problem
        throw AssertionError("device replay: $problem")
    }

    companion object {
        /** The device of [fixture], driven for the calls [currentCall] names. */
        fun of(
            fixture: SessionFixture,
            analyzer: SnapshotAnalyzer,
            currentCall: suspend () -> ToolCallId?,
        ): ReplayScreen =
            ReplayScreen(
                fixture.scenario.device.surface,
                DeviceScript.of(fixture.log.records, fixture.override),
                fixture.blobs,
                analyzer,
                currentCall,
            )
    }
}

/**
 * [snapshot] as a real port returns it: fingerprint and quality from this analyzer. Recording
 * (`SessionRecorder`) and replay ([ReplayScreen]) both pass the device's snapshots through it, so
 * the log holds the current analysis and a change of it shows in replay.
 */
fun SnapshotAnalyzer.analyzed(snapshot: ScreenSnapshot): ScreenSnapshot =
    snapshot.copy(
        fingerprint = fingerprint(snapshot.pkg, snapshot.activity, snapshot.elements, snapshot.ocr),
        quality = quality(snapshot.elements, snapshot.surface, snapshot.flags),
    )

/** [outcome] with its `after` analyzed like a read ([analyzed]); the diff is kept as given. */
fun SnapshotAnalyzer.analyzed(outcome: ActionOutcome): ActionOutcome =
    outcome.copy(after = outcome.after?.let { analyzed(it) })
