package com.uma.workbench.agent

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class AgentToolResult(val callId: String, val toolName: String, val resultId: String, val content: String, val startOffset: Int, val endOffsetExclusive: Int, val totalCharacterCount: Int, val complete: Boolean, val nextOffset: Int?, val sha256: String, val elapsedMillis: Long)
data class AgentToolFailure(val callId: String, val toolName: String, val completeError: String, val elapsedMillis: Long)
sealed interface AgentToolOutcome { data class Success(val result: AgentToolResult) : AgentToolOutcome; data class Failure(val failure: AgentToolFailure) : AgentToolOutcome }
data class AgentToolExecutionLimits(val maxCallsPerTurn: Int = 8, val timeoutMillisPerCall: Long = 15_000, val pageCharacters: Int = 32_768) { init { require(maxCallsPerTurn in 1..64); require(timeoutMillisPerCall in 1..120_000); require(pageCharacters in 1..1_000_000) } }

interface AgentToolResultStore {
    fun put(completeContent: String, toolName: String): String
    fun read(resultId: String, offset: Int, limit: Int): AgentToolResultPage
}
data class AgentToolResultPage(val resultId: String, val content: String, val startOffset: Int, val endOffsetExclusive: Int, val totalCharacterCount: Int, val complete: Boolean, val nextOffset: Int?, val sha256: String)

class InMemoryAgentToolResultStore : AgentToolResultStore {
    private data class Stored(val content: String, val sha256: String)
    private val values = ConcurrentHashMap<String, Stored>()
    override fun put(completeContent: String, toolName: String): String = UUID.randomUUID().toString().also { values[it] = Stored(completeContent, sha256(completeContent)) }
    override fun read(resultId: String, offset: Int, limit: Int): AgentToolResultPage {
        require(offset >= 0); require(limit > 0); val stored = values[resultId] ?: error("找不到完整工具结果 $resultId"); require(offset <= stored.content.length)
        val end = (offset.toLong() + limit).coerceAtMost(stored.content.length.toLong()).toInt()
        return AgentToolResultPage(resultId, stored.content.substring(offset, end), offset, end, stored.content.length, end == stored.content.length, end.takeIf { it < stored.content.length }, stored.sha256)
    }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

interface ReadonlyAgentToolDataSource {
    suspend fun listWorkspaceFiles(): String; suspend fun readCurrentFile(): String; suspend fun readFile(uri: String): String; suspend fun readFileRange(uri: String, startLine: Int, endLine: Int): String
    suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean): String; suspend fun searchSymbol(query: String, offset: Int): String; suspend fun readIl2CppClass(className: String): String
    suspend fun readProtocolRecord(id: String): String; suspend fun readSoSnapshot(endpoint: String?): String; suspend fun readDoc(id: String): String
}

class ReadonlyAgentToolExecutor(private val source: ReadonlyAgentToolDataSource, private val limits: AgentToolExecutionLimits = AgentToolExecutionLimits(), private val resultStore: AgentToolResultStore = InMemoryAgentToolResultStore(), private val nowMillis: () -> Long = System::currentTimeMillis) {
    suspend fun executeTurn(calls: List<AiToolCall>): List<AgentToolOutcome> { require(calls.size <= limits.maxCallsPerTurn) { "本轮工具调用 ${calls.size} 次，超过上限 ${limits.maxCallsPerTurn}" }; require(calls.map { it.id }.distinct().size == calls.size); return calls.map { execute(it) } }
    suspend fun execute(call: AiToolCall): AgentToolOutcome { val started=nowMillis(); return try { val args=ReadonlyAgentToolPolicy.validate(call); val page=withTimeout(limits.timeoutMillisPerCall) { if(call.name=="read_tool_result") resultStore.read(args.requiredString("resultId"),args.optionalNonNegativeInt("offset")?:0,(args.optionalPositiveInt("limit")?:limits.pageCharacters).coerceAtMost(limits.pageCharacters)) else { val complete=dispatch(call.name,args); require(complete.isNotEmpty()); val id=resultStore.put(complete,call.name); resultStore.read(id,0,limits.pageCharacters) } }; AgentToolOutcome.Success(AgentToolResult(call.id,call.name,page.resultId,page.content,page.startOffset,page.endOffsetExclusive,page.totalCharacterCount,page.complete,page.nextOffset,page.sha256,(nowMillis()-started).coerceAtLeast(0))) } catch(e:Throwable){ if(e is kotlinx.coroutines.CancellationException) throw e; AgentToolOutcome.Failure(AgentToolFailure(call.id,call.name,e.stackTraceToString(),(nowMillis()-started).coerceAtLeast(0))) } }
    private suspend fun dispatch(name:String,a:JsonObject):String=when(name){"list_workspace_files"->source.listWorkspaceFiles();"read_current_file"->source.readCurrentFile();"read_file"->source.readFile(a.requiredString("uri"));"read_file_range"->{val s=a.requiredPositiveInt("startLine");val e=a.requiredPositiveInt("endLine");require(e>=s);source.readFileRange(a.requiredString("uri"),s,e)};"search_workspace"->source.searchWorkspace(a.requiredString("query"),a.optionalNonNegativeInt("offset")?:0,a.optionalBoolean("caseSensitive")?:false);"search_symbol"->source.searchSymbol(a.requiredString("query"),a.optionalNonNegativeInt("offset")?:0);"read_il2cpp_class"->source.readIl2CppClass(a.requiredString("className"));"read_protocol_record"->source.readProtocolRecord(a.requiredString("id"));"read_so_snapshot"->source.readSoSnapshot(a.optionalString("endpoint"));"read_doc"->source.readDoc(a.requiredString("id"));else->error("不允许执行工具 $name")}
    private fun JsonObject.requiredString(n:String)=optionalString(n)?.takeIf{it.isNotBlank()}?:error("参数 $n 必须是非空字符串");private fun JsonObject.optionalString(n:String)=(get(n) as? JsonPrimitive)?.contentOrNull;private fun JsonObject.requiredPositiveInt(n:String)=(get(n) as? JsonPrimitive)?.intOrNull?.takeIf{it>0}?:error("参数 $n 必须是正整数");private fun JsonObject.optionalPositiveInt(n:String):Int?{val v=get(n)?:return null;return(v as? JsonPrimitive)?.intOrNull?.takeIf{it>0}?:error("参数 $n 必须是正整数")};private fun JsonObject.optionalNonNegativeInt(n:String):Int?{val v=get(n)?:return null;return(v as? JsonPrimitive)?.intOrNull?.takeIf{it>=0}?:error("参数 $n 必须是非负整数")};private fun JsonObject.optionalBoolean(n:String):Boolean?{val v=get(n)?:return null;return(v as? JsonPrimitive)?.booleanOrNull?:error("参数 $n 必须是布尔值")}
}
