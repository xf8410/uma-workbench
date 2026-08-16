package com.uma.workbench.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class SubAgentReportSummary(
    val taskId: String,
    val status: String,
    val answer: String?,
    val error: String?,
    val requestId: String?,
    val model: String?,
    val totalTokens: Long?,
    val evidenceCount: Int,
    val completeEvidenceCount: Int
)

object SubAgentReportPresentation {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(content: String): List<SubAgentReportSummary>? = runCatching {
        val root = json.parseToJsonElement(content) as? JsonObject ?: return null
        if (root.string("type") != "sub_agent_reports") return null
        val reports = root["reports"] as? JsonArray ?: return null
        reports.map { element ->
            val report = element as? JsonObject ?: error("report 必须是 object")
            val evidence = report["evidence"] as? JsonArray ?: JsonArray(emptyList())
            SubAgentReportSummary(
                taskId = report.string("taskId")?.takeIf { it.isNotBlank() } ?: error("缺少 taskId"),
                status = report.string("status") ?: "unknown",
                answer = report.string("answer"),
                error = report.string("error"),
                requestId = report.string("requestId"),
                model = report.string("model"),
                totalTokens = (report["usage"] as? JsonObject)?.primitive("totalTokens")?.longOrNull,
                evidenceCount = evidence.size,
                completeEvidenceCount = evidence.count { item ->
                    ((item as? JsonObject)?.primitive("complete")?.booleanOrNull == true)
                }
            )
        }
    }.getOrNull()

    private fun JsonObject.string(name: String) = primitive(name)?.contentOrNull
    private fun JsonObject.primitive(name: String) = get(name) as? JsonPrimitive
}
