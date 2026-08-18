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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Streaming HTTPS client whose headers, body template and response paths are user-defined. */
class CustomAiApiProvider(private val settings: () -> AiProviderSettings) : AiStreamingProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(request: AiGenerationRequest): Flow<AiStreamEvent> = flow {
        val config = settings().also { it.validate() }
        val adapter = CustomAiApiAdapter(config.protocol)
        val connection = (URL(config.endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 0
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", if (config.protocol.streamFormat == AiApiStreamFormat.SSE) "text/event-stream" else "application/x-ndjson")
            parseHeaders(config.headersJson).forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val completeBody = adapter.requestBody(request, config.model)
            connection.outputStream.use { it.write(completeBody.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val completeError = connection.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                throw AiHttpException(status, completeError)
            }
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    val payload = adapter.payload(line) ?: continue
                    val events = adapter.events(payload)
                    for (event in events) emit(event)
                    if (events.any { it == AiStreamEvent.Completed }) break
                }
            }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseHeaders(headersJson: String): Map<String, String> {
        val root = json.parseToJsonElement(headersJson).jsonObject
        return root.mapValues { (name, value) ->
            require(!name.equals("Host", true) && !name.equals("Content-Length", true)) { "不允许覆盖请求头 $name" }
            value.jsonPrimitive.contentOrNull ?: error("请求头 $name 必须是字符串")
        }
    }
}

@Deprecated("Use CustomAiApiProvider", ReplaceWith("CustomAiApiProvider(settings)"))
typealias OpenAiCompatibleProvider = CustomAiApiProvider
