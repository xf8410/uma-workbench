package com.uma.workbench.ui

import com.uma.workbench.data.AuditSourceEntity
import com.uma.workbench.data.WorkItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPresentationTest {
    @Test fun joinsSourceToDurableCheckpointWithoutShorteningMetadata() {
        val sha = "ab".repeat(32)
        val error = "line one\nline two with SID-full-value-and-token"
        val source = AuditSourceEntity("source-1", "content://complete-uri", "SESSION", "capture.jsonl", sha256 = sha, workspaceId = "ws", fileSize = 9_999_999_999L)
        val work = WorkItemEntity("work-1", "SOURCE_ANALYSIS", sourceId = source.id, stage = "TIMELINE_INDEX", status = "RETRY_WAIT", progress = 50, checkpoint = "session:jsonl:500", error = error, updatedAt = 1, workspaceId = "ws")
        val row = ImportPresentation.rows(listOf(source), listOf(work)).single()
        assertEquals(sha, row.sha256)
        assertEquals(error, row.error)
        assertEquals("session:jsonl:500", row.checkpoint)
        val detail = ImportPresentation.detail(row)
        assertTrue(detail.contains(sha))
        assertTrue(detail.contains(error))
        assertTrue(detail.contains("9999999999"))
    }

    @Test fun exposesEveryImportedSourceWithoutRecordLimit() {
        val sources = (0 until 750).map { index -> AuditSourceEntity("s$index", "content://$index", "ARCHIVE", "file-$index.apk", workspaceId = "ws") }
        assertEquals(750, ImportPresentation.rows(sources, emptyList()).size)
    }
}
