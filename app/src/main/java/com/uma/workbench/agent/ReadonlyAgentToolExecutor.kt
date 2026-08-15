package com.uma.workbench.agent

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Exact result retained by the Agent run. Content is never represented as success when empty. */
data class AgentToolResult(
    val callId: String,
    val toolName: String,
    val completeContent: String,
    val characterCount: Int,
    val elapsedMillis: Long,
    val truncated: Boolean = false,
    val continuation: String? = null
)

data class AgentToolFailure(
    val callId: String,
    val toolName: String,
    val completeError: String,
    val elapsedMillis: Long
)

sealed interface AgentToolOutcome {
    data class Success(val result: AgentToolResult) : AgentToolOutcome
    data class Failure(val failure: AgentToolFailure) : AgentToolOutcome
}

data class AgentToolExecutionLimits(
    val maxCallsPerTurn: Int = 8,
    val timeoutMillisPerCall: Long = 15_000,
    val maxResultCharacters: Int = 100_000
) {
    init {
        require(maxCallsPerTurn in 1..64)
        require(timeoutMillisPerCall in 1..120_000)
        require(maxResultCharacters in 1..2_000_000)
    }
}

/** Platform data access. Implementations must only expose the current workspace. */
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

/**
 * Executes only the allow-listed read operations. Validation happens before dispatch; sequential
 * execution makes the per-turn call budget deterministic. Coroutine cancellation stops the current
 * operation and prevents remaining calls from starting.
 */
class ReadonlyAgentToolExecutor(
    private val source: ReadonlyAgentToolDataSource,
    private val limits: AgentToolExecutionLimits = AgentToolExecutionLimits(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun executeTurn(calls: List<AiToolCall>): List<AgentToolOutcome> {
        require(calls.size <= limits.maxCallsPerTurn) {
            "本轮工具调用 ${calls.size} 次，超过上限 ${limits.maxCallsPerTurn}"
        }
        require(calls.map { it.id }.distinct().size == calls.size) { "本轮存在重复工具调用 id" }
        return calls.map { execute(it) }
    }

    suspend fun execute(call: AiToolCall): AgentToolOutcome {
        val started = nowMillis()
        return try {
            val arguments = ReadonlyAgentToolPolicy.validate(call)
            val complete = withTimeout(limits.timeoutMillisPerCall) { dispatch(call.name, arguments) }
            require(complete.isNotEmpty()) { "工具 ${call.name} 返回空结果" }
            val sent = complete.take(limits.maxResultCharacters)
            val truncated = sent.length < complete.length
            AgentToolOutcome.Success(
                AgentToolResult(
                    callId = call.id,
                    toolName = call.name,
                    completeContent = sent,
                    characterCount = complete.length,
                    elapsedMillis = (nowMillis() - started).coerceAtLeast(0),
                    truncated = truncated,
                    continuation = if (truncated) "characters:${sent.length}-${complete.length}" else null
                )
            )
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            AgentToolOutcome.Failure(
                AgentToolFailure(call.id, call.name, error.stackTraceToString(), (nowMillis() - started).coerceAtLeast(0))
            )
        }
    }

    private suspend fun dispatch(name: String, args: JsonObject): String = when (name) {
        "list_workspace_files" -> source.listWorkspaceFiles()
        "read_current_file" -> source.readCurrentFile()
        "read_file" -> source.readFile(args.requiredString("uri"))
        "read_file_range" -> {
            val start = args.requiredPositiveInt("startLine")
            val end = args.requiredPositiveInt("endLine")
            require(end >= start) { "endLine 不能小于 startLine" }
            source.readFileRange(args.requiredString("uri"), start, end)
        }
        "search_workspace" -> source.searchWorkspace(
            args.requiredString("query"),
            args.optionalNonNegativeInt("offset") ?: 0,
            args.optionalBoolean("caseSensitive") ?: false
        )
        "search_symbol" -> source.searchSymbol(args.requiredString("query"), args.optionalNonNegativeInt("offset") ?: 0)
        "read_il2cpp_class" -> source.readIl2CppClass(args.requiredString("className"))
        "read_protocol_record" -> source.readProtocolRecord(args.requiredString("id"))
        "read_so_snapshot" -> source.readSoSnapshot(args.optionalString("endpoint"))
        "read_doc" -> source.readDoc(args.requiredString("id"))
        else -> error("不允许执行工具 $name")
    }

    private fun JsonObject.requiredString(name: String): String = optionalString(name)
        ?.takeIf { it.isNotBlank() } ?: error("参数 $name 必须是非空字符串")

    private fun JsonObject.optionalString(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.requiredPositiveInt(name: String): Int = (get(name) as? JsonPrimitive)?.intOrNull
        ?.takeIf { it > 0 } ?: error("参数 $name 必须是正整数")

    private fun JsonObject.optionalNonNegativeInt(name: String): Int? {
        val value = get(name) ?: return null
        return (value as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
            ?: error("参数 $name 必须是非负整数")
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val value = get(name) ?: return null
        return (value as? JsonPrimitive)?.booleanOrNull ?: error("参数 $name 必须是布尔值")
    }
}
