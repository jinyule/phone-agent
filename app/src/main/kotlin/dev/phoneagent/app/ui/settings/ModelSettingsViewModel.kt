package dev.phoneagent.app.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.phoneagent.app.model.ModelServices
import dev.phoneagent.app.model.SettingsState
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.app.model.setup.SetupOutcome
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.openai.ProviderPreset
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the settings screen can ask for. */
internal interface SettingsActions {
    /**
     * The user changed the form (ignored while it is being tested). Naming another main model
     * replaces the context window with that model's ([ProviderForm.editedFrom]).
     */
    fun edit(form: ProviderForm)

    /** The user picked [preset] (`null`: custom); what was typed stays (ignored while it is being tested). */
    fun choosePreset(preset: ProviderPreset?)

    /** "Test and save" of the current form. */
    fun testAndSave()

    /** Delete the settings and every key (offered when the settings file is broken). */
    fun reset()
}

/** Where a "test and save" run is. */
internal sealed interface RunState {
    /** Nothing run yet, or the form changed since. */
    data object Idle : RunState

    /** Checking [model] (`null` before the first one). */
    data class Testing(
        val model: ModelId?,
    ) : RunState

    /** Finished with [outcome]. */
    data class Done(
        val outcome: SetupOutcome,
    ) : RunState

    /** Ended by something unexpected; only its class was logged. */
    data object Error : RunState
}

/**
 * The settings screen's state. The form, key included, lives only here, in memory: it is not put
 * into the saved-state bundle, so the key never reaches disk outside the credential store.
 *
 * Nothing its work throws reaches the thread's uncaught handler: an unexpected failure ends as
 * [RunState.Error] (cancellation, when the screen goes away, is not a failure).
 *
 * While a "test and save" runs ([RunState.Testing]) the form cannot change: the screen disables it
 * and edits are ignored. The form a saved run shows afterwards is therefore the one it tested,
 * never one that replaced later input.
 */
internal class ModelSettingsViewModel(
    private val services: ModelServices,
) : ViewModel(),
    SettingsActions {
    /** The settings as loaded or saved. */
    val settings: StateFlow<SettingsState> = services.state

    private val currentForm = MutableStateFlow(ProviderForm.start(null))

    /** The form being edited. */
    val form: StateFlow<ProviderForm> = currentForm.asStateFlow()

    private val currentRun = MutableStateFlow<RunState>(RunState.Idle)

    /** The last "test and save". */
    val run: StateFlow<RunState> = currentRun.asStateFlow()

    private val failures =
        CoroutineExceptionHandler { _, failure ->
            // Only the class: a message can quote the form, key included.
            Log.w(TAG, "settings work failed (${failure.javaClass.name})")
            currentRun.value = RunState.Error
        }

    init {
        viewModelScope.launch(failures) {
            val loaded = services.reload()
            if (loaded is SettingsState.Ready) currentForm.value = ProviderForm.of(loaded.settings)
        }
    }

    override fun edit(form: ProviderForm) {
        if (currentRun.value is RunState.Testing) return
        val saved = (services.state.value as? SettingsState.Ready)?.settings
        currentForm.value = form.editedFrom(currentForm.value, saved)
    }

    override fun choosePreset(preset: ProviderPreset?) {
        if (currentRun.value is RunState.Testing) return
        currentForm.value = currentForm.value.switchedTo(preset)
    }

    override fun testAndSave() {
        if (currentRun.value is RunState.Testing) return
        currentRun.value = RunState.Testing(null)
        val form = currentForm.value
        viewModelScope.launch(failures) {
            val outcome = services.testAndSave(form) { currentRun.value = RunState.Testing(it) }
            if (outcome is SetupOutcome.Saved) currentForm.value = ProviderForm.of(outcome.settings)
            currentRun.value = RunState.Done(outcome)
        }
    }

    override fun reset() {
        viewModelScope.launch(failures) {
            services.reset()
            currentForm.value = ProviderForm.start(null)
            currentRun.value = RunState.Idle
        }
    }

    companion object {
        private const val TAG = "PhoneAgentSettings"

        /** Builds the view model on the process-wide [ModelServices]. */
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    ModelSettingsViewModel(ModelServices.of(app))
                }
            }
    }
}
