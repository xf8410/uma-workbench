package com.uma.workbench.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.agent.DeterministicAuditEngine
import com.uma.workbench.data.AuditSourceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 确定性审计面板状态：来源选择 → 引擎执行 → 摘要展示。
 * 引擎零模型调用；预算只决定 checkpoint 边界，不限制处理量。
 */
class DeterministicAuditViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WorkbenchApplication
    private val engine = DeterministicAuditEngine(app.contentResolver, app.database)

    private val _workspaceId = MutableStateFlow<String?>(null)
    val workspaceId: StateFlow<String?> = _workspaceId.asStateFlow()

    val sources: StateFlow<List<AuditSourceEntity>> = combine(app.repository.sources(), _workspaceId) { list, wsId ->
        list.filter { wsId == null || it.workspaceId == wsId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun bindWorkspace(id: String?) {
        if (_workspaceId.value != id) {
            _workspaceId.value = id
            _selectedIds.value = emptySet()
            _summary.value = null
            _error.value = null
        }
    }

    fun toggleSource(id: String) {
        _selectedIds.value = if (id in _selectedIds.value) _selectedIds.value - id else _selectedIds.value + id
    }

    fun selectAllVisible() {
        val wsId = _workspaceId.value
        _selectedIds.value = sources.value.filter { wsId == null || it.workspaceId == wsId }.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /** 启动确定性审计：过滤当前工作区选中项，逐来源分析并落证据库。 */
    fun runAudit() {
        if (_running.value) return
        val wsId = _workspaceId.value
        val ids = sources.value.filter { s -> s.id in _selectedIds.value && (wsId == null || s.workspaceId == wsId) }.map { it.id }
        if (ids.isEmpty()) {
            _error.value = "请先选择当前工作区内的审计来源"
            return
        }
        _error.value = null
        _summary.value = null
        _running.value = true
        viewModelScope.launch {
            try {
                val outcome = engine.runAudit(ids)
                _summary.value = outcome.summary
            } catch (e: Throwable) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _running.value = false
            }
        }
    }
}
