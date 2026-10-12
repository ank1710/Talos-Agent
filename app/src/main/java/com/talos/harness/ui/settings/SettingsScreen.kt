package com.talos.harness.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talos.harness.store.ProviderCatalog
import com.talos.harness.store.ProviderModel
import com.talos.harness.store.TalosSettings
import com.talos.harness.ui.theme.TalosColors
import java.util.Locale

/**
 * Talos settings.
 *
 * Renders purely from [settings]: every edit is pushed straight up through the
 * `on*Change` callbacks and comes back down as a new [settings] value, so the
 * screen never holds a second copy of persisted state that could drift from the
 * store. The only local state is presentation-only (the API key mask toggle and
 * whether the provider or model dropdown is open).
 *
 * @param availableModels models discovered from the selected provider; an empty
 *   list hides the dropdown while the free-text model field stays usable.
 * @param isBusy a network probe is in flight; both diagnostic actions are
 *   disabled and a spinner is shown next to them.
 * @param statusMessage result of the last probe, or `null` when there has been
 *   none this session.
 * @param statusIsError tints [statusMessage] as a failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: TalosSettings,
    availableModels: List<ProviderModel>,
    isBusy: Boolean,
    statusMessage: String?,
    statusIsError: Boolean,
    onApiKeyChange: (String) -> Unit,
    onProviderChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onUseLocalGateway: () -> Unit,
    onGatewayApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onTemperatureChange: (Double) -> Unit,
    onFetchModels: () -> Unit,
    onTestConnection: () -> Unit,
    onRevokeApproval: (String) -> Unit,
    onClearApprovals: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TalosColors.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = { Text(text = "Ajustes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
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
            ConnectionSection(
                settings = settings,
                availableModels = availableModels,
                onApiKeyChange = onApiKeyChange,
                onProviderChange = onProviderChange,
                onBaseUrlChange = onBaseUrlChange,
                onUseLocalGateway = onUseLocalGateway,
                onGatewayApiKeyChange = onGatewayApiKeyChange,
                onModelChange = onModelChange,
                onTemperatureChange = onTemperatureChange,
            )

            SectionHeader(text = "Aprovações")
            ApprovalsSection(
                settings = settings,
                onRevokeApproval = onRevokeApproval,
                onClearApprovals = onClearApprovals,
            )
            Spacer(modifier = Modifier.height(28.dp))

            SectionHeader(text = "Diagnóstico")
            DiagnosticsSection(
                isBusy = isBusy,
                statusMessage = statusMessage,
                statusIsError = statusIsError,
                onFetchModels = onFetchModels,
                onTestConnection = onTestConnection,
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionSection(
    settings: TalosSettings,
    availableModels: List<ProviderModel>,
    onApiKeyChange: (String) -> Unit,
    onProviderChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onUseLocalGateway: () -> Unit,
    onGatewayApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onTemperatureChange: (Double) -> Unit,
) {
    val palette = TalosColors.current

    // Presentation-only: whether the API key is shown in clear text. Deliberately
    // NOT rememberSaveable — a secret should not be re-exposed after process death.
    var apiKeyVisible by remember { mutableStateOf(false) }
    var gatewayKeyVisible by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelsExpanded by remember(settings.providerId) { mutableStateOf(false) }

    SectionHeader(text = "Conexão")

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

    Spacer(modifier = Modifier.height(18.dp))

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
    Spacer(modifier = Modifier.height(28.dp))
}

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
