package com.uma.workbench

import android.app.Application
import com.uma.workbench.agent.AgentPartnerDatabase
import kotlinx.coroutines.launch
import com.uma.workbench.agent.AgentPartnerStore
import com.uma.workbench.agent.AndroidGitHubReadonlyAgentToolDataSource
import com.uma.workbench.agent.ActiveWorkspaceBridge
import com.uma.workbench.agent.AndroidGitHubCloneAgentToolDataSource
import com.uma.workbench.agent.AndroidGitHubContributionAgentToolDataSource
import com.uma.workbench.agent.GitHubCloneAgentToolDataSource
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

    /** GitHub 远程操作一次性授权令牌库（UI 发放，Agent 工具消耗）。 */
    val githubConfirmationStore = com.uma.workbench.github.GitHubConfirmationStore()

    /** UI 驱动的工具审批门（高风险工具执行时挂起等 UI 响应）；每次决定落审计库。 */
    val toolApprovalGate = com.uma.workbench.agent.UiToolApprovalGate(
        onDecision = { request, decision ->
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.uma.workbench.agent.AgentPartnerDatabase.get(this@WorkbenchApplication)
                        .toolApprovalRecords().upsert(
                            com.uma.workbench.agent.AgentToolApprovalRecordEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                runId = request.callId, // callId 全局唯一，runId 暂复用
                                callId = request.callId,
                                toolName = request.toolName,
                                riskLevel = request.riskLevel.name,
                                approved = decision.approved,
                                reason = decision.reason,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                }
            }
        }
    )

    /** Agent 当前对话模式持久化。 */
    val modePreferences by lazy {
        getSharedPreferences("agent_mode", android.content.Context.MODE_PRIVATE)
    }

    /** 局域网自托管模型端点配置。 */
    val lanModelStore by lazy {
        com.uma.workbench.agent.LanModelSettingsStore(this)
    }
    lateinit var githubCloneAgentSource: GitHubCloneAgentToolDataSource
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
        // OpenRouter 每日免费模型：注册每日周期任务 + 启动时若超过 12 小时未同步立即刷新
        workScheduler.scheduleOpenRouterFreeModelsPeriodic()
        runCatching {
            val freeState = com.uma.workbench.agent.OpenRouterFreeModelStore(this).load()
            if (freeState.autoManage && System.currentTimeMillis() - freeState.lastSyncAt > 12 * 3600_000L) {
                workScheduler.scheduleOpenRouterFreeModelsNow()
            }
        }
        githubReadonlyAgentSource = AndroidGitHubReadonlyAgentToolDataSource(this)
        githubContributionAgentSource = AndroidGitHubContributionAgentToolDataSource(this, githubConfirmationStore)
        githubCloneAgentSource = AndroidGitHubCloneAgentToolDataSource(this, database) { ActiveWorkspaceBridge.workspaceId.value }
        agentPartnerStore = AgentPartnerStore(AgentPartnerDatabase.get(this))
    }
}
