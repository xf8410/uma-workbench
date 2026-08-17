package com.uma.workbench.ui

import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.agent.AiStreamingProvider
import com.uma.workbench.agent.AndroidReadonlyAgentToolDataSource
import com.uma.workbench.agent.FileAgentToolResultStore
import com.uma.workbench.agent.GitHubReadonlyAgentToolDataSource
import com.uma.workbench.agent.ReadonlyAgentLoop
import com.uma.workbench.agent.ReadonlyAgentRuntimeFactory
import com.uma.workbench.agent.ReadonlyAgentToolDataSource
import java.io.File

/** Android composition boundary for a conversation-scoped main Agent runtime. */
internal object AiChatRuntimeFactory {
    fun create(
        app: WorkbenchApplication,
        provider: AiStreamingProvider,
        workspaceId: String,
        conversationId: String
    ): ReadonlyAgentLoop = create(
        filesDir = app.filesDir,
        provider = provider,
        workspaceId = workspaceId,
        conversationId = conversationId,
        githubSource = app.githubReadonlyAgentSource,
        workspaceSource = AndroidReadonlyAgentToolDataSource(
            app, app.database, workspaceId, { ActiveWorkspaceDocumentBridge.document.value }
        )
    )

    internal fun create(
        filesDir: File,
        provider: AiStreamingProvider,
        workspaceId: String,
        conversationId: String,
        githubSource: GitHubReadonlyAgentToolDataSource,
        workspaceSource: ReadonlyAgentToolDataSource
    ): ReadonlyAgentLoop {
        val resultStore = FileAgentToolResultStore(
            File(filesDir, "agent-tool-results/$workspaceId/$conversationId"),
            workspaceId,
            conversationId
        )
        return ReadonlyAgentRuntimeFactory(
            provider = provider,
            source = workspaceSource,
            resultStore = resultStore,
            githubSource = githubSource
        ).createRootLoop()
    }
}
