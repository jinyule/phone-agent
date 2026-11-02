package dev.phoneagent.privileged.cap

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

    private companion object {
        const val HANDLER = "com.example.browser"
        const val PAGE = "https://example.com/a?b=c"
        const val IMAGE = "content://com.example.files/image/1"
        const val NEW_TASK = Intent.FLAG_ACTIVITY_NEW_TASK
    }
}
