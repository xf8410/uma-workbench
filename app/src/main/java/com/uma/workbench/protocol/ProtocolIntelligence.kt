package com.uma.workbench.protocol

import kotlinx.serialization.json.*
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

object ProtocolRequestTemplates {
    fun forEndpoint(endpoint: GameEndpoint, session: GameSession?): String = when (endpoint) {
        GameEndpoint.BOOT -> objectBody("viewer_id" to "0", "app_ver" to quoted(session?.appVer ?: "2.29.0"), "device" to "4", "device_sub_type" to "1")
        GameEndpoint.LOGIN -> objectBody("viewer_id" to (session?.viewerId ?: 0L).toString(), "inherit_code" to quoted(session?.inheritCode.orEmpty()), "account_token" to quoted(session?.accountToken.orEmpty()))
        GameEndpoint.START_SESSION, GameEndpoint.LOAD_INDEX -> objectBody("viewer_id" to (session?.viewerId ?: 0L).toString())
        GameEndpoint.PRE_SIGNUP -> objectBody("viewer_id" to "0")
        GameEndpoint.SIGNUP -> objectBody("viewer_id" to (session?.viewerId ?: 0L).toString(), "inherit_code" to quoted(session?.inheritCode.orEmpty()))
    }
    private fun objectBody(vararg fields: Pair<String, String>) = fields.joinToString(",\n", "{\n", "\n}") { (name, value) -> "  ${quoted(name)}: $value" }
    private fun quoted(value: String) = buildString { append('"'); value.forEach { when (it) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(it) } }; append('"') }
}

data class ProtocolDiagnosis(val code: Int, val title: String, val explanation: String, val suggestedAction: String, val retryable: Boolean)
object ProtocolDiagnostics {
    fun diagnose(code: Int) = when (code) {
        200 -> ProtocolDiagnosis(code, "成功", "请求已正常处理。", "继续登录链或后续业务操作。", false)
        205 -> ProtocolDiagnosis(code, "资源不足", "当前账号资源不足以完成操作。", "读取完整响应确认缺少的资源后再决定是否重试。", false)
        102 -> ProtocolDiagnosis(code, "操作进行中", "服务端已接受操作，当前仍在处理。", "保留当前 SID 与 viewer_id，按端点流程继续查询。", true)
        1053 -> ProtocolDiagnosis(code, "跨日登录", "会话跨越了服务端日期边界。", "重新执行 login → start_session → load/index。", true)
        1055 -> ProtocolDiagnosis(code, "账号 token 过期", "当前账号 token 已失效。", "通过引继码登录流程获取新的账号 token。", true)
        216 -> ProtocolDiagnosis(code, "状态 216", "服务端要求进一步确认；具体含义应以完整响应为准。", "保留完整请求和响应，确认所需字段后继续。", false)
        217 -> ProtocolDiagnosis(code, "状态 217", "服务端要求进一步确认；具体含义应以完整响应为准。", "保留完整请求和响应，确认所需字段后继续。", false)
        218 -> ProtocolDiagnosis(code, "SID 与 viewer_id 不匹配", "SID 没有关联到请求中的有效 viewer_id。", "重新执行登录链，使用同一次会话产生的 SID 与 viewer_id。", true)
        else -> ProtocolDiagnosis(code, "未记录状态", "当前状态码不在已确认表中。", "保留完整请求、响应头和响应体后再分析。", false)
    }
}
data class InterpretedProtocolResponse(val httpStatus: Int, val protocolCode: Int, val diagnosis: ProtocolDiagnosis, val parsedJson: JsonElement?, val completeBody: String)
object ProtocolResponseInterpreter {
    private val json = Json { ignoreUnknownKeys = true }
    private val keys = listOf("result_code", "resultCode", "error_code", "errorCode", "status_code", "statusCode", "code")
    fun interpret(httpStatus: Int, body: String): InterpretedProtocolResponse { val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull(); val code = parsed?.let(::find) ?: httpStatus; return InterpretedProtocolResponse(httpStatus, code, ProtocolDiagnostics.diagnose(code), parsed, body) }
    private fun find(element: JsonElement): Int? = when (element) { is JsonObject -> keys.firstNotNullOfOrNull { element[it]?.asInt() } ?: element.values.firstNotNullOfOrNull(::find); is JsonArray -> element.firstNotNullOfOrNull(::find); else -> null }
    private fun JsonElement.asInt() = if (this is JsonPrimitive) intOrNull ?: contentOrNull?.toIntOrNull() else null
}
enum class ProtocolDiffKind { ADDED, REMOVED, CHANGED }
data class ProtocolDiffEntry(val path: String, val kind: ProtocolDiffKind, val before: String?, val after: String?)
object ProtocolPayloadDiff {
    private val json = Json { ignoreUnknownKeys = true }
    fun compare(before: String, after: String): List<ProtocolDiffEntry> { if (before == after) return emptyList(); val left = runCatching { json.parseToJsonElement(before) }.getOrNull(); val right = runCatching { json.parseToJsonElement(after) }.getOrNull(); if (left == null || right == null) return listOf(ProtocolDiffEntry("$", ProtocolDiffKind.CHANGED, before, after)); return buildList { compareElements("$", left, right, this) } }
    private fun compareElements(path: String, before: JsonElement, after: JsonElement, output: MutableList<ProtocolDiffEntry>) { if (before == after) return; when { before is JsonObject && after is JsonObject -> (before.keys + after.keys).forEach { key -> val l = before[key]; val r = after[key]; val p = "$path.${escape(key)}"; when { l == null -> output += ProtocolDiffEntry(p, ProtocolDiffKind.ADDED, null, r.complete()); r == null -> output += ProtocolDiffEntry(p, ProtocolDiffKind.REMOVED, l.complete(), null); else -> compareElements(p, l, r, output) } }; before is JsonArray && after is JsonArray -> repeat(maxOf(before.size, after.size)) { i -> val l = before.getOrNull(i); val r = after.getOrNull(i); val p = "$path[$i]"; when { l == null -> output += ProtocolDiffEntry(p, ProtocolDiffKind.ADDED, null, r.complete()); r == null -> output += ProtocolDiffEntry(p, ProtocolDiffKind.REMOVED, l.complete(), null); else -> compareElements(p, l, r, output) } }; else -> output += ProtocolDiffEntry(path, ProtocolDiffKind.CHANGED, before.complete(), after.complete()) } }
    private fun escape(key: String) = if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) key else "['${key.replace("'", "\\'")}']"
    private fun JsonElement?.complete() = when (this) { null -> null; JsonNull -> "null"; else -> toString() }
}
