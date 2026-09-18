package com.motion.browser.ui.browser

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator

/**
 * Find-in-page bar (Chrome-style): query field, live match count, previous /
 * next navigation and close. Uses the engine's real findAllAsync/findNext.
 */
@Composable
fun FindInPageBar(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manager = ServiceLocator.tabs ?: return
    var query by remember { mutableStateOf("") }
    var matchCount by remember { mutableStateOf(0) }
    var activeOrdinal by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // Listen to the engine's find listener via evaluate? No — engine findInPage
    // returns counts on demand; we track the count from the last query run.
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text("Find in page") },
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (query.isNotBlank()) {
                            val engine = manager.engineForActive()
                            scope.launch {
                                matchCount = runCatching { engine?.findInPage(query) ?: 0 }.getOrDefault(0)
                                activeOrdinal = 1
                            }
                        }
                    }
                ),
            )
            Text(
                text = if (matchCount > 0) "${activeOrdinal.coerceAtMost(matchCount)}/$matchCount"
                else if (query.isBlank()) ""
                else "0/0",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(
                enabled = matchCount > 0,
                onClick = {
                    manager.engineForActive()?.findNext(false)
                    if (matchCount > 0) activeOrdinal = if (activeOrdinal <= 1) matchCount else activeOrdinal - 1
                },
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(
                enabled = matchCount > 0,
                onClick = {
                    manager.engineForActive()?.findNext(true)
                    if (matchCount > 0) activeOrdinal = if (activeOrdinal >= matchCount) 1 else activeOrdinal + 1
                },
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
            }
            IconButton(onClick = {
                manager.engineForActive()?.clearFind()
                onDismiss()
            }) {
                Icon(Icons.Filled.Close, contentDescription = "Close find bar")
            }
        }
    }
}
