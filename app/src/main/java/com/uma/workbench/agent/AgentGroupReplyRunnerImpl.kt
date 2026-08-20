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
    private val filesDir: File
) : AgentGroupReplyRunner {

    override suspend fun run(
        agent: AgentProfileEntity,
        prompt: String
    ): String {
        val requestId = java.util.UUID.randomUUID().toString()
        val resultStore = FileAgentToolResultStore(
            File(filesDir, "agent-tool-results/group-replies/${agent.id}"),
            "group-reply-${agent.id}",
            requestId
        )
        val loop = ReadonlyAgentRuntimeFactory(
            provider = provider,
            source = source,
            resultStore = resultStore
        ).createRootLoop()

        val userQuestion = extractUserQuestion(prompt)
        val systemPrompt = prompt.substringBefore("[user_message]").trimEnd()
        val messages = listOf(
            AiPromptMessage("system", systemPrompt),
            AiPromptMessage("user", userQuestion)
        )
        val request = AiGenerationRequest(
            requestId = requestId,
            messages = messages,
            model = null,
            tools = ReadonlyAgentToolSchemas.openAiCompatible
        )
        val result = loop.run(request)
        return result.completeAnswer
    }

    private fun extractUserQuestion(prompt: String): String {
        val marker = "[user_message]"
        val idx = prompt.indexOf(marker)
        return if (idx >= 0) prompt.substring(idx + marker.length).trim()
        else prompt.takeLast(500)
    }
}
