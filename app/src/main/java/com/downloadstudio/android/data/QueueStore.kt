package com.downloadstudio.android.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

object QueueStore {
    // Keep SAF URI permissions and destination settings separate from the
    // queue preference file so Android Auto Backup never restores stale URIs.
    private const val SETTINGS_PREFS = "download_studio_store"
    private const val QUEUE_PREFS = "download_studio_queue"
    private const val KEY_QUEUE = "queue_v2"
    private const val KEY_MAX = "max_concurrent"
    private const val KEY_LAST_DESTINATION = "last_destination_path"

    private const val LEGACY_KEY_QUEUE = "queue_v1"
    private const val LEGACY_KEY_MAX = "max_concurrent"

    private const val STATE_DIRECTORY = "DownloadStudioState"
    private const val BACKUP_FILE = "queue_backup.json"

    private val migrationLock = Any()
    private val backupLock = Any()
    @Volatile private var lastExternalFingerprint: String? = null
    private val backupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DownloadStudio-queue-backup").apply { isDaemon = true }
    }

    fun load(context: Context): List<DownloadItem> {
        migrateLegacyPreferences(context)
        val raw = queuePrefs(context).getString(KEY_QUEUE, "[]") ?: "[]"
        return decodeQueue(raw)
    }

    fun save(context: Context, items: List<DownloadItem>) {
        migrateLegacyPreferences(context)
        val raw = encodeQueue(items).toString()
        queuePrefs(context).edit().putString(KEY_QUEUE, raw).apply()
        scheduleExternalBackup(context.applicationContext, raw, externalFingerprint(items), force = false)
    }

    /**
     * Restore the local shared-storage snapshot only when the app has no queue
     * preference at all. A restored (even intentionally empty) Android backup
     * remains authoritative and is never overwritten by an older sidecar.
     */
    fun restoreFromExternal(context: Context): List<DownloadItem>? = runCatching {
        migrateLegacyPreferences(context)
        val prefs = queuePrefs(context)
        if (prefs.contains(KEY_QUEUE)) return null

        val rawTreeUri = treeUri(context) ?: return null
        val tree = Uri.parse(rawTreeUri)
        val permission = context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
        if (!permission) return null

        val root = DocumentFile.fromTreeUri(context, tree) ?: return null
        if (!root.exists() || !root.isDirectory) return null
        val stateDirectory = root.findFile(STATE_DIRECTORY)?.takeIf { it.isDirectory } ?: return null
        val backup = stateDirectory.findFile(BACKUP_FILE)?.takeIf { it.isFile } ?: return null
        val raw = context.contentResolver.openInputStream(backup.uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() } ?: return null
        decodeQueue(raw)
    }.getOrNull()

    /** Force a fresh local snapshot after a folder is selected or a queue is restored. */
    fun mirrorBackup(context: Context, items: List<DownloadItem>, force: Boolean = true) {
        val raw = encodeQueue(items).toString()
        scheduleExternalBackup(context.applicationContext, raw, externalFingerprint(items), force)
    }

    private fun decodeQueue(raw: String): List<DownloadItem> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val status = runCatching {
                    DownloadStatus.valueOf(o.optString("status", "QUEUED"))
                }.getOrDefault(DownloadStatus.QUEUED)
                val mode = runCatching {
                    DownloadMode.valueOf(o.optString("mode", "VIDEO"))
                }.getOrDefault(DownloadMode.VIDEO)
                add(
                    DownloadItem(
                        id = o.optString("id"),
                        url = o.optString("url"),
                        title = o.optString("title", "Video"),
                        mode = mode,
                        status = status,
                        progress = o.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
                        speed = o.optString("speed", "—"),
                        eta = o.optString("eta", "—"),
                        downloaded = o.optString("downloaded", "—"),
                        totalSize = o.optString("totalSize", "—"),
                        processingStage = o.optString("processingStage", ""),
                        format = o.optString("format", ""),
                        outputPath = o.optString("outputPath", ""),
                        error = o.optString("error", ""),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        completedAt = o.optLong("completedAt", 0L),
                        thumbnailUrl = o.optString("thumbnailUrl", "")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeQueue(items: List<DownloadItem>): JSONArray = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("url", item.url)
                put("title", item.title)
                put("mode", item.mode.name)
                put("status", item.status.name)
                put("progress", item.progress.toDouble())
                put("speed", item.speed)
                put("eta", item.eta)
                put("downloaded", item.downloaded)
                put("totalSize", item.totalSize)
                put("processingStage", item.processingStage)
                put("format", item.format)
                put("outputPath", item.outputPath)
                put("error", item.error)
                put("createdAt", item.createdAt)
                put("completedAt", item.completedAt)
                put("thumbnailUrl", item.thumbnailUrl)
            })
        }
    }

    /**
     * The external mirror is rewritten for queue/status changes, not every
     * two-second progress tick. The SharedPreferences/Auto Backup copy still
     * receives the latest live progress values.
     */
    private fun externalFingerprint(items: List<DownloadItem>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("url", item.url)
                put("title", item.title)
                put("mode", item.mode.name)
                put("status", item.status.name)
                put("totalSize", item.totalSize)
                put("processingStage", item.processingStage)
                put("format", item.format)
                put("outputPath", item.outputPath)
                put("error", item.error)
                put("createdAt", item.createdAt)
                put("completedAt", item.completedAt)
                put("thumbnailUrl", item.thumbnailUrl)
            })
        }
    }.toString()

    private fun scheduleExternalBackup(
        context: Context,
        raw: String,
        fingerprint: String,
        force: Boolean
    ) {
        synchronized(backupLock) {
            if (!force && fingerprint == lastExternalFingerprint) return
            lastExternalFingerprint = fingerprint
        }

        backupExecutor.execute {
            val saved = runCatching { writeExternalBackup(context, raw) }.getOrDefault(false)
            if (!saved) {
                synchronized(backupLock) {
                    if (lastExternalFingerprint == fingerprint) lastExternalFingerprint = null
                }
            }
        }
    }

    private fun writeExternalBackup(context: Context, raw: String): Boolean {
        val rawTreeUri = treeUri(context) ?: return false
        val tree = Uri.parse(rawTreeUri)
        val permission = context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
        if (!permission) return false

        val root = DocumentFile.fromTreeUri(context, tree) ?: return false
        if (!root.exists() || !root.isDirectory || !root.canWrite()) return false

        val stateDirectory = root.findFile(STATE_DIRECTORY)?.takeIf { it.isDirectory }
            ?: root.createDirectory(STATE_DIRECTORY)
            ?: return false
        val backup = stateDirectory.findFile(BACKUP_FILE)?.takeIf { it.isFile }
            ?: stateDirectory.createFile("application/json", BACKUP_FILE)
            ?: return false
        val output = context.contentResolver.openOutputStream(backup.uri, "wt") ?: return false
        output.bufferedWriter(Charsets.UTF_8).use { it.write(raw) }
        true
    }

    private fun migrateLegacyPreferences(context: Context) {
        synchronized(migrationLock) {
            val old = settingsPrefs(context)
            val current = queuePrefs(context)
            val editor = current.edit()
            var changed = false

            if (!current.contains(KEY_QUEUE) && old.contains(LEGACY_KEY_QUEUE)) {
                editor.putString(KEY_QUEUE, old.getString(LEGACY_KEY_QUEUE, "[]") ?: "[]")
                changed = true
            }
            if (!current.contains(KEY_MAX) && old.contains(LEGACY_KEY_MAX)) {
                editor.putInt(KEY_MAX, old.getInt(LEGACY_KEY_MAX, 1).coerceIn(1, 2))
                changed = true
            }
            if (!current.contains(KEY_LAST_DESTINATION)) {
                old.getString("destination_base_path", null)?.let {
                    editor.putString(KEY_LAST_DESTINATION, it)
                    changed = true
                }
            }

            val migrationSaved = !changed || editor.commit()
            if (migrationSaved && (old.contains(LEGACY_KEY_QUEUE) || old.contains(LEGACY_KEY_MAX))) {
                old.edit().remove(LEGACY_KEY_QUEUE).remove(LEGACY_KEY_MAX).apply()
            }
        }
    }

    fun treeUri(context: Context): String? = settingsPrefs(context).getString("destination_tree_uri", null)
    fun basePath(context: Context): String? = settingsPrefs(context).getString("destination_base_path", null)

    fun previousDestinationPath(context: Context): String? {
        migrateLegacyPreferences(context)
        return queuePrefs(context).getString(KEY_LAST_DESTINATION, null)
            ?: basePath(context)
    }

    fun setTree(context: Context, uri: String, basePath: String) {
        settingsPrefs(context).edit()
            .putString("destination_tree_uri", uri)
            .putString("destination_base_path", basePath)
            .apply()
        queuePrefs(context).edit().putString(KEY_LAST_DESTINATION, basePath).apply()
    }

    fun maxConcurrent(context: Context): Int {
        migrateLegacyPreferences(context)
        val stored = queuePrefs(context).getInt(KEY_MAX, 1)
        return if (stored in 1..2) stored else 1
    }

    fun setMaxConcurrent(context: Context, value: Int) {
        migrateLegacyPreferences(context)
        queuePrefs(context).edit().putInt(KEY_MAX, value.coerceIn(1, 2)).apply()
    }

    private fun settingsPrefs(context: Context) =
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

    private fun queuePrefs(context: Context) =
        context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
}
