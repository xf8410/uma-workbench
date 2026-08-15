package com.uma.workbench.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class AiTokenUsage(val inputTokens: Long = 0, val outputTokens: Long = 0, val totalTokens: Long = inputTokens + outputTokens, val estimated: Boolean = false)
enum class AiGenerationPhase { IDLE, GENERATING, COMPLETED, CANCELLED, FAILED }
data class AiGenerationState(
    val phase: AiGenerationPhase = AiGenerationPhase.IDLE,
    val requestId: String? = null,
    val completeText: String = "",
    val usage: AiTokenUsage? = null,
    val model: String? = null,
    val error: String? = null,
    val toolCalls: List<AiToolCall> = emptyList()
) {
    val canSend: Boolean get() = phase != AiGenerationPhase.GENERATING
    val canInterrupt: Boolean get() = phase == AiGenerationPhase.GENERATING
    val statusLabel: String get() = when (phase) {
        AiGenerationPhase.IDLE -> "就绪"; AiGenerationPhase.GENERATING -> "生成中"; AiGenerationPhase.COMPLETED -> "已完成"; AiGenerationPhase.CANCELLED -> "已停止"; AiGenerationPhase.FAILED -> "生成失败"
    }
    val usageLabel: String get() = usage?.let { "输入 ${it.inputTokens} · 输出 ${it.outputTokens} · 合计 ${it.totalTokens}${if (it.estimated) "（估算）" else ""}" } ?: "Token：等待统计"
}

data class AiGenerationRequest(val requestId: String, val messages: List<AiPromptMessage>, val model: String?)
data class AiPromptMessage(val role: String, val completeContent: String)
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
        val input = request.messages.sumOf { estimateText(it.completeContent) }; val output = estimateText(completeOutput)
        return AiTokenUsage(input, output, input + output, estimated = true)
    }
    private fun estimateText(text: String): Long = if (text.isEmpty()) 0 else (text.codePointCount(0, text.length) + 3L) / 4L
}

class AiGenerationController(private val scope: CoroutineScope, private val provider: AiStreamingProvider) {
    private val _state = MutableStateFlow(AiGenerationState()); val state: StateFlow<AiGenerationState> = _state.asStateFlow()
    private var activeJob: Job? = null

    fun send(request: AiGenerationRequest): Boolean {
        if (activeJob?.isActive == true) return false
        _state.value = AiGenerationState(AiGenerationPhase.GENERATING, request.requestId, model = request.model)
        activeJob = scope.launch {
            var terminal = AiGenerationPhase.COMPLETED; var terminalError: String? = null
            val toolAccumulator = AiToolCallAccumulator()
            try {
                provider.stream(request).collect { event -> when (event) {
                    is AiStreamEvent.TextDelta -> _state.value = _state.value.copy(completeText = _state.value.completeText + event.completeDelta)
                    is AiStreamEvent.Usage -> _state.value = _state.value.copy(usage = mergeUsage(_state.value.usage, event.usage))
                    is AiStreamEvent.Model -> _state.value = _state.value.copy(model = event.model)
                    is AiStreamEvent.ToolCallDelta -> { toolAccumulator.append(event.delta); _state.value = _state.value.copy(toolCalls = toolAccumulator.snapshot()) }
                    AiStreamEvent.Completed -> terminal = AiGenerationPhase.COMPLETED
                } }
                if (toolAccumulator.snapshot().isNotEmpty()) _state.value = _state.value.copy(toolCalls = toolAccumulator.validatedSnapshot())
            } catch (cancelled: CancellationException) {
                terminal = AiGenerationPhase.CANCELLED; throw cancelled
            } catch (error: Throwable) {
                terminal = AiGenerationPhase.FAILED; terminalError = error.stackTraceToString()
            } finally {
                val usage = _state.value.usage ?: AiTokenEstimator.estimate(request, _state.value.completeText)
                _state.value = _state.value.copy(phase = terminal, usage = usage, error = terminalError)
                activeJob = null
            }
        }
        return true
    }

    fun interrupt(): Boolean { val job = activeJob; if (job?.isActive != true) return false; job.cancel(CancellationException("用户停止生成")); return true }
    private fun mergeUsage(previous: AiTokenUsage?, current: AiTokenUsage): AiTokenUsage = if (previous == null) current else AiTokenUsage(maxOf(previous.inputTokens, current.inputTokens), maxOf(previous.outputTokens, current.outputTokens), maxOf(previous.totalTokens, current.totalTokens), previous.estimated || current.estimated)
}
