package com.uma.workbench.agent

import kotlinx.coroutines.flow.Flow

/**
 * Delegates every stream() call to a resolver-supplied [AiStreamingProvider].
 *
 * This lets the chat runtime switch providers at runtime (e.g. between the cloud
 * catalog provider and the LAN self-hosted model provider) without recreating the
 * [ReadonlyAgentLoop], because the loop holds this proxy for its whole lifetime.
 */
class AiStreamingProviderProxy(
    private val resolve: () -> AiStreamingProvider
) : AiStreamingProvider {
    override fun stream(request: AiGenerationRequest): Flow<AiStreamEvent> = resolve().stream(request)
}
