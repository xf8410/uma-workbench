package com.uma.workbench.agent

/**
 * Token 估算器。
 *
 * 粗略策略：中英文混合场景下约 1 token / 字符。
 * 不伪造精确百分比——上限未知时显示"未知"。
 */
object TokenEstimator {
    fun estimate(text: String): Int {
        return text.length
    }
}

/**
 * 一次上下文预算快照。
 *
 * @property modelContextWindow 模型上下文窗口大小，未知时为 null。
 * @property reservedOutput 预留给模型输出的 token 数。
 * @property systemPromptTokens 系统指令占用 token 数。
 * @property toolSchemaTokens Tool Schema 占用 token 数。
 * @property safetyMargin 安全余量。
 * @property historyTokens 对话历史占用 token 数。
 * @property currentQuestionTokens 当前问题占用 token 数。
 * @property attachmentTokens 附件占用 token 数。
 * @property toolResultTokens 工具结果占用 token 数。
 * @property totalUsed 已用总量。
 * @property remaining 剩余 token 数，窗口未知时为 null。
 * @property contextWindowKnown 上下文窗口是否已知。
 */
data class ContextBudgetSnapshot(
    val modelContextWindow: Int?,
    val reservedOutput: Int,
    val systemPromptTokens: Int,
    val toolSchemaTokens: Int,
    val safetyMargin: Int,
    val historyTokens: Int,
    val currentQuestionTokens: Int,
    val attachmentTokens: Int,
    val toolResultTokens: Int,
    val totalUsed: Int,
    val remaining: Int?,
    val contextWindowKnown: Boolean
) {
    val displayRemaining: String
        get() = if (contextWindowKnown) "${remaining} tokens" else "未知"

    val isNearLimit: Boolean
        get() = contextWindowKnown && (remaining ?: Int.MAX_VALUE) < 500

    val isOverLimit: Boolean
        get() = contextWindowKnown && (remaining ?: 0) < 0
}

/**
 * 预算优先级（超限时的截断顺序）。
 * 从最高优先级到最低优先级排列。
 */
enum class BudgetPriority {
    CURRENT_USER_QUESTION,
    CURRENT_SELECTION,
    RELEVANT_SEARCH,
    ATTACHMENTS,
    OLD_HISTORY,
    TOOL_RESULTS
}
