package com.talos.harness.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.talos.harness.R
import com.talos.harness.agent.ApprovalDecision
import com.talos.harness.agent.ApprovalRequest
import com.talos.harness.agent.Attachment
import com.talos.harness.agent.ChatMessage
import com.talos.harness.agent.MessageStatus
import com.talos.harness.agent.Risk
import com.talos.harness.agent.Role
import com.talos.harness.llm.WireUsage
import com.talos.harness.store.ConversationSummary
import com.talos.harness.ui.markdown.MarkdownText
import com.talos.harness.ui.theme.TalosColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApproval: (ApprovalDecision) -> Unit,
    onNewConversation: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
    onSharedDraftConsumed: () -> Unit,
    onAttach: (Attachment) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onVoiceInput: () -> Unit,
    onToggleConversation: () -> Unit,
    onStopVoice: () -> Unit,
    onVoiceDraftConsumed: () -> Unit,
    onToggleSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
    onOpenSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TalosColors.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }

    // Text handed over by another app lands in the composer, never sent blind.
    LaunchedEffect(state.sharedDraft) {
        state.sharedDraft?.let { incoming ->
            input = if (input.isBlank()) incoming else "$input\n\n$incoming"
            onSharedDraftConsumed()
        }
    }

    // Dictation replaces the composer: partial results arrive word by word and
    // must overwrite each other, otherwise the transcript would repeat itself.
    LaunchedEffect(state.voiceDraft) {
        state.voiceDraft?.let { transcript ->
            input = transcript
            onVoiceDraftConsumed()
        }
    }

    val messages = state.conversation.messages
    val lastContentLength = messages.lastOrNull()?.content?.length ?: 0

    // Follow the tail while the agent streams, but never fight the user.
    LaunchedEffect(messages.size, lastContentLength, state.isRunning) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.lastIndex) }
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            onDismissError()
        }
    }

    // ---- Media capture -------------------------------------------------------
    // The camera writes into our own cache dir and hands out a FileProvider URI,
    // because a bare file:// path would trip FileUriExposedException on modern
    // Android.
    val context = LocalContext.current
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    fun newCaptureUri(): Uri {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "hermes-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { picked: Uri? ->
        picked?.let {
            onAttach(
                Attachment(
                    uri = it.toString(),
                    mimeType = context.contentResolver.getType(it) ?: "image/jpeg",
                ),
            )
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved: Boolean ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (saved && uri != null) {
            onAttach(Attachment(uri = uri.toString(), mimeType = "image/jpeg", label = "câmera"))
        }
    }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            ConversationsDrawer(
                conversations = state.conversations,
                activeId = state.conversation.id,
                onSelect = { id ->
                    onOpenConversation(id)
                    scope.launch { drawerState.close() }
                },
                onDelete = onDeleteConversation,
                onNew = {
                    onNewConversation()
                    scope.launch { drawerState.close() }
                },
                onOpenSchedule = {
                    onOpenSchedule()
                    scope.launch { drawerState.close() }
                },
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            containerColor = palette.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = palette.background,
                        titleContentColor = palette.textPrimary,
                        navigationIconContentColor = palette.textSecondary,
                        actionIconContentColor = palette.textSecondary,
                    ),
                    title = {
                        Column {
                            Text(
                                text = state.conversation.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append(state.settings.model.ifBlank { "—" })
                                    if (state.delegatingToPc) append("  ·  no PC")
                                    if (state.onDevice) append("  ·  no celular")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.delegatingToPc || state.onDevice) {
                                    palette.brand
                                } else {
                                    palette.textFaint
                                },
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Conversas")
                        }
                    },
                    actions = {
                        IconButton(onClick = onToggleSpeak) {
                            Icon(
                                painter = painterResource(R.drawable.ic_volume),
                                contentDescription = if (state.speakReplies) {
                                    "Desligar leitura em voz alta"
                                } else {
                                    "Ler respostas em voz alta"
                                },
                                tint = if (state.speakReplies) palette.brand else palette.textSecondary,
                            )
                        }
                        IconButton(onClick = onNewConversation) {
                            Icon(Icons.Default.Add, contentDescription = "Nova conversa")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .imePadding(),
            ) {
                if (state.needsSetup) {
                    SetupBanner(delegatingToPc = state.delegatingToPc, onOpenSettings = onOpenSettings)
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        EmptyState(onPick = onSend)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(items = messages, key = { it.id }) { message ->
                                MessageRow(message = message)
                            }
                        }
                    }
                }

                // The on-device recommendation and .litertlm list now live under
                // Ajustes > Onde roda o modelo. The chat screen is a chat screen
                // again; the top bar's "· no celular" suffix still names the mode.
                state.usage?.let { UsageStrip(it) }

                InputBar(
                    value = input,
                    onValueChange = { input = it },
                    isRunning = state.isRunning,
                    enabled = !state.needsSetup,
                    attachments = state.draftAttachments,
                    visionWarning = state.draftAttachments.isNotEmpty() && !state.supportsVision,
                    isListening = state.isListening,
                    voiceAvailable = state.voiceAvailable,
                    conversationMode = state.conversationMode,
                    onToggleConversation = onToggleConversation,
                    onVoiceInput = onVoiceInput,
                    onStopVoice = onStopVoice,
                    onPickGallery = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPickCamera = {
                        val uri = newCaptureUri()
                        pendingCaptureUri = uri
                        cameraLauncher.launch(uri)
                    },
                    onRemoveAttachment = onRemoveAttachment,
                    onSend = {
                        val text = input
                        input = ""
                        onSend(text)
                    },
                    onStop = onStop,
                )
            }
        }
    }

    state.pendingApproval?.let { request ->
        ApprovalDialog(request = request, onDecision = onApproval)
    }
}

// ------------------------------------------------------------------- messages

@Composable
private fun MessageRow(message: ChatMessage) {
    when (message.role) {
        Role.USER -> UserBubble(message)
        Role.ASSISTANT -> AssistantBlock(message)
        Role.TOOL -> ToolCard(message)
        Role.SYSTEM -> Unit
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    val palette = TalosColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            if (message.attachments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = if (message.content.isBlank()) 0.dp else 6.dp),
                ) {
                    message.attachments.forEach { attachment ->
                        AttachmentThumb(attachment = attachment)
                    }
                }
            }
            if (message.content.isNotBlank()) {
                Surface(
                    color = palette.userBubble,
                    contentColor = palette.userBubbleText,
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 6.dp,
                    ),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.userBubbleText,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * A small preview of a staged or sent image.
 *
 * Decoded at 1/8 scale straight from the content resolver rather than through an
 * image-loading library: Talos needs a 64dp thumbnail, and a whole dependency
 * is not worth the megabytes or the version risk.
 */
@Composable
private fun AttachmentThumb(
    attachment: Attachment,
    onRemove: (() -> Unit)? = null,
) {
    val palette = TalosColors.current
    val context = LocalContext.current

    val thumbnail by produceState<ImageBitmap?>(initialValue = null, attachment.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(attachment.uri))?.use { stream ->
                    val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                    BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surfaceVariant)
            .border(1.dp, palette.divider, RoundedCornerShape(12.dp)),
    ) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = attachment.label ?: "imagem anexada",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(19.dp)
                    .clip(CircleShape)
                    .background(palette.background.copy(alpha = 0.78f))
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remover anexo",
                    tint = palette.textPrimary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantBlock(message: ChatMessage) {
    val palette = TalosColors.current
    var reasoningOpen by remember(message.id) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (message.reasoning.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { reasoningOpen = !reasoningOpen }
                    .padding(vertical = 2.dp),
            ) {
                Icon(
                    imageVector = if (reasoningOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = palette.textFaint,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (reasoningOpen) "Ocultar raciocínio" else "Ver raciocínio",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textFaint,
                )
            }
            if (reasoningOpen) {
                Surface(
                    color = palette.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Text(
                        text = message.reasoning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }
        }

        when {
            message.content.isNotBlank() -> MarkdownText(
                text = message.content,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
            )

            message.status == MessageStatus.STREAMING -> ThinkingRow()

            message.toolCalls.isNotEmpty() -> Unit
        }

        if (message.error != null) {
            Text(
                text = message.error,
                style = MaterialTheme.typography.bodySmall,
                color = palette.danger,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (message.toolCalls.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                message.toolCalls.forEach { call ->
                    Surface(
                        color = palette.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = call.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingRow() {
    val palette = TalosColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = palette.brand,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Trabalhando…",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textFaint,
        )
    }
}

@Composable
private fun ToolCard(message: ChatMessage) {
    val palette = TalosColors.current
    var expanded by remember(message.id) { mutableStateOf(false) }
    val failed = message.content.startsWith("ERRO") || message.content.contains("exit=") &&
        !message.content.contains("exit=0")

    Surface(
        color = palette.toolCard,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.toolCardBorder, RoundedCornerShape(12.dp)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.toolName ?: "tool",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (failed) palette.danger else palette.toolCardText,
                )
                Spacer(modifier = Modifier.weight(1f))
                message.durationMs?.let {
                    Text(
                        text = "${it}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textFaint,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (expanded) "ocultar" else "ver saída",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.brand,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }

            val preview = message.content.lineSequence().take(if (expanded) Int.MAX_VALUE else 3).joinToString("\n")
            Text(
                text = preview.trimEnd(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = palette.textSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = if (expanded) 420.dp else 72.dp)
                    .let { if (expanded) it.verticalScroll(rememberScrollState()) else it },
            )
        }
    }
}

@Composable
private fun UsageStrip(usage: WireUsage) {
    val palette = TalosColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "entrada ${usage.promptTokens}  ·  saída ${usage.completionTokens}  ·  total ${usage.totalTokens} tokens",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textFaint,
        )
    }
}

// ------------------------------------------------------------------- local mode
// The on-device status strip moved to Ajustes > "Onde roda o modelo": the chat
// surface no longer carries a diagnostic panel that is unrelated to the turn
// being read.

// ---------------------------------------------------------------------- input

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isRunning: Boolean,
    enabled: Boolean,
    attachments: List<Attachment>,
    visionWarning: Boolean,
    isListening: Boolean,
    voiceAvailable: Boolean,
    conversationMode: Boolean,
    onToggleConversation: () -> Unit,
    onVoiceInput: () -> Unit,
    onStopVoice: () -> Unit,
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val palette = TalosColors.current
    // An image alone is a legitimate message: "what is this?" is implied.
    val canSend = (value.isNotBlank() || attachments.isNotEmpty()) && enabled && !isRunning
    var attachMenuOpen by remember { mutableStateOf(false) }

    Surface(color = palette.background) {
        Column {
            HorizontalDivider(color = palette.divider)

            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEach { attachment ->
                        AttachmentThumb(
                            attachment = attachment,
                            onRemove = { onRemoveAttachment(attachment.uri) },
                        )
                    }
                }
            }

            if (visionWarning) {
                Text(
                    text = "Este modelo pode não aceitar imagens — troque para deepseek-flash em Ajustes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.caution,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }

            // Hands-free conversation lives on its own line above the field rather
            // than beside it. Inline it stole width from the text box, which is the
            // thing being typed into -- the control for talking must not shrink the
            // control for writing. A word, not an icon: no core icon says "keep
            // listening after answering".
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onToggleConversation,
                    enabled = enabled && voiceAvailable,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = if (conversationMode) "Conversa •" else "Conversa",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (conversationMode) palette.brand else palette.textSecondary,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box {
                    IconButton(
                        onClick = { if (isListening) onStopVoice() else onVoiceInput() },
                        enabled = enabled && voiceAvailable,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = if (isListening) "Parar de ouvir" else "Falar",
                            tint = if (isListening) palette.brand else palette.textSecondary,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { attachMenuOpen = true }, enabled = enabled) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Anexar imagem",
                            tint = palette.textSecondary,
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenuOpen,
                        onDismissRequest = { attachMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Galeria") },
                            onClick = {
                                attachMenuOpen = false
                                onPickGallery()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Câmera") },
                            onClick = {
                                attachMenuOpen = false
                                onPickCamera()
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    placeholder = { Text("Peça algo ao Talos…", color = palette.textFaint) },
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (isRunning) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Close, contentDescription = "Parar", tint = palette.danger)
                    }
                } else {
                    IconButton(onClick = onSend, enabled = canSend) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Enviar",
                            tint = if (canSend) palette.brand else palette.textFaint,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- empty state

@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    val palette = TalosColors.current
    val suggestions = listOf(
        "O que tem na minha área de trabalho?",
        "Quanta bateria ainda resta e o que está consumindo?",
        "Liste os apps instalados que começam com 'com.google'",
        "Crie um arquivo notas.md com um resumo do dia",
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Talos",
            style = MaterialTheme.typography.displaySmall,
            color = palette.textPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Um agente que roda aqui dentro, no seu celular.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textFaint,
        )
        Spacer(modifier = Modifier.height(28.dp))
        suggestions.forEach { suggestion ->
            Surface(
                color = palette.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onPick(suggestion) },
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun SetupBanner(delegatingToPc: Boolean, onOpenSettings: () -> Unit) {
    val palette = TalosColors.current
    Surface(color = palette.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (delegatingToPc) {
                    "Modo PC: falta a chave do gateway (API_SERVER_KEY)."
                } else {
                    "Falta a chave da API DeepSeek."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("Configurar", color = palette.brand) }
        }
    }
}

// ------------------------------------------------------------------- approval

@Composable
private fun ApprovalDialog(request: ApprovalRequest, onDecision: (ApprovalDecision) -> Unit) {
    val palette = TalosColors.current
    val accent = when (request.risk) {
        Risk.DANGEROUS -> palette.danger
        Risk.CAUTION -> palette.caution
        Risk.SAFE -> palette.success
    }
    // A remote gate advertises what it will accept; offering more would send a
    // choice the server rejects. Null means unrestricted (the on-device path).
    val offered = request.choices ?: ApprovalDecision.entries.toList()

    AlertDialog(
        onDismissRequest = {
            // Back/outside tap is a refusal — but only when refusal is allowed.
            if (ApprovalDecision.REJECT in offered) onDecision(ApprovalDecision.REJECT)
        },
        containerColor = palette.surfaceElevated,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textSecondary,
        title = {
            Text(
                text = "Permitir ${request.toolName}?",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                Surface(
                    color = palette.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, accent, RoundedCornerShape(8.dp)),
                ) {
                    Text(
                        text = request.preview,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = palette.textPrimary,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
                if (request.toolName == "shell") {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Ao aprovar, o comando poderá ler e alterar arquivos privados do Talos, incluindo memória, skills, conversas e configurações.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.danger,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "O agente fica parado esperando sua resposta.",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textFaint,
                )
            }
        },
        confirmButton = {
            if (ApprovalDecision.APPROVE_ONCE in offered) {
                TextButton(onClick = { onDecision(ApprovalDecision.APPROVE_ONCE) }) {
                    Text("Permitir", color = palette.brand)
                }
            }
        },
        dismissButton = {
            Row {
                if (ApprovalDecision.REJECT in offered) {
                    TextButton(onClick = { onDecision(ApprovalDecision.REJECT) }) {
                        Text("Recusar", color = palette.danger)
                    }
                }
                if (ApprovalDecision.APPROVE_ALWAYS in offered) {
                    TextButton(onClick = { onDecision(ApprovalDecision.APPROVE_ALWAYS) }) {
                        Text("Sempre", color = palette.textSecondary)
                    }
                }
            }
        },
    )
}

// -------------------------------------------------------------------- drawer

/**
 * The conversation drawer: new conversation, scheduled tasks, and the list.
 *
 * The three mode [FilterChip]s that used to sit here moved to
 * Ajustes > "Onde roda o modelo". They were a persisted configuration choice
 * standing next to conversation actions, which made them read as if they only
 * affected the open chat; Settings is where a preference belongs, and it is also
 * the only place with room for the tradeoff each option carries.
 */
@Composable
private fun ConversationsDrawer(
    conversations: List<ConversationSummary>,
    activeId: String,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onOpenSchedule: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = TalosColors.current
    val formatter = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    ModalDrawerSheet(
        drawerContainerColor = palette.surface,
        drawerContentColor = palette.textPrimary,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Conversas",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Fechar", tint = palette.textSecondary)
            }
        }

        TextButton(
            onClick = onNew,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = palette.brand, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Nova conversa", color = palette.brand)
        }

        TextButton(
            onClick = onOpenSchedule,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text("Tarefas agendadas", color = palette.textSecondary)
        }

        HorizontalDivider(color = palette.divider, modifier = Modifier.padding(vertical = 8.dp))

        if (conversations.isEmpty()) {
            Text(
                text = "Nenhuma conversa salva ainda.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textFaint,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn {
                items(items = conversations, key = { it.id }) { summary ->
                    val active = summary.id == activeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(summary.id) }
                            .background(if (active) palette.surfaceVariant else Color.Transparent)
                            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = summary.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (active) palette.textPrimary else palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${summary.messageCount} mensagens · ${formatter.format(Date(summary.updatedAt))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textFaint,
                            )
                        }
                        IconButton(onClick = { onDelete(summary.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Apagar",
                                tint = palette.textFaint,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
