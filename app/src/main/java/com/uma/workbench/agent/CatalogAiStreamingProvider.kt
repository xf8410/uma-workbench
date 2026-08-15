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

/** Resolves the currently selected catalog profile for every new request. */
class CatalogAiStreamingProvider(
    private val profile: () -> AiProviderProfile?
) : AiStreamingProvider {
    override fun stream(request: AiGenerationRequest): Flow<AiStreamEvent> = flow {
        val provider = profile() ?: error("请先在 AI 配置中选择默认模型")
        provider.validate()
        val credential = provider.activeCredential ?: error("${provider.name} 没有启用的 API 密钥")
        require(request.model in provider.models) { "模型 ${request.model} 不属于提供商 ${provider.name}" }
        val adapter = CustomAiApiAdapter(provider.protocol)
        val connection = (URL(provider.chatUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 0
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${credential.secret}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", if (provider.protocol.streamFormat == AiApiStreamFormat.SSE) "text/event-stream" else "application/x-ndjson")
        }
        try {
            connection.outputStream.use { output ->
                output.write(adapter.requestBody(request, request.model.orEmpty()).toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                error("${provider.name} HTTP $status\n$body")
            }
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    val payload = adapter.payload(line) ?: continue
                    val events = adapter.events(payload)
                    events.forEach { emit(it) }
                    if (events.any { it == AiStreamEvent.Completed }) break
                }
            }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
