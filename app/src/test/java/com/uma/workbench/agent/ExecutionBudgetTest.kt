package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionBudgetTest {

    @Test
    fun quickTier_hasConservativeLimits() {
        val limits = ExecutionTier.QUICK.toLoopLimits()
        assertEquals(5, limits.maxModelRounds)
        assertEquals(16, limits.maxToolExecutionsPerRun)
        assertEquals(200_000, limits.maxToolResultCharactersPerRun)
    }

    @Test
    fun standardTier_matchesDefaults() {
        val limits = ExecutionTier.STANDARD.toLoopLimits()
        assertEquals(20, limits.maxModelRounds)
        assertEquals(64, limits.maxToolExecutionsPerRun)
        assertEquals(1_000_000, limits.maxToolResultCharactersPerRun)
    }

    @Test
    fun extremeTier_hasMaximumLimits() {
        val limits = ExecutionTier.EXTREME.toLoopLimits()
        assertEquals(100, limits.maxModelRounds)
        assertEquals(256, limits.maxToolExecutionsPerRun)
        assertEquals(10_000_000, limits.maxToolResultCharactersPerRun)
    }

    @Test
    fun allTiers_haveIncreasingLimits() {
        val tiers = ExecutionTier.values()
        for (i in 0 until tiers.size - 1) {
            val current = tiers[i]
            val next = tiers[i + 1]
            assertTrue(
                "${current.label} 的 maxModelRounds 应小于 ${next.label}",
                current.maxModelRounds < next.maxModelRounds
            )
            assertTrue(
                "${current.label} 的 maxToolExecutionsPerRun 应小于 ${next.label}",
                current.maxToolExecutionsPerRun < next.maxToolExecutionsPerRun
            )
            assertTrue(
                "${current.label} 的 maxToolResultCharacters 应小于 ${next.label}",
                current.maxToolResultCharacters < next.maxToolResultCharacters
            )
        }
    }
}
