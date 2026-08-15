package com.uma.workbench.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Loading state for the complete persistent protocol timeline. */
sealed interface ProtocolHistoryLoadState {
    data object Idle : ProtocolHistoryLoadState
    data object Loading : ProtocolHistoryLoadState
    data class Loaded(val recordCount: Int) : ProtocolHistoryLoadState
    data class Failed(val message: String) : ProtocolHistoryLoadState
}

/**
 * Reloads every persistent protocol record without row, body, header, SID, or response limits.
 * Existing records remain visible while a reload is running or fails.
 */
class ProtocolHistoryTimeline(
    private val loadAll: suspend () -> List<ProtocolHistoryRecord>
) {
    private val _records = MutableStateFlow<List<ProtocolHistoryRecord>>(emptyList())
    val records: StateFlow<List<ProtocolHistoryRecord>> = _records.asStateFlow()

    private val _loadState = MutableStateFlow<ProtocolHistoryLoadState>(ProtocolHistoryLoadState.Idle)
    val loadState: StateFlow<ProtocolHistoryLoadState> = _loadState.asStateFlow()

    suspend fun reload() {
        _loadState.value = ProtocolHistoryLoadState.Loading
        runCatching { loadAll() }
            .onSuccess { completeRecords ->
                _records.value = completeRecords
                _loadState.value = ProtocolHistoryLoadState.Loaded(completeRecords.size)
            }
            .onFailure { error ->
                _loadState.value = ProtocolHistoryLoadState.Failed(
                    error.message ?: error::class.java.name
                )
            }
    }
}
