package com.downloadstudio.android.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.downloadstudio.android.data.QueueStore

object StorageAccess {
    fun termuxBasePath(context: Context): String? = QueueStore.basePath(context)
    fun destinationPath(context: Context): String? = QueueStore.basePath(context)?.trimEnd('/')?.takeIf { it.isNotBlank() }

    fun treeRoot(context: Context): DocumentFile? {
        val uri = QueueStore.treeUri(context)?.let(Uri::parse) ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    fun readState(context: Context, relativeName: String): String? = runCatching {
        val root = treeRoot(context) ?: return null
        val parts = relativeName.split('/').filter { it.isNotBlank() }
        var node = root
        for (part in parts.dropLast(1)) node = node.findFile(part) ?: return null
        val file = node.findFile(parts.last()) ?: return null
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
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
