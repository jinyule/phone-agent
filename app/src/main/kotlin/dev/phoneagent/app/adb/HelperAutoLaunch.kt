package dev.phoneagent.app.adb

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import dev.phoneagent.app.helper.HelperClient
import dev.phoneagent.app.helper.HelperState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Timing of automatic helper launches, validated on construction (AGENTS §4).
 *
 * @property orphanResumeMs at process start, how long a helper of the previous app process gets to
 *   hand off again with its resume nonce before a new one is launched. Such a helper notices the app
 *   at once and, when that fails, waits 1, 3 and 10 s between retries (WP12 `HelperLifecycle`): the
 *   default covers the tries at 0, 1 and 4 s. Launching at once would start a second helper next to
 *   it on every app restart. 0..[MAX_WAIT_MS].
 * @property handoffTimeoutMs after the launch command ran, how long to wait for the handoff,
 *   1..[MAX_WAIT_MS].
 * @property minLaunchIntervalMs the least time between the starts of two launches, so a helper that
 *   dies at once is not relaunched in a tight loop, 0..[MAX_WAIT_MS].
 * @property earlyExitWindowMs a helper that dies within this long after its handoff exited early,
 *   1..[MAX_WAIT_MS].
 * @property earlyExitBackoffMs the least time between the starts of two launches after 1, 2, …
 *   consecutive early exits; the last entry holds for all later ones. Not empty, non-decreasing,
 *   each [minLaunchIntervalMs]..[MAX_WAIT_MS].
 * @property maxEarlyExits after this many consecutive early exits no helper is launched by itself
 *   ([LaunchOutcome.TooManyEarlyExits]) until a pairing succeeds again, a helper outlives
 *   [earlyExitWindowMs], or the process restarts; 1..[MAX_EARLY_EXITS].
 */
internal data class LaunchConfig(
    val orphanResumeMs: Long = 5_000,
    val handoffTimeoutMs: Long = 15_000,
    val minLaunchIntervalMs: Long = 10_000,
    val earlyExitWindowMs: Long = 30_000,
    val earlyExitBackoffMs: List<Long> = DEFAULT_EARLY_EXIT_BACKOFF_MS,
    val maxEarlyExits: Int = 5,
) {
    init {
        require(orphanResumeMs in 0..MAX_WAIT_MS) { "orphanResumeMs must be 0..$MAX_WAIT_MS, got $orphanResumeMs" }
        require(
            handoffTimeoutMs in 1..MAX_WAIT_MS,
        ) { "handoffTimeoutMs must be 1..$MAX_WAIT_MS, got $handoffTimeoutMs" }
        require(minLaunchIntervalMs in 0..MAX_WAIT_MS) {
            "minLaunchIntervalMs must be 0..$MAX_WAIT_MS, got $minLaunchIntervalMs"
        }
        require(earlyExitWindowMs in 1..MAX_WAIT_MS) {
            "earlyExitWindowMs must be 1..$MAX_WAIT_MS, got $earlyExitWindowMs"
        }
        require(
            earlyExitBackoffMs.isNotEmpty() &&
                earlyExitBackoffMs.all { it in minLaunchIntervalMs..MAX_WAIT_MS } &&
                earlyExitBackoffMs.zipWithNext().all { (shorter, longer) -> shorter <= longer },
        ) {
            "earlyExitBackoffMs must be non-empty, non-decreasing and within " +
                "$minLaunchIntervalMs..$MAX_WAIT_MS, got $earlyExitBackoffMs"
        }
        require(maxEarlyExits in 1..MAX_EARLY_EXITS) {
            "maxEarlyExits must be 1..$MAX_EARLY_EXITS, got $maxEarlyExits"
        }
    }

    /** The least time between launch starts after [earlyExits] consecutive early exits. */
    fun intervalAfter(earlyExits: Int): Long =
        if (earlyExits == 0) minLaunchIntervalMs else earlyExitBackoffMs[minOf(earlyExits, earlyExitBackoffMs.size) - 1]

    companion object {
        const val MAX_WAIT_MS: Long = 120_000
        const val MAX_EARLY_EXITS: Int = 100
        val DEFAULT_EARLY_EXIT_BACKOFF_MS: List<Long> = listOf(10_000, 30_000, 120_000)
    }
}

/**
 * Launches the helper by itself (phase1 §2.2):
 * - when the helper client reports [HelperState.NeedsLaunch] (the helper died, or a stale one was
 *   turned away), also when that happened before [start];
 * - at process start, when still no helper is connected after [LaunchConfig.orphanResumeMs].
 *
 * Never inside the listener: HelperClient may call listeners under its admission lock, and a launch
 * waits for a handoff, which needs that lock. Requests only start a coroutine in [scope]. One launch
 * runs at a time; requests that arrive meanwhile make one more run after it, and a run does nothing
 * when a helper is connected by then. Runs start at least [LaunchConfig.minLaunchIntervalMs] apart
 * (by [clock]). A failed launch is logged and not retried until the next request: a helper state
 * change, or [paired] ([startIfPaired] after a pairing succeeded again).
 *
 * A helper that dies within [LaunchConfig.earlyExitWindowMs] of its handoff (by anyone) exited
 * early. Consecutive early exits stretch the time between launch starts
 * ([LaunchConfig.earlyExitBackoffMs]), and at [LaunchConfig.maxEarlyExits] no launch is made: each
 * request is logged as [LaunchOutcome.TooManyEarlyExits] instead. The count starts over when a
 * helper dies after the window, at [paired], and with the process.
 */
internal class HelperAutoLaunch(
    private val helper: HelperLauncher.Helper,
    private val launch: suspend () -> LaunchOutcome,
    private val scope: CoroutineScope,
    private val config: LaunchConfig,
    private val clock: () -> Long,
) {
    private val lock = Any()
    private var running = false
    private var again = false
    private var lastStart: Long? = null

    /** When the helper now connected handed off; null while none is. Guarded by [lock]. */
    private var handedOffAt: Long? = null

    /** Consecutive early exits. Guarded by [lock]. */
    private var earlyExits = 0

    /** Starts watching; closing the result stops it (a launch already running finishes). */
    fun start(): AutoCloseable {
        val listening = helper.addListener(::changed)
        // A helper turned away before the listener was added (it hands off as this process
        // starts) is reported to no one.
        if (helper.state is HelperState.NeedsLaunch) requestLaunch()
        val startup =
            scope.launch {
                delay(config.orphanResumeMs)
                if (helper.state !is HelperState.Connected) requestLaunch()
            }
        return AutoCloseable {
            listening.close()
            startup.cancel()
        }
    }

    private fun changed(state: HelperState) {
        if (state is HelperState.Connected) synchronized(lock) { handedOffAt = clock() }
        if (state is HelperState.NeedsLaunch) {
            exited(state.reason)
            requestLaunch()
        }
    }

    /** Counts an early exit of the connected helper, or starts the count over after a later one. */
    private fun exited(reason: HelperState.LaunchReason) {
        synchronized(lock) {
            val since = handedOffAt ?: return
            handedOffAt = null
            // A stale helper turned away is not the connected one dying.
            if (reason != HelperState.LaunchReason.HelperDied) return
            earlyExits = if (clock() - since < config.earlyExitWindowMs) earlyExits + 1 else 0
        }
    }

    /** A pairing succeeded: the early-exit count starts over, and a launch is requested at once. */
    internal fun paired() {
        synchronized(lock) { earlyExits = 0 }
        requestLaunch()
    }

    /**
     * Requests a launch: one runs now, or after the one running, at least
     * [LaunchConfig.intervalAfter] the early exits so far after the previous start; it does nothing
     * when a helper is connected by then. Never waits; safe to call from any thread, also under the
     * helper client's lock.
     */
    internal fun requestLaunch() {
        synchronized(lock) {
            if (running) {
                again = true
                return
            }
            running = true
        }
        scope.launch {
            var more = true
            try {
                while (more) {
                    launchUnlessConnected()
                    more =
                        synchronized(lock) {
                            again.also {
                                again = false
                                running = it
                            }
                        }
                }
            } finally {
                // Cancelled or failed inside a run: requests queued behind it are dropped, and the
                // next request starts afresh.
                if (more) {
                    synchronized(lock) {
                        running = false
                        again = false
                    }
                }
            }
        }
    }

    private suspend fun launchUnlessConnected() {
        val exits = synchronized(lock) { earlyExits }
        if (exits >= config.maxEarlyExits) {
            Log.w(TAG, "helper launch: ${LaunchOutcome.TooManyEarlyExits(exits)}")
            return
        }
        lastStart?.let { delay(it + config.intervalAfter(exits) - clock()) }
        if (helper.state is HelperState.Connected) return
        lastStart = clock()
        Log.i(TAG, "helper launch: ${launchLogged()}")
    }

    private suspend fun launchLogged(): LaunchOutcome? =
        try {
            launch()
        } catch (failed: IOException) {
            // The identity store failed; the next request tries again.
            Log.e(TAG, "helper launch failed", failed)
            null
        }

    companion object {
        private const val TAG = "PhoneAgentHelperLaunch"

        private val slot = AutoLaunchSlot()

        /**
         * When the built-in client is supported and paired: the first call starts automatic launches
         * in this process, and every later one requests a launch at once ([paired], so still
         * [LaunchConfig.minLaunchIntervalMs] after the previous start). Otherwise does nothing (the
         * developer path stays `scripts/start-helper.sh`). Called at process start and again after
         * a pairing succeeds: the user paired again after NotPaired or Unauthorized, and nothing
         * else would request the launch, since the helper state does not change.
         */
        fun startIfPaired(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val adb = BuiltinAdb.of(context)
            if (!adb.isPaired) return
            if (slot.startOrRequest { create(context.applicationContext, adb) }) {
                Log.i(TAG, "automatic helper launches on")
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun create(
            app: Context,
            adb: BuiltinAdb,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): HelperAutoLaunch {
            val config = LaunchConfig()
            val helper = helperOf(HelperClient.of(app))
            val command = HelperLaunchCommand.of(app.applicationInfo.sourceDir, app.packageName)
            val launcher = HelperLauncher(adb, helper, command, config.handoffTimeoutMs)
            val logFailures = CoroutineExceptionHandler { _, failure -> Log.e(TAG, "helper launch crashed", failure) }
            val scope = CoroutineScope(SupervisorJob() + dispatcher + logFailures)
            return HelperAutoLaunch(helper, launcher::launch, scope, config, SystemClock::elapsedRealtime)
        }

        /** The app's helper client as the launcher sees it. */
        fun helperOf(client: HelperClient): HelperLauncher.Helper =
            object : HelperLauncher.Helper {
                override val state: HelperState get() = client.state

                override fun addListener(listener: (HelperState) -> Unit): AutoCloseable = client.addListener(listener)

                override fun issueLaunchNonce(): String = client.issueLaunchNonce()
            }
    }
}

/** Holds the process's one [HelperAutoLaunch]; [startOrRequest] makes and starts it on first use. */
internal class AutoLaunchSlot {
    private var started: HelperAutoLaunch? = null

    /**
     * Starts what [create] makes, the first time, and returns `true`; afterwards tells that instance
     * a pairing succeeded ([HelperAutoLaunch.paired]: a launch is requested at once) and returns
     * `false`. It runs for the rest of the process: nothing closes it.
     */
    @Synchronized
    fun startOrRequest(create: () -> HelperAutoLaunch): Boolean {
        started?.let {
            it.paired()
            return false
        }
        started = create().also { it.start() }
        return true
    }
}
