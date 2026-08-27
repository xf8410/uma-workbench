package com.uma.workbench.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubConfirmationStoreTest {

    private class FakeClock(var now: Long = 0L)

    private fun store(clock: FakeClock) = GitHubConfirmationStore(timeSource = { clock.now })

    @Test fun issueAndConsumeSingleOperationToken() {
        val clock = FakeClock()
        val s = store(clock)
        val id = s.issue(setOf(GitHubRemoteOperation.FORK), "单步 fork 授权")
        assertEquals(GitHubConfirmationStore.ConsumeResult.OK, s.consume(id, GitHubRemoteOperation.FORK))
        // 单步令牌用一次即焚
        assertEquals(GitHubConfirmationStore.ConsumeResult.INVALID, s.consume(id, GitHubRemoteOperation.FORK))
    }

    @Test fun wrongOperationIsRejectedWithoutConsuming() {
        val clock = FakeClock()
        val s = store(clock)
        val id = s.issue(setOf(GitHubRemoteOperation.FORK), "只授权 fork")
        assertEquals(GitHubConfirmationStore.ConsumeResult.WRONG_OPERATION, s.consume(id, GitHubRemoteOperation.WRITE))
        // 拒绝不消耗次数，原操作仍可用
        assertEquals(GitHubConfirmationStore.ConsumeResult.OK, s.consume(id, GitHubRemoteOperation.FORK))
    }

    @Test fun expiredTokenIsRejectedAndSwept() {
        val clock = FakeClock()
        val s = store(clock)
        val id = s.issue(setOf(GitHubRemoteOperation.BRANCH), "过期测试", ttlMillis = 1000)
        clock.now = 1000 // 到期边界
        assertEquals(GitHubConfirmationStore.ConsumeResult.EXPIRED, s.consume(id, GitHubRemoteOperation.BRANCH))
        assertEquals(GitHubConfirmationStore.ConsumeResult.INVALID, s.consume(id, GitHubRemoteOperation.BRANCH))
    }

    @Test fun fullContributionFlowRunsOnOneToken() {
        val clock = FakeClock()
        val s = store(clock)
        val id = s.issue(GitHubConfirmationStore.CONTRIBUTION_FLOW, "完整贡献流")
        assertEquals(GitHubConfirmationStore.ConsumeResult.OK, s.consume(id, GitHubRemoteOperation.FORK))
        assertEquals(GitHubConfirmationStore.ConsumeResult.OK, s.consume(id, GitHubRemoteOperation.BRANCH))
        // write 可多次
        repeat(WRITE_ALLOWANCE_COPIED_FOR_TEST) { _ ->
            assertEquals(GitHubConfirmationStore.ConsumeResult.OK, s.consume(id, GitHubRemoteOperation.WRITE))
        }
        // PR 终态：消耗成功后整张令牌作废
        assertEquals(GitHubConfirmationStore.ConsumeResult.OK, s.consume(id, GitHubRemoteOperation.PULL_REQUEST))
        assertEquals(GitHubConfirmationStore.ConsumeResult.INVALID, s.consume(id, GitHubRemoteOperation.WRITE))
    }

    @Test fun pullRequestTerminalRevokesWholeToken() {
        val clock = FakeClock()
        val s = store(clock)
        val id = s.issue(GitHubConfirmationStore.CONTRIBUTION_FLOW, "PR 后作废")
        assertEquals(GitHubConfirmationStore.ConsumeResult.OK, s.consume(id, GitHubRemoteOperation.PULL_REQUEST))
        assertEquals(GitHubConfirmationStore.ConsumeResult.INVALID, s.consume(id, GitHubRemoteOperation.FORK))
    }

    @Test fun revokeRemovesTokenImmediately() {
        val clock = FakeClock()
        val s = store(clock)
        val id = s.issue(setOf(GitHubRemoteOperation.WRITE), "撤回测试")
        assertTrue(s.revoke(id))
        assertEquals(GitHubConfirmationStore.ConsumeResult.INVALID, s.consume(id, GitHubRemoteOperation.WRITE))
        assertFalse(s.revoke(id))
    }

    @Test fun listActiveHidesExpiredTokens() {
        val clock = FakeClock()
        val s = store(clock)
        val live = s.issue(setOf(GitHubRemoteOperation.FORK), "有效")
        clock.now = 500
        s.issue(setOf(GitHubRemoteOperation.BRANCH), "将过期", ttlMillis = 100)
        clock.now = 1000
        val active = s.listActive()
        assertEquals(1, active.size)
        assertEquals(live, active.first().id)
    }

    @Test fun tokensAreUniqueAndReadable() {
        val clock = FakeClock()
        val s = store(clock)
        val a = s.issue(setOf(GitHubRemoteOperation.FORK), "a")
        val b = s.issue(setOf(GitHubRemoteOperation.FORK), "b")
        assertNotEquals(a, b)
        assertEquals(24, a.length)
        assertTrue(a.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
    }

    @Test fun invalidTokenIsRejected() {
        val clock = FakeClock()
        val s = store(clock)
        assertEquals(GitHubConfirmationStore.ConsumeResult.INVALID, s.consume("not-exist", GitHubRemoteOperation.FORK))
        assertEquals(GitHubConfirmationStore.ConsumeResult.INVALID, s.consume("", GitHubRemoteOperation.FORK))
    }

    @Test fun issueRejectsEmptyScopeAndBadTtl() {
        val clock = FakeClock()
        val s = store(clock)
        try {
            s.issue(emptySet(), "空作用域")
            throw AssertionError("空作用域应该被拒绝")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        try {
            s.issue(setOf(GitHubRemoteOperation.FORK), "零有效期", ttlMillis = 0)
            throw AssertionError("零有效期应该被拒绝")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test fun policyWithoutStoreKeepsLegacyBehavior() {
        val policy = GitHubOperationPolicy()
        policy.requireConfirmation(GitHubRemoteOperation.FORK, "旧式非空校验", "confirm")
        try {
            policy.requireConfirmation(GitHubRemoteOperation.FORK, "空令牌拒绝", null)
            throw AssertionError("空 confirmationId 应该被拒绝")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test fun policyWithStoreValidatesToken() {
        val clock = FakeClock()
        val store = GitHubConfirmationStore(timeSource = { clock.now })
        val policy = GitHubOperationPolicy(store)
        val id = store.issue(setOf(GitHubRemoteOperation.FORK), "policy 测试")
        policy.requireConfirmation(GitHubRemoteOperation.FORK, "有效令牌", id)
        try {
            policy.requireConfirmation(GitHubRemoteOperation.FORK, "错误操作", "garbage")
            throw AssertionError("无效令牌应该被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("授权令牌无效"))
        }
    }

    companion object {
        // 测试用副本，避免直接访问私有常量
        private const val WRITE_ALLOWANCE_COPIED_FOR_TEST = 8
    }
}
