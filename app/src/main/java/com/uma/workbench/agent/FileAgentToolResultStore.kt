package com.uma.workbench.agent

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AgentToolResultMetadata(
    val resultId: String,
    val toolName: String,
    val workspaceId: String,
    val conversationId: String,
    val createdAt: Long,
    val characterCount: Int,
    val utf8ByteCount: Long,
    val sha256: String
)

/**
 * Durable complete-result store. A result becomes visible only after both its exact UTF-8 body and
 * metadata have been fsynced and atomically renamed. Every read verifies length and SHA-256 before
 * returning an exact Kotlin character range, so a process restart cannot turn a partial file into a
 * successful result.
 */
class FileAgentToolResultStore(
    private val root: File,
    private val workspaceId: String,
    private val conversationId: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = false }
) : AgentToolResultStore {
    init { require(root.mkdirs() || root.isDirectory) { "无法创建完整工具结果目录 ${root.absolutePath}" } }

    override fun put(completeContent: String, toolName: String): String {
        require(completeContent.isNotEmpty()) { "完整工具结果不能为空" }
        require(toolName.isNotBlank()) { "toolName 不能为空" }
        val resultId = UUID.randomUUID().toString()
        val bytes = completeContent.toByteArray(Charsets.UTF_8)
        val metadata = AgentToolResultMetadata(resultId, toolName, workspaceId, conversationId, nowMillis(), completeContent.length, bytes.size.toLong(), sha256(bytes))
        val body = bodyFile(resultId)
        val meta = metadataFile(resultId)
        atomicWrite(body, bytes)
        try {
            atomicWrite(meta, json.encodeToString(metadata).toByteArray(Charsets.UTF_8))
        } catch (error: Throwable) {
            body.delete()
            throw error
        }
        return resultId
    }

    override fun read(resultId: String, offset: Int, limit: Int): AgentToolResultPage {
        requireUuid(resultId)
        require(offset >= 0) { "offset 必须是非负整数" }
        require(limit > 0) { "limit 必须是正整数" }
        val metaFile = metadataFile(resultId)
        val bodyFile = bodyFile(resultId)
        require(metaFile.isFile && bodyFile.isFile) { "找不到完整工具结果 $resultId" }
        val metadata = json.decodeFromString<AgentToolResultMetadata>(metaFile.readText(Charsets.UTF_8))
        require(metadata.resultId == resultId && metadata.workspaceId == workspaceId && metadata.conversationId == conversationId) { "工具结果归属校验失败 $resultId" }
        val bytes = bodyFile.readBytes()
        require(bytes.size.toLong() == metadata.utf8ByteCount) { "工具结果字节长度校验失败 $resultId" }
        require(sha256(bytes) == metadata.sha256) { "工具结果 SHA-256 校验失败 $resultId" }
        val completeContent = String(bytes, Charsets.UTF_8)
        require(completeContent.length == metadata.characterCount) { "工具结果字符长度校验失败 $resultId" }
        require(offset <= completeContent.length) { "offset $offset 超过完整结果长度 ${completeContent.length}" }
        val end = (offset.toLong() + limit).coerceAtMost(completeContent.length.toLong()).toInt()
        return AgentToolResultPage(resultId, completeContent.substring(offset, end), offset, end, completeContent.length, end == completeContent.length, end.takeIf { it < completeContent.length }, metadata.sha256)
    }

    fun metadata(resultId: String): AgentToolResultMetadata {
        requireUuid(resultId)
        return json.decodeFromString(metadataFile(resultId).readText(Charsets.UTF_8))
    }

    private fun bodyFile(id: String) = File(root, "$id.result.utf8")
    private fun metadataFile(id: String) = File(root, "$id.metadata.json")
    private fun requireUuid(id: String) { require(runCatching { UUID.fromString(id) }.isSuccess) { "resultId 非法" } }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File(root, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            check(temporary.renameTo(target)) { "无法原子提交 ${target.name}" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
