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

    /** Remove a temporary state file after a preview or health check has consumed it. */
    fun deleteState(context: Context, relativeName: String): Boolean = runCatching {
        val root = treeRoot(context) ?: return false
        val parts = relativeName.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) return false
        var node = root
        for (part in parts.dropLast(1)) node = node.findFile(part) ?: return false
        val file = node.findFile(parts.last()) ?: return false
        file.delete()
    }.getOrDefault(false)

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


    data class FolderScanResult(val matches: List<String>, val complete: Boolean)

    private data class TreeChild(val uri: Uri, val name: String, val isDirectory: Boolean)

    /**
     * Query the DocumentsProvider directly so a failed/null provider query is
     * distinguishable from a successfully enumerated empty directory. This is
     * essential: an unreadable folder must never cause queue entries to vanish.
     */
    private fun queryTreeChildren(context: Context, parentUri: Uri): List<TreeChild>? = runCatching {
        val treeUri = QueueStore.treeUri(context)?.let(Uri::parse) ?: return null
        val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null) ?: return null
        cursor.use { rows ->
            val idColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            buildList {
                while (rows.moveToNext()) {
                    val documentId = rows.getString(idColumn) ?: continue
                    val displayName = rows.getString(nameColumn) ?: continue
                    val mimeType = rows.getString(mimeColumn).orEmpty()
                    add(
                        TreeChild(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            name = displayName,
                            isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                        )
                    )
                }
            }
        }
    }.getOrNull()

    /**
     * Search only the configured document tree for a previously downloaded file.
     * Null or incomplete results mean "unknown", not "missing"; callers must
     * preserve queue history in those cases.
     */
    fun scanConfiguredDownloadsFolder(
        context: Context,
        fileName: String,
        maxEntries: Int = 8000,
        maxDepth: Int = 8
    ): FolderScanResult? = runCatching {
        if (fileName.isBlank() || fileName.contains('/') || fileName == "." || fileName == "..") return null
        val base = destinationPath(context)?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val root = treeRoot(context) ?: return null
        if (!root.exists() || !root.isDirectory) return null

        val matches = linkedSetOf<String>()
        var visited = 0
        var complete = true

        fun visit(directoryUri: Uri, relative: String, depth: Int) {
            if (depth > maxDepth) {
                complete = false
                return
            }
            val children = queryTreeChildren(context, directoryUri)
            if (children == null) {
                complete = false
                return
            }
            for (child in children) {
                visited++
                if (visited > maxEntries) {
                    complete = false
                    return
                }
                if (!child.isDirectory && child.name == fileName) {
                    matches.add("$base/${relative}${child.name}")
                } else if (child.isDirectory && !(depth == 0 && child.name == "DownloadStudioState")) {
                    val next = if (relative.isEmpty()) "${child.name}/" else "$relative${child.name}/"
                    visit(child.uri, next, depth + 1)
                    if (!complete) return
                }
            }
        }

        visit(root.uri, "", 0)
        FolderScanResult(matches.toList(), complete)
    }.getOrNull()

    /** Resolve the parent directory of a completed download for file-manager intents. */
    fun findDownloadedFolder(context: Context, outputPath: String): DocumentFile? =
        findDownloadedDocument(context, outputPath)?.parentFile

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
