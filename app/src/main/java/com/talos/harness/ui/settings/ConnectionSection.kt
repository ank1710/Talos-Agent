package com.talos.harness.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.talos.harness.store.*
import kotlinx.coroutines.launch

@Composable
fun ConnectionSection(
    settings: TalosSettings,
    running: Boolean,
    onDirtyChange: (Boolean) -> Unit,
    onSavingChange: (Boolean) -> Unit,
    onSave: suspend (ConnectionDraft) -> Result<Unit>,
    onTest: suspend (ConnectionDraft) -> Result<Unit>,
    onFetchModels: suspend (ConnectionDraft) -> Result<List<ProviderModel>>,
) {
    // Secrets must not enter saved-instance state. The committed profile survives process death.
    var draft by remember(settings.mode) { mutableStateOf(settings.connectionDraft()) }
    var baseline by remember(settings.mode) { mutableStateOf(settings.connectionDraft()) }
    var visible by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<ProviderModel>()) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun update(value: ConnectionDraft) {
        draft = value
        message = null
        models = emptyList()
        onDirtyChange(value != baseline)
    }
    DisposableEffect(Unit) { onDispose { onDirtyChange(false); onSavingChange(false) } }

    Text(if (draft.desktop) "Conexão com o computador" else "Conexão com o provedor", style = MaterialTheme.typography.titleMedium)
    Text("As alterações só entram em uso ao salvar. Testar usa o endereço e a chave deste formulário.", modifier = Modifier.padding(vertical = 8.dp))
    if (running) Text("A conversa em andamento mantém sua configuração. Espere terminar para salvar.")
    if (!draft.desktop) {
        Box {
            OutlinedButton(onClick = { providerMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Provedor: ${draft.provider.displayName}")
            }
            DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                ProviderCatalog.all.forEach { provider ->
                    DropdownMenuItem(text = { Text(provider.displayName) }, onClick = {
                        providerMenu = false
                        val profile = settings.profiles[provider.id]
                        update(draft.retarget(provider.id, profile?.baseUrl ?: provider.baseUrl.orEmpty(), settings.credentials)
                            .copy(model = profile?.model ?: provider.defaultModel.orEmpty()))
                        visible = false
                    })
                }
            }
        }
    }
    OutlinedTextField(value = draft.baseUrl, onValueChange = {
        update(draft.retarget(draft.providerId, it, settings.credentials)); visible = false
    }, enabled = !busy, label = { Text(if (draft.desktop) "Endereço do gateway" else "URL base") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    Text("Cada endereço tem sua própria chave. Ao mudar o destino, apenas a chave já salva para ele será carregada.",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
    OutlinedTextField(value = draft.apiKey, onValueChange = { update(draft.copy(apiKey = it, keyScope = draft.scope)) },
        enabled = !busy, label = { Text(if (draft.desktop) "Chave do gateway" else "Chave de API") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Ocultar" else "Mostrar") } })
    if (!draft.desktop) {
        OutlinedTextField(value = draft.model, onValueChange = { update(draft.copy(model = it)) }, enabled = !busy,
            label = { Text("Modelo") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Box {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        val result = onFetchModels(draft)
                        models = result.getOrDefault(emptyList())
                        error = result.isFailure
                        message = result.exceptionOrNull()?.message ?: "${models.size} modelos disponíveis."
                        modelMenu = models.isNotEmpty()
                    } finally { busy = false }
                }
            }) { Text("Buscar modelos neste endereço") }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                models.forEach { model -> DropdownMenuItem(text = { Text(model.name) }, onClick = {
                    modelMenu = false; update(draft.copy(model = model.id))
                }) }
            }
        }
    } else Text("Use a chave configurada no gateway do seu computador.", style = MaterialTheme.typography.bodySmall)

    message?.let { Text(it, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)) }
    // Stacked actions remain usable with large fonts and a narrow screen.
    Button(enabled = !busy && !running, modifier = Modifier.fillMaxWidth(), onClick = {
        draft.validation()?.let { message = it; error = true; return@Button }
        scope.launch {
            busy = true; saving = true; onSavingChange(true)
            try {
                val result = onSave(draft)
                error = result.isFailure
                message = result.exceptionOrNull()?.message ?: "Configuração salva."
                if (result.isSuccess) { baseline = draft; onDirtyChange(false) }
            } finally { busy = false; saving = false; onSavingChange(false) }
        }
    }) { Text(if (saving) "Salvando…" else "Salvar configuração") }
    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
        scope.launch {
            busy = true
            try {
                val result = onTest(draft)
                error = result.isFailure
                message = result.exceptionOrNull()?.message ?: "Conexão verificada para este endereço. Alterações ainda precisam ser salvas."
            } finally { busy = false }
        }
    }) { Text(if (busy && !saving) "Verificando…" else "Testar conexão") }
    TextButton(enabled = !busy && draft != baseline, onClick = {
        draft = baseline; message = null; models = emptyList(); onDirtyChange(false)
    }) { Text("Descartar alterações") }
}
