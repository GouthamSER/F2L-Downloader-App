package com.f2l.downloader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(info: UpdateChecker.UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Update available") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Version ${info.version} is ready. You have ${BuildConfig.VERSION_NAME}.")
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("What's new", fontWeight = FontWeight.Bold)
                    Text(info.notes.take(1200), style = MaterialTheme.typography.bodySmall)
                }
                if (downloading) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text("Downloading… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    if (info.apkUrl == null) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl)))
                        onDismiss()
                        return@TextButton
                    }
                    error = null
                    downloading = true
                    progress = 0f
                    job = scope.launch {
                        try {
                            val apk = UpdateChecker.downloadApk(context, info) { done, total ->
                                if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                            }
                            downloading = false
                            if (UpdateChecker.install(context, apk)) {
                                onDismiss()
                            } else {
                                error = "Allow F2L to install apps on the page that just opened, come back, then tap Update again."
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            downloading = false
                            error = ErrorMessages.friendly(e)
                        }
                    }
                }
            ) { Text(if (info.apkUrl == null) "Open release page" else "Update now") }
        },
        dismissButton = {
            TextButton(onClick = { job?.cancel(); onDismiss() }) { Text(if (downloading) "Cancel" else "Later") }
        }
    )
}
