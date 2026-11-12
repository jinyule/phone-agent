package dev.phoneagent.app.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.R
import dev.phoneagent.app.model.setup.FormProblem
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.app.model.setup.SetupOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs that stop before any check: each says why in words of its own. */
@RunWith(AndroidJUnit4::class)
class ModelSettingsStopTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun anUnexpectedFailureSaysTheRunStopped() {
        FakeSettingsScreen(compose, ProviderForm.start(null), run = RunState.Error)
        compose.onNodeWithText(text(R.string.result_error)).assertExists()
    }

    @Test
    fun brokenSettingsAskForAResetFirst() {
        FakeSettingsScreen(compose, ProviderForm.start(null), run = RunState.Done(SetupOutcome.SettingsBroken))
        compose.onNodeWithText(text(R.string.result_settings_broken)).assertExists()
    }

    @Test
    fun aRequestTheHttpClientRefusesSaysNothingWasSent() {
        FakeSettingsScreen(
            compose,
            ProviderForm.start(null),
            run = RunState.Done(SetupOutcome.Invalid(listOf(FormProblem.NOT_SENDABLE))),
        )
        compose.onNodeWithText(text(R.string.problem_not_sendable)).assertExists()
    }
}
