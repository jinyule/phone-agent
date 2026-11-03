package dev.phoneagent.app.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.R
import dev.phoneagent.app.model.SettingsState
import dev.phoneagent.app.model.credential.CredentialStatus
import dev.phoneagent.app.model.settings.SettingsFixtures
import dev.phoneagent.app.model.settings.SettingsProblem
import dev.phoneagent.app.model.settings.SettingsProblemKind
import dev.phoneagent.app.model.setup.AuthChoice
import dev.phoneagent.app.model.setup.FormProblem
import dev.phoneagent.app.model.setup.ModelCheck
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.app.model.setup.SetupOutcome
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.openai.ProbeFailureReason
import dev.phoneagent.core.model.openai.ProbeResult
import dev.phoneagent.core.model.openai.ProbeStep
import dev.phoneagent.core.model.openai.ProviderPreset
import dev.phoneagent.core.model.openai.ProviderPresets
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The form: authentication first, the key hints and the preset hint. */
@RunWith(AndroidJUnit4::class)
class ModelSettingsFormTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun noAuthenticationIsOfferedOnTheFirstScreen() {
        FakeSettingsScreen(compose, ProviderForm.start(null))
        compose.onNodeWithText(text(R.string.form_auth_none)).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.KEY).assertExists()
        compose.onNodeWithText(text(R.string.form_auth_none)).performClick()
        compose.onNodeWithTag(SettingsTags.KEY).assertDoesNotExist()
    }

    @Test
    fun presetsThatNeedNoKeyShowNoKeyField() {
        val screen = FakeSettingsScreen(compose, ProviderForm.start(null))
        compose.onNodeWithText(ProviderPresets.OLLAMA.displayName).performClick()
        assertEquals(listOf<ProviderPreset?>(ProviderPresets.OLLAMA), screen.presets)
        assertEquals(AuthChoice.NONE, screen.form.auth)
        assertEquals(ProviderPresets.OLLAMA.baseUrl, screen.form.baseUrl)
        compose.onNodeWithTag(SettingsTags.KEY).assertDoesNotExist()
    }

    @Test
    fun aShortKeyGetsTheHintAndTheKeyStaysInTheForm() {
        val screen = FakeSettingsScreen(compose, ProviderForm.start(ProviderPresets.DEEPSEEK))
        compose.onNodeWithTag(SettingsTags.KEY).performTextInput("x")
        assertEquals("x", screen.form.apiKey)
        compose.onNodeWithText(text(R.string.hint_short_key)).assertExists()
        compose.onNodeWithTag(SettingsTags.KEY).performTextInput("2345678")
        compose.onNodeWithText(text(R.string.hint_short_key)).assertDoesNotExist()
    }

    @Test
    fun anOfficialAddressInACustomFormSuggestsThePreset() {
        val screen =
            FakeSettingsScreen(
                compose,
                ProviderForm.start(null).copy(baseUrl = "https://api.deepseek.com", mainModel = "deepseek-chat"),
            )
        compose.onNodeWithText(text(R.string.hint_use_preset, ProviderPresets.DEEPSEEK.displayName)).assertExists()
        compose.onNodeWithText(text(R.string.hint_use_preset_action)).performScrollTo().performClick()
        assertEquals(listOf<ProviderPreset?>(ProviderPresets.DEEPSEEK), screen.presets)
        assertEquals("deepseek", screen.form.presetId)
        assertEquals("deepseek-chat", screen.form.mainModel)
        compose.onNodeWithText(text(R.string.hint_use_preset_action)).assertDoesNotExist()
    }

    @Test
    fun testAndSaveIsOneButton() {
        val screen = FakeSettingsScreen(compose, ProviderForm.start(null))
        compose.onNodeWithText(text(R.string.form_test_and_save)).performScrollTo().performClick()
        assertEquals(1, screen.tests)
    }
}
