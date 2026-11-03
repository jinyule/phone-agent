package dev.phoneagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phoneagent.app.ui.settings.ModelSettingsScreen
import dev.phoneagent.app.ui.settings.ModelSettingsViewModel

class MainActivity : ComponentActivity() {
    private val settings: ModelSettingsViewModel by viewModels { ModelSettingsViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                AppRoot(settings)
            }
        }
    }
}

/** The app's destinations, as tabs. */
internal enum class Destination {
    CHAT,
    MODELS,
}

/** The shell: "Chat" (filled in once tasks run) and the model settings. */
@Composable
internal fun AppRoot(settings: ModelSettingsViewModel) {
    var destination by rememberSaveable { mutableStateOf(Destination.MODELS) }
    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = destination.ordinal) {
                Tab(
                    selected = destination == Destination.CHAT,
                    onClick = { destination = Destination.CHAT },
                    text = { Text(stringResource(R.string.nav_chat)) },
                )
                Tab(
                    selected = destination == Destination.MODELS,
                    onClick = { destination = Destination.MODELS },
                    text = { Text(stringResource(R.string.nav_models)) },
                )
            }
            when (destination) {
                Destination.CHAT -> ChatPlaceholder()
                Destination.MODELS -> SettingsDestination(settings)
            }
        }
    }
}

@Composable
private fun ChatPlaceholder() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(R.string.chat_placeholder), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingsDestination(viewModel: ModelSettingsViewModel) {
    val state by viewModel.settings.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val run by viewModel.run.collectAsStateWithLifecycle()
    ModelSettingsScreen(state, form, run, viewModel)
}
