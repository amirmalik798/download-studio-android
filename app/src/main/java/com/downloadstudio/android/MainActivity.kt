package com.downloadstudio.android

import android.Manifest
import android.content.ClipData
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadstudio.android.data.DownloadItem
import com.downloadstudio.android.data.DownloadMode
import com.downloadstudio.android.data.DownloadStatus
import com.downloadstudio.android.data.QueueStore
import com.downloadstudio.android.engine.DownloadCoordinator
import com.downloadstudio.android.notifications.LinkStatusNotification
import com.downloadstudio.android.engine.TermuxBridge
import com.downloadstudio.android.util.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.URI
import java.util.Locale

private val Ink = Color(0xFF11161D)
private val Panel = Color(0xFF19212A)
private val Panel2 = Color(0xFF222C37)
private val Inset = Color(0xFF0D131A)
private val Line = Color(0xFF35414E)
private val TextMain = Color(0xFFF5F7F9)
private val TextMuted = Color(0xFF9AA7B4)
private val Yellow = Color(0xFFFFC928)
private val Cyan = Color(0xFFFFE07A)
private val Green = Color(0xFF5FC6A4)
private val Violet = Color(0xFF9EADC0)
private val Red = Color(0xFFFF766E)

private fun Modifier.premiumSurface(
    shape: Shape,
    elevation: Dp = 8.dp
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color(0xFF080B0F),
        spotColor = Color(0xFF303B47)
    )
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            listOf(
                Color(0xFF3B4754).copy(alpha = 0.70f),
                Color(0xFF0B1016).copy(alpha = 0.72f)
            )
        ),
        shape = shape
    )

private fun Modifier.premiumInset(shape: Shape): Modifier = this
    .clip(shape)
    .background(Inset)
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            listOf(Color(0xFF080D12), Color(0xFF34404C))
        ),
        shape = shape
    )

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            LinkStatusNotification.ensureChannel(this)
            LinkStatusNotification.showPending(this)
        } else {
            Toast.makeText(
                this,
                "Notifications are disabled. Enable them in Settings to see when shared links are accepted.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        DownloadCoordinator.synchronizeDownloadsFolder()
        LinkStatusNotification.ensureChannel(this)

        if (LinkStatusNotification.canPost(this)) {
            // Recover shared links accepted while permission was missing or the
            // app was returning from Android notification settings.
            LinkStatusNotification.showPending(this)
            return
        }

        val hasRuntimePermission = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!hasRuntimePermission) {
            val preferences = getSharedPreferences("download_studio_notification_state", MODE_PRIVATE)
            val alreadyRequested = preferences.getBoolean("notification_permission_request_started_v1", false)
            if (!alreadyRequested) {
                preferences.edit().putBoolean("notification_permission_request_started_v1", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(17, 22, 29)
        window.navigationBarColor = android.graphics.Color.rgb(17, 22, 29)
        DownloadCoordinator.initialize(this)
        handleIncoming(intent)
        setContent {
            val scheme = darkColorScheme(
                primary = Yellow, secondary = Cyan, tertiary = Violet,
                background = Ink, surface = Panel, surfaceVariant = Panel2,
                onPrimary = Ink, onBackground = TextMain, onSurface = TextMain,
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
        val notificationQueueId = intent
            ?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data
            ?.takeIf { it.scheme == "downloadstudio" && it.host == "queue" }
            ?.lastPathSegment
        if (!notificationQueueId.isNullOrBlank()) {
            // Opening the status notification is an explicit dismissal of that
            // notification, but it must never remove the underlying queue item.
            LinkStatusNotification.markDismissed(applicationContext, notificationQueueId)
            return
        }
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
            NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
                NavigationBarItem(selected = tab == AppTab.HOME, enabled = setupReady, onClick = { if (setupReady) tab = AppTab.HOME }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") }, colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Ink,
                    selectedTextColor = Yellow,
                    indicatorColor = Yellow,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                ))
                NavigationBarItem(selected = tab == AppTab.DOWNLOADS, enabled = setupReady, onClick = { if (setupReady) tab = AppTab.DOWNLOADS }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("Downloads") }, colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Ink,
                    selectedTextColor = Yellow,
                    indicatorColor = Yellow,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                ))
                NavigationBarItem(selected = tab == AppTab.SETTINGS, onClick = { tab = AppTab.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") }, colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Ink,
                    selectedTextColor = Yellow,
                    indicatorColor = Yellow,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                ))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().background(Ink).padding(padding)) {
            // Downloads uses its own compact toolbar; keep the branded header on Home and Settings.
            if (tab != AppTab.DOWNLOADS) {
                TopHeader(tab) { tab = AppTab.SETTINGS }
            }
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

    pendingFolderManagerTargets?.let { targets ->
        FolderManagerChoiceDialog(
            targets = targets,
            onDismiss = { pendingFolderManagerTargets = null },
            onChoice = { target, rememberChoice ->
                pendingFolderManagerTargets = null
                if (rememberChoice) {
                    uiPreferences.edit()
                        .putString(FOLDER_MANAGER_PREFERENCE, target.component.flattenToString())
                        .apply()
                }
                if (!startFolderTarget(context, target) && rememberChoice) {
                    uiPreferences.edit().remove(FOLDER_MANAGER_PREFERENCE).apply()
                }
            }
        )
    }

}

@Composable
private fun TopHeader(tab: AppTab, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel2).padding(horizontal = 20.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Yellow),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Download, null, tint = Ink, modifier = Modifier.size(24.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("DOWNLOAD STUDIO", color = TextMain, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 1.0.sp)
                Text(
                    if (tab == AppTab.HOME) "YOUR PERSONAL MEDIA HUB" else "PREFERENCES & SETUP",
                    color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.clip(CircleShape).background(Color(0xFF1F342F)).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Green))
                Text("ON DEVICE", color = Green, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .5.sp)
            }
            IconButton(
                onClick = onSettings,
                modifier = Modifier.size(40.dp).premiumSurface(CircleShape, 4.dp)
            ) { Icon(Icons.Default.Tune, "Settings", tint = TextMain, modifier = Modifier.size(19.dp)) }
        }
    }
    HorizontalDivider(color = Line.copy(alpha = .65f))
}

@Composable
private fun HomeHero() {
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("THE MEDIA WORKSPACE", color = Yellow, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp)
            Spacer(Modifier.weight(1f))
            Text("DIRECT TO DEVICE", color = TextMuted.copy(alpha = .85f), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text("YOUR MEDIA.", color = TextMain, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, letterSpacing = .2.sp)
            Text("ON YOUR TERMS.", color = Yellow, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, letterSpacing = .2.sp)
        }
        Text(
            "Paste a link, choose video or audio, and save it straight to your device.",
            color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.widthIn(max = 330.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.clip(CircleShape).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Default.VideoLibrary, null, tint = Yellow, modifier = Modifier.size(13.dp))
                Text("VIDEO + AUDIO", color = TextMain, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
            }
            Row(
                Modifier.clip(CircleShape).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Default.PhoneAndroid, null, tint = Green, modifier = Modifier.size(13.dp))
                Text("ON DEVICE", color = TextMain, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    items: List<DownloadItem>, url: String, onUrl: (String) -> Unit,
    mode: DownloadMode, onMode: (DownloadMode) -> Unit,
    onAdd: () -> Unit, onOpenItem: (DownloadItem) -> Unit, onQueue: () -> Unit
) {
    val active = items.count { it.status in setOf(DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING) }
    val completed = items.count { it.status == DownloadStatus.COMPLETED }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { HomeHero() }
        item {
            Card(
                modifier = Modifier.premiumSurface(RoundedCornerShape(24.dp), 10.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line.copy(alpha = .65f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val clipboard = LocalClipboardManager.current
                    val context = LocalContext.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("QUICK DOWNLOAD", color = Yellow, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
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
                        value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth().premiumInset(RoundedCornerShape(15.dp)),
                        placeholder = { Text("Paste a video or media URL", color = TextMuted) },
                        leadingIcon = { Icon(Icons.Default.Language, null, tint = TextMuted) },
                        singleLine = true, shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextMain, unfocusedTextColor = TextMain,
                            focusedBorderColor = Yellow, unfocusedBorderColor = Line,
                            cursorColor = Yellow, focusedContainerColor = Inset, unfocusedContainerColor = Inset
                        )
                    )
                    VideoThumbnailPreview(url)
                    ModeSelector(mode, onMode)
                    Button(
                        onClick = onAdd,
                        enabled = runCatching {
                            val uri = Uri.parse(url.trim())
                            (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
                        }.getOrDefault(false),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Ink),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 5.dp, pressedElevation = 1.dp)
                    ) {
                        Icon(Icons.Default.ArrowForward, null); Spacer(Modifier.width(9.dp)); Text("ADD TO DOWNLOADS", fontWeight = FontWeight.Black, letterSpacing = .7.sp)
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
                StatCard("IN QUEUE", items.count { it.status == DownloadStatus.QUEUED }.toString(), Icons.Default.Queue, Yellow, Modifier.weight(1f))
                StatCard("ACTIVE", active.toString(), Icons.Default.Downloading, Cyan, Modifier.weight(1f))
                StatCard("COMPLETED", completed.toString(), Icons.Default.CheckCircle, Green, Modifier.weight(1f))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RECENT DOWNLOADS", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .5.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onQueue) { Text("VIEW ALL  →", color = Yellow, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .6.sp) }
            }
        }
        if (items.isEmpty()) {
            item { EmptyState("Nothing downloading yet", "Paste a media link above, or use Share in another app and choose Download Studio.", Icons.Default.VideoLibrary) }
        } else {
            lazyItems(items.sortedByDescending { it.createdAt }.take(3), key = { it.id }) { item -> DownloadRow(item, onClick = { onOpenItem(item) }) }
        }
        item { ShareFeatureCard() }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ModeSelector(mode: DownloadMode, onMode: (DownloadMode) -> Unit) {
    Row(Modifier.fillMaxWidth().premiumInset(RoundedCornerShape(15.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ModeChip("Video", "Best available video + audio", mode == DownloadMode.VIDEO, Yellow, Modifier.weight(1f)) { onMode(DownloadMode.VIDEO) }
        ModeChip("Audio", "Best available audio", mode == DownloadMode.AUDIO, Green, Modifier.weight(1f)) { onMode(DownloadMode.AUDIO) }
    }
}

@Composable
private fun ModeChip(title: String, detail: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) color.copy(alpha = .15f) else Color.Transparent)
            .border(if (selected) 1.dp else 0.dp, if (selected) color.copy(alpha = .55f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = if (selected) color else TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(detail, color = TextMuted, fontSize = 9.sp)
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier.premiumSurface(RoundedCornerShape(18.dp), 7.dp), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line.copy(alpha = .6f)), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Text(value, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text(label, color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
        }
    }
}

@Composable
private fun ShareFeatureCard() {
    Card(modifier = Modifier.premiumSurface(RoundedCornerShape(20.dp), 8.dp), colors = CardDefaults.cardColors(containerColor = Panel2), shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line.copy(alpha = .65f)), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Violet.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Share, null, tint = Violet)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("SHARE TO DOWNLOAD STUDIO", color = TextMain, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = .5.sp)
                Text("Send a media link from any compatible app and it will appear in your download queue.", color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

private enum class DownloadSort(val label: String) {
    NEWEST_ARRIVALS("Newest arrivals first"),
    NAME_ASC("Name · A–Z"),
    NAME_DESC("Name · Z–A"),
    DATE_NEWEST("Date · Newest first"),
    DATE_OLDEST("Date · Oldest first")
}

private enum class DownloadFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    QUEUED("Queued"),
    DOWNLOADING("Downloading"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    VIDEO("Video"),
    AUDIO("Audio"),
    TODAY("Today")
}

@Composable
private fun DownloadsScreen(items: List<DownloadItem>, onOpenItem: (DownloadItem) -> Unit) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var sort by remember { mutableStateOf(DownloadSort.NEWEST_ARRIVALS) }
    var sortExpanded by remember { mutableStateOf(false) }
    var advancedFilters by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var itemPendingRemoval by remember { mutableStateOf<DownloadItem?>(null) }
    val todayStart = remember { startOfLocalDay(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000L)
            DownloadCoordinator.synchronizeDownloadsFolder()
        }
    }

    val activeCount = items.count {
        it.status in setOf(DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING)
    }
    val queuedCount = items.count { it.awaitingChoice || it.status == DownloadStatus.QUEUED }
    val completedCount = items.count { it.status == DownloadStatus.COMPLETED }
    val failedCount = items.count { it.status in setOf(DownloadStatus.FAILED, DownloadStatus.STOPPED) }
    val counts = mapOf(
        DownloadFilter.ALL to items.size,
        DownloadFilter.ACTIVE to items.count {
            it.status in setOf(DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING, DownloadStatus.PAUSED)
        },
        DownloadFilter.QUEUED to queuedCount,
        DownloadFilter.DOWNLOADING to activeCount,
        DownloadFilter.COMPLETED to completedCount,
        DownloadFilter.FAILED to failedCount,
        DownloadFilter.VIDEO to items.count { it.awaitingChoice || it.mode == DownloadMode.VIDEO },
        DownloadFilter.AUDIO to items.count { it.awaitingChoice || it.mode == DownloadMode.AUDIO },
        DownloadFilter.TODAY to items.count { it.createdAt >= todayStart }
    )

    val visibleItems = remember(items, search, filter, todayStart, sort) {
        val matches = items.filter { item ->
            val matchesFilter = when (filter) {
                DownloadFilter.ALL -> true
                DownloadFilter.ACTIVE -> item.status in setOf(
                    DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING, DownloadStatus.PAUSED
                )
                DownloadFilter.QUEUED -> item.awaitingChoice || item.status == DownloadStatus.QUEUED
                DownloadFilter.DOWNLOADING -> item.status in setOf(
                    DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING
                )
                DownloadFilter.COMPLETED -> item.status == DownloadStatus.COMPLETED
                DownloadFilter.FAILED -> item.status in setOf(DownloadStatus.FAILED, DownloadStatus.STOPPED)
                DownloadFilter.VIDEO -> item.awaitingChoice || item.mode == DownloadMode.VIDEO
                DownloadFilter.AUDIO -> item.awaitingChoice || item.mode == DownloadMode.AUDIO
                DownloadFilter.TODAY -> item.createdAt >= todayStart
            }
            val query = search.trim()
            matchesFilter && (
                query.isBlank() ||
                    displayTitle(item).contains(query, ignoreCase = true) ||
                    item.sourceName.contains(query, ignoreCase = true) ||
                    item.url.contains(query, ignoreCase = true)
                )
        }
        when (sort) {
            DownloadSort.NEWEST_ARRIVALS ->
                matches.sortedWith(compareByDescending<DownloadItem> { it.createdAt }.thenByDescending { it.id })
            DownloadSort.NAME_ASC ->
                matches.sortedBy { displayTitle(it).lowercase(Locale.ROOT) }
            DownloadSort.NAME_DESC ->
                matches.sortedByDescending { displayTitle(it).lowercase(Locale.ROOT) }
            DownloadSort.DATE_NEWEST ->
                matches.sortedByDescending { downloadTimestamp(it) }
            DownloadSort.DATE_OLDEST ->
                matches.sortedBy { downloadTimestamp(it) }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Ink)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("YOUR LIBRARY", color = Yellow, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.7.sp)
                Text("Downloads", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
            Box(
                Modifier.premiumInset(RoundedCornerShape(11.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text("${visibleItems.size} / ${items.size}", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { helpOpen = true }, modifier = Modifier.size(38.dp).premiumSurface(CircleShape, 3.dp)) { Icon(Icons.Default.Info, "Downloads help", tint = TextMuted) }
            IconButton(onClick = { advancedFilters = !advancedFilters }, modifier = Modifier.size(38.dp).premiumSurface(CircleShape, 3.dp)) {
                Icon(
                    if (advancedFilters) Icons.Default.Tune else Icons.Default.FilterList,
                    "More filters",
                    tint = Cyan
                )
            }
            IconButton(onClick = { showClearConfirmation = true }, enabled = items.isNotEmpty(), modifier = Modifier.size(38.dp).premiumSurface(CircleShape, 3.dp)) {
                Icon(Icons.Default.DeleteSweep, "Clear downloads list", tint = if (items.isNotEmpty()) Red else TextMuted)
            }
        }
        HorizontalDivider(color = Line.copy(alpha = .55f))

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth().premiumInset(RoundedCornerShape(15.dp)),
            placeholder = { Text("Search titles, websites, or links", color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Cyan) },
            trailingIcon = {
                if (search.isNotBlank()) {
                    IconButton(onClick = { search = "" }) {
                        Icon(Icons.Default.Close, "Clear search", tint = TextMuted)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                focusedBorderColor = Yellow,
                unfocusedBorderColor = Line,
                cursorColor = Cyan,
                focusedContainerColor = Inset,
                unfocusedContainerColor = Inset
            )
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DownloadFilterChip(filter, DownloadFilter.ALL, counts) { filter = it }
            DownloadFilterChip(filter, DownloadFilter.ACTIVE, counts) { filter = it }
            DownloadFilterChip(filter, DownloadFilter.QUEUED, counts) { filter = it }
            DownloadFilterChip(filter, DownloadFilter.COMPLETED, counts) { filter = it }
            DownloadFilterChip(filter, DownloadFilter.FAILED, counts) { filter = it }
        }

        AnimatedVisibility(visible = advancedFilters) {
            Column(
                Modifier.fillMaxWidth()
                    .premiumSurface(RoundedCornerShape(16.dp), 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Panel)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("FILE TYPE & DATE", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    DownloadFilterChip(filter, DownloadFilter.VIDEO, counts) { filter = it }
                    DownloadFilterChip(filter, DownloadFilter.AUDIO, counts) { filter = it }
                    DownloadFilterChip(filter, DownloadFilter.TODAY, counts) { filter = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SORT BY", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { sortExpanded = true }) {
                            Icon(Icons.Default.Sort, null, tint = Cyan, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(sort.label, color = Cyan, fontSize = 11.sp)
                            Icon(Icons.Default.ArrowDropDown, null, tint = Cyan)
                        }
                        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                            DownloadSort.values().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label, color = TextMain) },
                                    onClick = { sort = option; sortExpanded = false }
                                )
                            }
                        }
                    }
                }
                Text(
                    if (sort == DownloadSort.NEWEST_ARRIVALS)
                        "A download keeps its arrival position when its status changes."
                    else "Sort the list without changing saved downloads or queue state.",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
        }

        if (filter != DownloadFilter.ALL || search.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Showing ${visibleItems.size} items", color = TextMuted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { search = ""; filter = DownloadFilter.ALL },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Clear filters", color = Cyan, fontSize = 11.sp)
                }
            }
        }

        if (helpOpen) {
            AlertDialog(
                onDismissRequest = { helpOpen = false },
                containerColor = Panel,
                title = { Text("Downloads help", color = TextMain) },
                text = {
                    Text(
                        "New links always appear at the top. Status filters show active, queued, completed, or failed items. Use More filters for Video, Audio, and Today. Search checks titles, websites, and source links. Removing an entry never deletes the media file. Missing or moved files are reconciled with your selected folder.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = { TextButton(onClick = { helpOpen = false }) { Text("Got it", color = Cyan) } }
            )
        }

        if (showClearConfirmation) {
            AlertDialog(
                onDismissRequest = { showClearConfirmation = false },
                containerColor = Panel,
                title = { Text("Clear downloads list?", color = TextMain) },
                text = {
                    Text(
                        "This removes the records and stops active downloads. Files already saved in your Downloads folder will stay there.",
                        color = TextMuted
                    )
                },
                confirmButton = {
                    TextButton(onClick = { DownloadCoordinator.clearAll(); showClearConfirmation = false }) {
                        Text("Clear list", color = Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
                }
            )
        }

        itemPendingRemoval?.let { item ->
            AlertDialog(
                onDismissRequest = { itemPendingRemoval = null },
                containerColor = Panel,
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 0.dp,
                icon = { Icon(Icons.Default.DeleteOutline, null, tint = Red, modifier = Modifier.size(24.dp)) },
                title = { Text("Remove this entry?", color = TextMain, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "\"${displayTitle(item)}\" will be removed from Download Studio. The saved media file will not be deleted.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { DownloadCoordinator.remove(item.id); itemPendingRemoval = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Remove entry", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { itemPendingRemoval = null },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
                    ) {
                        Text("Keep entry")
                    }
                }
            )
        }

        when {
            items.isEmpty() -> EmptyState(
                "No downloads yet",
                "Paste a link or share a supported media URL from another app.",
                Icons.Default.CloudDownload
            )
            visibleItems.isEmpty() -> EmptyState(
                "No matching downloads",
                "Try another filter or search. Your downloads are still saved.",
                Icons.Default.Search
            )
            else -> LazyColumn(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .background(Ink),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 18.dp)
            ) {
                lazyItems(visibleItems, key = { it.id }) { item ->
                    DownloadRow(item, onClick = { onOpenItem(item) }, onRemoveRequest = { itemPendingRemoval = item })
                }
            }
        }
    }
}

@Composable
private fun DownloadFilterChip(
    selectedFilter: DownloadFilter,
    option: DownloadFilter,
    counts: Map<DownloadFilter, Int>,
    onSelect: (DownloadFilter) -> Unit
) {
    val selected = selectedFilter == option
    val tint = when (option) {
        DownloadFilter.COMPLETED -> Green
        DownloadFilter.FAILED -> Red
        DownloadFilter.QUEUED -> Violet
        DownloadFilter.VIDEO -> Yellow
        DownloadFilter.AUDIO -> Green
        else -> Cyan
    }
    FilterChip(
        selected = selected,
        onClick = { onSelect(if (selected) DownloadFilter.ALL else option) },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(option.label, maxLines = 1)
                Text(
                    counts[option]?.toString() ?: "0",
                    color = if (selected) tint else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = tint.copy(alpha = .16f),
            selectedLabelColor = tint,
            containerColor = Panel,
            labelColor = TextMuted
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Line,
            selectedBorderColor = tint.copy(alpha = .65f)
        )
    )
}

private fun startOfLocalDay(timestamp: Long): Long {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

@Composable
private fun DownloadRow(item: DownloadItem, onClick: () -> Unit, onRemoveRequest: (() -> Unit)? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val color = if (item.awaitingChoice) Violet else when (item.status) {
        DownloadStatus.COMPLETED -> Green
        DownloadStatus.FAILED -> Red
        DownloadStatus.PAUSED -> Violet
        DownloadStatus.QUEUED -> TextMuted
        else -> Cyan
    }
    Card(
        modifier = Modifier.fillMaxWidth().premiumSurface(RoundedCornerShape(19.dp), 7.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(19.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line.copy(alpha = .65f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val thumbnailUrl = item.thumbnailUrl.takeIf { it.isNotBlank() } ?: youtubeThumbnailUrl(item.url)
                var thumbnailFailed by remember(item.id, thumbnailUrl) { mutableStateOf(false) }
                Box(Modifier.size(width = 100.dp, height = 64.dp).clip(RoundedCornerShape(12.dp)).background(if (item.mode == DownloadMode.VIDEO) Yellow.copy(alpha = .13f) else Green.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
                    if (thumbnailUrl != null && !thumbnailFailed) {
                        AsyncImage(model = thumbnailUrl, contentDescription = "YouTube thumbnail", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { thumbnailFailed = true })
                    } else {
                        Icon(if (item.mode == DownloadMode.VIDEO) Icons.Default.PlayCircle else Icons.Default.MusicNote, null, tint = if (item.mode == DownloadMode.VIDEO) Yellow else Green)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(displayTitle(item), color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        val isVideo = item.mode == DownloadMode.VIDEO
                        Box(Modifier.clip(CircleShape).background((if (item.awaitingChoice) Violet else if (isVideo) Yellow else Green).copy(alpha = .16f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                            Text(if (item.awaitingChoice) "LINK" else if (isVideo) "VIDEO" else "AUDIO", color = if (item.awaitingChoice) Violet else if (isVideo) Yellow else Green, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(item.sourceName.ifBlank { mediaSiteName(item.url) }, color = TextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.clip(CircleShape).background(color.copy(alpha = .15f)).padding(horizontal = 9.dp, vertical = 5.dp)) {
                    Text(
                        when {
                            item.awaitingChoice -> "CHOOSE FORMAT"
                            item.status == DownloadStatus.COMPLETED -> "Saved"
                            else -> friendlyStatus(item.status)
                        },
                        color = color,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (!item.awaitingChoice) {
                    var actionsExpanded by remember(item.id) { mutableStateOf(false) }
                    Box {
                    IconButton(onClick = { actionsExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, "Download actions", tint = TextMuted)
                    }
                    DropdownMenu(
                        expanded = actionsExpanded,
                        onDismissRequest = { actionsExpanded = false },
                        modifier = Modifier.widthIn(min = 212.dp),
                        shape = RoundedCornerShape(15.dp),
                        containerColor = Panel2,
                        tonalElevation = 0.dp,
                        shadowElevation = 14.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
                    ) {
                        if (item.status == DownloadStatus.FAILED || item.status == DownloadStatus.STOPPED) {
                            DropdownMenuItem(
                                text = { Text("Retry download", color = TextMain, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Refresh, null, tint = Cyan) },
                                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 3.dp),
                                onClick = {
                                    actionsExpanded = false
                                    DownloadCoordinator.retry(item.id)
                                }
                            )
                        }
                        if (item.status == DownloadStatus.STARTING || item.status == DownloadStatus.DOWNLOADING) {
                            DropdownMenuItem(
                                text = { Text("Pause download", color = TextMain, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Pause, null, tint = Cyan) },
                                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 3.dp),
                                onClick = {
                                    actionsExpanded = false
                                    DownloadCoordinator.pause(item.id)
                                }
                            )
                        }
                        if (item.status == DownloadStatus.PAUSED) {
                            DropdownMenuItem(
                                text = { Text("Resume download", color = TextMain, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = Green) },
                                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 3.dp),
                                onClick = {
                                    actionsExpanded = false
                                    DownloadCoordinator.resume(item.id)
                                }
                            )
                        }
                        if (item.status in setOf(
                                DownloadStatus.QUEUED,
                                DownloadStatus.STARTING,
                                DownloadStatus.DOWNLOADING,
                                DownloadStatus.PAUSED,
                                DownloadStatus.PROCESSING
                            )
                        ) {
                            DropdownMenuItem(
                                text = { Text("Stop download", color = Red, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Stop, null, tint = Red) },
                                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 3.dp),
                                onClick = {
                                    actionsExpanded = false
                                    DownloadCoordinator.stop(item.id)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Copy source link", color = TextMain, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = Cyan) },
                            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 3.dp),
                            onClick = {
                                actionsExpanded = false
                                clipboard.setText(AnnotatedString(item.url))
                                Toast.makeText(context, "Source link copied.", Toast.LENGTH_SHORT).show()
                            }
                        )
                        if (item.status == DownloadStatus.COMPLETED) {
                            DropdownMenuItem(
                                text = { Text("View in folder", color = TextMain, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null, tint = Cyan) },
                                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 3.dp),
                                onClick = {
                                    actionsExpanded = false
                                    openDownloadedFolder(context, item)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share file", color = TextMain, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Share, null, tint = Violet) },
                                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 3.dp),
                                onClick = {
                                    actionsExpanded = false
                                    shareDownloadedFile(context, item)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Remove from downloads", color = Red, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null, tint = Red) },
                            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 3.dp),
                            onClick = { actionsExpanded = false; onRemoveRequest?.invoke() }
                        )
                    }
                    }
                }
            }
            if (item.awaitingChoice && onRemoveRequest != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Choose a format", color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { DownloadCoordinator.chooseLinkDownload(item.id, DownloadMode.VIDEO) },
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                            .background(Yellow.copy(alpha = .18f))
                            .border(1.dp, Yellow.copy(alpha = .55f), RoundedCornerShape(12.dp))
                    ) { Icon(Icons.Default.VideoLibrary, "Download video", tint = Yellow, modifier = Modifier.size(21.dp)) }
                    IconButton(
                        onClick = { DownloadCoordinator.chooseLinkDownload(item.id, DownloadMode.AUDIO) },
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                            .background(Green.copy(alpha = .16f))
                            .border(1.dp, Green.copy(alpha = .5f), RoundedCornerShape(12.dp))
                    ) { Icon(Icons.Default.MusicNote, "Download audio", tint = Green, modifier = Modifier.size(21.dp)) }
                    IconButton(
                        onClick = { onRemoveRequest?.invoke() },
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                            .background(Red.copy(alpha = .14f))
                            .border(1.dp, Red.copy(alpha = .45f), RoundedCornerShape(12.dp))
                    ) { Icon(Icons.Default.DeleteOutline, "Discard link", tint = Red, modifier = Modifier.size(20.dp)) }
                }
                val details = buildList {
                    if (item.durationSeconds > 0L) add(formatPreviewDuration(item.durationSeconds))
                    if (item.estimatedSizeBytes > 0L) add(formatBytesLabel(item.estimatedSizeBytes))
                }.joinToString(" · ")
                Text(
                    details.ifBlank { "Link accepted · Details may still be loading" },
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }

            when (item.status) {
                DownloadStatus.STARTING -> {
                    Text("Preparing download…", color = TextMuted, fontSize = 10.sp)
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        color = Yellow,
                        trackColor = Line
                    )
                }
                DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED -> {
                    LinearProgressIndicator(
                        progress = { item.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        color = if (item.status == DownloadStatus.PAUSED) Violet else Yellow,
                        trackColor = Line
                    )
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("${(item.progress * 100).toInt()}%", color = TextMain, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (item.totalSize != "—") "${item.downloaded} / ${item.totalSize}" else item.downloaded,
                            color = TextMuted,
                            fontSize = 9.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Speed  ${item.speed}", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        Text("ETA  ${item.eta}", color = TextMuted, fontSize = 9.sp)
                    }
                }
                DownloadStatus.PROCESSING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PROCESSING", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
                        Spacer(Modifier.weight(1f))
                        Text(item.totalSize.takeIf { it != "—" }?.let { "Download received · $it" } ?: "Download received", color = TextMuted, fontSize = 9.sp)
                    }
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        color = Cyan,
                        trackColor = Line
                    )
                    Text(item.processingStage.ifBlank { "Finalizing media…" }, color = TextMain, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                DownloadStatus.COMPLETED -> {
                    Text(item.outputPath.substringAfterLast('/').ifBlank { "Saved to Downloads" }, color = Green, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                else -> {
                    if (item.error.isNotBlank()) {
                        Text(friendlyError(item.error), color = Red, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } else if (!item.awaitingChoice) {
                        Text(if (item.mode == DownloadMode.VIDEO) "Best available video + audio" else "Best available audio · MP3", color = TextMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

private data class FolderOpenTarget(
    val label: String,
    val component: ComponentName,
    val intent: Intent
)

private const val FOLDER_MANAGER_PREFERENCE = "preferred_folder_manager_component"

private fun openDownloadedFolder(context: android.content.Context, item: DownloadItem) {
    val folder = StorageAccess.findDownloadedFolder(context, item.outputPath)
    if (folder == null) {
        Toast.makeText(context, "The download folder could not be found.", Toast.LENGTH_LONG).show()
        return
    }

    val configuredDownloadsPath = StorageAccess.destinationPath(context)?.trimEnd('/')
    val absolutePath = item.outputPath.substringBeforeLast('/', "").trimEnd('/')
    if (configuredDownloadsPath.isNullOrBlank() || absolutePath != configuredDownloadsPath) {
        Toast.makeText(
            context,
            "This download is not inside your currently configured Downloads folder.",
            Toast.LENGTH_LONG
        ).show()
        return
    }
    val folderLabel = folder.name ?: "Download folder"
    val directoryIntent = Intent(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        setDataAndType(folder.uri, DocumentsContract.Document.MIME_TYPE_DIR)
        putExtra("org.openintents.extra.ABSOLUTE_PATH", absolutePath)
        clipData = ClipData.newUri(context.contentResolver, folderLabel, folder.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val packageManager = context.packageManager
    val targets = mutableListOf<FolderOpenTarget>()

    fun addTarget(component: ComponentName, targetIntent: Intent, fallbackLabel: String) {
        if (targets.any { it.component == component }) return
        val label = runCatching {
            packageManager.getActivityInfo(component, 0).loadLabel(packageManager).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallbackLabel
        targets.add(FolderOpenTarget(label, component, targetIntent))
    }

    // Include apps that explicitly advertise support for the standard directory-view intent.
    val directoryHandlers = runCatching {
        packageManager.queryIntentActivities(directoryIntent, PackageManager.MATCH_DEFAULT_ONLY)
    }.getOrDefault(emptyList())
    directoryHandlers.forEach { resolved ->
        val component = ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
        addTarget(component, Intent(directoryIntent).setComponent(component), resolved.loadLabel(packageManager).toString())
    }

    // Some file managers do not register the directory MIME type. Keep them
    // visible through their installed launcher activity and pass the folder
    // path/URI using the handoff that was verified on the user's device.
    if (targets.none { it.component.packageName == "com.sec.android.app.myfiles" }) {
        val component = packageManager.getLaunchIntentForPackage("com.sec.android.app.myfiles")?.component
        if (component != null) {
            val targetIntent = Intent(Intent.ACTION_MAIN).setComponent(component).apply {
                putExtra("com.sec.android.app.myfiles.external.ui.folderPath", absolutePath)
                putExtra("org.openintents.extra.ABSOLUTE_PATH", absolutePath)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder.uri)
                clipData = ClipData.newUri(context.contentResolver, folderLabel, folder.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addTarget(component, targetIntent, "Samsung My Files")
        }
    }

    if (targets.none { it.component.packageName == "com.alphainventor.filemanager" }) {
        val component = packageManager.getLaunchIntentForPackage("com.alphainventor.filemanager")?.component
        if (component != null) {
            val targetIntent = Intent(Intent.ACTION_VIEW).setComponent(component).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(folder.uri, DocumentsContract.Document.MIME_TYPE_DIR)
                putExtra("org.openintents.extra.ABSOLUTE_PATH", absolutePath)
                clipData = ClipData.newUri(context.contentResolver, folderLabel, folder.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addTarget(component, targetIntent, "File Manager+")
        }
    }

    // Prefer Samsung My Files, then File Manager+, then other compatible managers.
    val preferredPackages = listOf("com.sec.android.app.myfiles", "com.alphainventor.filemanager")
    val orderedTargets = targets.sortedBy { target ->
        val index = preferredPackages.indexOf(target.component.packageName)
        if (index >= 0) index else preferredPackages.size
    }

    val preferences = context.getSharedPreferences("download_studio_ui", android.content.Context.MODE_PRIVATE)
    val savedComponent = preferences.getString(FOLDER_MANAGER_PREFERENCE, null)
        ?.let { ComponentName.unflattenFromString(it) }
    if (savedComponent != null) {
        val savedTarget = orderedTargets.firstOrNull { it.component == savedComponent }
        if (savedTarget != null && startFolderTarget(context, savedTarget)) return
        // The remembered app was removed or could no longer open the folder.
        preferences.edit().remove(FOLDER_MANAGER_PREFERENCE).apply()
    }

    if (orderedTargets.isNotEmpty()) {
        pendingFolderManagerTargets = orderedTargets
        return
    }

    // Final fallback: show the system document picker at the selected folder.
    runCatching {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder.uri)
        }
        context.startActivity(picker)
    }.onFailure {
        Toast.makeText(context, "No compatible file manager could open this folder.", Toast.LENGTH_LONG).show()
    }
}

private var pendingFolderManagerTargets by mutableStateOf<List<FolderOpenTarget>?>(null)

@Composable
private fun FolderManagerChoiceDialog(
    targets: List<FolderOpenTarget>,
    onDismiss: () -> Unit,
    onChoice: (FolderOpenTarget, Boolean) -> Unit
) {
    var selectedIndex by remember(targets) { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Open folder with",
                color = TextMain,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Choose a file manager for your configured Downloads folder.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    targets.forEachIndexed { index, target ->
                        val selected = selectedIndex == index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) Yellow.copy(alpha = 0.14f) else Panel2)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) Yellow else Line,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { selectedIndex = index }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { selectedIndex = index },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Cyan,
                                    unselectedColor = TextMuted
                                )
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    target.label,
                                    color = TextMain,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    target.component.packageName,
                                    color = TextMuted,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onChoice(targets[selectedIndex], true) },
                colors = ButtonDefaults.buttonColors(containerColor = Yellow),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Always", color = Color.White, fontSize = 12.sp)
            }
        },
        dismissButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextMuted, fontSize = 11.sp)
                }
                TextButton(onClick = { onChoice(targets[selectedIndex], false) }) {
                    Text("Just once", color = Cyan, fontSize = 11.sp)
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = Panel,
        titleContentColor = TextMain,
        textContentColor = TextMuted,
        tonalElevation = 0.dp
    )
}

private fun startFolderTarget(context: android.content.Context, target: FolderOpenTarget): Boolean =
    runCatching {
        context.startActivity(Intent(target.intent))
        true
    }.getOrElse {
        Toast.makeText(context, "Could not open the download folder with ${target.label}.", Toast.LENGTH_LONG).show()
        false
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
        Box(Modifier.size(68.dp).premiumSurface(RoundedCornerShape(22.dp), 6.dp).clip(RoundedCornerShape(22.dp)).background(Panel2), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Cyan, modifier = Modifier.size(28.dp)) }
        Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(detail, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.widthIn(max = 270.dp))
    }
}

@Composable
private fun SettingsScreen(setupRequired: Boolean = false) {
    val context = LocalContext.current
    var basePath by remember { mutableStateOf(QueueStore.basePath(context).orEmpty()) }
    var previousBasePath by remember { mutableStateOf(QueueStore.previousDestinationPath(context).orEmpty()) }
    var treeGranted by remember { mutableStateOf(StorageAccess.isDownloadLocationConfigured(context)) }
    var maxConcurrent by remember { mutableIntStateOf(QueueStore.maxConcurrent(context)) }
    var termuxInstalled by remember { mutableStateOf(TermuxBridge.isInstalled(context)) }
    var termuxPermission by remember { mutableStateOf(TermuxBridge.hasRunPermission(context)) }
    var notificationsAllowed by remember { mutableStateOf(LinkStatusNotification.canPost(context)) }
    var engineStatus by remember { mutableStateOf("Not checked") }

    LaunchedEffect(Unit) {
        while (isActive) {
            notificationsAllowed = LinkStatusNotification.canPost(context)
            delay(750)
        }
    }
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
                previousBasePath = path
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
                Text("CONTROL CENTER", color = Yellow, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.7.sp)
                Text("Settings", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("Manage your device, storage and download experience.", color = TextMuted, fontSize = 12.sp)
            }
            if (setupRequired) {
                Card(modifier = Modifier.premiumSurface(RoundedCornerShape(18.dp), 7.dp), colors = CardDefaults.cardColors(containerColor = Panel2), border = androidx.compose.foundation.BorderStroke(1.dp, Yellow.copy(alpha = .55f)), shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("LET'S FINISH SETUP", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Complete these three steps in order. Your downloads and saved files are not affected by setup changes.", color = TextMain, fontSize = 11.sp, lineHeight = 16.sp)
                        StatusLine("1. Choose a download folder", treeGranted, if (treeGranted) "Complete" else "Required")
                        StatusLine("2. Install Termux", termuxInstalled, if (termuxInstalled) "Detected" else "Required")
                        StatusLine("3. Allow Run commands in Termux", termuxPermission, if (termuxPermission) "Granted" else "Required")
                    }
                }
            } else {
                Card(modifier = Modifier.premiumSurface(RoundedCornerShape(18.dp), 7.dp), colors = CardDefaults.cardColors(containerColor = Panel2), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Green.copy(alpha = .4f)), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Green)
                        Column {
                            Text("Download Studio is ready", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Your download folder and Termux connection are configured.", color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        item {
            SettingsCard(
                "NOTIFICATIONS",
                "Allow Download Studio to confirm shared links in your Android notification shade."
            ) {
                StatusLine(
                    "Shared-link notifications",
                    notificationsAllowed,
                    if (notificationsAllowed) "Enabled" else "Permission or notifications disabled"
                )
                Button(
                    onClick = {
                        val settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        runCatching { context.startActivity(settingsIntent) }
                            .onFailure {
                                Toast.makeText(context, "Open Android Settings → Apps → Download Studio → Notifications.", Toast.LENGTH_LONG).show()
                            }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Panel2)
                ) {
                    Icon(Icons.Default.NotificationsActive, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (notificationsAllowed) "Manage notification settings" else "Enable notifications")
                }
            }
        }
        item {
            SettingsCard("DOWNLOAD LOCATION", "Choose the Download Studio folder inside Downloads (create it in the Files app first), or select another folder.") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Folder, tint = Cyan, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text(if (treeGranted) "Downloads folder connected" else "Folder access required", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(
                            when {
                                basePath.isNotBlank() -> basePath
                                previousBasePath.isNotBlank() -> "Re-select previous folder: $previousBasePath"
                                else -> "Select Download/Download Studio, or another writable folder"
                            },
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2)) {
                    Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose folder")
                }
                Text(
                    "Queue snapshots are also kept in this folder's DownloadStudioState directory. After reinstalling, choose this same folder again to reconnect saved files and active download logs. Android cloud/device backup can restore the queue too when system backup is enabled.",
                    color = TextMuted,
                    fontSize = 9.sp,
                    lineHeight = 14.sp
                )
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
                Slider(value = maxConcurrent.toFloat(), onValueChange = { maxConcurrent = it.toInt().coerceIn(1, 2) }, valueRange = 1f..2f, steps = 0, onValueChangeFinished = { DownloadCoordinator.setMaxConcurrent(maxConcurrent) }, colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Yellow, inactiveTrackColor = Line))
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
                            StorageAccess.deleteState(context, "DownloadStudioState/engine.txt")
                            val result = TermuxBridge.runShell(context, TermuxBridge.engineCheckScript(dest), "Download Studio · engine check", true)
                            if (result.isFailure) engineStatus = result.exceptionOrNull()?.message ?: "Could not check Termux"
                        }
                    }) { Text("Check health") }
                }
                OutlinedButton(onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage(TermuxBridge.TERMUX_PACKAGE)
                    if (intent != null) context.startActivity(intent) else Toast.makeText(context, "Install Termux from F-Droid or its official GitHub releases.", Toast.LENGTH_LONG).show()
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Terminal, null); Spacer(Modifier.width(8.dp)); Text("Open Termux") }
                Button(onClick = {
                    clipboard.setText(AnnotatedString(TermuxBridge.setupScript()))
                    Toast.makeText(context, "Setup command copied. Paste it into Termux and approve storage access.", Toast.LENGTH_LONG).show()
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Yellow)) {
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
                Text("Version 0.1.11", color = TextMuted, fontSize = 10.sp)
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
                    engineStatus = report.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.joinToString(" · ")
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
    Card(modifier = Modifier.premiumSurface(RoundedCornerShape(20.dp), 7.dp), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line.copy(alpha = .65f)), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp), content = {
            Text(title, color = Yellow, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
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

private fun extractFirstUrl(raw: String): String? =
    Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
        .find(raw)?.value?.trimEnd('.', ',', ')', ']', '}', '"', '\'')

private fun friendlyStatus(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "Waiting"
    DownloadStatus.STARTING -> "Preparing"
    DownloadStatus.DOWNLOADING -> "Downloading"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.PROCESSING -> "Processing"
    DownloadStatus.COMPLETED -> "Saved"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.STOPPED -> "Stopped"
}

private fun friendlyError(raw: String): String {
    val value = raw.trim()
    val message = value.lowercase(Locale.ROOT)
    return when {
        message.contains("unsupported url") ->
            "This website or media type isn't supported by the download engine yet."
        message.contains("private video") || message.contains("login required") ||
            message.contains("sign in") || message.contains("not authorized") ||
            message.contains("http error 401") || message.contains("http error 403") ->
            "This media is restricted or requires access the downloader doesn't have."
        message.contains("video unavailable") || message.contains("http error 404") ||
            message.contains("not found") ->
            "This video may have been removed or is no longer available."
        message.contains("timed out") || message.contains("timeout") ||
            message.contains("connection reset") || message.contains("network is unreachable") ->
            "The website took too long to respond. Check your connection and try again."
        message.contains("http error 429") || message.contains("too many requests") ->
            "The website is temporarily limiting requests. Wait a little and try again."
        message.contains("termux") || message.contains("run commands") ->
            "The download engine couldn't start. Open Settings and check the Termux connection."
        message.contains("ffmpeg") || message.contains("postprocessing") ->
            "The media was found, but final processing failed. Check FFmpeg in Settings."
        message.contains("yt-dlp exited") || message.contains("exit code") ->
            "The download couldn't be completed. Check your connection or try again."
        value.isBlank() -> "Something went wrong. Please try again."
        else -> value.take(220)
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

private data class PreviewMetadata(
    val title: String = "",
    val thumbnailUrl: String = "",
    val durationSeconds: Long = 0L
)

private fun formatBytesLabel(bytes: Long): String {
    if (bytes <= 0L) return "Size unavailable"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return "%.1f %s".format(value, units[index])
}

private fun mediaSiteName(rawUrl: String): String {
    val host = runCatching { Uri.parse(rawUrl).host?.lowercase(Locale.ROOT)?.removePrefix("www.") }.getOrNull().orEmpty()
    return when {
        host == "youtu.be" || host.endsWith("youtube.com") -> "YouTube"
        host == "facebook.com" || host.endsWith(".facebook.com") || host.endsWith("fb.watch") -> "Facebook"
        host == "instagram.com" || host.endsWith(".instagram.com") -> "Instagram"
        host == "tiktok.com" || host.endsWith(".tiktok.com") -> "TikTok"
        host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com") -> "X"
        host == "vimeo.com" || host.endsWith(".vimeo.com") -> "Vimeo"
        host == "reddit.com" || host.endsWith(".reddit.com") -> "Reddit"
        host == "pinterest.com" || host.endsWith(".pinterest.com") -> "Pinterest"
        host.isBlank() -> "Website"
        else -> host.removePrefix("m.").replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}

private fun safePreviewImageUrl(raw: String): String {
    val value = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
    val normalized = if (value.startsWith("http://", true)) "https://" + value.substringAfter("://") else value
    return normalized.takeIf { runCatching { URI(it).scheme == "https" && !URI(it).host.isNullOrBlank() && URI(it).userInfo == null }.getOrDefault(false) }.orEmpty()
}

private fun parsePreviewResult(raw: String): PreviewMetadata {
    val lines = raw.lineSequence().toList()
    val title = lines.lastOrNull { it.startsWith("DS_TITLE:") }?.substringAfter("DS_TITLE:")?.trim().orEmpty()
    val thumbnail = lines.lastOrNull { it.startsWith("DS_THUMBNAIL:") }?.substringAfter("DS_THUMBNAIL:")?.trim()
        ?.let(::safePreviewImageUrl).orEmpty()
    val duration = lines.lastOrNull { it.startsWith("DS_DURATION:") }?.substringAfter("DS_DURATION:")?.trim()
        ?.toDoubleOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L
    return PreviewMetadata(title = title, thumbnailUrl = thumbnail, durationSeconds = duration)
}

/**
 * Reuse yt-dlp's site extractor for a metadata-only lookup. It is the same extractor
 * that later supplies DS_THUMBNAIL during a real download, but --skip-download ensures
 * this preview step never downloads the media file.
 */
private suspend fun resolveDownloaderPreview(
    context: android.content.Context,
    rawUrl: String
): PreviewMetadata {
    val destination = StorageAccess.destinationPath(context) ?: return PreviewMetadata()
    if (!TermuxBridge.isInstalled(context) || !TermuxBridge.hasRunPermission(context)) return PreviewMetadata()

    val requestId = java.util.UUID.randomUUID().toString().replace("-", "")
    val statePath = "DownloadStudioState/preview-$requestId.txt"
    val script = TermuxBridge.previewMetadataScript(requestId, rawUrl, destination)
    try {
        val launch = withContext(Dispatchers.IO) {
            TermuxBridge.runShell(context, script, "Download Studio · preview lookup", true)
        }
        if (launch.isFailure) return PreviewMetadata()

        repeat(62) {
            delay(400)
            val state = withContext(Dispatchers.IO) { StorageAccess.readState(context, statePath) }
            if (!state.isNullOrBlank() && state.contains("DS_DONE")) {
                val parsed = parsePreviewResult(state)
                val safeThumbnail = if (parsed.thumbnailUrl.isBlank()) "" else withContext(Dispatchers.IO) {
                    parsed.thumbnailUrl.takeIf { isPublicHttpsUrl(it) }.orEmpty()
                }
                return parsed.copy(thumbnailUrl = safeThumbnail)
            }
        }
        return PreviewMetadata()
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            StorageAccess.deleteState(context, statePath)
        }
    }
}

private fun resolvePageMetadata(rawUrl: String): PreviewMetadata = runCatching {
    var currentUrl = rawUrl
    for (attempt in 0 until 5) {
        if (!isPublicHttpsUrl(currentUrl)) return@runCatching PreviewMetadata()
        val response = Jsoup.connect(currentUrl)
            .userAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .timeout(8_000)
            .maxBodySize(1_000_000)
            .followRedirects(false)
            .ignoreHttpErrors(true)
            .execute()
        if (response.statusCode() in 300..399) {
            val location = response.header("location") ?: return@runCatching PreviewMetadata()
            currentUrl = URI(currentUrl).resolve(location).toString()
            continue
        }
        if (response.statusCode() !in 200..299) return@runCatching PreviewMetadata()
        val document = response.parse()
        val title = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=twitter:title]")?.attr("content"),
            document.selectFirst("meta[name=title]")?.attr("content"),
            document.title()
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        val candidates = listOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[property=og:image:secure_url]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image:src]")?.attr("content"),
            document.selectFirst("meta[itemprop=image]")?.attr("content"),
            document.selectFirst("link[rel=image_src]")?.attr("href"),
            document.selectFirst("video[poster]")?.attr("poster")
        )
        var thumbnail = ""
        for (candidate in candidates) {
            if (candidate.isNullOrBlank()) continue
            val resolved = safePreviewImageUrl(URI(currentUrl).resolve(candidate.trim()).toString())
            if (resolved.isNotBlank() && isPublicHttpsUrl(resolved)) {
                thumbnail = resolved
                break
            }
        }
        val duration = listOf(
            document.selectFirst("meta[property=video:duration]")?.attr("content"),
            document.selectFirst("meta[itemprop=duration]")?.attr("content")
        ).firstNotNullOfOrNull { it?.trim()?.toDoubleOrNull()?.toLong() }?.coerceAtLeast(0L) ?: 0L
        return@runCatching PreviewMetadata(title, thumbnail, duration)
    }
    PreviewMetadata()
}.getOrDefault(PreviewMetadata())

private fun formatPreviewDuration(seconds: Long): String {
    if (seconds <= 0L) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%d:%02d".format(minutes, remainder)
}

@Composable
private fun VideoThumbnailPreview(rawUrl: String) {
    val context = LocalContext.current
    val cleanUrl = rawUrl.trim()
    if (!cleanUrl.startsWith("https://", ignoreCase = true) &&
        !cleanUrl.startsWith("http://", ignoreCase = true)) return

    val siteName = remember(cleanUrl) { mediaSiteName(cleanUrl) }
    var metadata by remember(cleanUrl) {
        mutableStateOf(PreviewMetadata(thumbnailUrl = youtubeThumbnailUrl(cleanUrl).orEmpty()))
    }
    var loading by remember(cleanUrl) { mutableStateOf(false) }
    var lookupFailed by remember(cleanUrl) { mutableStateOf(false) }
    var imageLoaded by remember(cleanUrl) { mutableStateOf(false) }
    var imageFailed by remember(cleanUrl) { mutableStateOf(false) }

    LaunchedEffect(cleanUrl) {
        metadata = PreviewMetadata(thumbnailUrl = youtubeThumbnailUrl(cleanUrl).orEmpty())
        loading = true
        lookupFailed = false
        imageLoaded = false
        imageFailed = false
        delay(650)

        val isSafeUrl = withContext(Dispatchers.IO) { isPublicHttpsUrl(cleanUrl) }
        if (!isSafeUrl) {
            lookupFailed = true
            loading = false
            return@LaunchedEffect
        }

        // Prefer yt-dlp metadata: it understands extractor-specific pages such as
        // Facebook watch/reel URLs that often hide their og:image from simple HTML fetches.
        val extracted = resolveDownloaderPreview(context, cleanUrl)
        if (extracted.thumbnailUrl.isNotBlank() || extracted.title.isNotBlank()) {
            metadata = PreviewMetadata(
                title = extracted.title,
                thumbnailUrl = extracted.thumbnailUrl.ifBlank { metadata.thumbnailUrl },
                durationSeconds = extracted.durationSeconds
            )
        }

        // Generic public-page metadata remains a fallback for sites not yet supported
        // by yt-dlp or pages that publish standard social preview tags.
        if (metadata.thumbnailUrl.isBlank() || metadata.title.isBlank()) {
            val page = withContext(Dispatchers.IO) { resolvePageMetadata(cleanUrl) }
            metadata = PreviewMetadata(
                title = metadata.title.ifBlank { page.title },
                thumbnailUrl = metadata.thumbnailUrl.ifBlank { page.thumbnailUrl },
                durationSeconds = metadata.durationSeconds.takeIf { it > 0L } ?: page.durationSeconds
            )
        }
        lookupFailed = metadata.thumbnailUrl.isBlank()
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .premiumInset(RoundedCornerShape(17.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (metadata.thumbnailUrl.isNotBlank() && !imageFailed) {
            AsyncImage(
                model = metadata.thumbnailUrl,
                contentDescription = "$siteName video thumbnail",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { imageLoaded = true },
                onError = { imageFailed = true }
            )
        }

        if (!imageLoaded || imageFailed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = Cyan,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    when {
                        loading -> "Finding preview from $siteName…"
                        lookupFailed || imageFailed -> "Preview image isn't available for this link"
                        else -> "Preparing preview…"
                    },
                    color = TextMuted,
                    fontSize = 11.sp
                )
                if (metadata.title.isNotBlank()) {
                    Text(
                        metadata.title,
                        color = TextMain,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .9f))))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(siteName.uppercase(Locale.ROOT), color = Cyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                    val duration = formatPreviewDuration(metadata.durationSeconds)
                    if (duration.isNotBlank()) {
                        Spacer(Modifier.weight(1f))
                        Text(duration, color = TextMain, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (metadata.title.isNotBlank()) {
                    Text(metadata.title, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
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
