package com.downloadstudio.android.engine

import android.content.Context
import com.downloadstudio.android.data.DownloadItem
import com.downloadstudio.android.data.DownloadMode
import com.downloadstudio.android.data.DownloadStatus
import com.downloadstudio.android.data.QueueStore
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
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    // Queue state may be updated by several download monitors at once. Serialize
    // list mutations and persistence so one worker cannot overwrite another's
    // most recent progress or status update.
    private val stateLock = Any()
    private val active = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    @Volatile private var maxConcurrent = 4
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        maxConcurrent = QueueStore.maxConcurrent(appContext)
        _items.value = QueueStore.load(appContext)
        initialized = true
        scope.launch {
            val inFlight = _items.value.filter { it.status in setOf(DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING, DownloadStatus.PAUSED) }
            inFlight.forEach { item ->
                if (!active.containsKey(item.id)) {
                    val monitor = scope.launch(start = CoroutineStart.LAZY) { monitorExisting(item) }
                    active[item.id] = monitor
                    monitor.start()
                }
            }
            pump()
        }
    }

    fun setMaxConcurrent(value: Int) {
        maxConcurrent = value.coerceIn(1, 8)
        QueueStore.setMaxConcurrent(appContext, maxConcurrent)
        pump()
    }

    fun configurationChanged() = pump()

    fun enqueue(url: String, mode: DownloadMode, title: String = "") : String {
        val cleaned = url.trim()
        require(cleaned.startsWith("https://") || cleaned.startsWith("http://")) { "Please provide a valid HTTP or HTTPS link." }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val item = DownloadItem(
            id = id, url = cleaned, title = title.ifBlank { titleFromUrl(cleaned) }, mode = mode
        )
        synchronized(stateLock) {
            _items.value = _items.value + item
            QueueStore.save(appContext, _items.value)
        }
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
        update(id) { it.copy(status = DownloadStatus.DOWNLOADING, speed = "Resuming…") }
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
        } else {
            // Queued/completed/failed items have no worker that could clear this flag.
            cancelled.remove(id)
        }
        update(id) {
            it.copy(status = DownloadStatus.STOPPED, speed = "—", eta = "—", error = "Stopped by user")
        }
        if (!running) pump()
    }

    fun remove(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.STARTING, DownloadStatus.PAUSED, DownloadStatus.PROCESSING)) {
            stop(id)
        }
        synchronized(stateLock) {
            _items.value = _items.value.filterNot { it.id == id }
            QueueStore.save(appContext, _items.value)
        }
    }

    fun clearFinished() {
        synchronized(stateLock) {
            _items.value = _items.value.filterNot {
                it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.STOPPED)
            }
            QueueStore.save(appContext, _items.value)
        }
    }

    fun clearAll() {
        _items.value.filter {
            it.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.STARTING, DownloadStatus.PAUSED, DownloadStatus.PROCESSING)
        }.forEach { stop(it.id) }
        synchronized(stateLock) {
            _items.value = emptyList()
            QueueStore.save(appContext, _items.value)
        }
    }

    private fun signal(id: String, signal: String) {
        val dest = StorageAccess.destinationPath(appContext) ?: return
        scope.launch {
            TermuxBridge.runShell(appContext, TermuxBridge.signalScript(dest, id, signal), "Download Studio · $signal", true)
        }
    }

    private fun pump() {
        if (!initialized || StorageAccess.destinationPath(appContext).isNullOrBlank()) return
        scope.launch {
            mutex.withLock {
                while (active.size < maxConcurrent) {
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
            update(id) { it.copy(status = DownloadStatus.STARTING, progress = 0f, speed = "Starting…", eta = "—", error = "") }
            val script = TermuxBridge.downloadScript(id, original.url, original.mode.name, destination)
            val launch = TermuxBridge.runShell(appContext, script, "Download Studio · ${original.mode.name.lowercase()}", true)
            if (launch.isFailure) throw launch.exceptionOrNull() ?: IllegalStateException("Termux could not start the download.")
            update(id) { current ->
                if (current.status == DownloadStatus.PAUSED) current else current.copy(status = DownloadStatus.DOWNLOADING, speed = "Connecting…")
            }

            var lastLog = ""
            val launchTime = System.currentTimeMillis()
            while (true) {
                val current = _items.value.firstOrNull { it.id == id } ?: break
                if (current.status == DownloadStatus.STOPPED || id in cancelled) break
                val log = StorageAccess.readState(appContext, "DownloadStudioState/$id.log").orEmpty()
                if (log != lastLog) {
                    lastLog = log
                    parseProgress(id, log)
                }
                val exit = StorageAccess.readState(appContext, "DownloadStudioState/$id.exit")?.trim()
                if (!exit.isNullOrBlank()) {
                    val code = exit.toIntOrNull() ?: 1
                    if (id in cancelled || _items.value.firstOrNull { it.id == id }?.status == DownloadStatus.STOPPED) break
                    if (code == 0) {
                        val path = log.lineSequence().lastOrNull { it.startsWith("DS_COMPLETE:") }?.substringAfter("DS_COMPLETE:")?.trim().orEmpty()
                        update(id) {
                            it.copy(status = DownloadStatus.COMPLETED, progress = 1f, speed = "—", eta = "—",
                                downloaded = "Complete", outputPath = path, error = "")
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
                if (pid.isBlank() && log.isBlank() && System.currentTimeMillis() - launchTime > 20_000) {
                    update(id) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            speed = "—",
                            eta = "—",
                            error = "Termux did not start the download command. Confirm allow-external-apps=true, grant Run commands in Termux permission, and rerun the Termux storage setup."
                        )
                    }
                    break
                }
                delay(900)
            }
        } catch (e: Exception) {
            if (id !in cancelled) update(id) { it.copy(status = DownloadStatus.FAILED, speed = "—", error = e.message ?: "Download failed") }
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
            val startTime = System.currentTimeMillis()
            val logPath = "DownloadStudioState/$id.log"
            val pidPath = "DownloadStudioState/$id.pid"
            while (true) {
                val current = _items.value.firstOrNull { it.id == id } ?: break
                if (current.status == DownloadStatus.STOPPED) break
                val log = StorageAccess.readState(appContext, logPath).orEmpty()
                if (log.isNotBlank()) parseProgress(id, log)
                val exit = StorageAccess.readState(appContext, "DownloadStudioState/$id.exit")?.trim()
                if (!exit.isNullOrBlank()) {
                    val code = exit.toIntOrNull() ?: 1
                    if (code == 0) {
                        val path = log.lineSequence().lastOrNull { it.startsWith("DS_COMPLETE:") }?.substringAfter("DS_COMPLETE:")?.trim().orEmpty()
                        update(id) { it.copy(status = DownloadStatus.COMPLETED, progress = 1f, speed = "—", eta = "—", downloaded = "Complete", outputPath = path, error = "") }
                    } else {
                        update(id) { it.copy(status = DownloadStatus.FAILED, speed = "—", eta = "—", error = "The previous Termux download exited with code $code.") }
                    }
                    break
                }
                val pid = StorageAccess.readState(appContext, pidPath)?.trim().orEmpty()
                if (pid.isBlank() && log.isBlank() && System.currentTimeMillis() - startTime > 12_000) {
                    update(id) { it.copy(status = DownloadStatus.QUEUED, speed = "—", eta = "—", error = "Previous download did not start; it has been returned to the queue.") }
                    break
                }
                delay(1000)
            }
        } finally {
            active.remove(id)
            persist()
            pump()
        }
    }

    private fun parseProgress(id: String, log: String) {
        val line = log.lineSequence().lastOrNull { it.startsWith("DS_PROGRESS|") } ?: return
        val parts = line.split('|')
        if (parts.size < 6) return
        val percent = parts[1].filter { it.isDigit() || it == '.' }.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: 0f
        val speed = parts[2].trim().ifBlank { "—" }
        val eta = parts[3].trim().ifBlank { "—" }
        val downloaded = humanBytes(parts[4].trim().toLongOrNull() ?: 0L)
        update(id) { current ->
            if (current.status == DownloadStatus.PAUSED || current.status == DownloadStatus.STOPPED) current
            else current.copy(status = DownloadStatus.DOWNLOADING, progress = percent, speed = speed, eta = eta, downloaded = downloaded)
        }
    }

    private fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        synchronized(stateLock) {
            _items.value = _items.value.map { if (it.id == id) transform(it) else it }
            QueueStore.save(appContext, _items.value)
        }
    }

    private fun persist() {
        synchronized(stateLock) {
            QueueStore.save(appContext, _items.value)
        }
    }

    private fun humanBytes(value: Long): String {
        if (value <= 0) return "—"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = value.toDouble(); var index = 0
        while (v >= 1024 && index < units.lastIndex) { v /= 1024; index++ }
        return "%.1f %s".format(v, units[index])
    }

    private fun titleFromUrl(url: String): String = runCatching {
        android.net.Uri.parse(url).host?.removePrefix("www.")?.let { "Video from $it" } ?: "New download"
    }.getOrDefault("New download")
}
