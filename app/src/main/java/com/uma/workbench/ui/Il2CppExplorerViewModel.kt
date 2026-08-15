package com.uma.workbench.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.hlpatch.HlpatchClient
import com.uma.workbench.hlpatch.Il2CppExplorerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Runs explorer requests against the same real local hlpatch service and snapshot database. */
class Il2CppExplorerViewModel(application: Application) : AndroidViewModel(application) {
    private val client = HlpatchClient((application as WorkbenchApplication).database)
    private val _state = MutableStateFlow(Il2CppExplorerState())
    val state: StateFlow<Il2CppExplorerState> = _state

    fun searchClasses(query: String) = runQuery { client.il2cppClasses(query) }
    fun readFields(className: String) = runQuery { client.il2cppFields(className) }
    fun readMethods(className: String) = runQuery { client.il2cppMethods(className) }

    private fun runQuery(block: suspend () -> com.uma.workbench.hlpatch.Il2CppExplorerResult) = viewModelScope.launch {
        _state.value = _state.value.copy(running = true)
        val result = block()
        _state.value = Il2CppExplorerState(result = result)
    }
}
