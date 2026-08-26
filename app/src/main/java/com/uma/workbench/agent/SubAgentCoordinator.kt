package com.uma.workbench.agent

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SubAgentTask(val id:String,val instruction:String,val evidenceRequirements:String="引用实际读取到的文件、结果 ID、范围或哈希；无法验证时明确说明")
data class SubAgentLimits(val maxTasksPerDispatch:Int=4,val maxParallelTasks:Int=2,val maxDepth:Int=1,val maxInstructionCharacters:Int=16_384,val maxCombinedAnswerCharacters:Int=131_072){init{require(maxTasksPerDispatch in 1..16);require(maxParallelTasks in 1..8);require(maxParallelTasks<=maxTasksPerDispatch);require(maxDepth in 1..4);require(maxInstructionCharacters in 1..100_000);require(maxCombinedAnswerCharacters in 1..1_000_000)}}
data class SubAgentResult(val taskId:String,val requestId:String,val answer:String,val rounds:List<ReadonlyAgentRound>,val usage:AiTokenUsage,val model:String?)
data class SubAgentFailure(val taskId:String,val completeError:String)
sealed interface SubAgentOutcome{val taskId:String;data class Success(val result:SubAgentResult):SubAgentOutcome{override val taskId get()=result.taskId};data class Failure(val failure:SubAgentFailure):SubAgentOutcome{override val taskId get()=failure.taskId}}
fun interface SubAgentLoopFactory{fun create():ReadonlyAgentLoop}

/** Provider-independent, bounded orchestration. Children never receive parent conversation history or delegation tools. */
class SubAgentCoordinator(private val loopFactory:SubAgentLoopFactory,private val limits:SubAgentLimits=SubAgentLimits(),private val requestIdFactory:()->String={UUID.randomUUID().toString()}){
 suspend fun dispatch(parentRequest:AiGenerationRequest,tasks:List<SubAgentTask>,depth:Int=1):List<SubAgentOutcome>{
  require(depth in 1..limits.maxDepth){"子 Agent 深度 $depth 超过上限 ${limits.maxDepth}"};require(tasks.isNotEmpty()){"子 Agent 任务不能为空"};require(tasks.size<=limits.maxTasksPerDispatch){"子 Agent 任务 ${tasks.size} 个，超过上限 ${limits.maxTasksPerDispatch}"};require(tasks.map{it.id}.distinct().size==tasks.size){"子 Agent taskId 必须唯一"}
  tasks.forEach{require(it.id.isNotBlank()){"子 Agent taskId 不能为空"};require(it.instruction.isNotBlank()){"子 Agent ${it.id} 指令不能为空"};require(it.instruction.length+it.evidenceRequirements.length<=limits.maxInstructionCharacters){"子 Agent ${it.id} 指令超过字符上限 ${limits.maxInstructionCharacters}"}}
  val semaphore=Semaphore(limits.maxParallelTasks);val outcomes=supervisorScope{tasks.map{task->async{semaphore.withPermit{execute(parentRequest,task,depth)}}}.awaitAll()};val chars=outcomes.sumOf{when(it){is SubAgentOutcome.Success->it.result.answer.length;is SubAgentOutcome.Failure->it.failure.completeError.length}};require(chars<=limits.maxCombinedAnswerCharacters){"子 Agent 汇总结果字符数 $chars 超过上限 ${limits.maxCombinedAnswerCharacters}"};return outcomes
 }
 private suspend fun execute(parent:AiGenerationRequest,task:SubAgentTask,depth:Int):SubAgentOutcome{
  val requestId=requestIdFactory();val messages=buildList{addAll(parent.messages.filter{it.role=="system"});add(AiPromptMessage("system",policy(depth)));add(AiPromptMessage("user",prompt(task)))}
  return try{val run=loopFactory.create().run(parent.copy(requestId=requestId,messages=messages,tools=ReadonlyAgentToolSchemas.childInvestigation));SubAgentOutcome.Success(SubAgentResult(task.id,requestId,run.completeAnswer,run.rounds,run.usage,run.model))}catch(c:CancellationException){throw c}catch(e:Throwable){SubAgentOutcome.Failure(SubAgentFailure(task.id,e.stackTraceToString()))}
 }
 private fun policy(depth:Int)="""你是只读证据调查子 Agent，当前深度为 $depth。
只完成分配给你的单一任务，不扩展用户目标，不执行写入、发布、删除或凭据操作。
结论必须来自工具实际返回的证据；没有证据时明确说明未知，不得猜测。
返回紧凑报告：结论、证据、未解决问题。不要向用户提问。"""
 private fun prompt(task:SubAgentTask)="""[sub_agent_task]
taskId=${task.id}
instruction=${task.instruction}

[evidence_requirements]
${task.evidenceRequirements}"""
}
