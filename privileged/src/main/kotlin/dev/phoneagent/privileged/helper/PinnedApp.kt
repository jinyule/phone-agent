package dev.phoneagent.privileged.helper

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.phoneagent.privileged.handoff.CertPin

/** What the package manager reports about an installed package. */
internal data class InstalledApp(
    val uid: Int,
    val sourceDir: String,
    val versionCode: Long,
    /** [CertPin] of the current signers; null when the package reports none. */
    val certPin: String?,
)

/**
 * The app this helper serves, pinned at startup. The helper only starts when its own code was
 * loaded from the installed APK of [packageName] (`CLASSPATH` equals `sourceDir`), so the pinned
 * certificate is the certificate of the code that is running: the Binder is only ever handed to
 * an app signed with the same key as the helper itself.
 */
internal data class PinnedApp(
    val packageName: String,
    val uid: Int,
    val certPin: String,
    /** versionCode of the APK the helper's code came from. */
    val versionCode: Long,
    /** Path of that APK. */
    val apkPath: String,
) {
    /** Still installed with the pinned uid and certificate (kept by an update, not by someone else's reinstall). */
    fun matches(installed: InstalledApp?): Boolean =
        installed != null && installed.uid == uid && installed.certPin == certPin

    companion object {
        private const val PER_USER_RANGE = 100_000

        /** Pins [installed]; throws [IllegalStateException] saying why the helper must not run. */
        fun resolve(
            packageName: String,
            classPath: String,
            installed: InstalledApp?,
        ): PinnedApp {
            checkNotNull(installed) { "$packageName is not installed for the primary user" }
            check(installed.sourceDir == classPath) {
                "helper code must come from the installed APK of $packageName " +
                    "(CLASSPATH=$classPath, installed=${installed.sourceDir}); run `pm path $packageName` again"
            }
            check(
                installed.uid / PER_USER_RANGE == 0,
            ) { "$packageName uid ${installed.uid} is not in the primary user" }
            val pin = checkNotNull(installed.certPin) { "$packageName reports no signing certificate" }
            return PinnedApp(packageName, installed.uid, pin, installed.versionCode, installed.sourceDir)
        }
    }
}

/** Reads installed packages of the primary user through the shell context's package manager. */
internal class AppPackages(
    private val context: Context,
) {
    /** [packageName] as installed now, or null when it is not installed. */
    fun read(packageName: String): InstalledApp? {
        val info =
            try {
                packageInfo(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.i(TAG, "$packageName is not installed", e)
                null
            }
        val app = info?.applicationInfo ?: return null
        val signers =
            info.signingInfo
                ?.apkContentsSigners
                ?.map { it.toByteArray() }
                .orEmpty()
        return InstalledApp(app.uid, app.sourceDir, info.longVersionCode, CertPin.of(signers))
    }

    private fun packageInfo(packageName: String): PackageInfo {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}
