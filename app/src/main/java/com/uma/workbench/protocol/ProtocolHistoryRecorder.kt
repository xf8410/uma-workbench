package com.uma.workbench.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Records one completed send atomically from the caller's point of view: durable persistence
 * finishes before the entry is exposed to the in-memory UI stream. The sink receives the
 * original entry unchanged, including complete credentials, headers, bodies and error text.
 */
class ProtocolHistoryRecorder(
    private val persist: suspend (ProtocolLogEntry) -> Unit
) {
    private val _entries = MutableStateFlow<List<ProtocolLogEntry>>(emptyList())
    val entries: StateFlow<List<ProtocolLogEntry>> = _entries

    suspend fun record(entry: ProtocolLogEntry) {
        persist(entry)
        _entries.value = _entries.value + entry
    }
}
