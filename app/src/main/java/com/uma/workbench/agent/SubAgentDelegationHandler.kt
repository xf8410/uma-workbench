package com.uma.workbench.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

fun interface AgentSpecialToolHandler {
    suspend fun execute(parentRequest: AiGenerationRequest, call: AiToolCall): String
}

/** Parses the root-only delegation tool and returns bounded, structured child reports to the parent. */
class SubAgentDelegationHandler(private val coordinator: SubAgentCoordinator) : AgentSpecialToolHandler {
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun execute(parentRequest: AiGenerationRequest, call: AiToolCall): String {
        require(call.name == "delegate_subagents") { "不支持特殊工具 ${call.name}" }
        val root = json.parseToJsonElement(call.completeArgumentsJson) as? JsonObject
            ?: error("delegate_subagents 参数必须是 JSON object")
        require(root.keys == setOf("tasks")) { "delegate_subagents 只允许 tasks 参数" }
        val rawTasks = root["tasks"] as? JsonArray ?: error("tasks 必须是数组")
        val tasks = rawTasks.mapIndexed { index, element -> parseTask(index, element) }
        return encode(coordinator.dispatch(parentRequest, tasks))
    }

    private fun parseTask(index: Int, element: kotlinx.serialization.json.JsonElement): SubAgentTask {
        val value = element as? JsonObject ?: error("tasks[$index] 必须是 object")
        require(value.keys.all { it in setOf("id", "instruction", "evidenceRequirements") }) {
            "tasks[$index] 包含未知参数"
        }
        val id = value.requiredString("id", index)
        val instruction = value.requiredString("instruction", index)
        val evidence = value.optionalString("evidenceRequirements", index)
        return if (evidence == null) SubAgentTask(id, instruction) else SubAgentTask(id, instruction, evidence)
    }

    private fun JsonObject.requiredString(name: String, index: Int): String =
        optionalString(name, index)?.takeIf { it.isNotBlank() }
            ?: error("tasks[$index].$name 必须是非空字符串")

    private fun JsonObject.optionalString(name: String, index: Int): String? {
        val value = get(name) ?: return null
        return (value as? JsonPrimitive)?.contentOrNull
            ?: error("tasks[$index].$name 必须是字符串")
    }

    private fun encode(outcomes: List<SubAgentOutcome>): String = buildJsonObject {
        put("type", "sub_agent_reports")
        put("reports", buildJsonArray {
            outcomes.forEach { outcome ->
                add(when (outcome) {
                    is SubAgentOutcome.Success -> buildJsonObject {
                        put("taskId", outcome.result.taskId)
                        put("status", "success")
                        put("requestId", outcome.result.requestId)
                        put("answer", outcome.result.answer)
                        outcome.result.model?.let { put("model", it) }
                        put("usage", buildJsonObject {
                            put("inputTokens", outcome.result.usage.inputTokens)
                            put("outputTokens", outcome.result.usage.outputTokens)
                            put("totalTokens", outcome.result.usage.totalTokens)
                        })
                        put("evidence", buildJsonArray {
                            outcome.result.rounds.flatMap { it.toolOutcomes }.forEach { toolOutcome ->
                                when (toolOutcome) {
                                    is AgentToolOutcome.Success -> add(buildJsonObject {
                                        put("tool", toolOutcome.result.toolName)
                                        put("resultId", toolOutcome.result.resultId)
                                        put("range", "${toolOutcome.result.startOffset}-${toolOutcome.result.endOffsetExclusive}")
                                        put("sha256", toolOutcome.result.sha256)
                                        put("complete", toolOutcome.result.complete)
                                    })
                                    is AgentToolOutcome.Failure -> add(buildJsonObject {
                                        put("tool", toolOutcome.failure.toolName)
                                        put("error", toolOutcome.failure.completeError.take(4_096))
                                    })
                                }
                            }
                        })
                    }
                    is SubAgentOutcome.Failure -> buildJsonObject {
                        put("taskId", outcome.failure.taskId)
                        put("status", "failure")
                        put("error", outcome.failure.completeError.take(8_192))
                    }
                })
            }
        })
    }.toString()
}
