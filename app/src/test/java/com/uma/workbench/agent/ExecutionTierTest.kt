package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 执行预算档位 → Agent 循环上限的映射。档位从聊天 UI 选择后经
 * AiChatRuntimeFactory 传入 ReadonlyAgentRuntimeFactory.rootLoopLimits。
 */
class ExecutionTierTest {

    @Test fun quickTierMapsToSmallestBudget() {
        val limits = ExecutionTier.QUICK.toLoopLimits()
        assertEquals(5, limits.maxModelRounds)
        assertEquals(16, limits.maxToolExecutionsPerRun)
        assertEquals(200_000, limits.maxToolResultCharactersPerRun)
    }

    @Test fun standardTierMatchesLoopDefault() {
        val limits = ExecutionTier.STANDARD.toLoopLimits()
        assertEquals(ReadonlyAgentLoopLimits().maxModelRounds, limits.maxModelRounds)
        assertEquals(ReadonlyAgentLoopLimits().maxToolExecutionsPerRun, limits.maxToolExecutionsPerRun)
        assertEquals(ReadonlyAgentLoopLimits().maxToolResultCharactersPerRun, limits.maxToolResultCharactersPerRun)
    }

    @Test fun deepAndExtremeTiersScaleUp() {
        val deep = ExecutionTier.DEEP.toLoopLimits()
        assertEquals(40, deep.maxModelRounds)
        assertEquals(128, deep.maxToolExecutionsPerRun)
        assertEquals(3_000_000, deep.maxToolResultCharactersPerRun)
        assertEquals(5, deep.maxConsecutiveCachedOnlyRounds)

        val extreme = ExecutionTier.EXTREME.toLoopLimits()
        assertEquals(100, extreme.maxModelRounds)
        assertEquals(256, extreme.maxToolExecutionsPerRun)
        assertEquals(10_000_000, extreme.maxToolResultCharactersPerRun)
    }

    @Test fun allTiersProduceValidLoopLimits() {
        ExecutionTier.entries.forEach { tier ->
            val limits = tier.toLoopLimits()
            // ReadonlyAgentLoopLimits 构造器自带范围校验，能构建即合法
            assertEquals(tier.maxModelRounds, limits.maxModelRounds)
            assertEquals(tier.maxToolExecutionsPerRun, limits.maxToolExecutionsPerRun)
            assertEquals(8, limits.maxToolCallsPerRound)
        }
    }
}
