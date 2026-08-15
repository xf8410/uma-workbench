package com.uma.workbench.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Public function schemas sent to tool-capable providers. They expose no mutation operation. */
object ReadonlyAgentToolSchemas {
    val openAiCompatible: JsonArray = buildJsonArray {
        function("list_workspace_files", "列出当前工作区允许读取的文件")
        function("read_current_file", "读取当前活动文件的完整文本")
        function("read_file", "按工作区 URI 读取完整文件", strings = listOf("uri"), required = listOf("uri"))
        function("read_file_range", "按工作区 URI 和行号读取文件范围", strings = listOf("uri"), integers = listOf("startLine", "endLine"), required = listOf("uri", "startLine", "endLine"))
        function("search_workspace", "在当前工作区进行分页字面量搜索", strings = listOf("query"), integers = listOf("offset"), booleans = listOf("caseSensitive"), required = listOf("query"))
        function("search_symbol", "在当前工作区进行区分大小写的符号字面量搜索", strings = listOf("query"), integers = listOf("offset"), required = listOf("query"))
        function("read_il2cpp_class", "通过本地 hlpatch 读取 IL2CPP 类字段和方法", strings = listOf("className"), required = listOf("className"))
        function("read_protocol_record", "按 ID 读取已脱除 SID 和请求头的协议记录", strings = listOf("id"), required = listOf("id"))
        function("read_so_snapshot", "读取本地 hlpatch 只读 GET 端点；默认 /summary", strings = listOf("endpoint"))
        function("read_doc", "按 ID 读取当前工作区 Doc", strings = listOf("id"), required = listOf("id"))
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.function(
        name: String,
        description: String,
        strings: List<String> = emptyList(),
        integers: List<String> = emptyList(),
        booleans: List<String> = emptyList(),
        required: List<String> = emptyList()
    ) {
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                    put("properties", buildJsonObject {
                        strings.forEach { property -> put(property, buildJsonObject { put("type", "string") }) }
                        integers.forEach { property -> put(property, buildJsonObject { put("type", "integer"); put("minimum", 0) }) }
                        booleans.forEach { property -> put(property, buildJsonObject { put("type", "boolean") }) }
                    })
                    put("required", buildJsonArray { required.forEach(::add) })
                })
            })
        })
    }
}
