package dev.phoneagent.privileged.cap

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Log
import dev.phoneagent.privileged.StartResult

/**
 * The one activity an intent starts, and whether the app could start it itself. The helper runs as
 * the shell user, which holds far more than the app; it must not let the app start what the app's
 * own uid could not (an `ACTION_CALL` without `CALL_PHONE`, another app's unexported activity).
 * Whether the agent should send the intent at all is the PolicyEngine's call, not this one.
 */
internal class LaunchTargets(
    private val context: Context,
    private val app: ServedApp,
) {
    /** What [check] found. */
    sealed interface Target {
        /** Start exactly [activity]. */
        data class Allowed(
            val activity: ComponentName,
        ) : Target

        /** Do not start anything; [result] is the [StartResult] to answer. */
        data class Refused(
            val result: Int,
            val reason: String,
        ) : Target
    }

    /**
     * Resolves [intent] to a single activity and checks that it is exported (or the app's own) and
     * that the app's uid holds the permission it requires. Several handlers are refused: the
     * helper cannot ask the user which one, and guessing could pick one the app did not mean.
     */
    fun check(intent: Intent): Target {
        val matches = resolve(intent).distinctBy { it.packageName to it.name }
        val only = matches.singleOrNull()
        return when {
            matches.isEmpty() -> Target.Refused(StartResult.NOT_FOUND, "nothing can handle $intent")
            only == null -> Target.Refused(StartResult.REFUSED, "${matches.size} activities can handle $intent")
            else -> permitted(only)
        }
    }

    private fun permitted(activity: ActivityInfo): Target {
        val component = ComponentName(activity.packageName, activity.name)
        val permission = activity.permission
        return when {
            !activity.exported && activity.packageName != app.packageName -> {
                Target.Refused(StartResult.REFUSED, "$component is not exported")
            }

            permission != null && !appHolds(permission) -> {
                Target.Refused(
                    StartResult.REFUSED,
                    "${app.packageName} does not hold $permission, required by $component",
                )
            }

            else -> {
                Target.Allowed(component)
            }
        }
    }

    private fun appHolds(permission: String): Boolean =
        context.checkPermission(permission, NO_PID, app.uid) == PackageManager.PERMISSION_GRANTED

    /**
     * The named component, or whatever resolves; a package with no matching activity may still
     * have a launcher entry.
     */
    private fun resolve(intent: Intent): List<ActivityInfo> {
        val component = intent.component
        val pkg = intent.`package`
        return when {
            component != null -> listOfNotNull(activity(component))
            pkg != null -> query(intent).ifEmpty { launcherEntry(pkg) }
            else -> query(intent)
        }
    }

    private fun activity(component: ComponentName): ActivityInfo? =
        try {
            context.packageManager.getActivityInfo(component, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(TAG, "$component does not exist", e)
            null
        }

    // The helper runs as the shell user, which sees every package; manifest <queries> would not
    // apply to it anyway (app_process is not this app's process).
    @SuppressLint("QueryPermissionsNeeded")
    private fun query(intent: Intent): List<ActivityInfo> =
        reflecting("PackageManager.queryIntentActivities") {
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo }
        }

    private fun launcherEntry(pkg: String): List<ActivityInfo> =
        listOfNotNull(
            context.packageManager
                .getLaunchIntentForPackage(pkg)
                ?.component
                ?.let(::activity),
        )

    private companion object {
        const val TAG = "PhoneAgentHelper"

        /** No particular process: the permission is checked for the uid alone. */
        const val NO_PID = -1
    }
}
