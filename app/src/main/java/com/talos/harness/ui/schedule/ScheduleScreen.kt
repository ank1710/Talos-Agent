package com.talos.harness.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talos.harness.schedule.ScheduledTask
import com.talos.harness.ui.theme.TalosColors
import java.util.Locale

/** Preset intervals offered by the create dialog: 15 min, 1 h, 6 h, 1 day. */
private val IntervalPresets = listOf(15, 60, 360, 1440)

/** Height cap for the expanded `lastResult` box, mirroring the chat tool card. */
private val ResultMaxHeight = 320.dp

/**
 * Talos scheduled tasks.
 *
 * Every row renders purely from the [tasks] handed down by the view model: the
 * screen owns no copy of persisted state, so a task can never be shown one way
 * while the store (and the WorkManager schedule behind it) holds another. The
 * only local state is presentation-only — which result is expanded, whether the
 * create dialog is open, and the dialog's own draft fields.
 *
 * The screen deliberately shows the trust model up front instead of hiding it in
 * settings: an unattended run has nobody to answer an approval prompt, so a task
 * can only use confirmation-free tools unless its author explicitly opts in.
 *
 * @param tasks enabled and paused tasks, in store order.
 * @param onSave receives a brand-new task from the dialog, or an existing task
 *   with one field flipped in the row toggle.
 * @param onRunNow id of the task to execute immediately.
 * @param onDelete id of the task to remove permanently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    tasks: List<ScheduledTask>,
    onBack: () -> Unit,
    onSave: (ScheduledTask) -> Unit,
    onDelete: (String) -> Unit,
    onRunNow: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TalosColors.current
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = { Text(text = "Tarefas agendadas") },
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
                .padding(bottom = 32.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionHeader(text = "Como funciona")
                UnattendedNotice()

                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { creating = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.brand,
                        contentColor = palette.userBubbleText,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Nova tarefa")
                }

                SectionHeader(text = "Suas tarefas")
            }

            if (tasks.isEmpty()) {
                Text(
                    text = "Nenhuma tarefa agendada ainda.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textFaint,
                )
            } else {
                val now = System.currentTimeMillis()
                tasks.forEach { task ->
                    TaskRow(
                        task = task,
                        now = now,
                        onRunNow = onRunNow,
                        onDelete = onDelete,
                        onSave = onSave,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    // The dialog is only composed while open, so its draft state dies with it and
    // the next open always starts from a blank task instead of a half-typed one.
    if (creating) {
        CreateTaskDialog(
            onDismiss = { creating = false },
            onConfirm = { task ->
                onSave(task)
                creating = false
            },
        )
    }
}

// ---------------------------------------------------------------------------
// List rows
// ---------------------------------------------------------------------------

/** One task: identity, cadence, last outcome, and the three row actions. */
@Composable
private fun TaskRow(
    task: ScheduledTask,
    now: Long,
    onRunNow: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSave: (ScheduledTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TalosColors.current
    // Presentation-only: a collapsed result is three lines, matching the tool
    // cards in the transcript so output reads the same in both places.
    var resultExpanded by remember(task.id) { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceElevated,
        contentColor = palette.textPrimary,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (task.enabled) palette.textPrimary else palette.textFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!task.enabled) {
                    Spacer(modifier = Modifier.width(8.dp))
                    PausedBadge()
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = describeInterval(task.intervalMinutes),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )

            val lastRunAt = task.lastRunAt
            if (lastRunAt != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = describeAgo(lastRunAt, now),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textFaint,
                    )
                    val status = task.lastStatus
                    if (status != null) {
                        Text(
                            text = "  ·  ",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textFaint,
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (isFailureStatus(status)) palette.danger else palette.success,
                        )
                    }
                }
            }

            val lastResult = task.lastResult
            if (!lastResult.isNullOrBlank()) {
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Resultado",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.0.sp,
                        color = palette.textFaint,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = if (resultExpanded) "ocultar" else "ver resultado",
                        modifier = Modifier.clickable { resultExpanded = !resultExpanded },
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.brandInk,
                    )
                }
                Text(
                    text = lastResult,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.codeBackground)
                        .border(1.dp, palette.codeBorder, RoundedCornerShape(10.dp))
                        .heightIn(max = if (resultExpanded) ResultMaxHeight else 66.dp)
                        .let { base ->
                            if (resultExpanded) base.verticalScroll(rememberScrollState()) else base
                        }
                        .padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = palette.codeText,
                    maxLines = if (resultExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onRunNow(task.id) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(text = "Rodar agora", color = palette.brandInk)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (task.enabled) "ativa" else "pausada",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (task.enabled) palette.textFaint else palette.caution,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = task.enabled,
                    onCheckedChange = { onSave(task.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = palette.userBubbleText,
                        checkedTrackColor = palette.brand,
                        uncheckedThumbColor = palette.textFaint,
                        uncheckedTrackColor = palette.surfaceVariant,
                        uncheckedBorderColor = palette.divider,
                    ),
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = { onDelete(task.id) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Excluir ${task.title}",
                        tint = palette.danger,
                    )
                }
            }
        }
    }
}

/** Muted chip marking a task that the scheduler is currently skipping. */
@Composable
private fun PausedBadge() {
    val palette = TalosColors.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = palette.caution.copy(alpha = if (palette.isDark) 0.16f else 0.12f),
        contentColor = palette.caution,
    ) {
        Text(
            text = "pausada",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = palette.caution,
        )
    }
}

/** Explains the confirmation-free default before the user creates anything. */
@Composable
private fun UnattendedNotice() {
    val palette = TalosColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceVariant,
        contentColor = palette.textPrimary,
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 34.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.info),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Execuções sem ninguém olhando",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Uma tarefa agendada roda sozinha, então não há quem " +
                        "responda a um pedido de confirmação. Por padrão ela só usa " +
                        "ferramentas que não pedem confirmação — as de leitura e " +
                        "consulta. Se a tarefa precisar mexer no aparelho, você " +
                        "libera isso ao criá-la, ciente de que ela agirá sem avisar. " +
                        "Você recebe uma notificação com o resultado de cada " +
                        "execução.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Create dialog
// ---------------------------------------------------------------------------

/**
 * Drafts a new task in its own local state.
 *
 * Because [ScheduleScreen] only composes this while the dialog is open, the
 * `remember`ed draft is discarded on every close: reopening always starts blank.
 * Both text fields are required; the extra context is optional and is appended
 * to the prompt so the worker sees a single instruction string.
 */
@Composable
private fun CreateTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (ScheduledTask) -> Unit,
) {
    val palette = TalosColors.current

    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var context by remember { mutableStateOf("") }
    var intervalMinutes by remember { mutableStateOf(60) }
    var allowSensitiveTools by remember { mutableStateOf(false) }

    val canCreate = title.isNotBlank() && prompt.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceElevated,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textSecondary,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Nova tarefa",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "Título") },
                    placeholder = { Text(text = "Resumo da tarefa") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )

                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    label = { Text(text = "O que o agente deve fazer") },
                    placeholder = {
                        Text(text = "Ex.: resuma os commits do dia e liste o que ficou pendente.")
                    },
                    shape = RoundedCornerShape(12.dp),
                )

                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = context,
                    onValueChange = { context = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp),
                    label = { Text(text = "Contexto extra (opcional)") },
                    placeholder = { Text(text = "Repositório, pasta, tom da resposta…") },
                    shape = RoundedCornerShape(12.dp),
                )
                Text(
                    text = "Anexado ao pedido acima quando a tarefa rodar.",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textFaint,
                )

                SectionHeader(text = "Frequência")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IntervalPresets.forEach { preset ->
                        FilterChip(
                            selected = intervalMinutes == preset,
                            onClick = { intervalMinutes = preset },
                            label = { Text(text = intervalLabel(preset)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.brand.copy(
                                    alpha = if (palette.isDark) 0.26f else 0.14f,
                                ),
                                selectedLabelColor = palette.brandInk,
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Permitir ações que pedem confirmação",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textPrimary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Com isto ligado a tarefa pode modificar o " +
                                "aparelho, apagar arquivos ou executar comandos " +
                                "sozinha, sem pedir sua confirmação. Deixe desligado " +
                                "a menos que precise.",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.caution,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = allowSensitiveTools,
                        onCheckedChange = { allowSensitiveTools = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = palette.userBubbleText,
                            checkedTrackColor = palette.caution,
                            uncheckedThumbColor = palette.textFaint,
                            uncheckedTrackColor = palette.surfaceVariant,
                            uncheckedBorderColor = palette.divider,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        // `id`, `createdAt` and the last-run fields come from the
                        // model's own defaults, so a new task is born unscheduled
                        // and never-run without this screen inventing an identity.
                        ScheduledTask(
                            title = title.trim(),
                            prompt = buildPrompt(prompt, context),
                            intervalMinutes = intervalMinutes,
                            allowSensitiveTools = allowSensitiveTools,
                        ),
                    )
                },
                enabled = canCreate,
            ) {
                Text(text = "Criar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancelar", color = palette.textSecondary)
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Small building blocks
// ---------------------------------------------------------------------------

/** Small uppercase section label; matches the settings screen's rhythm. */
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

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

/**
 * Human-readable cadence for a stored minute interval.
 *
 * 15 -> "a cada 15 min", 60 -> "a cada hora", 1440 -> "uma vez por dia"; every
 * other value falls back to "a cada N horas" above an hour and "a cada N
 * minutos" below it, with the singular forms handled explicitly.
 */
private fun describeInterval(minutes: Int): String = when {
    minutes == 60 -> "a cada hora"
    minutes == 1440 -> "uma vez por dia"
    minutes == 1 -> "a cada minuto"
    minutes < 60 -> "a cada $minutes min"
    minutes % 60 == 0 -> "a cada ${minutes / 60} horas"
    minutes < 120 -> "a cada ${minutes / 60} hora e ${minutes % 60} min"
    else -> "a cada ${minutes / 60} h ${minutes % 60} min"
}

/** Relative age of a timestamp, coarse on purpose: minutes, hours, then days. */
private fun describeAgo(timestamp: Long, now: Long): String = when {
    timestamp <= 0L -> "nunca rodou"
    else -> {
        val elapsed = now - timestamp
        val minutes = elapsed / 60_000L
        when {
            elapsed < 60_000L -> "agora mesmo"
            minutes < 60L -> "há $minutes min"
            minutes < 1440L -> {
                val hours = minutes / 60L
                if (hours == 1L) "há 1 h" else "há $hours h"
            }
            else -> {
                val days = minutes / 1440L
                if (days == 1L) "há 1 dia" else "há $days dias"
            }
        }
    }
}

/** Chip caption for an interval preset, in the same vocabulary as the rows. */
private fun intervalLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes == 60 -> "1 h"
    minutes == 1440 -> "1 dia"
    else -> "${minutes / 60} h"
}

/** Appends the optional context to the instruction the worker will receive. */
private fun buildPrompt(prompt: String, context: String): String {
    val trimmedPrompt = prompt.trim()
    val trimmedContext = context.trim()
    return if (trimmedContext.isEmpty()) {
        trimmedPrompt
    } else {
        "$trimmedPrompt\n\nContexto adicional:\n$trimmedContext"
    }
}

/**
 * Failure statuses the worker writes start with "erro" or are exactly
 * "recusado"; anything else (including "ok") reads as a success.
 */
private fun isFailureStatus(status: String): Boolean {
    val normalized = status.trim().lowercase(Locale.getDefault())
    return normalized.startsWith("erro") || normalized == "recusado"
}
