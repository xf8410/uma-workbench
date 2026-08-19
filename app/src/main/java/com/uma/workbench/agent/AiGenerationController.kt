package com.uma.workbench.agent

import com.uma.workbench.diagnostics.WorkbenchErrorMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray

data class AiTokenUsage(val inputTokens: Long = 0, val outputTokens: Long = 0, val totalTokens: Long = inputTokens + outputTokens, val estimated: Boolean = false)
enum class AiGenerationPhase { IDLE, GENERATING, WAITING_FOR_NETWORK, RESUMING, COMPLETED, CANCELLED, FAILED }
data class AiGenerationState(
    val phase: AiGenerationPhase = AiGenerationPhase.IDLE,
    val requestId: String? = null,
    val completeText: String = "",
    val usage: AiTokenUsage? = null,
    val model: String? = null,
    /** User-safe Chinese text only; raw exception details belong in diagnostics. */
    val error: String? = null,
    val toolCalls: List<AiToolCall> = emptyList(),
    val partialCharacterCount: Int = 0,
    val lastEventId: String? = null
) {
    val canSend: Boolean get() = phase != AiGenerationPhase.GENERATING && phase != AiGenerationPhase.WAITING_FOR_NETWORK && phase != AiGenerationPhase.RESUMING
    val canInterrupt: Boolean get() = phase == AiGenerationPhase.GENERATING || phase == AiGenerationPhase.WAITING_FOR_NETWORK || phase == AiGenerationPhase.RESUMING
    val statusLabel: String get() = when (phase) {
        AiGenerationPhase.IDLE -> "就绪"
        AiGenerationPhase.GENERATING -> "生成中"
        AiGenerationPhase.WAITING_FOR_NETWORK -> "等待网络恢复"
        AiGenerationPhase.RESUMING -> "正在恢复"
        AiGenerationPhase.COMPLETED -> "已完成"
        AiGenerationPhase.CANCELLED -> "已停止"
        AiGenerationPhase.FAILED -> "生成失败"
    }
    val usageLabel: String get() = usage?.let { "输入 ${it.inputTokens} · 输出 ${it.outputTokens} · 合计 ${it.totalTokens}${if (it.estimated) "（估算）" else ""}" } ?: "Token：等待统计"
}

data class AiGenerationRequest(val requestId: String, val messages: List<AiPromptMessage>, val model: String?, val tools: JsonArray? = null)
data class AiPromptMessage(
    val role: String,
    val completeContent: String,
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null
) {
    init {
        require(role.isNotBlank())
        if (role == "tool") require(!toolCallId.isNullOrBlank()) { "tool 消息必须包含 toolCallId" }
        if (toolCalls.isNotEmpty()) require(role == "assistant") { "只有 assistant 消息可以包含 toolCalls" }
    }
}
sealed interface AiStreamEvent {
    data class TextDelta(val completeDelta: String) : AiStreamEvent
    data class Usage(val usage: AiTokenUsage) : AiStreamEvent
    data class Model(val model: String) : AiStreamEvent
    data class ToolCallDelta(val delta: AiToolCallDelta) : AiStreamEvent
    data object Completed : AiStreamEvent
}
fun interface AiStreamingProvider { fun stream(request: AiGenerationRequest): Flow<AiStreamEvent> }

object AiTokenEstimator {
    fun estimate(request: AiGenerationRequest, completeOutput: String): AiTokenUsage {
        val input = request.messages.sumOf { estimateText(it.completeContent) }
        val output = estimateText(completeOutput)
        return AiTokenUsage(input, output, input + output, estimated = true)
    }
    private fun estimateText(text: String): Long = if (text.isEmpty()) 0 else (text.codePointCount(0, text.length) + 3L) / 4L
}

class AiGenerationController(private val scope: CoroutineScope, private val provider: AiStreamingProvider) {
    private val _state = MutableStateFlow(AiGenerationState())
    val state: StateFlow<AiGenerationState> = _state.asStateFlow()
    private var activeJob: Job? = null

    fun send(request: AiGenerationRequest): Boolean {
        if (activeJob?.isActive == true) return false
        _state.value = AiGenerationState(AiGenerationPhase.GENERATING, request.requestId, model = request.model)
        activeJob = scope.launch {
            var terminal = AiGenerationPhase.COMPLETED
            var terminalError: String? = null
            val toolAccumulator = AiToolCallAccumulator()
            val textBuilder = StringBuilder()
            var lastFlush = 0L
            val flushIntervalMs = 80L
            try {
                provider.stream(request).collect { event ->
                    when (event) {
                        is AiStreamEvent.TextDelta -> {
                            textBuilder.append(event.completeDelta)
                            val now = System.currentTimeMillis()
                            if (now - lastFlush >= flushIntervalMs || event.completeDelta.isEmpty()) {
                                _state.value = _state.value.copy(completeText = textBuilder.toString(), partialCharacterCount = textBuilder.length)
                                lastFlush = now
                            }
                        }
                        is AiStreamEvent.Usage -> _state.value = _state.value.copy(usage = mergeUsage(_state.value.usage, event.usage))
                        is AiStreamEvent.Model -> _state.value = _state.value.copy(model = event.model)
                        is AiStreamEvent.ToolCallDelta -> {
                            toolAccumulator.append(event.delta)
                            _state.value = _state.value.copy(toolCalls = toolAccumulator.snapshot())
                        }
                        AiStreamEvent.Completed -> terminal = AiGenerationPhase.COMPLETED
                    }
                }
                _state.value = _state.value.copy(completeText = textBuilder.toString(), partialCharacterCount = textBuilder.length)
                if (toolAccumulator.snapshot().isNotEmpty()) _state.value = _state.value.copy(toolCalls = toolAccumulator.validatedSnapshot())
            } catch (cancelled: CancellationException) {
                terminal = AiGenerationPhase.CANCELLED
                _state.value = _state.value.copy(completeText = textBuilder.toString(), partialCharacterCount = textBuilder.length)
                throw cancelled
            } catch (error: Throwable) {
                terminal = AiGenerationPhase.FAILED
                terminalError = WorkbenchErrorMapper.map(error, textBuilder.length).userFacing.displayText
                _state.value = _state.value.copy(completeText = textBuilder.toString(), partialCharacterCount = textBuilder.length)
            } finally {
                val usage = _state.value.usage ?: AiTokenEstimator.estimate(request, _state.value.completeText)
                _state.value = _state.value.copy(phase = terminal, usage = usage, error = terminalError)
                activeJob = null
            }
        }
        return true
    }

    fun interrupt(): Boolean {
        val job = activeJob
        if (job?.isActive != true) return false
        job.cancel(CancellationException("用户停止生成"))
        return true
    }

    private fun mergeUsage(previous: AiTokenUsage?, current: AiTokenUsage): AiTokenUsage = if (previous == null) current else AiTokenUsage(
        maxOf(previous.inputTokens, current.inputTokens),
        maxOf(previous.outputTokens, current.outputTokens),
        maxOf(previous.totalTokens, current.totalTokens),
        previous.estimated || current.estimated
    )
}
