package com.uma.workbench.agent

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class AiModelDiscovery(private val json: Json = Json { ignoreUnknownKeys = true }) {
    suspend fun fetch(provider: AiProviderProfile): List<String> = withContext(Dispatchers.IO) {
        provider.validate()
        val credential = provider.activeCredential ?: error("${provider.name} 没有启用的 API 密钥")
        val connection = (URL(provider.modelsUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 30_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${credential.secret}")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("同步模型 HTTP $code\n${connection.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()}")
            extract(json.parseToJsonElement(connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }))
        } finally { connection.disconnect() }
    }

    /** Accepts {data:[{id}]}, {models:[...]}, and a top-level array without discarding entries. */
    fun extract(root: JsonElement): List<String> {
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: error("模型响应缺少 data 或 models 数组")
            else -> error("模型响应不是 JSON 对象或数组")
        }
        return entries.mapNotNull { element -> when (element) {
            is JsonObject -> element["id"]?.jsonPrimitive?.contentOrNull ?: element["name"]?.jsonPrimitive?.contentOrNull
            else -> runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
        } }.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
    }
}
