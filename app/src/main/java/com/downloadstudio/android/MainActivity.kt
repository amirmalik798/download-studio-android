package com.downloadstudio.android

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.downloadstudio.android.data.DownloadItem
import com.downloadstudio.android.data.DownloadMode
import com.downloadstudio.android.data.DownloadStatus
import com.downloadstudio.android.data.QueueStore
import com.downloadstudio.android.engine.DownloadCoordinator
import com.downloadstudio.android.engine.TermuxBridge
import com.downloadstudio.android.service.OverlayService
import com.downloadstudio.android.util.StorageAccess
import kotlinx.coroutines.delay
import java.util.Locale

private val Ink = Color(0xFF090E19)
private val Panel = Color(0xFF111B2D)
private val Panel2 = Color(0xFF17243A)
private val Line = Color(0xFF26364F)
private val TextMain = Color(0xFFF3F7FF)
private val TextMuted = Color(0xFF91A2BC)
private val Blue = Color(0xFF4D8DFF)
private val Cyan = Color(0xFF5BE0F5)
private val Green = Color(0xFF36D6A0)
private val Violet = Color(0xFFA78BFA)
private val Red = Color(0xFFFF7185)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 14, 25)
        window.navigationBarColor = android.graphics.Color.rgb(9, 14, 25)
        DownloadCoordinator.initialize(this)
        handleIncoming(intent)
        setContent {
            val scheme = darkColorScheme(
                primary = Blue, secondary = Cyan, tertiary = Violet,
                background = Ink, surface = Panel, surfaceVariant = Panel2,
                onPrimary = Color.White, onBackground = TextMain, onSurface = TextMain,
                onSurfaceVariant = TextMuted, outline = Line
            )
            MaterialTheme(colorScheme = scheme) {
                DownloadStudioRoot(
                    onIncomingUrl = { url, mode ->
                        runCatching { DownloadCoordinator.enqueue(url, mode, "Shared media") }
                            .onFailure { Toast.makeText(this, it.message ?: "Could not add link", Toast.LENGTH_LONG).show() }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        // Permission settings are external activities. Start the user-enabled overlay
        // automatically when the user returns after granting both required permissions.
        if (QueueStore.floatingEnabled(this) && Settings.canDrawOverlays(this) && hasUsageAccess(this)) {
            startOverlayService(this)
        }
    }

    private fun handleIncoming(intent: Intent?) {
        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val url = Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE).find(raw)?.value?.trimEnd('.', ',', ')', ']') ?: return
        val mode = QueueStore.pendingMode(this)
        if (mode != null) {
            QueueStore.setPendingMode(this, null)
            runCatching { DownloadCoordinator.enqueue(url, mode, "YouTube share") }
                .onFailure { Toast.makeText(this, it.message ?: "Could not add link", Toast.LENGTH_LONG).show() }
            Toast.makeText(this, if (mode == DownloadMode.VIDEO) "Video added to queue" else "Audio added to queue", Toast.LENGTH_SHORT).show()
        } else {
            pendingSharedUrl = url
        }
    }

    companion object {
        var pendingSharedUrl by mutableStateOf<String?>(null)
    }
}

private enum class AppTab { HOME, QUEUE, LIBRARY, SETTINGS }

@Composable
private fun DownloadStudioRoot(onIncomingUrl: (String, DownloadMode) -> Unit) {
    val context = LocalContext.current
    val items by DownloadCoordinator.items.collectAsState()
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var url by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(DownloadMode.VIDEO) }
    var selected by remember { mutableStateOf<DownloadItem?>(null) }
    var shareUrl by remember { mutableStateOf<String?>(null) }
    val incoming = MainActivity.pendingSharedUrl
    LaunchedEffect(incoming) {
        if (!incoming.isNullOrBlank()) {
            shareUrl = incoming
            MainActivity.pendingSharedUrl = null
        }
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D1524), tonalElevation = 0.dp) {
                NavigationBarItem(selected = tab == AppTab.HOME, onClick = { tab = AppTab.HOME }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(selected = tab == AppTab.QUEUE, onClick = { tab = AppTab.QUEUE }, icon = { Icon(Icons.Default.Downloading, null) }, label = { Text("Queue") })
                NavigationBarItem(selected = tab == AppTab.LIBRARY, onClick = { tab = AppTab.LIBRARY }, icon = { Icon(Icons.Default.VideoLibrary, null) }, label = { Text("Library") })
                NavigationBarItem(selected = tab == AppTab.SETTINGS, onClick = { tab = AppTab.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().background(Ink).padding(padding)) {
            TopHeader(tab) { tab = AppTab.SETTINGS }
            when (tab) {
                AppTab.HOME -> HomeScreen(
                    items = items, url = url, onUrl = { url = it }, mode = mode,
                    onMode = { mode = it }, onAdd = {
                        val clean = url.trim()
                        if (clean.startsWith("http://") || clean.startsWith("https://")) {
                            onIncomingUrl(clean, mode); url = ""; tab = AppTab.QUEUE
                        } else Toast.makeText(context, "Enter a valid HTTP or HTTPS link", Toast.LENGTH_SHORT).show()
                    }, onOpenItem = { selected = it }, onQueue = { tab = AppTab.QUEUE }
                )
                AppTab.QUEUE -> QueueScreen(items, onOpenItem = { selected = it })
                AppTab.LIBRARY -> LibraryScreen(items.filter { it.status == DownloadStatus.COMPLETED }, onOpenItem = { selected = it })
                AppTab.SETTINGS -> SettingsScreen()
            }
        }
    }

    selected?.let { item ->
        DownloadDetailsDialog(item, onDismiss = { selected = null })
    }
    shareUrl?.let { shared ->
        AlertDialog(
            onDismissRequest = { shareUrl = null },
            containerColor = Panel,
            title = { Text("Add shared link", color = TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(shared, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Choose what to download", color = TextMain, fontWeight = FontWeight.SemiBold)
                    ModeSelector(mode, onMode = { mode = it })
                }
            },
            confirmButton = {
                TextButton(onClick = { onIncomingUrl(shared, mode); shareUrl = null; tab = AppTab.QUEUE }) { Text("Add to queue") }
            },
            dismissButton = { TextButton(onClick = { shareUrl = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TopHeader(tab: AppTab, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink).padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Blue, Violet))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Download, null, tint = Color.White)
            }
            Column {
                Text("DOWNLOAD STUDIO", color = TextMain, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, letterSpacing = 1.1.sp)
                Text("LOCAL MEDIA DOWNLOADER", color = TextMuted, fontSize = 9.sp, letterSpacing = 1.4.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.clip(CircleShape).background(Color(0xFF133A35)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("ON DEVICE", color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Tune, "Settings", tint = TextMuted) }
        }
    }
    HorizontalDivider(color = Line.copy(alpha = .55f))
}

@Composable
private fun HomeScreen(
    items: List<DownloadItem>, url: String, onUrl: (String) -> Unit,
    mode: DownloadMode, onMode: (DownloadMode) -> Unit,
    onAdd: () -> Unit, onOpenItem: (DownloadItem) -> Unit, onQueue: () -> Unit
) {
    val active = items.count { it.status in setOf(DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING) }
    val completed = items.count { it.status == DownloadStatus.COMPLETED }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Your media,\nbeautifully managed.", color = TextMain, fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.ExtraBold)
                Text("Download videos and audio directly to your phone.", color = TextMuted, fontSize = 13.sp)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("QUICK DOWNLOAD", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.Link, null, tint = TextMuted)
                    }
                    OutlinedTextField(
                        value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste a video or media URL", color = TextMuted) },
                        leadingIcon = { Icon(Icons.Default.Language, null, tint = TextMuted) },
                        singleLine = true, shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextMain, unfocusedTextColor = TextMain,
                            focusedBorderColor = Blue, unfocusedBorderColor = Line,
                            cursorColor = Cyan, focusedContainerColor = Color(0xFF0C1525), unfocusedContainerColor = Color(0xFF0C1525)
                        )
                    )
                    ModeSelector(mode, onMode)
                    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add to download queue", fontWeight = FontWeight.Bold)
                    }
                    Text("Best available video + audio or best-audio MP3 preset.", color = TextMuted, fontSize = 10.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("IN QUEUE", items.count { it.status == DownloadStatus.QUEUED }.toString(), Icons.Default.Queue, Blue, Modifier.weight(1f))
                StatCard("ACTIVE", active.toString(), Icons.Default.Downloading, Cyan, Modifier.weight(1f))
                StatCard("COMPLETED", completed.toString(), Icons.Default.CheckCircle, Green, Modifier.weight(1f))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent downloads", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onQueue) { Text("View queue", color = Cyan) }
            }
        }
        if (items.isEmpty()) {
            item { EmptyState("Nothing downloading yet", "Paste a link above or open YouTube and use the floating Download Studio controls.", Icons.Default.VideoLibrary) }
        } else {
            lazyItems(items.sortedByDescending { it.createdAt }.take(3), key = { it.id }) { item -> DownloadRow(item, onClick = { onOpenItem(item) }) }
        }
        item { FloatingFeatureCard() }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ModeSelector(mode: DownloadMode, onMode: (DownloadMode) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0B1423)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ModeChip("Video", "Best video + audio", mode == DownloadMode.VIDEO, Blue, Modifier.weight(1f)) { onMode(DownloadMode.VIDEO) }
        ModeChip("Audio", "Best audio · MP3", mode == DownloadMode.AUDIO, Green, Modifier.weight(1f)) { onMode(DownloadMode.AUDIO) }
    }
}

@Composable
private fun ModeChip(title: String, detail: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(11.dp)).background(if (selected) color.copy(alpha = .18f) else Color.Transparent).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = if (selected) color else TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(detail, color = TextMuted, fontSize = 9.sp)
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Text(value, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(label, color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
        }
    }
}

@Composable
private fun FloatingFeatureCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Panel2), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Violet.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PictureInPictureAlt, null, tint = Violet)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Floating video controls", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Show two download actions while YouTube is open.", color = TextMuted, fontSize = 10.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
        }
    }
}

@Composable
private fun QueueScreen(items: List<DownloadItem>, onOpenItem: (DownloadItem) -> Unit) {
    val active = items.count { it.status in setOf(DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Download queue", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("$active active · ${items.count { it.status == DownloadStatus.QUEUED }} waiting", color = TextMuted, fontSize = 12.sp)
            }
            Text("${items.size} ITEMS", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
        Spacer(Modifier.height(14.dp))
        if (items.isEmpty()) EmptyState("Your queue is clear", "Add a URL or share a video from YouTube to Download Studio.", Icons.Default.CloudDownload)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            lazyItems(items.sortedByDescending { it.createdAt }, key = { it.id }) { DownloadRow(it, onClick = { onOpenItem(it) }) }
        }
    }
}

@Composable
private fun LibraryScreen(items: List<DownloadItem>, onOpenItem: (DownloadItem) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text("Your library", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("Completed downloads", color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        if (items.isEmpty()) EmptyState("No completed files yet", "Your finished videos and audio will appear here.", Icons.Default.VideoLibrary)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { lazyItems(items, key = { it.id }) { DownloadRow(it, onClick = { onOpenItem(it) }) } }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onClick: () -> Unit) {
    val color = when (item.status) {
        DownloadStatus.COMPLETED -> Green
        DownloadStatus.FAILED -> Red
        DownloadStatus.PAUSED -> Violet
        DownloadStatus.QUEUED -> TextMuted
        else -> Cyan
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(if (item.mode == DownloadMode.VIDEO) Blue.copy(alpha = .15f) else Green.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                    Icon(if (item.mode == DownloadMode.VIDEO) Icons.Default.PlayCircle else Icons.Default.MusicNote, null, tint = if (item.mode == DownloadMode.VIDEO) Blue else Green)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.url.substringAfter("//").take(48), color = TextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.clip(CircleShape).background(color.copy(alpha = .15f)).padding(horizontal = 9.dp, vertical = 5.dp)) {
                    Text(item.status.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (item.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.STARTING, DownloadStatus.PROCESSING, DownloadStatus.PAUSED)) {
                LinearProgressIndicator(progress = { item.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = Blue, trackColor = Line)
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("${(item.progress * 100).toInt()}%  ·  ${item.downloaded}", color = TextMuted, fontSize = 9.sp)
                    Text("${item.speed}  ·  ${item.eta}", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (item.status == DownloadStatus.COMPLETED) {
                Text(item.outputPath.substringAfterLast('/').ifBlank { "Saved to Downloads" }, color = Green, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (item.error.isNotBlank()) {
                Text(item.error, color = Red, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text(if (item.mode == DownloadMode.VIDEO) "Best available video + audio" else "Best audio extracted to MP3", color = TextMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 46.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(62.dp).clip(RoundedCornerShape(20.dp)).background(Panel2), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Cyan, modifier = Modifier.size(28.dp)) }
        Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(detail, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.widthIn(max = 270.dp))
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    var basePath by remember { mutableStateOf(QueueStore.basePath(context).orEmpty()) }
    var treeGranted by remember { mutableStateOf(!QueueStore.treeUri(context).isNullOrBlank()) }
    var maxConcurrent by remember { mutableIntStateOf(QueueStore.maxConcurrent(context)) }
    var floating by remember { mutableStateOf(QueueStore.floatingEnabled(context)) }
    var termuxInstalled by remember { mutableStateOf(TermuxBridge.isInstalled(context)) }
    var termuxPermission by remember { mutableStateOf(TermuxBridge.hasRunPermission(context)) }
    var engineStatus by remember { mutableStateOf("Not checked") }
    val clipboard = LocalClipboardManager.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            val path = StorageAccess.termuxPathForTreeUri(uri)
            if (path != null) {
                QueueStore.setTree(context, uri.toString(), path)
                basePath = path; treeGranted = true
                DownloadCoordinator.configurationChanged()
                Toast.makeText(context, "Downloads folder saved", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(context, "Could not resolve the selected folder. Choose a folder on internal storage.", Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column {
                Text("Settings", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                Text("Configure the on-device download engine", color = TextMuted, fontSize = 12.sp)
            }
        }
        item {
            SettingsCard("DOWNLOAD LOCATION", "Choose the Download Studio folder inside Downloads (create it in the Files app first), or select another folder.") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Folder, tint = Cyan, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text(if (treeGranted) "Downloads folder connected" else "Folder access required", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(if (basePath.isBlank()) "Select Download/Download Studio, or another writable folder" else basePath, color = TextMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2)) {
                    Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose folder")
                }
            }
        }
        item {
            SettingsCard("DOWNLOAD CAPACITY", "Choose how many yt-dlp tasks may run at the same time.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Maximum concurrent downloads", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Default: 4", color = TextMuted, fontSize = 10.sp)
                    }
                    Text("$maxConcurrent", color = Cyan, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                }
                Slider(value = maxConcurrent.toFloat(), onValueChange = { maxConcurrent = it.toInt().coerceIn(1, 8) }, valueRange = 1f..8f, steps = 6, onValueChangeFinished = { DownloadCoordinator.setMaxConcurrent(maxConcurrent) }, colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Blue, inactiveTrackColor = Line))
                Text("Android default is 4 active downloads. Higher concurrency may use more battery and bandwidth.", color = TextMuted, fontSize = 10.sp)
            }
        }
        item {
            SettingsCard("DOWNLOAD ENGINE", "The reference project's yt-dlp + FFmpeg command-line workflow runs locally through Termux.") {
                StatusLine("Termux installed", termuxInstalled, if (termuxInstalled) "Detected" else "Install Termux first")
                StatusLine("Run-command permission", termuxPermission, if (termuxPermission) "Granted" else "Grant in App permissions")
                StatusLine("Download folder", treeGranted, if (treeGranted) "Connected" else "Not connected")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("yt-dlp / FFmpeg", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        Text(engineStatus, color = if (engineStatus.contains("MISSING", true)) Red else TextMuted, fontSize = 9.sp)
                    }
                    TextButton(onClick = {
                        val dest = StorageAccess.destinationPath(context)
                        if (dest.isNullOrBlank()) engineStatus = "Choose a Downloads folder first"
                        else {
                            engineStatus = "Checking…"
                            val result = TermuxBridge.runShell(context, TermuxBridge.engineCheckScript(dest), "Download Studio · engine check", true)
                            if (result.isFailure) engineStatus = result.exceptionOrNull()?.message ?: "Could not check Termux"
                        }
                    }) { Text("Check") }
                }
                OutlinedButton(onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage(TermuxBridge.TERMUX_PACKAGE)
                    if (intent != null) context.startActivity(intent) else Toast.makeText(context, "Install Termux from F-Droid or its official GitHub releases.", Toast.LENGTH_LONG).show()
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Terminal, null); Spacer(Modifier.width(8.dp)); Text("Open Termux") }
                Button(onClick = {
                    clipboard.setText(AnnotatedString(TermuxBridge.setupScript()))
                    Toast.makeText(context, "Setup command copied. Paste it into Termux and approve storage access.", Toast.LENGTH_LONG).show()
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                    Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy engine setup command")
                }
                Text("After installing Termux, set allow-external-apps=true in ~/.termux/termux.properties, restart Termux, and grant Download Studio the Run commands in Termux permission.", color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2)) {
                    Icon(Icons.Default.Security, null); Spacer(Modifier.width(8.dp)); Text("Open Download Studio app permissions")
                }
            }
        }
        item {
            SettingsCard("YOUTUBE FLOATING CONTROLS", "A small two-action panel appears while YouTube is in the foreground. Select an action, then use YouTube Share → Download Studio to provide the URL.") {
                StatusLine("Display over other apps", Settings.canDrawOverlays(context), if (Settings.canDrawOverlays(context)) "Allowed" else "Permission required")
                StatusLine("Usage access", hasUsageAccess(context), if (hasUsageAccess(context)) "Allowed" else "Permission required")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Floating controls", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("Show the overlay while YouTube is open", color = TextMuted, fontSize = 10.sp)
                    }
                    Switch(checked = floating, onCheckedChange = { enable ->
                        floating = enable; QueueStore.setFloatingEnabled(context, enable)
                        if (enable) {
                            when {
                                !Settings.canDrawOverlays(context) -> context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                                !hasUsageAccess(context) -> context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                else -> startOverlayService(context)
                            }
                        } else context.stopService(Intent(context, OverlayService::class.java))
                    })
                }
                Button(onClick = {
                    when {
                        !Settings.canDrawOverlays(context) -> context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        !hasUsageAccess(context) -> context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        else -> { startOverlayService(context); floating = true; QueueStore.setFloatingEnabled(context, true) }
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2)) { Icon(Icons.Default.PictureInPictureAlt, null); Spacer(Modifier.width(8.dp)); Text("Enable floating controls") }
            }
        }
        item {
            SettingsCard("ABOUT DOWNLOAD STUDIO", "Local media downloader") {
                Text("Developed by Amir Malik (2026)", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Version 0.1.2", color = TextMuted, fontSize = 10.sp)
            }
        }
        item {
            Text("Download policy: yt-dlp handles extraction and format selection; FFmpeg handles merging and audio extraction. DRM circumvention is not supported.", color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            termuxInstalled = TermuxBridge.isInstalled(context)
            termuxPermission = TermuxBridge.hasRunPermission(context)
            treeGranted = !QueueStore.treeUri(context).isNullOrBlank()
            if (engineStatus == "Checking…") {
                val report = StorageAccess.readState(context, "DownloadStudioState/engine.txt")
                if (!report.isNullOrBlank()) engineStatus = report.trim().replace("\n", " · ")
            }
            delay(1500)
        }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp), content = {
            Text(title, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Text(subtitle, color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
            content()
        })
    }
}

@Composable
private fun StatusLine(title: String, good: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (good) Icons.Default.CheckCircle else Icons.Default.Info, null, tint = if (good) Green else Violet, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = TextMain, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(detail, color = if (good) Green else TextMuted, fontSize = 9.sp)
    }
}

@Composable
private fun DownloadDetailsDialog(item: DownloadItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(item.title, color = TextMain, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailLine("Type", if (item.mode == DownloadMode.VIDEO) "Best available video + audio" else "Best audio · MP3")
                DetailLine("Status", item.status.name)
                DetailLine("Progress", "${(item.progress * 100).toInt()}%")
                DetailLine("Speed", item.speed)
                DetailLine("ETA", item.eta)
                DetailLine("Source", item.url)
                if (item.outputPath.isNotBlank()) DetailLine("Saved file", item.outputPath)
                if (item.error.isNotBlank()) Text(item.error, color = Red, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Row {
                if (item.status == DownloadStatus.DOWNLOADING) TextButton(onClick = { DownloadCoordinator.pause(item.id); onDismiss() }) { Text("Pause") }
                if (item.status == DownloadStatus.PAUSED) TextButton(onClick = { DownloadCoordinator.resume(item.id); onDismiss() }) { Text("Resume") }
                if (item.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.STARTING, DownloadStatus.PAUSED, DownloadStatus.QUEUED)) TextButton(onClick = { DownloadCoordinator.stop(item.id); onDismiss() }) { Text("Stop", color = Red) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(value, color = TextMain, fontSize = 11.sp)
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    val mode = if (Build.VERSION.SDK_INT >= 29) appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    else appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun startOverlayService(context: Context) {
    val intent = Intent(context, OverlayService::class.java)
    runCatching {
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
    }.onFailure { Toast.makeText(context, "Could not start floating controls: ${it.message}", Toast.LENGTH_LONG).show() }
}
