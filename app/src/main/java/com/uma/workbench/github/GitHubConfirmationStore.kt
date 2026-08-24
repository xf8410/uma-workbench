package com.uma.workbench.github

/** 贡献流远程操作类型（令牌作用域用）。 */
enum class GitHubRemoteOperation { FORK, BRANCH, WRITE, PULL_REQUEST }

/**
 * GitHub 远程操作一次性授权令牌。
 *
 * 令牌 = 作用域（允许的操作类型集合）+ 有效期 + 剩余次数。
 * Agent 工具调用携带 confirmationId 时由本库验证并消耗：
 * 不存在 / 过期 / 次数耗尽 / 操作不在作用域内，一律拒绝。
 * PULL_REQUEST 消耗成功视为贡献流终态，整张令牌立即作废。
 */
class GitHubConfirmationStore(
    private val timeSource: () -> Long = System::currentTimeMillis,
    private val defaultTtlMillis: Long = DEFAULT_TTL_MILLIS
) {
    enum class ConsumeResult { OK, INVALID, EXPIRED, EXHAUSTED, WRONG_OPERATION }

    class ConfirmationToken internal constructor(
        val id: String,
        val operations: Set<GitHubRemoteOperation>,
        val description: String,
        val issuedAt: Long,
        val expiresAt: Long,
        internal var remainingUses: Int
    ) {
        val remainingUsesPublic: Int get() = remainingUses
    }

    private val tokens = LinkedHashMap<String, ConfirmationToken>()

    /** 发放一张作用域令牌，返回令牌字符串（即 Agent 使用的 confirmationId）。 */
    @Synchronized
    fun issue(
        operations: Set<GitHubRemoteOperation>,
        description: String,
        ttlMillis: Long = defaultTtlMillis
    ): String {
        require(operations.isNotEmpty()) { "令牌必须至少允许一种操作" }
        require(ttlMillis > 0) { "有效期必须为正" }
        sweepExpiredLocked()
        val id = buildString {
            repeat(TOKEN_LENGTH) { append(ALPHABET.random()) }
        }
        // 单步令牌 1 次；多操作令牌按操作类型数+1 给余量（贡献流 fork/branch/write*/pr，
        // write 可多次，故按类型数 + 额外 write 余量计算）
        val uses = if (operations.size == 1) 1 else operations.size + WRITE_ALLOWANCE
        tokens[id] = ConfirmationToken(
            id = id,
            operations = operations,
            description = description,
            issuedAt = timeSource(),
            expiresAt = timeSource() + ttlMillis,
            remainingUses = uses
        )
        return id
    }

    /** 验证并消耗一次授权。返回 OK 才放行。 */
    @Synchronized
    fun consume(confirmationId: String, operation: GitHubRemoteOperation): ConsumeResult {
        val token = tokens[confirmationId] ?: return ConsumeResult.INVALID
        val now = timeSource()
        if (now >= token.expiresAt) {
            tokens.remove(confirmationId)
            return ConsumeResult.EXPIRED
        }
        if (operation !in token.operations) return ConsumeResult.WRONG_OPERATION
        if (token.remainingUses <= 0) {
            tokens.remove(confirmationId)
            return ConsumeResult.EXHAUSTED
        }
        token.remainingUses -= 1
        if (operation == GitHubRemoteOperation.PULL_REQUEST) {
            // PR 是贡献流终态：发出即作废整张令牌
            tokens.remove(confirmationId)
        } else if (token.remainingUses == 0) {
            tokens.remove(confirmationId)
        }
        return ConsumeResult.OK
    }

    /** 主动撤销（用户撤回授权）。 */
    @Synchronized
    fun revoke(confirmationId: String): Boolean = tokens.remove(confirmationId) != null

    /** 当前有效令牌（过期自动剔除）。 */
    @Synchronized
    fun listActive(): List<ConfirmationToken> {
        sweepExpiredLocked()
        return tokens.values.toList()
    }

    @Synchronized
    fun sweepExpired() {
        sweepExpiredLocked()
    }

    private fun sweepExpiredLocked() {
        val now = timeSource()
        tokens.entries.removeIf { now >= it.value.expiresAt }
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 10 * 60 * 1000
        private const val TOKEN_LENGTH = 24
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val WRITE_ALLOWANCE = 8

        /** 完整贡献流（fork → 分支 → 多次提交 → PR）的作用域集合。 */
        val CONTRIBUTION_FLOW: Set<GitHubRemoteOperation> = setOf(
            GitHubRemoteOperation.FORK,
            GitHubRemoteOperation.BRANCH,
            GitHubRemoteOperation.WRITE,
            GitHubRemoteOperation.PULL_REQUEST
        )
    }
}
