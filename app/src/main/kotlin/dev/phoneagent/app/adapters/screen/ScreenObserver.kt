package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SurfaceRef

/** Builds [ScreenSnapshot]s of one surface and remembers them in [history]. */
internal class ScreenObserver(
    private val surface: SurfaceRef,
    private val system: ScreenSystem,
    private val analyzer: SnapshotAnalyzer,
    private val snapshotIds: () -> SnapshotId,
    val history: SnapshotHistory,
) {
    // Highest ref number handed out by this port (RefCarrier never reuses one).
    private var lastRef = 0

    /** Reads the display and registers the result as a new snapshot; refs carry over from the latest one. */
    fun observe(
        host: ScreenHost,
        geometry: DisplayGeometry,
        detail: SnapshotDetail,
    ): ScreenSnapshot {
        val read = ScreenReader.read(host.windowsOn(surface.displayId), geometry, detail)
        val carried = RefCarrier.carry(history.newest()?.elements, read.elements.map { it.element }, lastRef)
        lastRef = carried.lastUsed
        val elements = carried.elements
        val activity = activityOf(host, read.pkg)
        val flags = flagsOf(read)
        val snapshot =
            ScreenSnapshot(
                id = snapshotIds(),
                surface = surface,
                pkg = read.pkg,
                activity = activity,
                elements = elements,
                ocr = null,
                quality = analyzer.quality(elements, surface, flags),
                flags = flags,
                fingerprint = analyzer.fingerprint(read.pkg, activity, elements, ocr = null),
            )
        history.put(snapshot)
        return snapshot
    }

    /** Reads the display before an action, keeping what the effect is judged against. Not registered. */
    fun baseline(
        host: ScreenHost,
        geometry: DisplayGeometry,
    ): Baseline {
        val read = ScreenReader.read(host.windowsOn(surface.displayId), geometry, SnapshotDetail.BRIEF)
        val elements = read.elements.map { it.element }
        return Baseline(read, analyzer.fingerprint(read.pkg, activityOf(host, read.pkg), elements, ocr = null))
    }

    private fun flagsOf(read: TreeRead): Set<ScreenFlag> =
        buildSet {
            if (read.keyboard) add(ScreenFlag.KEYBOARD)
            if (read.pkg != null && read.pkg in system.pickerPackages()) add(ScreenFlag.SYSTEM_PICKER)
            if (system.locked()) add(ScreenFlag.LOCKED)
        }

    /** The activity behind [pkg]'s latest window-state change on this display, when it is an activity. */
    private fun activityOf(
        host: ScreenHost,
        pkg: String?,
    ): String? =
        pkg?.let {
            host
                .recentWindowStates(surface.displayId)
                .firstOrNull { event ->
                    event.packageName == pkg && event.className != null &&
                        system.isActivity(pkg, event.className)
                }?.className
        }
}

/** The display just before an action: the fresh [read] and its [fingerprint]. */
internal class Baseline(
    val read: TreeRead,
    val fingerprint: String,
) {
    /** Effect of the action that led from this baseline to [after]. */
    fun effectOf(after: ScreenSnapshot): ActionOutcome.Effect =
        when {
            after.pkg != null && after.pkg != read.pkg -> ActionOutcome.Effect.NEW_APP
            after.fingerprint != fingerprint -> ActionOutcome.Effect.CHANGED
            else -> ActionOutcome.Effect.NO_CHANGE
        }
}

/** Outcomes that do not depend on the run. */
internal object Outcomes {
    /** The device-side guard against operating this app's own UI. */
    val OWN_UI: ActionOutcome =
        ActionOutcome(
            ActionOutcome.Status.Refused("PhoneAgent does not operate its own screens"),
            ActionOutcome.Effect.UNKNOWN,
        )

    /** The planned element is gone. */
    val STALE: ActionOutcome = ActionOutcome(ActionOutcome.Status.StaleRef, ActionOutcome.Effect.UNKNOWN)

    /** Not attempted, or attempted without effect, for [reason] (English, for the model). */
    fun failed(reason: String): ActionOutcome =
        ActionOutcome(ActionOutcome.Status.Failed(reason), ActionOutcome.Effect.UNKNOWN)
}
