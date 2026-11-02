package dev.phoneagent.privileged.cap

import android.app.ActivityManager
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.provider.Browser
import android.provider.ContactsContract
import android.provider.Settings
import android.service.chooser.ChooserTarget
import android.util.SparseArray
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

    // The shapes below each hid an intent from a check that looked for intents (r1a review); the
    // check now passes only the extras on its list, so whatever else carries one is refused too.

    @Test
    fun anIntentInASparseArrayIsRefused() {
        val sparse = SparseArray<Parcelable>().apply { put(0, Intent(Intent.ACTION_DIAL)) }
        assertRefused("SparseArray", settings().putExtras(Bundle().apply { putSparseParcelableArray("s", sparse) }))
    }

    @Test
    fun anIntentAsAMapValueIsRefused() {
        assertRefused(
            "Map value",
            settings().putExtra("m", hashMapOf<String, Any>("then" to Intent(Intent.ACTION_DIAL))),
        )
    }

    @Test
    fun aClipDataExtraIsRefused() {
        assertRefused("ClipData extra", settings().putExtra("clip", ClipData.newIntent("", Intent(Intent.ACTION_DIAL))))
    }

    @Test
    fun aTaskInfoWithABaseIntentIsRefused() {
        val task =
            ActivityManager.RunningTaskInfo().apply {
                baseIntent = Intent(Intent.ACTION_DIAL)
                setHidden("token", windowToken()) // TaskInfo.writeToParcel requires one.
            }
        assertRefused("RunningTaskInfo.baseIntent", settings().putExtra("task", task))
    }

    @Test
    fun aShortcutWithAnIntentIsRefused() {
        val shortcut =
            ShortcutInfo
                .Builder(helper.context, "s")
                .setShortLabel("s")
                .setIntent(Intent(Intent.ACTION_DIAL))
                .build()
        assertRefused("ShortcutInfo", settings().putExtra("shortcut", shortcut))
    }

    @Test
    fun aChooserTargetWithAnIntentInItsExtrasIsRefused() {
        val extras = Bundle().apply { putParcelable(Intent.EXTRA_INTENT, Intent(Intent.ACTION_DIAL)) }

        @Suppress("DEPRECATION") // Deprecated for share targets, still parcelable in an extra.
        val target = ChooserTarget("t", null, 1f, ComponentName("com.example", "com.example.A"), extras)
        assertRefused("ChooserTarget", settings().putExtra("target", target))
    }

    @Test
    fun contentValuesHoldingAnIntentOrABundleAreRefused() {
        installHandler(ContactsContract.Intents.Insert.ACTION, type = ContactsContract.Contacts.CONTENT_TYPE)
        for (smuggled in listOf<Any>(Intent(Intent.ACTION_DIAL), Bundle())) {
            val values = ContentValues().apply { put("data1", "+1 555 0100") }
            // An app can write any value into the parcel; ContentValues.put only offers leaf types.
            contentValuesMap(values)["data2"] = smuggled
            assertRefused(smuggled.javaClass.simpleName, contact(arrayListOf(values)))
        }
    }

    /** A WindowContainerToken, a hidden class, built the way the framework builds one. */
    private fun windowToken(): Any {
        val stub = Class.forName("android.window.IWindowContainerToken\$Stub")
        val token = stub.getMethod("asInterface", IBinder::class.java).invoke(null, Binder())
        val type = Class.forName("android.window.WindowContainerToken")
        val tokenInterface = Class.forName("android.window.IWindowContainerToken")
        return type.getDeclaredConstructor(tokenInterface).apply { isAccessible = true }.newInstance(token)
    }

    private fun Any.setHidden(
        field: String,
        value: Any,
    ) {
        android.app.TaskInfo::class.java
            .getDeclaredField(field)
            .apply { isAccessible = true }
            .set(this, value)
    }

    private fun contentValuesMap(values: ContentValues): MutableMap<String, Any?> {
        val field = ContentValues::class.java.getDeclaredField("mMap").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") // ContentValues keeps an ArrayMap<String, Object>.
        return field.get(values) as MutableMap<String, Any?>
    }

    private fun installHandler(
        action: String,
        scheme: String? = null,
        type: String? = null,
    ) {
        val filter =
            IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                if (scheme != null) addDataScheme(scheme)
                if (type != null) addDataType(type)
            }
        helper.install("com.example.handler", filter = filter)
    }

    private fun contact(data: ArrayList<ContentValues>): Intent =
        Intent(ContactsContract.Intents.Insert.ACTION)
            .setType(ContactsContract.Contacts.CONTENT_TYPE)
            .putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, data)

    private fun assertStarts(
        what: String,
        intent: Intent,
    ) {
        assertEquals(what, StartResult.OK, start(intent))
        assertEquals(what, 1, helper.bridge.atm.started.size)
    }

    @Test
    fun sharingAUriStarts() {
        installHandler(Intent.ACTION_SEND, type = "image/*")
        val uri = Uri.parse("content://com.example.files/image/1")
        assertStarts("EXTRA_STREAM", Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri))
    }

    @Test
    fun sharingSeveralUrisWithTextStarts() {
        installHandler(Intent.ACTION_SEND_MULTIPLE, type = "image/*")
        val uris =
            arrayListOf(
                Uri.parse("content://com.example.files/image/1"),
                Uri.parse("content://com.example.files/image/2"),
            )
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/png")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .putExtra(Intent.EXTRA_TEXT, "two photos")
                .putExtra(Intent.EXTRA_EMAIL, arrayOf("a@example.invalid"))
        assertStarts("ArrayList<Uri>", intent)
    }

    @Test
    fun aMapLocationStarts() {
        installHandler(Intent.ACTION_VIEW, scheme = "geo")
        assertStarts("geo:", Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=coffee")))
    }

    @Test
    fun aBrowserUrlWithStringHeadersStarts() {
        installHandler(Intent.ACTION_VIEW, scheme = "https")
        val headers = Bundle().apply { putString("Accept-Language", "zh-CN") }
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
                .putExtra(Browser.EXTRA_HEADERS, headers)
                .putExtra(Browser.EXTRA_APPLICATION_ID, "dev.phoneagent")
        assertStarts("https + headers", intent)
    }

    @Test
    fun insertingAContactWithItsDataRowsStarts() {
        installHandler(ContactsContract.Intents.Insert.ACTION, type = ContactsContract.Contacts.CONTENT_TYPE)
        val phone =
            ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, "+1 555 0100")
                put(ContactsContract.CommonDataKinds.Phone.TYPE, 2)
                put("data15", byteArrayOf(1, 2))
            }
        assertStarts(
            "ArrayList<ContentValues>",
            contact(arrayListOf(phone)).putExtra(ContactsContract.Intents.Insert.NAME, "Test"),
        )
    }

    @Test
    fun bundlesNestedBeyondTheLimitAreRefused() {
        fun nested(levels: Int): Bundle =
            (1..levels).fold(Bundle().apply { putString("leaf", "x") }) { inner, _ ->
                Bundle().apply { putBundle("b", inner) }
            }
        installHandler(Intent.ACTION_VIEW, scheme = "https")
        val page = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
        assertRefused("20 levels", Intent(page).putExtra("deep", nested(DEEP)))
        assertStarts("3 levels", Intent(page).putExtra("shallow", nested(SHALLOW)))
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

    private companion object {
        const val DEEP = 20
        const val SHALLOW = 3
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
