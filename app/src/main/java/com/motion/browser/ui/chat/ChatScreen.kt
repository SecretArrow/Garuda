package com.motion.browser.ui.chat

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import com.motion.browser.agent.runtime.RuntimeStatus
import com.motion.browser.ui.control.EmptyState
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen AI Chat (spec §12.12) — persistent conversations (Room),
 * Markdown answers with copyable code blocks, page-context questions,
 * voice input, TTS playback, regenerate/stop/edit, session management
 * (rename/pin/delete/search) — all Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit = {},
    initialText: String? = null,
) {
    val repo = remember { ChatRepository() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var sessionId by remember { mutableStateOf(repo.newSession()) }
    var input by remember { mutableStateOf(initialText ?: "") }
    var generating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var usePageContext by remember { mutableStateOf(false) }
    var pageLabel by remember { mutableStateOf<String?>(null) }
    var sessionSearch by remember { mutableStateOf("") }
    var editMessageId by remember { mutableStateOf<String?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }

    val messages by repo.messages(sessionId).collectAsState(initial = emptyList())
    val runtimeStatus by ServiceLocator.agentRuntime.status.collectAsState()
    val listState = rememberLazyListState()

    // ---- TTS setup/teardown
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        tts = engine
        onDispose { engine.shutdown() }
    }
    fun speak(text: String) {
        if (!ttsReady) return
        tts?.language = Locale.getDefault()
        tts?.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, "motion_tts")
    }

    // ---- Voice input
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = input + (if (input.isBlank()) "" else " ") + spoken
    }

    // ---- Consumed intake text (share-to-AI / process-text)
    LaunchedEffect(initialText) { if (!initialText.isNullOrBlank()) input = initialText ?: "" }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // Refresh the page-context chip label whenever the active page changes.
    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                val obs = ServiceLocator.browser?.observePage()
                pageLabel = obs?.takeIf { it.url.startsWith("http") }?.title?.take(48)
            }
            kotlinx.coroutines.delay(4000)
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || generating) return
        errorText = null
        input = ""
        editMessageId?.let { id ->
            repo.editUserMessage(messages.firstOrNull { it.id == id } ?: return@let)
            editMessageId = null
        }
        generating = true
        val pageCtx = if (usePageContext) {
            runCatching { ServiceLocator.browser?.observePage() }.getOrNull()
        } else null
        repo.generate(sessionId, text, pageCtx) { err ->
            generating = false
            errorText = err
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet {
            SessionDrawer(
                repo = repo,
                activeId = sessionId,
                search = sessionSearch,
                onSearch = { sessionSearch = it },
                onSelect = { sessionId = it; scope.launch { drawerState.close() } },
                onNew = { sessionId = repo.newSession(); scope.launch { drawerState.close() } },
            )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Motion AI", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { sessionId = repo.newSession() }) {
                            Icon(Icons.Filled.Add, contentDescription = "New chat")
                        }
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Chat history")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                // ---- Runtime banner (approvals + status, compact)
                RuntimeBanner()

                // ---- Page context chip
                if (pageLabel != null) {
                    Row(
                        Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(
                            onClick = { usePageContext = !usePageContext },
                            label = {
                                Text(
                                    (if (usePageContext) "✓ " else "") + "Page: $pageLabel",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.Translate, null, Modifier.size(16.dp)) },
                        )
                    }
                }

                // ---- Transcript
                if (messages.isEmpty() && !generating) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = "Ask Motion anything",
                            subtitle = "Chat privately with your configured AI provider. " +
                                "Enable Page context to ground answers in the current web page.",
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            ChatBubble(
                                role = msg.role,
                                content = msg.content,
                                onListen = { speak(msg.content) },
                                canRegenerate = msg.role == "assistant" && msg == messages.lastOrNull() && !generating,
                                canEdit = msg.role == "user",
                                onRegenerateClick = {
                                    repo.removeLastAssistantMessage(sessionId)
                                    val lastUser = messages.lastOrNull { it.role == "user" }
                                    if (lastUser != null) {
                                        generating = true
                                        val pageCtx = if (usePageContext) {
                                            runCatching { ServiceLocator.browser?.observePage() }.getOrNull()
                                        } else null
                                        repo.generate(sessionId, lastUser.content, pageCtx) { err ->
                                            generating = false
                                            errorText = err
                                        }
                                    }
                                },
                                onEditClick = {
                                    editMessageId = msg.id
                                    input = msg.content
                                },
                                onOpenLink = onOpenLink,
                            )
                        }
                        if (generating) {
                            item(key = "typing") {
                                TypingIndicator()
                            }
                        }
                        if (errorText != null) {
                            item(key = "error") {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Row(
                                        Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "Request failed: $errorText",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = {
                                            errorText = null
                                            val lastUser = messages.lastOrNull { it.role == "user" }
                                            if (lastUser != null) {
                                                generating = true
                                                repo.generate(sessionId, lastUser.content, null) { e ->
                                                    generating = false
                                                    errorText = e
                                                }
                                            }
                                        }) { Text("Retry") }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- Input row
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        }
                        runCatching { voiceLauncher.launch(intent) }
                            .onFailure { Toast.makeText(context, "Voice input unavailable", Toast.LENGTH_SHORT).show() }
                    }) {
                        Icon(Icons.Filled.KeyboardVoice, contentDescription = "Voice input")
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message Motion AI…") },
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    )
                    FilledIconButton(
                        onClick = {
                            if (generating) {
                                repo.stopGeneration()
                                generating = false
                            } else {
                                send()
                            }
                        },
                        enabled = input.isNotBlank() || generating,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (generating) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            contentColor = if (generating) MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            if (generating) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (generating) "Stop generation" else "Send",
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ bubbles

@Composable
private fun ChatBubble(
    role: String,
    content: String,
    onListen: () -> Unit,
    canRegenerate: Boolean,
    canEdit: Boolean,
    onRegenerateClick: () -> Unit,
    onEditClick: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val context = LocalContext.current
    when (role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(content, style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = onEditClick,
                        enabled = canEdit,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        ),
                    ) { Text("Edit", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        else -> Row(Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    if (content.isBlank()) {
                        Text(
                            "(empty response)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        MarkdownText(content, Modifier.padding(4.dp), onOpenLink)
                    }
                    Row {
                        TextButton(onClick = {
                            runCatching {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("Motion", content)
                                )
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Copy", style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = onListen) {
                            Text("Listen", style = MaterialTheme.typography.labelSmall)
                        }
                        if (canRegenerate) {
                            TextButton(onClick = onRegenerateClick) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp))
                                Text("  Regenerate", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Motion is thinking…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact agent status + approval strip (real runtime, real ApprovalQueue). */
@Composable
private fun RuntimeBanner() {
    val status by ServiceLocator.agentRuntime.status.collectAsState()
    val pending by remember { ServiceLocator.approvalQueue.pending() }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val approval = pending.firstOrNull()
    if (status is RuntimeStatus.Idle && approval == null) return
    Surface(
        color = when {
            approval != null -> MaterialTheme.colorScheme.tertiaryContainer
            status is RuntimeStatus.Running -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = when {
            approval != null -> MaterialTheme.colorScheme.onTertiaryContainer
            status is RuntimeStatus.Running -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SmartToy, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    approval != null -> "Approval needed: ${approval.domain} · ${approval.action}"
                    status is RuntimeStatus.Running -> "Agent working — step ${(status as RuntimeStatus.Running).step}"
                    status is RuntimeStatus.WaitingApproval -> "Waiting for approval"
                    status is RuntimeStatus.Paused -> "Agent paused: ${(status as RuntimeStatus.Paused).reason}"
                    else -> "Agent idle"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (approval != null) {
                TextButton(onClick = {
                    scope.launch { runCatching { ServiceLocator.approvalQueue.resolve(approval.id, false) } }
                }) { Text("Deny") }
                TextButton(onClick = {
                    scope.launch { runCatching { ServiceLocator.approvalQueue.resolve(approval.id, true) } }
                }) { Text("Allow") }
            }
            if (status !is RuntimeStatus.Idle) {
                TextButton(onClick = { runCatching { ServiceLocator.agentRuntime.stopAll() } }) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(14.dp))
                    Text("  Stop")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ sessions drawer

@Composable
private fun SessionDrawer(
    repo: ChatRepository,
    activeId: String,
    search: String,
    onSearch: (String) -> Unit,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
) {
    val sessions by repo.sessions().collectAsState(initial = emptyList())
    val filtered = if (search.isBlank()) sessions else sessions.filter {
        it.title.contains(search, ignoreCase = true)
    }
    var renameTarget by remember { mutableStateOf<com.motion.browser.data.entity.ChatSessionEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<com.motion.browser.data.entity.ChatSessionEntity?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search chats") },
        )
        Spacer(Modifier.height(8.dp))
        FilledIconButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("New chat")
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { session ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(session.id) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PushPin.takeIf { session.pinned } ?: Icons.Outlined.SmartToy,
                        null,
                        Modifier.size(16.dp),
                        tint = if (session.id == activeId) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    ) {
                        Text(
                            session.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (session.id == activeId) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { renameTarget = session }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename", Modifier.size(16.dp))
                    }
                    IconButton(onClick = { repo.setPinned(session.id, !session.pinned) }) {
                        Icon(
                            Icons.Filled.PushPin, contentDescription = "Pin/unpin",
                            Modifier.size(16.dp),
                            tint = if (session.pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { deleteTarget = session }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        var name by remember { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.renameSession(target.id, name)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete chat?") },
            text = { Text("“${target.title}” and all its messages will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    repo.deleteSession(target.id)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}
