package dev.phoneagent.privileged.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedAppTest {
    private val pkg = "dev.phoneagent"
    private val apk = "/data/app/~~a==/dev.phoneagent-b==/base.apk"
    private val installed = InstalledApp(uid = 10_214, sourceDir = apk, versionCode = 10001, certPin = "pin")

    private fun failure(
        classPath: String,
        app: InstalledApp?,
    ): String =
        assertThrows(IllegalStateException::class.java) { PinnedApp.resolve(pkg, classPath, app) }.message.orEmpty()

    @Test
    fun pinsTheInstalledApkTheHelperRunsFrom() {
        assertEquals(PinnedApp(pkg, 10_214, "pin", 10001, apk), PinnedApp.resolve(pkg, apk, installed))
    }

    @Test
    fun refusesWhenTheAppIsNotInstalled() {
        assertTrue(failure(apk, null).contains("not installed"))
    }

    @Test
    fun refusesCodeFromAnotherApk() {
        assertTrue(failure("/data/local/tmp/other.apk", installed).contains("CLASSPATH=/data/local/tmp/other.apk"))
    }

    @Test
    fun refusesSecondaryUsers() {
        assertTrue(failure(apk, installed.copy(uid = 1_010_214)).contains("primary user"))
    }

    @Test
    fun refusesUnsignedPackages() {
        assertTrue(failure(apk, installed.copy(certPin = null)).contains("signing certificate"))
    }

    @Test
    fun matchesSurviveAnUpdateButNotAReinstallBySomeoneElse() {
        val pinned = PinnedApp.resolve(pkg, apk, installed)
        assertTrue(pinned.matches(installed))
        assertTrue(
            "an update moves the APK",
            pinned.matches(installed.copy(sourceDir = "/data/app/~~new/base.apk", versionCode = 10002)),
        )
        assertFalse(pinned.matches(installed.copy(certPin = "other")))
        assertFalse(pinned.matches(installed.copy(uid = 10_300)))
        assertFalse(pinned.matches(null))
    }
}
