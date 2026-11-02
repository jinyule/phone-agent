package dev.phoneagent.app.adapters.screen

import android.accessibilityservice.AccessibilityService
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * [ScreenPort] over the accessibility service (phase1 §3.3), for any display: everything is keyed by
 * [surface]'s display id; M1 verifies it on the main display.
 *
 * - snapshots read the display's windows ([ScreenReader]); ids come from [Options.snapshotIds],
 *   refs are carried from screen to screen ([RefCarrier]), quality and fingerprint come from the
 *   [SnapshotAnalyzer];
 * - actions re-read the tree first and find ref targets again with the same identity ([RefMatcher]);
 *   a target that no longer matches yields `StaleRef` without touching the device;
 * - after an action the display settles on accessibility events ([ChangeWatch]) and is read back
 *   at the detail of the screen the action was based on;
 * - nothing here touches this app's own windows (`Refused`);
 * - FLAG_SECURE (API 34+): before every frame the top application window is captured alone; a
 *   window holding secure content gives no frame and is remembered ([SecureWindows]), so its
 *   snapshots carry `ScreenFlag.SECURE`. What to do about it is PolicyEngine's call. Below API 34
 *   secure content is **not detected**: frames come back with it blacked out and snapshots never
 *   carry `SECURE`.
 *
 * Calls are serialized by the caller (ScreenPort contract).
 *
 * @param connection the current accessibility connection, `null` while it is off.
 */
internal class AndroidScreen(
    override val surface: SurfaceRef,
    private val connection: () -> ScreenHost?,
    system: ScreenSystem,
    analyzer: SnapshotAnalyzer,
    private val options: Options = Options(),
) : ScreenPort {
    /**
     * Construction options.
     *
     * @property timings waits and gesture durations.
     * @property snapshotIds source of snapshot ids, unique within the task (seed it when resuming).
     * @property injector privileged input, the last fallback (M2); `null` without the helper.
     * @property io dispatcher for the blocking accessibility calls.
     */
    class Options(
        val timings: ScreenTimings = ScreenTimings(),
        val snapshotIds: () -> SnapshotId = sequentialSnapshotIds(),
        val injector: InputInjector? = null,
        val io: CoroutineDispatcher = Dispatchers.IO,
    )

    private val displayId = surface.displayId
    private val parts =
        PortParts(
            surface = surface,
            system = system,
            analyzer = analyzer,
            observer =
                ScreenObserver(
                    surface,
                    system,
                    analyzer,
                    options.snapshotIds,
                    SnapshotHistory(options.timings.snapshotsKept),
                ),
            timings = options.timings,
            injector = options.injector,
        )

    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot =
        withContext(this.options.io) {
            val host = host()
            parts.observer.observe(host, geometry(host), options.detail)
        }

    override suspend fun act(
        action: UiAction,
        on: SnapshotId?,
    ): ActionOutcome =
        withContext(options.io) {
            val host = host()
            val geometry = geometry(host)
            val planned = on?.let(parts.observer.history::get)
            // The screen the action was planned on; only without one named, the latest. A named
            // screen that was forgotten gives no diff rather than a diff against another screen.
            val base = if (on == null) parts.observer.history.newest() else planned
            val baseline = parts.observer.baseline(host, geometry, base?.detail ?: SnapshotDetail.BRIEF)
            if (action is UiAction.SetWheel) {
                Outcomes.failed("set_wheel is not supported on this screen yet")
            } else {
                when (val resolved = Aims.resolve(targetOf(action), on, planned, baseline.read, geometry)) {
                    is Resolution.Aimed -> {
                        ActionRun(parts, host, geometry, baseline, base?.snapshot).perform(action, resolved.aim)
                    }

                    Resolution.Stale -> {
                        Outcomes.STALE
                    }

                    is Resolution.Invalid -> {
                        Outcomes.failed(resolved.reason)
                    }
                }
            }
        }

    override suspend fun launch(target: LaunchTarget): ActionOutcome =
        withContext(options.io) {
            val host = host()
            LaunchRun(parts, host, geometry(host), base = parts.observer.history.newest()).launch(target)
        }

    override suspend fun frame(
        maxEdge: Int,
        som: Boolean,
    ): Frame? {
        require(maxEdge > 0) { "maxEdge must be > 0, got $maxEdge" }
        return withContext(options.io) {
            val host = host()
            geometry(host)
            val waitMs = options.timings.screenshotWaitMs
            if (parts.observer.secure.probe(host, host.windowsOn(displayId), waitMs)) return@withContext null
            // Set-of-Mark boxes arrive with OCR in M3; until then the frame reports som = false.
            when (val shot = screenshotWithin(waitMs) { host.screenshot(displayId) } ?: TIMED_OUT) {
                is Screenshot.Captured -> {
                    try {
                        FrameEncoder.encode(shot.bitmap, maxEdge)
                    } finally {
                        shot.bitmap.recycle()
                    }
                }

                Screenshot.Secure -> {
                    null
                }

                is Screenshot.Failed -> {
                    unavailableIfDisplayGone(shot.code)
                }
            }
        }
    }

    override suspend fun awaitChange(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0) { "timeoutMs must be >= 0, got $timeoutMs" }
        val host = host()
        return ChangeWatch.watching(host.events, displayId, options.timings) { watch ->
            // Subscribed first, so nothing is missed while the bars are looked up.
            watch.ignore(withContext(options.io) { systemBarsOf(host) })
            watch.awaitSettled(timeoutMs)
        }
    }

    private fun systemBarsOf(host: ScreenHost): Set<Int> {
        val geometry = geometry(host)
        return host
            .windowsOn(displayId)
            .filter { ScreenReader.isSystemBar(it, geometry, parts.system.ownPackage) }
            .map { it.id }
            .toSet()
    }

    private fun host(): ScreenHost =
        connection() ?: throw ScreenUnavailableException("the accessibility service is not connected")

    private fun geometry(host: ScreenHost): DisplayGeometry {
        val size = host.displaySize(displayId) ?: throw ScreenUnavailableException("display $displayId does not exist")
        return DisplayGeometry(size.width, size.height)
    }

    private fun unavailableIfDisplayGone(code: Int): Frame? {
        if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY) {
            throw ScreenUnavailableException("display $displayId cannot be captured")
        }
        return null
    }

    internal companion object {
        /** What a screenshot that did not come in time counts as. */
        private val TIMED_OUT = Screenshot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)

        /** Snapshot ids `s1`, `s2`, … starting at [first]. */
        fun sequentialSnapshotIds(first: Long = 1): () -> SnapshotId {
            val next = AtomicLong(first)
            return { SnapshotId("s${next.getAndIncrement()}") }
        }

        private fun targetOf(action: UiAction): Target? =
            when (action) {
                is UiAction.Tap -> action.target
                is UiAction.Type -> action.target
                is UiAction.Scroll -> action.target
                is UiAction.Swipe -> action.from
                is UiAction.SetWheel -> action.target
                is UiAction.Key -> null
            }
    }
}
