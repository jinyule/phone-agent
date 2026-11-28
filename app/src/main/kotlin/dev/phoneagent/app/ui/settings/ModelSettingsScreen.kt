package dev.phoneagent.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.phoneagent.app.R
import dev.phoneagent.app.model.SettingsState
import dev.phoneagent.app.model.credential.CredentialStatus
import dev.phoneagent.app.model.settings.ModelSettings
import dev.phoneagent.app.model.setup.AuthChoice
import dev.phoneagent.app.model.setup.FormCheck
import dev.phoneagent.app.model.setup.FormHint
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.app.model.setup.storedKeyRef
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.openai.ProviderPresets

/**
 * The model settings: a full-page error with a reset when the saved settings cannot be used;
 * otherwise the current setup (asking for keys that must be entered again), the form, and the
 * result of the last "test and save", step by step. Every control of the form is disabled while
 * it is being tested.
 */
@Composable
internal fun ModelSettingsScreen(
    settings: SettingsState,
    form: ProviderForm,
    run: RunState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (settings) {
            SettingsState.Loading -> {
                Text(stringResource(R.string.settings_loading))
            }

            is SettingsState.Broken -> {
                BrokenSettings(settings, actions)
            }

            is SettingsState.Ready -> {
                CurrentSetup(settings)
                HorizontalDivider()
                ProviderFormSection(form, settings, run, actions)
                RunResult(run)
            }
        }
    }
}

@Composable
private fun BrokenSettings(
    broken: SettingsState.Broken,
    actions: SettingsActions,
) {
    Text(stringResource(R.string.settings_broken_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(settingsProblemText(broken.problem.kind)))
    broken.problem.where?.let { Text(stringResource(R.string.settings_broken_where, it)) }
    Text(stringResource(R.string.settings_broken_explain))
    Button(onClick = actions::reset) { Text(stringResource(R.string.settings_reset)) }
}

@Composable
private fun CurrentSetup(ready: SettingsState.Ready) {
    Text(stringResource(R.string.settings_current), style = MaterialTheme.typography.titleMedium)
    val settings = ready.settings
    if (settings == null) {
        Text(stringResource(R.string.settings_none))
        return
    }
    val follows = stringResource(R.string.settings_follows_main)
    for ((role, text) in ROLE_TEXTS) {
        val model = settings.roles[role]?.model?.value ?: follows
        Text(stringResource(text, model))
    }
    for ((provider, status) in ready.keys) {
        if (status != CredentialStatus.PRESENT) {
            Card {
                Text(
                    stringResource(R.string.settings_key_again, displayName(settings, provider.value)),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private val ROLE_TEXTS =
    listOf(
        ModelRole.MAIN to R.string.settings_role_main,
        ModelRole.LIGHT to R.string.settings_role_light,
        ModelRole.VISION to R.string.settings_role_vision,
    )

private fun displayName(
    settings: ModelSettings,
    provider: String,
): String =
    settings.providers
        .firstOrNull { it.profile.id.value == provider }
        ?.profile
        ?.displayName ?: provider

@Composable
private fun ProviderFormSection(
    form: ProviderForm,
    ready: SettingsState.Ready,
    run: RunState,
    actions: SettingsActions,
) {
    val enabled = run !is RunState.Testing
    Text(stringResource(R.string.form_title), style = MaterialTheme.typography.titleMedium)
    PresetChoice(form, enabled, actions)
    // Right under the provider: "None" is offered first, so local services do not get a placeholder key.
    AuthChoiceRow(form, enabled, actions)
    val keepsKey =
        ready.keys[form.providerId] == CredentialStatus.PRESENT && storedKeyRef(form, ready.settings) != null
    if (form.auth == AuthChoice.API_KEY) KeyField(form, keepsKey, enabled, actions)
    Field(form.baseUrl, TextField.BASE_URL, enabled) { actions.edit(form.copy(baseUrl = it)) }
    for (hint in FormCheck.hints(form)) {
        if (hint is FormHint.UsePreset) {
            Text(stringResource(R.string.hint_use_preset, hint.preset.displayName))
            OutlinedButton(onClick = { actions.choosePreset(hint.preset) }, enabled = enabled) {
                Text(stringResource(R.string.hint_use_preset_action))
            }
        }
    }
    if (form.presetId == null) ProtocolChoice(form, enabled, actions)
    Field(form.mainModel, TextField.MAIN_MODEL, enabled) { actions.edit(form.copy(mainModel = it)) }
    Field(form.lightModel, TextField.LIGHT_MODEL, enabled) { actions.edit(form.copy(lightModel = it)) }
    Field(form.visionModel, TextField.VISION_MODEL, enabled) { actions.edit(form.copy(visionModel = it)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
            checked = form.visionImages,
            onCheckedChange = { actions.edit(form.copy(visionImages = it)) },
            enabled = enabled,
        )
        Text(stringResource(R.string.form_vision_images))
    }
    Field(form.contextTokens?.toString().orEmpty(), TextField.CONTEXT, enabled) {
        actions.edit(form.copy(contextTokens = it.trim().toIntOrNull()))
    }
    Button(onClick = actions::testAndSave, enabled = enabled) {
        Text(stringResource(R.string.form_test_and_save))
    }
}

@Composable
private fun PresetChoice(
    form: ProviderForm,
    enabled: Boolean,
    actions: SettingsActions,
) {
    Text(stringResource(R.string.form_provider))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (preset in ProviderPresets.ALL) {
            FilterChip(
                selected = form.presetId == preset.id,
                onClick = { actions.choosePreset(preset) },
                label = { Text(preset.displayName) },
                enabled = enabled,
            )
        }
        FilterChip(
            selected = form.presetId == null,
            onClick = { actions.choosePreset(null) },
            label = { Text(stringResource(R.string.form_custom)) },
            enabled = enabled,
        )
    }
}

@Composable
private fun AuthChoiceRow(
    form: ProviderForm,
    enabled: Boolean,
    actions: SettingsActions,
) {
    Text(stringResource(R.string.form_auth))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = form.auth == AuthChoice.NONE,
            onClick = { actions.edit(form.copy(auth = AuthChoice.NONE)) },
            label = { Text(stringResource(R.string.form_auth_none)) },
            enabled = enabled,
        )
        FilterChip(
            selected = form.auth == AuthChoice.API_KEY,
            onClick = { actions.edit(form.copy(auth = AuthChoice.API_KEY)) },
            label = { Text(stringResource(R.string.form_auth_key)) },
            enabled = enabled,
        )
    }
}

@Composable
private fun KeyField(
    form: ProviderForm,
    hasStoredKey: Boolean,
    enabled: Boolean,
    actions: SettingsActions,
) {
    OutlinedTextField(
        value = form.apiKey,
        onValueChange = { actions.edit(form.copy(apiKey = it)) },
        enabled = enabled,
        label = { Text(stringResource(R.string.form_key)) },
        supportingText = if (hasStoredKey) ({ Text(stringResource(R.string.form_key_keep)) }) else null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag(SettingsTags.KEY),
    )
    if (FormHint.ShortKey in FormCheck.hints(form)) {
        Text(stringResource(R.string.hint_short_key), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ProtocolChoice(
    form: ProviderForm,
    enabled: Boolean,
    actions: SettingsActions,
) {
    Text(stringResource(R.string.form_protocol))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((protocol, text) in PROTOCOL_TEXTS) {
            FilterChip(
                selected = form.protocol == protocol,
                onClick = { actions.edit(form.copy(protocol = protocol)) },
                label = { Text(stringResource(text)) },
                enabled = enabled,
            )
        }
    }
}

private val PROTOCOL_TEXTS =
    listOf(
        ModelProtocol.OPENAI_CHAT_COMPLETIONS to R.string.form_protocol_chat,
        ModelProtocol.OPENAI_RESPONSES to R.string.form_protocol_responses,
    )

/** The form's plain text fields: label, test tag and keyboard. */
private enum class TextField(
    val label: Int,
    val tag: String,
    val keyboard: KeyboardType = KeyboardType.Uri,
) {
    BASE_URL(R.string.form_base_url, SettingsTags.BASE_URL),
    MAIN_MODEL(R.string.form_main_model, SettingsTags.MAIN_MODEL),
    LIGHT_MODEL(R.string.form_light_model, SettingsTags.LIGHT_MODEL),
    VISION_MODEL(R.string.form_vision_model, SettingsTags.VISION_MODEL),
    CONTEXT(R.string.form_context, SettingsTags.CONTEXT, KeyboardType.Number),
}

@Composable
private fun Field(
    value: String,
    field: TextField,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(stringResource(field.label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = field.keyboard),
        modifier = Modifier.fillMaxWidth().testTag(field.tag),
    )
}
