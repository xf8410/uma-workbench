package com.uma.workbench.agent

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The document that is actually active in the workspace UI. */
data class ActiveWorkspaceDocument(
    val workspaceId: String,
    val uri: String,
    val title: String
)

/** Shared UI bridge containing the active document identity. */
object ActiveWorkspaceDocumentBridge {
    private val _document = MutableStateFlow<ActiveWorkspaceDocument?>(null)
    val document: StateFlow<ActiveWorkspaceDocument?> = _document.asStateFlow()

    fun publish(document: ActiveWorkspaceDocument?) {
        _document.value = document
    }
}

data class WorkspaceContextAttachment(
    val id: String = UUID.randomUUID().toString(),
    val workspaceId: String,
    val kind: String = "COMPLETE_FILE",
    val uri: String,
    val title: String,
    val startLine: Int,
    val endLine: Int,
    val totalLines: Int,
    val completeCharacterCount: Int,
    val content: String,
    val sha256: String
) {
    init {
        require(startLine == 1)
        require(endLine == totalLines)
        require(totalLines >= 1)
        require(completeCharacterCount == content.length)
    }

    val sentCharacterCount: Int get() = content.length
}

object WorkspaceContextAttachmentFactory {
    fun fromCompleteText(
        workspaceId: String,
        uri: String,
        title: String,
        completeText: String
    ): WorkspaceContextAttachment {
        val totalLines = completeText.count { it == '\n' } + 1
        return WorkspaceContextAttachment(
            workspaceId = workspaceId,
            uri = uri,
            title = title,
            startLine = 1,
            endLine = totalLines,
            totalLines = totalLines,
            completeCharacterCount = completeText.length,
            content = completeText,
            sha256 = sha256(completeText)
        )
    }

    fun fromText(
        workspaceId: String,
        uri: String,
        title: String,
        completeText: String,
        startLine: Int,
        endLine: Int
    ): WorkspaceContextAttachment {
        require(startLine >= 1) { "起始行必须大于 0" }
        require(endLine >= startLine) { "结束行不能小于起始行" }
        return fromCompleteText(workspaceId, uri, title, completeText)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

object WorkspaceContextPromptComposer {
    fun compose(userText: String, attachments: List<WorkspaceContextAttachment>): String {
        if (attachments.isEmpty()) return userText
        return buildString {
            append(userText)
            append("\n\n[本轮实际上下文附件]\n")
            attachments.forEach { attachment ->
                append("\n--- 完整附件 ${attachment.title} ---\n")
                append("workspaceId: ${attachment.workspaceId}\n")
                append("uri: ${attachment.uri}\n")
                append("完整字符数: ${attachment.completeCharacterCount}\n")
                append("完整行数: ${attachment.totalLines}\n")
                append("文件SHA-256: ${attachment.sha256}\n")
                append("完整内容:\n")
                append(attachment.content)
                if (!attachment.content.endsWith('\n')) append('\n')
                append("--- 完整附件结束 ---\n")
            }
        }
    }

    fun metadataJson(attachments: List<WorkspaceContextAttachment>): String? {
        if (attachments.isEmpty()) return null
        return attachments.joinToString(prefix = "[", postfix = "]") { attachment ->
            "{" + listOf(
                "\"id\":\"${escape(attachment.id)}\"",
                "\"workspaceId\":\"${escape(attachment.workspaceId)}\"",
                "\"kind\":\"${escape(attachment.kind)}\"",
                "\"uri\":\"${escape(attachment.uri)}\"",
                "\"title\":\"${escape(attachment.title)}\"",
                "\"startLine\":${attachment.startLine}",
                "\"endLine\":${attachment.endLine}",
                "\"totalLines\":${attachment.totalLines}",
                "\"completeCharacterCount\":${attachment.completeCharacterCount}",
                "\"sentCharacterCount\":${attachment.sentCharacterCount}",
                "\"sha256\":\"${attachment.sha256}\"",
                "\"content\":\"${escape(attachment.content)}\""
            ).joinToString(",") + "}"
        }
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }
}
