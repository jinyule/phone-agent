package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SurfaceRef

/**
 * Builds [ScreenSnapshot]s of one surface and remembers them in [history]; [secure] holds the
 * windows found to show FLAG_SECURE content, whose snapshots say so.
 */
internal class ScreenObserver(
    private val surface: SurfaceRef,
    private val system: ScreenSystem,
    private val analyzer: SnapshotAnalyzer,
    private val snapshotIds: () -> SnapshotId,
    val history: SnapshotHistory,
    val secure: SecureWindows = SecureWindows(),
) {
    // Highest ref number handed out by this port (RefCarrier never reuses one).
    private var lastRef = 0

    /** Reads the display and registers the result as a new snapshot; refs carry over from the latest one. */
    fun observe(
        host: ScreenHost,
        geometry: DisplayGeometry,
        detail: SnapshotDetail,
    ): ScreenSnapshot {
        val read = ScreenReader.read(host.windowsOn(surface.displayId), geometry)
        val carried = RefCarrier.carry(history.newest()?.tree, read.elements, lastRef)
        lastRef = carried.lastUsed
        val elements = read.project(carried.refs, detail)
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
        history.put(Observed(snapshot, detail, carried.tree))
        return snapshot
    }

    /**
     * Reads the display before an action, keeping what the effect is judged against: the screen
     * as [detail] shows it, which is also the detail of the read after the action. Not registered.
     */
    fun baseline(
        host: ScreenHost,
        geometry: DisplayGeometry,
        detail: SnapshotDetail,
    ): Baseline {
        val read = ScreenReader.read(host.windowsOn(surface.displayId), geometry)
        val elements = read.provisional(detail)
        return Baseline(read, detail, analyzer.fingerprint(read.pkg, activityOf(host, read.pkg), elements, ocr = null))
    }

    private fun flagsOf(read: TreeRead): Set<ScreenFlag> =
        buildSet {
            if (read.topWindow != null && read.topWindow in secure) add(ScreenFlag.SECURE)
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

/**
 * The display just before an action: the fresh [read] and the [fingerprint] of its projection at
 * [detail] (refs left out by the analyzer).
 */
internal class Baseline(
    val read: TreeRead,
    val detail: SnapshotDetail,
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
