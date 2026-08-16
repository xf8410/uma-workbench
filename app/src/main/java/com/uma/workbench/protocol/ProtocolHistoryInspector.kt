package com.uma.workbench.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProtocolHistoryDetail(val record: ProtocolHistoryRecord, val diagnosis: ProtocolDiagnosis?)
data class ProtocolHistoryDiff(
    val first: ProtocolHistoryRecord,
    val second: ProtocolHistoryRecord,
    val requestHeaders: List<ProtocolDiffEntry>,
    val requestBody: List<ProtocolDiffEntry>,
    val responseHeaders: List<ProtocolDiffEntry>,
    val rawResponseBody: List<ProtocolDiffEntry>,
    val decryptedResponseBody: List<ProtocolDiffEntry>
)

class ProtocolHistoryInspector {
    private val _selectedIds = MutableStateFlow<List<String>>(emptyList())
    val selectedIds: StateFlow<List<String>> = _selectedIds.asStateFlow()

    fun toggle(recordId: String) {
        val current = _selectedIds.value
        _selectedIds.value = when {
            recordId in current -> current - recordId
            current.size < 2 -> current + recordId
            else -> listOf(current.last(), recordId)
        }
    }

    fun clear() { _selectedIds.value = emptyList() }
    fun detail(record: ProtocolHistoryRecord) = ProtocolHistoryDetail(record, record.protocolCode?.let(ProtocolDiagnostics::diagnose))

    fun diff(records: List<ProtocolHistoryRecord>): ProtocolHistoryDiff? {
        if (_selectedIds.value.size != 2) return null
        val first = records.firstOrNull { it.id == _selectedIds.value[0] } ?: return null
        val second = records.firstOrNull { it.id == _selectedIds.value[1] } ?: return null
        return ProtocolHistoryDiff(
            first, second,
            ProtocolPayloadDiff.compare(headersText(first.requestHeaders), headersText(second.requestHeaders)),
            ProtocolPayloadDiff.compare(first.requestBody, second.requestBody),
            ProtocolPayloadDiff.compare(headersText(first.responseHeaders), headersText(second.responseHeaders)),
            ProtocolPayloadDiff.compare(first.responseBody.orEmpty(), second.responseBody.orEmpty()),
            ProtocolPayloadDiff.compare(first.responseBodyDecrypted.orEmpty(), second.responseBodyDecrypted.orEmpty())
        )
    }

    private fun headersText(headers: Map<String, String>?): String = headers.orEmpty().entries
        .joinToString("\n") { (name, value) -> "$name: $value" }
}
