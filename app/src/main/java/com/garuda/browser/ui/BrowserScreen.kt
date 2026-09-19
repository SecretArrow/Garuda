package com.garuda.browser.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.garuda.browser.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Browser shell (prototype phase): tab chips + omnibox + active WebView.
 * The agent layer attaches to this WebView through the DevTools socket.
 */
@Composable
fun BrowserScreen(modifier: Modifier = Modifier) {
    val engine = ServiceLocator.browser
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }

    if (engine.tabs.isEmpty()) {
        engine.createTab("about:blank")
    }
    val active = engine.activeTab ?: return

    Column(modifier.fillMaxSize()) {
        // Tab strip
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            engine.tabs.forEachIndexed { index, tab ->
                AssistChip(
                    onClick = { engine.switchTo(index); refresh++ },
                    label = { Text((tab.title.ifBlank { "Tab ${index + 1}" }).take(18)) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            IconButton(onClick = { engine.createTab("about:blank"); refresh++ }) {
                Icon(Icons.Filled.Add, contentDescription = "New tab")
            }
        }

        // Omnibox
        var urlDraft by remember(active.id, refresh) {
            mutableStateOf(if (active.webView.url == "about:blank") "" else active.webView.url.orEmpty())
        }
        OutlinedTextField(
            value = urlDraft,
            onValueChange = { urlDraft = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            placeholder = { Text("Search or enter address") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                val target = normalizeUrl(urlDraft)
                urlDraft = target
                active.webView.loadUrl(target)
                scope.launch(Dispatchers.Main) { refresh++ }
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            leadingIcon = {
                IconButton(onClick = { active.webView.goBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            trailingIcon = {
                Row {
                    IconButton(onClick = { active.webView.reload(); refresh++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    if (engine.tabs.size > 1) {
                        IconButton(onClick = {
                            val idx = engine.activeIndex
                            engine.closeTab(idx)
                            refresh++
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close tab")
                        }
                    }
                }
            },
        )

        // Active WebView (keyed by tab id so switching tabs swaps the view).
        key(active.id) {
            AndroidView(
                factory = { _ ->
                    active.webView.apply {
                        parent?.let { (it as ViewGroup).removeView(this) }
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

/** Chrome-like URL resolution: bare domains get https, other text searches DuckDuckGo. */
fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "about:blank"
    val looksLikeUrl = trimmed.startsWith("http") ||
        trimmed.matches(Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE))
    return if (looksLikeUrl) {
        if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
    } else {
        "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}
