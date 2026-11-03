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

/** What the screen shows of saved settings and of "test and save" results. */
@RunWith(AndroidJUnit4::class)
class ModelSettingsResultTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFailedTrialShowsBothStepsSeparately() {
        val failed =
            ProbeResult.Failed(
                ProbeStep.TOOL_CALL,
                ModelErrorKind.INVALID_REQUEST,
                "invalid request: no such model",
            )
        FakeSettingsScreen(
            compose,
            ProviderForm.start(null),
            run = RunState.Done(SetupOutcome.Failed(listOf(check(failed)))),
        )
        compose.onNodeWithText(stepLine(R.string.result_step_list_models, R.string.result_passed)).assertExists()
        compose.onNodeWithText(stepLine(R.string.result_step_tool_call, R.string.result_failed)).assertExists()
        compose.onNodeWithText(text(R.string.kind_invalid_request)).assertExists()
        compose.onNodeWithText("invalid request: no such model").assertExists()
        compose.onNodeWithText(text(R.string.result_nothing_saved)).assertExists()
    }

    @Test
    fun aFailedListingSaysTheTrialDidNotRun() {
        val failed =
            ProbeResult.Failed(
                ProbeStep.LIST_MODELS,
                ModelErrorKind.AUTH,
                "authentication failed: Invalid API key",
            )
        FakeSettingsScreen(
            compose,
            ProviderForm.start(null),
            run = RunState.Done(SetupOutcome.Failed(listOf(check(failed)))),
        )
        compose.onNodeWithText(stepLine(R.string.result_step_list_models, R.string.result_failed)).assertExists()
        compose.onNodeWithText(stepLine(R.string.result_step_tool_call, R.string.result_not_run)).assertExists()
        compose.onNodeWithText(text(R.string.kind_auth)).assertExists()
    }

    @Test
    fun aKeyInTheTrialItselfSuggestsAnotherKeyOrNone() {
        val failed = ProbeResult.Failed(ProbeStep.TOOL_CALL, null, "fixed", ProbeFailureReason.CREDENTIAL_IN_TRIAL)
        FakeSettingsScreen(
            compose,
            ProviderForm.start(null),
            run = RunState.Done(SetupOutcome.Failed(listOf(check(failed)))),
        )
        compose.onNodeWithText(stepLine(R.string.result_step_list_models, R.string.result_not_run)).assertExists()
        compose.onNodeWithText(text(R.string.reason_credential_in_trial)).assertExists()
    }

    @Test
    fun formProblemsAreListed() {
        FakeSettingsScreen(
            compose,
            ProviderForm.start(null),
            run = RunState.Done(SetupOutcome.Invalid(listOf(FormProblem.MAIN_MODEL_MISSING))),
        )
        compose.onNodeWithText(text(R.string.problem_main_model_missing)).assertExists()
    }

    @Test
    fun savedSettingsWhoseKeyIsGoneAskForItAgain() {
        val settings = SettingsFixtures.settings()
        val ready = SettingsState.Ready(settings, mapOf(SettingsFixtures.DEEPSEEK to CredentialStatus.UNREADABLE))
        FakeSettingsScreen(compose, ProviderForm.of(settings), settings = ready)
        compose.onNodeWithText(text(R.string.settings_key_again, "DeepSeek")).assertExists()
        compose.onNodeWithText(text(R.string.settings_role_main, SettingsFixtures.MAIN.value)).assertExists()
        compose.onNodeWithText(text(R.string.settings_broken_title)).assertDoesNotExist()
    }

    @Test
    fun aStoredKeyThatIsReadableIsKeptByAnEmptyField() {
        val settings = SettingsFixtures.settings()
        FakeSettingsScreen(
            compose,
            ProviderForm.of(settings),
            settings =
                SettingsState.Ready(
                    settings,
                    mapOf(SettingsFixtures.DEEPSEEK to CredentialStatus.PRESENT),
                ),
        )
        compose.onNodeWithText(text(R.string.form_key_keep)).assertExists()
        compose.onNodeWithText(text(R.string.settings_key_again, "DeepSeek")).assertDoesNotExist()
    }

    @Test
    fun brokenSettingsShowAPageWithAReset() {
        val screen =
            FakeSettingsScreen(
                compose,
                ProviderForm.start(null),
                settings = SettingsState.Broken(SettingsProblem(SettingsProblemKind.MALFORMED, "\$.roles")),
            )
        compose.onNodeWithText(text(R.string.settings_broken_title)).assertExists()
        compose.onNodeWithText(text(R.string.settings_problem_malformed)).assertExists()
        compose.onNodeWithText(text(R.string.settings_broken_where, "\$.roles")).assertExists()
        compose.onNodeWithTag(SettingsTags.MAIN_MODEL).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_reset)).performClick()
        assertEquals(1, screen.resets)
    }

    private fun check(result: ProbeResult) = ModelCheck(ModelId("m"), setOf(ModelRole.MAIN), result, null)

    private fun stepLine(
        step: Int,
        state: Int,
    ) = text(R.string.result_step_line, text(step), text(state))
}
