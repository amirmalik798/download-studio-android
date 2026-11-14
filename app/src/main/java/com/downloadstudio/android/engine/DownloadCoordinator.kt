package com.downloadstudio.android.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.downloadstudio.android.data.DownloadItem
import com.downloadstudio.android.data.DownloadMode
import com.downloadstudio.android.data.DownloadStatus
import com.downloadstudio.android.data.QueueStore
import com.downloadstudio.android.util.StorageAccess
import com.downloadstudio.android.notifications.LinkStatusNotification
import com.downloadstudio.android.notifications.LinkMetadataResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object DownloadCoordinator {
    private const val MONITOR_INTERVAL_MS = 2_000L
    private const val START_TIMEOUT_MS = 30_000L
    private const val STALL_TIMEOUT_MS = 15 * 60 * 1_000L
    private const val MAX_DOWNLOAD_RUNTIME_MS = 6 * 60 * 60 * 1_000L
    private const val THERMAL_DEFER_THRESHOLD = 2
    private const val THERMAL_STOP_THRESHOLD = 3
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    // Queue state may be updated by several download monitors at once. Serialize
    // list mutations and persistence so one worker cannot overwrite another's
    // most recent progress or status update.
    private val stateLock = Any()
    private val initializationLock = Any()
    private val active = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val thermalDeferred = ConcurrentHashMap.newKeySet<String>()
    private val monitorServiceRequested = AtomicBoolean(false)
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    private val _thermalStatus = MutableStateFlow("Thermal guard: checking device")
    val thermalStatus: StateFlow<String> = _thermalStatus.asStateFlow()
    @Volatile private var maxConcurrent = 1
    @Volatile private var currentThermalStatus = 0
    @Volatile private var initialized = false
    @Volatile private var bulkClearing = false

    fun initialize(context: Context) {
        synchronized(initializationLock) {
            if (initialized) return
            appContext = context.applicationContext
            maxConcurrent = QueueStore.maxConcurrent(appContext)
            _items.value = QueueStore.load(appContext)
                .sortedWith(compareByDescending<DownloadItem> { it.createdAt }.thenByDescending { it.id })
            initialized = true
        }
        if (_items.value.any { it.status in MONITORED_STATUSES || (it.status == DownloadStatus.QUEUED && !it.awaitingChoice) }) {
            startMonitoringService()
        }
        registerThermalGuard()
        scope.launch {
            if (StorageAccess.isDownloadLocationConfigured(appContext) && _items.value.isEmpty()) {
                QueueStore.restoreFromExternal(appContext)?.takeIf { it.isNotEmpty() }?.let { restored ->
                    synchronized(stateLock) {
                        if (_items.value.isEmpty()) {
                            _items.value = restored
                            QueueStore.save(appContext, restored)
                        }
                    }
                }
            }
            if (StorageAccess.isDownloadLocationConfigured(appContext)) {
                reconcileCompletedFiles()
                resumeExistingDownloads(_items.value)
                QueueStore.mirrorBackup(appContext, _items.value, force = true)
            }
            if (currentThermalStatus >= THERMAL_STOP_THRESHOLD) pauseActiveForThermal()
            pump()
        }
    }

    private fun registerThermalGuard() {
        if (Build.VERSION.SDK_INT < 29) {
            _thermalStatus.value = "Thermal sensor API unavailable; single-download limit remains active."
            return
        }
        runCatching {
            val manager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            currentThermalStatus = manager.currentThermalStatus
            updateThermalLabel(currentThermalStatus)
            manager.addThermalStatusListener(appContext.mainExecutor) { status ->
                currentThermalStatus = status
                updateThermalLabel(status)
                if (status >= THERMAL_STOP_THRESHOLD) pauseActiveForThermal()
                pump()
            }
        }.onFailure { _thermalStatus.value = "Thermal status unavailable; single-download limit remains active." }
    }

    private fun updateThermalLabel(status: Int) {
        val label = when (status) {
            0 -> "Normal"
            1 -> "Light — downloads allowed"
            2 -> "Moderate — waiting jobs are held"
            3 -> "Severe — active task stopped safely"
            4 -> "Critical — downloads are stopped"
            5 -> "Emergency — downloads are stopped"
            else -> "Thermal shutdown state — downloads are stopped"
        }
        _thermalStatus.value = "Android thermal guard: " + label
    }

    private fun pauseActiveForThermal() {
        _items.value.filter { it.id in active.keys && it.status in setOf(DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING) }.forEach { item ->
            if (thermalDeferred.add(item.id)) {
                update(item.id) { it.copy(status = DownloadStatus.QUEUED, speed = "—", eta = "—", error = "Paused automatically because the device is too warm. It will resume after cooling.") }
                signal(item.id, "INT")
            }
        }
    }

    fun setMaxConcurrent(value: Int) {
        maxConcurrent = value.coerceIn(1, 2)
        QueueStore.setMaxConcurrent(appContext, maxConcurrent)
        pump()
    }

    fun configurationChanged() {
        if (!initialized) return
        scope.launch {
            if (!StorageAccess.isDownloadLocationConfigured(appContext)) return@launch
            if (_items.value.isEmpty()) {
                QueueStore.restoreFromExternal(appContext)?.takeIf { it.isNotEmpty() }?.let { restored ->
                    synchronized(stateLock) {
                        if (_items.value.isEmpty()) {
                            _items.value = restored
                            QueueStore.save(appContext, restored)
                        }
                    }
                }
            }
            reconcileCompletedFiles()
            resumeExistingDownloads(_items.value)
            QueueStore.mirrorBackup(appContext, _items.value, force = true)
            pump()
        }
    }

    private fun nextArrivalTimestampLocked(): Long = maxOf(
        System.currentTimeMillis(),
        (_items.value.maxOfOrNull { it.createdAt } ?: 0L) + 1L
    )

    /**
     * If an output no longer exists at its recorded path, look for that exact
     * filename inside the configured tree. A unique match updates its path;
     * a complete scan with no match removes only the record, never the file.
     * Ambiguous or incomplete scans preserve the queue item.
     */
    private fun reconcileCompletedFiles() {
        if (!StorageAccess.isDownloadLocationConfigured(appContext)) return
        val completed = _items.value.filter {
            it.status == DownloadStatus.COMPLETED && it.outputPath.isNotBlank()
        }
        if (completed.isEmpty()) return

        val movedPaths = mutableMapOf<String, String>()
        val missingIds = mutableSetOf<String>()
        completed.forEach { item ->
            if (StorageAccess.findDownloadedDocument(appContext, item.outputPath) != null) return@forEach
            val fileName = item.outputPath.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return@forEach
            val scan = StorageAccess.scanConfiguredDownloadsFolder(appContext, fileName) ?: return@forEach
            if (!scan.complete) return@forEach
            when (scan.matches.size) {
                0 -> missingIds.add(item.id)
                1 -> movedPaths[item.id] = scan.matches.first()
                else -> Unit
            }
        }
        if (missingIds.isEmpty() && movedPaths.isEmpty()) return

        val originalById = completed.associateBy { it.id }
        val actuallyRemoved = mutableSetOf<String>()
        synchronized(stateLock) {
            _items.value = _items.value.mapNotNull { current ->
                val original = originalById[current.id] ?: return@mapNotNull current
                // A retry or another state change may have happened while the SAF tree was scanned.
                if (current.status != DownloadStatus.COMPLETED || current.outputPath != original.outputPath) {
                    return@mapNotNull current
                }
                if (current.id in missingIds) {
                    actuallyRemoved.add(current.id)
                    return@mapNotNull null
                }
                val newPath = movedPaths[current.id] ?: return@mapNotNull current
                current.copy(outputPath = newPath)
            }
            QueueStore.save(appContext, _items.value, durable = true)
        }
        actuallyRemoved.forEach { id ->
            LinkMetadataResolver.cancel(appContext, id)
            LinkStatusNotification.cancel(appContext, id)
        }
    }

    fun synchronizeDownloadsFolder() {
        if (!initialized) return
        scope.launch(Dispatchers.IO) {
            reconcileCompletedFiles()
            QueueStore.mirrorBackup(appContext, _items.value, force = false)
        }
    }

    fun onMonitorServiceStarted() {
        monitorServiceRequested.set(true)
    }

    fun onMonitorServiceStopped() {
        monitorServiceRequested.set(false)
    }

    private fun startMonitoringService() {
        if (!initialized) return
        val hasWork = _items.value.any { !it.awaitingChoice && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.PROCESSING) }
        if (!hasWork || !monitorServiceRequested.compareAndSet(false, true)) return
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                android.content.Intent(appContext, DownloadMonitorService::class.java)
            )
        }.onFailure {
            monitorServiceRequested.set(false)
            Log.w("DownloadStudio", "Could not start the foreground download monitor", it)
        }
    }

    private fun resumeExistingDownloads(items: List<DownloadItem>) {
        items.filter {
            it.status in setOf(
                DownloadStatus.STARTING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PROCESSING,
                DownloadStatus.PAUSED
            )
        }.forEach { item ->
            val monitor = scope.launch(start = CoroutineStart.LAZY) { monitorExisting(item) }
            val existing = active.putIfAbsent(item.id, monitor)
            if (existing == null) {
                monitor.start()
            } else {
                monitor.cancel()
            }
        }
    }

    fun enqueue(url: String, mode: DownloadMode, title: String = ""): String {
        val cleaned = url.trim()
        require(cleaned.startsWith("https://") || cleaned.startsWith("http://")) { "Please provide a valid HTTP or HTTPS link." }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        synchronized(stateLock) {
            val item = DownloadItem(
                id = id,
                url = cleaned,
                title = title.ifBlank { titleFromUrl(cleaned) },
                mode = mode,
                createdAt = nextArrivalTimestampLocked()
            )
            _items.value = (_items.value + item)
                .sortedWith(compareByDescending<DownloadItem> { it.createdAt }.thenByDescending { it.id })
            QueueStore.save(appContext, _items.value, durable = true)
        }
        startMonitoringService()
        pump()
        return id
    }

    /**
     * Add a link to the persistent queue without starting the download.
     * A user must explicitly choose video or audio from the queue row.
     */
    fun enqueueForChoice(url: String, preferredMode: DownloadMode = DownloadMode.VIDEO, notificationEligible: Boolean = false): String {
        val cleaned = url.trim()
        val uri = android.net.Uri.parse(cleaned)
        require((uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Please provide a valid HTTP or HTTPS media link."
        }
        require(cleaned.length <= 4096) { "The media link is too long." }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        synchronized(stateLock) {
            val item = DownloadItem(
                id = id,
                url = cleaned,
                title = titleFromUrl(cleaned),
                mode = preferredMode,
                createdAt = nextArrivalTimestampLocked(),
                awaitingChoice = true,
                sourceName = sourceNameFromUrl(cleaned),
                notificationEligible = notificationEligible
            )
            _items.value = (_items.value + item)
                .sortedWith(compareByDescending<DownloadItem> { it.createdAt }.thenByDescending { it.id })
            QueueStore.save(appContext, _items.value, durable = true)
        }
        return id
    }

    /** Update metadata on the same waiting queue entry; never create a second row. */
    fun updateLinkMetadata(
        id: String,
        title: String,
        thumbnailUrl: String,
        durationSeconds: Long,
        estimatedSizeBytes: Long,
        sourceName: String
    ): Boolean {
        var updated = false
        synchronized(stateLock) {
            val current = _items.value.firstOrNull { it.id == id && it.awaitingChoice } ?: return false
            _items.value = _items.value.map { item ->
                if (item.id != id) item else item.copy(
                    title = title.takeIf { it.isNotBlank() } ?: current.title,
                    thumbnailUrl = thumbnailUrl.takeIf { it.isNotBlank() } ?: current.thumbnailUrl,
                    durationSeconds = durationSeconds.takeIf { it > 0L } ?: current.durationSeconds,
                    estimatedSizeBytes = estimatedSizeBytes.takeIf { it > 0L } ?: current.estimatedSizeBytes,
                    sourceName = sourceName.takeIf { it.isNotBlank() } ?: current.sourceName
                )
            }
            QueueStore.save(appContext, _items.value)
            updated = true
        }
        return updated
    }

    /** Turn a waiting link into a normal download, retaining its queue identity. */
    fun chooseLinkDownload(id: String, mode: DownloadMode): Boolean {
        var chosen = false
        synchronized(stateLock) {
            val current = _items.value.firstOrNull { it.id == id && it.awaitingChoice } ?: return false
            _items.value = _items.value.map { item ->
                if (item.id != id) item else item.copy(
                    mode = mode,
                    status = DownloadStatus.QUEUED,
                    awaitingChoice = false,
                    progress = 0f,
                    speed = "—",
                    eta = "—",
                    downloaded = "—",
                    error = ""
                )
            }
            QueueStore.save(appContext, _items.value, durable = true)
            chosen = true
        }
        if (chosen) {
            startMonitoringService()
            LinkMetadataResolver.cancel(appContext, id)
            LinkStatusNotification.cancel(appContext, id)
            pump()
        }
        return chosen
    }

    private fun sourceNameFromUrl(raw: String): String = runCatching {
        val host = android.net.Uri.parse(raw).host.orEmpty().lowercase()
            .removePrefix("www.").removePrefix("m.")
        when {
            host == "youtu.be" || host.endsWith("youtube.com") -> "YouTube"
            host == "facebook.com" || host.endsWith(".facebook.com") || host.endsWith("fb.watch") -> "Facebook"
            host == "instagram.com" || host.endsWith(".instagram.com") -> "Instagram"
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> "TikTok"
            host == "x.com" || host == "twitter.com" -> "X"
            host == "vimeo.com" || host.endsWith(".vimeo.com") -> "Vimeo"
            host == "reddit.com" || host.endsWith(".reddit.com") -> "Reddit"
            host.isBlank() -> "Website"
            else -> host.substringBefore(':').replaceFirstChar { it.uppercase() }
        }
    }.getOrDefault("Website")

    fun pause(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.status != DownloadStatus.DOWNLOADING && item.status != DownloadStatus.STARTING) return
        update(id) { it.copy(status = DownloadStatus.PAUSED, speed = "Paused", error = "") }
        signal(id, "STOP")
    }

    fun resume(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.status != DownloadStatus.PAUSED) return
        if (currentThermalStatus >= THERMAL_DEFER_THRESHOLD) {
            update(id) { it.copy(error = "Resume is temporarily blocked until the device cools down.") }
            return
        }
        update(id) { it.copy(status = DownloadStatus.DOWNLOADING, speed = "Resuming…", error = "") }
        signal(id, "CONT")
    }

    fun stop(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        val running = id in active.keys || item.status in setOf(
            DownloadStatus.STARTING, DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED, DownloadStatus.PROCESSING
        )
        if (running) {
            cancelled.add(id)
            signal(id, "INT")
        } else cancelled.remove(id)
        thermalDeferred.remove(id)
        update(id) {
            it.copy(status = DownloadStatus.STOPPED, speed = "—", eta = "—", error = "Stopped by user")
        }
        if (!running) pump()
    }

    /** Retry a failed or stopped record without creating a duplicate history entry. */
    fun retry(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.status !in setOf(DownloadStatus.FAILED, DownloadStatus.STOPPED)) return
        if (active.containsKey(id)) {
            update(id) { it.copy(error = "This download is still stopping. Try again in a moment.") }
            return
        }
        cancelled.remove(id)
        thermalDeferred.remove(id)
        update(id) {
            it.copy(
                status = DownloadStatus.QUEUED,
                progress = 0f,
                speed = "—",
                eta = "—",
                downloaded = "—",
                outputPath = "",
                runToken = "",
                error = ""
            )
        }
        startMonitoringService()
        pump()
    }

    fun remove(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (id in active.keys || item.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.STARTING, DownloadStatus.PAUSED, DownloadStatus.PROCESSING)) {
            stop(id)
        }
        synchronized(stateLock) {
            _items.value = _items.value.filterNot { it.id == id }
            QueueStore.save(appContext, _items.value, durable = true)
        }
        LinkMetadataResolver.cancel(appContext, id)
        LinkStatusNotification.cancel(appContext, id)
    }

    fun clearFinished() {
        synchronized(stateLock) {
            _items.value = _items.value.filterNot {
                it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.STOPPED)
            }
            QueueStore.save(appContext, _items.value, durable = true)
        }
    }

    fun clearAll() {
        // Close the queue pump gate before taking the snapshot. Workers that finish
        // during this operation must not launch another queued item.
        bulkClearing = true
        val clearSnapshot = synchronized(stateLock) {
            val notificationIds = _items.value.map { it.id }
            val ids = (active.keys + _items.value.filter {
                it.status in setOf(
                    DownloadStatus.DOWNLOADING, DownloadStatus.STARTING,
                    DownloadStatus.PAUSED, DownloadStatus.PROCESSING
                )
            }.map { it.id }).toSet()
            val runTokens = _items.value.associate { it.id to it.runToken }
            _items.value = emptyList()
            QueueStore.save(appContext, emptyList(), durable = true)
            Triple(ids, notificationIds, runTokens)
        }
        clearSnapshot.second.forEach {
            LinkMetadataResolver.cancel(appContext, it)
            LinkStatusNotification.cancel(appContext, it)
        }

        clearSnapshot.first.forEach { id ->
            cancelled.add(id)
            thermalDeferred.remove(id)
            signal(id, "INT", clearSnapshot.third[id].orEmpty())
            active[id]?.cancel()
        }
        // No queued records remain; a later enqueue can safely restart the pump.
        bulkClearing = false
    }

    private fun signal(id: String, signal: String, runTokenOverride: String? = null) {
        val item = _items.value.firstOrNull { it.id == id }
        if (item == null && runTokenOverride == null) return
        val runToken = runTokenOverride ?: item?.runToken.orEmpty()
        val dest = StorageAccess.destinationPath(appContext) ?: return
        scope.launch {
            TermuxBridge.runShell(
                appContext,
                TermuxBridge.signalScript(dest, id, signal, runToken),
                "Download Studio · $signal",
                true
            )
        }
    }

    private fun pump() {
        startMonitoringService()
        if (!initialized || bulkClearing || StorageAccess.destinationPath(appContext).isNullOrBlank()) return
        if (currentThermalStatus >= THERMAL_DEFER_THRESHOLD) return
        scope.launch {
            mutex.withLock {
                while (!bulkClearing && active.size < maxConcurrent && currentThermalStatus < THERMAL_DEFER_THRESHOLD) {
                    val next = _items.value.firstOrNull { it.status == DownloadStatus.QUEUED && !it.awaitingChoice && it.id !in active.keys } ?: break
                    val worker = scope.launch(start = CoroutineStart.LAZY) { runJob(next) }
                    active[next.id] = worker
                    worker.start()
                }
            }
        }
    }

    private suspend fun runJob(original: DownloadItem) {
        startMonitoringService()
        val id = original.id
        try {
            val destination = StorageAccess.destinationPath(appContext)
            if (destination.isNullOrBlank()) {
                update(id) { it.copy(status = DownloadStatus.FAILED, error = "Choose a Downloads folder in Settings and grant folder access first.") }
                return
            }
            if (!TermuxBridge.isInstalled(appContext)) {
                update(id) { it.copy(status = DownloadStatus.FAILED, error = "Termux is not installed. Install Termux and finish the engine setup in Settings.") }
                return
            }
            if (!TermuxBridge.hasRunPermission(appContext)) {
                update(id) { it.copy(status = DownloadStatus.FAILED, error = "Grant Download Studio the 'Run commands in Termux' permission in Android Settings.") }
                return
            }
            if (currentThermalStatus >= THERMAL_DEFER_THRESHOLD) {
                update(id) { it.copy(status = DownloadStatus.QUEUED, speed = "—", eta = "—", error = "Waiting for the device to cool before starting.") }
                return
            }
            // The previous attempt may have been interrupted by thermal protection.
            // Clear that marker only after the thermal gate allows a real retry.
            thermalDeferred.remove(id)
            val runToken = UUID.randomUUID().toString().replace("-", "")
            update(id, durable = true) {
                it.copy(
                    status = DownloadStatus.STARTING,
                    progress = 0f,
                    speed = "Starting…",
                    eta = "—",
                    error = "",
                    runToken = runToken
                )
            }
            val executionKey = "$id-$runToken"
            val beforeLaunch = _items.value.firstOrNull { it.id == id }
            if (beforeLaunch == null || beforeLaunch.status == DownloadStatus.STOPPED || id in cancelled) return
            val script = TermuxBridge.downloadScript(id, original.url, original.mode.name, destination, runToken)
            val launch = TermuxBridge.runShell(appContext, script, "Download Studio · ${original.mode.name.lowercase()}", true)
            if (launch.isFailure) throw launch.exceptionOrNull() ?: IllegalStateException("Termux could not start the download.")

            val afterLaunch = _items.value.firstOrNull { it.id == id }
            if (afterLaunch == null || afterLaunch.status == DownloadStatus.STOPPED || id in cancelled) {
                // The queue item may have been removed while Android was handing the command to Termux.
                signal(id, "INT", runToken)
                return
            }
            if (afterLaunch.status == DownloadStatus.PAUSED) {
                signal(id, "STOP", runToken)
            } else {
                update(id) { current ->
                    if (current.status == DownloadStatus.PAUSED || current.status == DownloadStatus.STOPPED) current
                    else current.copy(status = DownloadStatus.DOWNLOADING, speed = "Connecting…")
                }
            }

            var lastLog = ""
            val startAt = SystemClock.elapsedRealtime()
            var lastActivityAt = startAt
            var sawPid = false
            var stopReason = ""
            while (SystemClock.elapsedRealtime() - startAt < MAX_DOWNLOAD_RUNTIME_MS) {
                val current = _items.value.firstOrNull { it.id == id }
                if (current == null || current.status == DownloadStatus.STOPPED || id in cancelled) {
                    signal(id, "INT", runToken)
                    break
                }
                if (current.status == DownloadStatus.PAUSED) {
                    lastActivityAt = SystemClock.elapsedRealtime()
                    delay(MONITOR_INTERVAL_MS * 2)
                    continue
                }
                val log = StorageAccess.readStateTail(appContext, "DownloadStudioState/$executionKey.log").orEmpty()
                if (log != lastLog) {
                    lastLog = log
                    lastActivityAt = SystemClock.elapsedRealtime()
                    parseProgress(id, log)
                }
                val exit = StorageAccess.readState(appContext, "DownloadStudioState/$executionKey.exit")?.trim()
                if (!exit.isNullOrBlank()) {
                    val code = exit.toIntOrNull() ?: 1
                    if (id in cancelled || _items.value.firstOrNull { it.id == id }?.status == DownloadStatus.STOPPED) break
                    if (id in thermalDeferred) {
                        update(id) { it.copy(status = DownloadStatus.QUEUED, speed = "—", eta = "—", error = "Paused automatically because the device was too warm. It will resume after cooling.") }
                    } else if (code == 0) {
                        val path = log.lineSequence().lastOrNull { it.startsWith("DS_COMPLETE:") }?.substringAfter("DS_COMPLETE:")?.trim().orEmpty()
                        update(id) {
                            it.copy(
                                status = DownloadStatus.COMPLETED,
                                progress = 1f,
                                speed = "—",
                                eta = "—",
                                downloaded = it.totalSize.takeIf { size -> size != "—" } ?: it.downloaded,
                                processingStage = "",
                                outputPath = path,
                                completedAt = System.currentTimeMillis(),
                                error = ""
                            )
                        }
                    } else {
                        val errorLine = log.lineSequence().lastOrNull { line ->
                            val l = line.lowercase()
                            l.contains("error:") || l.contains("unable to") || l.contains("unsupported url") || l.contains("http error")
                        }?.take(320).orEmpty()
                        update(id) { it.copy(status = DownloadStatus.FAILED, speed = "—", eta = "—", error = errorLine.ifBlank { "yt-dlp exited with code $code. Check Termux setup and the activity log." }) }
                    }
                    break
                }
                val pid = StorageAccess.readState(appContext, "DownloadStudioState/$executionKey.pid")?.trim().orEmpty()
                if (pid.isNotBlank()) sawPid = true
                val now = SystemClock.elapsedRealtime()
                if (!sawPid && now - startAt > START_TIMEOUT_MS) {
                    if (id in thermalDeferred) {
                        update(id) { it.copy(status = DownloadStatus.QUEUED, error = "Waiting for the device to cool before resuming.") }
                    } else {
                        stopReason = "Termux did not start the download within 30 seconds. Check the Termux command permission and setup."
                    }
                    break
                }
                if (now - lastActivityAt > STALL_TIMEOUT_MS) {
                    stopReason = "Download stopped because there was no activity for 15 minutes. You can retry it."
                    break
                }
                if (currentThermalStatus >= THERMAL_STOP_THRESHOLD && thermalDeferred.add(id)) {
                    update(id) { it.copy(status = DownloadStatus.QUEUED, speed = "—", eta = "—", error = "Paused automatically because the device was too warm. It will resume after cooling.") }
                    signal(id, "INT")
                }
                delay(MONITOR_INTERVAL_MS)
            }
            if (stopReason.isBlank() && SystemClock.elapsedRealtime() - startAt >= MAX_DOWNLOAD_RUNTIME_MS) {
                stopReason = "Download stopped at the six-hour safety limit. You can retry it."
            }
            if (stopReason.isNotBlank()) {
                signal(id, "INT")
                if (id !in thermalDeferred) update(id) { it.copy(status = DownloadStatus.FAILED, speed = "—", eta = "—", error = stopReason) }
            }
        } catch (e: Exception) {
            if (id !in cancelled && id !in thermalDeferred) update(id) { it.copy(status = DownloadStatus.FAILED, speed = "—", error = e.message ?: "Download failed") }
        } finally {
            active.remove(id)
            cancelled.remove(id)
            persist()
            pump()
        }
    }


    private suspend fun monitorExisting(item: DownloadItem) {
        val id = item.id
        try {
            val startAt = SystemClock.elapsedRealtime()
            var lastActivityAt = startAt
            var lastLog = ""
            val executionKey = if (item.runToken.isBlank()) id else "$id-${item.runToken}"
            val logPath = "DownloadStudioState/$executionKey.log"
            val pidPath = "DownloadStudioState/$executionKey.pid"
            val exitPath = "DownloadStudioState/$executionKey.exit"
            while (SystemClock.elapsedRealtime() - startAt < MAX_DOWNLOAD_RUNTIME_MS) {
                val current = _items.value.firstOrNull { it.id == id } ?: break
                if (current.status == DownloadStatus.STOPPED || id in cancelled) break
                if (current.status == DownloadStatus.PAUSED) {
                    lastActivityAt = SystemClock.elapsedRealtime()
                    delay(MONITOR_INTERVAL_MS * 2)
                    continue
                }
                val log = StorageAccess.readStateTail(appContext, logPath).orEmpty()
                if (log != lastLog) {
                    lastLog = log
                    lastActivityAt = SystemClock.elapsedRealtime()
                    parseProgress(id, log)
                }
                val exit = StorageAccess.readState(appContext, exitPath)?.trim()
                if (!exit.isNullOrBlank()) {
                    val code = exit.toIntOrNull() ?: 1
                    if (id in thermalDeferred) {
                        update(id) { it.copy(status = DownloadStatus.QUEUED, error = "Paused automatically because the device was too warm. It will resume after cooling.") }
                    } else if (code == 0) {
                        val path = log.lineSequence().lastOrNull { it.startsWith("DS_COMPLETE:") }?.substringAfter("DS_COMPLETE:")?.trim().orEmpty()
                        update(id) {
                            it.copy(
                                status = DownloadStatus.COMPLETED,
                                progress = 1f,
                                speed = "—",
                                eta = "—",
                                downloaded = it.totalSize.takeIf { size -> size != "—" } ?: it.downloaded,
                                processingStage = "",
                                outputPath = path,
                                completedAt = System.currentTimeMillis(),
                                error = ""
                            )
                        }
                    } else {
                        update(id) { it.copy(status = DownloadStatus.FAILED, speed = "—", eta = "—", error = "The previous Termux download exited with code $code.") }
                    }
                    break
                }
                val pid = StorageAccess.readState(appContext, pidPath)?.trim().orEmpty()
                val now = SystemClock.elapsedRealtime()
                if (pid.isBlank() && now - startAt > START_TIMEOUT_MS) {
                    update(id) { it.copy(status = DownloadStatus.QUEUED, speed = "—", eta = "—", error = "Previous download was not running; returned to the queue.") }
                    break
                }
                if (now - lastActivityAt > STALL_TIMEOUT_MS) {
                    signal(id, "INT")
                    update(id) { it.copy(status = DownloadStatus.FAILED, speed = "—", eta = "—", error = "Download monitoring detected no activity for 15 minutes. Retry when ready.") }
                    break
                }
                delay(MONITOR_INTERVAL_MS)
            }
            if (SystemClock.elapsedRealtime() - startAt >= MAX_DOWNLOAD_RUNTIME_MS) {
                signal(id, "INT")
                update(id) { it.copy(status = DownloadStatus.FAILED, speed = "—", eta = "—", error = "Download stopped at the six-hour safety limit.") }
            }
        } finally {
            active.remove(id)
            thermalDeferred.remove(id)
            persist()
            pump()
        }
    }

    private fun parseProgress(id: String, log: String) {
        if (id in thermalDeferred) return
        val mediaTitle = log.lineSequence().lastOrNull { it.startsWith("DS_TITLE:") }?.substringAfter("DS_TITLE:")?.trim()
        val thumbnailUrl = log.lineSequence().lastOrNull { it.startsWith("DS_THUMBNAIL:") }?.substringAfter("DS_THUMBNAIL:")?.trim()
        if (!mediaTitle.isNullOrBlank() || !thumbnailUrl.isNullOrBlank()) {
            update(id) { current ->
                current.copy(
                    title = mediaTitle?.takeIf { it.isNotBlank() } ?: current.title,
                    thumbnailUrl = thumbnailUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: current.thumbnailUrl
                )
            }
        }

        val postprocessLine = log.lineSequence().lastOrNull { it.startsWith("DS_POSTPROCESS|") }
        var postprocessingObserved = false
        if (postprocessLine != null) {
            val postParts = postprocessLine.split('|')
            val postStatus = postParts.getOrNull(1)?.trim()?.lowercase().orEmpty()
            val processor = postParts.getOrNull(2)?.trim().orEmpty()
            if (postStatus in setOf("started", "processing", "finished")) {
                postprocessingObserved = true
                val stage = when (processor.lowercase()) {
                    "merger" -> "Merging video and audio"
                    "extractaudio" -> "Converting audio to MP3"
                    "embedthumbnail" -> "Embedding thumbnail"
                    "metadata" -> "Writing media metadata"
                    "videoremuxer" -> "Finalizing video container"
                    "videoconvertor" -> "Converting video"
                    else -> if (postStatus == "finished") "Finalizing media" else "Processing media"
                }
                update(id) { current ->
                    if (current.status in setOf(DownloadStatus.PAUSED, DownloadStatus.STOPPED, DownloadStatus.QUEUED)) current
                    else current.copy(
                        status = DownloadStatus.PROCESSING,
                        progress = maxOf(current.progress, 1f),
                        speed = "—",
                        eta = "—",
                        processingStage = if (postStatus == "finished") "Finalizing media" else stage
                    )
                }
            }
        }

        val line = log.lineSequence().lastOrNull { it.startsWith("DS_PROGRESS|") } ?: return
        val parts = line.split('|')
        if (parts.size < 5) return
        val percent = parts[1].filter { it.isDigit() || it == '.' }.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: 0f
        val speed = parts[2].trim().ifBlank { "—" }
        val eta = parts[3].trim().ifBlank { "—" }
        val downloadedBytes = parts[4].trim().toLongOrNull() ?: 0L
        val totalBytes = parts.getOrNull(5)?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            ?: parts.getOrNull(6)?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        val downloaded = humanBytes(downloadedBytes)
        val totalSize = totalBytes?.let(::humanBytes)
        update(id) { current ->
            if (current.status in setOf(DownloadStatus.PAUSED, DownloadStatus.STOPPED, DownloadStatus.QUEUED)) current
            else current.copy(
                status = if (postprocessingObserved || current.status == DownloadStatus.PROCESSING) DownloadStatus.PROCESSING else DownloadStatus.DOWNLOADING,
                progress = if (postprocessingObserved) maxOf(current.progress, percent) else percent,
                speed = if (postprocessingObserved) "—" else speed,
                eta = if (postprocessingObserved) "—" else eta,
                downloaded = downloaded,
                totalSize = totalSize ?: current.totalSize,
                processingStage = if (postprocessingObserved) current.processingStage else ""
            )
        }
    }

    private fun update(
        id: String,
        durable: Boolean = false,
        transform: (DownloadItem) -> DownloadItem
    ) {
        synchronized(stateLock) {
            _items.value = _items.value.map { if (it.id == id) transform(it) else it }
            QueueStore.save(appContext, _items.value, durable = durable)
        }
    }

    private fun persist() {
        synchronized(stateLock) {
            QueueStore.save(appContext, _items.value)
        }
    }

    private val MONITORED_STATUSES = setOf(DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING, DownloadStatus.PAUSED)

    private fun humanBytes(value: Long): String {
        if (value <= 0) return "—"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = value.toDouble(); var index = 0
        while (v >= 1024 && index < units.lastIndex) { v /= 1024; index++ }
        return "%.1f %s".format(v, units[index])
    }

    private fun titleFromUrl(url: String): String = runCatching {
        val host = android.net.Uri.parse(url).host?.lowercase()?.removePrefix("www.")
        when {
            host == "youtu.be" || host == "youtube.com" || host?.endsWith(".youtube.com") == true -> "YouTube video"
            !host.isNullOrBlank() -> "Media from $host"
            else -> "New download"
        }
    }.getOrDefault("New download")
}
