@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.motion.browser.ui.control

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motion.browser.R
import com.motion.browser.ServiceLocator
import com.motion.browser.ai.core.LlmMessage
import com.motion.browser.ai.core.ProviderConfig
import com.motion.browser.ai.core.ProviderType
import com.motion.browser.ai.core.RoleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Per-type sensible default endpoint shown as a placeholder. */
private fun placeholderFor(type: ProviderType): String = when (type) {
    ProviderType.OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
    ProviderType.ANTHROPIC -> "https://api.anthropic.com/v1"
    ProviderType.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
    ProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
    ProviderType.OLLAMA -> "http://localhost:11434/v1"
    ProviderType.CUSTOM -> "https://your-endpoint.example/v1"
}

/**
 * §9 AI provider management: real CRUD on ProviderManager configs, role→model
 * mapping and honest per-provider connectivity tests. API keys only ever flow into
 * ProviderManager.saveConfig → SecretStore; they are never displayed or logged.
 */
@Composable
fun ProvidersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val providerManager = ServiceLocator.providerManager
    val configs by providerManager.configs.collectAsState()

    var dialogConfig by remember { mutableStateOf<ProviderConfig?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var configToDelete by remember { mutableStateOf<ProviderConfig?>(null) }
    var roleMenuFor by remember { mutableStateOf<RoleType?>(null) }
    val testState = remember { mutableStateMapOf<String, String>() }

    fun save(config: ProviderConfig, apiKey: String?) {
        scope.launch(Dispatchers.Default) {
            val error = runCatching { providerManager.saveConfig(config, apiKey) }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                snackbar.showSnackbar(
                    if (error == null) context.getString(R.string.action_save)
                    else context.getString(R.string.err_generic, error.message ?: "")
                )
            }
        }
    }

    fun test(configId: String) {
        testState[configId] = context.getString(R.string.providers_testing)
        scope.launch(Dispatchers.Default) {
            val response = runCatching {
                providerManager.chatWith(configId, listOf(LlmMessage(role = "user", content = "ping")))
            }.getOrNull()
            withContext(Dispatchers.Main) {
                testState[configId] = when {
                    response == null -> context.getString(R.string.providers_test_failed, "unexpected error")
                    response.ok -> context.getString(R.string.providers_test_ok)
                    else -> context.getString(R.string.providers_test_failed, response.error ?: "unknown")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.providers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.providers_add))
            }

            if (configs.isEmpty()) {
                Card {
                    Text(
                        stringResource(R.string.providers_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            } else {
                configs.forEach { config ->
                    ProviderCard(
                        config = config,
                        testResult = testState[config.id],
                        onTest = { test(config.id) },
                        onEdit = { dialogConfig = config },
                        onDelete = { configToDelete = config },
                    )
                }
            }

            // ---- Role → model mapping ----
            Text(
                stringResource(R.string.providers_role_mapping),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.providers_role_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RoleType.entries.forEach { role ->
                val currentId = providerManager.roleModel(role)
                val currentName = configs.firstOrNull { it.id == currentId }?.name
                    ?: stringResource(R.string.label_not_set)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(roleLabel(role), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    currentName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { roleMenuFor = role }) {
                                Text(stringResource(R.string.action_edit))
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = roleMenuFor == role,
                        onDismissRequest = { roleMenuFor = null },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.label_not_set)) },
                            onClick = {
                                roleMenuFor = null
                                scope.launch { runCatching { providerManager.setRoleModel(role, null) } }
                            },
                        )
                        configs.forEach { config ->
                            DropdownMenuItem(
                                text = { Text(config.name) },
                                onClick = {
                                    roleMenuFor = null
                                    scope.launch { runCatching { providerManager.setRoleModel(role, config.id) } }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- dialogs ------------------------------------------------------------------
    if (showAddDialog) {
        ProviderDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { config, key ->
                showAddDialog = false
                save(config, key)
            },
        )
    }
    dialogConfig?.let { config ->
        ProviderDialog(
            initial = config,
            onDismiss = { dialogConfig = null },
            onSave = { updated, key ->
                dialogConfig = null
                save(updated, key)
            },
        )
    }
    configToDelete?.let { config ->
        AlertDialog(
            onDismissRequest = { configToDelete = null },
            title = { Text(stringResource(R.string.providers_delete_title)) },
            text = { Text(stringResource(R.string.providers_delete_text, config.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val target = config
                    configToDelete = null
                    scope.launch { runCatching { providerManager.deleteConfig(target.id) } }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { configToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Provider card
// ---------------------------------------------------------------------------

@Composable
private fun ProviderCard(
    config: ProviderConfig,
    testResult: String?,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    config.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (config.enabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (config.enabled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        stringResource(R.string.providers_enabled),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                typeLabel(config.type) + " · " + config.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                config.model,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (config.keyId.isNotBlank()) {
                Text(
                    stringResource(R.string.providers_key_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            testResult?.let { result ->
                Text(result, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onTest) { Text(stringResource(R.string.providers_test)) }
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_edit))
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Add/edit dialog — the API key field never echoes the stored key
// ---------------------------------------------------------------------------

@Composable
private fun ProviderDialog(
    initial: ProviderConfig?,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig, String?) -> Unit,
) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(initial?.type ?: ProviderType.OPENAI_COMPATIBLE) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var typeMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.providers_add else R.string.providers_edit
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.providers_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = typeLabel(type),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.providers_type)) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { typeMenuOpen = true }
                    )
                    DropdownMenu(
                        expanded = typeMenuOpen,
                        onDismissRequest = { typeMenuOpen = false },
                    ) {
                        ProviderType.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(typeLabel(candidate)) },
                                onClick = {
                                    val previousPlaceholder = placeholderFor(type)
                                    type = candidate
                                    if (baseUrl.isBlank() || baseUrl == previousPlaceholder) {
                                        baseUrl = placeholderFor(candidate)
                                    }
                                    typeMenuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.providers_base_url)) },
                    placeholder = { Text(placeholderFor(type)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.providers_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.providers_api_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        Text(
                            stringResource(
                                if (initial == null) R.string.providers_key_hint_new
                                else R.string.providers_key_hint_edit
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.providers_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val base = initial
                val fallbackName = typeLabel(type)
                val config = (base ?: ProviderConfig(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    name = "",
                    baseUrl = "",
                    model = "",
                    enabled = true,
                    keyId = "key_" + UUID.randomUUID().toString(),
                )).copy(
                    type = type,
                    name = name.trim().ifBlank { fallbackName },
                    baseUrl = baseUrl.trim(),
                    model = model.trim(),
                    enabled = enabled,
                )
                onSave(config, apiKey.takeIf { it.isNotBlank() })
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------

@Composable
private fun typeLabel(type: ProviderType): String = when (type) {
    ProviderType.OPENAI_COMPATIBLE -> stringResource(R.string.type_openai_compatible)
    ProviderType.ANTHROPIC -> stringResource(R.string.type_anthropic)
    ProviderType.GEMINI -> stringResource(R.string.type_gemini)
    ProviderType.OPENROUTER -> stringResource(R.string.type_openrouter)
    ProviderType.OLLAMA -> stringResource(R.string.type_ollama)
    ProviderType.CUSTOM -> stringResource(R.string.type_custom)
}

@Composable
private fun roleLabel(role: RoleType): String = when (role) {
    RoleType.PLANNER -> stringResource(R.string.role_planner)
    RoleType.ACTION -> stringResource(R.string.role_action)
    RoleType.VISION -> stringResource(R.string.role_vision)
    RoleType.SUMMARIZATION -> stringResource(R.string.role_summarization)
    RoleType.BACKGROUND -> stringResource(R.string.role_background)
}
