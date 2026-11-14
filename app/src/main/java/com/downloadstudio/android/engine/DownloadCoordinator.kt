package com.downloadstudio.android.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.downloadstudio.android.data.DownloadItem
import com.downloadstudio.android.data.DownloadMode
import com.downloadstudio.android.data.DownloadStatus
import com.downloadstudio.android.data.QueueStore
import com.downloadstudio.android.notifications.ShareDownloadNotification
import com.downloadstudio.android.util.StorageAccess
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
    private val active = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val thermalDeferred = ConcurrentHashMap.newKeySet<String>()
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    private val _thermalStatus = MutableStateFlow("Thermal guard: checking device")
    val thermalStatus: StateFlow<String> = _thermalStatus.asStateFlow()
    @Volatile private var maxConcurrent = 1
    @Volatile private var currentThermalStatus = 0
    @Volatile private var initialized = false
    @Volatile private var bulkClearing = false

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        maxConcurrent = QueueStore.maxConcurrent(appContext)
        val loaded = QueueStore.load(appContext)
        val recovered = loaded.map { item ->
            if (item.status == DownloadStatus.PREVIEWING) {
                item.copy(
                    status = DownloadStatus.AWAITING_ACTION,
                    shareReady = false,
                    error = "The media preview was interrupted. Share this link again to refresh its details."
                )
            } else item
        }
        _items.value = recovered
        if (recovered != loaded) QueueStore.save(appContext, recovered)
        initialized = true
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
                resumeExistingDownloads(_items.value)
                QueueStore.mirrorBackup(appContext, _items.value, force = true)
            }
            ShareDownloadNotification.syncQueue(appContext, _items.value)
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
            resumeExistingDownloads(_items.value)
            QueueStore.mirrorBackup(appContext, _items.value, force = true)
            pump()
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
            if (!active.containsKey(item.id)) {
                val monitor = scope.launch(start = CoroutineStart.LAZY) { monitorExisting(item) }
                active[item.id] = monitor
                monitor.start()
            }
        }
    }

    /**
     * Shared links are persisted queue records. Notifications are a projection
     * of these records and do not own a second copy of link or download state.
     */
    fun addSharedLink(url: String): String {
        val cleaned = url.trim()
        require(cleaned.startsWith("https://", true) || cleaned.startsWith("http://", true)) {
            "Please share a valid HTTP or HTTPS media link."
        }
        require(android.net.Uri.parse(cleaned).host?.isNotBlank() == true) {
            "The shared link does not contain a valid host."
        }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val item = DownloadItem(
            id = id, url = cleaned, title = "Checking media details…",
            mode = DownloadMode.CHOOSE, status = DownloadStatus.PREVIEWING, shareReady = false
        )
        val snapshot = synchronized(stateLock) {
            _items.value = _items.value + item
            QueueStore.save(appContext, _items.value)
            _items.value.toList()
        }
        ShareDownloadNotification.syncQueue(appContext, snapshot)
        return id
    }

    fun updateSharedLinkPreview(
        id: String, title: String, thumbnailUrl: String, durationSeconds: Long,
        estimatedSizeBytes: Long, shareReady: Boolean, error: String = ""
    ) {
        update(id) { current ->
            if (current.status != DownloadStatus.PREVIEWING) current
            else current.copy(
                status = DownloadStatus.AWAITING_ACTION,
                title = title.ifBlank { "Shared media link" },
                thumbnailUrl = thumbnailUrl.takeIf {
                    it.startsWith("https://", true) || it.startsWith("http://", true)
                }.orEmpty(),
                durationSeconds = durationSeconds.coerceAtLeast(0L),
                totalSize = estimatedSizeBytes.takeIf { it > 0L }?.let { "≈ " + humanBytes(it) } ?: "—",
                shareReady = shareReady,
                error = error
            )
        }
    }

    /** Select a format on the shared-link record; do not create a duplicate queue row. */
    fun startSharedDownload(id: String, mode: DownloadMode): Boolean {
        if (mode == DownloadMode.CHOOSE) return false
        val current = _items.value.firstOrNull { it.id == id } ?: return false
        if (current.status != DownloadStatus.AWAITING_ACTION || !current.shareReady) return false
        update(id) { item ->
            if (item.status != DownloadStatus.AWAITING_ACTION || !item.shareReady) item
            else item.copy(
                mode = mode, status = DownloadStatus.QUEUED, shareReady = false,
                progress = 0f, speed = "—", eta = "—", downloaded = "—", error = ""
            )
        }
        val started = _items.value.any {
            it.id == id && it.mode == mode && it.status in setOf(
                DownloadStatus.QUEUED, DownloadStatus.STARTING, DownloadStatus.DOWNLOADING
            )
        }
        if (started) pump()
        return started
    }

    fun enqueue(url: String, mode: DownloadMode, title: String = "", thumbnailUrl: String = "", durationSeconds: Long = 0L, estimatedSizeBytes: Long = 0L) : String {
        val cleaned = url.trim()
        require(mode != DownloadMode.CHOOSE) { "Choose video or audio before starting a download." }
        require(cleaned.startsWith("https://") || cleaned.startsWith("http://")) { "Please provide a valid HTTP or HTTPS link." }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val item = DownloadItem(
            id = id, url = cleaned, title = title.ifBlank { titleFromUrl(cleaned) }, mode = mode,
            thumbnailUrl = thumbnailUrl.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty(),
            durationSeconds = durationSeconds.coerceAtLeast(0L),
            totalSize = estimatedSizeBytes.takeIf { it > 0L }?.let { "≈ " + humanBytes(it) } ?: "—"
        )
        val snapshot = synchronized(stateLock) {
            _items.value = _items.value + item
            QueueStore.save(appContext, _items.value)
            _items.value.toList()
        }
        ShareDownloadNotification.syncQueue(appContext, snapshot)
        pump()
        return id
    }

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
        val running = item.status in setOf(
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
                error = ""
            )
        }
        pump()
    }

    fun remove(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.STARTING, DownloadStatus.PAUSED, DownloadStatus.PROCESSING)) {
            stop(id)
        }
        val snapshot = synchronized(stateLock) {
            _items.value = _items.value.filterNot { it.id == id }
            QueueStore.save(appContext, _items.value)
            _items.value.toList()
        }
        ShareDownloadNotification.syncQueue(appContext, snapshot)
    }

    fun clearFinished() {
        val snapshot = synchronized(stateLock) {
            _items.value = _items.value.filterNot {
                it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.STOPPED)
            }
            QueueStore.save(appContext, _items.value)
            _items.value.toList()
        }
        ShareDownloadNotification.syncQueue(appContext, snapshot)
    }

    fun clearAll() {
        // Close the queue pump gate before taking the snapshot. Workers that finish
        // during this operation must not launch another queued item.
        bulkClearing = true
        val idsToStop = synchronized(stateLock) {
            val ids = (active.keys + _items.value.filter {
                it.status in setOf(
                    DownloadStatus.DOWNLOADING, DownloadStatus.STARTING,
                    DownloadStatus.PAUSED, DownloadStatus.PROCESSING
                )
            }.map { it.id }).toSet()
            _items.value = emptyList()
            QueueStore.save(appContext, emptyList())
            ids
        }
        ShareDownloadNotification.syncQueue(appContext, emptyList())

        idsToStop.forEach { id ->
            cancelled.add(id)
            thermalDeferred.remove(id)
            signal(id, "INT")
            active[id]?.cancel()
        }
        // No queued records remain; a later enqueue can safely restart the pump.
        bulkClearing = false
    }

    private fun signal(id: String, signal: String) {
        val dest = StorageAccess.destinationPath(appContext) ?: return
        scope.launch {
            TermuxBridge.runShell(appContext, TermuxBridge.signalScript(dest, id, signal), "Download Studio · $signal", true)
        }
    }

    private fun pump() {
        if (!initialized || bulkClearing || StorageAccess.destinationPath(appContext).isNullOrBlank()) return
        if (currentThermalStatus >= THERMAL_DEFER_THRESHOLD) return
        scope.launch {
            mutex.withLock {
                while (!bulkClearing && active.size < maxConcurrent && currentThermalStatus < THERMAL_DEFER_THRESHOLD) {
                    val next = _items.value.firstOrNull { it.status == DownloadStatus.QUEUED && it.id !in active.keys } ?: break
                    val worker = scope.launch(start = CoroutineStart.LAZY) { runJob(next) }
                    active[next.id] = worker
                    worker.start()
                }
            }
        }
    }

    private suspend fun runJob(original: DownloadItem) {
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
            update(id) { it.copy(status = DownloadStatus.STARTING, progress = 0f, speed = "Starting…", eta = "—", error = "") }
            val script = TermuxBridge.downloadScript(id, original.url, original.mode.name, destination)
            val launch = TermuxBridge.runShell(appContext, script, "Download Studio · ${original.mode.name.lowercase()}", true)
            if (launch.isFailure) throw launch.exceptionOrNull() ?: IllegalStateException("Termux could not start the download.")
            update(id) { current ->
                if (current.status == DownloadStatus.PAUSED) current else current.copy(status = DownloadStatus.DOWNLOADING, speed = "Connecting…")
            }

            var lastLog = ""
            val startAt = SystemClock.elapsedRealtime()
            var lastActivityAt = startAt
            var sawPid = false
            var stopReason = ""
            while (SystemClock.elapsedRealtime() - startAt < MAX_DOWNLOAD_RUNTIME_MS) {
                val current = _items.value.firstOrNull { it.id == id } ?: break
                if (current.status == DownloadStatus.STOPPED || id in cancelled) break
                if (current.status == DownloadStatus.PAUSED) {
                    lastActivityAt = SystemClock.elapsedRealtime()
                    delay(MONITOR_INTERVAL_MS * 2)
                    continue
                }
                val log = StorageAccess.readStateTail(appContext, "DownloadStudioState/$id.log").orEmpty()
                if (log != lastLog) {
                    lastLog = log
                    lastActivityAt = SystemClock.elapsedRealtime()
                    parseProgress(id, log)
                }
                val exit = StorageAccess.readState(appContext, "DownloadStudioState/$id.exit")?.trim()
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
                val pid = StorageAccess.readState(appContext, "DownloadStudioState/$id.pid")?.trim().orEmpty()
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
            val logPath = "DownloadStudioState/$id.log"
            val pidPath = "DownloadStudioState/$id.pid"
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
                val exit = StorageAccess.readState(appContext, "DownloadStudioState/$id.exit")?.trim()
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

    private fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        val snapshot = synchronized(stateLock) {
            _items.value = _items.value.map { if (it.id == id) transform(it) else it }
            QueueStore.save(appContext, _items.value)
            _items.value.toList()
        }
        ShareDownloadNotification.syncQueue(appContext, snapshot)
    }

    private fun persist() {
        val snapshot = synchronized(stateLock) {
            QueueStore.save(appContext, _items.value)
            _items.value.toList()
        }
        ShareDownloadNotification.syncQueue(appContext, snapshot)
    }

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
