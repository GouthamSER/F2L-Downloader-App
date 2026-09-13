@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.f2l.downloader

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// ---- palette matching the reference design (deep navy + blue accent) ----
// mutable so a theme-mode change (see applyPalette) can flip every screen at once via recreate()
private var BgDark = Color(0xFF0B0D12)
private var Surface1 = Color(0xFF121722)
private var Surface2 = Color(0xFF171D2B)
private val AccentBlue = Color(0xFF2F7BFF)
private var TextPrimary = Color(0xFFEFF2F7)
private var TextSecondary = Color(0xFF8B93A7)
private val GreenOk = Color(0xFF33C481)
private val AmberWarn = Color(0xFFF2A93B)
private val RedErr = Color(0xFFE0554F)
private var isLightMode = false

private fun applyPalette(mode: String) {
    isLightMode = mode == "light"
    if (isLightMode) {
        BgDark = Color(0xFFF5F6F9)
        Surface1 = Color(0xFFFFFFFF)
        Surface2 = Color(0xFFE9ECF2)
        TextPrimary = Color(0xFF10131A)
        TextSecondary = Color(0xFF5A6270)
    } else {
        BgDark = Color(0xFF0B0D12)
        Surface1 = Color(0xFF121722)
        Surface2 = Color(0xFF171D2B)
        TextPrimary = Color(0xFFEFF2F7)
        TextSecondary = Color(0xFF8B93A7)
    }
}

private fun colorScheme() = if (isLightMode) {
    lightColorScheme(
        background = BgDark, surface = Surface1, surfaceVariant = Surface2,
        primary = AccentBlue, onPrimary = Color.White,
        onBackground = TextPrimary, onSurface = TextPrimary,
        secondary = TextSecondary, error = RedErr
    )
} else {
    darkColorScheme(
        background = BgDark, surface = Surface1, surfaceVariant = Surface2,
        primary = AccentBlue, onPrimary = Color.White,
        onBackground = TextPrimary, onSurface = TextPrimary,
        secondary = TextSecondary, error = RedErr
    )
}

// ---- Liquid Glass v2.5: blurred glow background + frosted translucent surfaces ----

@Composable
private fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(BgDark)) {
        val blobAlpha = if (isLightMode) 0.35f else 0.55f
        Box(
            Modifier.size(260.dp).offset((-70).dp, (-60).dp).align(Alignment.TopStart)
                .graphicsLayer { alpha = blobAlpha; renderEffect = blurEffect(70f) }
                .background(Brush.radialGradient(listOf(Color(0xFF3B6BFF), Color.Transparent)), CircleShape)
        )
        Box(
            Modifier.size(230.dp).offset(70.dp, 260.dp).align(Alignment.TopEnd)
                .graphicsLayer { alpha = blobAlpha; renderEffect = blurEffect(70f) }
                .background(Brush.radialGradient(listOf(Color(0xFF7B5CFF), Color.Transparent)), CircleShape)
        )
        Box(
            Modifier.size(200.dp).offset((-40).dp, 60.dp).align(Alignment.BottomStart)
                .graphicsLayer { alpha = blobAlpha; renderEffect = blurEffect(70f) }
                .background(Brush.radialGradient(listOf(Color(0xFF24C78E), Color.Transparent)), CircleShape)
        )
        content()
    }
}

private fun blurEffect(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? =
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        android.graphics.RenderEffect
            .createBlurEffect(radiusPx, radiusPx, android.graphics.Shader.TileMode.DECAL)
            .asComposeRenderEffect()
    } else null

/** Frosted-glass surface: translucent tint + soft border + rounded corners. Layer glass panels over GlassBackground. */
private fun Modifier.glass(radius: androidx.compose.ui.unit.Dp = 20.dp): Modifier {
    val tint = if (isLightMode) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f)
    val border = if (isLightMode) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.18f)
    return this
        .clip(RoundedCornerShape(radius))
        .background(tint)
        .border(1.dp, border, RoundedCornerShape(radius))
}

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
                    vm.update(id) { it.copy(status = DownloadItem.Status.COMPLETED, downloadedBytes = it.totalBytes.coerceAtLeast(it.downloadedBytes), speedBytesPerSec = 0, etaSeconds = -1) }
                DownloadService.ACTION_FAILED ->
                    vm.update(id) { it.copy(status = DownloadItem.Status.FAILED, error = intent.getStringExtra("error")) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPalette(SettingsRepository(application).themeMode)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        }
        if (intent?.action == Intent.ACTION_SEND) incomingUrl = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        // Registered for the whole process lifetime (not just onStart/onStop) so progress/completion
        // from the background DownloadService still lands while the app is backgrounded, not just visible.
        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_PROGRESS)
            addAction(DownloadService.ACTION_FINISHED)
            addAction(DownloadService.ACTION_FAILED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            MaterialTheme(colorScheme = colorScheme()) {
                F2LApp(vm, incomingUrl)
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
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
        BackHandler { showAddScreen = false }
        AddDownloadScreen(
            initialUrl = prefillUrl,
            defaultConnections = vm.settings.defaultConnections,
            defaultFolder = vm.settings.defaultFolderUri?.let { runCatching { Uri.parse(it) }.getOrNull() },
            onBack = { showAddScreen = false },
            onStart = { url, name, folder, connections, autoStart ->
                vm.add(url, folder, name, connections, autoStart)
                showAddScreen = false
                tab = Tab.HOME
            }
        )
        return
    }

    var showExitDialog by remember { mutableStateOf(false) }
    val activeCount = items.count { it.status == DownloadItem.Status.DOWNLOADING || it.status == DownloadItem.Status.QUEUED }

    BackHandler {
        if (tab != Tab.HOME) tab = Tab.HOME else showExitDialog = true
    }

    if (showExitDialog) {
        val activity = (LocalContext.current as? android.app.Activity)
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit F2L Downloader?") },
            text = {
                Text(
                    if (activeCount > 0) "$activeCount download${if (activeCount > 1) "s" else ""} will keep running in the background."
                    else "Are you sure you want to exit?"
                )
            },
            confirmButton = { TextButton(onClick = { activity?.finish() }) { Text("Yes", color = RedErr) } },
            dismissButton = { TextButton(onClick = { showExitDialog = false }) { Text("No") } }
        )
    }

    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(buildString {
                            append("F2L ")
                        })
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = TextPrimary),
                    navigationIcon = { Icon(Icons.Default.Download, null, Modifier.padding(start = 12.dp), tint = AccentBlue) },
                    modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp).glass(radius = 18.dp)
                )
            },
            floatingActionButton = {
                if (tab == Tab.HOME || tab == Tab.DOWNLOADS) {
                    FloatingActionButton(
                        onClick = { prefillUrl = ""; showAddScreen = true },
                        containerColor = Color.Transparent,
                        modifier = Modifier.glass(radius = 20.dp)
                    ) {
                        Icon(Icons.Default.Add, "Add download", tint = AccentBlue)
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
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
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
            Image(
                painter = painterResource(R.drawable.f2l_logo),
                contentDescription = "F2L Downloader",
                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(28.dp))
            )
            Spacer(Modifier.height(20.dp))
            Text("F2L Downloader", color = TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.tagline), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(32.dp))
            LinearProgressIndicator(
                color = AccentBlue, trackColor = Surface2,
                modifier = Modifier.width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.loading), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(
        containerColor = Color.Transparent,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).glass(radius = 24.dp)
    ) {
        val entries = listOf(
            Triple(Tab.HOME, Icons.Default.Home, stringResource(R.string.nav_home)),
            Triple(Tab.DOWNLOADS, Icons.Default.Download, stringResource(R.string.nav_downloads)),
            Triple(Tab.FILES, Icons.Default.Folder, stringResource(R.string.nav_files)),
            Triple(Tab.SETTINGS, Icons.Default.Settings, stringResource(R.string.nav_settings))
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
                    indicatorColor = Color.White.copy(alpha = if (isLightMode) 0.4f else 0.12f)
                )
            )
        }
    }
}

private fun openDownloadedFile(context: android.content.Context, item: DownloadItem) {
    try {
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(item.folderUri))
        val file = tree?.findFile(item.fileName)
        if (file == null || !file.exists()) {
            android.widget.Toast.makeText(context, "File not found — it may have been moved or deleted", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val mime = context.contentResolver.getType(file.uri)
            ?: android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(item.fileName.substringAfterLast('.', "").lowercase())
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "No app found to open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun DownloadListScreen(
    items: List<DownloadItem>,
    showFilters: Boolean,
    filterDefault: StatusFilter,
    vm: MainViewModel
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(filterDefault) }
    val filtered = items.filter {
        when (filter) {
            StatusFilter.ALL -> true
            StatusFilter.ACTIVE -> it.status == DownloadItem.Status.DOWNLOADING || it.status == DownloadItem.Status.PAUSED || it.status == DownloadItem.Status.QUEUED
            StatusFilter.COMPLETED -> it.status == DownloadItem.Status.COMPLETED
            StatusFilter.FAILED -> it.status == DownloadItem.Status.FAILED
        }
    }

    var selected by remember { mutableStateOf<DownloadItem?>(null) }
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete download?") },
            text = { Text("\"${target.fileName}\" will be permanently deleted from your device. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target)
                    if (selected?.id == target.id) selected = null
                    pendingDelete = null
                }) { Text("Yes, delete", color = RedErr) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("No") }
            }
        )
    }

    selected?.let { sel ->
        val live = items.find { it.id == sel.id } ?: sel
        DownloadDetailScreen(
            live, onBack = { selected = null }, onPause = { vm.pause(live) }, onResume = { vm.start(live) },
            onDelete = { pendingDelete = live },
            onOpen = { openDownloadedFile(context, live) }
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (showFilters) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusFilter.entries.forEach { f ->
                    val label = when (f) {
                        StatusFilter.ALL -> stringResource(R.string.filter_all)
                        StatusFilter.ACTIVE -> stringResource(R.string.filter_active)
                        StatusFilter.COMPLETED -> stringResource(R.string.filter_completed)
                        StatusFilter.FAILED -> stringResource(R.string.filter_failed)
                    }
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
                Text(stringResource(R.string.no_downloads_yet), color = TextSecondary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { item ->
                    DownloadCard(
                        item,
                        onPause = { vm.pause(item) },
                        onResume = { vm.start(item) },
                        onDelete = { pendingDelete = item },
                        onClick = {
                            if (item.status == DownloadItem.Status.COMPLETED) openDownloadedFile(context, item)
                            else selected = item
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun F2LProgressBar(progress: Float, color: Color, height: androidx.compose.ui.unit.Dp = 10.dp) {
    val animated by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), label = "progress")
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Surface2)
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(animated.coerceIn(0.03f, 1f))
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.75f), color)))
        )
    }
}

@Composable
private fun DownloadCard(item: DownloadItem, onPause: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit, onClick: () -> Unit) {
    val progress = if (item.totalBytes > 0)
        (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else 0f

    val (statusColor, statusLabel) = when (item.status) {
        DownloadItem.Status.DOWNLOADING -> AccentBlue to stringResource(R.string.status_downloading)
        DownloadItem.Status.PAUSED -> AmberWarn to stringResource(R.string.status_paused)
        DownloadItem.Status.COMPLETED -> GreenOk to stringResource(R.string.status_completed)
        DownloadItem.Status.FAILED -> RedErr to stringResource(R.string.status_failed)
        DownloadItem.Status.QUEUED -> TextSecondary to stringResource(R.string.status_queued)
        DownloadItem.Status.CANCELED -> TextSecondary to stringResource(R.string.status_canceled)
    }

    Card(
        Modifier.fillMaxWidth().glass(radius = 18.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(18.dp)
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
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = TextSecondary) }
            }
            Spacer(Modifier.height(10.dp))
            F2LProgressBar(progress = progress, color = statusColor, height = 8.dp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Text(statusLabel, color = statusColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            if (item.status == DownloadItem.Status.DOWNLOADING) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (item.speedBytesPerSec > 0) "${formatBytes(item.speedBytesPerSec)}/s" else "Calculating speed…",
                        color = TextSecondary, style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        if (item.etaSeconds > 0) "ETA ${formatDuration(item.etaSeconds)}" else "",
                        color = TextSecondary, style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (item.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(item.error!!, color = RedErr, style = MaterialTheme.typography.labelSmall)
            }
            when (item.status) {
                DownloadItem.Status.DOWNLOADING -> {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Pause, null, tint = TextPrimary); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_pause), color = TextPrimary)
                    }
                }
                DownloadItem.Status.PAUSED, DownloadItem.Status.FAILED -> {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onResume, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (item.status == DownloadItem.Status.FAILED) stringResource(R.string.action_retry) else stringResource(R.string.action_resume))
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
    val context = LocalContext.current
    val completed = items.filter { it.status == DownloadItem.Status.COMPLETED }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.files_title), color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.files_subtitle), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        if (completed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.files_empty), color = TextSecondary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(completed, key = { it.id }) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().glass(radius = 16.dp).clickable { openDownloadedFile(context, item) }
                    ) {
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
    defaultConnections: Int = 8,
    defaultFolder: Uri? = null,
    onBack: () -> Unit,
    onStart: (url: String, name: String, folder: Uri, connections: Int, autoStart: Boolean) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var fileName by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(defaultFolder) }
    var connections by remember { mutableIntStateOf(defaultConnections) }
    var autoStart by remember { mutableStateOf(true) }
    var connectionsExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            folder = uri
        }
    }

    val urlValid = url.startsWith("http://") || url.startsWith("https://")

    GlassBackground {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_download_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = TextPrimary),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = TextPrimary) }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            Text(stringResource(R.string.label_url), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.hint_url)) }
            )
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.label_filename), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = fileName, onValueChange = { fileName = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.hint_filename)) }
            )
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.label_save_to), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Folder, null, tint = TextPrimary)
                Spacer(Modifier.width(8.dp))
                Text(if (folder == null) stringResource(R.string.action_choose_folder) else stringResource(R.string.folder_selected), color = TextPrimary)
            }
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.label_connections), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
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
            Text(stringResource(R.string.connections_hint), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.label_start_immediately), color = TextPrimary)
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
                Text(if (autoStart) stringResource(R.string.action_start_download) else stringResource(R.string.action_add_to_queue))
            }
        }
    }
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val settings = vm.settings

    var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
    var notifications by remember { mutableStateOf(settings.notifications) }
    var autoResume by remember { mutableStateOf(settings.autoResume) }
    var retryAttempts by remember { mutableIntStateOf(settings.retryAttempts) }
    var defaultConnections by remember { mutableIntStateOf(settings.defaultConnections) }
    var defaultFolder by remember { mutableStateOf(settings.defaultFolderUri) }

    var connectionsMenu by remember { mutableStateOf(false) }
    var retryMenu by remember { mutableStateOf(false) }
    var appearanceMenu by remember { mutableStateOf(false) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            settings.defaultFolderUri = uri.toString()
            defaultFolder = uri.toString()
        }
    }

    val folderLabel = defaultFolder?.let { Uri.parse(it).lastPathSegment ?: it } ?: "/storage/emulated/0/Download"

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.settings_title), color = TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        SettingsSection(title = stringResource(R.string.section_download_settings)) {
            Box {
                SettingsRow(
                    icon = Icons.Default.Speed, title = stringResource(R.string.row_parallel_connections),
                    subtitle = "$defaultConnections" + if (defaultConnections == 8) " (recommended)" else "",
                    onClick = { connectionsMenu = true }
                )
                DropdownMenu(expanded = connectionsMenu, onDismissRequest = { connectionsMenu = false }) {
                    listOf(2, 4, 6, 8, 16).forEach { n ->
                        DropdownMenuItem(text = { Text("$n threads" + if (n == 8) " (recommended)" else "") }, onClick = {
                            defaultConnections = n; settings.defaultConnections = n; connectionsMenu = false
                        })
                    }
                }
            }
            SettingsRow(icon = Icons.Default.Folder, title = stringResource(R.string.row_download_folder), subtitle = folderLabel, onClick = { folderPicker.launch(null) })
            SettingsToggleRow(icon = Icons.Default.Wifi, title = stringResource(R.string.row_wifi_only), subtitle = stringResource(R.string.row_wifi_only_desc), checked = wifiOnly, onCheckedChange = { wifiOnly = it; settings.wifiOnly = it })
        }

        Spacer(Modifier.height(20.dp))
        SettingsSection(title = stringResource(R.string.section_general)) {
            Box {
                SettingsRow(icon = Icons.Default.Palette, title = stringResource(R.string.row_appearance), subtitle = if (themeMode == "light") stringResource(R.string.theme_light) else stringResource(R.string.theme_dark), onClick = { appearanceMenu = true })
                DropdownMenu(expanded = appearanceMenu, onDismissRequest = { appearanceMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.theme_dark)) }, onClick = {
                        appearanceMenu = false
                        if (themeMode != "dark") { themeMode = "dark"; settings.themeMode = "dark"; (context as? android.app.Activity)?.recreate() }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.theme_light)) }, onClick = {
                        appearanceMenu = false
                        if (themeMode != "light") { themeMode = "light"; settings.themeMode = "light"; (context as? android.app.Activity)?.recreate() }
                    })
                }
            }
            SettingsToggleRow(icon = Icons.Default.Notifications, title = stringResource(R.string.row_notifications), subtitle = stringResource(R.string.row_notifications_desc), checked = notifications, onCheckedChange = { notifications = it; settings.notifications = it })
        }

        Spacer(Modifier.height(20.dp))
        SettingsSection(title = "Advanced") {
            SettingsToggleRow(icon = Icons.Default.Autorenew, title = stringResource(R.string.row_auto_resume), subtitle = stringResource(R.string.row_auto_resume_desc), checked = autoResume, onCheckedChange = { autoResume = it; settings.autoResume = it })
            Box {
                SettingsRow(icon = Icons.Default.Replay, title = stringResource(R.string.row_retry_on_failure), subtitle = stringResource(R.string.attempts_fmt, retryAttempts), onClick = { retryMenu = true })
                DropdownMenu(expanded = retryMenu, onDismissRequest = { retryMenu = false }) {
                    listOf(0, 1, 2, 3, 5).forEach { n ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.attempts_fmt, n)) }, onClick = {
                            retryAttempts = n; settings.retryAttempts = n; retryMenu = false
                        })
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth().glass(radius = 18.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("F2L Downloader", color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.dev_version), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.dev_developed_by), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Text("Goutham Josh", color = TextPrimary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
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
    Card(modifier = Modifier.fillMaxWidth().glass(radius = 18.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary)
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextSecondary)
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

@Composable
private fun DownloadDetailScreen(item: DownloadItem, onBack: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit, onOpen: () -> Unit) {
    BackHandler(onBack = onBack)
    val progress = if (item.totalBytes > 0) (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else 0f
    val (statusColor, statusLabel) = when (item.status) {
        DownloadItem.Status.DOWNLOADING -> AccentBlue to stringResource(R.string.status_downloading)
        DownloadItem.Status.PAUSED -> AmberWarn to stringResource(R.string.status_paused)
        DownloadItem.Status.COMPLETED -> GreenOk to stringResource(R.string.status_completed)
        DownloadItem.Status.FAILED -> RedErr to stringResource(R.string.status_failed)
        DownloadItem.Status.QUEUED -> TextSecondary to stringResource(R.string.status_queued)
        DownloadItem.Status.CANCELED -> TextSecondary to stringResource(R.string.status_canceled)
    }

    GlassBackground {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(item.fileName, maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = TextPrimary),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = TextPrimary) } },
                actions = { IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = RedErr) } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(
                Modifier.fillMaxWidth().height(150.dp).glass(radius = 20.dp)
                    .background(Brush.verticalGradient(listOf(statusColor.copy(alpha = 0.28f), Color.Transparent))),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(52.dp).glass(radius = 16.dp)
                            .background(statusColor.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(fileTypeIcon(item.fileName), null, tint = statusColor, modifier = Modifier.size(26.dp)) }
                    Spacer(Modifier.height(10.dp))
                    Text("${(progress * 100).toInt()}%", color = TextPrimary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(statusLabel, color = statusColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            F2LProgressBar(progress = progress, color = statusColor, height = 10.dp)
            Spacer(Modifier.height(20.dp))

            Card(modifier = Modifier.fillMaxWidth().glass(radius = 20.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    DetailRow(stringResource(R.string.detail_downloaded), "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}")
                    if (item.status == DownloadItem.Status.DOWNLOADING) {
                        DetailRow(stringResource(R.string.detail_speed), if (item.speedBytesPerSec > 0) "${formatBytes(item.speedBytesPerSec)}/s" else stringResource(R.string.calculating))
                        DetailRow(stringResource(R.string.detail_eta), if (item.etaSeconds > 0) formatDuration(item.etaSeconds) else stringResource(R.string.calculating))
                    }
                    DetailRow(stringResource(R.string.detail_connections), "${item.connections} threads")
                    DetailRow(stringResource(R.string.detail_filename), item.fileName)
                    DetailRow(stringResource(R.string.detail_save_folder), Uri.parse(item.folderUri).lastPathSegment ?: item.folderUri)
                    DetailRow(stringResource(R.string.detail_source_url), item.url, isLast = item.error == null)
                    if (item.error != null) DetailRow(stringResource(R.string.detail_error), item.error!!, RedErr, isLast = true)
                }
            }

            Spacer(Modifier.height(24.dp))
            when (item.status) {
                DownloadItem.Status.DOWNLOADING -> OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Pause, null, tint = TextPrimary); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_pause), color = TextPrimary)
                }
                DownloadItem.Status.PAUSED, DownloadItem.Status.FAILED -> Button(
                    onClick = onResume, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (item.status == DownloadItem.Status.FAILED) stringResource(R.string.action_retry) else stringResource(R.string.action_resume))
                }
                DownloadItem.Status.COMPLETED -> Button(
                    onClick = onOpen, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_open_file))
                }
                else -> {}
            }
        }
    }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = TextPrimary, isLast: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium)
    }
    if (!isLast) HorizontalDivider(color = Surface2)
}

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "Unknown"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}
