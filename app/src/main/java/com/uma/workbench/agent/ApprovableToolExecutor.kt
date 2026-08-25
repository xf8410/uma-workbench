package com.uma.workbench.agent

/**
 * Wraps ReadonlyAgentToolExecutor with approval-gate checks.
 * High-risk tools (LOCAL_WRITE and above) are intercepted before execution;
 * if the gate denies, a Failure outcome is returned without calling the delegate.
 */
class ApprovableToolExecutor(
    private val delegate: ReadonlyAgentToolExecutor,
    private val gate: ToolApprovalGate,
    private val registry: ToolCapabilityRegistry = ToolCapabilityRegistry.default()
) {
    suspend fun execute(call: AiToolCall): AgentToolOutcome {
        val denial = checkApproval(call)
        if (denial != null) return denial
        return delegate.execute(call)
    }

    private suspend fun checkApproval(call: AiToolCall): AgentToolOutcome? {
        if (!registry.requiresApproval(call.name)) return null
        val risk = registry.riskLevel(call.name)
        val request = ToolApprovalRequest(
            callId = call.id,
            toolName = call.name,
            argumentsJson = call.completeArgumentsJson,
            riskLevel = risk,
            reason = "工具 ${call.name} 风险等级为 $risk，需要用户确认"
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
