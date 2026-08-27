package com.uma.workbench.agent

/**
 * Verification result for an agent run's output.
 * Checks whether the final answer is backed by tool evidence (feature: 模型输出必须由确定性工具或证据引用验证).
 */
data class OutputVerification(
    val status: VerificationStatus,
    val totalToolCalls: Int,
    val successfulToolCalls: Int,
    val failedToolCalls: Int,
    val evidenceSnippetsReferenced: Int,
    val toolNamesUsed: Set<String>,
    val warnings: List<String>
) {
    enum class VerificationStatus {
        /** Agent called tools and the answer references tool result content. */
        VERIFIED,
        /** Agent called tools but the answer doesn't clearly reference tool results. */
        PARTIAL,
        /** Agent produced an answer without calling any tools. */
        UNVERIFIED,
        /** Agent made no tool calls and the answer is very short (simple greeting etc). */
        TRIVIAL
    }

    fun summary(): String = when (status) {
        VerificationStatus.VERIFIED ->
            "输出已验证：$successfulToolCalls 次工具调用，$evidenceSnippetsReferenced 处证据引用"
        VerificationStatus.PARTIAL ->
            "部分验证：$successfulToolCalls 次工具调用但答案未充分引用工具结果"
        VerificationStatus.UNVERIFIED ->
            "未验证：Agent 未调用工具即产出答案，结论可能缺乏证据支撑"
        VerificationStatus.TRIVIAL ->
            "简短回复：无工具调用，内容较短"
    }
}

/**
 * Verifies that model output is backed by deterministic tool evidence.
 *
 * Strategy:
 * 1. Collect all successful tool result content from the run.
 * 2. Extract significant snippets (10+ char sequences) from tool results.
 * 3. Check how many snippets appear in the final answer.
 * 4. Flag answers that make claims without tool backing.
 *
 * This does NOT use another model call — it's pure deterministic string matching.
 */
object ModelOutputVerifier {

    private const val SNIPPET_MIN_LENGTH = 10
    private const val SNIPPET_MAX_COUNT = 200
    private const val TRIVIAL_ANSWER_THRESHOLD = 80
    private const val SNIPPET_CHUNK_SIZE = 60

    /**
     * Verify an agent run result.
     */
    fun verify(result: ReadonlyAgentRunResult): OutputVerification {
        val allToolCalls = result.rounds.flatMap { it.toolCalls }
        val allOutcomes = result.rounds.flatMap { it.toolOutcomes }
        val successfulOutcomes = allOutcomes.filterIsInstance<AgentToolOutcome.Success>()
        val failedOutcomes = allOutcomes.filterIsInstance<AgentToolOutcome.Failure>()

        val totalToolCalls = allToolCalls.size
        val successfulToolCalls = successfulOutcomes.size
        val failedToolCalls = failedOutcomes.size
        val toolNamesUsed = allToolCalls.map { it.name }.toSet()

        val answer = result.completeAnswer

        // Trivial case: no tool calls and short answer
        if (totalToolCalls == 0) {
            val status = if (answer.length <= TRIVIAL_ANSWER_THRESHOLD) {
                OutputVerification.VerificationStatus.TRIVIAL
            } else {
                OutputVerification.VerificationStatus.UNVERIFIED
            }
            val warnings = mutableListOf<String>()
            if (status == OutputVerification.VerificationStatus.UNVERIFIED) {
                warnings.add("答案超过 $TRIVIAL_ANSWER_THRESHOLD 字符但未调用任何工具验证")
            }
            return OutputVerification(
                status = status,
                totalToolCalls = 0,
                successfulToolCalls = 0,
                failedToolCalls = 0,
                evidenceSnippetsReferenced = 0,
                toolNamesUsed = emptySet(),
                warnings = warnings
            )
        }

        // Collect evidence snippets from successful tool results
        val evidenceSnippets = collectSnippets(successfulOutcomes)

        // Count how many snippets appear in the final answer
        val referencedCount = countReferencedSnippets(answer, evidenceSnippets)

        // Determine verification status
        val warnings = mutableListOf<String>()

        val status = when {
            referencedCount >= 1 -> {
                OutputVerification.VerificationStatus.VERIFIED
            }
            successfulToolCalls > 0 -> {
                warnings.add("调用了 $successfulToolCalls 次工具但答案未引用工具结果内容")
                OutputVerification.VerificationStatus.PARTIAL
            }
            else -> {
                warnings.add("所有工具调用均失败")
                OutputVerification.VerificationStatus.UNVERIFIED
            }
        }

        // Additional warnings
        if (failedToolCalls > 0 && successfulToolCalls == 0) {
            warnings.add("全部 $totalToolCalls 次工具调用失败，答案可能基于推测")
        }
        if (answer.length > 500 && referencedCount == 0) {
            warnings.add("答案较长（${answer.length} 字符）但无工具结果引用")
        }

        return OutputVerification(
            status = status,
            totalToolCalls = totalToolCalls,
            successfulToolCalls = successfulToolCalls,
            failedToolCalls = failedToolCalls,
            evidenceSnippetsReferenced = referencedCount,
            toolNamesUsed = toolNamesUsed,
            warnings = warnings
        )
    }

    /**
     * Extract significant text snippets from successful tool results.
     * Takes chunks of content to check for overlap with the answer.
     */
    private fun collectSnippets(
        successfulOutcomes: List<AgentToolOutcome.Success>
    ): List<String> {
        val snippets = mutableListOf<String>()
        for (outcome in successfulOutcomes) {
            val content = outcome.result.content
            if (content.isBlank()) continue
            // Extract chunks of SNIPPET_CHUNK_SIZE characters
            var offset = 0
            while (offset < content.length && snippets.size < SNIPPET_MAX_COUNT) {
                val end = minOf(offset + SNIPPET_CHUNK_SIZE, content.length)
                val chunk = content.substring(offset, end).trim()
                if (chunk.length >= SNIPPET_MIN_LENGTH) {
                    snippets.add(chunk)
                }
                offset += SNIPPET_CHUNK_SIZE
            }
        }
        return snippets
    }

    /**
     * Count how many evidence snippets appear in the answer text.
     * Uses case-insensitive containment check.
     */
    private fun countReferencedSnippets(
        answer: String,
        snippets: List<String>
    ): Int {
        if (snippets.isEmpty()) return 0
        val answerLower = answer.lowercase()
        return snippets.count { snippet ->
            val snippetLower = snippet.lowercase()
            snippetLower.length >= SNIPPET_MIN_LENGTH && answerLower.contains(snippetLower)
        }
    }
}
