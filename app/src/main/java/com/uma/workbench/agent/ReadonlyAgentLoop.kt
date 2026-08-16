package com.uma.workbench.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

data class ReadonlyAgentRound(val index:Int,val assistantText:String,val toolCalls:List<AiToolCall>,val toolOutcomes:List<AgentToolOutcome>,val model:String?,val usage:AiTokenUsage?)
data class ReadonlyAgentRunResult(val requestId:String,val completeAnswer:String,val rounds:List<ReadonlyAgentRound>,val usage:AiTokenUsage,val model:String?,val messages:List<AiPromptMessage>)
data class ReadonlyAgentLoopLimits(val maxModelRounds:Int=20,val maxToolCallsPerRound:Int=8,val maxToolExecutionsPerRun:Int=64,val maxToolResultCharactersPerRun:Int=1_000_000,val maxConsecutiveCachedOnlyRounds:Int=3){init{require(maxModelRounds in 1..100);require(maxToolCallsPerRound in 1..64);require(maxToolExecutionsPerRun in 1..1_000);require(maxToolResultCharactersPerRun in 1..10_000_000);require(maxConsecutiveCachedOnlyRounds in 1..20)}}
interface ReadonlyAgentObserver{fun onModelEvent(round:Int,event:AiStreamEvent)=Unit;fun onRoundCompleted(round:ReadonlyAgentRound)=Unit}

class ReadonlyAgentLoop(
    private val provider:AiStreamingProvider,
    private val executor:ReadonlyAgentToolExecutor,
    private val limits:ReadonlyAgentLoopLimits=ReadonlyAgentLoopLimits(),
    private val specialToolHandler:AgentSpecialToolHandler?=null
){
 suspend fun run(initial:AiGenerationRequest,observer:ReadonlyAgentObserver?=null):ReadonlyAgentRunResult{
  val messages=initial.messages.toMutableList();val rounds=mutableListOf<ReadonlyAgentRound>();val runCache=linkedMapOf<String,AgentToolOutcome>();var usage=AiTokenUsage();var model=initial.model;var actualExecutions=0;var returnedToolCharacters=0;var consecutiveCachedOnlyRounds=0
  repeat(limits.maxModelRounds){roundIndex->
   currentCoroutineContext().ensureActive();val accumulator=AiToolCallAccumulator();val text=StringBuilder();var roundUsage:AiTokenUsage?=null
   provider.stream(initial.copy(messages=messages.toList())).collect{event->observer?.onModelEvent(roundIndex+1,event);when(event){is AiStreamEvent.TextDelta->text.append(event.completeDelta);is AiStreamEvent.ToolCallDelta->accumulator.append(event.delta);is AiStreamEvent.Usage->roundUsage=merge(roundUsage,event.usage);is AiStreamEvent.Model->model=event.model;AiStreamEvent.Completed->Unit}}
   val calls=accumulator.validatedSnapshot();require(calls.size<=limits.maxToolCallsPerRound){"本轮工具调用 ${calls.size} 次，超过上限 ${limits.maxToolCallsPerRound}"}
   val missing=linkedMapOf<String,AiToolCall>();calls.forEach{call->val key=AiToolCallNormalizer.semanticFingerprint(call);if(key !in runCache)missing.putIfAbsent(key,call)}
   require(actualExecutions+missing.size<=limits.maxToolExecutionsPerRun){"Agent 工具实际执行将超过总上限 ${limits.maxToolExecutionsPerRun}"}
   val requestSnapshot=initial.copy(messages=messages.toList())
   val newOutcomes=missing.values.map{call->
    if(call.name=="delegate_subagents"){
     val handler=specialToolHandler?:error("当前 Agent 不允许分派子 Agent")
     executor.executeSpecial(call){handler.execute(requestSnapshot,call)}
    }else executor.execute(call)
   }
   missing.keys.zip(newOutcomes).forEach{(key,outcome)->runCache[key]=outcome};actualExecutions+=newOutcomes.size
   val outcomes=calls.map{call->requireNotNull(runCache[AiToolCallNormalizer.semanticFingerprint(call)]).forCall(call)}
   val chars=outcomes.sumOf{it.modelContent().length};require(returnedToolCharacters+chars<=limits.maxToolResultCharactersPerRun){"Agent 工具结果字符数将超过总上限 ${limits.maxToolResultCharactersPerRun}"};returnedToolCharacters+=chars
   if(calls.isNotEmpty()&&newOutcomes.isEmpty())consecutiveCachedOnlyRounds++ else consecutiveCachedOnlyRounds=0;require(consecutiveCachedOnlyRounds<=limits.maxConsecutiveCachedOnlyRounds){"Agent 连续 $consecutiveCachedOnlyRounds 轮只重复已有工具调用，未产生新证据"}
   val round=ReadonlyAgentRound(roundIndex+1,text.toString(),calls,outcomes,model,roundUsage);rounds+=round;observer?.onRoundCompleted(round);roundUsage?.let{usage=add(usage,it)}
   if(calls.isEmpty()){require(text.isNotBlank()){"模型未返回工具调用，也未返回最终答案"};return ReadonlyAgentRunResult(initial.requestId,text.toString(),rounds,usage,model,messages.toList())}
   require(roundIndex+1<limits.maxModelRounds){"Agent 达到最大模型轮次 ${limits.maxModelRounds}"};messages+=AiPromptMessage("assistant",text.toString(),toolCalls=calls);calls.zip(outcomes).forEach{(call,outcome)->messages+=AiPromptMessage("tool",outcome.modelContent(),toolCallId=call.id,toolName=call.name)}
  };error("Agent 循环异常结束")
 }
 private fun AgentToolOutcome.forCall(call:AiToolCall)=when(this){is AgentToolOutcome.Success->AgentToolOutcome.Success(result.copy(callId=call.id,toolName=call.name,elapsedMillis=0));is AgentToolOutcome.Failure->AgentToolOutcome.Failure(failure.copy(callId=call.id,toolName=call.name,elapsedMillis=0))}
 private fun AgentToolOutcome.modelContent()=when(this){is AgentToolOutcome.Success->buildString{append(result.content);append("\n\n[tool_result_page]\nresultId=${result.resultId}\nrange=${result.startOffset}-${result.endOffsetExclusive}\ntotalCharacterCount=${result.totalCharacterCount}\nsha256=${result.sha256}\ncomplete=${result.complete}\nnextOffset=${result.nextOffset?:""}\nelapsedMillis=${result.elapsedMillis}");if(!result.complete)append("\nUse read_tool_result with this resultId and nextOffset to continue; do not claim the complete result was read yet.")};is AgentToolOutcome.Failure->"[tool_error]\n${failure.completeError}\nelapsedMillis=${failure.elapsedMillis}"}
 private fun merge(a:AiTokenUsage?,b:AiTokenUsage)=if(a==null)b else AiTokenUsage(maxOf(a.inputTokens,b.inputTokens),maxOf(a.outputTokens,b.outputTokens),maxOf(a.totalTokens,b.totalTokens),a.estimated||b.estimated)
 private fun add(a:AiTokenUsage,b:AiTokenUsage)=AiTokenUsage(a.inputTokens+b.inputTokens,a.outputTokens+b.outputTokens,a.totalTokens+b.totalTokens,a.estimated||b.estimated)
}
