package com.uma.workbench.agent

/**
 * 组装后的上下文，包含实际发送内容和因超限被截断的附件。
 */
data class AssembledContext(
    val systemPrompt: String?,
    val messages: List<AiPromptMessage>,
    val attachmentsSent: List<WorkspaceContextAttachment>,
    val attachmentsTruncated: List<WorkspaceContextAttachment>,
    val budget: ContextBudgetSnapshot
)

/**
 * 预算化上下文组装器。
 *
 * 在发送前计算上下文预算，明确告知用户各部分占用情况。
 * 当模型上下文窗口未知时，仍然提供各部分 token 估算，
 * 但剩余空间显示为"未知"。
 *
 * @property reservedOutputTokens 预留给模型输出的 token 数，默认 4096。
 * @property safetyMargin 安全余量 token 数，默认 200。
 */
class ContextBudgetAssembler(
    private val reservedOutputTokens: Int = 4096,
    private val safetyMargin: Int = 200
) {

    /**
     * 计算上下文预算快照，不执行截断。
     */
    fun assemble(
        modelContextWindow: Int?,
        systemPrompt: String?,
        history: List<AiPromptMessage>,
        currentQuestion: String,
        attachments: List<WorkspaceContextAttachment>,
        toolSchemaTokens: Int = 0
    ): ContextBudgetSnapshot {
        val systemPromptTokens = if (systemPrompt.isNullOrBlank()) {
            0
        } else {
            TokenEstimator.estimate(systemPrompt)
        }
        val historyTokens = estimateHistory(history)
        val currentQuestionTokens = TokenEstimator.estimate(currentQuestion)
        val attachmentTokens = estimateAttachments(attachments)

        val totalUsed = systemPromptTokens +
            toolSchemaTokens +
            safetyMargin +
            historyTokens +
            currentQuestionTokens +
            attachmentTokens

        val contextWindowKnown = modelContextWindow != null
        val remaining = if (contextWindowKnown) {
            modelContextWindow!! - reservedOutputTokens - totalUsed
        } else {
            null
        }

        return ContextBudgetSnapshot(
            modelContextWindow = modelContextWindow,
            reservedOutput = reservedOutputTokens,
            systemPromptTokens = systemPromptTokens,
            toolSchemaTokens = toolSchemaTokens,
            safetyMargin = safetyMargin,
            historyTokens = historyTokens,
            currentQuestionTokens = currentQuestionTokens,
            attachmentTokens = attachmentTokens,
            toolResultTokens = 0,
            totalUsed = totalUsed,
            remaining = remaining,
            contextWindowKnown = contextWindowKnown
        )
    }

    /**
     * 组装实际发送内容，按优先级截断超限部分。
     *
     * 当模型上下文窗口未知时，不做截断，全部发送。
     * 当窗口已知但超限时，按以下顺序截断：
     * 1. 附件（从最大的开始移除）
     * 2. 旧历史消息（从最早的开始移除）
     */
    fun assembleForSend(
        modelContextWindow: Int?,
        systemPrompt: String?,
        history: List<AiPromptMessage>,
        currentQuestion: String,
        attachments: List<WorkspaceContextAttachment>,
        toolSchemaTokens: Int = 0
    ): AssembledContext {
        // 如果窗口未知，全部发送，不截断
        if (modelContextWindow == null) {
            val budget = assemble(
                modelContextWindow = null,
                systemPrompt = systemPrompt,
                history = history,
                currentQuestion = currentQuestion,
                attachments = attachments,
                toolSchemaTokens = toolSchemaTokens
            )
            return AssembledContext(
                systemPrompt = systemPrompt,
                messages = history + AiPromptMessage(
                    role = "user",
                    completeContent = WorkspaceContextPromptComposer.compose(
                        currentQuestion,
                        attachments
                    )
                ),
                attachmentsSent = attachments,
                attachmentsTruncated = emptyList(),
                budget = budget
            )
        }

        val availableBudget = modelContextWindow - reservedOutputTokens
        val fixedCosts = (if (systemPrompt.isNullOrBlank()) {
            0
        } else {
            TokenEstimator.estimate(systemPrompt)
        }) + toolSchemaTokens + safetyMargin + TokenEstimator.estimate(currentQuestion)

        // 先尝试全部附件
        var currentAttachments = attachments.toMutableList()
        var currentHistory = history.toMutableList()

        // 计算当前总用量
        fun currentTotal(): Int {
            val attTokens = currentAttachments.sumOf {
                TokenEstimator.estimate(it.content)
            }
            val histTokens = currentHistory.sumOf {
                TokenEstimator.estimate(it.completeContent)
            }
            return fixedCosts + attTokens + histTokens
        }

        // 第一步：如果超限，逐步移除附件（从最大的开始）
        while (currentTotal() > availableBudget && currentAttachments.isNotEmpty()) {
            val largestIndex = currentAttachments.indices.maxByOrNull {
                currentAttachments[it].content.length
            } ?: break
            currentAttachments.removeAt(largestIndex)
        }

        // 第二步：如果仍然超限，逐步移除旧历史（从最早的开始）
        while (currentTotal() > availableBudget && currentHistory.isNotEmpty()) {
            currentHistory.removeAt(0)
        }

        val sentAttachments = currentAttachments.toList()
        val truncatedAttachments = attachments.filterNot { it in sentAttachments }

        val budget = assemble(
            modelContextWindow = modelContextWindow,
            systemPrompt = systemPrompt,
            history = currentHistory.toList(),
            currentQuestion = currentQuestion,
            attachments = sentAttachments,
            toolSchemaTokens = toolSchemaTokens
        )

        val composedMessage = AiPromptMessage(
            role = "user",
            completeContent = WorkspaceContextPromptComposer.compose(
                currentQuestion,
                sentAttachments
            )
        )

        return AssembledContext(
            systemPrompt = systemPrompt,
            messages = currentHistory.toList() + composedMessage,
            attachmentsSent = sentAttachments,
            attachmentsTruncated = truncatedAttachments,
            budget = budget
        )
    }

    /**
     * 估算附件总 token 数。
     */
    fun estimateAttachments(
        attachments: List<WorkspaceContextAttachment>
    ): Int {
        return attachments.sumOf {
            TokenEstimator.estimate(it.content)
        }
    }

    /**
     * 估算对话历史总 token 数。
     */
    fun estimateHistory(
        history: List<AiPromptMessage>
    ): Int {
        return history.sumOf {
            TokenEstimator.estimate(it.completeContent)
        }
    }
}
