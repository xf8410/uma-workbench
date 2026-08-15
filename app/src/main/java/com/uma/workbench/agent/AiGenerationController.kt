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

/** Token usage reported by a provider. Values remain visible for completed, cancelled and failed runs. */
data class AiTokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = inputTokens + outputTokens,
    val estimated: Boolean = false
)

enum class AiGenerationPhase { IDLE, GENERATING, COMPLETED, CANCELLED, FAILED }

data class AiGenerationState(
    val phase: AiGenerationPhase = AiGenerationPhase.IDLE,
    val requestId: String? = null,
    val completeText: String = "",
    val usage: AiTokenUsage? = null,
    val model: String? = null,
    val error: String? = null
) {
    /** The send/stop icon is derived from actual active work, not stale status text. */
    val canSend: Boolean get() = phase != AiGenerationPhase.GENERATING
    val canInterrupt: Boolean get() = phase == AiGenerationPhase.GENERATING
    val statusLabel: String get() = when (phase) {
        AiGenerationPhase.IDLE -> "就绪"
        AiGenerationPhase.GENERATING -> "生成中"
        AiGenerationPhase.COMPLETED -> "已完成"
        AiGenerationPhase.CANCELLED -> "已停止"
        AiGenerationPhase.FAILED -> "生成失败"
    }
}

data class AiGenerationRequest(val requestId: String, val messages: List<AiPromptMessage>, val model: String?)
data class AiPromptMessage(val role: String, val completeContent: String)

sealed interface AiStreamEvent {
    data class TextDelta(val completeDelta: String) : AiStreamEvent
    data class Usage(val usage: AiTokenUsage) : AiStreamEvent
    data class Model(val model: String) : AiStreamEvent
    data object Completed : AiStreamEvent
}

fun interface AiStreamingProvider {
    fun stream(request: AiGenerationRequest): Flow<AiStreamEvent>
}

/**
 * Owns exactly one streaming run. Natural EOF is completion even when a provider omits an explicit
 * Completed event. Cancellation and failure always leave GENERATING in finally. Usage already
 * reported by the provider is never cleared when the run is interrupted.
 */
class AiGenerationController(
    private val scope: CoroutineScope,
    private val provider: AiStreamingProvider
) {
    private val _state = MutableStateFlow(AiGenerationState())
    val state: StateFlow<AiGenerationState> = _state.asStateFlow()
    private var activeJob: Job? = null

    fun send(request: AiGenerationRequest): Boolean {
        if (activeJob?.isActive == true) return false
        _state.value = AiGenerationState(AiGenerationPhase.GENERATING, request.requestId, model = request.model)
        activeJob = scope.launch {
            var terminal = AiGenerationPhase.COMPLETED
            var terminalError: String? = null
            try {
                provider.stream(request).collect { event ->
                    when (event) {
                        is AiStreamEvent.TextDelta -> _state.value = _state.value.copy(completeText = _state.value.completeText + event.completeDelta)
                        is AiStreamEvent.Usage -> _state.value = _state.value.copy(usage = mergeUsage(_state.value.usage, event.usage))
                        is AiStreamEvent.Model -> _state.value = _state.value.copy(model = event.model)
                        AiStreamEvent.Completed -> terminal = AiGenerationPhase.COMPLETED
                    }
                }
            } catch (cancelled: CancellationException) {
                terminal = AiGenerationPhase.CANCELLED
                throw cancelled
            } catch (error: Throwable) {
                terminal = AiGenerationPhase.FAILED
                terminalError = error.stackTraceToString()
            } finally {
                _state.value = _state.value.copy(phase = terminal, error = terminalError)
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

    private fun mergeUsage(previous: AiTokenUsage?, current: AiTokenUsage): AiTokenUsage {
        if (previous == null) return current
        // Providers may send cumulative usage repeatedly. Keeping per-field maxima prevents double count.
        return AiTokenUsage(
            inputTokens = maxOf(previous.inputTokens, current.inputTokens),
            outputTokens = maxOf(previous.outputTokens, current.outputTokens),
            totalTokens = maxOf(previous.totalTokens, current.totalTokens),
            estimated = previous.estimated || current.estimated
        )
    }
}
