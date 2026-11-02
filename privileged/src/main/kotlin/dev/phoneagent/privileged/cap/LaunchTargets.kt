package dev.phoneagent.privileged.cap

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import dev.phoneagent.privileged.StartResult

/** Why a start was refused; [result] is the [StartResult] it answers. */
internal enum class LaunchRefusal(
    val result: Int,
) {
    /** No intent, a display that is not the agent's, or another user. */
    BAD_REQUEST(StartResult.REFUSED),

    /** The intent may carry another intent ([NestedIntents]). */
    NESTED_INTENT(StartResult.REFUSED),

    /** No activity handles the intent. */
    NOT_FOUND(StartResult.NOT_FOUND),

    /** More than one activity handles it; the helper cannot ask the user which. */
    SEVERAL_HANDLERS(StartResult.REFUSED),

    /** The target's package cannot be named on a command line ([PackageNames]). */
    BAD_PACKAGE_NAME(StartResult.REFUSED),

    /** Another app's activity that is not exported. */
    NOT_EXPORTED(StartResult.REFUSED),

    /** The activity requires a permission the app's uid does not hold. */
    PERMISSION_NOT_HELD(StartResult.REFUSED),

    /** The app op of a permission the start needs is not allowed for the app, or cannot be read. */
    APP_OP_DENIED(StartResult.REFUSED),

    /** The action needs a runtime permission the app requests but does not hold. */
    ACTION_PERMISSION_NOT_HELD(StartResult.REFUSED),
}

/**
 * The one activity an intent starts, and whether the app could start it itself. The helper runs as
 * the shell user, which holds far more than the app; what it starts for the app must pass the
 * checks the activity manager makes when the app calls `startActivity` itself
 * (`ActivityTaskSupervisor.checkStartAnyActivityPermission`), for the app's uid:
 * - the activity, as the package manager reports it, is the app's own (same app id) or exported.
 *   For an activity-alias that is the alias itself, with its own `exported` and permission;
 * - another app's activity requires no permission, or one the app's uid holds. The permission is
 *   `ActivityInfo.permission` as the package manager resolved it, the one the activity manager
 *   checks: an activity that declares none has its application's, one that declares an empty one
 *   (`android:permission=""`) has none, and an alias has only its own;
 * - the app op of that permission, if it has one, is allowed for the app (`MODE_ALLOWED`; the
 *   activity manager refuses any other mode too, `MODE_DEFAULT` included). An op the helper may
 *   not read refuses;
 * - an action the platform ties to a runtime permission (ACTION_CALL, image and video capture)
 *   is refused when the app requests that permission but does not hold it, or its app op is denied.
 * And what it starts carries nothing of the shell's: the URI-grant flags are cleared, so neither
 * its data nor its ClipData is opened to the target with the shell's permissions.
 * Whether the agent should send the intent at all is the PolicyEngine's call, not this one.
 */
internal class LaunchTargets(
    private val context: Context,
    private val app: ServedApp,
) {
    private val grants = AppGrants(context, app)

    /** What [check] found. */
    sealed interface Target {
        /**
         * Start [intent]: the request with its component set to the activity that was checked, its
         * URI-grant flags cleared, and [Intent.FLAG_ACTIVITY_NEW_TASK] set (it is the root of a new
         * task on the agent display). Every start path takes this, so none starts what was not checked.
         */
        class Allowed internal constructor(
            val intent: Intent,
        ) : Target {
            val activity: ComponentName get() = checkNotNull(intent.component)
        }

        /** Start nothing; [detail] is for logcat. */
        data class Refused(
            val reason: LaunchRefusal,
            val detail: String,
        ) : Target
    }

    /**
     * Resolves [intent] to a single activity and checks it as above. Several handlers are refused:
     * the helper cannot ask the user which one, and guessing could pick one the app did not mean.
     */
    fun check(intent: Intent): Target {
        val matches = resolve(intent).distinctBy { it.packageName to it.name }
        val only = matches.singleOrNull()
        return when {
            matches.isEmpty() -> {
                Target.Refused(LaunchRefusal.NOT_FOUND, "nothing can handle $intent")
            }

            only == null -> {
                Target.Refused(LaunchRefusal.SEVERAL_HANDLERS, "${matches.size} activities can handle $intent")
            }

            else -> {
                refusal(only, intent.action) ?: Target.Allowed(pinned(intent, only))
            }
        }
    }

    /** Why the app's uid could not start [activity] itself with [action], or null. */
    private fun refusal(
        activity: ActivityInfo,
        action: String?,
    ): Target.Refused? {
        val component = ComponentName(activity.packageName, activity.name)
        val own = activity.applicationInfo?.uid?.let(app::isSameApp) == true
        val permission = activity.permission
        val actionPermission = ACTION_PERMISSIONS[action]?.takeIf(grants::requests)
        // A cold start names the package on the `am force-stop` command line.
        val nameProblem = PackageNames.problem(context, activity.packageName)
        return when {
            nameProblem != null -> {
                Target.Refused(LaunchRefusal.BAD_PACKAGE_NAME, nameProblem)
            }

            !own && !activity.exported -> {
                Target.Refused(LaunchRefusal.NOT_EXPORTED, "$component is not exported")
            }

            !own && permission != null && !grants.holds(permission) -> {
                Target.Refused(LaunchRefusal.PERMISSION_NOT_HELD, "${app.packageName} lacks $permission for $component")
            }

            permission != null && !grants.opAllowed(permission) -> {
                Target.Refused(LaunchRefusal.APP_OP_DENIED, "the app op of $permission is not allowed for $component")
            }

            actionPermission != null && !grants.holds(actionPermission) -> {
                Target.Refused(LaunchRefusal.ACTION_PERMISSION_NOT_HELD, "$action needs $actionPermission")
            }

            actionPermission != null && !grants.opAllowed(actionPermission) -> {
                Target.Refused(
                    LaunchRefusal.APP_OP_DENIED,
                    "the app op of $actionPermission is not allowed for $action",
                )
            }

            else -> {
                null
            }
        }
    }

    /** A copy of [intent] that starts [activity] and nothing else, and grants nothing. */
    private fun pinned(
        intent: Intent,
        activity: ActivityInfo,
    ): Intent {
        if (intent.flags and URI_GRANTS != 0) Log.i(TAG, "dropped URI-grant flags from $intent")
        return Intent(intent).apply {
            component = ComponentName(activity.packageName, activity.name)
            flags = intent.flags and URI_GRANTS.inv() or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

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

    internal companion object {
        private const val TAG = "PhoneAgentHelper"

        /** Flags that would let the target open URIs with the starter's (the shell's) permissions. */
        private const val URI_GRANTS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        /**
         * `ActivityTaskSupervisor.ACTION_TO_RUNTIME_PERMISSION`. Motion photo capture is in the map
         * from API 36; earlier platforms do not restrict it, so there this is stricter.
         */
        val ACTION_PERMISSIONS =
            mapOf(
                MediaStore.ACTION_IMAGE_CAPTURE to Manifest.permission.CAMERA,
                "android.provider.action.MOTION_PHOTO_CAPTURE" to Manifest.permission.CAMERA,
                MediaStore.ACTION_VIDEO_CAPTURE to Manifest.permission.CAMERA,
                Intent.ACTION_CALL to Manifest.permission.CALL_PHONE,
            )
    }
}
