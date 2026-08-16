package com.uma.workbench.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.github.GitHubAccount
import com.uma.workbench.github.GitHubApiClient
import com.uma.workbench.github.GitHubContent
import com.uma.workbench.github.GitHubCredentialStore
import com.uma.workbench.github.GitHubFileContent
import com.uma.workbench.github.GitHubRepositorySummary
import kotlinx.coroutines.flow.MutableStateFlow
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
    val directory: List<GitHubContent> = emptyList(),
    val file: GitHubFileContent? = null,
    val error: String? = null
)

class GitHubViewModel(application: Application) : AndroidViewModel(application) {
    private val credentialStore = GitHubCredentialStore(application)
    private val _state = MutableStateFlow(GitHubUiState(tokenPresent = credentialStore.loadToken().isNotEmpty()))
    val state: StateFlow<GitHubUiState> = _state

    init {
        if (_state.value.tokenPresent) refreshAccountAndRepositories()
    }

    fun login(token: String) {
        if (token.isBlank()) {
            _state.value = _state.value.copy(error = "GitHub Token 不能为空")
            return
        }
        viewModelScope.launch {
            updateLoading()
            runCatching {
                val client = GitHubApiClient(token.trim())
                val account = client.account()
                val repositories = client.repositories(1)
                credentialStore.saveToken(token)
                account to repositories
            }.onSuccess { (account, repositories) ->
                _state.value = GitHubUiState(
                    tokenPresent = true,
                    account = account,
                    repositories = repositories
                )
            }.onFailure(::showError)
        }
    }

    fun logout() {
        credentialStore.clear()
        _state.value = GitHubUiState()
    }

    fun refreshAccountAndRepositories() {
        val token = credentialStore.loadToken()
        if (token.isEmpty()) return
        viewModelScope.launch {
            updateLoading()
            runCatching {
                val client = GitHubApiClient(token)
                client.account() to client.repositories(1)
            }.onSuccess { (account, repositories) ->
                _state.value = _state.value.copy(
                    tokenPresent = true,
                    loading = false,
                    account = account,
                    repositories = repositories,
                    error = null
                )
            }.onFailure(::showError)
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
            runCatching {
                client().file(repository.owner, repository.name, state.ref, path)
            }.onSuccess { file ->
                _state.value = _state.value.copy(loading = false, file = file, error = null)
            }.onFailure(::showError)
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

    private fun loadDirectory(repository: GitHubRepositorySummary, ref: String, path: String) {
        viewModelScope.launch {
            updateLoading()
            runCatching {
                client().directory(repository.owner, repository.name, ref, path)
            }.onSuccess { entries ->
                _state.value = _state.value.copy(
                    loading = false,
                    selectedRepository = repository,
                    ref = ref,
                    path = path,
                    directory = entries.sortedWith(compareBy<GitHubContent> { it.type != "dir" }.thenBy { it.path }),
                    file = null,
                    error = null
                )
            }.onFailure(::showError)
        }
    }

    private fun client(): GitHubApiClient = GitHubApiClient(credentialStore.loadToken())

    private fun updateLoading() {
        _state.value = _state.value.copy(loading = true, error = null)
    }

    private fun showError(error: Throwable) {
        _state.value = _state.value.copy(loading = false, error = error.message ?: error::class.java.simpleName)
    }
}
