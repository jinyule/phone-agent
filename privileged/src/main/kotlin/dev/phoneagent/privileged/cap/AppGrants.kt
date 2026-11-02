package dev.phoneagent.privileged.cap

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * What the app's uid holds, read the way the activity manager reads it when the app starts an
 * activity itself: granted permissions, app-op modes, and the permissions its manifest requests.
 * The helper asks as the shell user, which may read them for another uid.
 */
internal class AppGrants(
    private val context: Context,
    private val app: ServedApp,
) {
    /** Whether the app's uid holds [permission]. */
    fun holds(permission: String): Boolean =
        context.checkPermission(permission, NO_PID, app.uid) == PackageManager.PERMISSION_GRANTED

    /**
     * True when [permission] has no app op, or its op is `MODE_ALLOWED` for the app. Every other
     * mode, `MODE_DEFAULT` included, makes the activity manager refuse the start, and so does an
     * op the helper may not read.
     */
    fun opAllowed(permission: String): Boolean {
        val op = AppOpsManager.permissionToOp(permission)
        return op == null || opMode(op) == AppOpsManager.MODE_ALLOWED
    }

    /** Whether the app's manifest requests [permission]; false when its package cannot be read, as on the platform. */
    fun requests(permission: String): Boolean = permission in requested()

    /** The app's mode of [op]; null when the helper may not read it. */
    private fun opMode(op: String): Int? =
        try {
            context.getSystemService(AppOpsManager::class.java)?.checkOpNoThrow(op, app.uid, app.packageName)
        } catch (e: SecurityException) {
            Log.w(TAG, "the helper may not read app op $op of ${app.packageName}", e)
            null
        }

    private fun requested(): List<String> =
        try {
            @Suppress("DEPRECATION") // The flags overload is API 33+; this one answers the same.
            context.packageManager
                .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toList()
                .orEmpty()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(TAG, "${app.packageName} not found; no action restriction applies", e)
            emptyList()
        }

    private companion object {
        const val TAG = "PhoneAgentHelper"

        /** No particular process: the permission is checked for the uid alone. */
        const val NO_PID = -1
    }
}
