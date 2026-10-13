package com.talos.harness.ui.settings

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talos.harness.local.BenchmarkOutcome
import com.talos.harness.local.BenchmarkResult
import com.talos.harness.local.HuggingFaceModelBundle
import com.talos.harness.local.ModelFile
import com.talos.harness.local.ModelRecommendation
import com.talos.harness.local.formatBytes
import com.talos.harness.local.formatDuration
import com.talos.harness.local.formatTokensPerSecond
import com.talos.harness.store.ConnectionDraft
import com.talos.harness.store.MemoryEntry
import com.talos.harness.store.MemoryKind
import com.talos.harness.store.SkillEntry
import com.talos.harness.store.ModelMode
import com.talos.harness.store.ProviderCatalog
import com.talos.harness.store.ProviderModel
import com.talos.harness.store.TalosSettings
import com.talos.harness.store.ApprovalPermissiveness
import com.talos.harness.store.WebSearchProvider
import com.talos.harness.ui.LocalModelDownloadStatus
import com.talos.harness.ui.theme.TalosColors
import java.util.Locale

/**
 * Which page of Settings is on screen.
 *
 * This is deliberately a hand-rolled enum instead of a navigation library: the
 * app already switches root screens with a `rememberSaveable` flag in
 * [com.talos.harness.MainActivity], and a nav dependency would pull in a
 * back-stack, a graph and a second source of truth for a five-row menu.
 */
private enum class SettingsSection(val title: String) {
    ROOT("Ajustes"),
    RUNTIME("Onde roda o modelo"),
    LOCAL_MODELS("Modelos locais"),
    CONNECTION("Provedor e conexão"),
    MODEL("Modelo"),
    APPROVALS("Aprovações"),
    WEB_SEARCH("Busca na web"),
    MEMORY("Memória"),
    SKILLS("Skills"),
    DIAGNOSTICS("Diagnóstico"),
}

/**
 * Talos settings, organised as a root menu of sub-menus.
 *
 * The screen renders purely from [settings]: every edit is pushed straight up
 * through the `on*Change` callbacks and comes back down as a new [settings]
 * value, so it never holds a second copy of persisted state that could drift
 * from the store. The only local state is presentation-only — which section is
 * open, the API key mask toggles and which dropdown is expanded.
 *
 * The root rows carry a one-line summary of the *current* value, so the common
 * question ("where is it running?", "which model?") is answered without opening
 * anything.
 *
 * @param availableModels models discovered from the selected provider; an empty
 *   list hides the dropdown while the free-text model field stays usable.
 * @param isBusy a network probe is in flight; both diagnostic actions are
 *   disabled and a spinner is shown next to them.
 * @param statusMessage result of the last probe, or `null` when there has been
 *   none this session.
 * @param statusIsError tints [statusMessage] as a failure.
 * @param deviceRecommendation what the hardware probe measured; `null` when the
 *   probe itself failed, in which case ON_DEVICE must stay selectable.
 * @param installedModels `.litertlm` bundles on disk, shown under "Onde roda".
 * @param selectedOnDeviceModel the bundle the phone loads now, including one the
 *   user forced against the probe's advice; the list marks it with a check.
 * @param benchmarkResults the last real measurement of each model, keyed by
 *   name. Shown next to the model it belongs to, together with the basis of its
 *   speed figure.
 * @param benchmarkingModel the model being measured right now, or `null`.
 * @param benchmarkStage what the running measurement is doing, in Portuguese.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: TalosSettings,
    availableModels: List<ProviderModel>,
    isBusy: Boolean,
    statusMessage: String?,
    statusIsError: Boolean,
    deviceRecommendation: ModelRecommendation?,
    installedModels: List<ModelFile>,
    modelsDirectory: String,
    /**
     * The bundle the phone is told to load right now — the user's forced choice
     * when they made one. Passed separately from [settings] so the screen never
     * has to decide for itself which field names the on-device model.
     */
    selectedOnDeviceModel: String,
    benchmarkResults: Map<String, BenchmarkResult>,
    benchmarkingModel: String?,
    benchmarkStage: String?,
    localModelBundles: List<HuggingFaceModelBundle>,
    localModelSearchQuery: String?,
    localModelSearchBusy: Boolean,
    localModelSearchError: String?,
    localModelDownload: LocalModelDownloadStatus?,
    onSaveConnection: suspend (ConnectionDraft) -> Result<Unit>,
    onTestDraft: suspend (ConnectionDraft) -> Result<Unit>,
    onFetchDraftModels: suspend (ConnectionDraft) -> Result<List<ProviderModel>>,
    isRunning: Boolean,
    onTemperatureChange: (Double) -> Unit,
    onUseCloud: () -> Unit,
    onUsePc: () -> Unit,
    onUseOnDevice: () -> Unit,
    onRefreshModels: () -> Unit,
    onSelectOnDeviceModel: (String) -> Unit,
    onBenchmarkModel: (String) -> Unit,
    onCancelBenchmark: () -> Unit,
    onSearchLocalModels: (String) -> Unit,
    onInstallLocalModel: (HuggingFaceModelBundle) -> Unit,
    onCancelLocalModelDownload: () -> Unit,
    onFetchModels: () -> Unit,
    onTestConnection: () -> Unit,
    onRevokeApproval: (String) -> Unit,
    onClearApprovals: () -> Unit,
    onRevokeShellPrefix: (String) -> Unit,
    onApprovalPermissiveness: (ApprovalPermissiveness) -> Unit,
    onSaveWebSearch: suspend (WebSearchProvider, String) -> Result<Unit>,
    onBack: () -> Unit,
    memories: List<MemoryEntry>,
    skills: List<SkillEntry>,
    memoryDirectory: String,
    skillsDirectory: String,
    persistenceBusy: Boolean,
    memoryError: String?,
    skillsError: String?,
    skillExecutionUnavailable: String?,
    conversationTitles: Map<String, String>,
    onMemoryPolicy: suspend (Boolean, Boolean) -> Result<Unit>,
    onRefreshPersistentData: () -> Unit,
    onSaveMemory: suspend (String?, String, MemoryKind) -> Result<Unit>,
    onDeleteMemory: suspend (String) -> Result<Unit>,
    onSaveSkill: suspend (String, String, String, String, Boolean) -> Result<Unit>,
    onDeleteSkill: suspend (String) -> Result<Unit>,
    onExecuteSkill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TalosColors.current

    // rememberSaveable, not remember: a rotation or a process-death restore while
    // editing the API key should not bounce the user back to the root list and
    // make them hunt for the section again.
    var sectionName by rememberSaveable { mutableStateOf(SettingsSection.ROOT.name) }
    val section = SettingsSection.entries.firstOrNull { it.name == sectionName }
        ?: SettingsSection.ROOT
    // The system back gesture must walk sub-menu -> root before it leaves Settings;
    // without this the user would jump straight out of a section into the chat.
    var connectionDirty by remember { mutableStateOf(false) }
    var connectionSaving by remember { mutableStateOf(false) }
    var discardConnection by remember { mutableStateOf(false) }
    val goBack: () -> Unit = {
        if (!connectionSaving) {
            if (section == SettingsSection.CONNECTION && connectionDirty) discardConnection = true
            else if (section == SettingsSection.ROOT) onBack() else sectionName = SettingsSection.ROOT.name
        }
    }
    if (discardConnection) AlertDialog(onDismissRequest = { discardConnection = false },
        title = { Text("Descartar alterações da conexão?") }, text = { Text("A configuração ativa ainda não foi alterada.") },
        confirmButton = { TextButton(onClick = {
            discardConnection = false; connectionDirty = false; sectionName = SettingsSection.ROOT.name
        }) { Text("Descartar") } },
        dismissButton = { TextButton(onClick = { discardConnection = false }) { Text("Continuar editando") } })
    BackHandler(enabled = true) { goBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = { Text(text = section.title) },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (section == SettingsSection.ROOT) {
                                "Voltar"
                            } else {
                                "Voltar aos ajustes"
                            },
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .then(if (section == SettingsSection.MEMORY || section == SettingsSection.SKILLS) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (section) {
                SettingsSection.ROOT -> RootSection(
                    settings = settings,
                    isBusy = isBusy,
                    statusMessage = statusMessage,
                    deviceRecommendation = deviceRecommendation,
                    installedModels = installedModels,
                    memoryCount = memories.size,
                    skillCount = skills.size,
                    onOpen = { sectionName = it.name },
                )

                SettingsSection.RUNTIME -> RuntimeSection(
                    settings = settings,
                    recommendation = deviceRecommendation,
                    models = installedModels,
                    directory = modelsDirectory,
                    selectedModel = selectedOnDeviceModel,
                    benchmarkResults = benchmarkResults,
                    benchmarkingModel = benchmarkingModel,
                    benchmarkStage = benchmarkStage,
                    onUseCloud = onUseCloud,
                    onUsePc = onUsePc,
                    onUseOnDevice = onUseOnDevice,
                    onRefreshModels = onRefreshModels,
                    onSelectModel = onSelectOnDeviceModel,
                    onBenchmarkModel = onBenchmarkModel,
                    onCancelBenchmark = onCancelBenchmark,
                    allowModeChange = !isRunning && benchmarkingModel == null,
                )

                SettingsSection.LOCAL_MODELS -> LocalModelCatalogSection(
                    bundles = localModelBundles,
                    searchedQuery = localModelSearchQuery,
                    busy = localModelSearchBusy,
                    error = localModelSearchError,
                    recommendation = deviceRecommendation,
                    installedModels = installedModels,
                    download = localModelDownload,
                    onSearch = onSearchLocalModels,
                    onInstall = onInstallLocalModel,
                    onCancelDownload = onCancelLocalModelDownload,
                    onSelectModel = onSelectOnDeviceModel,
                )

                SettingsSection.CONNECTION -> if (settings.mode == ModelMode.ON_DEVICE) {
                    Text("O modelo local não precisa de endereço ou chave. Configure o modelo em Onde roda o modelo.")
                } else ConnectionSection(
                    settings = settings, running = isRunning,
                    onDirtyChange = { connectionDirty = it }, onSavingChange = { connectionSaving = it },
                    onSave = onSaveConnection, onTest = onTestDraft, onFetchModels = onFetchDraftModels,
                )

                SettingsSection.MODEL -> ModelSection(
                    settings = settings,
                    onEditModel = { sectionName = if (settings.mode == ModelMode.ON_DEVICE) SettingsSection.RUNTIME.name else SettingsSection.CONNECTION.name },
                    onTemperatureChange = onTemperatureChange,
                )

                SettingsSection.MEMORY -> MemorySection(
                    memories, memoryDirectory, persistenceBusy, memoryError, settings, conversationTitles,
                    onRefreshPersistentData, onMemoryPolicy, onSaveMemory, onDeleteMemory,
                )
                SettingsSection.SKILLS -> SkillsSection(
                    skills, skillsDirectory, persistenceBusy, skillExecutionUnavailable, skillsError,
                    conversationTitles, onRefreshPersistentData, onSaveSkill, onDeleteSkill, onExecuteSkill,
                )
                SettingsSection.APPROVALS -> ApprovalsSection(
                    settings = settings,
                    onRevokeApproval = onRevokeApproval,
                    onClearApprovals = onClearApprovals,
                    onRevokeShellPrefix = onRevokeShellPrefix,
                    onPolicyChange = onApprovalPermissiveness,
                )
                SettingsSection.WEB_SEARCH -> WebSearchSection(settings, onSaveWebSearch)

                SettingsSection.DIAGNOSTICS -> if (settings.mode == ModelMode.ON_DEVICE) {
                    Text("Diagnóstico local", style = MaterialTheme.typography.titleMedium)
                    Text("${installedModels.size} modelos instalados. A verificação roda no aparelho, sem acesso à rede.")
                    Text("Selecionado: $selectedOnDeviceModel")
                    OutlinedButton(onClick = onRefreshModels) { Text("Atualizar modelos instalados") }
                    OutlinedButton(enabled = !isRunning && benchmarkingModel == null && installedModels.any { it.name == selectedOnDeviceModel },
                        onClick = { onBenchmarkModel(selectedOnDeviceModel) }) { Text("Medir modelo local") }
                    benchmarkStage?.let { Text(it) }
                    if (benchmarkingModel != null) TextButton(onClick = onCancelBenchmark) { Text("Cancelar medição") }
                    benchmarkResults[selectedOnDeviceModel]?.let { BenchmarkResultBlock(it) }
                } else DiagnosticsSection(
                    isBusy = isBusy,
                    statusMessage = statusMessage,
                    statusIsError = statusIsError,
                    onFetchModels = onFetchModels,
                    onTestConnection = onTestConnection,
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Root menu
// ---------------------------------------------------------------------------

/**
 * The root list: one row per sub-menu, each with the live value as its summary.
 *
 * Nothing here is a control — tapping always navigates. Keeping the summaries
 * read-only is what makes the root hard to mis-tap into a state change.
 */
@Composable
private fun RootSection(
    settings: TalosSettings,
    isBusy: Boolean,
    statusMessage: String?,
    deviceRecommendation: ModelRecommendation?,
    installedModels: List<ModelFile>,
    memoryCount: Int,
    skillCount: Int,
    onOpen: (SettingsSection) -> Unit,
) {
    val palette = TalosColors.current

    SectionHeader("Modelo e conexão")
    SettingsMenuRow(
        title = SettingsSection.RUNTIME.title,
        summary = modeSummary(settings.mode),
        onClick = { onOpen(SettingsSection.RUNTIME) },
    )
    SettingsMenuRow(
        title = SettingsSection.LOCAL_MODELS.title,
        summary = "Buscar bundles LiteRT-LM compatíveis no Hugging Face",
        onClick = { onOpen(SettingsSection.LOCAL_MODELS) },
    )
    if (settings.mode != ModelMode.ON_DEVICE) SettingsMenuRow(
        title = if (settings.usingDesktopBridge) "Gateway do PC" else SettingsSection.CONNECTION.title,
        summary = providerSummary(settings),
        onClick = { onOpen(SettingsSection.CONNECTION) },
    )
    SettingsMenuRow(
        title = SettingsSection.MODEL.title,
        summary = modelSummary(settings),
        onClick = { onOpen(SettingsSection.MODEL) },
    )
    SettingsMenuRow(
        title = SettingsSection.WEB_SEARCH.title,
        summary = "${if (settings.webSearchProvider == WebSearchProvider.TAVILY) "Tavily" else "Brave"} · ${if (settings.webSearchApiKey.isBlank()) "chave não configurada" else "chave configurada"}",
        onClick = { onOpen(SettingsSection.WEB_SEARCH) },
    )
    SectionHeader("Memória e procedimentos")
    SettingsMenuRow(
        title = SettingsSection.MEMORY.title,
        summary = "$memoryCount entradas · ${if (settings.includesMemory) "em uso neste modo" else "inclusão automática desativada neste modo"}",
        onClick = { onOpen(SettingsSection.MEMORY) },
    )
    SettingsMenuRow(
        title = SettingsSection.SKILLS.title,
        summary = "$skillCount procedimentos salvos",
        onClick = { onOpen(SettingsSection.SKILLS) },
    )
    SectionHeader("Segurança e diagnóstico")
    SettingsMenuRow(
        title = SettingsSection.APPROVALS.title,
        summary = approvalsSummary(settings),
        onClick = { onOpen(SettingsSection.APPROVALS) },
    )
    SettingsMenuRow(
        title = SettingsSection.DIAGNOSTICS.title,
        summary = if (settings.mode == ModelMode.ON_DEVICE) "Verificação e desempenho no aparelho" else diagnosticsSummary(isBusy, statusMessage),
        onClick = { onOpen(SettingsSection.DIAGNOSTICS) },
    )

    // ON_DEVICE is the one mode whose remaining blockers are invisible from the
    // root list, so the honest one-liner is repeated here rather than buried.
    if (settings.mode == ModelMode.ON_DEVICE && deviceRecommendation?.runnable == false) {
        Text(
            text = deviceRecommendation.explanation,
            style = MaterialTheme.typography.labelSmall,
            color = palette.caution,
            modifier = Modifier.padding(top = 12.dp),
        )
    } else if (settings.mode == ModelMode.ON_DEVICE && installedModels.isEmpty()) {
        Text(
            text = "Nenhum modelo instalado no aparelho ainda.",
            style = MaterialTheme.typography.labelSmall,
            color = palette.caution,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** One navigable row of the root list: title, current value, chevron. */
@Composable
private fun SettingsMenuRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val palette = TalosColors.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceElevated,
        contentColor = palette.textPrimary,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textPrimary,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.textFaint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LocalModelCatalogSection(
    bundles: List<HuggingFaceModelBundle>,
    searchedQuery: String?,
    busy: Boolean,
    error: String?,
    recommendation: ModelRecommendation?,
    installedModels: List<ModelFile>,
    download: LocalModelDownloadStatus?,
    onSearch: (String) -> Unit,
    onInstall: (HuggingFaceModelBundle) -> Unit,
    onCancelDownload: () -> Unit,
    onSelectModel: (String) -> Unit,
) {
    val palette = TalosColors.current
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf(searchedQuery.orEmpty()) }
    var pendingInstall by remember { mutableStateOf<HuggingFaceModelBundle?>(null) }

    if (pendingInstall != null) {
        val selected = pendingInstall!!
        val estimatedFit = recommendation?.takeIf { it.runnable }
            ?.let { selected.bytes <= it.modelFileBudgetBytes }
        AlertDialog(
            onDismissRequest = { pendingInstall = null },
            title = { Text("Baixar e instalar este modelo?") },
            text = {
                Column {
                    Text("${selected.repository}\n${selected.filename}")
                    Text("Tamanho: ${selected.sizeLabel}", modifier = Modifier.padding(top = 8.dp))
                    Text(
                        text = when {
                            recommendation?.runnable == false -> "Este aparelho não passou na checagem mínima para modelos locais."
                            estimatedFit == true -> "O tamanho cabe no orçamento de RAM medido; a inicialização ainda depende do bundle e do runtime."
                            estimatedFit == false -> "Este arquivo excede o orçamento de RAM medido e pode não carregar neste aparelho."
                            else -> "Não foi possível estimar o encaixe de RAM neste aparelho."
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text("Licença: ${selected.license ?: "não informada"}")
                    Text("SHA-256: ${selected.sha256}")
                    Text(
                        "O Talos vai baixar do Hugging Face, verificar o tamanho e o SHA-256 e só então salvar o arquivo na pasta de modelos. Os pesos passam a ocupar ${selected.sizeLabel} no armazenamento.",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingInstall = null
                    onInstall(selected)
                }) {
                    Text(if (estimatedFit == false || recommendation?.runnable == false) "Instalar mesmo assim" else "Baixar e instalar")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingInstall = null }) { Text("Cancelar") }
            },
        )
    }

    LaunchedEffect(Unit) {
        if (searchedQuery == null) onSearch("")
    }

    Text(
        "Busque no Hugging Face bundles .litertlm, o formato que o Talos usa no aparelho. " +
            "Arquivos GGUF, safetensors e pesos comuns não são carregados diretamente pelo app.",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.textSecondary,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(100) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Buscar modelo, ex.: Qwen3 ou Gemma") },
    )
    OutlinedButton(
        onClick = { onSearch(query) },
        enabled = !busy && download?.let { !it.complete && it.error == null } != true,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (busy) "Buscando no Hugging Face…" else "Buscar modelos")
    }

    if (error != null) {
        Text(
            text = error,
            color = palette.danger,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
    } else if (searchedQuery != null && !busy && bundles.isEmpty()) {
        Text(
            text = "Nenhum bundle .litertlm público encontrado para esta busca. Tente outro nome.",
            color = palette.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    download?.let { progress ->
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = RoundedCornerShape(14.dp),
            color = palette.surfaceVariant,
            contentColor = palette.textPrimary,
        ) {
            Column(Modifier.padding(14.dp)) {
                val status = when {
                    progress.complete -> "Download verificado e instalado"
                    progress.error != null -> "Falha ao instalar: ${progress.error}"
                    progress.verifying -> "Verificando tamanho e SHA-256…"
                    else -> "Baixando ${progress.bundle.title} · ${progressPercent(progress)}%"
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (!progress.complete && progress.error == null) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progressFraction(progress) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    TextButton(onClick = onCancelDownload) { Text("Cancelar download") }
                }
                if (progress.error != null) {
                    TextButton(onClick = { pendingInstall = progress.bundle }) { Text("Tentar novamente") }
                }
                if (progress.complete) {
                    Text("Agora você pode selecionar em ‘Onde roda o modelo’.", modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (bundles.isNotEmpty()) {
        val queryLabel = searchedQuery?.takeIf(String::isNotBlank)?.let { " para ‘$it’" }.orEmpty()
        Text(
            text = "${bundles.size} bundles encontrados$queryLabel",
            style = MaterialTheme.typography.titleSmall,
            color = palette.textPrimary,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
    }

    bundles.forEach { bundle ->
        val fileName = bundle.filename.substringAfterLast('/')
        val installed = installedModels.firstOrNull {
            it.name.equals(fileName, ignoreCase = true) ||
                it.name.equals("${bundle.repository.substringBefore('/')}__$fileName", ignoreCase = true)
        }
        val alreadyInstalled = installed != null
        val fitsEstimate = recommendation?.takeIf { it.runnable }
            ?.let { bundle.bytes <= it.modelFileBudgetBytes }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(14.dp),
            color = palette.surfaceElevated,
            contentColor = palette.textPrimary,
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = bundle.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = bundle.repository,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textFaint,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "$fileName · ${bundle.sizeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = when {
                        alreadyInstalled -> "Este arquivo já está instalado"
                        fitsEstimate == true -> "Tamanho dentro do orçamento de RAM medido"
                        fitsEstimate == false -> "Acima do orçamento de RAM medido"
                        else -> "Não foi possível estimar o encaixe neste aparelho"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        alreadyInstalled || fitsEstimate == true -> palette.brandInk
                        fitsEstimate == false -> palette.caution
                        else -> palette.textSecondary
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
                val license = bundle.license?.replace('-', ' ') ?: "não informada"
                Text(
                    text = "Licença: $license · ${bundle.downloads} downloads no último mês",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textFaint,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "O tamanho é uma estimativa de encaixe, não garantia de execução. O Talos valida o bundle ao carregá-lo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textFaint,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(bundle.repositoryUrl)))
                        }
                    },
                    modifier = Modifier.padding(start = 0.dp, top = 4.dp),
                ) { Text("Abrir repositório no Hugging Face") }
                if (installed != null) {
                    TextButton(onClick = { onSelectModel(installed.name) }) { Text("Usar este modelo") }
                } else {
                    TextButton(
                        enabled = bundle.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true &&
                            (download == null || download.complete || download.error != null),
                        onClick = { pendingInstall = bundle },
                    ) {
                        Text(
                            if (bundle.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true) {
                                "Baixar e instalar"
                            } else {
                                "Sem SHA-256 verificável"
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun progressFraction(status: LocalModelDownloadStatus): Float =
    if (status.totalBytes <= 0L) 0f
    else (status.downloadedBytes.toDouble() / status.totalBytes).toFloat().coerceIn(0f, 1f)

private fun progressPercent(status: LocalModelDownloadStatus): Int =
    (progressFraction(status) * 100f).toInt()

// ---------------------------------------------------------------------------
// Onde roda o modelo
// ---------------------------------------------------------------------------

/**
 * The "where does the model run" choice, moved out of the chat drawer.
 *
 * It lives in Settings because it is a persisted preference, not a conversation
 * action: it changes which backend answers every *future* turn, and mixing it in
 * with "Nova conversa" invited the reading that it only affected the open chat.
 */
@Composable
private fun RuntimeSection(
    settings: TalosSettings,
    recommendation: ModelRecommendation?,
    models: List<ModelFile>,
    directory: String,
    selectedModel: String,
    benchmarkResults: Map<String, BenchmarkResult>,
    benchmarkingModel: String?,
    benchmarkStage: String?,
    onUseCloud: () -> Unit,
    onUsePc: () -> Unit,
    onUseOnDevice: () -> Unit,
    onRefreshModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onBenchmarkModel: (String) -> Unit,
    onCancelBenchmark: () -> Unit,
    allowModeChange: Boolean,
) {
    val palette = TalosColors.current

    Text(
        text = "Escolha quem responde às próximas mensagens. Trocar de modo também " +
            "ajusta o modelo e a credencial, porque cada destino tem os seus.",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.textSecondary,
    )
    Spacer(modifier = Modifier.height(12.dp))

    ModeOptionRow(
        title = "Na nuvem",
        description = "O aparelho só monta a conversa e envia pela internet; a resposta " +
            "vem do provedor em nuvem configurado, sem gastar bateria com inferência.",
        selected = settings.mode == ModelMode.CLOUD,
        enabled = allowModeChange,
        onClick = onUseCloud,
    )

    ModeOptionRow(
        title = "No PC",
        description = "As chamadas passam pelo gateway do Talos no computador, que executa " +
            "o agente com o espaço e as ferramentas do desktop. Precisa da chave do gateway.",
        selected = settings.mode == ModelMode.DESKTOP,
        enabled = allowModeChange,
        onClick = onUsePc,
    )

    // An option that silently does nothing is worse than a disabled one: when the
    // probe said no, the row stays visible, disabled, with the measured reason.
    ModeOptionRow(
        title = "No celular (offline)",
        description = "Os pesos rodam dentro do app, sem rede. Mais lento e limitado pelo " +
            "modelo que couber na memória do aparelho.",
        selected = settings.mode == ModelMode.ON_DEVICE,
        enabled = allowModeChange && recommendation?.runnable != false,
        onClick = onUseOnDevice,
    )

    if (recommendation?.runnable == false) {
        Text(
            text = recommendation.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.caution,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    /*
     * The on-device status used to float above the composer, which meant the chat
     * carried a permanent diagnostic panel. It belongs here, next to the choice it
     * explains, and the chat top bar keeps showing the active model suffix.
     */
    if (settings.mode == ModelMode.ON_DEVICE) {
        Spacer(modifier = Modifier.height(20.dp))
        SectionHeader(text = "Modelo neste aparelho")

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = palette.surfaceVariant,
            contentColor = palette.textPrimary,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (recommendation?.runnable == false) {
                            "Este aparelho não comporta um modelo local"
                        } else {
                            "O que o aparelho pode rodar"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (recommendation?.runnable == false) {
                            palette.danger
                        } else {
                            palette.brandInk
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Atualizar lista",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.brand,
                        modifier = Modifier.clickable { onRefreshModels() },
                    )
                }

                // Shown verbatim: this sentence is the measurement's own account of
                // why the phone got this backend and this size class.
                Text(
                    text = recommendation?.explanation
                        ?: "Este aparelho não pôde ser medido; o modelo local ainda pode funcionar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (models.isEmpty()) {
            Text(
                text = "Nenhum modelo instalado. Copie um arquivo .litertlm para " +
                    "${directory.ifBlank { "a pasta de modelos do app" }} e toque em " +
                    "\"Atualizar lista\".",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.caution,
            )
        } else {
            Text(
                text = "Toque em um modelo para rodar nele. A memória medida é uma " +
                    "estimativa: um modelo acima do orçamento pode até rodar, porque os " +
                    "pesos são um arquivo mapeado que o sistema pode descartar — ou pode " +
                    "ser morto ao carregar.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))

            models.forEach { model ->
                OnDeviceModelRow(
                    model = model,
                    selected = model.name == selectedModel.trim(),
                    // The probe's budget is a file-size budget: anything above it
                    // is exactly the case this screen exists to let the user try.
                    overBudget = recommendation?.takeIf { model.bytes > it.modelFileBudgetBytes },
                    result = benchmarkResults[model.name],
                    running = benchmarkingModel == model.name,
                    stage = benchmarkStage,
                    // One measurement at a time: the engine is a single shared
                    // resource and a second run would fight the first for RAM.
                    anyRunning = benchmarkingModel != null,
                    onSelect = { onSelectModel(model.name) },
                    onBenchmark = { onBenchmarkModel(model.name) },
                    onCancel = onCancelBenchmark,
                )
            }
        }
    }
}

/**
 * One installed bundle: its size, whether it is the one in use, the warning if
 * the probe rejected it, its last measurement, and the actions.
 *
 * The whole row selects; the buttons are the only other targets, so a mis-tap
 * costs a selection (reversible, and the check mark shows it) rather than an
 * unannounced multi-minute measurement.
 */
@Composable
private fun OnDeviceModelRow(
    model: ModelFile,
    selected: Boolean,
    overBudget: ModelRecommendation?,
    result: BenchmarkResult?,
    running: Boolean,
    stage: String?,
    anyRunning: Boolean,
    onSelect: () -> Unit,
    onBenchmark: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = TalosColors.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !running) { onSelect() },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) palette.surfaceVariant else palette.surfaceElevated,
        contentColor = palette.textPrimary,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = if (selected) "Em uso" else null,
                    tint = if (selected) palette.brand else palette.background.copy(alpha = 0f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (selected) "${model.sizeLabel} · em uso" else model.sizeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) palette.brandInk else palette.textFaint,
                    )
                }
            }

            if (overBudget != null) {
                Text(
                    text = "Acima do orçamento medido (${formatBytes(overBudget.modelFileBudgetBytes)}). " +
                        "Pode ser morto pelo sistema ou falhar ao carregar — dá para tentar e medir.",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.caution,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (result != null) {
                BenchmarkResultBlock(result = result, modifier = Modifier.padding(top = 10.dp))
            }

            if (running) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = palette.brand,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stage ?: "Medindo…",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBenchmark, enabled = !anyRunning) {
                    Text(text = if (result == null) "Medir velocidade" else "Medir de novo")
                }
                if (running) {
                    TextButton(onClick = onCancel) {
                        Text(text = "Cancelar")
                    }
                }
            }
        }
    }
}

/**
 * One measurement, with its basis stated next to the number.
 *
 * The speed line always carries [BenchmarkResult.speedBasis]'s label, so a
 * tokens/s figure can never appear here as if it were exact: LiteRT-LM exposes
 * no prompt/completion split, and the label says which approximation produced
 * the number.
 */
@Composable
private fun BenchmarkResultBlock(
    result: BenchmarkResult,
    modifier: Modifier = Modifier,
) {
    val palette = TalosColors.current
    val accent = when (result.outcome) {
        BenchmarkOutcome.OK -> palette.success
        BenchmarkOutcome.SLOW -> palette.caution
        BenchmarkOutcome.FAILED_TO_LOAD, BenchmarkOutcome.ERROR -> palette.danger
    }

    Column(modifier = modifier) {
        Text(
            text = result.outcome.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = accent,
        )
        Text(
            text = buildString {
                append("Carregou em ")
                append(formatDuration(result.loadMs))
                append(" · gerou em ")
                append(formatDuration(result.generateMs))
                append(" (")
                append(result.outputChars)
                append(" caracteres)")
                result.backend?.let {
                    append(" · ")
                    append(it.name)
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = result.approxTokensPerSecond?.let {
                "${formatTokensPerSecond(it)} tokens/s — ${result.speedBasis.label}"
            } ?: result.speedBasis.label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textFaint,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = result.detail,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textFaint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * One selectable mode. A row rather than a chip because each option needs a
 * sentence of tradeoff, which a chip has no room for.
 */
@Composable
private fun ModeOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = TalosColors.current
    val titleColor = when {
        !enabled -> palette.textFaint
        selected -> palette.brandInk
        else -> palette.textPrimary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) palette.surfaceVariant else palette.surfaceElevated,
        contentColor = palette.textPrimary,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // The check replaces a radio button so the selected state reads at a
            // glance without adding a second interactive target.
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = if (selected) "Selecionado" else null,
                tint = if (selected) palette.brand else palette.background.copy(alpha = 0f),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) palette.textSecondary else palette.textFaint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Provedor e conexão
// ---------------------------------------------------------------------------

@Composable
private fun ModelSection(
    settings: TalosSettings,
    onEditModel: () -> Unit,
    onTemperatureChange: (Double) -> Unit,
) {
    val palette = TalosColors.current
    Text("Modelo ativo", style = MaterialTheme.typography.titleMedium)
    Text(settings.effectiveModel.ifBlank { "Nenhum modelo configurado" })
    TextButton(onClick = onEditModel) { Text(if (settings.mode == ModelMode.ON_DEVICE) "Escolher modelo instalado" else "Configurar conexão e modelo") }

    Spacer(modifier = Modifier.height(18.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Temperatura",
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
        )
        Text(
            text = formatTemperature(settings.temperature),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = palette.brandInk,
        )
    }

    Slider(
        value = settings.temperature.toFloat(),
        onValueChange = { onTemperatureChange(it.toDouble()) },
        valueRange = 0f..2f,
        colors = SliderDefaults.colors(
            thumbColor = palette.brand,
            activeTrackColor = palette.brand,
            inactiveTrackColor = palette.surfaceVariant,
        ),
    )
    Text(
        text = "Valores baixos deixam as respostas mais determinísticas e são melhores " +
            "para uso de ferramentas; valores altos aumentam a variedade.",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.textFaint,
    )
}

// ---------------------------------------------------------------------------
// Aprovações
// ---------------------------------------------------------------------------

@Composable
private fun ApprovalsSection(
    settings: TalosSettings,
    onRevokeApproval: (String) -> Unit,
    onClearApprovals: () -> Unit,
    onRevokeShellPrefix: (String) -> Unit,
    onPolicyChange: (ApprovalPermissiveness) -> Unit,
) {
    val palette = TalosColors.current
    val approved = settings.autoApprovedTools.sorted()

    Text("Permissividade global", style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
    listOf(
        ApprovalPermissiveness.STRICT to ("Estrito" to "Tudo pergunta, inclusive ferramentas SAFE."),
        ApprovalPermissiveness.BALANCED to ("Equilibrado" to "SAFE passa direto; CAUTION pergunta."),
        ApprovalPermissiveness.LOOSE to ("Solto" to "CAUTION passa direto, exceto shell, arquivos/listagens, área de transferência, agenda, contatos, memória, histórico e skills."),
    ).forEach { (mode, copy) ->
        Row(Modifier.fillMaxWidth().clickable { onPolicyChange(mode) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected = settings.approvalPermissiveness == mode, onClick = { onPolicyChange(mode) })
            Column(Modifier.padding(start = 8.dp)) {
                Text(copy.first, color = palette.textPrimary, style = MaterialTheme.typography.bodyLarge)
                Text(copy.second, color = palette.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Text("Ferramentas DANGEROUS sempre pedem confirmação. Esta preferência vale só para conversas interativas; tarefas agendadas usam a allowlist própria.", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
    Spacer(Modifier.height(12.dp))

    Text(
        text = "As tarefas agendadas mantêm sua allowlist própria. Liberações abaixo valem apenas para conversas interativas.",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.textSecondary,
    )
    Spacer(modifier = Modifier.height(12.dp))

    if (approved.isEmpty()) {
        Text(
            text = "Nenhuma ferramenta aprovada automaticamente.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textFaint,
        )
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = palette.surfaceElevated,
            contentColor = palette.textPrimary,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                approved.forEach { toolName ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = approvalLabel(toolName),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = palette.textPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { onRevokeApproval(toolName) }) {
                            Text(text = "Revogar")
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    Spacer(Modifier.height(14.dp))
    Text("Prefixos de shell liberados", style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
    Text("Cada prefixo autoriza comandos que começam com esse texto. Isso não confina o shell: ele roda como o Talos e pode ler e alterar arquivos privados do app, incluindo memória, skills, conversas e configurações.", style = MaterialTheme.typography.bodySmall, color = palette.danger)
    if (settings.shellCommandPrefixes.isEmpty()) {
        Text("Nenhum prefixo liberado.", style = MaterialTheme.typography.bodyMedium, color = palette.textFaint)
    } else settings.shellCommandPrefixes.sorted().forEach { prefix ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(prefix, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, color = palette.textPrimary)
            TextButton(onClick = { onRevokeShellPrefix(prefix) }) { Text("Revogar") }
        }
    }
    if (approved.isNotEmpty() || settings.shellCommandPrefixes.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onClearApprovals, shape = RoundedCornerShape(12.dp)) {
            Text("Revogar todas as liberações")
        }
    }
}

@Composable
private fun WebSearchSection(
    settings: TalosSettings,
    onSave: suspend (WebSearchProvider, String) -> Result<Unit>,
) {
    val palette = TalosColors.current
    val scope = rememberCoroutineScope()
    var providerName by rememberSaveable { mutableStateOf(settings.webSearchProvider.name) }
    var apiKey by rememberSaveable { mutableStateOf(settings.webSearchApiKey) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("Escolha o provedor de busca e cole a chave de API correspondente.", color = palette.textSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(WebSearchProvider.TAVILY, WebSearchProvider.BRAVE).forEach { provider ->
            OutlinedButton(onClick = { providerName = provider.name }, enabled = !busy) {
                Text(if (provider == WebSearchProvider.TAVILY) "Tavily" else "Brave")
            }
        }
    }
    Text("Selecionado: ${if (providerName == WebSearchProvider.TAVILY.name) "Tavily" else "Brave"}", color = palette.textPrimary)
    OutlinedTextField(
        value = apiKey, onValueChange = { apiKey = it; message = null },
        modifier = Modifier.fillMaxWidth(), label = { Text("Chave de API") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    Text("A chave fica no armazenamento privado do app. A consulta enviada ao provedor pode conter dados da conversa; web_search pede aprovação.", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
    OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            val result = onSave(WebSearchProvider.valueOf(providerName), apiKey)
            busy = false
            message = result.fold({ "Configuração salva." }, { "Não foi possível salvar: ${it.message}" })
        }
    }) { Text(if (busy) "Salvando…" else "Salvar busca web") }
    message?.let { Text(it, color = if (it.startsWith("Não")) palette.danger else palette.success) }
}

// ---------------------------------------------------------------------------
// Diagnóstico
// ---------------------------------------------------------------------------

@Composable
private fun DiagnosticsSection(
    isBusy: Boolean,
    statusMessage: String?,
    statusIsError: Boolean,
    onFetchModels: () -> Unit,
    onTestConnection: () -> Unit,
) {
    val palette = TalosColors.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onFetchModels,
            enabled = !isBusy,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(text = "Buscar modelos")
        }
        OutlinedButton(
            onClick = onTestConnection,
            enabled = !isBusy,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(text = "Testar conexão")
        }
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = palette.brand,
            )
        }
    }

    if (statusMessage != null) {
        Spacer(modifier = Modifier.height(14.dp))
        val accent = when {
            statusIsError -> palette.danger
            else -> palette.success
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = accent.copy(alpha = if (palette.isDark) 0.16f else 0.10f),
            contentColor = palette.textPrimary,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Summaries
// ---------------------------------------------------------------------------

/**
 * The mode in Portuguese, matching the labels of the rows under "Onde roda".
 *
 * ON_DEVICE is spelled "(offline)" on purpose: "Neste celular" and "No celular"
 * are one preposition apart, and the summary is the only place the two ever
 * appear without their explanatory sentences.
 */
private fun modeSummary(mode: ModelMode): String = when (mode) {
    ModelMode.CLOUD -> "Neste celular"
    ModelMode.DESKTOP -> "No PC"
    ModelMode.ON_DEVICE -> "No celular (offline)"
}

private fun providerSummary(settings: TalosSettings): String {
    val provider = settings.provider.displayName
    // Only cloud modes have a provider-side model id worth previewing; in the
    // other two the field names something the provider never serves.
    val model = settings.model.trim()
    return if (model.isBlank() || settings.mode != ModelMode.CLOUD) {
        provider
    } else {
        "$provider · $model"
    }
}

private fun modelSummary(settings: TalosSettings): String =
    settings.model.ifBlank { "Nenhum modelo definido" }

private fun approvalsSummary(settings: TalosSettings): String {
    val tools = settings.autoApprovedTools.size
    val prefixes = settings.shellCommandPrefixes.size
    return when {
        tools == 0 && prefixes == 0 -> "Nenhuma liberação permanente"
        tools > 0 && prefixes > 0 -> "$tools ferramentas · $prefixes prefixos shell"
        prefixes > 0 -> "$prefixes prefixos shell"
        tools == 1 -> "1 ferramenta liberada"
        else -> "$tools ferramentas liberadas"
    }
}

private fun diagnosticsSummary(isBusy: Boolean, statusMessage: String?): String = when {
    isBusy -> "Testando…"
    statusMessage != null -> statusMessage
    else -> "Testar conexão e buscar modelos"
}

// ---------------------------------------------------------------------------
// Small building blocks
// ---------------------------------------------------------------------------

/** Small uppercase section label; adds rhythm above every section. */
@Composable
private fun SectionHeader(text: String) {
    val palette = TalosColors.current
    Text(
        text = text.uppercase(Locale.getDefault()),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 10.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        color = palette.textFaint,
    )
}

/** One decimal, locale-independent so the readout matches the API's expectations. */
private fun formatTemperature(value: Double): String =
    String.format(Locale.US, "%.1f", value)

private fun approvalLabel(name: String): String = when (name) {
    "shell" -> "Executar comandos (shell)"
    "memory" -> "Ler e alterar memórias"
    "skill" -> "Ler e gerenciar skills"
    "session_search" -> "Buscar no histórico de conversas"
    "read_file" -> "Ler arquivos"
    "write_file" -> "Gravar arquivos"
    "fetch_url" -> "Acessar endereços da rede"
    "contacts_search" -> "Consultar contatos"
    "calendar_read" -> "Consultar calendário"
    "clipboard_read" -> "Ler área de transferência"
    "clipboard_write" -> "Alterar área de transferência"
    "open_url" -> "Abrir links e aplicativos"
    "notify" -> "Enviar notificações"
    else -> name
}
