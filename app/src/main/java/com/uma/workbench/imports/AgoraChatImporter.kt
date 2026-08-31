package com.uma.workbench.imports

import android.content.Context
import android.net.Uri
import com.uma.workbench.data.ConversationEntity
import com.uma.workbench.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Agora-Workbench 备份导入器。
 *
 * Agora 导出包是 ZIP：manifest.json + conversations.json（含
 * {"conversations":[...],"messages":[...]} 流式拼接，无换行）。
 * 也直接接受裸的 conversations.json。
 *
 * 映射规则：
 * - chat.id   → conversation.id（加 "agora-" 前缀防碰撞）
 * - participant USER/MODEL → role user/assistant
 * - status SUCCESS/ERROR  → COMPLETED/INTERRUPTED
 * - thoughts（思考过程）等扩展字段存入 toolCallsJson（JSON 包装，展示层解析失败静默忽略）
 * - parentId 分支结构不适用线性对话，按 timestamp 排序展开
 */
object AgoraChatImporter {

    data class ImportResult(val conversations: Int, val messages: Int)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 落库由调用方通过回调注入，导入器本身不依赖 Room。 */
    suspend fun import(
        context: Context,
        uri: Uri,
        workspaceId: String,
        writeConversation: suspend (ConversationEntity) -> Unit,
        writeMessage: suspend (MessageEntity) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val conversationsJson = readConversationsJson(context, uri)
            ?: error("备份里找不到 conversations.json（确认这是 Agora-Workbench 导出的 zip 或裸 conversations.json）")
        parse(conversationsJson, workspaceId, writeConversation, writeMessage)
    }

    /** 解析核心：不依赖 Android 框架，JVM 单测直测。 */
    suspend fun parse(
        conversationsJson: String,
        workspaceId: String,
        writeConversation: suspend (ConversationEntity) -> Unit,
        writeMessage: suspend (MessageEntity) -> Unit
    ): ImportResult {
        val root = runCatching { json.parseToJsonElement(conversationsJson).jsonObject }
            .getOrElse { error("conversations.json 解析失败：${it.message}") }
        val chats = (root["conversations"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?: error("conversations.json 缺少 conversations 数组")
        val messages = (root["messages"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

        val messagesByConversation = messages.groupBy { it.string("conversationId") ?: "" }

        var importedConversations = 0
        var importedMessages = 0

        chats.forEach { chat ->
            val agoraId = chat.string("id") ?: return@forEach
            val title = chat.string("title")?.takeIf { it.isNotBlank() } ?: "Agora 对话 $agoraId"
            val updatedAt = chat.long("lastUpdated") ?: System.currentTimeMillis()
            val convMessages = (messagesByConversation[agoraId] ?: emptyList())
                .sortedBy { it.long("timestamp") ?: 0L }

            val convId = "agora-$agoraId"
            val firstAt = convMessages.firstOrNull()?.long("timestamp") ?: updatedAt
            val tokenTotal = convMessages.sumOf { it.long("tokenCount") ?: 0L }
            val preview = convMessages.lastOrNull()?.string("text")?.take(80)

            writeConversation(
                ConversationEntity(
                    id = convId,
                    title = title,
                    createdAt = firstAt,
                    updatedAt = updatedAt,
                    status = "ACTIVE",
                    workspaceId = workspaceId,
                    agentMode = "ASK",
                    lastMessagePreview = preview,
                    messageCount = convMessages.size,
                    tokenTotal = tokenTotal
                )
            )

            convMessages.forEachIndexed { index, m ->
                val participant = m.string("participant") ?: "MODEL"
                val role = when (participant.uppercase()) {
                    "USER" -> "user"
                    else -> "assistant"
                }
                val text = m.string("text") ?: ""
                if (text.isBlank() && m.string("thoughts").isNullOrBlank()) return@forEachIndexed
                val status = when ((m.string("status") ?: "SUCCESS").uppercase()) {
                    "SUCCESS" -> "COMPLETED"
                    else -> "INTERRUPTED"
                }
                val extras = buildMap {
                    m.string("thoughts")?.takeIf { it.isNotBlank() }?.let { put("agoraThoughts", it) }
                    m.string("thoughtTitle")?.takeIf { it.isNotBlank() }?.let { put("agoraThoughtTitle", it) }
                    m.string("toolCallJson")?.takeIf { it.isNotBlank() }?.let { put("agoraToolCalls", it) }
                    m.string("attachmentMeta")?.takeIf { it.isNotBlank() }?.let { put("agoraAttachmentMeta", it) }
                }
                writeMessage(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = convId,
                        runId = null,
                        requestId = null,
                        sequence = index + 1L,
                        role = role,
                        content = text,
                        status = status,
                        createdAt = m.long("timestamp") ?: updatedAt,
                        toolCallsJson = if (extras.isEmpty()) null else extras.entries.joinToString(",") {
                            "\"${it.key}\":${JsonPrimitive(it.value)}"
                        }.let { "{$it}" },
                        tokenCount = m.long("tokenCount")?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                        modelUsed = m.string("modelName")?.takeIf { it.isNotBlank() }
                    )
                )
                importedMessages++
            }
            importedConversations++
        }

        return ImportResult(importedConversations, importedMessages)
    }

    private fun readConversationsJson(context: Context, uri: Uri): String? {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        PushbackInputStream(stream, 4).use { pushed ->
            val head = ByteArray(4)
            val n = pushed.read(head)
            if (n < 4) {
                return String(head, 0, n.coerceAtLeast(0), Charsets.UTF_8).takeIf { it.isNotBlank() }
            }
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                pushed.unread(head)
                return readZip(pushed)
            }
            val rest = pushed.readBytes()
            return String(head, Charsets.UTF_8) + String(rest, Charsets.UTF_8)
        }
    }

    private fun readZip(input: InputStream): String? {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == "conversations.json") {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(name: String): Long? =
        (get(name) as? JsonPrimitive)?.longOrNull ?: (get(name) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}
