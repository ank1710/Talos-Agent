package com.hermes.harness.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.harness.agent.AgentEvent
import com.hermes.harness.agent.AgentLoop
import com.hermes.harness.agent.ApprovalDecision
import com.hermes.harness.agent.ApprovalGate
import com.hermes.harness.agent.ApprovalRequest
import com.hermes.harness.agent.Attachment
import com.hermes.harness.agent.ChatMessage
import com.hermes.harness.agent.Conversation
import com.hermes.harness.agent.MessageStatus
import com.hermes.harness.agent.Risk
import com.hermes.harness.agent.Role
import com.hermes.harness.agent.SystemPrompt
import com.hermes.harness.agent.newId
import com.hermes.harness.gateway.GatewayApprovalClient
import com.hermes.harness.gateway.GatewayApprovalGate
import com.hermes.harness.gateway.decisionForGatewayChoice
import com.hermes.harness.gateway.gatewayChoiceFor
import com.hermes.harness.llm.DeepSeekClient
import com.hermes.harness.llm.StreamEvent
import com.hermes.harness.llm.WireUsage
import com.hermes.harness.media.MediaResolver
import com.hermes.harness.schedule.ScheduleScheduler
import com.hermes.harness.schedule.ScheduledTask
import com.hermes.harness.schedule.ScheduledTaskStore
import com.hermes.harness.store.ConversationStore
import com.hermes.harness.store.ConversationSummary
import com.hermes.harness.store.HermesSettings
import com.hermes.harness.store.SettingsRepository
import com.hermes.harness.tools.CalendarReadTool
import com.hermes.harness.tools.ClipboardReadTool
import com.hermes.harness.tools.ClipboardWriteTool
import com.hermes.harness.tools.ContactsSearchTool
import com.hermes.harness.tools.DeviceInfoTool
import com.hermes.harness.tools.FetchUrlTool
import com.hermes.harness.tools.ListAppsTool
import com.hermes.harness.tools.ListDirTool
import com.hermes.harness.tools.NotifyTool
import com.hermes.harness.tools.OpenUrlTool
import com.hermes.harness.tools.ReadFileTool
import com.hermes.harness.tools.ShellTool
import com.hermes.harness.tools.ToolContext
import com.hermes.harness.tools.ToolRegistry
import com.hermes.harness.tools.WriteFileTool
import com.hermes.harness.voice.VoiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

data class ChatUiState(
    val conversation: Conversation = Conversation(),
    val conversations: List<ConversationSummary> = emptyList(),
    val settings: HermesSettings = HermesSettings(),
    val isRunning: Boolean = false,
    val pendingApproval: ApprovalRequest? = null,
    val usage: WireUsage? = null,
    val error: String? = null,
    val availableModels: List<String> = emptyList(),
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val statusIsError: Boolean = false,
    /** True when calls are routed through the Hermes gateway on the desktop. */
    val delegatingToPc: Boolean = false,
    /** Text handed to the app from another app's share sheet, awaiting the composer. */
    val sharedDraft: String? = null,
    /**
     * Live dictation transcript. Kept separate from [sharedDraft] because partial
     * results must *replace* the composer as the user keeps talking, whereas shared
     * text is appended.
     */
    val voiceDraft: String? = null,
    /** Images staged in the composer, not yet sent. */
    val draftAttachments: List<Attachment> = emptyList(),
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    /** When on, finished assistant turns are read aloud. Off by default. */
    val speakReplies: Boolean = false,
    val voiceAvailable: Boolean = true,
    /** Unattended tasks, mirrored from disk so the UI never reads files itself. */
    val scheduledTasks: List<ScheduledTask> = emptyList(),
) {
    val needsSetup: Boolean get() = !settings.isConfigured

    /** Vision needs a multimodal model; a text-only model would silently ignore the pixels. */
    val supportsVision: Boolean
        get() = settings.model.contains("flash", ignoreCase = true) ||
            settings.model.contains("vl", ignoreCase = true)
}

/**
 * Owns the conversation, drives [AgentLoop], and answers approval requests.
 *
 * The approval gate is the interesting part: the loop suspends on a
 * [CompletableDeferred] that this view model completes when the user taps a
 * button. Because the gate is just an interface, the same pause can later be
 * answered from a notification or from the desktop.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Context = app
    private val settingsRepo = SettingsRepository(app)
    private val taskStore = ScheduledTaskStore(app.filesDir)
    private val voice = VoiceController(app)
    private val conversationStore = ConversationStore(app.filesDir)
    private val client = DeepSeekClient()
    private val gatewayClient = GatewayApprovalClient()

    private val workspace = File(app.filesDir, "workspace").apply { mkdirs() }
    private val toolContext = ToolContext(app, workspace)
    private val registry = ToolRegistry(
        listOf(
            // Filesystem and shell.
            ShellTool(),
            ReadFileTool(),
            WriteFileTool(),
            ListDirTool(),
            // Device integration.
            DeviceInfoTool(),
            NotifyTool(),
            ClipboardReadTool(),
            ClipboardWriteTool(),
            OpenUrlTool(),
            ListAppsTool(),
            CalendarReadTool(),
            ContactsSearchTool(),
            // Network. CAUTION: an unattended run must not be able to POST your
            // data anywhere without you opting that task in.
            FetchUrlTool(),
        ),
    )

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var runJob: Job? = null
    private var approvalDeferred: CompletableDeferred<ApprovalDecision>? = null

    /** Mirrors the persisted allowlist so the gate can answer without I/O. */
    private var autoApproved: Set<String> = emptySet()

    private val approvalGate = object : ApprovalGate {
        override suspend fun request(request: ApprovalRequest): ApprovalDecision {
            if (request.toolName in autoApproved) return ApprovalDecision.APPROVE_ONCE

            val deferred = CompletableDeferred<ApprovalDecision>()
            approvalDeferred = deferred
            _state.update { it.copy(pendingApproval = request) }
            return try {
                deferred.await()
            } finally {
                approvalDeferred = null
                _state.update { it.copy(pendingApproval = null) }
            }
        }
    }

    /**
     * Gate used while the desktop gateway is the one running the agent.
     *
     * Two sources park on it and both end up in the same dialog:
     *  - a local tool the phone itself runs while in bridge mode (the loop calls
     *    [ApprovalGate.request] as usual), and
     *  - a prompt the *desktop* agent raised, which arrives as an SSE control
     *    frame and is fed in by [onGatewayFrame].
     *
     * It publishes the prompt through `onPending` exactly like [approvalGate]
     * above, so `ChatScreen` needs no second dialog.
     */
    private val gatewayGate = GatewayApprovalGate(
        onPending = { request -> _state.update { it.copy(pendingApproval = request) } },
        onSettled = { request ->
            _state.update { current ->
                if (current.pendingApproval?.id == request.id) {
                    current.copy(pendingApproval = null)
                } else {
                    current
                }
            }
        },
    )

    init {
        // Gateway control frames carry no chat chunk and AgentLoop must not be
        // touched to forward them, so the client this view model owns hands them
        // over directly.
        client.onGatewayEvent = ::onGatewayFrame

        viewModelScope.launch {
            settingsRepo.flow.collect { settings ->
                autoApproved = settings.autoApprovedTools
                _state.update {
                    it.copy(
                        settings = settings,
                        delegatingToPc = settings.usingDesktopBridge,
                    )
                }
            }
        }
        refreshConversations()
        refreshTasks()

        _state.update { it.copy(voiceAvailable = voice.isRecognitionAvailable()) }

        // Mirror the controller's own state instead of duplicating its bookkeeping:
        // recognition can end without us asking (silence, timeout, a phone call),
        // and a listener that only trusts its own flags would show a stuck mic.
        viewModelScope.launch {
            while (true) {
                val listening = voice.isListening
                val speaking = voice.isSpeaking
                val current = _state.value
                if (current.isListening != listening || current.isSpeaking != speaking) {
                    _state.update { it.copy(isListening = listening, isSpeaking = speaking) }
                }
                delay(VOICE_POLL_MS)
            }
        }
    }

    override fun onCleared() {
        voice.shutdown()
        super.onCleared()
    }

    // ---------------------------------------------------------------- chat flow

    fun send(text: String) {
        val prompt = text.trim()
        val staged = _state.value.draftAttachments
        // A message can be image-only: "what is this?" is implied by the picture.
        if ((prompt.isEmpty() && staged.isEmpty()) || _state.value.isRunning) return

        val settings = _state.value.settings
        if (!settings.isConfigured) {
            _state.update { it.copy(error = "Configure sua chave da API DeepSeek em Ajustes.") }
            return
        }

        val conversation = _state.value.conversation
        val isFirstUserTurn = conversation.messages.none { it.role == Role.USER }
        val withUser = conversation.copy(
            title = if (isFirstUserTurn) deriveTitle(prompt.ifBlank { "Imagem" }) else conversation.title,
            messages = conversation.messages + ChatMessage(
                role = Role.USER,
                content = prompt,
                attachments = staged,
            ),
            updatedAt = System.currentTimeMillis(),
        )
        _state.update {
            it.copy(
                conversation = withUser,
                error = null,
                usage = null,
                draftAttachments = emptyList(),
            )
        }
        persist(withUser)
        startRun()
    }

    /** Cancels the in-flight turn; whatever streamed so far stays in the transcript. */
    fun stop() {
        runJob?.cancel()
        runJob = null
        approvalDeferred?.cancel()
        approvalDeferred = null
        // A parked remote prompt must not outlive the run that raised it.
        gatewayGate.cancel()
        gatewayGate.clear()
        _state.update { it.copy(isRunning = false, pendingApproval = null) }
        persist(_state.value.conversation)
    }

    /**
     * Handles the user's answer to whatever prompt is on screen.
     *
     * A prompt from the desktop is answered over HTTP — the desktop is the side
     * that is blocked — while a local tool call only needs its coroutine
     * resumed. [GatewayApprovalGate.runId] tells the two apart.
     */
    fun resolveApproval(decision: ApprovalDecision) {
        val request = _state.value.pendingApproval
        val runId = gatewayGate.runId
        if (runId != null) {
            val choice = gatewayChoiceFor(decision, gatewayGate.choices)
            val requestId = gatewayGate.requestId
            gatewayGate.submit(decision)
            gatewayGate.clear()
            _state.update { it.copy(pendingApproval = null) }
            viewModelScope.launch { postDecision(runId, requestId, choice) }
            return
        }

        if (decision == ApprovalDecision.APPROVE_ALWAYS && request != null) {
            viewModelScope.launch { settingsRepo.approveAlways(request.toolName) }
        }
        approvalDeferred?.complete(decision)
        // The bridge-mode gate parks local tools too; a no-op in the other mode.
        gatewayGate.submit(decision)
    }

    /**
     * Wires a gateway frame into the approval UI.
     *
     * `approval.request` is the whole point of this feature: the desktop agent
     * has paused and is waiting for a decision, so the prompt is published
     * through [gatewayGate] (same dialog as any other approval) and the ids the
     * answer needs are attached to it. `approval.responded` means the prompt is
     * already gone — answered elsewhere or expired — so ours is taken down.
     */
    private fun onGatewayFrame(event: StreamEvent) {
        when (event) {
            is StreamEvent.GatewayApprovalRequested -> {
                gatewayGate.attach(event.runId, event.requestId, event.choices)
                // Only the advertised decisions become buttons; an unknown choice
                // maps to null and is dropped rather than rendered as nothing.
                val offered = event.choices
                    .mapNotNull(::decisionForGatewayChoice)
                    .distinct()
                    .ifEmpty { listOf(ApprovalDecision.APPROVE_ONCE, ApprovalDecision.REJECT) }

                viewModelScope.launch {
                    gatewayGate.request(
                        ApprovalRequest(
                            id = event.requestId ?: newId(),
                            toolName = "ação no computador",
                            arguments = event.command,
                            preview = event.command.ifBlank { "O PC pediu permissão para continuar." },
                            // The desktop's policy decided to ask; we cannot know
                            // how risky it is, and the safe default is "look".
                            risk = Risk.CAUTION,
                            choices = offered,
                        ),
                    )
                }
            }

            is StreamEvent.GatewayApprovalResolved -> {
                // Another prompt on the same run may be the one that was answered;
                // only take ours down when the id matches (or was never told).
                val mine = gatewayGate.requestId
                if (event.requestId == null || mine == null || event.requestId == mine) {
                    gatewayGate.cancel()
                    gatewayGate.clear()
                    _state.update { it.copy(pendingApproval = null) }
                }
            }

            else -> Unit
        }
    }

    /**
     * Sends an answer to the desktop.
     *
     * A 409 (`approval_not_pending`) is a soft outcome: the prompt was already
     * answered or expired, which needs no error banner — just a note.
     */
    private suspend fun postDecision(runId: String, requestId: String?, choice: String) {
        val settings = _state.value.settings
        // The run lives on the gateway; if the user switched back to DeepSeek
        // while the dialog was open, there is no endpoint left to answer — and
        // posting the chat key at api.deepseek.com would be nonsense.
        if (!settings.usingDesktopBridge) {
            _state.update {
                it.copy(
                    statusMessage = "Modo alterado: a resposta não foi enviada ao PC.",
                    statusIsError = true,
                )
            }
            return
        }
        gatewayClient
            .respond(
                baseUrl = settings.baseUrl,
                apiKey = settings.effectiveApiKey,
                runId = runId,
                choice = choice,
                requestId = requestId,
            )
            .onSuccess {
                _state.update { it.copy(statusMessage = "Resposta enviada ao PC.", statusIsError = false) }
            }
            .onFailure { error ->
                if (error is GatewayApprovalClient.AlreadyResolved) {
                    _state.update {
                        it.copy(
                            statusMessage = error.message ?: "Este pedido já foi respondido.",
                            statusIsError = false,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(error = error.message ?: "Não consegui enviar a resposta ao PC.")
                    }
                }
            }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Text arrived from another app (share sheet or the text-selection menu).
     *
     * It lands in the composer rather than being sent straight off: the user
     * should always get to see and edit what the agent is about to receive.
     */
    fun acceptSharedText(text: String) {
        if (text.isBlank()) return
        _state.update { it.copy(sharedDraft = text.trim()) }
    }

    fun consumeSharedDraft() = _state.update { it.copy(sharedDraft = null) }

    /**
     * Downscales and base64-encodes staged attachments.
     *
     * Failures are dropped rather than aborting the turn: losing one image should
     * not cost the user their message. `MediaResolver` caches, which matters
     * because the loop rebuilds the whole transcript on every step.
     */
    private val resolveImages: suspend (List<Attachment>) -> List<String> = { attachments ->
        attachments.mapNotNull { attachment ->
            MediaResolver.toDataUrl(appContext, attachment.uri)
        }
    }

    // -------------------------------------------------------------- attachments

    fun addAttachment(attachment: Attachment) {
        _state.update { current ->
            if (current.draftAttachments.any { it.uri == attachment.uri }) {
                current
            } else {
                current.copy(draftAttachments = current.draftAttachments + attachment)
            }
        }
    }

    fun removeAttachment(uri: String) {
        _state.update { current ->
            current.copy(draftAttachments = current.draftAttachments.filterNot { it.uri == uri })
        }
    }

    // -------------------------------------------------------------------- voice

    /**
     * Starts one dictation session. The transcript lands in the composer rather
     * than being sent, for the same reason shared text does: speech recognition
     * mishears, and the user should get to fix it before the agent acts.
     */
    fun startVoiceInput() {
        if (!voice.isRecognitionAvailable()) {
            _state.update { it.copy(error = "Reconhecimento de voz indisponível neste aparelho.") }
            return
        }
        voice.startListening(
            onPartial = { partial ->
                if (partial.isNotBlank()) _state.update { it.copy(voiceDraft = partial) }
            },
            onFinal = { transcript -> _state.update { it.copy(voiceDraft = transcript) } },
            onError = { message -> _state.update { it.copy(error = message) } },
        )
    }

    fun stopVoiceInput() = voice.stopListening()

    fun consumeVoiceDraft() = _state.update { it.copy(voiceDraft = null) }

    fun toggleSpeakReplies() {
        val next = !_state.value.speakReplies
        if (!next) voice.stopSpeaking()
        _state.update { it.copy(speakReplies = next) }
    }

    fun stopSpeaking() = voice.stopSpeaking()

    // --------------------------------------------------------- scheduled tasks

    /**
     * Re-reads tasks and re-syncs WorkManager.
     *
     * Every mutation goes through here on purpose: the periodic worker is
     * enqueued only while at least one task is enabled, and letting the UI change
     * a task without re-syncing would leave the schedule and the list disagreeing.
     */
    fun refreshTasks() {
        viewModelScope.launch {
            val tasks = taskStore.list()
            _state.update { it.copy(scheduledTasks = tasks) }
            ScheduleScheduler.sync(appContext, tasks)
        }
    }

    fun saveTask(task: ScheduledTask) {
        viewModelScope.launch {
            taskStore.upsert(task)
            val tasks = taskStore.list()
            _state.update { it.copy(scheduledTasks = tasks) }
            ScheduleScheduler.sync(appContext, tasks)
        }
    }

    fun deleteTask(id: String) {
        viewModelScope.launch {
            taskStore.delete(id)
            val tasks = taskStore.list()
            _state.update { it.copy(scheduledTasks = tasks) }
            ScheduleScheduler.sync(appContext, tasks)
        }
    }

    fun runTaskNow(id: String) = ScheduleScheduler.runNow(appContext, id)

    private fun startRun() {
        val settings = _state.value.settings
        val history = _state.value.conversation.messages

        runJob?.cancel()
        _state.update { it.copy(isRunning = true) }

        runJob = viewModelScope.launch {
            val loop = AgentLoop(
                client = client,
                registry = registry,
                // Against the desktop, prompts come from its policy rather than
                // from this phone's allowlist, so the gateway gate answers them.
                approvals = if (settings.usingDesktopBridge) gatewayGate else approvalGate,
                toolContext = toolContext,
                resolveImages = resolveImages,
            )
            try {
                loop.run(
                    apiKey = settings.effectiveApiKey,
                    baseUrl = settings.baseUrl,
                    model = settings.effectiveModel,
                    systemPrompt = SystemPrompt.build(workspace.absolutePath),
                    history = history,
                    temperature = settings.temperature,
                ).collect(::handle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Erro inesperado ao falar com o modelo.") }
            } finally {
                _state.update { it.copy(isRunning = false) }
                persist(_state.value.conversation)
            }
        }
    }

    private fun handle(event: AgentEvent) {
        when (event) {
            is AgentEvent.TurnStarted -> upsert(
                ChatMessage(id = event.messageId, role = Role.ASSISTANT, status = MessageStatus.STREAMING),
            )

            is AgentEvent.TextDelta -> mutateMessage(event.messageId) {
                it.copy(content = it.content + event.text)
            }

            is AgentEvent.ReasoningDelta -> mutateMessage(event.messageId) {
                it.copy(reasoning = it.reasoning + event.text)
            }

            is AgentEvent.TurnFinished -> {
                upsert(event.message)
                // Read only settled answers aloud. Turns that request tools are
                // intermediate chatter; speaking them would talk over the work.
                if (_state.value.speakReplies &&
                    event.message.toolCalls.isEmpty() &&
                    event.message.content.isNotBlank()
                ) {
                    voice.speak(event.message.content)
                }
            }

            is AgentEvent.ToolStarted -> Unit

            is AgentEvent.ToolFinished -> upsert(event.message)

            // The gate already published the request through ChatUiState.
            is AgentEvent.ApprovalRequired -> Unit
            is AgentEvent.ApprovalResolved -> Unit

            is AgentEvent.UsageReported -> _state.update { it.copy(usage = event.usage) }

            is AgentEvent.Failed -> _state.update { current ->
                current.copy(
                    error = event.message,
                    conversation = current.conversation.copy(
                        messages = current.conversation.messages.map { message ->
                            if (message.status == MessageStatus.STREAMING) {
                                message.copy(status = MessageStatus.FAILED, error = event.message)
                            } else {
                                message
                            }
                        },
                    ),
                )
            }

            AgentEvent.Completed -> Unit
        }
    }

    private fun upsert(message: ChatMessage) {
        _state.update { current ->
            val existing = current.conversation.messages
            val next = if (existing.any { it.id == message.id }) {
                existing.map { if (it.id == message.id) message else it }
            } else {
                existing + message
            }
            current.copy(
                conversation = current.conversation.copy(
                    messages = next,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun mutateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _state.update { current ->
            current.copy(
                conversation = current.conversation.copy(
                    messages = current.conversation.messages.map { if (it.id == id) transform(it) else it },
                ),
            )
        }
    }

    // ----------------------------------------------------------- conversations

    fun newConversation() {
        stop()
        _state.update { it.copy(conversation = Conversation(), error = null, usage = null) }
    }

    fun openConversation(id: String) {
        stop()
        viewModelScope.launch {
            conversationStore.load(id)?.let { loaded ->
                _state.update { it.copy(conversation = loaded, error = null, usage = null) }
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationStore.delete(id)
            if (_state.value.conversation.id == id) {
                _state.update { it.copy(conversation = Conversation()) }
            }
            refreshConversations()
        }
    }

    fun refreshConversations() {
        viewModelScope.launch {
            _state.update { it.copy(conversations = conversationStore.list()) }
        }
    }

    private fun persist(conversation: Conversation) {
        val snapshot = conversation.copy(updatedAt = System.currentTimeMillis())
        viewModelScope.launch {
            conversationStore.save(snapshot)
            _state.update { it.copy(conversations = conversationStore.list()) }
        }
    }

    private fun deriveTitle(prompt: String): String =
        prompt.lineSequence().first().trim().take(48).ifBlank { "Nova conversa" }

    private companion object {
        /** How often the UI re-reads the controller's listening/speaking flags. */
        const val VOICE_POLL_MS = 400L
    }

    // ---------------------------------------------------------------- settings

    fun updateApiKey(value: String) = viewModelScope.launch { settingsRepo.setApiKey(value) }

    fun updateBaseUrl(value: String) = viewModelScope.launch { settingsRepo.setBaseUrl(value) }

    fun updateModel(value: String) = viewModelScope.launch { settingsRepo.setModel(value) }

    fun updateTemperature(value: Double) = viewModelScope.launch { settingsRepo.setTemperature(value) }

    /**
     * Routes requests through the Hermes gateway on the desktop.
     *
     * Base URL, model **and credential** all resolve from the mode: the gateway
     * answers to its own model id and authenticates with its own key, so switching
     * the URL alone would send the DeepSeek key to a server that rejects it.
     * [HermesSettings.effectiveApiKey] and friends encode that rule in one place,
     * shared with the unattended worker.
     */
    fun useDesktopBridge() {
        viewModelScope.launch {
            settingsRepo.setBaseUrl(HermesSettings.LOCAL_GATEWAY_BASE_URL)
            settingsRepo.setModel(HermesSettings.GATEWAY_MODEL)
            _state.update { it.copy(statusMessage = "Rodando no PC.", statusIsError = false) }
        }
    }

    /** Goes back to calling DeepSeek directly from the phone. */
    fun useOnDeviceModel() {
        viewModelScope.launch {
            settingsRepo.setBaseUrl(HermesSettings.DEFAULT_BASE_URL)
            settingsRepo.setModel(HermesSettings.DEFAULT_MODEL)
            _state.update { it.copy(statusMessage = "Rodando neste celular.", statusIsError = false) }
        }
    }

    fun updateGatewayApiKey(value: String) = viewModelScope.launch { settingsRepo.setGatewayApiKey(value) }

    fun revokeApproval(toolName: String) = viewModelScope.launch { settingsRepo.revokeApproval(toolName) }

    fun clearApprovals() = viewModelScope.launch { settingsRepo.clearApprovals() }

    fun fetchModels() {
        val settings = _state.value.settings
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, statusMessage = null) }
            val models = client.listModels(settings.apiKey, settings.baseUrl)
            _state.update {
                it.copy(
                    isBusy = false,
                    availableModels = models,
                    statusMessage = if (models.isEmpty()) {
                        "Nenhum modelo retornado. Confira a chave e a URL base."
                    } else {
                        "${models.size} modelos disponíveis."
                    },
                    statusIsError = models.isEmpty(),
                )
            }
        }
    }

    fun testConnection() {
        val settings = _state.value.settings
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, statusMessage = "Testando conexão…", statusIsError = false) }
            val failure = client.probe(settings.apiKey, settings.baseUrl)
            _state.update {
                it.copy(
                    isBusy = false,
                    statusMessage = failure ?: "Conexão estabelecida com sucesso.",
                    statusIsError = failure != null,
                )
            }
        }
    }
}
