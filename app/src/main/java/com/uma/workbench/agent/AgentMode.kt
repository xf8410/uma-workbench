package com.uma.workbench.agent

/**
 * Agent conversation modes (feature catalog 251-260).
 * Each mode defines what the agent is allowed to do,
 * integrating with ToolRiskLevel to enforce permission boundaries.
 *
 * Default mode is ASK (read-only) per feature 257.
 */
enum class AgentMode(
    val storageKey: String,
    val label: String,
    val description: String,
    val canRead: Boolean,
    val canWriteLocally: Boolean,
    val canWriteRemotely: Boolean,
    val requiresApprovalForWrite: Boolean
) {
    ASK(
        storageKey = "ASK",
        label = "询问",
        description = "只读模式：回答问题、查找信息、不执行任何写操作",
        canRead = true,
        canWriteLocally = false,
        canWriteRemotely = false,
        requiresApprovalForWrite = false
    ),
    INVESTIGATE(
        storageKey = "INVESTIGATE",
        label = "调查",
        description = "深度调查模式：只读但允许执行审计工具和证据收集",
        canRead = true,
        canWriteLocally = false,
        canWriteRemotely = false,
        requiresApprovalForWrite = false
    ),
    ACT(
        storageKey = "ACT",
        label = "执行",
        description = "执行模式：允许本地和远程写入，但需要逐次审批",
        canRead = true,
        canWriteLocally = true,
        canWriteRemotely = true,
        requiresApprovalForWrite = true
    ),
    OBSERVE(
        storageKey = "OBSERVE",
        label = "观察",
        description = "观察模式：只读取状态和快照，不触发任何操作",
        canRead = true,
        canWriteLocally = false,
        canWriteRemotely = false,
        requiresApprovalForWrite = false
    );

    /**
     * Whether this mode allows executing a tool with the given risk level.
     * DESTRUCTIVE tools are never allowed in any mode.
     */
    fun canExecute(risk: ToolRiskLevel): Boolean = when (risk) {
        ToolRiskLevel.READ_ONLY -> canRead
        ToolRiskLevel.LOCAL_WRITE -> canWriteLocally
        ToolRiskLevel.REMOTE_WRITE -> canWriteRemotely
        ToolRiskLevel.DESTRUCTIVE -> false
    }

    /**
     * Whether a tool with the given risk level requires user approval
     * when running in this mode.
     */
    fun needsApprovalFor(risk: ToolRiskLevel): Boolean {
        if (!canExecute(risk)) return false
        return when (risk) {
            ToolRiskLevel.READ_ONLY -> false
            ToolRiskLevel.LOCAL_WRITE -> requiresApprovalForWrite
            ToolRiskLevel.REMOTE_WRITE -> requiresApprovalForWrite
            ToolRiskLevel.DESTRUCTIVE -> true
        }
    }

    /**
     * Generates a system-prompt fragment describing the current mode's capabilities.
     * Injected into the agent's system message so it knows what it can and cannot do,
     * avoiding wasted rounds on tools that will be rejected (feature 259).
     */
    fun systemPromptFragment(): String = buildString {
        appendLine("[agent_mode]")
        appendLine("current_mode=$storageKey ($label)")
        appendLine("description=$description")
        appendLine("capabilities:")
        appendLine("  read_only_tools: ${if (canRead) "allowed" else "denied"}")
        appendLine("  local_write_tools: ${if (canWriteLocally) "allowed" else "denied"}")
        appendLine("  remote_write_tools: ${if (canWriteRemotely) "allowed" else "denied"}")
        appendLine("  destructive_tools: always_denied")
        if (canWriteLocally || canWriteRemotely) {
            if (requiresApprovalForWrite) {
                appendLine("  approval: write_operations_require_user_approval")
            } else {
                appendLine("  approval: auto")
            }
        }
        appendLine("instruction: Do not attempt operations your current mode denies; they will be rejected. If the user needs a denied capability, suggest switching to a more permissive mode.")
        appendLine("[/agent_mode]")
    }

    companion object {
        fun fromStorageKey(key: String?): AgentMode =
            entries.firstOrNull { it.storageKey == key } ?: ASK

        /**
         * Returns the set of modes that allow the given risk level.
         */
        fun modesAllowing(risk: ToolRiskLevel): Set<AgentMode> =
            entries.filter { it.canExecute(risk) }.toSet()
    }
}

/**
 * Validates mode transitions (feature 256: 模式切换确认).
 * Not all transitions are safe; some require explicit user confirmation.
 */
data class ModeTransition(
    val from: AgentMode,
    val to: AgentMode
) {
    val isElevation: Boolean get() = from.ordinal < to.ordinal
    val involvesWriteAccess: Boolean get() = !from.canWriteLocally && to.canWriteLocally
    val involvesRemoteAccess: Boolean get() = !from.canWriteRemotely && to.canWriteRemotely
    val requiresConfirmation: Boolean get() = involvesWriteAccess || involvesRemoteAccess

    fun warningMessage(): String? = when {
        involvesRemoteAccess && involvesWriteAccess ->
            "即将切换到「${to.label}」模式，将获得本地写入和远程操作权限，请确认。"
        involvesRemoteAccess ->
            "即将切换到「${to.label}」模式，将获得远程操作权限，请确认。"
        involvesWriteAccess ->
            "即将切换到「${to.label}」模式，将获得本地写入权限，请确认。"
        else -> null
    }
}
