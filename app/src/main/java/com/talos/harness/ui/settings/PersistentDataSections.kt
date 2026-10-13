package com.talos.harness.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.talos.harness.store.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun MemorySection(
    entries: List<MemoryEntry>, directory: String, busy: Boolean, error: String?, settings: TalosSettings,
    conversationTitles: Map<String, String>, onRefresh: () -> Unit,
    onPolicy: suspend (Boolean, Boolean) -> Result<Unit>,
    onSave: suspend (String?, String, MemoryKind) -> Result<Unit>, onDelete: suspend (String) -> Result<Unit>,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var policyBusy by remember { mutableStateOf(false) }
    var policyError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val filtered = entries.filter { it.text.contains(query, true) || originLabel(it.origin, conversationTitles).contains(query, true) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("${entries.size} memórias", style = MaterialTheme.typography.titleMedium)
            Text("${entries.count { it.kind == MemoryKind.AGENT_INFERRED }} inferidas: revise antes de tratá-las como fatos seus. Entradas antigas sem classificação são consideradas inferidas.",
                style = MaterialTheme.typography.bodySmall)
            ToggleRow("Usar memória nas conversas", "Inclui resumos automaticamente nos próximos pedidos. Desativar não apaga os fatos.",
                settings.memoryEnabled, !policyBusy) { enabled ->
                policyBusy = true
                scope.launch { try { policyError = onPolicy(enabled, settings.memoryInRemote).exceptionOrNull()?.message } finally { policyBusy = false } }
            }
            ToggleRow("Permitir uso na nuvem e no PC", "Autoriza a inclusão automática de resumos em modelos fora deste aparelho.",
                settings.memoryInRemote, !policyBusy && settings.memoryEnabled) { remote ->
                policyBusy = true
                scope.launch { try { policyError = onPolicy(settings.memoryEnabled, remote).exceptionOrNull()?.message } finally { policyBusy = false } }
            }
            Text(when {
                !settings.memoryEnabled -> "Inclusão automática desativada."
                !settings.includesMemory -> "Neste modo, as memórias não serão incluídas automaticamente."
                settings.mode == ModelMode.ON_DEVICE -> "Neste modo, os resumos ficam no modelo local."
                settings.usingDesktopBridge -> "Os resumos relevantes serão enviados ao seu gateway no PC."
                else -> "Os resumos relevantes serão enviados a ${settings.provider.displayName}."
            }, style = MaterialTheme.typography.bodySmall)
            Text("Apenas parte das entradas cabe em cada pedido. Leituras explícitas pela ferramenta memory continuam sujeitas às aprovações.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            policyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            StoreStatus(directory, error)
            if (busy) Text("Leitura disponível. Espere a tarefa ou gravação terminar para alterar entradas.")
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Row {
                TextButton(enabled = !busy, onClick = { editing = "" }) { Text("Adicionar") }
                TextButton(onClick = onRefresh) { Text("Atualizar") }
            }
            SearchField(query, { query = it }, "Buscar memórias")
        }
        if (filtered.isEmpty()) item { Text(if (entries.isEmpty()) "Nenhuma memória carregada." else "Nenhuma memória encontrada.") }
        items(filtered, key = { it.id }) { entry ->
            Text(entry.kind.label, style = MaterialTheme.typography.labelLarge,
                color = if (entry.kind == MemoryKind.AGENT_INFERRED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
            Text(entry.text)
            Metadata(entry.createdAt, entry.updatedAt, originLabel(entry.origin, conversationTitles))
            Row {
                TextButton(onClick = { editing = entry.id }) { Text(if (busy) "Ver" else "Ver / editar") }
                TextButton(enabled = !busy, onClick = { deleting = entry.id }) { Text("Apagar") }
            }
            HorizontalDivider()
        }
    }
    editing?.let { id ->
        val entry = entries.firstOrNull { it.id == id }
        key(id) { MemoryEditor(entry, busy, { editing = null }) { text, kind ->
            onSave(id.takeIf { it.isNotEmpty() }, text, kind).onSuccess { notice = "Memória salva." }
        } }
    }
    deleting?.let { id -> DeleteDialog("Apagar esta memória?", busy, { deleting = null }) {
        onDelete(id).onSuccess { notice = "Memória apagada." }
    } }
}

@Composable
fun SkillsSection(
    entries: List<SkillEntry>, directory: String, busy: Boolean, executionUnavailable: String?, error: String?,
    conversationTitles: Map<String, String>, onRefresh: () -> Unit,
    onSave: suspend (String, String, String, String, Boolean) -> Result<Unit>,
    onDelete: suspend (String) -> Result<Unit>, onExecute: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val filtered = entries.filter { it.displayName.contains(query, true) || it.name.contains(query, true) || it.description.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("${entries.size} skills", style = MaterialTheme.typography.titleMedium)
            Text("Procedimentos reutilizáveis. Executar abre um pedido no chat; cada ação mantém suas aprovações.")
            executionUnavailable?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            StoreStatus(directory, error)
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Row {
                TextButton(enabled = !busy, onClick = { editing = "" }) { Text("Criar skill") }
                TextButton(onClick = onRefresh) { Text("Atualizar") }
            }
            SearchField(query, { query = it }, "Buscar skills")
        }
        if (filtered.isEmpty()) item { Text(if (entries.isEmpty()) "Nenhuma skill carregada." else "Nenhuma skill encontrada.") }
        items(filtered, key = { it.name }) { entry ->
            Text(entry.displayName, style = MaterialTheme.typography.titleMedium)
            Text(entry.description)
            Metadata(entry.createdAt, entry.updatedAt, originLabel(entry.origin, conversationTitles))
            Row {
                TextButton(onClick = { editing = entry.name }) { Text(if (busy) "Ver passos" else "Ver / editar") }
                TextButton(enabled = !busy, onClick = { deleting = entry.name }) { Text("Apagar") }
            }
            OutlinedButton(enabled = executionUnavailable == null, onClick = { onExecute(entry.name) }) { Text("Executar no chat") }
            HorizontalDivider()
        }
    }
    editing?.let { id ->
        val entry = entries.firstOrNull { it.name == id }
        key(id) { SkillEditor(entry, busy, { editing = null }) { name, title, description, steps ->
            onSave(name, title, description, steps, id.isEmpty()).onSuccess { notice = "Skill salva." }
        } }
    }
    deleting?.let { id -> DeleteDialog("Apagar ${entries.firstOrNull { it.name == id }?.displayName ?: "esta skill"}?", busy, { deleting = null }) {
        onDelete(id).onSuccess { notice = "Skill apagada." }
    } }
}

@Composable
private fun MemoryEditor(entry: MemoryEntry?, busy: Boolean, onClose: () -> Unit, onSave: suspend (String, MemoryKind) -> Result<Unit>) {
    var text by rememberSaveable { mutableStateOf(entry?.text.orEmpty()) }
    // A manual addition is a human statement; editing an inference alone must not promote it.
    val initialKind = entry?.kind ?: MemoryKind.USER_DECLARED
    var kindValue by rememberSaveable { mutableStateOf(initialKind.value) }
    val kind = MemoryKind.parse(kindValue)
    DocumentEditor(if (entry == null) "Adicionar memória" else "Memória", text != entry?.text.orEmpty() || kind != initialKind, busy,
        text.isNotBlank() && text.length <= MemoryStore.MAX_TEXT, onClose, { onSave(text, kind) }) { locked ->
        Text("Classificação", style = MaterialTheme.typography.titleSmall)
        MemoryKind.entries.forEach { option ->
            FilterChip(selected = kind == option, onClick = { kindValue = option.value }, enabled = !locked,
                label = { Text(option.label) })
        }
        Text(if (kind == MemoryKind.AGENT_INFERRED) "Interpretação do agente, ainda não confirmada por você."
            else "Use apenas para algo que você afirmou ou confirma agora.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = text, onValueChange = { text = it }, readOnly = locked,
            label = { Text("Fato ou preferência") }, supportingText = { Text("${text.length}/${MemoryStore.MAX_TEXT}") },
            isError = text.length > MemoryStore.MAX_TEXT, modifier = Modifier.fillMaxWidth(), minLines = 5)
    }
}

@Composable
private fun SkillEditor(entry: SkillEntry?, busy: Boolean, onClose: () -> Unit,
    onSave: suspend (String, String, String, String) -> Result<Unit>) {
    var title by rememberSaveable { mutableStateOf(entry?.displayName.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(entry?.description.orEmpty()) }
    var steps by rememberSaveable { mutableStateOf(entry?.steps.orEmpty()) }
    var details by rememberSaveable { mutableStateOf(false) }
    val name = entry?.name ?: skillIdentifier(title)
    val dirty = title != entry?.displayName.orEmpty() || description != entry?.description.orEmpty() || steps != entry?.steps.orEmpty()
    val valid = title.isNotBlank() && title.length <= 80 && name.isNotBlank() && description.isNotBlank() && description.length <= 160 &&
        steps.isNotBlank() && steps.length <= SkillStore.MAX_STEPS && '\n' !in title && '\r' !in title && '\n' !in description && '\r' !in description
    DocumentEditor(if (entry == null) "Criar skill" else "Skill", dirty, busy, valid, onClose,
        { onSave(name, title, description, steps) }) { locked ->
        OutlinedTextField(value = title, onValueChange = { title = it }, readOnly = locked, label = { Text("Nome") },
            singleLine = true, supportingText = { Text("${title.length}/80") }, isError = title.length > 80, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = description, onValueChange = { description = it }, readOnly = locked, label = { Text("Descrição") },
            singleLine = true, supportingText = { Text("${description.length}/160") }, isError = description.length > 160, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = steps, onValueChange = { steps = it }, readOnly = locked, label = { Text("Passos") },
            supportingText = { Text("${steps.length}/${SkillStore.MAX_STEPS}") }, isError = steps.length > SkillStore.MAX_STEPS,
            minLines = 10, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { details = !details }) { Text(if (details) "Ocultar detalhes" else "Detalhes") }
        if (details) Text("Identificador: $name", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentEditor(title: String, dirty: Boolean, busy: Boolean, valid: Boolean, onClose: () -> Unit,
    onSave: suspend () -> Result<Unit>, content: @Composable (Boolean) -> Unit) {
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val close = { if (!saving) { if (dirty) discard = true else onClose() } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { close() }
        Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(enabled = !saving, onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
        }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) Text("Leitura disponível. A edição será liberada quando a tarefa terminar.")
                content(busy || saving)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = valid && !busy && !saving, onClick = {
                    scope.launch {
                        saving = true
                        try {
                            val result = onSave()
                            if (result.isSuccess) onClose() else error = result.exceptionOrNull()?.message ?: "Não foi possível salvar."
                        } finally { saving = false }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Salvando…" else "Salvar") }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Descartar alterações?") },
            text = { Text("O texto ainda não foi salvo.") },
            confirmButton = { TextButton(onClick = onClose) { Text("Descartar") } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("Continuar editando") } })
    }
}

@Composable
private fun DeleteDialog(title: String, busy: Boolean, onClose: () -> Unit, onDelete: suspend () -> Result<Unit>) {
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!deleting) onClose() }, title = { Text(title) },
        text = { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
        confirmButton = { TextButton(enabled = !busy && !deleting, onClick = {
            scope.launch {
                deleting = true
                try {
                    val result = onDelete()
                    if (result.isSuccess) onClose() else error = result.exceptionOrNull()?.message ?: "Não foi possível apagar."
                } finally { deleting = false }
            }
        }) { Text(if (deleting) "Apagando…" else "Apagar") } },
        dismissButton = { TextButton(enabled = !deleting, onClick = onClose) { Text("Cancelar") } })
}

@Composable
private fun ToggleRow(title: String, summary: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.weight(1f)) { Text(title); Text(summary, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun StoreStatus(directory: String, error: String?) {
    var details by rememberSaveable { mutableStateOf(false) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (directory.isNotBlank()) {
        TextButton(onClick = { details = !details }) { Text(if (details) "Ocultar detalhes" else "Detalhes de armazenamento") }
        if (details) Text("Arquivos de texto: $directory", style = MaterialTheme.typography.bodySmall)
    }
}

private fun originLabel(origin: String, titles: Map<String, String>): String =
    if (origin.startsWith("conversa: ")) "Conversa: ${titles[origin.removePrefix("conversa: ")] ?: "conversa removida ou indisponível"}"
    else if (origin == "usuário: ajustes") "Você, nos Ajustes" else origin

private fun displayDate(value: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

@Composable
private fun Metadata(created: String, updated: String, origin: String) {
    Text("Criado: ${displayDate(created)} · Atualizado: ${displayDate(updated)}\nOrigem: $origin", style = MaterialTheme.typography.bodySmall)
}
