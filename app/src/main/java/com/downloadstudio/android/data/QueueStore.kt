package com.downloadstudio.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object QueueStore {
    private const val PREFS = "download_studio_store"
    private const val KEY_QUEUE = "queue_v1"
    private const val KEY_TREE_URI = "destination_tree_uri"
    private const val KEY_BASE_PATH = "destination_base_path"
    private const val KEY_MAX = "max_concurrent"

    fun load(context: Context): List<DownloadItem> = runCatching {
        val raw = prefs(context).getString(KEY_QUEUE, "[]") ?: "[]"
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val status = runCatching { DownloadStatus.valueOf(o.optString("status", "QUEUED")) }.getOrDefault(DownloadStatus.QUEUED)
                val mode = runCatching { DownloadMode.valueOf(o.optString("mode", "VIDEO")) }.getOrDefault(DownloadMode.VIDEO)
                val saved = DownloadItem(
                    id = o.optString("id"), url = o.optString("url"), title = o.optString("title", "Video"),
                    mode = mode, status = status, progress = o.optDouble("progress", 0.0).toFloat(),
                    speed = o.optString("speed", "—"), eta = o.optString("eta", "—"),
                    downloaded = o.optString("downloaded", "—"), format = o.optString("format", ""),
                    outputPath = o.optString("outputPath", ""), error = o.optString("error", ""),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    completedAt = o.optLong("completedAt", 0L),
                    thumbnailUrl = o.optString("thumbnailUrl", ""), sourceUrl = o.optString("sourceUrl", o.optString("url"))
                )
                // Keep in-flight status: DownloadCoordinator reconnects to the Termux
                // log/exit files rather than starting a duplicate process on app restart.
                add(saved)
            }
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, items: List<DownloadItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("url", item.url); put("title", item.title)
                put("mode", item.mode.name); put("status", item.status.name)
                put("progress", item.progress.toDouble()); put("speed", item.speed); put("eta", item.eta)
                put("downloaded", item.downloaded); put("format", item.format)
                put("outputPath", item.outputPath); put("error", item.error); put("createdAt", item.createdAt); put("completedAt", item.completedAt); put("thumbnailUrl", item.thumbnailUrl); put("sourceUrl", item.sourceUrl)
            })
        }
        prefs(context).edit().putString(KEY_QUEUE, array.toString()).apply()
    }

    fun treeUri(context: Context): String? = prefs(context).getString(KEY_TREE_URI, null)
    fun basePath(context: Context): String? = prefs(context).getString(KEY_BASE_PATH, null)
    fun setTree(context: Context, uri: String, basePath: String) = prefs(context).edit().putString(KEY_TREE_URI, uri).putString(KEY_BASE_PATH, basePath).apply()
    fun maxConcurrent(context: Context): Int {
        val stored = prefs(context).getInt(KEY_MAX, 1)
        return if (stored in 1..2) stored else 1
    }
    fun setMaxConcurrent(context: Context, value: Int) = prefs(context).edit().putInt(KEY_MAX, value.coerceIn(1, 2)).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
