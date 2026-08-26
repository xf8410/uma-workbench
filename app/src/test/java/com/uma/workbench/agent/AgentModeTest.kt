package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeTest {

    @Test
    fun askMode_allowsReadOnlyOnly() {
        assertTrue(AgentMode.ASK.canExecute(ToolRiskLevel.READ_ONLY))
        assertFalse(AgentMode.ASK.canExecute(ToolRiskLevel.LOCAL_WRITE))
        assertFalse(AgentMode.ASK.canExecute(ToolRiskLevel.REMOTE_WRITE))
        assertFalse(AgentMode.ASK.canExecute(ToolRiskLevel.DESTRUCTIVE))
    }

    @Test
    fun investigateMode_allowsReadOnlyOnly() {
        assertTrue(AgentMode.INVESTIGATE.canExecute(ToolRiskLevel.READ_ONLY))
        assertFalse(AgentMode.INVESTIGATE.canExecute(ToolRiskLevel.LOCAL_WRITE))
        assertFalse(AgentMode.INVESTIGATE.canExecute(ToolRiskLevel.REMOTE_WRITE))
    }

    @Test
    fun actMode_allowsAllExceptDestructive() {
        assertTrue(AgentMode.ACT.canExecute(ToolRiskLevel.READ_ONLY))
        assertTrue(AgentMode.ACT.canExecute(ToolRiskLevel.LOCAL_WRITE))
        assertTrue(AgentMode.ACT.canExecute(ToolRiskLevel.REMOTE_WRITE))
        assertFalse(AgentMode.ACT.canExecute(ToolRiskLevel.DESTRUCTIVE))
    }

    @Test
    fun observeMode_allowsReadOnlyOnly() {
        assertTrue(AgentMode.OBSERVE.canExecute(ToolRiskLevel.READ_ONLY))
        assertFalse(AgentMode.OBSERVE.canExecute(ToolRiskLevel.LOCAL_WRITE))
    }

    @Test
    fun destructiveNeverAllowed() {
        for (mode in AgentMode.entries) {
            assertFalse(
                "${mode.label} 不应允许 DESTRUCTIVE 操作",
                mode.canExecute(ToolRiskLevel.DESTRUCTIVE)
            )
        }
    }

    @Test
    fun actMode_requiresApprovalForWrites() {
        assertFalse(AgentMode.ACT.needsApprovalFor(ToolRiskLevel.READ_ONLY))
        assertTrue(AgentMode.ACT.needsApprovalFor(ToolRiskLevel.LOCAL_WRITE))
        assertTrue(AgentMode.ACT.needsApprovalFor(ToolRiskLevel.REMOTE_WRITE))
    }

    @Test
    fun askMode_neverNeedsApproval() {
        for (risk in ToolRiskLevel.entries) {
            assertFalse(
                "ASK 模式不应需要审批 for $risk",
                AgentMode.ASK.needsApprovalFor(risk)
            )
        }
    }

    @Test
    fun fromStorageKey_returnsCorrectMode() {
        assertEquals(AgentMode.ASK, AgentMode.fromStorageKey("ASK"))
        assertEquals(AgentMode.INVESTIGATE, AgentMode.fromStorageKey("INVESTIGATE"))
        assertEquals(AgentMode.ACT, AgentMode.fromStorageKey("ACT"))
        assertEquals(AgentMode.OBSERVE, AgentMode.fromStorageKey("OBSERVE"))
    }

    @Test
    fun fromStorageKey_defaultsToAskForUnknown() {
        assertEquals(AgentMode.ASK, AgentMode.fromStorageKey(null))
        assertEquals(AgentMode.ASK, AgentMode.fromStorageKey("UNKNOWN"))
        assertEquals(AgentMode.ASK, AgentMode.fromStorageKey(""))
    }

    @Test
    fun modesAllowing_returnsCorrectSet() {
        val readOnlyModes = AgentMode.modesAllowing(ToolRiskLevel.READ_ONLY)
        assertEquals(4, readOnlyModes.size)

        val localWriteModes = AgentMode.modesAllowing(ToolRiskLevel.LOCAL_WRITE)
        assertEquals(1, localWriteModes.size)
        assertTrue(localWriteModes.contains(AgentMode.ACT))

        val destructiveModes = AgentMode.modesAllowing(ToolRiskLevel.DESTRUCTIVE)
        assertTrue(destructiveModes.isEmpty())
    }

    @Test
    fun modeTransition_askToAct_requiresConfirmation() {
        val transition = ModeTransition(AgentMode.ASK, AgentMode.ACT)
        assertTrue(transition.requiresConfirmation)
        assertTrue(transition.isElevation)
        assertTrue(transition.involvesWriteAccess)
        assertTrue(transition.involvesRemoteAccess)
        assertNotNull(transition.warningMessage())
    }

    @Test
    fun modeTransition_askToInvestigate_noConfirmationNeeded() {
        val transition = ModeTransition(AgentMode.ASK, AgentMode.INVESTIGATE)
        assertFalse(transition.requiresConfirmation)
        assertFalse(transition.involvesWriteAccess)
        assertNull(transition.warningMessage())
    }

    @Test
    fun modeTransition_actToAsk_isNotElevation() {
        val transition = ModeTransition(AgentMode.ACT, AgentMode.ASK)
        assertFalse(transition.isElevation)
        assertFalse(transition.requiresConfirmation)
        assertNull(transition.warningMessage())
    }

    @Test
    fun modeTransition_investigateToAct_requiresConfirmation() {
        val transition = ModeTransition(AgentMode.INVESTIGATE, AgentMode.ACT)
        assertTrue(transition.requiresConfirmation)
        assertTrue(transition.involvesWriteAccess)
        assertTrue(transition.involvesRemoteAccess)
    }

    @Test
    fun modeTransition_sameMode_noWarning() {
        val transition = ModeTransition(AgentMode.ASK, AgentMode.ASK)
        assertFalse(transition.requiresConfirmation)
        assertNull(transition.warningMessage())
    }

    @Test
    fun systemPromptFragment_containsModeInfo() {
        val fragment = AgentMode.ASK.systemPromptFragment()
        assertTrue("应包含 [agent_mode] 标记", fragment.contains("[agent_mode]"))
        assertTrue("应包含模式名", fragment.contains("ASK"))
        assertTrue("应包含模式标签", fragment.contains("询问"))
        assertTrue("应包含 capabilities", fragment.contains("capabilities"))
        assertTrue("应包含只读工具 allowed", fragment.contains("read_only_tools: allowed"))
        assertTrue("应包含本地写入 denied", fragment.contains("local_write_tools: denied"))
        assertTrue("应包含远程写入 denied", fragment.contains("remote_write_tools: denied"))
        assertTrue("应包含 instruction", fragment.contains("instruction"))
        assertTrue("应以 [/agent_mode] 结尾", fragment.contains("[/agent_mode]"))
    }

    @Test
    fun systemPromptFragment_actModeIncludesApprovalInfo() {
        val fragment = AgentMode.ACT.systemPromptFragment()
        assertTrue("ACT 模式应允许本地写入", fragment.contains("local_write_tools: allowed"))
        assertTrue("ACT 模式应允许远程写入", fragment.contains("remote_write_tools: allowed"))
        assertTrue("ACT 模式应声明审批要求", fragment.contains("write_operations_require_user_approval"))
    }

    @Test
    fun systemPromptFragment_observeModeDeniesWrites() {
        val fragment = AgentMode.OBSERVE.systemPromptFragment()
        assertTrue("OBSERVE 应拒绝本地写入", fragment.contains("local_write_tools: denied"))
        assertTrue("OBSERVE 应拒绝远程写入", fragment.contains("remote_write_tools: denied"))
        assertFalse("OBSERVE 不应包含审批信息", fragment.contains("approval:"))
    }
}