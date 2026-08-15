package com.uma.workbench.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class AiApiStreamFormat { SSE, NDJSON }

data class CustomAiApiProtocol(
    val requestTemplate: String = OPENAI_REQUEST_TEMPLATE,
    val streamFormat: AiApiStreamFormat = AiApiStreamFormat.SSE,
    val textPath: String = "choices.0.delta.content",
    val modelPath: String = "model",
    val inputTokensPath: String = "usage.prompt_tokens",
    val outputTokensPath: String = "usage.completion_tokens",
    val totalTokensPath: String = "usage.total_tokens",
    val doneValue: String = "[DONE]"
) {
    fun validate() {
        require(requestTemplate.isNotBlank()) { "请求模板不能为空" }
        require(textPath.isNotBlank()) { "回复文本字段路径不能为空" }
    }

    companion object {
        const val OPENAI_REQUEST_TEMPLATE = """{"model":{{modelJson}},"stream":true,"stream_options":{"include_usage":true},"messages":{{messagesJson}}}"""
    }
}

/** Pure request/response adapter used by arbitrary user-configured streaming endpoints. */
class CustomAiApiAdapter(
    private val protocol: CustomAiApiProtocol,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun requestBody(request: AiGenerationRequest, configuredModel: String): String {
        protocol.validate()
        val messages = buildJsonArray {
            request.messages.forEach { message -> add(buildJsonObject {
                put("role", message.role)
                put("content", message.completeContent)
            }) }
        }
        val modelJson = JsonPrimitive(request.model ?: configuredModel).toString()
        return protocol.requestTemplate
            .replace("{{modelJson}}", modelJson)
            .replace("{{messagesJson}}", messages.toString())
            .replace("{{requestIdJson}}", JsonPrimitive(request.requestId).toString())
    }

    fun payload(line: String): String? = when (protocol.streamFormat) {
        AiApiStreamFormat.SSE -> line.takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trimStart()
        AiApiStreamFormat.NDJSON -> line.trim().takeIf { it.isNotEmpty() }
    }

    fun events(payload: String): List<AiStreamEvent> {
        if (payload == protocol.doneValue) return listOf(AiStreamEvent.Completed)
        val root = json.parseToJsonElement(payload)
        val events = mutableListOf<AiStreamEvent>()
        value(root, protocol.modelPath)?.primitiveText()?.let { events += AiStreamEvent.Model(it) }
        value(root, protocol.textPath)?.primitiveText()?.let { if (it.isNotEmpty()) events += AiStreamEvent.TextDelta(it) }
        val input = value(root, protocol.inputTokensPath)?.primitiveLong()
        val output = value(root, protocol.outputTokensPath)?.primitiveLong()
        val total = value(root, protocol.totalTokensPath)?.primitiveLong()
        if (input != null || output != null || total != null) {
            val safeInput = input ?: 0
            val safeOutput = output ?: 0
            events += AiStreamEvent.Usage(AiTokenUsage(safeInput, safeOutput, total ?: safeInput + safeOutput))
        }
        return events
    }

    private fun value(root: JsonElement, path: String): JsonElement? {
        if (path.isBlank()) return null
        var current: JsonElement = root
        for (segment in path.split('.')) {
            current = when (current) {
                is JsonObject -> current[segment] ?: return null
                is JsonArray -> current.getOrNull(segment.toIntOrNull() ?: return null) ?: return null
                else -> return null
            }
        }
        return current.takeUnless { it is JsonNull }
    }

    private fun JsonElement.primitiveText(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.primitiveLong(): Long? = primitiveText()?.toLongOrNull()
}
