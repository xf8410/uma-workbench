package com.uma.workbench

import android.app.Application
import com.uma.workbench.agent.AgentPartnerDatabase
import com.uma.workbench.agent.AgentPartnerStore
import com.uma.workbench.agent.AndroidGitHubReadonlyAgentToolDataSource
import com.uma.workbench.agent.AndroidGitHubContributionAgentToolDataSource
import com.uma.workbench.agent.GitHubContributionAgentToolDataSource
import com.uma.workbench.agent.GitHubReadonlyAgentToolDataSource
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.WorkbenchRepository
import com.uma.workbench.imports.SourceImporter
import com.uma.workbench.network.NetworkMonitor
import com.uma.workbench.network.NetworkState
import com.uma.workbench.worker.WorkScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class WorkbenchApplication : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var repository: WorkbenchRepository
        private set
    lateinit var networkMonitor: NetworkMonitor
        private set
    lateinit var sourceImporter: SourceImporter
        private set
    lateinit var workScheduler: WorkScheduler
        private set
    lateinit var networkState: kotlinx.coroutines.flow.StateFlow<NetworkState>
        private set
    lateinit var githubReadonlyAgentSource: GitHubReadonlyAgentToolDataSource
    lateinit var githubContributionAgentSource: GitHubContributionAgentToolDataSource
        private set
    lateinit var agentPartnerStore: AgentPartnerStore
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.get(this)
        repository = WorkbenchRepository(database)
        networkMonitor = NetworkMonitor(this)
        networkState = networkMonitor.state.stateIn(
            kotlinx.coroutines.GlobalScope,
            SharingStarted.WhileSubscribed(5000),
            NetworkState.ONLINE
        )
        sourceImporter = SourceImporter(contentResolver)
        workScheduler = WorkScheduler(this)
        githubReadonlyAgentSource = AndroidGitHubReadonlyAgentToolDataSource(this)
        githubContributionAgentSource = AndroidGitHubContributionAgentToolDataSource(this)
        agentPartnerStore = AgentPartnerStore(AgentPartnerDatabase.get(this))
    }
}
