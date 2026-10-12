package com.talos.harness.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talos.harness.local.ModelFile
import com.talos.harness.local.ModelRecommendation
import com.talos.harness.store.ModelMode
import com.talos.harness.store.ProviderCatalog
import com.talos.harness.store.ProviderModel
import com.talos.harness.store.TalosSettings
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
    CONNECTION("Provedor e conexão"),
    MODEL("Modelo"),
    APPROVALS("Aprovações"),
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
    onApiKeyChange: (String) -> Unit,
    onProviderChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onUseLocalGateway: () -> Unit,
    onGatewayApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onTemperatureChange: (Double) -> Unit,
    onUseCloud: () -> Unit,
    onUsePc: () -> Unit,
    onUseOnDevice: () -> Unit,
    onRefreshModels: () -> Unit,
    onFetchModels: () -> Unit,
    onTestConnection: () -> Unit,
    onRevokeApproval: (String) -> Unit,
    onClearApprovals: () -> Unit,
    onBack: () -> Unit,
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
    val goBack: () -> Unit = {
        if (section == SettingsSection.ROOT) onBack() else sectionName = SettingsSection.ROOT.name
    }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (section) {
                SettingsSection.ROOT -> RootSection(
                    settings = settings,
                    isBusy = isBusy,
                    statusMessage = statusMessage,
                    deviceRecommendation = deviceRecommendation,
                    installedModels = installedModels,
                    onOpen = { sectionName = it.name },
                )

                SettingsSection.RUNTIME -> RuntimeSection(
                    settings = settings,
                    recommendation = deviceRecommendation,
                    models = installedModels,
                    directory = modelsDirectory,
                    onUseCloud = onUseCloud,
                    onUsePc = onUsePc,
                    onUseOnDevice = onUseOnDevice,
                    onRefreshModels = onRefreshModels,
                )

                SettingsSection.CONNECTION -> ConnectionSection(
                    settings = settings,
                    onApiKeyChange = onApiKeyChange,
                    onProviderChange = onProviderChange,
                    onBaseUrlChange = onBaseUrlChange,
                    onUseLocalGateway = onUseLocalGateway,
                    onGatewayApiKeyChange = onGatewayApiKeyChange,
                )

                SettingsSection.MODEL -> ModelSection(
                    settings = settings,
                    availableModels = availableModels,
                    onModelChange = onModelChange,
                    onTemperatureChange = onTemperatureChange,
                )

                SettingsSection.APPROVALS -> ApprovalsSection(
                    settings = settings,
                    onRevokeApproval = onRevokeApproval,
                    onClearApprovals = onClearApprovals,
                )

                SettingsSection.DIAGNOSTICS -> DiagnosticsSection(
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
    onOpen: (SettingsSection) -> Unit,
) {
    val palette = TalosColors.current

    SettingsMenuRow(
        title = SettingsSection.RUNTIME.title,
        summary = modeSummary(settings.mode),
        onClick = { onOpen(SettingsSection.RUNTIME) },
    )
    SettingsMenuRow(
        title = SettingsSection.CONNECTION.title,
        summary = providerSummary(settings),
        onClick = { onOpen(SettingsSection.CONNECTION) },
    )
    SettingsMenuRow(
        title = SettingsSection.MODEL.title,
        summary = modelSummary(settings),
        onClick = { onOpen(SettingsSection.MODEL) },
    )
    SettingsMenuRow(
        title = SettingsSection.APPROVALS.title,
        summary = approvalsSummary(settings),
        onClick = { onOpen(SettingsSection.APPROVALS) },
    )
    SettingsMenuRow(
        title = SettingsSection.DIAGNOSTICS.title,
        summary = diagnosticsSummary(isBusy, statusMessage),
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
    onUseCloud: () -> Unit,
    onUsePc: () -> Unit,
    onUseOnDevice: () -> Unit,
    onRefreshModels: () -> Unit,
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
        title = "Neste celular",
        description = "O aparelho só monta a conversa e envia pela internet; a resposta " +
            "vem do provedor em nuvem configurado, sem gastar bateria com inferência.",
        selected = settings.mode == ModelMode.CLOUD,
        enabled = true,
        onClick = onUseCloud,
    )

    ModeOptionRow(
        title = "No PC",
        description = "As chamadas passam pelo gateway do Talos no computador, que executa " +
            "o agente com o espaço e as ferramentas do desktop. Precisa da chave do gateway.",
        selected = settings.mode == ModelMode.DESKTOP,
        enabled = true,
        onClick = onUsePc,
    )

    // An option that silently does nothing is worse than a disabled one: when the
    // probe said no, the row stays visible, disabled, with the measured reason.
    ModeOptionRow(
        title = "No celular (offline)",
        description = "Os pesos rodam dentro do app, sem rede. Mais lento e limitado pelo " +
            "modelo que couber na memória do aparelho.",
        selected = settings.mode == ModelMode.ON_DEVICE,
        enabled = recommendation?.runnable != false,
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = palette.surfaceElevated,
                contentColor = palette.textPrimary,
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    models.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = model.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = model.sizeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textFaint,
                            )
                        }
                    }
                }
            }
        }
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
private fun ConnectionSection(
    settings: TalosSettings,
    onApiKeyChange: (String) -> Unit,
    onProviderChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onUseLocalGateway: () -> Unit,
    onGatewayApiKeyChange: (String) -> Unit,
) {
    val palette = TalosColors.current

    // Presentation-only: whether the API key is shown in clear text. Deliberately
    // NOT rememberSaveable — a secret should not be re-exposed after process death.
    var apiKeyVisible by remember { mutableStateOf(false) }
    var gatewayKeyVisible by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = settings.apiKey,
        onValueChange = onApiKeyChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = "Chave de API") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (apiKeyVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            // Text instead of an eye icon: material-icons-extended is not a
            // dependency (it would add ~10 MB), and the core icon set has no
            // visibility glyphs.
            TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                Text(
                    text = if (apiKeyVisible) "Ocultar" else "Mostrar",
                    style = MaterialTheme.typography.labelSmall,
                    color = TalosColors.current.brandInk,
                )
            }
        },
    )

    Spacer(modifier = Modifier.height(14.dp))

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { providerExpanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(text = "Provedor: ${settings.provider.displayName}")
        }
        DropdownMenu(
            expanded = providerExpanded,
            onDismissRequest = { providerExpanded = false },
        ) {
            ProviderCatalog.all.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(text = provider.displayName) },
                    onClick = {
                        providerExpanded = false
                        onProviderChange(provider.id)
                    },
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    OutlinedTextField(
        value = settings.baseUrl,
        onValueChange = onBaseUrlChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = "URL base") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )

    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "O provedor selecionado preenche a URL, que continua editável para " +
            "servidores personalizados. Um gateway compatível com OpenAI rodando " +
            "no PC (padrão ${TalosSettings.LOCAL_GATEWAY_BASE_URL}) roteia as " +
            "chamadas pelo agente do desktop.",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.textFaint,
    )
    Spacer(modifier = Modifier.height(8.dp))
    SuggestionChip(
        onClick = onUseLocalGateway,
        label = { Text(text = "Usar gateway local") },
    )

    Spacer(modifier = Modifier.height(14.dp))

    // The gateway authenticates with its own secret, separate from the DeepSeek
    // key. Keeping them in distinct fields is what stops one being sent to the
    // other's endpoint — the failure that motivated this field.
    OutlinedTextField(
        value = settings.gatewayApiKey,
        onValueChange = onGatewayApiKeyChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = "Chave do gateway (API_SERVER_KEY)") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (gatewayKeyVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            TextButton(onClick = { gatewayKeyVisible = !gatewayKeyVisible }) {
                Text(
                    text = if (gatewayKeyVisible) "Ocultar" else "Mostrar",
                    style = MaterialTheme.typography.labelSmall,
                    color = TalosColors.current.brandInk,
                )
            }
        },
    )
    Text(
        text = "Usada só no modo PC. No Mac: openssl rand -hex 32, e a mesma em API_SERVER_KEY.",
        style = MaterialTheme.typography.labelSmall,
        color = palette.textFaint,
        modifier = Modifier.padding(top = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// Modelo
// ---------------------------------------------------------------------------

@Composable
private fun ModelSection(
    settings: TalosSettings,
    availableModels: List<ProviderModel>,
    onModelChange: (String) -> Unit,
    onTemperatureChange: (Double) -> Unit,
) {
    val palette = TalosColors.current
    var modelsExpanded by remember(settings.providerId) { mutableStateOf(false) }

    OutlinedTextField(
        value = settings.model,
        onValueChange = onModelChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = "Modelo") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
    )

    if (availableModels.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        val selectedModel = availableModels.firstOrNull { it.id == settings.model }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { modelsExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(text = "Modelos disponíveis: ${selectedModel?.name ?: "Escolher"}")
            }
            DropdownMenu(
                expanded = modelsExpanded,
                onDismissRequest = { modelsExpanded = false },
            ) {
                availableModels.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(text = model.name)
                                if (model.name != model.id) {
                                    Text(
                                        text = model.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TalosColors.current.textFaint,
                                    )
                                }
                            }
                        },
                        onClick = {
                            modelsExpanded = false
                            onModelChange(model.id)
                        },
                    )
                }
            }
        }
    }

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
) {
    val palette = TalosColors.current
    val approved = settings.autoApprovedTools.sorted()

    Text(
        text = "Toda ferramenta que não seja de leitura segura pausa e pede " +
            "confirmação antes de executar. As ferramentas abaixo foram liberadas " +
            "para sempre.",
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
                            text = toolName,
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
        OutlinedButton(
            onClick = onClearApprovals,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(text = "Limpar tudo")
        }
    }
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
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
    val count = settings.autoApprovedTools.size
    return when (count) {
        0 -> "Nenhuma ferramenta liberada"
        1 -> "1 ferramenta liberada"
        else -> "$count ferramentas liberadas"
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
