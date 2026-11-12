package dev.phoneagent.app

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.model.settings.ModelSettings
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.settings.SettingsFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
