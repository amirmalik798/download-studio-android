package com.downloadstudio.android

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
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
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadstudio.android.data.DownloadItem
import com.downloadstudio.android.data.DownloadMode
import com.downloadstudio.android.data.DownloadStatus
import com.downloadstudio.android.data.QueueStore
import com.downloadstudio.android.engine.DownloadCoordinator
import com.downloadstudio.android.engine.TermuxBridge
import com.downloadstudio.android.util.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.URI
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
                        runCatching { DownloadCoordinator.enqueue(url, mode); Unit }
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

    private fun handleIncoming(intent: Intent?) {
        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val url = extractFirstUrl(raw) ?: return
        pendingSharedUrl = url
    }

    companion object {
        var pendingSharedUrl by mutableStateOf<String?>(null)
    }
}

private enum class AppTab { HOME, DOWNLOADS, SETTINGS }

@Composable
private fun DownloadStudioRoot(onIncomingUrl: (String, DownloadMode) -> Result<Unit>) {
    val context = LocalContext.current
    val items by DownloadCoordinator.items.collectAsState()
    val uiPreferences = remember {
        context.getSharedPreferences("download_studio_ui", android.content.Context.MODE_PRIVATE)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var url by remember { mutableStateOf("") }
    var mode by remember {
        mutableStateOf(
            runCatching {
                DownloadMode.valueOf(uiPreferences.getString("default_mode", "VIDEO") ?: "VIDEO")
            }.getOrDefault(DownloadMode.VIDEO)
        )
    }
    var selected by remember { mutableStateOf<DownloadItem?>(null) }
    var setupReady by remember {
        mutableStateOf(StorageAccess.isDownloadLocationConfigured(context) &&
            TermuxBridge.isInstalled(context) && TermuxBridge.hasRunPermission(context))
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            setupReady = StorageAccess.isDownloadLocationConfigured(context) &&
                TermuxBridge.isInstalled(context) && TermuxBridge.hasRunPermission(context)
            if (!setupReady) tab = AppTab.SETTINGS
            delay(750)
        }
    }
    val openDownload: (DownloadItem) -> Unit = { item ->
        if (item.status == DownloadStatus.COMPLETED) {
            val document = StorageAccess.findDownloadedDocument(context, item.outputPath)
            if (document == null) {
                Toast.makeText(context, "The downloaded file could not be found in the selected folder.", Toast.LENGTH_LONG).show()
            } else {
                val mimeType = document.type ?: StorageAccess.mimeTypeForFileName(document.name.orEmpty()) ?: "*/*"
                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(document.uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(openIntent) }
                    .onFailure { Toast.makeText(context, "No compatible app is available to open this file.", Toast.LENGTH_LONG).show() }
            }
        } else {
            selected = item
        }
    }
    val incoming = MainActivity.pendingSharedUrl
    LaunchedEffect(incoming) {
        if (!incoming.isNullOrBlank()) {
            // Use the existing editable Quick Download field for shared links.
            url = incoming
            tab = if (setupReady) AppTab.HOME else AppTab.SETTINGS
            MainActivity.pendingSharedUrl = null
        }
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D1524), tonalElevation = 0.dp) {
                NavigationBarItem(selected = tab == AppTab.HOME, enabled = setupReady, onClick = { if (setupReady) tab = AppTab.HOME }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(selected = tab == AppTab.DOWNLOADS, enabled = setupReady, onClick = { if (setupReady) tab = AppTab.DOWNLOADS }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("Downloads") })
                NavigationBarItem(selected = tab == AppTab.SETTINGS, onClick = { tab = AppTab.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().background(Ink).padding(padding)) {
            TopHeader(tab) { tab = AppTab.SETTINGS }
            when (tab) {
                AppTab.HOME -> if (setupReady) HomeScreen(
                    items = items, url = url, onUrl = { url = it }, mode = mode,
                    onMode = {
                        mode = it
                        uiPreferences.edit().putString("default_mode", it.name).apply()
                    }, onAdd = {
                        val clean = url.trim()
                        val uri = runCatching { Uri.parse(clean) }.getOrNull()
                        if ((uri?.scheme == "http" || uri?.scheme == "https") && !uri.host.isNullOrBlank()) {
                            onIncomingUrl(clean, mode)
                                .onSuccess {
                                    url = ""
                                    tab = AppTab.DOWNLOADS
                                    snackbarScope.launch { snackbarHostState.showSnackbar("Added to Downloads") }
                                }
                                .onFailure {
                                    Toast.makeText(context, friendlyError(it.message ?: "Could not add link"), Toast.LENGTH_LONG).show()
                                }
                        } else {
                            Toast.makeText(context, "Paste a valid video or media link.", Toast.LENGTH_SHORT).show()
                        }
                    }, onOpenItem = openDownload, onQueue = { tab = AppTab.DOWNLOADS }
                )
                AppTab.DOWNLOADS -> if (setupReady) DownloadsScreen(items, onOpenItem = openDownload)
                AppTab.SETTINGS -> SettingsScreen(setupRequired = !setupReady)
            }
        }
    }

    selected?.let { item ->
        DownloadDetailsDialog(item, onDismiss = { selected = null })
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
                Text("Paste a link, check the preview, and download.", color = TextMuted, fontSize = 13.sp)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val clipboard = LocalClipboardManager.current
                    val context = LocalContext.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("QUICK DOWNLOAD", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                val pasted = clipboard.getText()?.text.orEmpty()
                                val link = extractFirstUrl(pasted)
                                if (link != null) onUrl(link)
                                else Toast.makeText(context, "No link found on the clipboard.", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Paste", fontSize = 11.sp)
                        }
                        if (url.isNotBlank()) {
                            IconButton(onClick = { onUrl("") }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Close, "Clear link", tint = TextMuted)
                            }
                        }
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
                    VideoThumbnailPreview(url)
                    ModeSelector(mode, onMode)
                    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add download", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (mode == DownloadMode.VIDEO) "Best available video + audio."
                        else "Best available audio, saved as MP3.",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
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
                TextButton(onClick = onQueue) { Text("View downloads", color = Cyan) }
            }
        }
        if (items.isEmpty()) {
            item { EmptyState("Nothing downloading yet", "Paste a link above, or open YouTube → Share → Download Studio.", Icons.Default.VideoLibrary) }
        } else {
            lazyItems(items.sortedByDescending { it.createdAt }.take(3), key = { it.id }) { item -> DownloadRow(item, onClick = { onOpenItem(item) }) }
        }
        item { ShareFeatureCard() }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ModeSelector(mode: DownloadMode, onMode: (DownloadMode) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0B1423)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ModeChip("Video", "Best available video + audio", mode == DownloadMode.VIDEO, Blue, Modifier.weight(1f)) { onMode(DownloadMode.VIDEO) }
        ModeChip("Audio", "Best available audio", mode == DownloadMode.AUDIO, Green, Modifier.weight(1f)) { onMode(DownloadMode.AUDIO) }
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
private fun ShareFeatureCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Panel2), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Violet.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Share, null, tint = Violet)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Download from YouTube", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Use Share in any app and choose Download Studio, or paste a link above.", color = TextMuted, fontSize = 10.sp)
            }
        }
    }
}

private enum class DownloadSort(val label: String) {
    NAME_ASC("Name · A–Z"),
    NAME_DESC("Name · Z–A"),
    DATE_NEWEST("Date · Newest first"),
    DATE_OLDEST("Date · Oldest first")
}

private enum class DownloadFilter { ALL, VIDEO, AUDIO, DOWNLOADING, DOWNLOADED }

@Composable
private fun DownloadsScreen(items: List<DownloadItem>, onOpenItem: (DownloadItem) -> Unit) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var sort by remember { mutableStateOf(DownloadSort.DATE_NEWEST) }
    var sortExpanded by remember { mutableStateOf(false) }
    var toolsExpanded by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var itemPendingRemoval by remember { mutableStateOf<DownloadItem?>(null) }
    val active = items.count { it.status in setOf(DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING) }
    val visibleItems = remember(items, search, filter, sort) {
        val matches = items.filter { item ->
            val categoryMatches = when (filter) {
                DownloadFilter.ALL -> true
                DownloadFilter.VIDEO -> item.mode == DownloadMode.VIDEO
                DownloadFilter.AUDIO -> item.mode == DownloadMode.AUDIO
                DownloadFilter.DOWNLOADING -> item.status in setOf(DownloadStatus.QUEUED, DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.PROCESSING)
                DownloadFilter.DOWNLOADED -> item.status == DownloadStatus.COMPLETED
            }
            categoryMatches && displayTitle(item).contains(search.trim(), ignoreCase = true)
        }
        when (sort) {
            DownloadSort.NAME_ASC -> matches.sortedBy { displayTitle(it).lowercase(Locale.ROOT) }
            DownloadSort.NAME_DESC -> matches.sortedByDescending { displayTitle(it).lowercase(Locale.ROOT) }
            DownloadSort.DATE_NEWEST -> matches.sortedByDescending { downloadTimestamp(it) }
            DownloadSort.DATE_OLDEST -> matches.sortedBy { downloadTimestamp(it) }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                Text("${items.size} items · $active active · ${items.count { it.status == DownloadStatus.COMPLETED }} completed", color = TextMuted, fontSize = 11.sp)
            }
            Text("${visibleItems.size}", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            IconButton(onClick = { toolsExpanded = !toolsExpanded }) {
                Icon(if (toolsExpanded) Icons.Default.Close else Icons.Default.Search, "Search and filters", tint = Cyan)
            }
            IconButton(onClick = { showClearConfirmation = true }, enabled = items.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, "Clear downloads list", tint = if (items.isNotEmpty()) Red else TextMuted)
            }
        }
        AnimatedVisibility(visible = toolsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search by name", color = TextMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) },
                    trailingIcon = { if (search.isNotBlank()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "Clear search", tint = TextMuted) } },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextMain, unfocusedTextColor = TextMain,
                        focusedBorderColor = Blue, unfocusedBorderColor = Line, cursorColor = Cyan,
                        focusedContainerColor = Color(0xFF0C1525), unfocusedContainerColor = Color(0xFF0C1525)))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = filter == DownloadFilter.ALL, onClick = { filter = DownloadFilter.ALL }, label = { Text("All") })
                    FilterChip(selected = filter == DownloadFilter.VIDEO, onClick = { filter = DownloadFilter.VIDEO }, label = { Text("Video") })
                    FilterChip(selected = filter == DownloadFilter.AUDIO, onClick = { filter = DownloadFilter.AUDIO }, label = { Text("Audio") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = filter == DownloadFilter.DOWNLOADING, onClick = { filter = DownloadFilter.DOWNLOADING }, label = { Text("Downloading") })
                    FilterChip(selected = filter == DownloadFilter.DOWNLOADED, onClick = { filter = DownloadFilter.DOWNLOADED }, label = { Text("Downloaded") })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("SORT BY", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { sortExpanded = true }) {
                            Icon(Icons.Default.Sort, null, tint = Cyan, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp)); Text(sort.label, color = Cyan, fontSize = 11.sp)
                            Icon(Icons.Default.ArrowDropDown, null, tint = Cyan)
                        }
                        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                            DownloadSort.values().forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; sortExpanded = false }) }
                        }
                    }
                }
            }
        }
        if (showClearConfirmation) {
            AlertDialog(onDismissRequest = { showClearConfirmation = false }, containerColor = Panel,
                title = { Text("Clear downloads list?", color = TextMain) },
                text = { Text("This removes download records and stops active jobs. Files already saved on your device will not be deleted.", color = TextMuted) },
                confirmButton = { TextButton(onClick = { DownloadCoordinator.clearAll(); showClearConfirmation = false }) { Text("Clear list", color = Red) } },
                dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") } })
        }
        itemPendingRemoval?.let { item ->
            AlertDialog(onDismissRequest = { itemPendingRemoval = null }, containerColor = Panel,
                title = { Text("Remove from downloads?", color = TextMain) },
                text = { Text("\"${displayTitle(item)}\" will be removed from Download Studio. The actual media file on your device will not be deleted.", color = TextMuted) },
                confirmButton = {
                    TextButton(onClick = { DownloadCoordinator.remove(item.id); itemPendingRemoval = null }) {
                        Text("Remove", color = Red)
                    }
                },
                dismissButton = { TextButton(onClick = { itemPendingRemoval = null }) { Text("Cancel") } })
        }
        Spacer(Modifier.height(5.dp))
        when {
            items.isEmpty() -> EmptyState("No downloads yet", "Paste a link or use YouTube → Share → Download Studio.", Icons.Default.CloudDownload)
            visibleItems.isEmpty() -> EmptyState("No matching downloads", "Try another search term or category filter.", Icons.Default.Search)
            else -> LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                lazyItems(visibleItems, key = { it.id }) { item ->
                    DownloadRow(item, onClick = { onOpenItem(item) }, onRemoveRequest = { itemPendingRemoval = item })
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onClick: () -> Unit, onRemoveRequest: (() -> Unit)? = null) {
    val context = LocalContext.current
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
                val thumbnailUrl = item.thumbnailUrl.takeIf { it.isNotBlank() } ?: youtubeThumbnailUrl(item.url)
                var thumbnailFailed by remember(item.id, thumbnailUrl) { mutableStateOf(false) }
                Box(Modifier.size(width = 92.dp, height = 58.dp).clip(RoundedCornerShape(10.dp)).background(if (item.mode == DownloadMode.VIDEO) Blue.copy(alpha = .15f) else Green.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                    if (thumbnailUrl != null && !thumbnailFailed) {
                        AsyncImage(model = thumbnailUrl, contentDescription = "YouTube thumbnail", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { thumbnailFailed = true })
                    } else {
                        Icon(if (item.mode == DownloadMode.VIDEO) Icons.Default.PlayCircle else Icons.Default.MusicNote, null, tint = if (item.mode == DownloadMode.VIDEO) Blue else Green)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(displayTitle(item), color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        val isVideo = item.mode == DownloadMode.VIDEO
                        Box(Modifier.clip(CircleShape).background((if (isVideo) Blue else Green).copy(alpha = .16f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                            Text(if (isVideo) "VIDEO" else "AUDIO", color = if (isVideo) Blue else Green, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(item.url.substringAfter("//").take(34), color = TextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.clip(CircleShape).background(color.copy(alpha = .15f)).padding(horizontal = 9.dp, vertical = 5.dp)) {
                    Text(item.status.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                var actionsExpanded by remember(item.id) { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { actionsExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, "Download actions", tint = TextMuted)
                    }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        if (item.status == DownloadStatus.COMPLETED) {
                            DropdownMenuItem(text = { Text("View in folder") },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                onClick = {
                                    actionsExpanded = false
                                    openDownloadedFolder(context, item)
                                })
                            DropdownMenuItem(text = { Text("Share file") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    actionsExpanded = false
                                    shareDownloadedFile(context, item)
                                })
                        }
                        DropdownMenuItem(text = { Text("Remove from downloads") },
                            leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) },
                            onClick = { actionsExpanded = false; onRemoveRequest?.invoke() })
                    }
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

private fun openDownloadedFolder(context: android.content.Context, item: DownloadItem) {
    val folder = StorageAccess.findDownloadedFolder(context, item.outputPath)
    if (folder == null) {
        Toast.makeText(context, "The download folder could not be found.", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(folder.uri, DocumentsContract.Document.MIME_TYPE_DIR)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "No file manager on this device can open this folder.", Toast.LENGTH_LONG).show()
        }
}

private fun shareDownloadedFile(context: android.content.Context, item: DownloadItem) {
    val document = StorageAccess.findDownloadedDocument(context, item.outputPath)
    if (document == null) {
        Toast.makeText(context, "The downloaded file could not be found.", Toast.LENGTH_LONG).show()
        return
    }
    val mimeType = document.type
        ?: StorageAccess.mimeTypeForFileName(document.name.orEmpty())
        ?: "*/*"
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, document.uri)
        putExtra(Intent.EXTRA_SUBJECT, displayTitle(item))
        clipData = ClipData.newUri(context.contentResolver, displayTitle(item), document.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(shareIntent, "Share " + displayTitle(item)))
    }.onFailure {
        Toast.makeText(context, "No compatible app is available to share this file.", Toast.LENGTH_LONG).show()
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
private fun SettingsScreen(setupRequired: Boolean = false) {
    val context = LocalContext.current
    var basePath by remember { mutableStateOf(QueueStore.basePath(context).orEmpty()) }
    var treeGranted by remember { mutableStateOf(StorageAccess.isDownloadLocationConfigured(context)) }
    var maxConcurrent by remember { mutableIntStateOf(QueueStore.maxConcurrent(context)) }
    var termuxInstalled by remember { mutableStateOf(TermuxBridge.isInstalled(context)) }
    var termuxPermission by remember { mutableStateOf(TermuxBridge.hasRunPermission(context)) }
    var engineStatus by remember { mutableStateOf("Not checked") }
    val clipboard = LocalClipboardManager.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val permissionFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val path = StorageAccess.termuxPathForTreeUri(uri)
            val permissionGranted = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, permissionFlags)
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission && it.isWritePermission
                }
            }.getOrDefault(false)
            if (path != null && permissionGranted) {
                QueueStore.setTree(context, uri.toString(), path)
                basePath = path
                treeGranted = StorageAccess.isDownloadLocationConfigured(context)
                DownloadCoordinator.configurationChanged()
                if (treeGranted) Toast.makeText(context, "Downloads folder saved", Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, "Android did not grant writable folder access. Please choose the folder again.", Toast.LENGTH_LONG).show()
            } else Toast.makeText(context, "Folder access was not granted. Choose a writable folder on internal storage.", Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Settings", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                Text("Configure the on-device download engine", color = TextMuted, fontSize = 12.sp)
            }
            if (setupRequired) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel2), border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = .65f)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("SETUP REQUIRED", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Configure a writable download folder, install Termux, and grant Download Studio the Run commands in Termux permission. Home and Downloads stay locked until all checks pass.", color = TextMain, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
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
            SettingsCard("DOWNLOAD CAPACITY", "Conservative mobile settings reduce simultaneous network and processing load.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Maximum concurrent downloads", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Default: 1 · Maximum: 2", color = TextMuted, fontSize = 10.sp)
                    }
                    Text("$maxConcurrent", color = Cyan, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                }
                Slider(value = maxConcurrent.toFloat(), onValueChange = { maxConcurrent = it.toInt().coerceIn(1, 2) }, valueRange = 1f..2f, steps = 0, onValueChangeFinished = { DownloadCoordinator.setMaxConcurrent(maxConcurrent) }, colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Blue, inactiveTrackColor = Line))
                Text("Only one download runs by default. Two is the maximum; use one for cooler, quieter operation.", color = TextMuted, fontSize = 10.sp)
            }
        }
        item {
            val thermalText by DownloadCoordinator.thermalStatus.collectAsState()
            SettingsCard("DEVICE SAFETY", "Download Studio limits background work and watches Android thermal status where the OS exposes it.") {
                Text("Maximum concurrent downloads: $maxConcurrent", color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(thermalText, color = Cyan, fontSize = 10.sp, lineHeight = 15.sp)
                Text("Downloads are held at moderate thermal status and an active task is interrupted at severe status. Inactivity is limited to 15 minutes and each task has a six-hour maximum.", color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
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
            SettingsCard("ABOUT DOWNLOAD STUDIO", "Local media downloader") {
                Text("Developed by Amir Malik (2026)", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Version 0.1.5", color = TextMuted, fontSize = 10.sp)
            }
        }
        item {
            Text("Download policy: yt-dlp handles extraction and format selection; FFmpeg handles merging and audio extraction. DRM circumvention is not supported.", color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            termuxInstalled = TermuxBridge.isInstalled(context)
            termuxPermission = TermuxBridge.hasRunPermission(context)
            treeGranted = StorageAccess.isDownloadLocationConfigured(context)
            basePath = QueueStore.basePath(context).orEmpty()
            delay(750)
        }
    }
    LaunchedEffect(engineStatus) {
        if (engineStatus == "Checking…") {
            repeat(15) {
                val report = StorageAccess.readState(context, "DownloadStudioState/engine.txt")
                if (!report.isNullOrBlank()) {
                    engineStatus = report.trim().replace("\\n", " · ")
                    return@LaunchedEffect
                }
                delay(1_000)
            }
            if (engineStatus == "Checking…") engineStatus = "No response from Termux. Recheck its Run Command permission."
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
        title = { Text(displayTitle(item), color = TextMain, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailLine("Type", if (item.mode == DownloadMode.VIDEO) "Best available video + audio" else "Best audio · MP3")
                DetailLine("Status", item.status.name)
                DetailLine("Download date", formatDownloadDate(item))
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


private fun youtubeVideoId(rawUrl: String): String? = runCatching {
    val uri = Uri.parse(rawUrl)
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    val candidate = when {
        host == "youtu.be" -> uri.pathSegments.firstOrNull()
        host.endsWith("youtube.com") || host == "youtube-nocookie.com" -> {
            uri.getQueryParameter("v") ?: run {
                val parts = uri.pathSegments
                val marker = parts.indexOfFirst { it in setOf("shorts", "embed", "live") }
                if (marker >= 0) parts.getOrNull(marker + 1) else null
            }
        }
        else -> null
    }
    candidate?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
}.getOrNull()

private fun youtubeThumbnailUrl(rawUrl: String): String? = youtubeVideoId(rawUrl)?.let {
    "https://i.ytimg.com/vi/$it/hqdefault.jpg"
}

private fun isPublicHttpsUrl(rawUrl: String): Boolean = runCatching {
    val uri = URI(rawUrl)
    val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
    if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.userInfo != null ||
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
    val addresses = InetAddress.getAllByName(host)
    addresses.isNotEmpty() && addresses.all { address ->
        val bytes = address.address
        val privateIpv6 = bytes.size == 16 &&
            ((bytes[0].toInt() and 0xFE) == 0xFC ||
                ((bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80))
        val privateIpv4 = bytes.size == 4 && (
            (bytes[0].toInt() and 0xFF) == 10 ||
                (bytes[0].toInt() and 0xFF) == 127 ||
                (bytes[0].toInt() and 0xFF) == 0 ||
                ((bytes[0].toInt() and 0xFF) == 169 && (bytes[1].toInt() and 0xFF) == 254) ||
                ((bytes[0].toInt() and 0xFF) == 172 && (bytes[1].toInt() and 0xF0) == 16) ||
                ((bytes[0].toInt() and 0xFF) == 192 && (bytes[1].toInt() and 0xFF) == 168)
        )
        !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
            !address.isMulticastAddress && !privateIpv4 && !privateIpv6
    }
}.getOrDefault(false)

private fun resolvePageThumbnail(rawUrl: String): String? = runCatching {
    var currentUrl = rawUrl
    repeat(5) {
        if (!isPublicHttpsUrl(currentUrl)) return null
        val response = Jsoup.connect(currentUrl)
            .userAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .timeout(8_000)
            .maxBodySize(1_000_000)
            .followRedirects(false)
            .ignoreHttpErrors(true)
            .execute()
        if (response.statusCode() in 300..399) {
            val location = response.header("location") ?: return null
            currentUrl = URI(currentUrl).resolve(location).toString()
        } else {
            if (response.statusCode() !in 200..299) return null
            val document = response.parse()
            val candidates = listOf(
                document.selectFirst("meta[property=og:image]")?.attr("content"),
                document.selectFirst("meta[property=og:image:secure_url]")?.attr("content"),
                document.selectFirst("meta[name=twitter:image]")?.attr("content"),
                document.selectFirst("meta[name=twitter:image:src]")?.attr("content"),
                document.selectFirst("meta[itemprop=image]")?.attr("content"),
                document.selectFirst("link[rel=image_src]")?.attr("href"),
                document.selectFirst("video[poster]")?.attr("poster")
            )
            for (candidate in candidates) {
                if (candidate.isNullOrBlank()) continue
                val resolved = URI(currentUrl).resolve(candidate.trim()).toString()
                if (isPublicHttpsUrl(resolved)) return resolved
            }
            return null
        }
    }
    null
}.getOrNull()

@Composable
private fun VideoThumbnailPreview(rawUrl: String) {
    val cleanUrl = rawUrl.trim()
    if (!cleanUrl.startsWith("https://", ignoreCase = true) &&
        !cleanUrl.startsWith("http://", ignoreCase = true)) return

    var thumbnailUrl by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var lookupFailed by remember { mutableStateOf(false) }
    var imageLoaded by remember { mutableStateOf(false) }
    var imageFailed by remember { mutableStateOf(false) }

    LaunchedEffect(cleanUrl) {
        thumbnailUrl = null
        loading = false
        lookupFailed = false
        imageLoaded = false
        imageFailed = false
        if (cleanUrl.isBlank()) return@LaunchedEffect

        val youtubeThumbnail = youtubeThumbnailUrl(cleanUrl)
        if (youtubeThumbnail != null) {
            thumbnailUrl = youtubeThumbnail
            return@LaunchedEffect
        }

        if (!cleanUrl.startsWith("https://", ignoreCase = true)) {
            lookupFailed = true
            return@LaunchedEffect
        }

        delay(450)
        loading = true
        val resolved = withContext(Dispatchers.IO) { resolvePageThumbnail(cleanUrl) }
        thumbnailUrl = resolved
        lookupFailed = resolved == null
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0C1525)),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailUrl == null || !imageLoaded || imageFailed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = Cyan,
                    modifier = Modifier.size(30.dp)
                )
                Text(
                    when {
                        loading -> "Finding video thumbnail…"
                        lookupFailed || imageFailed -> "No preview image found for this link"
                        else -> "Preparing video preview…"
                    },
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
        thumbnailUrl?.let { image ->
            AsyncImage(
                model = image,
                contentDescription = "Video thumbnail preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { imageLoaded = true },
                onError = { imageFailed = true }
            )
        }
    }
}

private fun displayTitle(item: DownloadItem): String {
    val title = item.title.trim()
    val placeholders = setOf("shared media", "new download", "youtube video", "video from youtube.com", "video from youtu.be")
    if (title.isNotBlank() && title.lowercase(Locale.ROOT) !in placeholders) return title
    val rawName = item.outputPath.substringAfterLast("/")
    val fileName = rawName.substringBeforeLast(".", rawName)
    if (fileName.isNotBlank()) {
        val id = youtubeVideoId(item.url)
        val extracted = if (id != null) fileName.removeSuffix("-$id") else fileName
        if (extracted.isNotBlank()) return extracted
    }
    return if (youtubeVideoId(item.url) != null) "YouTube video" else title.ifBlank { "Downloaded media" }
}

private fun downloadTimestamp(item: DownloadItem): Long = item.completedAt.takeIf { it > 0L } ?: item.createdAt

private fun formatDownloadDate(item: DownloadItem): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(downloadTimestamp(item)))
