package com.uma.workbench.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.github.GitContent
import com.uma.workbench.github.GitHubAccount
import com.uma.workbench.github.GitHubApiClient
import com.uma.workbench.github.GitHubCredentialStore
import com.uma.workbench.github.GitHubDeviceCode
import com.uma.workbench.github.GitHubDeviceFlow
import com.uma.workbench.github.GitHubFileContent
import com.uma.workbench.github.GitHubRepositorySummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class GitHubUiState(
    val tokenPresent: Boolean = false,
    val loading: Boolean = false,
    val account: GitHubAccount? = null,
    val repositories: List<GitHubRepositorySummary> = emptyList(),
    val selectedRepository: GitHubRepositorySummary? = null,
    val ref: String = "",
    val path: String = "",
    val directory: List<GitContent> = emptyList(),
    val file: GitHubFileContent? = null,
    val oauthClientId: String = "",
    val deviceCode: GitHubDeviceCode? = null,
    val error: String? = null,
    /** Agent 克隆到本地的仓库（github-clones 目录扫描结果）。 */
    val clones: List<CloneRecord> = emptyList()
)

/** 本地克隆记录：owner__repo 目录聚合。 */
data class CloneRecord(
    val directory: java.io.File,
    val ownerRepo: String,
    val refs: List<String>,
    val totalBytes: Long,
    val fileCount: Int
)

class GitHubViewModel(application: Application) : AndroidViewModel(application) {
    private val credentialStore = GitHubCredentialStore(application)
    private val deviceFlow = GitHubDeviceFlow()
    private var deviceFlowJob: Job? = null
    private val database = com.uma.workbench.data.AppDatabase.get(application)

    /** 扫描本地克隆目录刷新克隆记录。 */
    fun refreshClones() {
        viewModelScope.launch {
            val root = java.io.File(getApplication<Application>().filesDir, "github-clones")
            val records = root.listFiles()?.filter { it.isDirectory }?.map { dir ->
                var bytes = 0L
                var count = 0
                dir.walkTopDown().forEach { f -> if (f.isFile) { bytes += f.length(); count++ } }
                CloneRecord(
                    directory = dir,
                    ownerRepo = dir.name,
                    refs = dir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList(),
                    totalBytes = bytes,
                    fileCount = count
                )
            } ?: emptyList()
            _state.update { it.copy(clones = records) }
        }
    }

    /** 删除本地克隆：目录 + audit_sources 里的注册记录（按 uri 前缀）。 */
    fun deleteClone(record: CloneRecord) {
        viewModelScope.launch {
            record.directory.deleteRecursively()
            runCatching { database.auditSources().deleteByUriPrefix(record.directory.toURI().toString()) }
            refreshClones()
        }
    }
    private val _state = MutableStateFlow(
        GitHubUiState(
            tokenPresent = credentialStore.loadToken().isNotEmpty(),
            oauthClientId = credentialStore.loadClientId()
        )
    )
    val state: StateFlow<GitHubUiState> = _state

    init {
        if (_state.value.tokenPresent) refreshAccountAndRepositories()
    }

    fun setOAuthClientId(value: String) {
        _state.value = _state.value.copy(oauthClientId = value)
    }

    fun startDeviceFlow() {
        val clientId = _state.value.oauthClientId.trim()
        if (clientId.isEmpty()) {
            _state.value = _state.value.copy(error = "OAuth Client ID 不能为空")
            return
        }
        deviceFlowJob?.cancel()
        credentialStore.saveClientId(clientId)
        deviceFlowJob = viewModelScope.launch {
            updateLoading()
            runCatching { deviceFlow.requestCode(clientId) }
                .onSuccess { code ->
                    _state.value = _state.value.copy(loading = true, deviceCode = code, error = null)
                    runCatching { deviceFlow.awaitToken(clientId, code) }
                        .onSuccess { token -> completeLogin(token) }
                        .onFailure(::showError)
                }
                .onFailure(::showError)
        }
    }

    fun cancelDeviceFlow() {
        deviceFlowJob?.cancel()
        deviceFlowJob = null
        _state.value = _state.value.copy(loading = false, deviceCode = null, error = null)
    }

    fun login(token: String) {
        if (token.isBlank()) {
            _state.value = _state.value.copy(error = "GitHub Token 不能为空")
            return
        }
        viewModelScope.launch {
            updateLoading()
            runCatching { completeLoginData(token.trim()) }
                .onSuccess { (account, repositories) -> saveLogin(token, account, repositories) }
                .onFailure(::showError)
        }
    }

    fun logout() {
        cancelDeviceFlow()
        credentialStore.clear()
        _state.value = GitHubUiState(oauthClientId = credentialStore.loadClientId())
    }

    fun refreshAccountAndRepositories() {
        val token = credentialStore.loadToken()
        if (token.isEmpty()) return
        viewModelScope.launch {
            updateLoading()
            runCatching { completeLoginData(token) }
                .onSuccess { (account, repositories) ->
                    _state.value = _state.value.copy(
                        tokenPresent = true,
                        loading = false,
                        account = account,
                        repositories = repositories,
                        error = null
                    )
                }
                .onFailure(::showError)
        }
    }

    fun openRepository(repository: GitHubRepositorySummary) {
        loadDirectory(repository, repository.defaultBranch, "")
    }

    fun openDirectory(path: String) {
        val repository = _state.value.selectedRepository ?: return
        loadDirectory(repository, _state.value.ref, path)
    }

    fun goUp() {
        val current = _state.value.path
        if (current.isEmpty()) return
        openDirectory(current.substringBeforeLast('/', ""))
    }

    fun openFile(path: String) {
        val state = _state.value
        val repository = state.selectedRepository ?: return
        viewModelScope.launch {
            updateLoading()
            runCatching { client().file(repository.owner, repository.name, state.ref, path) }
                .onSuccess { file ->
                    _state.value = _state.value.copy(loading = false, file = file, error = null)
                }
                .onFailure(::showError)
        }
    }

    fun closeFile() {
        _state.value = _state.value.copy(file = null)
    }

    fun closeRepository() {
        _state.value = _state.value.copy(
            selectedRepository = null,
            ref = "",
            path = "",
            directory = emptyList(),
            file = null,
            error = null
        )
    }

    private suspend fun completeLogin(token: String) {
        runCatching { completeLoginData(token) }
            .onSuccess { (account, repositories) -> saveLogin(token, account, repositories) }
            .onFailure(::showError)
    }

    private suspend fun completeLoginData(token: String): Pair<GitHubAccount, List<GitHubRepositorySummary>> {
        val client = GitHubApiClient(token.trim())
        return client.account() to client.repositories(1)
    }

    private fun saveLogin(
        token: String,
        account: GitHubAccount,
        repositories: List<GitHubRepositorySummary>
    ) {
        credentialStore.saveToken(token)
        deviceFlowJob = null
        _state.value = GitHubUiState(
            tokenPresent = true,
            account = account,
            repositories = repositories,
            oauthClientId = credentialStore.loadClientId()
        )
    }

    private fun loadDirectory(repository: GitHubRepositorySummary, ref: String, path: String) {
        viewModelScope.launch {
            updateLoading()
            runCatching { client().directory(repository.owner, repository.name, ref, path) }
                .onSuccess { entries ->
                    _state.value = _state.value.copy(
                        loading = false,
                        selectedRepository = repository,
                        ref = ref,
                        path = path,
                        directory = entries.sortedWith(compareBy<GitContent> { it.type != "dir" }.thenBy { it.path }),
                        file = null,
                        error = null
                    )
                }
                .onFailure(::showError)
        }
    }

    private fun client(): GitHubApiClient = GitHubApiClient(credentialStore.loadToken())

    private fun updateLoading() {
        _state.value = _state.value.copy(loading = true, error = null)
    }

    private fun showError(error: Throwable) {
        deviceFlowJob = null
        _state.value = _state.value.copy(loading = false, deviceCode = null, error = error.message ?: error::class.java.simpleName)
    }
}
