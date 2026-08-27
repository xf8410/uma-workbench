package com.uma.workbench.agent

import com.uma.workbench.diagnostics.AiHttpException
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

/**
 * Streaming provider for self-hosted LAN model servers (Ollama, LM Studio, text-generation-webui, etc.).
 *
 * Uses the same OpenAI-compatible streaming protocol as [CatalogAiStreamingProvider],
 * but allows plain HTTP for private network addresses (feature: 局域网自托管模型连接).
 *
 * The provider does NOT require a cloud API key. The endpoint URL and optional
 * auth token are the only configuration needed.
 */
class LanModelProvider(
    private val endpoint: () -> LanModelEndpoint
) : AiStreamingProvider {

    override fun stream(request: AiGenerationRequest): Flow<AiStreamEvent> = flow {
        val ep = endpoint().also { it.validate() }
        require(request.model == ep.model || request.model.isNullOrBlank()) {
            "请求的模型 ${request.model} 与局域网模型端点配置的 ${ep.model} 不匹配"
        }
        // Use OpenAI-compatible protocol by default; LAN servers typically support this
        val protocol = CustomAiApiProtocol()
        val adapter = CustomAiApiAdapter(protocol)
        val connection = (URL(ep.chatUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 0
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", if (protocol.streamFormat == AiApiStreamFormat.SSE) "text/event-stream" else "application/x-ndjson")
            if (ep.authToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${ep.authToken}")
            }
        }
        try {
            val body = adapter.requestBody(request, ep.model)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = connection.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                throw AiHttpException(status, errorBody, ep.label)
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
