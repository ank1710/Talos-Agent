package com.talos.harness.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.talos.harness.agent.AgentEvent
import com.talos.harness.agent.AgentLoop
import com.talos.harness.agent.ApprovalDecision
import com.talos.harness.agent.ApprovalGate
import com.talos.harness.agent.ApprovalRequest
import com.talos.harness.agent.Attachment
import com.talos.harness.agent.ChatMessage
import com.talos.harness.agent.Conversation
import com.talos.harness.agent.MessageStatus
import com.talos.harness.agent.Risk
import com.talos.harness.agent.Role
import com.talos.harness.agent.PersistentContext
import com.talos.harness.store.MemoryKind
import com.talos.harness.agent.SystemPrompt
import com.talos.harness.agent.newId
import com.talos.harness.gateway.GatewayApprovalClient
import com.talos.harness.gateway.GatewayApprovalGate
import com.talos.harness.gateway.decisionForGatewayChoice
import com.talos.harness.gateway.gatewayOrigin
import com.talos.harness.gateway.gatewayChoiceFor
import com.talos.harness.llm.ChatBackend
import com.talos.harness.llm.DeepSeekClient
import com.talos.harness.llm.StreamEvent
import com.talos.harness.llm.WireUsage
import com.talos.harness.local.BenchmarkResult
import com.talos.harness.local.DeviceCapability
import com.talos.harness.local.DeviceProfile
import com.talos.harness.local.HuggingFaceModelBundle
import com.talos.harness.local.HuggingFaceModelCatalog
import com.talos.harness.local.HuggingFaceModelInstaller
import com.talos.harness.local.LocalBackend
import com.talos.harness.local.ModelFile
import com.talos.harness.local.ModelRecommendation
import com.talos.harness.local.ModelStore
import com.talos.harness.local.OnDeviceBackend
import com.talos.harness.local.benchmark
import com.talos.harness.media.MediaResolver
import com.talos.harness.schedule.ScheduleScheduler
import com.talos.harness.schedule.ScheduledTask
import com.talos.harness.schedule.ScheduledTaskStore
import com.talos.harness.store.ConnectionDraft
import com.talos.harness.store.MemoryEntry
import com.talos.harness.store.MemoryStore
import com.talos.harness.store.SkillEntry
import com.talos.harness.store.SkillStore
import com.talos.harness.store.ConversationStore
import com.talos.harness.store.ConversationSummary
import com.talos.harness.store.ModelMode
import com.talos.harness.store.ProviderCatalog
import com.talos.harness.store.ProviderModel
import com.talos.harness.store.TalosSettings
import com.talos.harness.store.SettingsRepository
import com.talos.harness.tools.CalendarReadTool
import com.talos.harness.tools.ClipboardReadTool
import com.talos.harness.tools.ClipboardWriteTool
import com.talos.harness.tools.ContactsSearchTool
import com.talos.harness.tools.DeviceInfoTool
import com.talos.harness.tools.FetchUrlTool
import com.talos.harness.tools.ListAppsTool
import com.talos.harness.tools.ListDirTool
import com.talos.harness.tools.MemoryTool
import com.talos.harness.tools.SkillTool
import com.talos.harness.tools.SessionSearchTool
import com.talos.harness.tools.NotifyTool
import com.talos.harness.tools.OpenUrlTool
import com.talos.harness.tools.ReadFileTool
import com.talos.harness.tools.ShellTool
import com.talos.harness.tools.SpawnSubagentTool
import com.talos.harness.tools.Tool
import com.talos.harness.tools.ToolContext
import com.talos.harness.tools.ToolRegistry
import com.talos.harness.tools.WriteFileTool
import com.talos.harness.voice.VoiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

data class ChatUiState(
    val conversation: Conversation = Conversation(),
    val conversations: List<ConversationSummary> = emptyList(),
    val settings: TalosSettings = TalosSettings(),
    val isRunning: Boolean = false,
    val pendingApproval: ApprovalRequest? = null,
    val usage: WireUsage? = null,
    val error: String? = null,
    val availableModels: List<ProviderModel> = emptyList(),
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val statusIsError: Boolean = false,
    /** True when calls are routed through the Talos gateway on the desktop. */
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
    /**
     * Hands-free conversation: listen, answer aloud, then listen again, with no taps.
     *
     * Distinct from [speakReplies], which only reads answers out. Turning this on
     * also implies speaking, because a conversation the user cannot hear is not a
     * conversation.
     */
    val conversationMode: Boolean = false,
    val voiceAvailable: Boolean = true,
    /** Unattended tasks, mirrored from disk so the UI never reads files itself. */
    val scheduledTasks: List<ScheduledTask> = emptyList(),
    /** `.litertlm` bundles on disk, mirrored so the UI never touches storage. */
    val installedModels: List<ModelFile> = emptyList(),
    /** Where a bundle has to be copied; shown when nothing is installed. */
    val modelsDirectory: String = "",
    /**
     * What the device probe measured, or null when the probe itself failed.
     * The UI shows [ModelRecommendation.explanation] verbatim: it is the honest
     * reason for the backend and size class the phone was given.
     */
    val deviceRecommendation: ModelRecommendation? = null,
    /**
     * Name of the bundle being measured right now, or null when idle. The row
     * for this model shows the progress and the cancel button.
     */
    val benchmarkingModel: String? = null,
    /** What the benchmark is doing, in Portuguese, for the row above. */
    val benchmarkStage: String? = null,
    /**
     * Last measurement per model name. Keyed by name, not by [ModelFile],
     * because the list is re-read from disk and two reads of the same bundle
     * must land on the same entry.
     */
    val benchmarkResults: Map<String, BenchmarkResult> = emptyMap(),
    /** Remote LiteRT-LM bundles found in the public Hugging Face catalog. */
    val localModelBundles: List<HuggingFaceModelBundle> = emptyList(),
    val localModelSearchQuery: String? = null,
    val localModelSearchBusy: Boolean = false,
    val localModelSearchError: String? = null,
    val localModelDownload: LocalModelDownloadStatus? = null,
    val memories: List<MemoryEntry> = emptyList(),
    val skills: List<SkillEntry> = emptyList(),
    val memoryError: String? = null,
    val skillsError: String? = null,
    val persistenceBusy: Boolean = false,
    val memoryDirectory: String = "",
    val skillsDirectory: String = "",
) {
    val persistentToolsAvailable: Boolean get() = !PersistentContext.useCompact(onDevice, settings.model)

    val skillExecutionUnavailable: String? get() = when {
        isRunning -> "Espere a tarefa atual terminar para executar uma skill."
        persistenceBusy -> "Uma alteração está sendo salva."
        needsSetup -> "Configure a conexão e a chave do modelo para executar skills."
        onDevice && installedModels.none { it.name == onDeviceModel } -> "Selecione um modelo instalado no aparelho."
        !persistentToolsAvailable -> "O modelo selecionado tem janela pequena. Escolha um modelo com pelo menos 4096 tokens."
        else -> null
    }

    val needsSetup: Boolean get() = !settings.isConfigured

    /**
     * The bundle the phone is told to load: [TalosSettings.model] read through
     * the state. It is the user's explicit choice when they made one — including
     * one the capability probe rejects — and the automatic choice otherwise.
     */
    val onDeviceModel: String get() = settings.model

    /** True when the weights run on this phone. */
    val onDevice: Boolean get() = settings.mode == ModelMode.ON_DEVICE

    /** Vision needs a multimodal model; a text-only model would silently ignore the pixels. */
    val supportsVision: Boolean
        get() = settings.model.contains("flash", ignoreCase = true) ||
            settings.model.contains("vl", ignoreCase = true)
}

data class LocalModelDownloadStatus(
    val bundle: HuggingFaceModelBundle,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val verifying: Boolean = false,
    val complete: Boolean = false,
    val error: String? = null,
)

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
    private val memoryStore = MemoryStore(app.filesDir)
    private val skillStore = SkillStore(app.filesDir)
    private val client = DeepSeekClient()
    private val gatewayClient = GatewayApprovalClient()

    /** Shared: the view model resolves names for the preload, the engine for the request. */
    private val modelStore = ModelStore(appContext)
    private val huggingFaceModelCatalog = HuggingFaceModelCatalog()
    private val huggingFaceModelInstaller = HuggingFaceModelInstaller(modelStore)

    /**
     * Measured once per process. The probe reads free RAM, the ABI list and the
     * OpenCL driver, so the answer is stable for this view model's lifetime and
     * the UI, the backend preference and the model choice all read one measurement
     * instead of three that could disagree.
     */
    private val deviceProfile: DeviceProfile? =
        runCatching { DeviceCapability.probe(appContext) }.getOrNull()

    /**
     * Built once and kept.
     *
     * `Engine.initialize()` costs seconds and the engine then owns native memory,
     * so constructing it per turn would make every turn slow and every turn leak.
     * The preferred backend is whatever [DeviceCapability] measured; the load path
     * degrades to the CPU on its own if the driver refuses.
     */
    private val onDeviceEngine = OnDeviceBackend(
        context = appContext,
        models = modelStore,
        preferredBackend = deviceProfile?.recommendation?.backend ?: LocalBackend.GPU,
    )

    private val workspace = File(app.filesDir, "workspace").apply { mkdirs() }
    private val toolContext = ToolContext(app, workspace, interactive = true)

    /**
     * The registry for the agent the user talks to (depth 0).
     *
     * It is built through [buildTools] rather than listed here, and a fresh one is
     * built per turn inside [startRun], because the spawner has to capture the
     * model, the credential and the backend of *that* turn: caching a registry
     * built while a different provider was selected would send a subagent to the
     * wrong endpoint with the wrong key.
     */
    private fun registryFor(backend: ChatBackend, settings: TalosSettings, userMessage: String): ToolRegistry =
        buildTools(
            depth = 0,
            backend = backend,
            settings = settings,
            approvals = if (settings.usingDesktopBridge) gatewayGate else approvalGate,
            origin = "conversa: ${_state.value.conversation.id}",
            userMessage = userMessage,
        )

    /**
     * Builds the toolset for one nesting depth, recursively.
     *
     * A depth that may still delegate gets a [SpawnSubagentTool] whose
     * [RegistryFactory] is this same builder at `depth + 1`, so a child is never
     * handed a tool that would let it delegate past [SpawnSubagentTool.MAX_DEPTH].
     * Termination is structural, not runtime: at the maximum depth no spawner is
     * added at all, so the recursion bottoms out without ever calling itself —
     * and the spawner's own guard is a second refusal for a model that tries
     * anyway.
     *
     * Everything the root agent can do is available to every subagent, because a
     * subagent that cannot run `shell` or read a file is just a second opinion.
     */
    private fun buildTools(
        depth: Int,
        backend: ChatBackend,
        settings: TalosSettings,
        approvals: ApprovalGate,
        origin: String,
        userMessage: String,
    ): ToolRegistry {
        // Which tools this run may describe to the model.
        //
        // On-device models are small AND their context windows are small: the only
        // tool-calling model available here holds 1024 tokens in total, while
        // describing all 13 tools costs ~2190 before the conversation even starts.
        // The run then dies with "Input token ids are too long" and the agent looks
        // broken. So a local run gets a curated set rather than a truncated
        // catalogue, and the three tools are chosen so one still covers a lot:
        //   - device_info: structured device state, tiny schema
        //   - shell: the general instrument. It reaches files, apps and system
        //     state, which is what makes the separate file tools redundant here.
        //   - notify: how the agent reports back when the app is not on screen.
        val onDevice = settings.mode == ModelMode.ON_DEVICE

        // Whether this run can afford to DESCRIBE the whole catalogue.
        //
        // The limit is the model's context window, not the fact that inference
        // happens on the phone. That distinction matters: the small tool model here
        // holds 1024 tokens, but the Gemma bundles sit in the gigabytes with windows
        // to match, and denying them tools they can hold makes the agent weaker for
        // no reason. Measured on device: the 2.4 GB Gemma loads and runs at 35 tok/s,
        // so "on-device" is not the same as "small".
        val roomForEverything = !PersistentContext.useCompact(onDevice, settings.model)

        val tools = mutableListOf<Tool>().apply {
            add(DeviceInfoTool())
            add(ShellTool())
            add(NotifyTool())

            if (roomForEverything) {
                // Everything else needs room to be described.
                add(ReadFileTool())
                add(WriteFileTool())
                add(ListDirTool())
                add(ClipboardReadTool())
                add(ClipboardWriteTool())
                add(OpenUrlTool())
                add(ListAppsTool())
                add(CalendarReadTool())
                add(ContactsSearchTool())
                // Network. CAUTION: an unattended run must not be able to POST
                // your data anywhere without you opting that task in.
                add(FetchUrlTool())
                add(MemoryTool(memoryStore, origin, userMessage))
                add(SessionSearchTool(conversationStore))
                add(SkillTool(skillStore, origin))
            }
        }

        // Subagents cost tokens twice over - the spawner's own schema, then the
        // child's whole run - so they need the same room as the full catalogue.
        if (roomForEverything && depth < SpawnSubagentTool.MAX_DEPTH) {
            tools += SpawnSubagentTool(
                depth = depth,
                backend = backend,
                // The parent's gate, deliberately: nested work must not be able
                // to skip a confirmation the user would have been asked for.
                approvals = approvals,
                toolContext = toolContext,
                registryFactory = { next -> buildTools(next, backend, settings, approvals, origin, userMessage) },
                apiKey = settings.effectiveApiKey,
                baseUrl = settings.baseUrl,
                model = settings.effectiveModel,
                temperature = settings.temperature,
            )
        }

        return ToolRegistry(tools)
    }

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var runJob: Job? = null
    private var modelFetchJob: Job? = null
    private var benchmarkJob: Job? = null
    private var localModelSearchJob: Job? = null
    private var localModelDownloadJob: Job? = null
    private var approvalDeferred: CompletableDeferred<ApprovalDecision>? = null

    private data class ActiveGatewayRun(
        val runId: String,
        val target: GatewayApprovalGate.Target,
    )

    // The stream callback runs on IO while Stop runs on Main; volatile snapshots
    // let Stop capture the exact remote run before cancelling its local parent.
    @Volatile private var activeRunToken: Any? = null
    @Volatile private var activeGatewayRun: ActiveGatewayRun? = null

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

    /** A prompt stays parked while its decision POST is in flight. */
    private val approvalPostsInFlight = mutableSetOf<String>()

    init {
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
        refreshPersistentData()
        refreshTasks()
        // Read the models directory up front: the drawer needs to know whether a
        // local run is even possible before the user picks that mode.
        refreshInstalledModels()

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

        // The turn-taking loop: the moment Talos stops talking, listen again.
        //
        // Reopening on this signal rather than on a timer is the whole trick. The
        // recogniser and the speaker contend for the same audio path, so a mic
        // opened too early transcribes Talos' own voice and the agent answers
        // itself. Only the engine knows when it is really done.
        voice.onSpeakFinished = {
            if (_state.value.conversationMode) startVoiceInput()
        }
    }

    override fun onCleared() {
        voice.shutdown()
        // The engine holds native memory; a view model teardown that dropped the
        // reference without closing would keep the model's mapping alive in a
        // process Android already treats as tight.
        benchmarkJob?.cancel()
        onDeviceEngine.release()
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
            _state.update {
                it.copy(error = "Configure a chave de API de ${settings.provider.displayName} em Ajustes.")
            }
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
        val remoteRun = activeGatewayRun
        activeRunToken = null
        activeGatewayRun = null
        runJob?.cancel()
        runJob = null
        approvalDeferred?.cancel()
        approvalDeferred = null
        // A parked remote prompt must not outlive the run that raised it.
        gatewayGate.cancel()
        _state.update { it.copy(isRunning = false, pendingApproval = null) }
        persist(_state.value.conversation)

        // This request is intentionally outside the cancelled run's job: it is
        // the remote half of Stop and must survive cancellation of the local stream.
        if (remoteRun != null) {
            viewModelScope.launch {
                gatewayClient.stopRun(
                    baseUrl = remoteRun.target.baseUrl,
                    apiKey = remoteRun.target.apiKey,
                    runId = remoteRun.runId,
                ).onFailure { error ->
                    _state.update {
                        it.copy(
                            statusMessage = error.message ?: "Não consegui confirmar o cancelamento no PC.",
                            statusIsError = true,
                        )
                    }
                }
            }
        }
    }

    /**
     * Handles the user's answer to whatever prompt is on screen.
     *
     * A prompt from the desktop is answered over HTTP — the desktop is the side
     * that is blocked — while a local tool call only needs its coroutine
     * resumed. The gate publishes prompts FIFO, so the one on screen is the
     * oldest; its captured ids travel with the POST, and the gate is settled
     * only after the gateway acknowledges that decision.
     */
    fun resolveApproval(decision: ApprovalDecision) {
        val request = _state.value.pendingApproval
        val oldest = gatewayGate.oldest
        if (oldest?.runId != null && oldest.request.id == request?.id) {
            val answered = oldest
            val choice = gatewayChoiceFor(decision, answered.choices)
            // Keep this exact prompt parked until the gateway acknowledges the
            // POST. A transport failure must leave the same ids available to retry.
            val lifecycle = runJob
            if (answered.target == null || answered.runId.isBlank() || lifecycle == null || !lifecycle.isActive) {
                _state.update {
                    it.copy(
                        statusMessage = "A execução do PC terminou; a resposta não foi enviada.",
                        statusIsError = true,
                    )
                }
                return
            }
            val postKey = answered.requestId ?: answered.request.id
            if (!approvalPostsInFlight.add(postKey)) return
            // As a child of the stream job, this POST cannot outlive Stop.
            viewModelScope.launch(lifecycle) {
                try {
                    postDecision(answered, choice, decision)
                } finally {
                    approvalPostsInFlight.remove(postKey)
                }
            }
            return
        }

        // Local tools parked on this gate have no desktop run and need only a
        // local resume. Remote prompts are never completed before their POST.
        if (oldest?.runId == null && oldest?.request?.id == request?.id) {
            gatewayGate.submit(decision)
        }

        if (decision == ApprovalDecision.APPROVE_ALWAYS && request != null) {
            viewModelScope.launch { settingsRepo.approveAlways(request.toolName) }
        }
        approvalDeferred?.complete(decision)
        // A prompt a local tool parked on the bridge gate was settled by the
        // submit() above; that is the only other thing it needed.
    }

    /**
     * Wires a gateway frame into the approval UI.
     *
     * `approval.request` is the whole point of this feature: the desktop agent
     * has paused and is waiting for a decision, so the prompt is published
     * through [gatewayGate] (same dialog as any other approval) together with the
     * ids its answer needs. Those ids are read off *this* frame and carried into
     * the coroutine that parks the prompt, so they cannot be separated from it by
     * a second frame. `approval.responded` means the prompt is already gone —
     * answered elsewhere or expired — so ours is taken down; the gate remembers
     * the id, so a prompt that registers after that frame fails closed instead of
     * stranding.
     */
    private fun onGatewayFrame(
        event: StreamEvent,
        target: GatewayApprovalGate.Target?,
        runToken: Any,
    ) {
        if (activeRunToken !== runToken) return
        when (event) {
            is StreamEvent.GatewayApprovalRequested -> {
                // Only the advertised decisions become buttons; an unknown choice
                // maps to null and is dropped rather than rendered as nothing.
                val offered = event.choices
                    .mapNotNull(::decisionForGatewayChoice)
                    .distinct()

                val runId = event.runId.takeIf { it.isNotBlank() }
                if (runId != null) recordGatewayRun(runId, target, runToken)

                // A generic command or a guessed Allow button hides missing
                // gateway metadata. Stop this run instead of asking for consent
                // that cannot be bound to one advertised operation.
                if (
                    runId == null || event.requestId.isNullOrBlank() || event.command.isBlank() ||
                    offered.isEmpty() || target == null
                ) {
                    viewModelScope.launch {
                        if (activeRunToken !== runToken) return@launch
                        _state.update {
                            it.copy(
                                error = "Pedido de aprovação do PC inválido (comando, identificadores ou opções ausentes). Execução interrompida por segurança.",
                            )
                        }
                        stop()
                    }
                    return
                }

                // Captured on the frame callback, before any coroutine runs: the
                // ids and the prompt become one value, so a second
                // `approval.request` read before this launch is dispatched cannot
                // re-point the answer to this one.
                val frame = gatewayGate.forFrame(runId, event.requestId, event.choices, target)
                val request = ApprovalRequest(
                    id = event.requestId,
                    toolName = "ação no computador",
                    arguments = event.command,
                    preview = event.command,
                    // The desktop's policy decided to ask; we cannot know
                    // how risky it is, and the safe default is "look".
                    risk = Risk.CAUTION,
                    choices = offered,
                )

                val lifecycle = runJob
                if (lifecycle == null || !lifecycle.isActive || activeRunToken !== runToken) return
                viewModelScope.launch(lifecycle) { gatewayGate.request(request, frame) }
            }

            is StreamEvent.GatewayApprovalResolved -> {
                val target = event.requestId
                if (target != null) {
                    // A named prompt: settle exactly that one, fail-closed, and
                    // leave the others alone — cancel() would reject prompts the
                    // desktop may still be waiting on.
                    gatewayGate.submitFor(target, ApprovalDecision.REJECT)
                } else {
                    // No id to go by, so there is no way to tell which prompt
                    // went away: take the whole queue down rather than leave a
                    // dialog up for something already settled.
                    gatewayGate.cancel()
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
    private suspend fun postDecision(
        answered: GatewayApprovalGate.Parked,
        choice: String,
        decision: ApprovalDecision,
    ) {
        val settings = _state.value.settings
        val target = answered.target ?: return
        // The run lives on the gateway; if the user switched back to DeepSeek
        // while the dialog was open, refuse to send the old run's credential.
        if (!settings.usingDesktopBridge) {
            _state.update {
                it.copy(
                    statusMessage = "Modo alterado: a resposta não foi enviada ao PC.",
                    statusIsError = true,
                )
            }
            return
        }

        // Use the captured base URL and key for the request. A changed origin
        // must never receive a bearer key from the gateway that raised the prompt.
        if (gatewayOrigin(settings.baseUrl) != target.origin) {
            _state.update {
                it.copy(
                    statusMessage = "A origem do gateway mudou; a resposta não foi enviada.",
                    statusIsError = true,
                )
            }
            return
        }
        if (settings.effectiveApiKey != target.apiKey) {
            _state.update {
                it.copy(
                    statusMessage = "A chave do gateway mudou; a resposta não foi enviada.",
                    statusIsError = true,
                )
            }
            return
        }
        gatewayClient
            .respond(
                baseUrl = target.baseUrl,
                apiKey = target.apiKey,
                runId = answered.runId.orEmpty(),
                choice = choice,
                requestId = answered.requestId,
            )
            .onSuccess {
                gatewayGate.submitFor(answered.requestId ?: answered.request.id, decision)
                _state.update { it.copy(statusMessage = "Resposta enviada ao PC.", statusIsError = false) }
            }
            .onFailure { error ->
                if (error is GatewayApprovalClient.AlreadyResolved) {
                    gatewayGate.submitFor(answered.requestId ?: answered.request.id, decision)
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
            onFinal = { transcript ->
                // In conversation mode the transcript is the turn: sending it is the
                // whole point of hands-free. Outside it, the text goes to the composer
                // so the user can read what was heard before the agent acts on it --
                // recognition mishears, and acting on a misheard command is worse than
                // an extra tap.
                val handsFree = _state.value.conversationMode && transcript.isNotBlank()
                if (handsFree) {
                    _state.update { it.copy(voiceDraft = null) }
                    send(transcript)
                } else {
                    _state.update { it.copy(voiceDraft = transcript) }
                }
            },
            onError = { message -> _state.update { it.copy(error = message) } },
        )
    }

    fun stopVoiceInput() = voice.stopListening()

    fun consumeVoiceDraft() = _state.update { it.copy(voiceDraft = null) }

    /**
     * Turns hands-free conversation on or off.
     *
     * Switching on also switches speaking on, and opens the microphone immediately:
     * leaving it on with a closed mic would look like it is listening while silently
     * doing nothing. Switching off stops both, so the app never keeps the microphone
     * after the user asked it to stop.
     */
    fun toggleConversationMode() {
        val next = !_state.value.conversationMode
        _state.update { it.copy(conversationMode = next, speakReplies = next || it.speakReplies) }
        if (next) {
            startVoiceInput()
        } else {
            voice.stopListening()
            voice.stopSpeaking()
        }
    }

    /**
     * Stops the answer and reopens the microphone at once, for interrupting by tap.
     *
     * Barge-in by voice is not offered: the recogniser would hear the very audio it
     * is trying to interrupt, so the honest version of interruption is a deliberate
     * tap rather than a button that appears to listen while Talos talks.
     */
    fun interruptAndListen() {
        voice.stopSpeaking()
        if (_state.value.conversationMode) startVoiceInput()
    }

    fun toggleSpeakReplies() {
        val next = !_state.value.speakReplies
        if (!next) voice.stopSpeaking()
        _state.update { it.copy(speakReplies = next) }
    }

    fun stopSpeaking() = voice.stopSpeaking()

    // UI writes originate from explicit edits; agent writes use approval-gated tools.
    fun refreshPersistentData() {
        viewModelScope.launch { loadPersistentData() }
    }

    private suspend fun loadPersistentData() {
        // A damaged skill must not prevent the user from viewing or fixing memories.
        try {
            val memories = memoryStore.list()
            _state.update { it.copy(memories = memories, memoryError = null, memoryDirectory = memoryStore.directory) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(memoryError = e.message ?: "Falha ao ler memórias.") } }
        try {
            val skills = skillStore.list()
            _state.update { it.copy(skills = skills, skillsError = null, skillsDirectory = skillStore.directory) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(skillsError = e.message ?: "Falha ao ler skills.") } }
    }

    private suspend fun changePersistentData(change: suspend () -> Unit): Result<Unit> {
        if (_state.value.persistenceBusy || _state.value.isRunning)
            return Result.failure(IllegalStateException("Espere a tarefa ou gravação atual terminar. A leitura continua disponível."))
        _state.update { it.copy(persistenceBusy = true) }
        return try {
            change()
            loadPersistentData()
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
        finally { _state.update { it.copy(persistenceBusy = false) } }
    }

    suspend fun saveMemory(id: String?, text: String, kind: MemoryKind): Result<Unit> = changePersistentData {
        if (id == null) memoryStore.save(text, "usuário: ajustes", kind) else memoryStore.edit(id, text, kind)
    }
    suspend fun deleteMemory(id: String): Result<Unit> = changePersistentData { memoryStore.forget(id) }
    suspend fun saveSkill(name: String, title: String, description: String, steps: String, create: Boolean): Result<Unit> = changePersistentData {
        skillStore.save(name, description, steps, "usuário: ajustes", create, title)
    }
    suspend fun deleteSkill(name: String): Result<Unit> = changePersistentData { skillStore.delete(name) }
    suspend fun setMemoryPolicy(enabled: Boolean, remote: Boolean): Result<Unit> = try {
        settingsRepo.setMemoryPolicy(enabled, remote)
        val saved = settingsRepo.flow.first()
        _state.update { it.copy(settings = saved) }
        Result.success(Unit)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }

    fun executeSkill(name: String): Boolean {
        if (_state.value.skillExecutionUnavailable != null) return false
        // Only the validated identifier becomes a user request, never the stored body.
        if (!Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(name)) return false
        send("Execute a skill '$name': carregue os passos com skill/execute e realize as ações pertinentes, respeitando as aprovações.")
        return true
    }

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
        val local = settings.mode == ModelMode.ON_DEVICE
        val targetSnapshot = gatewayTarget(settings)
        val runToken = Any()

        activeRunToken = runToken
        activeGatewayRun = null
        runJob?.cancel()
        _state.update { it.copy(isRunning = true) }

        runJob = viewModelScope.launch {
            // CLOUD and DESKTOP bind the selected provider's auth to this turn;
            // only ON_DEVICE swaps out the remote client entirely.
            val turnBackend = if (local) onDeviceEngine else client.forProvider(settings.provider)
            if (turnBackend is DeepSeekClient) {
                // Bind gateway callbacks to this turn's endpoint and lifecycle;
                // stale callbacks from a cancelled stream cannot retarget Stop.
                turnBackend.onGatewayEvent = { event -> onGatewayFrame(event, targetSnapshot, runToken) }
                turnBackend.onGatewayRunId = { id -> recordGatewayRun(id, targetSnapshot, runToken) }
            }
            val loop = AgentLoop(
                client = turnBackend,
                // Rebuilt per turn, so the subagent spawner in it captures the
                // backend, model and credential that this turn is actually using.
                registry = registryFor(turnBackend, settings, history.lastOrNull { it.role == Role.USER }?.content.orEmpty()),
                // Against the desktop, prompts come from its policy rather than
                // from this phone's allowlist, so the gateway gate answers them.
                // [TalosSettings.usingDesktopBridge] is false on-device, so a local
                // turn keeps the phone's own approval gate.
                approvals = if (settings.usingDesktopBridge) gatewayGate else approvalGate,
                toolContext = toolContext,
                resolveImages = resolveImages,
            )
            try {
                // The weights are loaded before the loop asks for a token;
                // `initialize()` takes seconds, and discovering that on the first
                // streamed turn looks like a freeze. Nothing installed or a failed
                // load is reported here, in Portuguese, instead of as a silent stall.
                if (local) {
                    val problem = preloadOnDevice(settings)
                    if (problem != null) {
                        _state.update { it.copy(error = problem) }
                        return@launch
                    }
                }

                val compact = PersistentContext.useCompact(local, settings.model)
                val context = PersistentContext.build(
                    if (settings.includesMemory) memoryStore.list() else emptyList(), skillStore.list(),
                    history.lastOrNull { it.role == Role.USER }?.content.orEmpty(), compact,
                )
                loop.run(
                    apiKey = settings.effectiveApiKey,
                    baseUrl = settings.baseUrl,
                    model = settings.effectiveModel,
                    systemPrompt = SystemPrompt.build(workspace.absolutePath, compact, context, persistentTools = !compact),
                    history = history,
                    temperature = settings.temperature,
                ).collect(::handle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Erro inesperado ao falar com o modelo.") }
            } finally {
                if (activeRunToken === runToken) {
                    activeGatewayRun = null
                    activeRunToken = null
                    _state.update { it.copy(isRunning = false) }
                    persist(_state.value.conversation)

                    // Hands-free safety net. The loop normally reopens the microphone
                    // when the answer finishes being spoken, but a turn can end with
                    // nothing to say -- an empty reply, a tool-only turn, a failure --
                    // and then that signal never fires and the conversation silently
                    // stalls with the mic shut. Reopen here too.
                    //
                    // Guarded on both flags because either path may win the race: if
                    // the utterance is still playing we leave it to onSpeakFinished,
                    // and if the mic is already open there is nothing to do. Without
                    // the guard the two paths would start two recognition sessions.
                    if (_state.value.conversationMode && !voice.isSpeaking && !voice.isListening) {
                        startVoiceInput()
                    }
                }
            }
        }
    }

    private fun gatewayTarget(settings: TalosSettings): GatewayApprovalGate.Target? {
        if (!settings.usingDesktopBridge) return null
        val origin = gatewayOrigin(settings.baseUrl) ?: return null
        return GatewayApprovalGate.Target(
            baseUrl = settings.baseUrl,
            origin = origin,
            apiKey = settings.effectiveApiKey,
        )
    }

    private fun recordGatewayRun(
        runId: String,
        target: GatewayApprovalGate.Target?,
        runToken: Any,
    ) {
        if (runId.isBlank() || target == null || activeRunToken !== runToken) return
        activeGatewayRun = ActiveGatewayRun(runId, target)
    }

    /**
     * Loads the on-device engine ahead of the loop, or returns why it could not.
     *
     * The configured name wins when it resolves to a file on disk, because that is
     * also how [OnDeviceBackend.streamChat] resolves `ChatRequest.model`. A null
     * from [OnDeviceBackend.ensureDefaultLoaded] means *nothing* is installed —
     * the one case the backend would otherwise report only after the first token
     * was requested — and a thrown [com.talos.harness.local.LocalModelException]
     * carries the loader's own Portuguese reason.
     */
    private suspend fun preloadOnDevice(settings: TalosSettings): String? = try {
        val recommendation = _state.value.deviceRecommendation ?: deviceProfile?.recommendation
        val configured = modelStore.pathFor(settings.effectiveModel)
        if (configured != null) {
            onDeviceEngine.ensureLoaded(configured)
            null
        } else if (onDeviceEngine.ensureDefaultLoaded(recommendation) == null) {
            "Nenhum modelo instalado no celular. Copie um arquivo .litertlm para " +
                "${modelStore.directory().absolutePath} e tente de novo."
        } else {
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        e.message ?: "Não foi possível carregar o modelo no celular."
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

            is AgentEvent.ToolFinished -> {
                upsert(event.message)
                if (event.message.toolName in setOf("memory", "skill", "spawn_subagent")) refreshPersistentData()
            }

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

    suspend fun saveConnection(draft: ConnectionDraft): Result<Unit> {
        if (_state.value.isRunning) return Result.failure(IllegalStateException("Espere a conversa atual terminar antes de mudar a conexão."))
        return try {
            settingsRepo.saveConnection(draft)
            val saved = settingsRepo.flow.first()
            _state.update { it.copy(settings = saved, availableModels = emptyList(), statusMessage = null, isBusy = false) }
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun testConnectionDraft(draft: ConnectionDraft): Result<Unit> {
        draft.validation(requireModel = false)?.let { return Result.failure(IllegalArgumentException(it)) }
        return try {
            val error = client.probe(draft.boundKey, draft.baseUrl.trim(), draft.provider)
            if (error == null) Result.success(Unit) else Result.failure(IllegalStateException(error))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun fetchDraftModels(draft: ConnectionDraft): Result<List<ProviderModel>> {
        draft.validation(requireModel = false)?.let { return Result.failure(IllegalArgumentException(it)) }
        return try {
            val models = client.listModels(draft.boundKey, draft.baseUrl.trim(), draft.provider)
            if (models.isEmpty()) Result.failure(IllegalStateException("Nenhum modelo retornado. Verifique o endereço e a chave ou informe o modelo manualmente."))
            else Result.success(models)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    fun updateTemperature(value: Double) = viewModelScope.launch { settingsRepo.setTemperature(value) }

    /**
     * Re-reads what is on disk.
     *
     * Called from `init`, when the user switches the mode, and from the strip's
     * refresh action: the list the user reads and the list the loader resolves
     * against must be the same one, and a bundle can be pushed while the app is
     * already open.
     */
    fun refreshInstalledModels() {
        viewModelScope.launch {
            val (directory, installed) = withContext(Dispatchers.IO) {
                modelStore.directory().absolutePath to modelStore.installed()
            }
            _state.update {
                it.copy(
                    installedModels = installed,
                    modelsDirectory = directory,
                    deviceRecommendation = deviceProfile?.recommendation,
                )
            }
        }
    }

    /** Searches only public Hugging Face repositories that advertise LiteRT-LM bundles. */
    fun searchLocalModels(query: String) {
        localModelSearchJob?.cancel()
        localModelSearchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    localModelBundles = emptyList(),
                    localModelSearchQuery = query.trim().take(100),
                    localModelSearchBusy = true,
                    localModelSearchError = null,
                )
            }
            try {
                val models = huggingFaceModelCatalog.search(query)
                _state.update {
                    it.copy(
                        localModelBundles = models,
                        localModelSearchBusy = false,
                        localModelSearchError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        localModelBundles = emptyList(),
                        localModelSearchBusy = false,
                        localModelSearchError = e.message ?: "Não foi possível buscar modelos no Hugging Face.",
                    )
                }
            }
        }
    }

    /** Installs a selected catalog bundle only after the settings UI confirms it. */
    fun installLocalModel(bundle: HuggingFaceModelBundle) {
        if (localModelDownloadJob?.isActive == true) return
        localModelDownloadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    localModelDownload = LocalModelDownloadStatus(
                        bundle = bundle,
                        downloadedBytes = 0L,
                        totalBytes = bundle.bytes,
                    ),
                )
            }
            try {
                huggingFaceModelInstaller.install(bundle) { downloaded, total, verifying ->
                    _state.update { state ->
                        state.copy(
                            localModelDownload = LocalModelDownloadStatus(
                                bundle = bundle,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                verifying = verifying,
                            ),
                        )
                    }
                }
                val (directory, installed) = withContext(Dispatchers.IO) {
                    modelStore.directory().absolutePath to modelStore.installed()
                }
                _state.update {
                    it.copy(
                        installedModels = installed,
                        modelsDirectory = directory,
                        localModelDownload = LocalModelDownloadStatus(
                            bundle = bundle,
                            downloadedBytes = bundle.bytes,
                            totalBytes = bundle.bytes,
                            complete = true,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(localModelDownload = null) }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        localModelDownload = LocalModelDownloadStatus(
                            bundle = bundle,
                            downloadedBytes = 0L,
                            totalBytes = bundle.bytes,
                            error = e.message ?: "Não foi possível instalar o bundle.",
                        ),
                    )
                }
            } finally {
                localModelDownloadJob = null
            }
        }
    }

    fun cancelLocalModelDownload() {
        localModelDownloadJob?.cancel()
        localModelDownloadJob = null
    }

    /**
     * Routes requests through the Talos gateway on the desktop.
     *
     * Base URL, model **and credential** all resolve from the mode: the gateway
     * answers to its own model id and authenticates with its own key, so switching
     * the URL alone would send the DeepSeek key to a server that rejects it.
     * [TalosSettings.effectiveApiKey] and friends encode that rule in one place,
     * shared with the unattended worker.
     */
    private fun switchRemoteMode(mode: ModelMode) {
        if (_state.value.isRunning) return
        viewModelScope.launch {
            try {
                settingsRepo.setMode(mode)
                _state.update { it.copy(availableModels = emptyList(), statusMessage = null, isBusy = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(statusMessage = "Não foi possível mudar o modo.", statusIsError = true) }
            }
        }
    }

    fun useDesktopBridge() = switchRemoteMode(ModelMode.DESKTOP)
    fun useCloud() = switchRemoteMode(ModelMode.CLOUD)

    /**
     * Switches to the model running on this phone.
     *
     * The model field is repointed at a bundle that is actually on disk, because
     * [OnDeviceBackend.streamChat] resolves `ChatRequest.model` through
     * [ModelStore] — leaving a cloud id there ("deepseek-flash") would make the
     * first turn fail with two models installed. With nothing installed the field
     * is left alone and the chat strip says where to copy a bundle instead of
     * clobbering a name the user may still want.
     */
    fun useOnDeviceModel() {
        if (_state.value.isRunning) return
        viewModelScope.launch {
            val recommendation = deviceProfile?.recommendation
            val (directory, installed) = withContext(Dispatchers.IO) {
                modelStore.directory().absolutePath to modelStore.installed()
            }
            // A model the user forced earlier wins over the automatic pick. The
            // whole point of forcing one is that the probe's advice is not the
            // last word, and re-pointing the field on every mode switch would
            // undo that choice exactly the way the old behaviour did.
            val forced = installed.firstOrNull {
                it.name.equals(_state.value.settings.localModel.trim(), ignoreCase = true)
            }
            val chosen = forced ?: modelStore.default(recommendation)
            settingsRepo.setMode(ModelMode.ON_DEVICE, chosen?.name)
            val overBudget = chosen != null &&
                recommendation != null &&
                chosen.bytes > recommendation.modelFileBudgetBytes
            _state.update {
                it.copy(
                    isBusy = false, availableModels = emptyList(),
                    installedModels = installed,
                    modelsDirectory = directory,
                    deviceRecommendation = recommendation,
                    statusMessage = when {
                        chosen == null ->
                            "Modo no celular ativado, mas nenhum modelo está instalado."

                        overBudget ->
                            "Rodando no celular: ${chosen.name} — acima do orçamento medido; " +
                                "pode ser morto pelo sistema ou falhar ao carregar."

                        else -> "Rodando no celular: ${chosen.name}."
                    },
                    statusIsError = chosen == null,
                )
            }
        }
    }

    /**
     * Forces one installed bundle, whatever the probe says about it.
     *
     * This is the escape hatch the capability probe must not close: its weight
     * budget is half the measured headroom, deliberately, so it rejects models
     * that might still run — the weights are a file mapping the kernel can
     * evict. Selecting here is an explicit acceptance of that risk (the row
     * carries the warning), and it is persisted, so it survives a restart
     * instead of being overwritten by [useOnDeviceModel]'s automatic pick.
     */
    fun selectOnDeviceModel(name: String) {
        if (_state.value.isRunning) return
        val model = _state.value.installedModels.firstOrNull { it.name == name } ?: return
        viewModelScope.launch {
            // The list only exists under ON_DEVICE, but setting the mode here
            // keeps the invariant if that ever changes: choosing a local bundle
            // means the phone runs it.
            settingsRepo.setMode(ModelMode.ON_DEVICE, model.name)
            _state.update {
                it.copy(
                    statusMessage = "Modelo escolhido: ${model.name} (${model.sizeLabel}).",
                    statusIsError = false,
                )
            }
        }
    }

    /**
     * Measures one installed model for real: unload, load, generate, put back.
     *
     * Refused while a turn is streaming, because the benchmark releases the
     * engine and closing one mid-decode is not something a measurement should be
     * able to do to a conversation. A previous measurement is joined rather than
     * merely cancelled, so its cleanup — including putting the previous model
     * back — has finished before this one touches the engine.
     */
    fun benchmarkModel(name: String) {
        val model = _state.value.installedModels.firstOrNull { it.name == name }
        if (model == null) {
            _state.update { it.copy(statusMessage = "Modelo não encontrado: $name", statusIsError = true) }
            return
        }
        if (_state.value.isRunning) {
            _state.update {
                it.copy(
                    statusMessage = "Espere a resposta atual terminar antes de medir a velocidade.",
                    statusIsError = true,
                )
            }
            return
        }

        val previous = benchmarkJob
        benchmarkJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            _state.update { it.copy(benchmarkingModel = model.name, benchmarkStage = "Preparando…") }
            try {
                val result = benchmark(
                    model = model,
                    backend = onDeviceEngine,
                    // The same accelerator preference a turn would use, so the
                    // measurement describes this app's real path, not a
                    // synthetic one.
                    accelerator = deviceProfile?.recommendation?.backend ?: LocalBackend.GPU,
                ) { stage -> _state.update { it.copy(benchmarkStage = stage) } }
                _state.update { it.copy(benchmarkResults = it.benchmarkResults + (model.name to result)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // `benchmark` classifies load and generation failures itself, so
                // reaching here means something outside them — but an uncaught
                // exception in a viewModelScope launch takes the app down, which
                // a measurement must never do.
                _state.update {
                    it.copy(
                        statusMessage = "A medição falhou: ${e.message ?: "erro inesperado"}",
                        statusIsError = true,
                    )
                }
            } finally {
                _state.update { it.copy(benchmarkingModel = null, benchmarkStage = null) }
            }
        }
    }

    /**
     * Stops a running measurement. Its own cleanup releases the engine, which is
     * reloaded before the next turn streams — a cancelled benchmark must not
     * leave the conversation without a model.
     */
    fun cancelBenchmark() {
        benchmarkJob?.cancel()
        benchmarkJob = null
    }

    fun revokeApproval(toolName: String) = viewModelScope.launch { settingsRepo.revokeApproval(toolName) }

    fun clearApprovals() = viewModelScope.launch { settingsRepo.clearApprovals() }

    fun fetchModels() {
        if (_state.value.onDevice) { refreshInstalledModels(); return }
        fetchModels(_state.value.settings)
    }

    private fun fetchModels(settings: TalosSettings) {
        modelFetchJob?.cancel()
        _state.update {
            it.copy(isBusy = true, availableModels = emptyList(), statusMessage = null, statusIsError = false)
        }
        val customRemoteMissingKey = settings.provider.id == ProviderCatalog.CUSTOM.id && !settings.isConfigured
        if (
            settings.effectiveApiKey.isBlank() &&
            (settings.provider.requiresApiKeyForModels || customRemoteMissingKey)
        ) {
            _state.update {
                it.copy(
                    isBusy = false,
                    statusMessage = "Informe uma chave de API para buscar modelos de ${settings.provider.displayName}.",
                    statusIsError = true,
                )
            }
            return
        }
        modelFetchJob = viewModelScope.launch {
            val models = client.listModels(
                apiKey = settings.effectiveApiKey,
                baseUrl = settings.baseUrl,
                provider = settings.provider,
            )
            if (!isCurrentConnection(settings)) return@launch
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

    private fun isCurrentConnection(snapshot: TalosSettings): Boolean {
        val current = _state.value.settings
        return current.mode == snapshot.mode && current.providerId == snapshot.providerId &&
            current.baseUrl == snapshot.baseUrl && current.effectiveApiKey == snapshot.effectiveApiKey
    }

    fun testConnection() {
        if (_state.value.onDevice) { benchmarkModel(_state.value.onDeviceModel); return }
        val settings = _state.value.settings
        viewModelScope.launch {
            val customRemoteMissingKey = settings.provider.id == ProviderCatalog.CUSTOM.id && !settings.isConfigured
            if (
                settings.effectiveApiKey.isBlank() &&
                (settings.provider.requiresApiKeyForModels || customRemoteMissingKey)
            ) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Informe uma chave de API para testar ${settings.provider.displayName}.",
                        statusIsError = true,
                    )
                }
                return@launch
            }
            _state.update { it.copy(isBusy = true, statusMessage = "Testando conexão…", statusIsError = false) }
            val failure = client.probe(settings.effectiveApiKey, settings.baseUrl, settings.provider)
            if (!isCurrentConnection(settings)) return@launch
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
