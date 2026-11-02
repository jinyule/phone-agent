package dev.phoneagent.privileged.cap

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.Settings
import dev.phoneagent.privileged.StartResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What `startActivity` refuses, through the Proxy, PrivilegedCore and Launcher. */
@RunWith(RobolectricTestRunner::class)
class LaunchRestrictionsTest {
    @get:Rule
    val folder = TemporaryFolder()
    private lateinit var helper: CapabilityHarness
    private var display = -1

    @Before
    fun setUp() {
        helper = CapabilityHarness(stateDir = folder.root)
        helper.install("com.android.settings", filter = CapabilityHarness.filter(Settings.ACTION_SETTINGS))
        display = helper.display()
        assertTrue("display created: $display", display >= 0)
    }

    @After
    fun close() {
        helper.close()
    }

    private fun start(intent: Intent): Int = helper.core.startActivity(display, intent, 0, false)

    private fun assertNothingStarted() {
        assertEquals("nothing reached the activity manager", emptyList<Pair<Intent, Int>>(), helper.bridge.atm.started)
        assertEquals("no command ran", emptyList<List<String>>(), helper.commands.commands)
    }

    @Test
    fun nothingStartsWhileThePhoneIsLocked() {
        helper.bridge.wm.keyguardLocked = true
        assertEquals(StartResult.LOCKED, start(Intent(Settings.ACTION_SETTINGS)))
        assertNothingStarted()
    }

    @Test
    fun aLockStateThatCannotBeReadCountsAsLocked() {
        helper.bridge.wm.keyguardLocked = null
        assertEquals(StartResult.LOCKED, start(Intent(Settings.ACTION_SETTINGS)))
        assertNothingStarted()
    }

    @Test
    fun anUnlockedPhoneStillStarts() {
        helper.bridge.wm.keyguardLocked = false
        assertEquals(StartResult.OK, start(Intent(Settings.ACTION_SETTINGS)))
    }

    private fun settings(): Intent = Intent(Settings.ACTION_SETTINGS)

    private fun assertRefused(
        what: String,
        intent: Intent,
    ) {
        assertEquals(what, StartResult.REFUSED, start(intent))
        assertNothingStarted()
    }

    @Test
    fun anIntentInTheExtrasIsRefused() {
        assertRefused("EXTRA_INTENT", settings().putExtra(Intent.EXTRA_INTENT, Intent(Intent.ACTION_DIAL)))
    }

    @Test
    fun anIntentArrayInTheExtrasIsRefused() {
        val initial = arrayOf(Intent(Intent.ACTION_DIAL))
        assertRefused("EXTRA_INITIAL_INTENTS", settings().putExtra(Intent.EXTRA_INITIAL_INTENTS, initial))
    }

    @Test
    fun anIntentListInTheExtrasIsRefused() {
        val list = arrayListOf<Parcelable>(Intent(Intent.ACTION_DIAL))
        assertRefused("a list of intents", settings().putParcelableArrayListExtra("next", list))
    }

    @Test
    fun anIntentInANestedBundleIsRefused() {
        val inner = Bundle().apply { putParcelable("then", Intent(Intent.ACTION_DIAL)) }
        val outer = Bundle().apply { putBundle("inner", inner) }
        assertRefused("a bundle in a bundle", settings().putExtra("outer", outer))
    }

    @Test
    fun aChooserIsRefused() {
        assertRefused("ACTION_CHOOSER", Intent.createChooser(settings(), "pick"))
    }

    @Test
    fun aSelectorIsRefused() {
        assertRefused("selector", settings().apply { selector = Intent(Intent.ACTION_DIAL) })
    }

    @Test
    fun aClipDataItemWithAnIntentIsRefused() {
        assertRefused(
            "ClipData intent",
            settings().apply {
                clipData =
                    ClipData.newIntent("", Intent(Intent.ACTION_DIAL))
            },
        )
    }

    @Test
    fun extrasThatCannotBeReadAreRefused() {
        assertRefused("unreadable extras", settings().putExtra("odd", Unreadable()))
    }

    @Test
    fun aPlainUriInClipDataStillStartsWithoutGrantFlags() {
        val intent =
            settings().apply {
                clipData = ClipData.newRawUri("", Uri.parse("content://com.example.files/doc/1"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        assertEquals(StartResult.OK, start(intent))
        val (started, _) =
            helper.bridge.atm.started
                .single()
        val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertEquals("grant flags are dropped", 0, started.flags and grants)
    }
}

/** A Parcelable the receiving side cannot read back, as an app class unknown to the helper would be. */
class Unreadable : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeInt(1)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Unreadable> =
            object : Parcelable.Creator<Unreadable> {
                override fun createFromParcel(source: Parcel): Unreadable = error("cannot read Unreadable")

                override fun newArray(size: Int): Array<Unreadable?> = arrayOfNulls(size)
            }
    }
}
