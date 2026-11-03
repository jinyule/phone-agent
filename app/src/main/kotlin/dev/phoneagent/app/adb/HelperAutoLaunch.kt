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
 */
internal data class LaunchConfig(
    val orphanResumeMs: Long = 5_000,
    val handoffTimeoutMs: Long = 15_000,
    val minLaunchIntervalMs: Long = 10_000,
) {
    init {
        require(orphanResumeMs in 0..MAX_WAIT_MS) { "orphanResumeMs must be 0..$MAX_WAIT_MS, got $orphanResumeMs" }
        require(
            handoffTimeoutMs in 1..MAX_WAIT_MS,
        ) { "handoffTimeoutMs must be 1..$MAX_WAIT_MS, got $handoffTimeoutMs" }
        require(minLaunchIntervalMs in 0..MAX_WAIT_MS) {
            "minLaunchIntervalMs must be 0..$MAX_WAIT_MS, got $minLaunchIntervalMs"
        }
    }

    companion object {
        const val MAX_WAIT_MS: Long = 120_000
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
 * (by [clock]). A failed launch is logged and not retried until the next request.
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

    /** Starts watching; closing the result stops it (a launch already running finishes). */
    fun start(): AutoCloseable {
        val listening = helper.addListener { state -> if (state is HelperState.NeedsLaunch) request() }
        // A helper turned away before the listener was added (it hands off as this process
        // starts) is reported to no one.
        if (helper.state is HelperState.NeedsLaunch) request()
        val startup =
            scope.launch {
                delay(config.orphanResumeMs)
                if (helper.state !is HelperState.Connected) request()
            }
        return AutoCloseable {
            listening.close()
            startup.cancel()
        }
    }

    private fun request() {
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
        lastStart?.let { delay(it + config.minLaunchIntervalMs - clock()) }
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

        @Volatile
        private var started: AutoCloseable? = null

        /**
         * Starts automatic launches in this process, once, when the built-in client is supported and
         * paired; otherwise does nothing (the developer path stays `scripts/start-helper.sh`). Called
         * at process start and again after a pairing succeeds.
         */
        fun startIfPaired(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val adb = BuiltinAdb.of(context)
            if (!adb.isPaired) return
            synchronized(this) {
                if (started == null) {
                    started = create(context.applicationContext, adb).start()
                    Log.i(TAG, "automatic helper launches on")
                }
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
