package com.uma.workbench.lsp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.data.LspDiagnosticEntity
import com.uma.workbench.data.LspServerEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

class LspViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WorkbenchApplication
    private val database = app.database

    private val _servers = MutableStateFlow<List<LspServerEntity>>(emptyList())
    val servers: StateFlow<List<LspServerEntity>> = _servers.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<LspDiagnosticEntity>>(emptyList())
    val diagnostics: StateFlow<List<LspDiagnosticEntity>> = _diagnostics.asStateFlow()

    private val _currentFileUri = MutableStateFlow<String?>(null)
    val currentFileUri: StateFlow<String?> = _currentFileUri.asStateFlow()

    fun loadServers(workspaceId: String) {
        viewModelScope.launch {
            database.lspServers().observeAll(workspaceId).collect { _servers.value = it }
        }
    }

    fun loadDiagnostics(workspaceId: String, fileUri: String) {
        _currentFileUri.value = fileUri
        viewModelScope.launch {
            database.lspDiagnostics().observeForFile(workspaceId, fileUri).collect { _diagnostics.value = it }
        }
    }

    fun addBuiltinServer(workspaceId: String, config: LspServerConfig) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            database.lspServers().upsert(LspServerEntity(
                id = UUID.randomUUID().toString(),
                workspaceId = workspaceId,
                serverId = config.id,
                displayName = config.displayName,
                language = config.language,
                command = config.command,
                argsJson = config.args.joinToString(","),
                initOptionsJson = config.initOptionsJson,
                enabled = config.enabled,
                status = "NOT_STARTED",
                capabilitiesJson = null,
                updatedAt = now
            ))
        }
    }

    fun toggleServer(workspaceId: String, serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            val existing = database.lspServers().get(workspaceId, serverId) ?: return@launch
            database.lspServers().upsert(existing.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
        }
    }

    fun removeServer(workspaceId: String, serverId: String) {
        viewModelScope.launch {
            database.lspServers().delete(workspaceId, serverId)
        }
    }

    fun ensureBuiltinServers(workspaceId: String) {
        viewModelScope.launch {
            val existing = database.lspServers().get(workspaceId, BuiltinLspServers.RUST.id)
            if (existing == null) {
                addBuiltinServer(workspaceId, BuiltinLspServers.RUST)
            }
            val existingKt = database.lspServers().get(workspaceId, BuiltinLspServers.KOTLIN.id)
            if (existingKt == null) {
                addBuiltinServer(workspaceId, BuiltinLspServers.KOTLIN)
            }
        }
    }

    fun diagnosticCount(severity: String): Int = _diagnostics.value.count { it.severity == severity }
}
