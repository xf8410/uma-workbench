package com.uma.workbench.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileAgentToolResultStoreTest {
    @Test fun survivesStoreRecreationAndReassemblesUnicodeExactly() {
        val root = createTempDir(prefix = "agent-results-")
        try {
            val first = FileAgentToolResultStore(root, "workspace", "conversation", nowMillis = { 123L })
            val original = "嵌套类 A/B+C\n0123456789\nemoji=🐎"
            val id = first.put(original, "read_so_snapshot")
            val restarted = FileAgentToolResultStore(root, "workspace", "conversation")
            val pages = mutableListOf<String>()
            var offset = 0
            do {
                val page = restarted.read(id, offset, 5)
                pages += page.content
                offset = page.nextOffset ?: page.endOffsetExclusive
            } while (!page.complete)
            assertEquals(original, pages.joinToString(""))
            val metadata = restarted.metadata(id)
            assertEquals(original.length, metadata.characterCount)
            assertEquals("read_so_snapshot", metadata.toolName)
            assertEquals(64, metadata.sha256.length)
        } finally { root.deleteRecursively() }
    }

    @Test fun detectsBodyCorruptionInsteadOfReturningPartialSuccess() {
        val root = createTempDir(prefix = "agent-results-corrupt-")
        try {
            val store = FileAgentToolResultStore(root, "w", "c")
            val id = store.put("complete original value", "read_current_file")
            File(root, "$id.result.utf8").appendText("corruption")
            val error = runCatching { FileAgentToolResultStore(root, "w", "c").read(id, 0, 10) }.exceptionOrNull()
            assertTrue(error?.message?.contains("校验失败") == true)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsCrossConversationResultAccess() {
        val root = createTempDir(prefix = "agent-results-owner-")
        try {
            val id = FileAgentToolResultStore(root, "w", "c1").put("secret", "read_doc")
            val error = runCatching { FileAgentToolResultStore(root, "w", "c2").read(id, 0, 10) }.exceptionOrNull()
            assertTrue(error?.message?.contains("归属校验失败") == true)
        } finally { root.deleteRecursively() }
    }
}
