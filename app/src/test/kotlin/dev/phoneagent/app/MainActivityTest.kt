package dev.phoneagent.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

private const val WAIT_MS = 5_000L

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
