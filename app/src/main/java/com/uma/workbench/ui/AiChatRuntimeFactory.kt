package com.uma.workbench.ui

import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.agent.AiStreamingProvider
import com.uma.workbench.agent.AndroidReadonlyAgentToolDataSource
import com.uma.workbench.agent.FileAgentToolResultStore
import com.uma.workbench.agent.ReadonlyAgentLoop
import com.uma.workbench.agent.ReadonlyAgentRuntimeFactory
import java.io.File

/**
 * Android composition boundary for a conversation-scoped Agent runtime.
 *
 * The main Agent receives the app-owned GitHub source. The runtime factory still constructs child
 * Agents without that source, so this wiring cannot accidentally grant GitHub access to children.
 */
internal object AiChatRuntimeFactory {
    fun create(
        app: WorkbenchApplication,
        provider: AiStreamingProvider,
        workspaceId: String,
        conversationId: String
    ): ReadonlyAgentLoop {
        val resultStore = FileAgentToolResultStore(
            File(app.filesDir, "agent-tool-results/$workspaceId/$conversationId"),
            workspaceId,
            conversationId
        )
        val workspaceSource = AndroidReadonlyAgentToolDataSource(
            app,
            app.database,
            workspaceId,
            { ActiveWorkspaceDocumentBridge.document.value }
        )
        return ReadonlyAgentRuntimeFactory(
            provider = provider,
            source = workspaceSource,
            resultStore = resultStore,
            githubSource = app.githubReadonlyAgentSource
        ).createRootLoop()
    }
}
