package com.uma.workbench.agent

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Provider-neutral tool-call model after all streaming deltas have been assembled. */
data class AiToolCall(val index: Int, val id: String, val name: String, val completeArgumentsJson: String)
data class AiToolCallDelta(val index: Int, val idFragment: String = "", val nameFragment: String = "", val argumentsFragment: String = "")

object ReadonlyAgentToolPolicy {
    val allowedNames: Set<String> = linkedSetOf(
        "list_workspace_files", "read_current_file", "read_file", "read_file_range",
        "search_workspace", "search_symbol", "read_il2cpp_class", "read_protocol_record",
        "read_so_snapshot", "read_doc", "read_tool_result"
    )
    val toolsAllowingEmptyArguments: Set<String> = setOf(
        "list_workspace_files", "read_current_file", "read_so_snapshot"
    )
    private val json = Json { ignoreUnknownKeys = false }

    fun validate(call: AiToolCall): JsonObject {
        require(call.name in allowedNames) { "不允许执行工具 ${call.name}" }
        require(call.id.isNotBlank()) { "工具调用 ${call.name} 缺少 id" }
        require(call.completeArgumentsJson.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "工具调用 ${call.name} 参数超过 65536 字节" }
        return json.parseToJsonElement(call.completeArgumentsJson) as? JsonObject
            ?: error("工具调用 ${call.name} 参数必须是 JSON object")
    }
}

/**
 * Shared normalization for every provider. It removes only completely empty streaming placeholders,
 * canonicalizes object-key order, and rejects ambiguous duplicate ids before execution/history write.
 */
object AiToolCallNormalizer {
    private val json = Json { ignoreUnknownKeys = false }

    fun normalize(calls: List<AiToolCall>): List<AiToolCall> {
        val normalized = calls.mapNotNull(::normalizeOne)
        val byId = linkedMapOf<String, AiToolCall>()
        normalized.forEach { call ->
            val previous = byId[call.id]
            when {
                previous == null -> byId[call.id] = call
                semanticFingerprint(previous) == semanticFingerprint(call) -> Unit
                else -> error("工具调用 id ${call.id} 对应了不同的工具或参数")
            }
        }
        return byId.values.toList()
    }

    fun semanticFingerprint(call: AiToolCall): String {
        val value = "${call.name}\n${call.completeArgumentsJson}"
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun normalizeOne(raw: AiToolCall): AiToolCall? {
        val id = raw.id.trim()
        val name = raw.name.trim()
        val arguments = raw.completeArgumentsJson.trim()
        if (id.isEmpty() && name.isEmpty() && arguments.isEmpty()) return null
        require(id.isNotEmpty()) { "工具调用 $name 缺少 id" }
        require(name.isNotEmpty()) { "工具调用 $id 缺少 name" }
        val effectiveArguments = when {
            arguments.isNotEmpty() -> arguments
            name in ReadonlyAgentToolPolicy.toolsAllowingEmptyArguments -> "{}"
            else -> error("工具调用 $name 缺少 arguments")
        }
        require(effectiveArguments.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "工具调用 $name 参数超过 65536 字节" }
        val parsed = json.parseToJsonElement(effectiveArguments) as? JsonObject
            ?: error("工具调用 $name 参数必须是 JSON object")
        val call = AiToolCall(raw.index, id, name, canonicalize(parsed).toString())
        ReadonlyAgentToolPolicy.validate(call)
        return call
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) })
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }
}

class AiToolCallAccumulator {
    private data class MutableCall(
        val index: Int,
        val id: StringBuilder = StringBuilder(),
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    )
    private val calls = linkedMapOf<Int, MutableCall>()

    fun append(delta: AiToolCallDelta) {
        require(delta.index >= 0) { "工具调用 index 不能为负数" }
        val call = calls.getOrPut(delta.index) { MutableCall(delta.index) }
        call.id.append(delta.idFragment)
        call.name.append(delta.nameFragment)
        call.arguments.append(delta.argumentsFragment)
    }

    fun snapshot(): List<AiToolCall> = calls.values.sortedBy { it.index }.map {
        AiToolCall(it.index, it.id.toString(), it.name.toString(), it.arguments.toString())
    }

    fun validatedSnapshot(): List<AiToolCall> = AiToolCallNormalizer.normalize(snapshot())
}
