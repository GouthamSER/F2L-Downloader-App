@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.f2l.downloader

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Locale

// ---- palette matching the reference design (deep navy + blue accent) ----
private val BgDark = Color(0xFF0B0D12)
private val Surface1 = Color(0xFF121722)
private val Surface2 = Color(0xFF171D2B)
private val AccentBlue = Color(0xFF2F7BFF)
private val TextPrimary = Color(0xFFEFF2F7)
private val TextSecondary = Color(0xFF8B93A7)
private val GreenOk = Color(0xFF33C481)
private val AmberWarn = Color(0xFFF2A93B)
private val RedErr = Color(0xFFE0554F)

private val F2LColors = darkColorScheme(
    background = BgDark,
    surface = Surface1,
    surfaceVariant = Surface2,
    primary = AccentBlue,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    secondary = TextSecondary,
    error = RedErr
)

class MainActivity : ComponentActivity() {
    private val vm by viewModels<MainViewModel>()
    private var incomingUrl = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra("id", 0L)
            when (intent.action) {
                DownloadService.ACTION_PROGRESS -> {
                    if (intent.getBooleanExtra("paused", false)) {
                        vm.update(id) { it.copy(status = DownloadItem.Status.PAUSED) }
                    } else {
                        vm.applyProgress(
                            id,
                            intent.getLongExtra("downloaded", 0),
                            intent.getLongExtra("total", -1),
                            intent.getLongExtra("speed", 0)
                        )
                    }
                }
                DownloadService.ACTION_FINISHED ->
                    vm.update(id) { it.copy(status = DownloadItem.Status.COMPLETED, speedBytesPerSec = 0, etaSeconds = -1) }
                DownloadService.ACTION_FAILED ->
                    vm.update(id) { it.copy(status = DownloadItem.Status.FAILED, error = intent.getStringExtra("error")) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        }
        if (intent?.action == Intent.ACTION_SEND) incomingUrl = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        setContent {
            MaterialTheme(colorScheme = F2LColors) {
                F2LApp(vm, incomingUrl)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_PROGRESS)
            addAction(DownloadService.ACTION_FINISHED)
            addAction(DownloadService.ACTION_FAILED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }
}

private enum class Tab { HOME, DOWNLOADS, FILES, SETTINGS }
private enum class StatusFilter { ALL, ACTIVE, COMPLETED, FAILED }

@Composable
fun F2LApp(vm: MainViewModel, sharedUrl: String) {
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1800); showSplash = false }

    if (showSplash) {
        SplashScreen()
        return
    }

    val items by vm.items.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.HOME) }
    var showAddScreen by remember { mutableStateOf(sharedUrl.isNotBlank()) }
    var prefillUrl by remember { mutableStateOf(sharedUrl) }

    if (showAddScreen) {
        AddDownloadScreen(
            initialUrl = prefillUrl,
            onBack = { showAddScreen = false },
            onStart = { url, name, folder, connections, autoStart ->
                vm.add(url, folder, name, connections, autoStart)
                showAddScreen = false
                tab = Tab.HOME
            }
        )
        return
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(buildString {
                        append("F2L ")
                    })
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark, titleContentColor = TextPrimary),
                navigationIcon = { Icon(Icons.Default.Download, null, Modifier.padding(start = 12.dp), tint = AccentBlue) }
            )
        },
        floatingActionButton = {
            if (tab == Tab.HOME || tab == Tab.DOWNLOADS) {
                FloatingActionButton(onClick = { prefillUrl = ""; showAddScreen = true }, containerColor = AccentBlue) {
                    Icon(Icons.Default.Add, "Add download", tint = Color.White)
                }
            }
        },
        bottomBar = { BottomBar(tab) { tab = it } }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> DownloadListScreen(items, showFilters = true, filterDefault = StatusFilter.ALL, vm = vm)
                Tab.DOWNLOADS -> DownloadListScreen(items, showFilters = false, filterDefault = StatusFilter.ACTIVE, vm = vm)
                Tab.FILES -> FilesScreen(items)
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(
        Modifier.fillMaxSize().background(BgDark),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(Surface2),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Download, null, tint = AccentBlue, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("F2L Downloader", color = TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Fast · Reliable · Simple", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(color = AccentBlue, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text("Loading…", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Surface1) {
        val entries = listOf(
            Triple(Tab.HOME, Icons.Default.Home, "Home"),
            Triple(Tab.DOWNLOADS, Icons.Default.Download, "Downloads"),
            Triple(Tab.FILES, Icons.Default.Folder, "Files"),
            Triple(Tab.SETTINGS, Icons.Default.Settings, "Settings")
        )
        entries.forEach { (t, icon, label) ->
            NavigationBarItem(
                selected = current == t,
                onClick = { onSelect(t) },
                icon = { Icon(icon, label) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentBlue, selectedTextColor = AccentBlue,
                    unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary,
                    indicatorColor = Surface2
                )
            )
        }
    }
}

@Composable
private fun DownloadListScreen(
    items: List<DownloadItem>,
    showFilters: Boolean,
    filterDefault: StatusFilter,
    vm: MainViewModel
) {
    var filter by remember { mutableStateOf(filterDefault) }
    val filtered = items.filter {
        when (filter) {
            StatusFilter.ALL -> true
            StatusFilter.ACTIVE -> it.status == DownloadItem.Status.DOWNLOADING || it.status == DownloadItem.Status.PAUSED || it.status == DownloadItem.Status.QUEUED
            StatusFilter.COMPLETED -> it.status == DownloadItem.Status.COMPLETED
            StatusFilter.FAILED -> it.status == DownloadItem.Status.FAILED
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (showFilters) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusFilter.entries.forEach { f ->
                    val label = f.name.lowercase().replaceFirstChar { it.uppercase() }
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentBlue, selectedLabelColor = Color.White,
                            containerColor = Surface1, labelColor = TextSecondary
                        )
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No downloads here yet", color = TextSecondary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { item ->
                    DownloadCard(
                        item,
                        onPause = { vm.pause(item) },
                        onResume = { vm.start(item) },
                        onDelete = { vm.delete(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(item: DownloadItem, onPause: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit) {
    val progress = if (item.totalBytes > 0)
        (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else 0f

    val (statusColor, statusLabel) = when (item.status) {
        DownloadItem.Status.DOWNLOADING -> AccentBlue to "Downloading"
        DownloadItem.Status.PAUSED -> AmberWarn to "Paused"
        DownloadItem.Status.COMPLETED -> GreenOk to "Completed"
        DownloadItem.Status.FAILED -> RedErr to "Failed"
        DownloadItem.Status.QUEUED -> TextSecondary to "Queued"
        DownloadItem.Status.CANCELED -> TextSecondary to "Canceled"
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(statusColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(fileTypeIcon(item.fileName), null, tint = statusColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.fileName, color = TextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("${formatBytes(item.totalBytes)} · ${item.connections} threads", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = TextSecondary) }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                color = statusColor, trackColor = Surface2
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Text(statusLabel, color = statusColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            if (item.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(item.error!!, color = RedErr, style = MaterialTheme.typography.labelSmall)
            }
            when (item.status) {
                DownloadItem.Status.DOWNLOADING -> {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Pause, null, tint = TextPrimary); Spacer(Modifier.width(6.dp)); Text("Pause", color = TextPrimary)
                    }
                }
                DownloadItem.Status.PAUSED, DownloadItem.Status.FAILED -> {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onResume, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (item.status == DownloadItem.Status.FAILED) "Retry" else "Resume")
                    }
                }
                else -> {}
            }
        }
    }
}

private fun fileTypeIcon(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "mkv", "mov", "avi" -> Icons.Default.Movie
    "mp3", "wav", "flac" -> Icons.Default.MusicNote
    "jpg", "jpeg", "png", "webp" -> Icons.Default.Image
    "apk" -> Icons.Default.Android
    "zip", "rar", "7z", "iso" -> Icons.Default.FolderZip
    "pdf" -> Icons.Default.PictureAsPdf
    "exe" -> Icons.Default.Terminal
    else -> Icons.Default.InsertDriveFile
}

@Composable
private fun FilesScreen(items: List<DownloadItem>) {
    val completed = items.filter { it.status == DownloadItem.Status.COMPLETED }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Downloaded files", color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Browse files saved to your chosen folders", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        if (completed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing completed yet", color = TextSecondary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(completed, key = { it.id }) { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(fileTypeIcon(item.fileName), null, tint = GreenOk)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(item.fileName, color = TextPrimary)
                                Text(formatBytes(item.totalBytes), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddDownloadScreen(
    initialUrl: String,
    onBack: () -> Unit,
    onStart: (url: String, name: String, folder: Uri, connections: Int, autoStart: Boolean) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var fileName by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf<Uri?>(null) }
    var connections by remember { mutableIntStateOf(8) }
    var autoStart by remember { mutableStateOf(true) }
    var connectionsExpanded by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) folder = uri
    }

    val urlValid = url.startsWith("http://") || url.startsWith("https://")

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("Add download") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark, titleContentColor = TextPrimary),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            Text("URL", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("https://example.com/file.zip") }
            )
            Spacer(Modifier.height(16.dp))

            Text("File name (optional)", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = fileName, onValueChange = { fileName = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Detected automatically if left blank") }
            )
            Spacer(Modifier.height(16.dp))

            Text("Save to", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Folder, null, tint = TextPrimary)
                Spacer(Modifier.width(8.dp))
                Text(if (folder == null) "Choose folder" else "Folder selected", color = TextPrimary)
            }
            Spacer(Modifier.height(16.dp))

            Text("Connections", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Box {
                OutlinedButton(onClick = { connectionsExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("$connections threads" + if (connections == 8) " (recommended)" else "", color = TextPrimary, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, null, tint = TextPrimary)
                }
                DropdownMenu(expanded = connectionsExpanded, onDismissRequest = { connectionsExpanded = false }) {
                    listOf(2, 4, 6, 8, 16).forEach { n ->
                        DropdownMenuItem(text = { Text("$n threads" + if (n == 8) " (recommended)" else "") }, onClick = { connections = n; connectionsExpanded = false })
                    }
                }
            }
            Text("More connections = faster download (works best for large files)", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Start download immediately", color = TextPrimary)
                Switch(
                    checked = autoStart, onCheckedChange = { autoStart = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue)
                )
            }
            Spacer(Modifier.weight(1f))

            Button(
                onClick = { folder?.let { onStart(url.trim(), fileName, it, connections, autoStart) } },
                enabled = urlValid && folder != null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Text(if (autoStart) "Start download" else "Add to queue")
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    var wifiOnly by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(true) }
    var autoResume by remember { mutableStateOf(true) }
    var retryAttempts by remember { mutableIntStateOf(3) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", color = TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "Download settings") {
            SettingsRow(icon = Icons.Default.Speed, title = "Parallel connections", subtitle = "8 (recommended)")
            SettingsRow(icon = Icons.Default.Folder, title = "Download folder", subtitle = "/storage/emulated/0/Download")
            SettingsToggleRow(icon = Icons.Default.Wifi, title = "Wi-Fi only", subtitle = "Download only on Wi-Fi", checked = wifiOnly, onCheckedChange = { wifiOnly = it })
        }

        Spacer(Modifier.height(20.dp))
        SettingsSection(title = "General") {
            SettingsRow(icon = Icons.Default.Palette, title = "Appearance", subtitle = "Dark theme")
            SettingsRow(icon = Icons.Default.Language, title = "Language", subtitle = "English")
            SettingsToggleRow(icon = Icons.Default.Notifications, title = "Notifications", subtitle = "Enable download notifications", checked = notifications, onCheckedChange = { notifications = it })
        }

        Spacer(Modifier.height(20.dp))
        SettingsSection(title = "Advanced") {
            SettingsToggleRow(icon = Icons.Default.Autorenew, title = "Auto resume", subtitle = "Resume after app restart", checked = autoResume, onCheckedChange = { autoResume = it })
            SettingsRow(icon = Icons.Default.Replay, title = "Retry on failure", subtitle = "$retryAttempts attempts")
        }

        Spacer(Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("F2L Downloader", color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Version 1.0", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("Developed by", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Text("Goutham", color = TextPrimary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    "github.com/GouthamSER",
                    color = AccentBlue, textDecoration = TextDecoration.Underline,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/GouthamSER")))
                    }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, color = TextSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary)
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextSecondary)
    }
}

@Composable
private fun SettingsToggleRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary)
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue))
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "Unknown"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}
