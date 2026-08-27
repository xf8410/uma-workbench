package com.uma.workbench.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovableToolExecutorTest {

    private class FakeDataSource : ReadonlyAgentToolDataSource {
        override suspend fun listWorkspaceFiles() = "fake_files"
        override suspend fun readCurrentFile() = "fake_current"
        override suspend fun readFile(uri: String) = "fake_read"
        override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = "fake_range"
        override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = "fake_search"
        override suspend fun searchSymbol(query: String, offset: Int) = "fake_symbol"
        override suspend fun readIl2CppClass(className: String) = "fake_il2cpp"
        override suspend fun readProtocolRecord(id: String) = "fake_protocol"
        override suspend fun readSoSnapshot(endpoint: String?) = "fake_so"
        override suspend fun readDoc(id: String) = "fake_doc"
    }

    private class FakeContributionSource : GitHubContributionAgentToolDataSource {
        override suspend fun forkRepository(owner: String, repo: String, confirmationId: String) = "forked:$owner/$repo"
        override suspend fun createBranch(progress: String, branch: String, confirmationId: String) = "branched:$branch"
        override suspend fun writeFile(progress: String, path: String, content: String, commitMessage: String, confirmationId: String) = "wrote:$path"
        override suspend fun createPullRequest(progress: String, title: String, body: String, draft: Boolean, confirmationId: String) = "pr:$title"
    }

    private class FakeCloneSource : GitHubCloneAgentToolDataSource {
        override suspend fun cloneRepository(owner: String, repo: String, ref: String) = "cloned:$owner/$repo@$ref"
    }

    private class StubGate(val decision: ToolApprovalDecision) : ToolApprovalGate {
        val requests = mutableListOf<ToolApprovalRequest>()
        override suspend fun requestApproval(request: ToolApprovalRequest): ToolApprovalDecision {
            requests += request
            return decision
        }
    }

    private fun newExecutor(mode: AgentMode, gate: ToolApprovalGate): ApprovableToolExecutor {
        val base = ReadonlyAgentToolExecutor(
            source = FakeDataSource(),
            resultStore = InMemoryAgentToolResultStore(),
            githubContributionSource = FakeContributionSource(),
            githubCloneSource = FakeCloneSource()
        )
        return ApprovableToolExecutor(base, gate, ToolCapabilityRegistry.default()) { mode }
    }

    private fun call(name: String, args: String = "{}") = AiToolCall(0, "call-1", name, args)

    @Test fun modeAskBlocksRemoteWrite() {
        runBlocking {
            val gate = StubGate(ToolApprovalDecision("call-1", approved = true))
            val exec = newExecutor(AgentMode.ASK, gate)
            val outcome = exec.execute(call("github_contribute_fork", """{"owner":"o","repo":"r","confirmationId":"c"}"""))
            assertTrue(outcome is AgentToolOutcome.Failure)
            val failure = (outcome as AgentToolOutcome.Failure).failure
            assertTrue("实际错误：${failure.completeError}", failure.completeError.contains("不允许执行风险等级"))
            assertTrue(failure.completeError.contains("询问"))
            // gate 没被调用——模式直接拒绝
            assertEquals(0, gate.requests.size)
        }
    }

    @Test fun modeAskAllowsReadOnlyWithoutApproval() {
        runBlocking {
            val gate = StubGate(ToolApprovalDecision("call-1", approved = true))
            val exec = newExecutor(AgentMode.ASK, gate)
            val outcome = exec.execute(call("list_workspace_files"))
            assertTrue("实际：$outcome", outcome is AgentToolOutcome.Success)
            // read-only 不需要审批
            assertEquals(0, gate.requests.size)
        }
    }

    @Test fun modeActAllowsRemoteWriteWhenApproved() {
        runBlocking {
            val gate = StubGate(ToolApprovalDecision("call-1", approved = true, reason = "测试批准"))
            val exec = newExecutor(AgentMode.ACT, gate)
            val outcome = exec.execute(call("github_contribute_fork", """{"owner":"o","repo":"r","confirmationId":"c"}"""))
            assertTrue("实际：$outcome", outcome is AgentToolOutcome.Success)
            assertEquals(1, gate.requests.size)
        }
    }

    @Test fun modeActRejectsRemoteWriteWhenDenied() {
        runBlocking {
            val gate = StubGate(ToolApprovalDecision("call-1", approved = false, reason = "测试拒绝"))
            val exec = newExecutor(AgentMode.ACT, gate)
            val outcome = exec.execute(call("github_contribute_fork", """{"owner":"o","repo":"r","confirmationId":"c"}"""))
            assertTrue(outcome is AgentToolOutcome.Failure)
            assertTrue((outcome as AgentToolOutcome.Failure).failure.completeError.contains("测试拒绝"))
        }
    }

    @Test fun modeActAllowsLocalWriteWithApproval() {
        runBlocking {
            val gate = StubGate(ToolApprovalDecision("call-1", approved = true))
            val exec = newExecutor(AgentMode.ACT, gate)
            val outcome = exec.execute(call("github_clone_repository", """{"owner":"o","repo":"r"}"""))
            assertTrue("实际：$outcome", outcome is AgentToolOutcome.Success)
            assertEquals(1, gate.requests.size)
        }
    }

    @Test fun modeAskBlocksLocalWrite() {
        runBlocking {
            val gate = StubGate(ToolApprovalDecision("call-1", approved = true))
            val exec = newExecutor(AgentMode.ASK, gate)
            val outcome = exec.execute(call("github_clone_repository", """{"owner":"o","repo":"r"}"""))
            assertTrue(outcome is AgentToolOutcome.Failure)
            assertTrue((outcome as AgentToolOutcome.Failure).failure.completeError.contains("不允许"))
            assertEquals(0, gate.requests.size)
        }
    }

    @Test fun modeObserveBlocksAllWrites() {
        runBlocking {
            val gate = StubGate(ToolApprovalDecision("call-1", approved = true))
            val exec = newExecutor(AgentMode.OBSERVE, gate)
            val outcome = exec.execute(call("github_clone_repository", """{"owner":"o","repo":"r"}"""))
            assertTrue(outcome is AgentToolOutcome.Failure)
            assertEquals(0, gate.requests.size)
        }
    }

    @Test fun modeInvestigateAllowsReadOnlyButBlocksWrites() {
        runBlocking {
            val gate = StubGate(ToolApprovalDecision("call-1", approved = true))
            val exec = newExecutor(AgentMode.INVESTIGATE, gate)
            // read-only 允许
            val read = exec.execute(call("list_workspace_files"))
            assertTrue(read is AgentToolOutcome.Success)
            // local write 不允许
            val clone = exec.execute(call("github_clone_repository", """{"owner":"o","repo":"r"}"""))
            assertTrue(clone is AgentToolOutcome.Failure)
        }
    }
}
