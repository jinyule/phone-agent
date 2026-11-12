package dev.phoneagent.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phoneagent.app.ui.chat.ChatActions
import dev.phoneagent.app.ui.chat.ChatScreen
import dev.phoneagent.app.ui.chat.ChatViewModel
import dev.phoneagent.app.ui.settings.ModelSettingsScreen
import dev.phoneagent.app.ui.settings.ModelSettingsViewModel

class MainActivity : ComponentActivity() {
    private val settings: ModelSettingsViewModel by viewModels { ModelSettingsViewModel.Factory }
    private val chat: ChatViewModel by viewModels { ChatViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                AppRoot(chat, settings)
            }
        }
    }
}

/** The app's destinations, as tabs. */
internal enum class Destination {
    CHAT,
    MODELS,
}

/**
 * The shell: the chat with the task (where it opens) and the model settings. Back from the model
 * settings returns to the chat; back from the chat leaves the app.
 */
@Composable
internal fun AppRoot(
    chat: ChatViewModel,
    settings: ModelSettingsViewModel,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.CHAT) }
    BackHandler(enabled = destination != Destination.CHAT) { destination = Destination.CHAT }
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
                Destination.CHAT -> ChatDestination(chat) { destination = Destination.MODELS }
                Destination.MODELS -> SettingsDestination(settings)
            }
        }
    }
}

/**
 * The chat. The first command asks for the notification permission (API 33+) where it is not
 * granted: the task's notification is how the user reaches it, Stop included, while the agent
 * drives the screen. The task runs whatever the answer.
 */
@Composable
private fun ChatDestination(
    viewModel: ChatViewModel,
    onModels: () -> Unit,
) {
    val view by viewModel.view.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // What is sent waits for the answer to the notification permission, so the dialog is gone before
    // the task looks at the screen; granted or not, it then goes.
    var waiting by rememberSaveable { mutableStateOf<String?>(null) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            waiting?.let(viewModel::send)
            waiting = null
        }
    val actions =
        remember(viewModel) {
            object : ChatActions by viewModel {
                override fun send(text: String) {
                    val ask =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                    if (ask) {
                        waiting = text
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.send(text)
                    }
                }
            }
        }
    ChatScreen(view, actions, onModels)
}

@Composable
private fun SettingsDestination(viewModel: ModelSettingsViewModel) {
    val state by viewModel.settings.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val run by viewModel.run.collectAsStateWithLifecycle()
    ModelSettingsScreen(state, form, run, viewModel)
}
