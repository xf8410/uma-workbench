package com.uma.workbench.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class AiToolCall(val index: Int, val id: String, val name: String, val completeArgumentsJson: String)
data class AiToolCallDelta(val index: Int, val idFragment: String = "", val nameFragment: String = "", val argumentsFragment: String = "")

object ReadonlyAgentToolPolicy {
    val allowedNames: Set<String> = linkedSetOf(
        "list_workspace_files", "read_current_file", "read_file", "read_file_range",
        "search_workspace", "search_symbol", "read_il2cpp_class", "read_protocol_record",
        "read_so_snapshot", "read_doc", "read_tool_result"
    )
    private val json = Json { ignoreUnknownKeys = false }
    fun validate(call: AiToolCall): JsonObject {
        require(call.name in allowedNames) { "不允许执行工具 ${call.name}" }
        require(call.id.isNotBlank()) { "工具调用 ${call.name} 缺少 id" }
        require(call.completeArgumentsJson.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "工具调用 ${call.name} 参数超过 65536 字节" }
        return json.parseToJsonElement(call.completeArgumentsJson) as? JsonObject ?: error("工具调用 ${call.name} 参数必须是 JSON object")
    }
}

class AiToolCallAccumulator {
    private data class MutableCall(val index: Int, val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val arguments: StringBuilder = StringBuilder())
    private val calls = linkedMapOf<Int, MutableCall>()
    fun append(delta: AiToolCallDelta) { require(delta.index >= 0) { "工具调用 index 不能为负数" }; val call = calls.getOrPut(delta.index) { MutableCall(delta.index) }; call.id.append(delta.idFragment); call.name.append(delta.nameFragment); call.arguments.append(delta.argumentsFragment) }
    fun snapshot(): List<AiToolCall> = calls.values.sortedBy { it.index }.map { AiToolCall(it.index, it.id.toString(), it.name.toString(), it.arguments.toString()) }
    fun validatedSnapshot(): List<AiToolCall> = snapshot().onEach(ReadonlyAgentToolPolicy::validate)
}
