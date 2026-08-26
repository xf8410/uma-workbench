package com.uma.workbench.agent

import java.io.File

/**
 * 真正的群聊 Agent 回复执行器。
 * 绑定当前 AI 配置 + 工作区只读数据源，为每个伙伴构造独立 Prompt，
 * 通过 ReadonlyAgentLoop 执行，禁止写入/发布/删除/凭据工具。
 */
class AgentGroupReplyRunnerImpl(
    private val provider: AiStreamingProvider,
    private val source: ReadonlyAgentToolDataSource,
    private val filesDir: File,
    private val approvalGate: ToolApprovalGate? = null,
    private val modeProvider: () -> AgentMode = { AgentMode.ASK }
) : AgentGroupReplyRunner {

    override suspend fun run(
        agent: AgentProfileEntity,
        prompt: String
    ): AgentGroupReplyRunnerResult {
        val requestId = java.util.UUID.randomUUID().toString()
        val resultStore = FileAgentToolResultStore(
            File(filesDir, "agent-tool-results/group-replies/${agent.id}"),
            "group-reply-${agent.id}",
            requestId
        )
        val loop = ReadonlyAgentRuntimeFactory(
            provider = provider,
            source = source,
            resultStore = resultStore,
            approvalGate = approvalGate,
            modeProvider = modeProvider
        ).createRootLoop()

        val userQuestion = extractUserQuestion(prompt)
        val systemPrompt = prompt.substringBefore("[user_message]").trimEnd()
        val modeContext = modeProvider().systemPromptFragment()
        val messages = listOf(
            AiPromptMessage("system", "$systemPrompt\n\n$modeContext"),
            AiPromptMessage("user", userQuestion)
        )
        val request = AiGenerationRequest(
            requestId = requestId,
            messages = messages,
            model = null,
            tools = ReadonlyAgentToolSchemas.openAiCompatible
        )
        val result = loop.run(request)
        val usageJson = buildString {
            append("{\"input\":${result.usage.inputTokens}")
            append(",\"output\":${result.usage.outputTokens}")
            append(",\"total\":${result.usage.totalTokens}")
            append(",\"estimated\":${result.usage.estimated}}")
        }
        val toolCallsJson = buildToolCallsJson(result.rounds)
        return AgentGroupReplyRunnerResult(
            content = result.completeAnswer,
            requestId = result.requestId,
            model = result.model,
            roundsCount = result.rounds.size,
            usageJson = usageJson,
            toolCallsJson = toolCallsJson
        )
    }

    private fun buildToolCallsJson(rounds: List<ReadonlyAgentRound>): String? {
        val entries = mutableListOf<String>()
        for (round in rounds) {
            val pairs = round.toolCalls.zip(round.toolOutcomes)
            for ((call, outcome) in pairs) {
                val status = when (outcome) {
                    is AgentToolOutcome.Success -> "ok"
                    is AgentToolOutcome.Failure -> "fail"
                }
                val argsRaw = call.completeArgumentsJson
                val argsTruncated = if (argsRaw.length > 300) argsRaw.substring(0, 300) + "..." else argsRaw
                val escapedArgs = argsTruncated.replace("\\", "\\\\").replace("\"", "\\\"")
                val errorPart = if (outcome is AgentToolOutcome.Failure) {
                    val errMsg = outcome.failure.completeError.let {
                        if (it.length > 200) it.substring(0, 200) + "..." else it
                    }.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    ",\"error\":\"$errMsg\""
                } else ""
                entries.add("{\"tool\":\"${call.name}\",\"status\":\"$status\",\"args\":\"$escapedArgs\"$errorPart}")
            }
        }
        return if (entries.isEmpty()) null else "[${entries.joinToString(",")}]"
    }

    private fun extractUserQuestion(prompt: String): String {
        val marker = "[user_message]"
        val idx = prompt.indexOf(marker)
        return if (idx >= 0) prompt.substring(idx + marker.length).trim()
        else prompt.takeLast(500)
    }
}
