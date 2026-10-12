package com.hermes.harness

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.harness.agent.Attachment
import com.hermes.harness.ui.ChatScreen
import com.hermes.harness.ui.ChatViewModel
import com.hermes.harness.ui.settings.SettingsScreen
import com.hermes.harness.ui.schedule.ScheduleScreen
import com.hermes.harness.ui.theme.HermesTheme

/** Whatever another app handed over: text, an image, or both. */
private data class SharedPayload(val text: String?, val imageUri: String?)

class MainActivity : ComponentActivity() {

    /**
     * Payload from another app.
     *
     * `launchMode="singleTask"` means a second share reuses this instance and
     * arrives through [onNewIntent], so the value has to be observable rather
     * than read once in `onCreate`.
     */
    private val sharedPayload = mutableStateOf<SharedPayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sharedPayload.value = extractShared(intent)
        setContent {
            HermesTheme {
                HermesRoot(
                    sharedPayload = sharedPayload,
                    onSharedConsumed = { sharedPayload.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedPayload.value = extractShared(intent)
    }

    private fun extractShared(intent: Intent?): SharedPayload? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (text == null && stream == null) null else SharedPayload(text, stream?.toString())
            }

            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { SharedPayload(it, null) }

            else -> null
        }
    }
}

/**
 * Two screens, one view model: chat is the product, settings is a modal detour.
 *
 * `rememberSaveable` keeps the detour across rotation and process death, which
 * matters because a configuration change mid-approval would otherwise drop the
 * user back into the transcript and hide the pending decision.
 */
@Composable
private fun HermesRoot(
    sharedPayload: State<SharedPayload?>,
    onSharedConsumed: () -> Unit,
) {
    val viewModel: ChatViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSchedule by rememberSaveable { mutableStateOf(false) }

    // Notifications are how a scheduled run reaches the user, so ask once, early.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denial is surfaced by the notify tool when it matters. */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(sharedPayload.value) {
        sharedPayload.value?.let { payload ->
            payload.text?.let(viewModel::acceptSharedText)
            payload.imageUri?.let { uri ->
                viewModel.addAttachment(
                    Attachment(
                        uri = uri,
                        mimeType = context.contentResolver.getType(Uri.parse(uri)) ?: "image/jpeg",
                        label = "compartilhada",
                    ),
                )
            }
            onSharedConsumed()
        }
    }

    // Ask for the microphone only when the user actually reaches for it, and start
    // dictation the moment it is granted so the tap does not feel dropped.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startVoiceInput()
        }
    }

    val requestVoiceInput: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startVoiceInput()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    when {
        showSettings -> SettingsScreen(
            settings = state.settings,
            availableModels = state.availableModels,
            isBusy = state.isBusy,
            statusMessage = state.statusMessage,
            statusIsError = state.statusIsError,
            onApiKeyChange = viewModel::updateApiKey,
            onBaseUrlChange = viewModel::updateBaseUrl,
            onGatewayApiKeyChange = viewModel::updateGatewayApiKey,
            onModelChange = viewModel::updateModel,
            onTemperatureChange = viewModel::updateTemperature,
            onFetchModels = viewModel::fetchModels,
            onTestConnection = viewModel::testConnection,
            onRevokeApproval = viewModel::revokeApproval,
            onClearApprovals = viewModel::clearApprovals,
            onBack = { showSettings = false },
        )

        showSchedule -> ScheduleScreen(
            tasks = state.scheduledTasks,
            onBack = { showSchedule = false },
            onSave = viewModel::saveTask,
            onDelete = viewModel::deleteTask,
            onRunNow = viewModel::runTaskNow,
        )

        else -> ChatScreen(
            state = state,
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onApproval = viewModel::resolveApproval,
            onNewConversation = viewModel::newConversation,
            onOpenConversation = viewModel::openConversation,
            onDeleteConversation = viewModel::deleteConversation,
            onOpenSettings = { showSettings = true },
            onDismissError = viewModel::dismissError,
            onSharedDraftConsumed = viewModel::consumeSharedDraft,
            onAttach = viewModel::addAttachment,
            onRemoveAttachment = viewModel::removeAttachment,
            onVoiceInput = requestVoiceInput,
            onStopVoice = viewModel::stopVoiceInput,
            onVoiceDraftConsumed = viewModel::consumeVoiceDraft,
            onToggleSpeak = viewModel::toggleSpeakReplies,
            onStopSpeaking = viewModel::stopSpeaking,
            onOpenSchedule = { showSchedule = true },
            onUseLocal = viewModel::useOnDeviceModel,
            onUsePc = viewModel::useDesktopBridge,
        )
    }
}
