package com.uma.workbench.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class AgentSpecialToolPayload(
    /** Complete lossless document written to AgentToolResultStore. */
    val persistedContent: String,
    /** Small index returned to the parent model; never contains complete child answers or stack traces. */
    val modelContent: String
)

fun interface AgentSpecialToolHandler {
    suspend fun execute(parentRequest: AiGenerationRequest, call: AiToolCall): AgentSpecialToolPayload
}

/** Parses root-only delegation and separates durable reports from the bounded parent context. */
class SubAgentDelegationHandler(private val coordinator: SubAgentCoordinator) : AgentSpecialToolHandler {
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun execute(parentRequest: AiGenerationRequest, call: AiToolCall): AgentSpecialToolPayload {
        require(call.name == "delegate_subagents") { "不支持特殊工具 ${call.name}" }
        val root = json.parseToJsonElement(call.completeArgumentsJson) as? JsonObject
            ?: error("delegate_subagents 参数必须是 JSON object")
        require(root.keys == setOf("tasks")) { "delegate_subagents 只允许 tasks 参数" }
        val rawTasks = root["tasks"] as? JsonArray ?: error("tasks 必须是数组")
        val outcomes = coordinator.dispatch(parentRequest, rawTasks.mapIndexed(::parseTask))
        return AgentSpecialToolPayload(
            persistedContent = encodeComplete(outcomes),
            modelContent = encodeManifest(outcomes)
        )
    }

    private fun parseTask(index: Int, element: kotlinx.serialization.json.JsonElement): SubAgentTask {
        val value = element as? JsonObject ?: error("tasks[$index] 必须是 object")
        require(value.keys.all { it in setOf("id", "instruction", "evidenceRequirements") }) { "tasks[$index] 包含未知参数" }
        val id = value.requiredString("id", index)
        val instruction = value.requiredString("instruction", index)
        val evidence = value.optionalString("evidenceRequirements", index)
        return if (evidence == null) SubAgentTask(id, instruction) else SubAgentTask(id, instruction, evidence)
    }

    private fun JsonObject.requiredString(name: String, index: Int) = optionalString(name, index)?.takeIf { it.isNotBlank() }
        ?: error("tasks[$index].$name 必须是非空字符串")
    private fun JsonObject.optionalString(name: String, index: Int): String? {
        val value = get(name) ?: return null
        return (value as? JsonPrimitive)?.contentOrNull ?: error("tasks[$index].$name 必须是字符串")
    }

    private fun encodeManifest(outcomes: List<SubAgentOutcome>) = buildJsonObject {
        put("type", "sub_agent_report_manifest")
        put("reportCount", outcomes.size)
        put("reports", buildJsonArray {
            outcomes.forEach { outcome -> add(buildJsonObject {
                put("taskId", outcome.taskId)
                when (outcome) {
                    is SubAgentOutcome.Success -> {
                        put("status", "success")
                        put("requestId", outcome.result.requestId)
                        outcome.result.model?.let { put("model", it) }
                        put("totalTokens", outcome.result.usage.totalTokens)
                        val evidence = outcome.result.rounds.flatMap { it.toolOutcomes }
                        put("evidenceCount", evidence.size)
                        put("completeEvidenceCount", evidence.count { it is AgentToolOutcome.Success && it.result.complete })
                        // A bounded preview is navigation help, not the complete report.
                        put("preview", outcome.result.answer.replace(Regex("\\s+"), " ").take(240))
                    }
                    is SubAgentOutcome.Failure -> {
                        put("status", "failure")
                        put("preview", outcome.failure.completeError.lineSequence().firstOrNull().orEmpty().take(240))
                    }
                }
            }) }
        })
        put("instruction", "完整子报告已落盘。仅当确需细节时使用本工具结果附带的 resultId 和 read_tool_result 定向续读。")
    }.toString()

    private fun encodeComplete(outcomes: List<SubAgentOutcome>) = buildJsonObject {
        put("type", "sub_agent_reports")
        put("reports", buildJsonArray {
            outcomes.forEach { outcome -> add(when (outcome) {
                is SubAgentOutcome.Success -> buildJsonObject {
                    put("taskId", outcome.result.taskId); put("status", "success"); put("requestId", outcome.result.requestId)
                    put("answer", outcome.result.answer); outcome.result.model?.let { put("model", it) }
                    put("usage", buildJsonObject { put("inputTokens", outcome.result.usage.inputTokens); put("outputTokens", outcome.result.usage.outputTokens); put("totalTokens", outcome.result.usage.totalTokens) })
                    put("evidence", buildJsonArray { outcome.result.rounds.flatMap { it.toolOutcomes }.forEach { tool -> add(when (tool) {
                        is AgentToolOutcome.Success -> buildJsonObject { put("tool", tool.result.toolName); put("resultId", tool.result.resultId); put("range", "${tool.result.startOffset}-${tool.result.endOffsetExclusive}"); put("sha256", tool.result.sha256); put("complete", tool.result.complete) }
                        is AgentToolOutcome.Failure -> buildJsonObject { put("tool", tool.failure.toolName); put("error", tool.failure.completeError) }
                    }) } })
                }
                is SubAgentOutcome.Failure -> buildJsonObject { put("taskId", outcome.failure.taskId); put("status", "failure"); put("error", outcome.failure.completeError) }
            }) }
        })
    }.toString()
}
