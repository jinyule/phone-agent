package dev.phoneagent.app.adapters.screen

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.AppEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AppCatalog] from the package manager. Package visibility (Android 11+) limits it to what the
 * manifest's `<queries>` reveal: every app with a launcher activity, plus the packages that are
 * always visible. Left out: this app itself and packages whose name is not a dotted Java package
 * name (`android`); a blank label falls back to the package name.
 */
internal class AndroidAppCatalog(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AppCatalog {
    private val pm: PackageManager = context.applicationContext.packageManager
    private val ownPackage = context.packageName

    override suspend fun apps(): List<AppEntry> =
        withContext(io) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launchable =
                launcherActivities(launcher)
                    .associate { it.activityInfo.packageName to it.loadLabel(pm) }
            val others =
                installedApplications()
                    .filter { it.packageName !in launchable }
                    .associate { it.packageName to it.loadLabel(pm) }
            val entries =
                launchable.mapNotNull { (pkg, label) -> entryOf(pkg, label, launchable = true) } +
                    others.mapNotNull { (pkg, label) -> entryOf(pkg, label, launchable = false) }
            entries.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
        }

    private fun entryOf(
        pkg: String,
        label: CharSequence?,
        launchable: Boolean,
    ): AppEntry? {
        val shown = label?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
        val listed = pkg != ownPackage && JAVA_PACKAGE.matches(pkg)
        return if (listed) AppEntry(pkg, shown, launchable) else null
    }

    private fun launcherActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

    private fun installedApplications(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

    private companion object {
        /** The names `AppEntry` accepts. */
        val JAVA_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
