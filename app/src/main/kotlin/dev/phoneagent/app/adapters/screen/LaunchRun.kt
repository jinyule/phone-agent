package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.SnapshotDetail

/**
 * One `ScreenPort.launch`: build the whitelisted intent, start it on the surface's display
 * (`ScreenSystem.start` passes the launch display id), wait for the expected app to reach the
 * front, settle and read back at the detail of [base] (the latest screen, `null` before any), and
 * diff [base] with the screen after.
 */
internal class LaunchRun(
    private val parts: PortParts,
    private val host: ScreenHost,
    private val geometry: DisplayGeometry,
    private val base: Observed?,
) {
    private val displayId = parts.surface.displayId
    private val timings = parts.timings

    /** Opens [target]. */
    suspend fun launch(target: LaunchTarget): ActionOutcome {
        val built =
            when (target) {
                is LaunchTarget.App -> LaunchIntents.app(target, parts.system::launchIntent)
                is LaunchTarget.Link -> LaunchIntents.link(target)
                is LaunchTarget.SystemIntent -> LaunchIntents.system(target)
            }
        return when (built) {
            is LaunchIntents.Built.Invalid -> {
                Outcomes.failed(built.reason)
            }

            is LaunchIntents.Built.Ready -> {
                if ((built.expectPackage ?: parts.system.resolvedPackage(built.intent)) == parts.system.ownPackage) {
                    Outcomes.OWN_UI
                } else {
                    start(target, built)
                }
            }
        }
    }

    private suspend fun start(
        target: LaunchTarget,
        built: LaunchIntents.Built.Ready,
    ): ActionOutcome {
        val before = parts.observer.baseline(host, geometry, base?.detail ?: SnapshotDetail.BRIEF)
        return ChangeWatch.watching(host.events, displayId, timings) { watch ->
            watch.ignore(before.read.systemBars)
            when (val started = parts.system.start(built.intent, displayId)) {
                StartResult.Started -> arrive(target, before, watch)
                StartResult.NotFound -> Outcomes.failed("no app on this device opens it")
                is StartResult.Denied -> Outcomes.failed("the system refused to open it: ${started.detail}")
            }
        }
    }

    private suspend fun arrive(
        target: LaunchTarget,
        before: Baseline,
        watch: ChangeWatch,
    ): ActionOutcome {
        val arrived =
            watch.awaitUntil(timings.launchWaitMs) {
                val top = ScreenReader.topPackage(host.windowsOn(displayId))
                if (target is LaunchTarget.App) top == target.packageName else top != before.read.pkg
            }
        watch.awaitSettled(timings.settleCapMs)
        val after = parts.observer.observe(host, geometry, before.detail)
        val status =
            if (target is LaunchTarget.App && !arrived) {
                ActionOutcome.Status.Failed(
                    "${target.packageName} did not come to the front within ${timings.launchWaitMs} ms",
                )
            } else {
                ActionOutcome.Status.Ok
            }
        val diff = base?.let { parts.analyzer.diff(it.snapshot, after) }
        return ActionOutcome(status, before.effectOf(after), after = after, diff = diff)
    }
}
