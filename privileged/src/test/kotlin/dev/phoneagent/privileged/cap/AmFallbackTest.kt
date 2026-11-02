package dev.phoneagent.privileged.cap

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import dev.phoneagent.privileged.StartResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * `am start`, the fallback when the hidden `startActivityAsUser` is unavailable, through the Proxy,
 * PrivilegedCore and Launcher: the command must start the same intent, not just its component, in
 * the helper's user. Which activities it may start at all is LaunchBoundaryTest's.
 */
@RunWith(RobolectricTestRunner::class)
class AmFallbackTest {
    @get:Rule
    val folder = TemporaryFolder()
    private lateinit var helper: CapabilityHarness
    private var display = -1

    @Before
    fun setUp() {
        helper = CapabilityHarness(stateDir = folder.root)
        val page =
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            }
        val component = helper.install(HANDLER, filter = page)
        val image =
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("content")
                addDataType("image/*")
            }
        shadowOf(helper.context.packageManager).addIntentFilterForActivity(component, image)
        val launcher =
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        shadowOf(helper.context.packageManager).addIntentFilterForActivity(component, launcher)
        display = helper.display()
        helper.bridge.atm.startUnavailable = true
    }

    @After
    fun close() {
        helper.close()
    }

    private fun start(intent: Intent): Int = helper.core.startActivity(display, intent, 0, false)

    private fun am(vararg rest: String): List<String> =
        listOf("am", "start", "--user", "0", "--display", "$display", "-n", "$HANDLER/.Main") + rest

    @Test
    fun anImplicitViewKeepsItsActionAndUrl() {
        assertEquals(StartResult.OK, start(Intent(Intent.ACTION_VIEW, Uri.parse(PAGE))))
        assertEquals(listOf(am("-a", Intent.ACTION_VIEW, "-d", PAGE, "-f", "$NEW_TASK")), helper.commands.of("am"))
    }

    @Test
    fun theTypeAndTheCategoriesAreKept() {
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(IMAGE), "image/png")
                .addCategory(Intent.CATEGORY_BROWSABLE)
        assertEquals(StartResult.OK, start(intent))
        val expected =
            am(
                "-a",
                Intent.ACTION_VIEW,
                "-d",
                IMAGE,
                "-t",
                "image/png",
                "-c",
                Intent.CATEGORY_BROWSABLE,
                "-f",
                "$NEW_TASK",
            )
        assertEquals(listOf(expected), helper.commands.of("am"))
    }

    @Test
    fun aLauncherStartWithAComponentKeepsActionMain() {
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(HANDLER, "$HANDLER.Main"))
        assertEquals(StartResult.OK, start(intent))
        val expected = am("-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, "-f", "$NEW_TASK")
        assertEquals(listOf(expected), helper.commands.of("am"))
    }

    @Test
    fun anIntentWithExtrasIsAnErrorAndRunsNoCommand() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PAGE)).putExtra("com.example.REFERRER", "x")
        assertEquals(StartResult.ERROR, start(intent))
        assertEquals("am start cannot carry the extras", emptyList<List<String>>(), helper.commands.of("am"))
    }

    /**
     * A cold start on a build without `startActivityAsUser` of an intent `am start` cannot carry:
     * nothing can start it, so the app is not stopped for nothing.
     */
    @Test
    fun aColdStartThatOnlyAmCouldDoAndAmCannotCarryStopsNothing() {
        helper.bridge.activityTaskManagerOverride = ActivityTaskManagerWithoutStart(helper.bridge.atm)
        val page = Intent(Intent.ACTION_VIEW, Uri.parse(PAGE))
        val withExtras = Intent(page).putExtra("com.example.REFERRER", "x")
        val withClipData = Intent(page).apply { clipData = ClipData.newRawUri("", Uri.parse(IMAGE)) }
        for ((what, intent) in listOf("extras" to withExtras, "ClipData" to withClipData)) {
            assertEquals(what, StartResult.ERROR, helper.core.startActivity(display, intent, 0, true))
            assertEquals("$what: no force-stop, no am start", emptyList<List<String>>(), helper.commands.of("am"))
            assertEquals("$what: nothing started", emptyList<Pair<Intent, Int>>(), helper.bridge.atm.started)
        }
    }

    @Test
    fun aColdStartOnABuildWithoutStartActivityAsUserStopsTheAppThenStartsItWithAm() {
        helper.bridge.activityTaskManagerOverride = ActivityTaskManagerWithoutStart(helper.bridge.atm)
        assertEquals(
            StartResult.OK,
            helper.core.startActivity(display, Intent(Intent.ACTION_VIEW, Uri.parse(PAGE)), 0, true),
        )
        val stop = listOf("am", "force-stop", "--user", "0", HANDLER)
        assertEquals(
            listOf(stop, am("-a", Intent.ACTION_VIEW, "-d", PAGE, "-f", "$NEW_TASK")),
            helper.commands.of("am"),
        )
    }

    /**
     * Where `startActivityAsUser` is there but fails only when called, that is known only after the
     * force-stop; the intent `am start` cannot carry then answers ERROR as before.
     */
    @Test
    fun aDirectStartThatFailsOnlyWhenCalledIsKnownAfterTheForceStop() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PAGE)).putExtra("com.example.REFERRER", "x")
        assertEquals(StartResult.ERROR, helper.core.startActivity(display, intent, 0, true))
        assertEquals(listOf(listOf("am", "force-stop", "--user", "0", HANDLER)), helper.commands.of("am"))
    }

    private companion object {
        const val HANDLER = "com.example.browser"
        const val PAGE = "https://example.com/a?b=c"
        const val IMAGE = "content://com.example.files/image/1"
        const val NEW_TASK = Intent.FLAG_ACTIVITY_NEW_TASK
    }
}
