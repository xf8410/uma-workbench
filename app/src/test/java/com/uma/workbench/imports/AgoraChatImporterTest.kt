package com.uma.workbench.imports

import com.uma.workbench.data.ConversationEntity
import com.uma.workbench.data.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class AgoraChatImporterTest {

    /** 与 Agora DataExporter 输出一致的备份：zip + 流式拼接的 conversations.json。 */
    private fun agoraBackupJson(): String = """{"conversations":[{"id":"c1","title":"拉面杯机制","lastUpdated":1724000000000,"modelId":"gpt-x"},{"id":"c2","title":"空对话","lastUpdated":1724000000001}],"messages":[
{"id":"m1","conversationId":"c1","text":"帮我分析","tokenCount":12,"status":"SUCCESS","participant":"USER","timestamp":1723999000000},
{"id":"m2","conversationId":"c1","text":"分析如下","thoughts":"先想一下","tokenCount":34,"status":"SUCCESS","participant":"MODEL","timestamp":1723999100000,"modelName":"gpt-x"},
{"id":"m3","conversationId":"c1","text":"生成中断","status":"ERROR","participant":"MODEL","timestamp":1723999200000}]}"""

    private fun agoraZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"agora_export_version":1,"app_version":"1.0","categories":["conversations"]}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("conversations.json"))
            zip.write(agoraBackupJson().toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private class Sink {
        val conversations = mutableListOf<ConversationEntity>()
        val messages = mutableListOf<MessageEntity>()
    }

    @Test fun parsesChatsAndMapsParticipantRoles() = runBlocking {
        val sink = Sink()
        val result = AgoraChatImporter.parse(
            agoraBackupJson(), "ws-1",
            { sink.conversations.add(it) }, { sink.messages.add(it) }
        )
        assertEquals(2, result.conversations)
        assertEquals(3, result.messages)
        assertEquals(2, sink.conversations.size)
        assertEquals(3, sink.messages.size)

        val conv = sink.conversations.first { it.id == "agora-c1" }
        assertEquals("拉面杯机制", conv.title)
        assertEquals("ws-1", conv.workspaceId)
        assertEquals(3, conv.messageCount)
        assertEquals(46L, conv.tokenTotal) // 12 + 34

        val user = sink.messages.first { it.content == "帮我分析" }
        assertEquals("user", user.role)
        assertEquals("COMPLETED", user.status)
        assertEquals(1L, user.sequence) // 按时间排序后第一条
        assertNull(user.modelUsed)

        val model = sink.messages.first { it.content == "分析如下" }
        assertEquals("assistant", model.role)
        assertEquals("gpt-x", model.modelUsed)
        assertEquals(2L, model.sequence)
        assertTrue(model.toolCallsJson!!.contains("\"agoraThoughts\":\"先想一下\""))

        val interrupted = sink.messages.first { it.content == "生成中断" }
        assertEquals("INTERRUPTED", interrupted.status)
        assertEquals(3L, interrupted.sequence)
    }

    @Test fun emptyConversationStillImportsWithZeroStats() = runBlocking {
        val sink = Sink()
        AgoraChatImporter.parse(agoraBackupJson(), "ws-1", { sink.conversations.add(it) }, { sink.messages.add(it) })
        val empty = sink.conversations.first { it.id == "agora-c2" }
        assertEquals(0, empty.messageCount)
        assertEquals(0L, empty.tokenTotal)
    }

    @Test fun malformedJsonFailsWithClearMessage() {
        val e = runCatching {
            runBlocking {
                AgoraChatImporter.parse("不是json", "ws", {}, {})
            }
        }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException || e is IllegalStateException)
        assertTrue(e!!.message!!.contains("解析失败"))
    }

    @Test fun zipLayoutMatchesImporterExpectations() {
        val zip = ZipInputStream(ByteArrayInputStream(agoraZip()))
        val names = mutableListOf<String>()
        var e = zip.nextEntry
        var conversationsJson: String? = null
        while (e != null) {
            if (e.name == "conversations.json") conversationsJson = zip.readBytes().toString(Charsets.UTF_8)
            names.add(e.name)
            e = zip.nextEntry
        }
        assertTrue(names.contains("manifest.json"))
        assertTrue(conversationsJson!!.contains("\"conversations\":["))
        assertTrue(conversationsJson.contains("\"participant\":\"MODEL\""))
    }
}
