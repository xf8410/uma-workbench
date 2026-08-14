package com.uma.workbench.imports

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.uma.workbench.audit.SourceKind
import java.security.MessageDigest

class SourceImporter(private val resolver: ContentResolver) {
    data class ImportedSource(val uri: Uri, val name: String, val size: Long?, val sha256: String, val kind: SourceKind)

    fun importSource(uri: Uri): ImportedSource {
        val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val displayName = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else "unnamed"
                val length = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                displayName to length
            }
        }
        val name = metadata?.first ?: "unnamed"
        val size = metadata?.second
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        } ?: error("无法读取文件：$uri")
        return ImportedSource(uri, name, size, digest.digest().joinToString("") { "%02x".format(it) }, classify(name))
    }

    private fun classify(name: String): SourceKind = when {
        name.endsWith(".so", true) -> SourceKind.SO
        name.contains("global-metadata", true) || name.endsWith(".dat", true) -> SourceKind.IL2CPP_METADATA
        name.endsWith(".db", true) || name.endsWith(".sqlite", true) -> SourceKind.SQLITE
        name.endsWith(".zip", true) || name.endsWith(".7z", true) || name.endsWith(".tar", true) -> SourceKind.ARCHIVE
        name.contains("master", true) -> SourceKind.MASTER
        else -> SourceKind.SESSION
    }
}
