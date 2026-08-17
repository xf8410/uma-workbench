package com.uma.workbench.agent

/**
 * Builds the production main Agent and its non-recursive child Agents from the same provider,
 * workspace-bound read source and durable result store. Each child gets an independent loop/cache,
 * while complete evidence remains readable from the conversation's shared result store.
 *
 * GitHub access is deliberately injected only into the main Agent. Child Agents use the child
 * schema and an executor without a GitHub source, preventing accidental parallel API fan-out.
 * No Android root/superuser permission is involved.
 *
 * No hard wall-clock timeout is imposed; calls per turn and page size are bounded.
 */
class ReadonlyAgentRuntimeFactory(
    private val provider: AiStreamingProvider,
    private val source: ReadonlyAgentToolDataSource,
    private val resultStore: AgentToolResultStore,
    private val rootLoopLimits: ReadonlyAgentLoopLimits = ReadonlyAgentLoopLimits(),
    private val childLoopLimits: ReadonlyAgentLoopLimits = ReadonlyAgentLoopLimits(),
    private val toolLimits: AgentToolExecutionLimits = AgentToolExecutionLimits(),
    private val subAgentLimits: SubAgentLimits = SubAgentLimits(),
    private val githubSource: GitHubReadonlyAgentToolDataSource? = null
) {
    fun createRootLoop(): ReadonlyAgentLoop {
        val coordinator = SubAgentCoordinator(
            SubAgentLoopFactory { createChildLoop() },
            subAgentLimits
        )
        return ReadonlyAgentLoop(
            provider = provider,
            executor = createMainExecutor(),
            limits = rootLoopLimits,
            specialToolHandler = SubAgentDelegationHandler(coordinator)
        )
    }

    private fun createChildLoop(): ReadonlyAgentLoop = ReadonlyAgentLoop(
        provider = provider,
        executor = createChildExecutor(),
        limits = childLoopLimits
    )

    private fun createMainExecutor() = ReadonlyAgentToolExecutor(
        source = source,
        limits = toolLimits,
        resultStore = resultStore,
        githubSource = githubSource
    )

    private fun createChildExecutor() = ReadonlyAgentToolExecutor(
        source = source,
        limits = toolLimits,
        resultStore = resultStore
    )
}
