package com.uma.workbench.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.data.*
import com.uma.workbench.network.NetworkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WorkbenchApplication
    private val repository = app.repository
    private val selectedId = MutableStateFlow<String?>(null)
    private val importState = MutableStateFlow<String?>(null)

    val conversations = repository.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedConversationId = selectedId.asStateFlow()
    val messages = selectedId.filterNotNull().flatMapLatest(repository::messages).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val workItems = repository.workItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sources = repository.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val importStatus = importState.asStateFlow()
    val network: StateFlow<NetworkState> = app.networkMonitor.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkState.SWITCHING)

    init { viewModelScope.launch { conversations.filter { it.isNotEmpty() }.firstOrNull()?.let { if (selectedId.value == null) selectedId.value = it.first().id } } }
    fun createConversation() = viewModelScope.launch { selectedId.value = repository.createConversation() }
    fun selectConversation(id: String) { selectedId.value = id }
    fun send(text: String) { val clean = text.trim(); if (clean.isEmpty()) return; viewModelScope.launch { val id = selectedId.value ?: repository.createConversation().also { selectedId.value = it }; repository.queueUserMessage(id, clean) } }

    fun importDocuments(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        importState.value = "正在读取并计算 ${uris.size} 个文件的 SHA-256…"
        var succeeded = 0
        uris.forEach { uri ->
            runCatching {
                runCatching { app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val imported = app.sourceImporter.importSource(uri)
                val queued = repository.queueImportedSource(imported.name, uri.toString(), imported.kind, imported.sha256)
                app.workScheduler.scheduleAudit(queued.workItemId)
            }.onSuccess { succeeded++ }.onFailure { importState.value = "已导入 $succeeded/${uris.size}；失败：${it.message ?: "未知错误"}" }
        }
        if (succeeded == uris.size) importState.value = "已导入 $succeeded 个文件并启动审计"
    }
}
