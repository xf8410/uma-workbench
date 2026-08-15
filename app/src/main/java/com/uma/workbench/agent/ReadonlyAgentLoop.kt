package com.uma.workbench.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

/** One observable model/tool round. No hidden reasoning is recorded. */
data class ReadonlyAgentRound(
    val index: Int,
    val assistantText: String,
    val toolCalls: List<AiToolCall>,
    val toolOutcomes: List<AgentToolOutcome>,
    val model: String?,
    val usage: AiTokenUsage?
)

data class ReadonlyAgentRunResult(
    val requestId: String,
    val completeAnswer: String,
    val rounds: List<ReadonlyAgentRound>,
    val usage: AiTokenUsage,
    val model: String?,
    val messages: List<AiPromptMessage>
)

data class ReadonlyAgentLoopLimits(val maxModelRounds: Int = 6) {
    init { require(maxModelRounds in 1..20) }
}

/**
 * Bounded read-only Agent loop:
 * model -> validated tool calls -> tool execution -> tool results -> model.
 * Cancellation propagates through provider collection and tool execution. A run cannot finish as
 * success with an empty answer, and a final round containing more tool calls is rejected.
 */
class ReadonlyAgentLoop(
    private val provider: AiStreamingProvider,
    private val executor: ReadonlyAgentToolExecutor,
    private val limits: ReadonlyAgentLoopLimits = ReadonlyAgentLoopLimits()
) {
    suspend fun run(initial: AiGenerationRequest): ReadonlyAgentRunResult {
        val messages = initial.messages.toMutableList()
        val rounds = mutableListOf<ReadonlyAgentRound>()
        var aggregateUsage = AiTokenUsage()
        var resolvedModel = initial.model

        repeat(limits.maxModelRounds) { roundIndex ->
            currentCoroutineContext().ensureActive()
            val accumulator = AiToolCallAccumulator()
            val text = StringBuilder()
            var roundUsage: AiTokenUsage? = null
            provider.stream(initial.copy(messages = messages.toList())).collect { event ->
                when (event) {
                    is AiStreamEvent.TextDelta -> text.append(event.completeDelta)
                    is AiStreamEvent.ToolCallDelta -> accumulator.append(event.delta)
                    is AiStreamEvent.Usage -> roundUsage = mergeUsage(roundUsage, event.usage)
                    is AiStreamEvent.Model -> resolvedModel = event.model
                    AiStreamEvent.Completed -> Unit
                }
            }
            val calls = accumulator.validatedSnapshot()
            val outcomes = if (calls.isEmpty()) emptyList() else executor.executeTurn(calls)
            rounds += ReadonlyAgentRound(roundIndex + 1, text.toString(), calls, outcomes, resolvedModel, roundUsage)
            roundUsage?.let { aggregateUsage = addUsage(aggregateUsage, it) }

            if (calls.isEmpty()) {
                require(text.isNotBlank()) { "模型未返回工具调用，也未返回最终答案" }
                return ReadonlyAgentRunResult(initial.requestId, text.toString(), rounds, aggregateUsage, resolvedModel, messages)
            }
            require(roundIndex + 1 < limits.maxModelRounds) { "Agent 达到最大模型轮次 ${limits.maxModelRounds}，但模型仍请求工具" }

            messages += AiPromptMessage(
                role = "assistant",
                completeContent = text.toString(),
                toolCalls = calls
            )
            calls.zip(outcomes).forEach { (call, outcome) ->
                messages += AiPromptMessage(
                    role = "tool",
                    completeContent = outcome.toModelContent(),
                    toolCallId = call.id,
                    toolName = call.name
                )
            }
        }
        error("Agent 循环异常结束")
    }

    private fun AgentToolOutcome.toModelContent(): String = when (this) {
        is AgentToolOutcome.Success -> buildString {
            append(result.completeContent)
            append("\n\n[tool_result_metadata]")
            append("\ncharacterCount=${result.characterCount}")
            append("\ntruncated=${result.truncated}")
            result.continuation?.let { append("\ncontinuation=$it") }
            append("\nelapsedMillis=${result.elapsedMillis}")
        }
        is AgentToolOutcome.Failure -> "[tool_error]\n${failure.completeError}\nelapsedMillis=${failure.elapsedMillis}"
    }

    private fun mergeUsage(previous: AiTokenUsage?, current: AiTokenUsage): AiTokenUsage = if (previous == null) current else AiTokenUsage(
        maxOf(previous.inputTokens, current.inputTokens),
        maxOf(previous.outputTokens, current.outputTokens),
        maxOf(previous.totalTokens, current.totalTokens),
        previous.estimated || current.estimated
    )

    private fun addUsage(total: AiTokenUsage, round: AiTokenUsage): AiTokenUsage = AiTokenUsage(
        total.inputTokens + round.inputTokens,
        total.outputTokens + round.outputTokens,
        total.totalTokens + round.totalTokens,
        total.estimated || round.estimated
    )
}
