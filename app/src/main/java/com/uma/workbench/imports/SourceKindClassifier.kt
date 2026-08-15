package com.uma.workbench.imports

import com.uma.workbench.audit.SourceKind

/** Pure filename classification shared by the SAF importer and unit tests. */
object SourceKindClassifier {
    fun classify(name: String): SourceKind {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".apk") || lower.endsWith(".zip") || lower.endsWith(".tar") -> SourceKind.ARCHIVE
            lower.endsWith(".so") -> SourceKind.SO
            "global-metadata" in lower || lower.endsWith(".dat") -> SourceKind.IL2CPP_METADATA
            lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3") -> SourceKind.SQLITE
            "master" in lower -> SourceKind.MASTER
            lower.endsWith(".jsonl") || lower.endsWith(".ndjson") || lower.endsWith(".session") || lower.endsWith(".log") -> SourceKind.SESSION
            else -> SourceKind.SESSION
        }
    }
}
