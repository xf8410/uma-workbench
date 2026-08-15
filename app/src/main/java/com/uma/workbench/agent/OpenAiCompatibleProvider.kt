package com.uma.workbench.agent

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Real HTTPS streaming client for OpenAI-compatible chat-completions APIs. */
class OpenAiCompatibleProvider(private val settings: () -> AiProviderSettings) : AiStreamingProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(request: AiGenerationRequest): Flow<AiStreamEvent> = flow {
        val config = settings().also { it.validate() }
        val connection = (URL(config.endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 0
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            val payload = buildJsonObject {
                put("model", request.model ?: config.model)
                put("stream", true)
                put("stream_options", buildJsonObject { put("include_usage", true) })
                put("messages", buildJsonArray { request.messages.forEach { message -> add(buildJsonObject { put("role", message.role); put("content", message.completeContent) }) } })
            }.toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val completeError = connection.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                error("AI API HTTP $status\n$completeError")
            }
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trimStart()
                    if (data == "[DONE]") { emit(AiStreamEvent.Completed); break }
                    if (data.isEmpty()) continue
                    val root = json.parseToJsonElement(data).jsonObject
                    root["model"]?.jsonPrimitive?.contentOrNull?.let { emit(AiStreamEvent.Model(it)) }
                    root["choices"]?.jsonArray?.forEach { choice ->
                        choice.jsonObject["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.let { emit(AiStreamEvent.TextDelta(it)) }
                    }
                    root["usage"]?.jsonObject?.let { usage ->
                        val input = usage.long("prompt_tokens")
                        val output = usage.long("completion_tokens")
                        emit(AiStreamEvent.Usage(AiTokenUsage(input, output, usage.long("total_tokens").takeIf { it > 0 } ?: input + output)))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun JsonObject.long(name: String): Long = get(name)?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
}
