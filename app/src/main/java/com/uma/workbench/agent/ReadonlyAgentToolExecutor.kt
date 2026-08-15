package com.uma.workbench.agent

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One exact page backed by a locally retained complete tool result. */
data class AgentToolResult(
    val callId: String,
    val toolName: String,
    val resultId: String,
    val content: String,
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val totalCharacterCount: Int,
    val complete: Boolean,
    val nextOffset: Int?,
    val elapsedMillis: Long
)

data class AgentToolFailure(val callId: String, val toolName: String, val completeError: String, val elapsedMillis: Long)
sealed interface AgentToolOutcome {
    data class Success(val result: AgentToolResult) : AgentToolOutcome
    data class Failure(val failure: AgentToolFailure) : AgentToolOutcome
}

data class AgentToolExecutionLimits(
    val maxCallsPerTurn: Int = 8,
    val timeoutMillisPerCall: Long = 15_000,
    val pageCharacters: Int = 32_768
) {
    init {
        require(maxCallsPerTurn in 1..64)
        require(timeoutMillisPerCall in 1..120_000)
        require(pageCharacters in 1..1_000_000)
    }
}

/** Complete values remain available by resultId; reading a page never deletes or rewrites them. */
interface AgentToolResultStore {
    fun put(completeContent: String): String
    fun read(resultId: String, offset: Int, limit: Int): AgentToolResultPage
}

data class AgentToolResultPage(
    val resultId: String,
    val content: String,
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val totalCharacterCount: Int,
    val complete: Boolean,
    val nextOffset: Int?
)

class InMemoryAgentToolResultStore : AgentToolResultStore {
    private val completeResults = ConcurrentHashMap<String, String>()
    override fun put(completeContent: String): String = UUID.randomUUID().toString().also { completeResults[it] = completeContent }
    override fun read(resultId: String, offset: Int, limit: Int): AgentToolResultPage {
        require(offset >= 0) { "offset 必须是非负整数" }
        require(limit > 0) { "limit 必须是正整数" }
        val complete = completeResults[resultId] ?: error("找不到完整工具结果 $resultId")
        require(offset <= complete.length) { "offset $offset 超过完整结果长度 ${complete.length}" }
        val end = (offset.toLong() + limit).coerceAtMost(complete.length.toLong()).toInt()
        return AgentToolResultPage(resultId, complete.substring(offset, end), offset, end, complete.length, end == complete.length, end.takeIf { it < complete.length })
    }
}

interface ReadonlyAgentToolDataSource {
    suspend fun listWorkspaceFiles(): String
    suspend fun readCurrentFile(): String
    suspend fun readFile(uri: String): String
    suspend fun readFileRange(uri: String, startLine: Int, endLine: Int): String
    suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean): String
    suspend fun searchSymbol(query: String, offset: Int): String
    suspend fun readIl2CppClass(className: String): String
    suspend fun readProtocolRecord(id: String): String
    suspend fun readSoSnapshot(endpoint: String?): String
    suspend fun readDoc(id: String): String
}

class ReadonlyAgentToolExecutor(
    private val source: ReadonlyAgentToolDataSource,
    private val limits: AgentToolExecutionLimits = AgentToolExecutionLimits(),
    private val resultStore: AgentToolResultStore = InMemoryAgentToolResultStore(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun executeTurn(calls: List<AiToolCall>): List<AgentToolOutcome> {
        require(calls.size <= limits.maxCallsPerTurn) { "本轮工具调用 ${calls.size} 次，超过上限 ${limits.maxCallsPerTurn}" }
        require(calls.map { it.id }.distinct().size == calls.size) { "本轮存在重复工具调用 id" }
        return calls.map { execute(it) }
    }

    suspend fun execute(call: AiToolCall): AgentToolOutcome {
        val started = nowMillis()
        return try {
            val arguments = ReadonlyAgentToolPolicy.validate(call)
            val page = withTimeout(limits.timeoutMillisPerCall) {
                if (call.name == "read_tool_result") {
                    resultStore.read(
                        arguments.requiredString("resultId"),
                        arguments.optionalNonNegativeInt("offset") ?: 0,
                        (arguments.optionalPositiveInt("limit") ?: limits.pageCharacters).coerceAtMost(limits.pageCharacters)
                    )
                } else {
                    val complete = dispatch(call.name, arguments)
                    require(complete.isNotEmpty()) { "工具 ${call.name} 返回空结果" }
                    val resultId = resultStore.put(complete)
                    resultStore.read(resultId, 0, limits.pageCharacters)
                }
            }
            AgentToolOutcome.Success(AgentToolResult(call.id, call.name, page.resultId, page.content, page.startOffset, page.endOffsetExclusive, page.totalCharacterCount, page.complete, page.nextOffset, (nowMillis() - started).coerceAtLeast(0)))
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            AgentToolOutcome.Failure(AgentToolFailure(call.id, call.name, error.stackTraceToString(), (nowMillis() - started).coerceAtLeast(0)))
        }
    }

    private suspend fun dispatch(name: String, args: JsonObject): String = when (name) {
        "list_workspace_files" -> source.listWorkspaceFiles()
        "read_current_file" -> source.readCurrentFile()
        "read_file" -> source.readFile(args.requiredString("uri"))
        "read_file_range" -> { val start = args.requiredPositiveInt("startLine"); val end = args.requiredPositiveInt("endLine"); require(end >= start) { "endLine 不能小于 startLine" }; source.readFileRange(args.requiredString("uri"), start, end) }
        "search_workspace" -> source.searchWorkspace(args.requiredString("query"), args.optionalNonNegativeInt("offset") ?: 0, args.optionalBoolean("caseSensitive") ?: false)
        "search_symbol" -> source.searchSymbol(args.requiredString("query"), args.optionalNonNegativeInt("offset") ?: 0)
        "read_il2cpp_class" -> source.readIl2CppClass(args.requiredString("className"))
        "read_protocol_record" -> source.readProtocolRecord(args.requiredString("id"))
        "read_so_snapshot" -> source.readSoSnapshot(args.optionalString("endpoint"))
        "read_doc" -> source.readDoc(args.requiredString("id"))
        else -> error("不允许执行工具 $name")
    }

    private fun JsonObject.requiredString(name: String): String = optionalString(name)?.takeIf { it.isNotBlank() } ?: error("参数 $name 必须是非空字符串")
    private fun JsonObject.optionalString(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.requiredPositiveInt(name: String): Int = (get(name) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: error("参数 $name 必须是正整数")
    private fun JsonObject.optionalPositiveInt(name: String): Int? { val value = get(name) ?: return null; return (value as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: error("参数 $name 必须是正整数") }
    private fun JsonObject.optionalNonNegativeInt(name: String): Int? { val value = get(name) ?: return null; return (value as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: error("参数 $name 必须是非负整数") }
    private fun JsonObject.optionalBoolean(name: String): Boolean? { val value = get(name) ?: return null; return (value as? JsonPrimitive)?.booleanOrNull ?: error("参数 $name 必须是布尔值") }
}
