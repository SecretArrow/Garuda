package com.motion.browser.ui.browser

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.motion.browser.ServiceLocator

/**
 * Real website file-upload support (spec §43): the engine's file-chooser requests
 * are bridged to the SAF document picker. The user always picks the file — the AI
 * never silently exposes local files (hard limitation, stated in UI text elsewhere).
 */
@Composable
fun FileChooserHost() {
    val manager = ServiceLocator.tabs ?: return

    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        manager.activeFileChooser?.complete(uris)
    }
    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        manager.activeFileChooser?.complete(listOfNotNull(uri))
    }

    DisposableEffect(manager) {
        manager.fileChooserLauncher = { request ->
            val mime = arrayOf("*/*")
            if (request.multiple) multiLauncher.launch(mime) else singleLauncher.launch(mime)
        }
        onDispose { manager.fileChooserLauncher = null }
    }
}
