package com.uma.workbench.ui

import com.uma.workbench.agent.AgentToolOutcome
import com.uma.workbench.agent.ReadonlyAgentRound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class PersistedSubAgentReportRef(
    val resultId: String,
    val totalCharacterCount: Int,
    val sha256: String
)

/** Message metadata stores references only; complete reports remain in the durable result store. */
object AgentRunPresentation {
    private val json = Json { ignoreUnknownKeys = true }

    fun toJson(rounds: List<ReadonlyAgentRound>): String = buildJsonArray {
        rounds.forEach { round -> add(buildJsonObject {
            put("round", round.index)
            put("calls", buildJsonArray {
                round.toolCalls.zip(round.toolOutcomes).forEach { (call, outcome) -> add(buildJsonObject {
                    put("id", call.id)
                    put("name", call.name)
                    when (outcome) {
                        is AgentToolOutcome.Success -> {
                            put("status", "success")
                            put("resultId", outcome.result.resultId)
                            put("totalCharacterCount", outcome.result.totalCharacterCount)
                            put("sha256", outcome.result.sha256)
                        }
                        is AgentToolOutcome.Failure -> put("status", "failure")
                    }
                }) }
            })
        })
    }.toString()

    fun subAgentReportRefs(metadata: String?): List<PersistedSubAgentReportRef> {
        if (metadata.isNullOrBlank()) return emptyList()
        return runCatching {
            val rounds = json.parseToJsonElement(metadata) as? JsonArray ?: return emptyList()
            rounds.flatMap { roundElement ->
                val round = roundElement as? JsonObject ?: return@flatMap emptyList()
                val calls = round["calls"] as? JsonArray ?: return@flatMap emptyList()
                calls.mapNotNull { callElement ->
                    val call = callElement as? JsonObject ?: return@mapNotNull null
                    if (call.string("name") != "delegate_subagents" || call.string("status") != "success") return@mapNotNull null
                    val resultId = call.string("resultId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val count = call.primitive("totalCharacterCount")?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
                    val sha = call.string("sha256")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    PersistedSubAgentReportRef(resultId, count, sha)
                }
            }.distinctBy { it.resultId }
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.string(name: String) = primitive(name)?.contentOrNull
    private fun JsonObject.primitive(name: String) = get(name) as? JsonPrimitive
}
