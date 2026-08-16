package com.uma.workbench.agent

/**
 * Builds the production root Agent and its non-recursive child Agents from the same provider,
 * workspace-bound read source and durable result store. Each child gets an independent loop/cache,
 * while complete evidence remains readable from the conversation's shared result store.
 */
class ReadonlyAgentRuntimeFactory(
    private val provider: AiStreamingProvider,
    private val source: ReadonlyAgentToolDataSource,
    private val resultStore: AgentToolResultStore,
    private val rootLoopLimits: ReadonlyAgentLoopLimits = ReadonlyAgentLoopLimits(),
    private val childLoopLimits: ReadonlyAgentLoopLimits = ReadonlyAgentLoopLimits(),
    private val toolLimits: AgentToolExecutionLimits = AgentToolExecutionLimits(),
    private val subAgentLimits: SubAgentLimits = SubAgentLimits()
) {
    fun createRootLoop(): ReadonlyAgentLoop {
        val coordinator = SubAgentCoordinator(
            SubAgentLoopFactory { createChildLoop() },
            subAgentLimits
        )
        return ReadonlyAgentLoop(
            provider = provider,
            executor = createExecutor(),
            limits = rootLoopLimits,
            specialToolHandler = SubAgentDelegationHandler(coordinator)
        )
    }

    private fun createChildLoop(): ReadonlyAgentLoop = ReadonlyAgentLoop(
        provider = provider,
        executor = createExecutor(),
        limits = childLoopLimits
    )

    private fun createExecutor() = ReadonlyAgentToolExecutor(
        source = source,
        limits = toolLimits,
        resultStore = resultStore
    )
}
