package com.f2l.downloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

private fun fmtSize(b: Long): String {
    if (b <= 0) return ""
    val mb = b / 1048576.0
    return if (mb >= 1024) String.format(Locale.US, "~%.2f GB", mb / 1024) else String.format(Locale.US, "~%.0f MB", mb)
}

private fun fmtDuration(s: Long): String {
    if (s <= 0) return ""
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

/** Shown in the Add screen when the pasted link is a YouTube link: pick MP3 / video and its quality. */
@Composable
fun YouTubePanel(
    url: String,
    accent: Color,
    textPrimary: Color,
    textSecondary: Color,
    surface: Color,
    canAdd: Boolean,
    onAdd: (YtDlpEngine.Info, YtDlpEngine.Option) -> Unit
) {
    val context = LocalContext.current
    var info by remember(url) { mutableStateOf<YtDlpEngine.Info?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    var audioTab by remember(url) { mutableStateOf(false) }
    var selected by remember(url) { mutableIntStateOf(0) }

    LaunchedEffect(url, attempt) {
        loading = true; error = null
        runCatching { YtDlpEngine.fetchInfo(context, url) }
            .onSuccess { info = it }
            .onFailure { error = it.message?.lineSequence()?.firstOrNull { l -> l.isNotBlank() } ?: "Could not read this link" }
        loading = false
    }

    val thumb by produceState<Bitmap?>(null, info?.thumbnail) {
        val t = info?.thumbnail
        value = if (t == null) null else withContext(Dispatchers.IO) {
            runCatching {
                OkHttpClient().newCall(Request.Builder().url(t).build()).execute().use { r ->
                    r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }.getOrNull()
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("YouTube", color = accent, fontWeight = FontWeight.Bold)
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = accent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("Reading video… first time can take a few seconds", color = textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error ?: "", color = Color(0xFFE0554F), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                OutlinedButton(onClick = { attempt++ }) { Text("Retry") }
            }
            info != null -> {
                val i = info!!
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    thumb?.let {
                        Image(
                            it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(112.dp).height(64.dp).clip(RoundedCornerShape(10.dp))
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(i.title, color = textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(i.uploader, fmtDuration(i.durationSec).ifBlank { null }).joinToString(" • "),
                            color = textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !audioTab, onClick = { audioTab = false; selected = 0 }, label = { Text("Video") })
                    FilterChip(selected = audioTab, onClick = { audioTab = true; selected = 0 }, label = { Text("Audio / MP3") })
                }
                val options = if (audioTab) i.audio else i.video
                options.forEachIndexed { idx, o ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { selected = idx }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == idx, onClick = { selected = idx }, colors = RadioButtonDefaults.colors(selectedColor = accent))
                        Text(o.label, color = textPrimary, modifier = Modifier.weight(1f))
                        Text(fmtSize(o.approxBytes), color = textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = { options.getOrNull(selected)?.let { onAdd(i, it) } },
                    enabled = canAdd,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text(if (audioTab) "Download audio" else "Download video") }
                if (!canAdd) Text("Choose a download folder first", color = textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
