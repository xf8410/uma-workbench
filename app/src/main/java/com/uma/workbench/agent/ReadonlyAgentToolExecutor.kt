package com.uma.workbench.agent

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class AgentToolResult(val callId:String,val toolName:String,val resultId:String,val content:String,val startOffset:Int,val endOffsetExclusive:Int,val totalCharacterCount:Int,val complete:Boolean,val nextOffset:Int?,val sha256:String,val elapsedMillis:Long)
data class AgentToolFailure(val callId:String,val toolName:String,val completeError:String,val elapsedMillis:Long)
sealed interface AgentToolOutcome{data class Success(val result:AgentToolResult):AgentToolOutcome;data class Failure(val failure:AgentToolFailure):AgentToolOutcome}
interface AgentToolResultStore{fun put(completeContent:String,toolName:String):String;fun read(resultId:String,offset:Int,limit:Int):AgentToolResultPage}
data class AgentToolResultPage(val resultId:String,val content:String,val startOffset:Int,val endOffsetExclusive:Int,val totalCharacterCount:Int,val complete:Boolean,val nextOffset:Int?,val sha256:String)
data class AgentToolExecutionLimits(val maxCallsPerTurn:Int=8,val pageCharacters:Int=32_768){init{require(maxCallsPerTurn in 1..256){"maxCallsPerTurn 必须在 1..256"};require(pageCharacters in 1..1_000_000){"pageCharacters 必须在 1..1_000_000"}}}

class InMemoryAgentToolResultStore:AgentToolResultStore{
 private data class Stored(val content:String,val sha256:String);private val values=ConcurrentHashMap<String,Stored>()
 override fun put(completeContent:String,toolName:String):String{require(completeContent.isNotEmpty()){"完整工具结果不能为空"};require(toolName.isNotBlank()){"toolName 不能为空"};return UUID.randomUUID().toString().also{values[it]=Stored(completeContent,sha256(completeContent))}}
 override fun read(resultId:String,offset:Int,limit:Int):AgentToolResultPage{require(offset>=0){"offset 必须是非负整数"};require(limit>0){"limit 必须是正整数"};val stored=values[resultId]?:error("找不到完整工具结果 $resultId");require(offset<=stored.content.length){"offset $offset 超过完整结果长度 ${stored.content.length}"};val end=(offset.toLong()+limit).coerceAtMost(stored.content.length.toLong()).toInt();val next=end.takeIf{it<stored.content.length};return AgentToolResultPage(resultId,stored.content.substring(offset,end),offset,end,stored.content.length,next==null,next,stored.sha256)}
 private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
}

interface ReadonlyAgentToolDataSource{
 suspend fun listWorkspaceFiles():String;suspend fun readCurrentFile():String;suspend fun readFile(uri:String):String;suspend fun readFileRange(uri:String,startLine:Int,endLine:Int):String;suspend fun searchWorkspace(query:String,offset:Int,caseSensitive:Boolean):String;suspend fun searchSymbol(query:String,offset:Int):String;suspend fun readIl2CppClass(className:String):String;suspend fun readProtocolRecord(id:String):String;suspend fun readSoSnapshot(endpoint:String?):String;suspend fun readDoc(id:String):String
}

class ReadonlyAgentToolExecutor(
 private val source:ReadonlyAgentToolDataSource,
 private val limits:AgentToolExecutionLimits=AgentToolExecutionLimits(),
 private val resultStore:AgentToolResultStore=InMemoryAgentToolResultStore(),
 private val nowMillis:()->Long=System::currentTimeMillis,
 private val githubSource:GitHubReadonlyAgentToolDataSource?=null,
 private val githubContributionSource:GitHubContributionAgentToolDataSource?=null,
 private val githubCloneSource:GitHubCloneAgentToolDataSource?=null
) : AgentToolExecutor {
 suspend fun executeTurn(rawCalls:List<AiToolCall>):List<AgentToolOutcome>{val calls=AiToolCallNormalizer.normalize(rawCalls);require(calls.size<=limits.maxCallsPerTurn){"本轮工具调用 ${calls.size} 超过上限 ${limits.maxCallsPerTurn}"};val cache=linkedMapOf<String,AgentToolOutcome>();return calls.map{call->val key=AiToolCallNormalizer.semanticFingerprint(call);cache[key]?.forCall(call)?:execute(call).also{cache[key]=it}}}
 override suspend fun execute(call:AiToolCall):AgentToolOutcome=timed(call){args->if(call.name=="read_tool_result")readStoredPage(args)else storePaged(call.name,dispatch(call.name,args))}
 override suspend fun executeSpecial(call:AiToolCall,operation:suspend()->AgentSpecialToolPayload):AgentToolOutcome{val started=nowMillis();return try{ReadonlyAgentToolPolicy.validate(call);val payload=operation();require(payload.persistedContent.isNotEmpty()){"特殊工具完整结果不能为空"};require(payload.modelContent.isNotEmpty()){"特殊工具引用清单不能为空"};val id=resultStore.put(payload.persistedContent,call.name);val stored=completeStored(id);AgentToolOutcome.Success(AgentToolResult(call.id,call.name,id,payload.modelContent,0,0,stored.totalCharacterCount,false,0,stored.sha256,(nowMillis()-started).coerceAtLeast(0)))}catch(error:Throwable){if(error is kotlinx.coroutines.CancellationException)throw error;AgentToolOutcome.Failure(AgentToolFailure(call.id,call.name,error.stackTraceToString(),(nowMillis()-started).coerceAtLeast(0)))}}
 private suspend fun timed(call:AiToolCall,operation:suspend(JsonObject)->AgentToolResultPage):AgentToolOutcome{val started=nowMillis();return try{val page=operation(ReadonlyAgentToolPolicy.validate(call));AgentToolOutcome.Success(AgentToolResult(call.id,call.name,page.resultId,page.content,page.startOffset,page.endOffsetExclusive,page.totalCharacterCount,page.complete,page.nextOffset,page.sha256,(nowMillis()-started).coerceAtLeast(0)))}catch(error:Throwable){if(error is kotlinx.coroutines.CancellationException)throw error;AgentToolOutcome.Failure(AgentToolFailure(call.id,call.name,error.stackTraceToString(),(nowMillis()-started).coerceAtLeast(0)))}}
 private fun storePaged(name:String,content:String):AgentToolResultPage{require(content.isNotEmpty()){"工具完整结果不能为空"};val id=resultStore.put(content,name);return resultStore.read(id,0,limits.pageCharacters)}
 private fun readStoredPage(args:JsonObject):AgentToolResultPage{val id=args.requiredString("resultId");val offset=args.optionalNonNegativeInt("offset")?:0;val limit=args.optionalPositiveInt("limit")?:limits.pageCharacters;return resultStore.read(id,offset,limit)}
 private fun completeStored(id:String)=resultStore.read(id,0,Int.MAX_VALUE)
 private fun AgentToolOutcome.forCall(call:AiToolCall)=when(this){is AgentToolOutcome.Success->AgentToolOutcome.Success(result.copy(callId=call.id,toolName=call.name,elapsedMillis=0));is AgentToolOutcome.Failure->AgentToolOutcome.Failure(failure.copy(callId=call.id,toolName=call.name,elapsedMillis=0))}
 private fun github():GitHubReadonlyAgentToolDataSource=githubSource?:error("GitHub 只读工具未配置")
 private fun githubContribution():GitHubContributionAgentToolDataSource=githubContributionSource?:error("GitHub 贡献流工具未配置")
 private fun githubClone():GitHubCloneAgentToolDataSource=githubCloneSource?:error("GitHub 克隆工具未配置")
 private suspend fun dispatch(name:String,args:JsonObject):String=when(name){
  "list_workspace_files"->source.listWorkspaceFiles()
  "read_current_file"->source.readCurrentFile()
  "read_file"->source.readFile(args.requiredString("uri"))
  "read_file_range"->{val start=args.requiredPositiveInt("startLine");val end=args.requiredPositiveInt("endLine");require(end>=start){"endLine 不能小于 startLine"};source.readFileRange(args.requiredString("uri"),start,end)}
  "search_workspace"->source.searchWorkspace(args.requiredString("query"),args.optionalNonNegativeInt("offset")?:0,args.optionalBoolean("caseSensitive")?:false)
  "search_symbol"->source.searchSymbol(args.requiredString("query"),args.optionalNonNegativeInt("offset")?:0)
  "read_il2cpp_class"->source.readIl2CppClass(args.requiredString("className"))
  "read_protocol_record"->source.readProtocolRecord(args.requiredString("id"))
  "read_so_snapshot"->source.readSoSnapshot(args.optionalString("endpoint"))
  "read_doc"->source.readDoc(args.requiredString("id"))
  "github_list_repositories"->github().listRepositories(args.optionalPositiveInt("page")?:1)
  "github_get_repository"->github().getRepository(args.requiredString("owner"),args.requiredString("name"))
  "github_list_branches"->github().listBranches(args.requiredString("owner"),args.requiredString("name"))
  "github_read_file"->github().readFile(args.requiredString("owner"),args.requiredString("name"),args.requiredString("ref"),args.requiredStringAllowEmpty("path"))
  "github_list_commits"->github().listCommits(args.requiredString("owner"),args.requiredString("name"),args.requiredString("ref"),args.optionalPositiveInt("page")?:1)
  "github_get_workflow_runs"->github().getWorkflowRuns(args.requiredString("owner"),args.requiredString("name"),args.optionalPositiveInt("page")?:1)
  "github_clone_repository"->githubClone().cloneRepository(args.requiredString("owner"),args.requiredString("repo"),args.optionalString("ref")?:"")
  "github_contribute_fork"->githubContribution().forkRepository(args.requiredString("owner"),args.requiredString("repo"),args.requiredString("confirmationId"))
  "github_contribute_branch"->githubContribution().createBranch(args.requiredString("progress"),args.requiredString("branch"),args.requiredString("confirmationId"))
  "github_contribute_write"->githubContribution().writeFile(args.requiredString("progress"),args.requiredString("path"),args.requiredString("content"),args.requiredString("commitMessage"),args.requiredString("confirmationId"))
  "github_contribute_pr"->githubContribution().createPullRequest(args.requiredString("progress"),args.requiredString("title"),args.requiredString("body"),args.optionalBoolean("draft")?:false,args.requiredString("confirmationId"))
  else->error("不允许执行工具 $name")
 }
 private fun JsonObject.requiredString(name:String)=optionalString(name)?.takeIf{it.isNotBlank()}?:error("参数 $name 必须是非空字符串")
 private fun JsonObject.requiredStringAllowEmpty(name:String):String{val value=get(name)?:error("缺少参数 $name");return(value as? JsonPrimitive)?.contentOrNull?:error("参数 $name 必须是字符串")}
 private fun JsonObject.optionalString(name:String)=(get(name)as? JsonPrimitive)?.contentOrNull
 private fun JsonObject.requiredPositiveInt(name:String)=(get(name)as? JsonPrimitive)?.intOrNull?.takeIf{it>0}?:error("参数 $name 必须是正整数")
 private fun JsonObject.optionalPositiveInt(name:String):Int?{val value=get(name)?:return null;return(value as? JsonPrimitive)?.intOrNull?.takeIf{it>0}?:error("参数 $name 必须是正整数")}
 private fun JsonObject.optionalNonNegativeInt(name:String):Int?{val value=get(name)?:return null;return(value as? JsonPrimitive)?.intOrNull?.takeIf{it>=0}?:error("参数 $name 必须是非负整数")}
 private fun JsonObject.optionalBoolean(name:String):Boolean?{val value=get(name)?:return null;return(value as? JsonPrimitive)?.booleanOrNull?:error("参数 $name 必须是布尔值")}
}
