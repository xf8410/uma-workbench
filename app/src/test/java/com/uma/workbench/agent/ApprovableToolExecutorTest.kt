package com.uma.workbench.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovableToolExecutorTest {

    private class FakeDataSource : ReadonlyAgentToolDataSource {
        override suspend fun listWorkspaceFiles() = "fake.kt"
        override suspend fun readCurrentFile() = "content"
        override suspend fun readFile(uri: String) = "content of $uri"
        override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = "content"
        override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = "no results"
        override suspend fun searchSymbol(query: String, offset: Int) = "no results"
        override suspend fun readIl2CppClass(className: String) = "not found"
        override suspend fun readProtocolRecord(id: String) = "not found"
        override suspend fun readSoSnapshot(endpoint: String?) = "not connected"
        override suspend fun readDoc(id: String) = "not found"
    }

    @Test
    fun readOnlyTool_passesThroughWithoutApprovalCheck() = runBlocking {
        val executor = ReadonlyAgentToolExecutor(FakeDataSource())
        val gate = InMemoryToolApprovalGate(autoApproveReadOnly = false)
        val approvable = ApprovableToolExecutor(executor, gate)

        val call = AiToolCall(0, "call-1", "read_file", "{\"uri\":\"test.kt\"}")
        val outcome = approvable.execute(call)

        assertTrue("只读工具应直接执行", outcome is AgentToolOutcome.Success)
        val success = outcome as AgentToolOutcome.Success
        assertTrue(success.result.content.contains("content"))
    }

    @Test
    fun highRiskTool_blockedWhenGateDenies() = runBlocking {
        val executor = ReadonlyAgentToolExecutor(FakeDataSource())
        val gate = InMemoryToolApprovalGate() // denies non-read-only by default
        val approvable = ApprovableToolExecutor(executor, gate)

        val call = AiToolCall(0, "call-2", "github_clone_repository", "{\"owner\":\"test\",\"repo\":\"test\"}")
        val outcome = approvable.execute(call)

        assertTrue("高风险工具应被拦截", outcome is AgentToolOutcome.Failure)
        val failure = (outcome as AgentToolOutcome.Failure).failure
        assertTrue(failure.completeError.contains("拒绝"))
    }

    @Test
    fun highRiskTool_executesWhenGateApproves() = runBlocking {
        val executor = ReadonlyAgentToolExecutor(FakeDataSource())
        val gate = InMemoryToolApprovalGate(
            autoApproveLocalWrite = true,
            autoApproveRemoteWrite = true
        )
        val approvable = ApprovableToolExecutor(executor, gate)

        // github_clone_repository is LOCAL_WRITE, auto-approved when configured
        // It will fail at execution time (no githubCloneSource), but not at approval
        val call = AiToolCall(0, "call-3", "github_clone_repository", "{\"owner\":\"test\",\"repo\":\"test\"}")
        val outcome = approvable.execute(call)

        // Should fail at execution (clone source not configured), not at approval
        assertTrue(outcome is AgentToolOutcome.Failure)
        val failure = (outcome as AgentToolOutcome.Failure).failure
        assertTrue(
            "应因执行层缺少配置而失败，而非审批拒绝: ${failure.completeError}",
            !failure.completeError.contains("拒绝")
        )
    }

    @Test
    fun readOnlyTool_notBlockedEvenWhenGateDeniesEverything() = runBlocking {
        val executor = ReadonlyAgentToolExecutor(FakeDataSource())
        val gate = InMemoryToolApprovalGate(autoApproveReadOnly = false)
        val approvable = ApprovableToolExecutor(executor, gate)

        val call = AiToolCall(0, "call-4", "list_workspace_files", "{}")
        val outcome = approvable.execute(call)

        assertTrue(
            "只读工具不应被审批门拦截: $outcome",
            outcome is AgentToolOutcome.Success
        )
    }
}
