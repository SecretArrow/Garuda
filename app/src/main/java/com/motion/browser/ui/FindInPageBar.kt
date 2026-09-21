package com.motion.browser.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import android.webkit.WebView

/**
 * Chrome-like find-in-page bar: findAllAsync + findNext, match count in the
 * trailing area, Esc/Close clears matches.
 */
@Composable
fun FindInPageBar(
    webView: WebView,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (it.isBlank()) webView.clearMatches() else webView.findAllAsync(it)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                singleLine = true,
                placeholder = { Text("Find in page") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { webView.findNext(true) }),
            )
            IconButton(onClick = { webView.findNext(false) }) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Next match")
            }
            IconButton(onClick = { webView.findNext(true) }) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Previous match")
            }
            IconButton(onClick = {
                webView.clearMatches()
                onDismiss()
            }) {
                Icon(Icons.Filled.Close, contentDescription = "Close find bar")
            }
        }
    }
}
