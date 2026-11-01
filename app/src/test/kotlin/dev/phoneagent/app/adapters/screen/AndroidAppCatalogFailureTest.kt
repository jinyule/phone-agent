package dev.phoneagent.app.adapters.screen

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.AppEntry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager

/** The package manager calls [FailingPackageManager] can make throw. */
enum class ManagerCall { LAUNCHER_QUERY, INSTALLED_APPS, LABEL }

/** Throws like a package manager that died (the platform wraps DeadObjectException this way). */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class FailingPackageManager : ShadowApplicationPackageManager() {
    @Implementation
    override fun queryIntentActivitiesAsUser(
        intent: Intent,
        flags: Any,
        userId: Int,
    ): List<ResolveInfo> =
        failOr(ManagerCall.LAUNCHER_QUERY) { super.queryIntentActivitiesAsUser(intent, flags, userId) }

    @Implementation
    override fun getInstalledApplications(flags: Any): List<ApplicationInfo> =
        failOr(ManagerCall.INSTALLED_APPS) { super.getInstalledApplications(flags) }

    @Implementation
    override fun getText(
        packageName: String,
        resid: Int,
        appInfo: ApplicationInfo?,
    ): CharSequence? = failOr(ManagerCall.LABEL) { super.getText(packageName, resid, appInfo) }

    // The platform rethrows a dead package manager's DeadObjectException as a plain RuntimeException.
    @Suppress("TooGenericExceptionThrown")
    private fun <T> failOr(
        call: ManagerCall,
        real: () -> T,
    ): T = if (call in failing) throw RuntimeException("Package manager has died") else real()

    companion object {
        var failing: Set<ManagerCall> = emptySet()
    }
}

/** AppCatalog never throws: a failed package manager query leaves out only what it would have listed. */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [FailingPackageManager::class])
class AndroidAppCatalogFailureTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun install(
        pkg: String,
        configure: ApplicationInfo.() -> Unit,
        launcher: Boolean,
    ) {
        val app = ApplicationInfo().apply { packageName = pkg }.apply(configure)
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = app
            },
        )
        if (launcher) {
            val main = ComponentName(pkg, "$pkg.Main")
            shadowOf(context.packageManager).addActivityIfNotPresent(main)
            shadowOf(context.packageManager).addIntentFilterForActivity(
                main,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
    }

    private fun installMailAndService() {
        install("com.mail", { nonLocalizedLabel = "Mail" }, launcher = true)
        install("com.example.service", { nonLocalizedLabel = "Background" }, launcher = false)
    }

    private suspend fun ofInterest(catalog: AndroidAppCatalog): List<AppEntry> =
        catalog.apps().filter { it.packageName.startsWith("com.mail") || it.packageName.startsWith("com.example") }

    @After
    fun heal() {
        FailingPackageManager.failing = emptySet()
    }

    @Test
    fun aFailedLauncherQueryStillListsTheInstalledApps() =
        runTest {
            installMailAndService()
            FailingPackageManager.failing = setOf(ManagerCall.LAUNCHER_QUERY)

            val apps = ofInterest(AndroidAppCatalog(context, StandardTestDispatcher(testScheduler)))

            assertEquals(
                listOf(
                    AppEntry("com.example.service", "Background", launchable = false),
                    AppEntry("com.mail", "Mail", launchable = false),
                ),
                apps,
            )
        }

    @Test
    fun aFailedApplicationQueryStillListsTheLaunchableApps() =
        runTest {
            installMailAndService()
            FailingPackageManager.failing = setOf(ManagerCall.INSTALLED_APPS)

            val apps = ofInterest(AndroidAppCatalog(context, StandardTestDispatcher(testScheduler)))

            assertEquals(listOf(AppEntry("com.mail", "Mail", launchable = true)), apps)
        }

    @Test
    fun whenEveryQueryFailsTheListIsEmpty() =
        runTest {
            installMailAndService()
            FailingPackageManager.failing = setOf(ManagerCall.LAUNCHER_QUERY, ManagerCall.INSTALLED_APPS)

            assertEquals(
                emptyList<AppEntry>(),
                AndroidAppCatalog(context, StandardTestDispatcher(testScheduler)).apps(),
            )
        }

    @Test
    fun anUnreadableLabelFallsBackToThePackageName() =
        runTest {
            install("com.example.resources", { labelRes = LABEL_RES }, launcher = false)
            FailingPackageManager.failing = setOf(ManagerCall.LABEL)

            val apps = ofInterest(AndroidAppCatalog(context, StandardTestDispatcher(testScheduler)))

            assertEquals(listOf(AppEntry("com.example.resources", "com.example.resources", launchable = false)), apps)
        }

    private companion object {
        /** A string resource the package manager is asked to load for the label. */
        const val LABEL_RES = 0x7f0b0001
    }
}
