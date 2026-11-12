package dev.phoneagent.app.ui.settings

import androidx.annotation.StringRes
import dev.phoneagent.app.R
import dev.phoneagent.app.model.settings.SettingsProblemKind
import dev.phoneagent.app.model.setup.FormProblem
import dev.phoneagent.app.model.setup.SaveFailure
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.openai.ProbeFailureReason
import dev.phoneagent.core.model.openai.ProbeResult
import dev.phoneagent.core.model.openai.ProbeStep

/** How one "test and save" step went. */
internal enum class StepState {
    PASSED,
    FAILED,
    NOT_RUN,
}

/**
 * One step of a model's "test and save" as shown (model-layer §4.1): which step, how it went, a
 * [note] for a passed listing, and for a failure the classified [kind], the key-specific [reason]
 * and the provider's own [message] (already masked by core).
 */
internal data class StepLine(
    val step: ProbeStep,
    val state: StepState,
    @param:StringRes val note: Int? = null,
    @param:StringRes val kind: Int? = null,
    @param:StringRes val reason: Int? = null,
    val message: String? = null,
)

/** The two steps of [result], each with its own outcome: a failure says which step it was. */
internal fun stepsOf(result: ProbeResult): List<StepLine> =
    when (result) {
        is ProbeResult.Passed -> {
            val note =
                when {
                    result.models == null -> R.string.result_no_listing
                    result.candidateListed == false -> R.string.result_not_listed
                    else -> null
                }
            listOf(
                StepLine(ProbeStep.LIST_MODELS, StepState.PASSED, note),
                StepLine(ProbeStep.TOOL_CALL, StepState.PASSED),
            )
        }

        is ProbeResult.Failed -> {
            val failed =
                StepLine(
                    result.step,
                    StepState.FAILED,
                    null,
                    result.kind?.let(::kindText),
                    result.reason?.let(::reasonText),
                    result.message,
                )
            when {
                result.step == ProbeStep.LIST_MODELS -> {
                    listOf(failed, StepLine(ProbeStep.TOOL_CALL, StepState.NOT_RUN))
                }

                // The key was found in the trial's own text before any request: the listing never ran.
                result.reason == ProbeFailureReason.CREDENTIAL_IN_TRIAL -> {
                    listOf(
                        StepLine(ProbeStep.LIST_MODELS, StepState.NOT_RUN),
                        failed,
                    )
                }

                else -> {
                    listOf(StepLine(ProbeStep.LIST_MODELS, StepState.PASSED), failed)
                }
            }
        }
    }

@StringRes
internal fun stepText(step: ProbeStep): Int =
    when (step) {
        ProbeStep.LIST_MODELS -> R.string.result_step_list_models
        ProbeStep.TOOL_CALL -> R.string.result_step_tool_call
    }

@StringRes
internal fun stateText(state: StepState): Int =
    when (state) {
        StepState.PASSED -> R.string.result_passed
        StepState.FAILED -> R.string.result_failed
        StepState.NOT_RUN -> R.string.result_not_run
    }

@StringRes
internal fun reasonText(reason: ProbeFailureReason): Int =
    when (reason) {
        ProbeFailureReason.CREDENTIAL_ECHOED -> R.string.reason_credential_echoed
        ProbeFailureReason.CREDENTIAL_IN_TRIAL -> R.string.reason_credential_in_trial
    }

@StringRes
internal fun saveFailureText(failure: SaveFailure): Int =
    when (failure) {
        SaveFailure.KEY -> R.string.result_not_saved_key
        SaveFailure.SETTINGS -> R.string.result_not_saved_settings
    }

@StringRes
internal fun kindText(kind: ModelErrorKind): Int =
    when (kind) {
        ModelErrorKind.AUTH -> R.string.kind_auth
        ModelErrorKind.SIGN_IN_EXPIRED -> R.string.kind_sign_in_expired
        ModelErrorKind.PLAN_NOT_ELIGIBLE -> R.string.kind_plan_not_eligible
        ModelErrorKind.QUOTA_EXCEEDED -> R.string.kind_quota_exceeded
        ModelErrorKind.RATE_LIMITED -> R.string.kind_rate_limited
        ModelErrorKind.OVERLOADED -> R.string.kind_overloaded
        ModelErrorKind.CONTEXT_OVERFLOW -> R.string.kind_context_overflow
        ModelErrorKind.INVALID_REQUEST -> R.string.kind_invalid_request
        ModelErrorKind.REFUSAL -> R.string.kind_refusal
        ModelErrorKind.PREFIX_BINDING_MISMATCH -> R.string.kind_prefix_binding_mismatch
        ModelErrorKind.NETWORK -> R.string.kind_network
        ModelErrorKind.SERVER -> R.string.kind_server
        ModelErrorKind.BAD_RESPONSE -> R.string.kind_bad_response
    }

@StringRes
internal fun problemText(problem: FormProblem): Int =
    when (problem) {
        FormProblem.MAIN_MODEL_MISSING -> R.string.problem_main_model_missing
        FormProblem.MODEL_ID_INVALID -> R.string.problem_model_id_invalid
        FormProblem.KEY_MISSING -> R.string.problem_key_missing
        FormProblem.KEY_NOT_HEADER_SAFE -> R.string.problem_key_not_header_safe
        FormProblem.BASE_URL_INVALID -> R.string.problem_base_url_invalid
        FormProblem.HEADER_INVALID -> R.string.problem_header_invalid
        FormProblem.UNKNOWN_PRESET -> R.string.problem_unknown_preset
        FormProblem.PROTOCOL_UNSUPPORTED -> R.string.problem_protocol_unsupported
        FormProblem.CONTEXT_INVALID -> R.string.problem_context_invalid
        FormProblem.VISION_NEEDS_IMAGES -> R.string.problem_vision_needs_images
        FormProblem.NOT_SENDABLE -> R.string.problem_not_sendable
    }

@StringRes
internal fun settingsProblemText(kind: SettingsProblemKind): Int =
    when (kind) {
        SettingsProblemKind.UNREADABLE_FILE -> R.string.settings_problem_unreadable_file
        SettingsProblemKind.MALFORMED -> R.string.settings_problem_malformed
        SettingsProblemKind.INVALID_VALUE -> R.string.settings_problem_invalid_value
        SettingsProblemKind.UNSUPPORTED_VERSION -> R.string.settings_problem_unsupported_version
        SettingsProblemKind.DUPLICATE_PROVIDER -> R.string.settings_problem_duplicate_provider
        SettingsProblemKind.DUPLICATE_MODEL -> R.string.settings_problem_duplicate_model
        SettingsProblemKind.UNSUPPORTED_PROTOCOL -> R.string.settings_problem_unsupported_protocol
        SettingsProblemKind.UNKNOWN_PRESET -> R.string.settings_problem_unknown_preset
        SettingsProblemKind.OPTIONS_DO_NOT_FIT -> R.string.settings_problem_options_do_not_fit
        SettingsProblemKind.NO_MAIN_MODEL -> R.string.settings_problem_no_main_model
        SettingsProblemKind.UNKNOWN_MODEL -> R.string.settings_problem_unknown_model
        SettingsProblemKind.MAIN_WITHOUT_TOOLS -> R.string.settings_problem_main_without_tools
        SettingsProblemKind.VISION_WITHOUT_IMAGES -> R.string.settings_problem_vision_without_images
    }
