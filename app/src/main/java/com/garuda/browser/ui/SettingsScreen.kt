package com.garuda.browser.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.garuda.browser.ServiceLocator
import com.garuda.browser.agent.llm.Presets
import com.garuda.browser.agent.llm.ProviderDetector
import com.garuda.browser.data.ProviderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Settings (plan Prompt 7 §3): provider manager (preset/manual + auto-detect +
 * default + fallback chain), agent guardrails, battery-optimization onboarding.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val providers by ServiceLocator.database.providerDao().all().collectAsState(initial = emptyList())
    val settings by ServiceLocator.agentSettings.settings.collectAsState(
        initial = com.garuda.browser.data.AgentSettings()
    )
    var showAddDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LazyColumn(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("AI Providers", style = MaterialTheme.typography.titleMedium)
        }
        items(providers, key = { it.id }) { provider ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(provider.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${provider.protocol} · ${provider.model}\n${provider.baseUrl}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (provider.isDefault) {
                            Text("DEFAULT", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    provider.detectedInfo?.let {
                        Text(
                            it.take(160), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        TextButton(onClick = {
                            CoroutineScope(Dispatchers.IO).launch {
                                ServiceLocator.database.providerDao().setDefault(provider.id)
                            }
                        }) { Text("Set default") }

                        TextButton(onClick = {
                            CoroutineScope(Dispatchers.IO).launch {
                                val key = ServiceLocator.keyVault.getKey(provider.id)
                                val detection = runCatching {
                                    ProviderDetector.detect(provider.baseUrl, key)
                                }.getOrNull()
                                val info = detection?.let {
                                    "detected=${it.protocol} models=${it.models.size} ${it.latencyMs}ms ${it.note}"
                                } ?: "detect failed"
                                val vision = detection?.models?.any { m -> ProviderDetector.visionCapable(m) } ?: false
                                val updated = provider.copy(
                                    detectedInfo = info,
                                    visionCapable = vision,
                                    protocol = detection?.protocol ?: provider.protocol,
                                    model = detection?.models?.firstOrNull() ?: provider.model,
                                )
                                ServiceLocator.database.providerDao().update(updated)
                            }
                        }) { Text("Detect") }

                        IconButton(onClick = {
                            CoroutineScope(Dispatchers.IO).launch {
                                ServiceLocator.keyVault.deleteKey(provider.id)
                                ServiceLocator.database.providerDao().delete(provider.id)
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Delete provider") }
                    }
                }
            }
        }
        item {
            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add provider (preset or manual)")
            }
        }

        item { Text("Agent guardrails", style = MaterialTheme.typography.titleMedium) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    ToggleRow("Confirm risky actions (submit/pay/delete)") {
                        CoroutineScope(Dispatchers.IO).launch {
                            ServiceLocator.agentSettings.setRequireConfirmRisky(it)
                        }
                    }
                    ToggleRow("Background execution (foreground service)") {
                        CoroutineScope(Dispatchers.IO).launch {
                            ServiceLocator.agentSettings.setBackgroundEnabled(it)
                        }
                    }
                    Text(
                        "Rate limit: ${settings.minActionIntervalMs} ms between actions per domain · " +
                            "max steps: ${settings.maxStepsPerTask} · token budget: ${settings.tokenBudgetPerTask}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bolt, contentDescription = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Battery optimization", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Xiaomi/Oppo/Realme kill background agents — exempt Garuda for reliable automation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = {
                    runCatching {
                        val pm = context.getSystemService(PowerManager::class.java)
                        val intent = if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}"))
                        } else {
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        }
                        context.startActivity(intent)
                    }
                }) { Text("Disable optimization for Garuda") }
            }
        }
    }

    if (showAddDialog) {
        AddProviderDialog(onDismiss = { showAddDialog = false })
    }
}

@Composable
private fun ToggleRow(label: String, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(true) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { checked = it; onChange(it) })
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AddProviderDialog(onDismiss: () -> Unit) {
    val presets = Presets.all
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf<Presets.Preset?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add AI provider") },
        text = {
            Column {
                Text("Presets:", style = MaterialTheme.typography.labelSmall)
                androidx.compose.foundation.layout.FlowRow {
                    presets.forEach { preset ->
                        androidx.compose.material3.FilterChip(
                            selected = selectedPreset == preset,
                            onClick = {
                                selectedPreset = preset
                                name = preset.name
                                baseUrl = preset.baseUrl
                                model = preset.defaultModel
                            },
                            label = { Text(preset.name) },
                            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp),
                        )
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL (manual entry allowed)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = model, onValueChange = { model = it },
                    label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API key (stored encrypted)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && baseUrl.isNotBlank()) {
                    val id = "prov_${System.currentTimeMillis()}"
                    val protocol = selectedPreset?.protocol ?: "openai"
                    CoroutineScope(Dispatchers.IO).launch {
                        if (apiKey.isNotBlank()) ServiceLocator.keyVault.saveKey(id, apiKey)
                        ServiceLocator.database.providerDao().insert(
                            ProviderEntity(
                                id = id, name = name, protocol = protocol,
                                baseUrl = baseUrl.trim(), model = model.ifBlank { "default" },
                            )
                        )
                        val first = ServiceLocator.database.providerDao().allOnce()
                        if (first.size == 1) ServiceLocator.database.providerDao().setDefault(id)
                    }
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
