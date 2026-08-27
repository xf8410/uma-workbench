package com.uma.workbench.agent

/**
 * Agent 工具执行器抽象。
 * 主代码通过 [ApprovableToolExecutor] 包装 [ReadonlyAgentToolExecutor]，
 * 注入 [ToolApprovalGate] 与 [AgentMode] 实现风险分级 + 模式权限 + 审批。
 */
interface AgentToolExecutor {
    suspend fun execute(call: AiToolCall): AgentToolOutcome
    suspend fun executeSpecial(
        call: AiToolCall,
        operation: suspend () -> AgentSpecialToolPayload
    ): AgentToolOutcome
}
