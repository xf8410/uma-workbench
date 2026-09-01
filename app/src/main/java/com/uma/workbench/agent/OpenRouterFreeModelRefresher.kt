package com.uma.workbench.agent

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 从 OpenRouter 公开 /models 端点拉取当日免费模型。
 *
 * 免费判定（满足其一）：
 * - pricing.prompt 与 pricing.completion 都是 "0"（OpenRouter 用字符串报价，单位美元/token）
 * - 模型 id 以 ":free" 结尾（OpenRouter 官方免费后缀）
 */
class OpenRouterFreeModelRefresher(private val json: Json = Json { ignoreUnknownKeys = true }) {

    data class RefreshResult(
        val freeModels: List<String>,
        val opened: List<String>,
        val closed: List<String>,
        val skipped: Boolean = false
    )

    suspend fun refresh(
        context: Context,
        store: OpenRouterFreeModelStore,
        force: Boolean = false
    ): RefreshResult = withContext(Dispatchers.IO) {
        val state = store.load()
        if (!state.autoManage && !force) return@withContext RefreshResult(emptyList(), emptyList(), emptyList(), skipped = true)
        val free = fetchFreeModels()
        val previous = state.freeModels
        val opened = free.filter { it !in previous }
        val closed = previous.filter { it !in free }
        store.save(state.copy(freeModels = free, lastSyncAt = System.currentTimeMillis()))
        RefreshResult(free, opened, closed)
    }

    fun fetchFreeModels(): List<String> {
        val connection = (URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("OpenRouter 模型列表 HTTP $code\n${connection.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()}")
            val body = connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            return extractFreeModels(json.parseToJsonElement(body))
        } finally {
            connection.disconnect()
        }
    }

    fun extractFreeModels(root: kotlinx.serialization.json.JsonElement): List<String> {
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray ?: error("OpenRouter 响应缺少 data 数组")
            else -> error("OpenRouter 响应不是 JSON 对象")
        }
        return entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (isFree(obj, id)) id.trim() else null
        }.filter(String::isNotEmpty).distinct().sorted()
    }

    private fun isFree(obj: JsonObject, id: String): Boolean {
        if (id.endsWith(":free")) return true
        val pricing = obj["pricing"] as? JsonObject ?: return false
        val prompt = (pricing["prompt"] as? JsonPrimitive)?.contentOrNull
        val completion = (pricing["completion"] as? JsonPrimitive)?.contentOrNull
        return prompt == "0" && completion == "0"
    }
}
