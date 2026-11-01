package dev.phoneagent.core.testing

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UiElement

/**
 * A [ScreenPort] that plays a script: [snapshot] returns the queued snapshots in order and then
 * keeps returning the last one; actions and launches are recorded and answered with the queued
 * outcomes, else "performed, changed" with the next snapshot as `after`. Shared by all core tests;
 * the replay screen of the regression harness builds on it.
 */
class ScriptedScreen(
    vararg snapshots: ScreenSnapshot,
    override val surface: SurfaceRef = SURFACE,
) : ScreenPort {
    private val snapshots = ArrayDeque(snapshots.toList())
    private val outcomes = ArrayDeque<ActionOutcome>()

    /** Every action with the snapshot it was planned on. */
    val actions = mutableListOf<Pair<UiAction, SnapshotId?>>()

    /** Every launch. */
    val launches = mutableListOf<LaunchTarget>()

    /** Returned by [frame]. */
    var frame: Frame? = null

    /** Returned by [awaitChange]. */
    var changes: Boolean = true

    /** When set, [snapshot] throws [ScreenUnavailableException] with this message. */
    var unavailable: String? = null

    /** Queues [outcome] for the next action or launch. */
    fun answers(outcome: ActionOutcome): ScriptedScreen = apply { outcomes.addLast(outcome) }

    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot {
        unavailable?.let { throw ScreenUnavailableException(it) }
        return next()
    }

    override suspend fun act(
        action: UiAction,
        on: SnapshotId?,
    ): ActionOutcome {
        actions += action to on
        return outcomes.removeFirstOrNull() ?: performed()
    }

    override suspend fun launch(target: LaunchTarget): ActionOutcome {
        launches += target
        return outcomes.removeFirstOrNull() ?: performed()
    }

    override suspend fun frame(
        maxEdge: Int,
        som: Boolean,
    ): Frame? = frame

    override suspend fun awaitChange(timeoutMs: Long): Boolean = changes

    private fun performed(): ActionOutcome =
        ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.CHANGED, next())

    private fun next(): ScreenSnapshot {
        check(snapshots.isNotEmpty()) { "ScriptedScreen has no snapshot" }
        return if (snapshots.size > 1) snapshots.removeFirst() else snapshots.first()
    }

    companion object {
        val SURFACE = SurfaceRef(7, SurfaceKind.AGENT)

        /** A minimal snapshot [id] of [pkg]. */
        fun snapshotOf(
            id: String,
            pkg: String? = "com.example.chat",
            elements: List<UiElement> = emptyList(),
            ocr: List<OcrLine>? = null,
            flags: Set<ScreenFlag> = emptySet(),
        ): ScreenSnapshot =
            ScreenSnapshot(
                id = SnapshotId(id),
                surface = SURFACE,
                pkg = pkg,
                activity = ".Main",
                elements = elements,
                ocr = ocr,
                quality = TreeQuality.GOOD,
                flags = flags,
                fingerprint = "fp-$id",
            )
    }
}
