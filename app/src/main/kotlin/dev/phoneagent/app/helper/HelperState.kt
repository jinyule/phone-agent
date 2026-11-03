package dev.phoneagent.app.helper

import dev.phoneagent.privileged.IPrivilegedCore
import dev.phoneagent.privileged.handoff.HandoffReject

/** Connection to the shell-uid helper, as seen by this app process. */
sealed interface HelperState {
    /** Name reported by the `status` provider call. */
    val name: String

    /** No helper has handed off to this process yet. */
    data object NotConnected : HelperState {
        override val name: String = "NOT_CONNECTED"
    }

    /** [core] is live and was verified at handoff (uid 2000, [pid], protocol, APK). */
    data class Connected(
        val core: IPrivilegedCore,
        val pid: Int,
    ) : HelperState {
        override val name: String = "CONNECTED"
    }

    /**
     * The helper went away and has to be launched again: by HelperAutoLaunch once the built-in adb
     * client is paired, else with `scripts/start-helper.sh`.
     */
    data class NeedsLaunch(
        val reason: LaunchReason,
        /** pid of the helper that went away. */
        val lastPid: Int,
    ) : HelperState {
        override val name: String = "NEEDS_LAUNCH"
    }

    /** Why a new helper has to be launched. */
    sealed interface LaunchReason {
        /** The connected helper process died. */
        data object HelperDied : LaunchReason

        /** A helper running other code offered itself and was told to release and exit. */
        data class Stale(
            val reject: HandoffReject,
        ) : LaunchReason
    }
}
