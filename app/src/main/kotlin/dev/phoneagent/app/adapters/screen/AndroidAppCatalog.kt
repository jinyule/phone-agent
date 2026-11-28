package dev.phoneagent.app.adapters.screen

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.AppEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AppCatalog] from the package manager: the apps installed for the user this app runs as
 * (queries without `MATCH_ANY_USER` / `MATCH_UNINSTALLED_PACKAGES`). The manifest asks for
 * `QUERY_ALL_PACKAGES`, so package visibility hides nothing. Left out: this app itself and
 * packages whose name is not a dotted Java package name (`android`); a blank or unreadable label
 * falls back to the package name.
 *
 * Failures: when a package manager query fails (it died, or its reply was too large) that part
 * of the list is left out and logged; [apps] returns the rest. Cancellation is passed on.
 */
internal class AndroidAppCatalog(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AppCatalog {
    private val pm: PackageManager = context.applicationContext.packageManager
    private val ownPackage = context.packageName

    override suspend fun apps(): List<AppEntry> =
        withContext(io) {
            val launchable =
                readOrNothing("launcher activities", ::launcherActivities)
                    .associate { it.activityInfo.packageName to labelOf(it.activityInfo.packageName, it::loadLabel) }
            val others =
                readOrNothing("installed applications", ::installedApplications)
                    .filter { it.packageName !in launchable }
                    .associate { it.packageName to labelOf(it.packageName, it::loadLabel) }
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
        val listed = pkg != ownPackage && PackageNames.isValid(pkg)
        return if (listed) AppEntry(pkg, shown, launchable) else null
    }

    /** [query], or nothing when the package manager fails; it reports every failure as a RuntimeException. */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> readOrNothing(
        what: String,
        query: () -> List<T>,
    ): List<T> =
        try {
            query()
        } catch (cancelled: CancellationException) {
            // A RuntimeException too, but never a package manager failure: passed on.
            throw cancelled
        } catch (managerFailed: RuntimeException) {
            Log.w(TAG, "Could not read the $what; listing the rest", managerFailed)
            emptyList()
        }

    /** The label [load] reads, or `null` (the package name is shown) when reading it fails. */
    @Suppress("TooGenericExceptionCaught")
    private fun labelOf(
        pkg: String,
        load: (PackageManager) -> CharSequence?,
    ): CharSequence? =
        try {
            load(pm)
        } catch (cancelled: CancellationException) {
            // A RuntimeException too, but never a label failure: passed on.
            throw cancelled
        } catch (labelFailed: RuntimeException) {
            Log.w(TAG, "Could not read the label of $pkg; showing the package name", labelFailed)
            null
        }

    private fun launcherActivities(): List<ResolveInfo> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }
    }

    private fun installedApplications(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

    private companion object {
        const val TAG = "AppCatalog"
    }
}
