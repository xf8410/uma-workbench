package com.uma.workbench.agent

/**
 * Wraps ReadonlyAgentToolExecutor with approval-gate checks.
 * 集成 [AgentMode] 权限矩阵：模式不允许的工具直接拒绝；
 * 模式允许但需要审批的工具走 [ToolApprovalGate]；
 * 模式允许且免审批的工具直接放行。
 */
class ApprovableToolExecutor(
    private val delegate: ReadonlyAgentToolExecutor,
    private val gate: ToolApprovalGate,
    private val registry: ToolCapabilityRegistry = ToolCapabilityRegistry.default(),
    private val mode: () -> AgentMode = { AgentMode.ASK }
) : AgentToolExecutor {

    override suspend fun execute(call: AiToolCall): AgentToolOutcome {
        val denial = checkApproval(call)
        if (denial != null) return denial
        return delegate.execute(call)
    }

    override suspend fun executeSpecial(
        call: AiToolCall,
        operation: suspend () -> AgentSpecialToolPayload
    ): AgentToolOutcome {
        // delegate_subagents 走 special 路径，权限由 specialToolHandler 决定，
        // 但仍受模式约束（不允许远程写的模式也不应委派出远程写）
        val denial = checkApproval(call)
        if (denial != null) return denial
        return delegate.executeSpecial(call, operation)
    }

    private suspend fun checkApproval(call: AiToolCall): AgentToolOutcome? {
        val risk = registry.riskLevel(call.name)
        val current = mode()
        // 1) 模式不允许该风险等级 → 直接拒绝
        if (!current.canExecute(risk)) {
            return AgentToolOutcome.Failure(
                AgentToolFailure(
                    callId = call.id,
                    toolName = call.name,
                    completeError = "当前模式「${current.label}」不允许执行风险等级 $risk 的工具（${call.name}）。请切换到「执行」模式。",
                    elapsedMillis = 0
                )
            )
        }
        // 2) 模式允许且不需要审批 → 放行
        if (!current.needsApprovalFor(risk)) return null
        // 3) 模式允许但需要审批 → 走审批门
        val request = ToolApprovalRequest(
            callId = call.id,
            toolName = call.name,
            argumentsJson = call.completeArgumentsJson,
            riskLevel = risk,
            reason = "工具 ${call.name} 风险等级为 $risk，模式「${current.label}」要求用户确认"
        )
        val decision = gate.requestApproval(request)
        return if (decision.approved) {
            null
        } else {
            AgentToolOutcome.Failure(
                AgentToolFailure(
                    callId = call.id,
                    toolName = call.name,
                    completeError = "工具执行被拒绝：${decision.reason ?: "未批准"}",
                    elapsedMillis = 0
                )
            )
        }
    }
}
