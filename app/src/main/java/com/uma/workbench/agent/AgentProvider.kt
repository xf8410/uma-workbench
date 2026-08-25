package com.uma.workbench.agent

import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonArray

/**
 * Vendor-agnostic model provider interface.
 * Implementations may wrap cloud APIs, local on-device models, or no model at all.
 * This abstraction lets the agent loop run with or without a configured LLM.
 */
interface ModelProvider {
    val id: String
    val label: String
    val isAvailable: Boolean

    suspend fun generate(request: ModelGenerationRequest): ModelGenerationResult
}

data class ModelGenerationRequest(
    val messages: List<AiPromptMessage>,
    val model: String? = null,
    val tools: JsonArray? = null,
    val maxTokens: Int? = null
)

data class ModelGenerationResult(
    val text: String,
    val toolCalls: List<AiToolCall> = emptyList(),
    val usage: AiTokenUsage? = null,
    val model: String? = null,
    val finishReason: String? = null
) {
    companion object {
        val EMPTY = ModelGenerationResult(text = "")
    }
}

/**
 * Bridges the existing AiStreamingProvider into the ModelProvider interface.
 * Collects streaming events into a single non-streaming result.
 */
class StreamingModelProvider(
    private val streaming: AiStreamingProvider,
    override val id: String = "streaming",
    override val label: String = "流式模型"
) : ModelProvider {

    override val isAvailable: Boolean = true

    override suspend fun generate(request: ModelGenerationRequest): ModelGenerationResult {
        val aiRequest = AiGenerationRequest(
            requestId = "mp-${System.currentTimeMillis()}",
            messages = request.messages,
            model = request.model,
            tools = request.tools
        )
        val accumulator = AiToolCallAccumulator()
        val text = StringBuilder()
        var usage: AiTokenUsage? = null
        var model: String? = request.model

        streaming.stream(aiRequest).collect { event ->
            when (event) {
                is AiStreamEvent.TextDelta -> text.append(event.completeDelta)
                is AiStreamEvent.ToolCallDelta -> accumulator.append(event.delta)
                is AiStreamEvent.Usage -> usage = event.usage
                is AiStreamEvent.Model -> model = event.model
                AiStreamEvent.Completed -> Unit
            }
        }
        return ModelGenerationResult(
            text = text.toString(),
            toolCalls = accumulator.validatedSnapshot(),
            usage = usage ?: AiTokenEstimator.estimate(aiRequest, text.toString()),
            model = model
        )
    }
}

/**
 * Deterministic provider that does not call any LLM.
 * Used when no cloud or local model is configured.
 * Returns a structured message indicating deterministic mode is active.
 * The agent loop can still execute tools and produce evidence-based results.
 */
class NoModelProvider(
    override val id: String = "no-model",
    override val label: String = "无模型（确定性工作流）"
) : ModelProvider {

    override val isAvailable: Boolean = true

    override suspend fun generate(request: ModelGenerationRequest): ModelGenerationResult {
        val systemPrompt = request.messages.firstOrNull { it.role == "system" }?.completeContent
        val userPrompt = request.messages.lastOrNull { it.role == "user" }?.completeContent
        val response = buildString {
            appendLine("[确定性模式] 当前未配置 AI 模型，已切换至确定性工作流。")
            if (systemPrompt != null) {
                appendLine()
                appendLine("系统指令摘要：")
                appendLine(systemPrompt.take(200))
            }
            if (userPrompt != null) {
                appendLine()
                appendLine("用户请求摘要：")
                appendLine(userPrompt.take(200))
            }
            appendLine()
            appendLine("可用工具已就绪，可通过工具调用完成确定性审计任务。")
        }
        return ModelGenerationResult(
            text = response,
            usage = AiTokenUsage(0, 0, 0, estimated = false),
            model = "no-model"
        )
    }
}
