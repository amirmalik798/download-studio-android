package com.downloadstudio.android.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.downloadstudio.android.data.QueueStore

object StorageAccess {
    fun termuxBasePath(context: Context): String? = QueueStore.basePath(context)
    fun destinationPath(context: Context): String? = QueueStore.basePath(context)?.trimEnd('/')?.takeIf { it.isNotBlank() }

    fun treeRoot(context: Context): DocumentFile? {
        val uri = QueueStore.treeUri(context)?.let(Uri::parse) ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    /** Verify the saved SAF folder still exists with persisted read/write access. */
    fun isDownloadLocationConfigured(context: Context): Boolean = runCatching {
        val rawUri = QueueStore.treeUri(context) ?: return false
        val uri = Uri.parse(rawUri)
        val persisted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        val root = treeRoot(context) ?: return false
        persisted && root.exists() && root.isDirectory && root.canWrite() &&
            !destinationPath(context).isNullOrBlank()
    }.getOrDefault(false)

    fun readState(context: Context, relativeName: String): String? = runCatching {
        val root = treeRoot(context) ?: return null
        val parts = relativeName.split('/').filter { it.isNotBlank() }
        var node = root
        for (part in parts.dropLast(1)) node = node.findFile(part) ?: return null
        val file = node.findFile(parts.last()) ?: return null
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    fun readStateTail(context: Context, relativeName: String, maxBytes: Long = 48L * 1024L): String? = runCatching {
        val root = treeRoot(context) ?: return null
        val parts = relativeName.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var node = root
        for (part in parts.dropLast(1)) node = node.findFile(part) ?: return null
        val file = node.findFile(parts.last()) ?: return null
        val input = context.contentResolver.openInputStream(file.uri) ?: return null
        input.buffered().use { stream ->
            val skipTarget = (file.length() - maxBytes).coerceAtLeast(0L)
            var skipped = 0L
            while (skipped < skipTarget) {
                val count = stream.skip(skipTarget - skipped)
                if (count > 0) skipped += count else {
                    if (stream.read() == -1) break
                    skipped++
                }
            }
            stream.bufferedReader().use { reader ->
                if (skipped > 0) reader.readLine()
                reader.readText()
            }
        }
    }.getOrNull()

    fun findDownloadedDocument(context: Context, outputPath: String): DocumentFile? = runCatching {
        val base = destinationPath(context)?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val cleanPath = outputPath.trim()
        if (!cleanPath.startsWith("$base/")) return null
        val relative = cleanPath.removePrefix("$base/").split('/').filter { it.isNotBlank() }
        if (relative.isEmpty() || relative.any { it == "." || it == ".." }) return null
        var node = treeRoot(context) ?: return null
        for (part in relative) node = node.findFile(part) ?: return null
        node.takeIf { it.exists() && it.isFile }
    }.getOrNull()

    fun mimeTypeForFileName(fileName: String): String? = runCatching {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        extension.takeIf { it.isNotBlank() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    }.getOrNull()

    fun termuxPathForTreeUri(uri: Uri): String? = runCatching {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "").trim('/')
        val base = if (volume.equals("primary", true)) "/storage/emulated/0" else "/storage/$volume"
        if (relative.split('/').any { it == ".." }) return null
        if (relative.isBlank()) base else "$base/$relative"
    }.getOrNull()
}
