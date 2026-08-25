package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCapabilityRegistryTest {

    @Test
    fun defaultRegistry_containsAllWorkspaceReadOnlyTools() {
        val registry = ToolCapabilityRegistry.default()
        val workspaceTools = listOf(
            "list_workspace_files", "read_current_file", "read_file",
            "read_file_range", "search_workspace", "search_symbol",
            "read_il2cpp_class", "read_protocol_record", "read_so_snapshot",
            "read_doc", "read_tool_result", "delegate_subagents"
        )
        for (tool in workspaceTools) {
            assertNotNull("工具 $tool 应在注册表中", registry.get(tool))
            assertEquals("工具 $tool 应为只读", ToolRiskLevel.READ_ONLY, registry.riskLevel(tool))
            assertFalse("只读工具不需要审批", registry.requiresApproval(tool))
        }
    }

    @Test
    fun defaultRegistry_githubContributionToolsRequireApproval() {
        val registry = ToolCapabilityRegistry.default()
        val contributionTools = listOf(
            "github_contribute_fork", "github_contribute_branch",
            "github_contribute_write", "github_contribute_pr"
        )
        for (tool in contributionTools) {
            assertEquals("工具 $tool 应为远程写入", ToolRiskLevel.REMOTE_WRITE, registry.riskLevel(tool))
            assertTrue("工具 $tool 应需要审批", registry.requiresApproval(tool))
        }
    }

    @Test
    fun defaultRegistry_cloneRepositoryIsLocalWrite() {
        val registry = ToolCapabilityRegistry.default()
        val cap = registry.get("github_clone_repository")
        assertNotNull(cap)
        assertEquals(ToolRiskLevel.LOCAL_WRITE, cap!!.riskLevel)
        assertTrue(cap.requiresApproval)
    }

    @Test
    fun defaultRegistry_githubReadOnlyToolsDoNotRequireApproval() {
        val registry = ToolCapabilityRegistry.default()
        val readOnlyTools = listOf(
            "github_list_repositories", "github_get_repository", "github_read_file"
        )
        for (tool in readOnlyTools) {
            assertFalse("工具 $tool 不应需要审批", registry.requiresApproval(tool))
        }
    }

    @Test
    fun unknownTool_defaultsToReadOnly() {
        val registry = ToolCapabilityRegistry()
        assertEquals(ToolRiskLevel.READ_ONLY, registry.riskLevel("nonexistent_tool"))
        assertFalse(registry.requiresApproval("nonexistent_tool"))
        assertNull(registry.get("nonexistent_tool"))
    }

    @Test
    fun customRegistration_worksCorrectly() {
        val registry = ToolCapabilityRegistry()
        registry.register(
            ToolCapability("custom_tool", "自定义工具", ToolRiskLevel.DESTRUCTIVE)
        )
        assertEquals(ToolRiskLevel.DESTRUCTIVE, registry.riskLevel("custom_tool"))
        assertTrue(registry.requiresApproval("custom_tool"))
    }
}
