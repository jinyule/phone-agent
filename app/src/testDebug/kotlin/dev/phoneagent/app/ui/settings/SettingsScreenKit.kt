package dev.phoneagent.app.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import dev.phoneagent.app.model.SettingsState
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.core.model.openai.ProviderPreset

/** The app's string [id] with [args], as the screen shows it. */
internal fun text(
    id: Int,
    vararg args: Any,
): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

/** The settings screen on a live form: edits go back into it, like the view model does. */
internal class FakeSettingsScreen(
    compose: ComposeContentTestRule,
    start: ProviderForm,
    settings: SettingsState = SettingsState.Ready(null, emptyMap()),
    run: RunState = RunState.Idle,
) : SettingsActions {
    var form by mutableStateOf(start)
    val presets = mutableListOf<ProviderPreset?>()
    var tests = 0
    var resets = 0

    init {
        compose.setContent { MaterialTheme { ModelSettingsScreen(settings, form, run, this) } }
    }

    override fun edit(form: ProviderForm) {
        this.form = form
    }

    override fun choosePreset(preset: ProviderPreset?) {
        presets += preset
        form = form.switchedTo(preset)
    }

    override fun testAndSave() {
        tests++
    }

    override fun reset() {
        resets++
    }
}
