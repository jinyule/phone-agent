package dev.phoneagent.app.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
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
import org.junit.Assert.assertTrue
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

    /** Each control on the screen (clickable, or a text field) by its tag, text or role, and whether it is enabled. */
    private fun controls(): List<Pair<String, Boolean>> =
        compose.onAllNodes(hasClickAction() or hasSetTextAction()).fetchSemanticsNodes().map { node ->
            val config = node.config
            val name =
                config.getOrNull(SemanticsProperties.TestTag)
                    ?: config.getOrNull(SemanticsProperties.Text)?.joinToString()
                    ?: config.getOrNull(SemanticsProperties.Role).toString()
            name to !config.contains(SemanticsProperties.Disabled)
        }

    @Test
    fun nothingInTheFormCanBeChangedWhileItIsBeingTested() {
        // A custom form on an official address with a key: every kind of control shows, the preset hint included.
        val start =
            ProviderForm.start(null).copy(baseUrl = ProviderPresets.DEEPSEEK.baseUrl, apiKey = "sk-test-not-a-real-key")
        val screen = FakeSettingsScreen(compose, start, run = RunState.Testing(null))
        val testing = controls()
        screen.run = RunState.Idle
        compose.waitForIdle()
        val idle = controls()
        // Collected first, so a screen that leaves some enabled names each of them.
        assertEquals(
            listOf(idle.map { it.first }, emptyList(), idle.map { it.first }),
            listOf(testing.map { it.first }, testing.filter { it.second }, idle.filter { it.second }.map { it.first }),
        )
        assertTrue(idle.toString(), idle.size >= MIN_CONTROLS)
    }

    @Test
    fun testAndSaveIsOneButton() {
        val screen = FakeSettingsScreen(compose, ProviderForm.start(null))
        compose.onNodeWithText(text(R.string.form_test_and_save)).performScrollTo().performClick()
        assertEquals(1, screen.tests)
    }

    private companion object {
        /**
         * The presets and custom, two kinds of authentication, the key, the address, the preset hint,
         * two protocols, three models, images, the context window, and the button.
         */
        val MIN_CONTROLS = ProviderPresets.ALL.size + 14
    }
}
