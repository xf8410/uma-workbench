package com.uma.workbench.agent

/**
 * Execution budget tiers for agent runs.
 * Maps user-facing speed/depth choices to concrete loop limits.
 */
enum class ExecutionTier(
    val label: String,
    val description: String,
    val maxModelRounds: Int,
    val maxToolExecutionsPerRun: Int,
    val maxToolResultCharacters: Int,
    val maxConsecutiveCachedOnlyRounds: Int
) {
    QUICK(
        label = "快速",
        description = "5 轮模型对话，16 次工具执行",
        maxModelRounds = 5,
        maxToolExecutionsPerRun = 16,
        maxToolResultCharacters = 200_000,
        maxConsecutiveCachedOnlyRounds = 3
    ),
    STANDARD(
        label = "标准",
        description = "20 轮模型对话，64 次工具执行",
        maxModelRounds = 20,
        maxToolExecutionsPerRun = 64,
        maxToolResultCharacters = 1_000_000,
        maxConsecutiveCachedOnlyRounds = 3
    ),
    DEEP(
        label = "深入",
        description = "40 轮模型对话，128 次工具执行",
        maxModelRounds = 40,
        maxToolExecutionsPerRun = 128,
        maxToolResultCharacters = 3_000_000,
        maxConsecutiveCachedOnlyRounds = 5
    ),
    EXTREME(
        label = "极限",
        description = "100 轮模型对话，256 次工具执行",
        maxModelRounds = 100,
        maxToolExecutionsPerRun = 256,
        maxToolResultCharacters = 10_000_000,
        maxConsecutiveCachedOnlyRounds = 5
    );

    fun toLoopLimits(
        maxToolCallsPerRound: Int = 8
    ): ReadonlyAgentLoopLimits = ReadonlyAgentLoopLimits(
        maxModelRounds = maxModelRounds,
        maxToolCallsPerRound = maxToolCallsPerRound,
        maxToolExecutionsPerRun = maxToolExecutionsPerRun,
        maxToolResultCharactersPerRun = maxToolResultCharacters,
        maxConsecutiveCachedOnlyRounds = maxConsecutiveCachedOnlyRounds
    )
}
