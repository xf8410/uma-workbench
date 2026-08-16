package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceContextAttachmentTest {
    @Test
    fun attachmentKeepsCompleteFileContent() {
        val complete = "第一行\n第二行 SID=full-value\n第三行\n第四行"

        val attachment = WorkspaceContextAttachmentFactory.fromCompleteText(
            workspaceId = "workspace-1",
            uri = "content://workspace/source.kt",
            title = "source.kt",
            completeText = complete
        )

        assertEquals(complete, attachment.content)
        assertEquals(1, attachment.startLine)
        assertEquals(4, attachment.endLine)
        assertEquals(4, attachment.totalLines)
        assertEquals(complete.length, attachment.completeCharacterCount)
        assertEquals(complete.length, attachment.sentCharacterCount)
        assertEquals(64, attachment.sha256.length)
    }

    @Test
    fun composedPromptContainsExactUserTextAndCompleteAttachmentContent() {
        val attachment = WorkspaceContextAttachmentFactory.fromCompleteText(
            workspaceId = "workspace-1",
            uri = "content://workspace/a.md",
            title = "a.md",
            completeText = "alpha\nbeta\ngamma"
        )

        val prompt = WorkspaceContextPromptComposer.compose("分析这个文件", listOf(attachment))

        assertTrue(prompt.startsWith("分析这个文件\n\n[本轮实际上下文附件]"))
        assertTrue(prompt.contains("--- 完整附件 a.md ---"))
        assertTrue(prompt.contains("workspaceId: workspace-1"))
        assertTrue(prompt.contains("uri: content://workspace/a.md"))
        assertTrue(prompt.contains("完整内容:\nalpha\nbeta\ngamma\n"))
    }

    @Test
    fun metadataRecordsIdentityAndCompleteAttachmentBody() {
        val attachment = WorkspaceContextAttachmentFactory.fromCompleteText(
            workspaceId = "ws\"exact",
            uri = "content://workspace/path?name=a&line=1",
            title = "line\nfile.txt",
            completeText = "one\ntwo\nthree"
        )

        val metadata = WorkspaceContextPromptComposer.metadataJson(listOf(attachment))

        assertNotNull(metadata)
        metadata!!
        assertTrue(metadata.contains("\"workspaceId\":\"ws\\\"exact\""))
        assertTrue(metadata.contains("\"title\":\"line\\nfile.txt\""))
        assertTrue(metadata.contains("\"startLine\":1"))
        assertTrue(metadata.contains("\"endLine\":3"))
        assertTrue(metadata.contains("\"totalLines\":3"))
        assertTrue(metadata.contains("\"completeCharacterCount\":13"))
        assertTrue(metadata.contains("\"sentCharacterCount\":13"))
        assertTrue(metadata.contains("\"sha256\":\"${attachment.sha256}\""))
        assertTrue(metadata.contains("\"content\":\"one\\ntwo\\nthree\""))
    }

    @Test
    fun compatibilityEntryPointStillReturnsCompleteContent() {
        val complete = "one\ntwo\nthree"
        val attachment = WorkspaceContextAttachmentFactory.fromText("workspace-1", "content://workspace/a.txt", "a.txt", complete, 2, 2)
        assertEquals(complete, attachment.content)
        assertEquals(1, attachment.startLine)
        assertEquals(3, attachment.endLine)
    }

    @Test
    fun noAttachmentsLeavesUserPromptUnchangedAndHasNoMetadata() {
        assertEquals("原始问题", WorkspaceContextPromptComposer.compose("原始问题", emptyList()))
        assertEquals(null, WorkspaceContextPromptComposer.metadataJson(emptyList()))
    }
}
