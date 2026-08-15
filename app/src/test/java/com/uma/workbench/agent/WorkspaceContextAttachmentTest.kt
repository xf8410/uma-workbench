package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceContextAttachmentTest {
    @Test
    fun rangeAttachmentKeepsExactRequestedTextAndCompleteFileMetadata() {
        val complete = "第一行\n第二行 SID=full-value\n第三行\n第四行"

        val attachment = WorkspaceContextAttachmentFactory.fromText(
            workspaceId = "workspace-1",
            uri = "content://workspace/source.kt",
            title = "source.kt",
            completeText = complete,
            startLine = 2,
            endLine = 3
        )

        assertEquals("第二行 SID=full-value\n第三行\n", attachment.content)
        assertEquals(2, attachment.startLine)
        assertEquals(3, attachment.endLine)
        assertEquals(4, attachment.totalLines)
        assertEquals(complete.length, attachment.completeCharacterCount)
        assertEquals(attachment.content.length, attachment.sentCharacterCount)
        assertEquals(64, attachment.sha256.length)
    }

    @Test
    fun composedPromptContainsExactUserTextAndActualAttachmentContent() {
        val attachment = WorkspaceContextAttachmentFactory.fromText(
            workspaceId = "workspace-1",
            uri = "content://workspace/a.md",
            title = "a.md",
            completeText = "alpha\nbeta\ngamma",
            startLine = 2,
            endLine = 3
        )

        val prompt = WorkspaceContextPromptComposer.compose("分析这个范围", listOf(attachment))

        assertTrue(prompt.startsWith("分析这个范围\n\n[本轮实际上下文附件]"))
        assertTrue(prompt.contains("--- 附件 a.md L2-L3 ---"))
        assertTrue(prompt.contains("workspaceId: workspace-1"))
        assertTrue(prompt.contains("uri: content://workspace/a.md"))
        assertTrue(prompt.contains("实际发送内容:\nbeta\ngamma\n"))
        assertFalse(prompt.contains("alpha\n"))
    }

    @Test
    fun metadataRecordsIdentityRangeAndBothCharacterCountsWithoutAttachmentBody() {
        val attachment = WorkspaceContextAttachmentFactory.fromText(
            workspaceId = "ws\"exact",
            uri = "content://workspace/path?name=a&line=1",
            title = "line\nfile.txt",
            completeText = "one\ntwo\nthree",
            startLine = 1,
            endLine = 2
        )

        val metadata = WorkspaceContextPromptComposer.metadataJson(listOf(attachment))

        assertNotNull(metadata)
        metadata!!
        assertTrue(metadata.contains("\"workspaceId\":\"ws\\\"exact\""))
        assertTrue(metadata.contains("\"title\":\"line\\nfile.txt\""))
        assertTrue(metadata.contains("\"startLine\":1"))
        assertTrue(metadata.contains("\"endLine\":2"))
        assertTrue(metadata.contains("\"totalLines\":3"))
        assertTrue(metadata.contains("\"completeCharacterCount\":13"))
        assertTrue(metadata.contains("\"sentCharacterCount\":8"))
        assertTrue(metadata.contains("\"sha256\":\"${attachment.sha256}\""))
        assertFalse(metadata.contains("one\\ntwo"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rangeStartingPastCompleteFileFailsExplicitly() {
        WorkspaceContextAttachmentFactory.fromText(
            workspaceId = "workspace-1",
            uri = "content://workspace/a.txt",
            title = "a.txt",
            completeText = "only one line",
            startLine = 2,
            endLine = 2
        )
    }

    @Test
    fun noAttachmentsLeavesUserPromptUnchangedAndHasNoMetadata() {
        assertEquals("原始问题", WorkspaceContextPromptComposer.compose("原始问题", emptyList()))
        assertEquals(null, WorkspaceContextPromptComposer.metadataJson(emptyList()))
    }
}
