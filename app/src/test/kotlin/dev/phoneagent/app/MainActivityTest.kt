package dev.phoneagent.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.model.settings.ModelSettings
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.settings.SettingsFixtures
import dev.phoneagent.app.task.TaskView
import dev.phoneagent.app.task.Tasks
import dev.phoneagent.app.ui.chat.ChatTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

private const val WAIT_MS = 5_000L

/** The status bar, navigation bar and keyboard heights in the tests, in pixels. */
private const val STATUS_PX = 24f
private const val NAVIGATION_PX = 48f
private const val IME_PX = 200

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun opensOnAnEmptyChatWithTheModelSettingsOneTabAway() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(context.getString(R.string.chat_empty)).assertExists()
            compose.onNodeWithText(context.getString(R.string.nav_models)).performClick()
            compose.onNodeWithText(context.getString(R.string.settings_none)).assertExists()
        }
    }

    @Test
    fun backFromTheModelSettingsReturnsToTheChat() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText(context.getString(R.string.chat_empty)).assertExists()
            compose.onNodeWithText(context.getString(R.string.nav_models)).performClick()
            compose.onNodeWithText(context.getString(R.string.settings_none)).assertExists()
            scenario.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText(context.getString(R.string.chat_empty)).assertExists()
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }

    @Test
    fun backFromTheChatLeavesTheApp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText(context.getString(R.string.chat_empty)).assertExists()
            scenario.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
            scenario.onActivity { activity -> assertTrue(activity.isFinishing) }
        }
    }

    @Test
    fun theKeyboardLiftsTheComposerOntoItAndLeavesTheTabsUnderTheStatusBar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Reads back the manifest's adjustResize; the deprecation is for setting it in code.
            @Suppress("DEPRECATION")
            val resize = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            scenario.onActivity { activity ->
                assertEquals(
                    "the system leaves the window alone and hands the keyboard to the page",
                    resize,
                    activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
                )
            }
            val root =
                compose
                    .onRoot()
                    .fetchSemanticsNode()
                    .boundsInRoot.bottom
            val input = compose.onNodeWithTag(ChatTags.INPUT)

            fun bottom() = input.fetchSemanticsNode().boundsInRoot.bottom

            fun tabs() =
                compose
                    .onNodeWithText(context.getString(R.string.nav_chat))
                    .fetchSemanticsNode()
                    .boundsInRoot.top

            scenario.onActivity { activity -> activity.applyInsets(keyboard = 0) }
            compose.waitForIdle()
            assertTrue("the tabs are under the status bar", tabs() >= STATUS_PX)
            val tabsTop = tabs()
            // The composer's own margin above the navigation bar.
            val margin = root - NAVIGATION_PX - bottom()
            assertTrue(margin >= 0f)

            scenario.onActivity { activity -> activity.applyInsets(keyboard = IME_PX) }
            compose.waitForIdle()
            // The keyboard covers the navigation bar: its inset already holds it, so it counts once.
            assertEquals("right on the keyboard", root - IME_PX - margin, bottom(), 1f)
            assertEquals("the tabs stay put", tabsTop, tabs(), 0f)
            compose.onNodeWithText(context.getString(R.string.chat_empty)).assertIsDisplayed()
        }
    }

    /** What the system sends the window: the status and navigation bars, and a [keyboard] px keyboard (0: none). */
    private fun Activity.applyInsets(keyboard: Int) {
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_PX.toInt(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAVIGATION_PX.toInt()))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard))
                .setVisible(WindowInsetsCompat.Type.ime(), keyboard > 0)
                .build()
        ViewCompat.dispatchApplyWindowInsets(window.decorView, insets)
    }

    @Test
    fun theFirstCommandWaitsForTheAnswerToTheNotificationPermission() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag(ChatTags.INPUT).performTextInput("open settings")
            compose.onNodeWithTag(ChatTags.SEND).performClick()
            compose.waitForIdle()
            assertEquals("not sent while the dialog shows", TaskView(), Tasks.of(context).view.value)
            scenario.onActivity { activity ->
                val request = shadowOf(activity).lastRequestedPermission
                assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
                @Suppress("DEPRECATION") // How the system answers a permission request; the registry reads it here.
                activity.onRequestPermissionsResult(
                    request.requestCode,
                    request.requestedPermissions,
                    intArrayOf(PackageManager.PERMISSION_DENIED),
                )
            }
            // Denied, the command still goes: without a model it cannot start.
            compose.waitUntil(WAIT_MS) {
                Tasks
                    .of(context)
                    .view.value.problem != null
            }
            compose.onNodeWithText(context.getString(R.string.task_problem_no_model)).assertExists()
        }
    }

    @Test
    fun savedSettingsWithoutTheirKeyAskForItInsteadOfFailingThePage() {
        // What a restore of filesDir without noBackupFilesDir looks like: settings, but no key file.
        File(context.filesDir, ModelSettingsFile.NAME)
            .writeText(Json.encodeToString(serializer<ModelSettings>(), SettingsFixtures.settings()))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(context.getString(R.string.nav_models)).performClick()
            compose.onNodeWithText(context.getString(R.string.settings_key_again, "DeepSeek")).assertExists()
            compose.onNodeWithText(context.getString(R.string.settings_broken_title)).assertDoesNotExist()
        }
    }

    @Test
    fun aBrokenSettingsFileShowsTheResetPage() {
        File(context.filesDir, ModelSettingsFile.NAME).writeText("{ not json")
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(context.getString(R.string.nav_models)).performClick()
            compose.onNodeWithText(context.getString(R.string.settings_broken_title)).assertExists()
            compose.onNodeWithText(context.getString(R.string.settings_reset)).assertExists()
        }
    }
}
