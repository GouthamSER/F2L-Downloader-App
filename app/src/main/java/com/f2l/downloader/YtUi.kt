package com.f2l.downloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** Playlist link: list the videos, tick the ones wanted, pick one quality for all, queue them (2 at a time). */
@Composable
fun PlaylistPanel(
    url: String,
    accent: Color,
    textPrimary: Color,
    textSecondary: Color,
    surface: Color,
    onAdd: (List<YtDlpEngine.PlaylistEntry>, YtDlpEngine.Option) -> Unit
) {
    val context = LocalContext.current
    var info by remember(url) { mutableStateOf<YtDlpEngine.PlaylistInfo?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    val picked = remember(url) { mutableStateMapOf<String, Boolean>() }
    var preset by remember(url) { mutableIntStateOf(1) }

    val presets = remember {
        fun v(h: Int) = YtDlpEngine.Option("Video ${h}p", false, "bv*[height<=$h]+ba/b[height<=$h]", null, null, 0L)
        listOf(
            v(1080), v(720), v(480), v(360),
            YtDlpEngine.Option("MP3 320 kbps", true, null, "mp3", "320K", 0L),
            YtDlpEngine.Option("MP3 192 kbps", true, null, "mp3", "192K", 0L),
            YtDlpEngine.Option("M4A (no re-encode)", true, null, "m4a", null, 0L)
        )
    }

    LaunchedEffect(url, attempt) {
        loading = true; error = null
        runCatching { YtDlpEngine.fetchPlaylist(context, url) }
            .onSuccess { r -> info = r; picked.clear(); r.entries.forEach { picked[it.id] = true } }
            .onFailure { error = it.message?.lineSequence()?.firstOrNull { l -> l.isNotBlank() } ?: "Could not read this playlist" }
        loading = false
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("YouTube playlist", color = accent, fontWeight = FontWeight.Bold)
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = accent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("Reading playlist…", color = textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error ?: "", color = Color(0xFFE0554F), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                OutlinedButton(onClick = { attempt++ }) { Text("Retry") }
            }
            info != null -> {
                val pl = info!!
                val count = pl.entries.count { picked[it.id] == true }
                Text(pl.title, color = textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$count / ${pl.entries.size} selected", color = textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pl.entries.forEach { picked[it.id] = true } }) { Text("All") }
                    TextButton(onClick = { pl.entries.forEach { picked[it.id] = false } }) { Text("None") }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    pl.entries.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { picked[e.id] = !(picked[e.id] ?: false) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = picked[e.id] == true,
                                onCheckedChange = { picked[e.id] = it },
                                colors = CheckboxDefaults.colors(checkedColor = accent)
                            )
                            Text(e.title, color = textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (e.durationSec > 0) Text(fmtDuration(e.durationSec), color = textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("Quality for all", color = textSecondary, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEachIndexed { i, o ->
                        FilterChip(selected = preset == i, onClick = { preset = i }, label = { Text(o.label) })
                    }
                }
                Button(
                    onClick = { onAdd(pl.entries.filter { picked[it.id] == true }, presets[preset]) },
                    enabled = count > 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text("Download $count item${if (count == 1) "" else "s"}") }
                Text("Downloads run 2 at a time. The rest wait in the queue.", color = textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
