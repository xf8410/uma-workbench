package com.uma.workbench.agent

/**
 * Request for tool execution approval.
 */
data class ToolApprovalRequest(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val riskLevel: ToolRiskLevel,
    val reason: String
)

/**
 * Decision on a tool approval request.
 */
data class ToolApprovalDecision(
    val callId: String,
    val approved: Boolean,
    val reason: String? = null
)

/**
 * Gate that controls whether high-risk tools can execute.
 * Implementations may show UI, wait for user input, or auto-decide based on policy.
 */
interface ToolApprovalGate {
    suspend fun requestApproval(request: ToolApprovalRequest): ToolApprovalDecision
}

/**
 * In-memory approval gate with configurable auto-approval for read-only tools.
 * High-risk tools (LOCAL_WRITE and above) are denied by default until a user confirms.
 */
class InMemoryToolApprovalGate(
    private val autoApproveReadOnly: Boolean = true,
    private val autoApproveLocalWrite: Boolean = false,
    private val autoApproveRemoteWrite: Boolean = false,
    private val autoApproveDestructive: Boolean = false
) : ToolApprovalGate {

    private val decisions = linkedMapOf<String, ToolApprovalDecision>()

    override suspend fun requestApproval(request: ToolApprovalRequest): ToolApprovalDecision {
        val autoApprove = when (request.riskLevel) {
            ToolRiskLevel.READ_ONLY -> autoApproveReadOnly
            ToolRiskLevel.LOCAL_WRITE -> autoApproveLocalWrite
            ToolRiskLevel.REMOTE_WRITE -> autoApproveRemoteWrite
            ToolRiskLevel.DESTRUCTIVE -> autoApproveDestructive
        }
        val decision = if (autoApprove) {
            ToolApprovalDecision(
                callId = request.callId,
                approved = true,
                reason = "自动批准（${request.riskLevel}）"
            )
        } else {
            ToolApprovalDecision(
                callId = request.callId,
                approved = false,
                reason = "等待用户确认（${request.riskLevel}）"
            )
        }
        decisions[request.callId] = decision
        return decision
    }

    fun getDecision(callId: String): ToolApprovalDecision? = decisions[callId]

    fun clear() {
        decisions.clear()
    }
}
