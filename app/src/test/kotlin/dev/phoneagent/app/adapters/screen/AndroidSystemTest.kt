package dev.phoneagent.app.adapters.screen

import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.AppEntry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Installs [pkg] labelled [label], with a launcher activity when [launcher]. */
private fun Application.install(
    pkg: String,
    label: String,
    launcher: Boolean,
) {
    val app =
        ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = label
        }
    shadowOf(packageManager).installPackage(
        PackageInfo().apply {
            packageName = pkg
            applicationInfo = app
        },
    )
    if (launcher) {
        val main = ComponentName(pkg, "$pkg.Main")
        shadowOf(packageManager).addActivityIfNotPresent(main)
        shadowOf(packageManager).addIntentFilterForActivity(
            main,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }
}

@RunWith(AndroidJUnit4::class)
class AndroidAppCatalogTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun listsLaunchableAndOtherAppsButNeitherItselfNorUnnamedPackages() =
        runTest {
            context.install("com.mail", "Mail", launcher = true)
            context.install("com.example.service", "Background", launcher = false)
            context.install("com.blank", " ", launcher = true)
            context.install("android", "Android System", launcher = false)

            val apps = AndroidAppCatalog(context, StandardTestDispatcher(testScheduler)).apps()

            assertEquals(
                listOf(
                    AppEntry("com.example.service", "Background", launchable = false),
                    AppEntry("com.blank", "com.blank", launchable = true),
                    AppEntry("com.mail", "Mail", launchable = true),
                ),
                apps.filter { it.packageName in setOf("com.mail", "com.example.service", "com.blank", "android") },
            )
            assertTrue(apps.none { it.packageName == context.packageName })
        }
}

@RunWith(AndroidJUnit4::class)
class AndroidScreenSystemTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val system = AndroidScreenSystem(context)

    @Test
    fun knowsItsOwnPackageAndTheLockState() {
        assertEquals(context.packageName, system.ownPackage)
        assertFalse(system.locked())

        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertTrue(system.locked())
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(context.getSystemService(PowerManager::class.java)).turnScreenOn(false)
        assertTrue("screen off counts as locked", system.locked())
    }

    // isActivity uses getActivityInfo(ComponentName, ComponentInfoFlags), which Robolectric 4.17 does not
    // implement; AndroidScreenDeviceTest covers it on the emulator.
    @Test
    fun findsLauncherIntentsAndPickers() {
        context.install("com.mail", "Mail", launcher = true)

        assertNotNull(system.launchIntent("com.mail"))
        assertNull(system.launchIntent("com.missing"))
        assertTrue("the platform hosts the chooser", "android" in system.pickerPackages())
    }

    @Test
    fun startsOnTheRequestedDisplay() {
        val intent = Intent(Intent.ACTION_VIEW).setPackage("com.mail")

        assertEquals(StartResult.Started, system.start(intent, displayId = 3))

        val started = shadowOf(context).nextStartedActivityForResult
        assertEquals("com.mail", started.intent.`package`)
        assertTrue(started.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // ActivityOptions.fromBundle is hidden; this is the key setLaunchDisplayId writes.
        assertEquals(3, started.options.getInt("android.activity.launchDisplayId", -1))
    }

    @Test
    fun anUnhandledIntentIsNotFound() {
        shadowOf(context).checkActivities(true)

        assertEquals(StartResult.NotFound, system.start(Intent("com.example.NOTHING"), displayId = 0))
    }
}
