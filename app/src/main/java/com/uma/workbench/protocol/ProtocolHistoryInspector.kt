package com.uma.workbench.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Complete diagnostic projection for one persistent history record. */
data class ProtocolHistoryDetail(
    val record: ProtocolHistoryRecord,
    val diagnosis: ProtocolDiagnosis?
)

/** Complete request/response comparison for exactly two selected records. */
data class ProtocolHistoryDiff(
    val first: ProtocolHistoryRecord,
    val second: ProtocolHistoryRecord,
    val requestHeaders: List<ProtocolDiffEntry>,
    val requestBody: List<ProtocolDiffEntry>,
    val responseHeaders: List<ProtocolDiffEntry>,
    val rawResponseBody: List<ProtocolDiffEntry>,
    val decryptedResponseBody: List<ProtocolDiffEntry>
)

/**
 * Selection and inspection state used by the protocol history UI. Selection is intentionally
 * limited to two IDs because a diff has two sides; records and every field inside them are not
 * truncated, filtered, masked, replaced, or discarded.
 */
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

    fun detail(record: ProtocolHistoryRecord): ProtocolHistoryDetail = ProtocolHistoryDetail(
        record = record,
        diagnosis = record.protocolCode?.let(ProtocolDiagnostics::diagnose)
    )

    fun diff(records: List<ProtocolHistoryRecord>): ProtocolHistoryDiff? {
        if (_selectedIds.value.size != 2) return null
        val first = records.firstOrNull { it.id == _selectedIds.value[0] } ?: return null
        val second = records.firstOrNull { it.id == _selectedIds.value[1] } ?: return null
        return ProtocolHistoryDiff(
            first = first,
            second = second,
            requestHeaders = ProtocolPayloadDiff.compare(headersText(first.requestHeaders), headersText(second.requestHeaders)),
            requestBody = ProtocolPayloadDiff.compare(first.requestBody, second.requestBody),
            responseHeaders = ProtocolPayloadDiff.compare(headersText(first.responseHeaders), headersText(second.responseHeaders)),
            rawResponseBody = ProtocolPayloadDiff.compare(first.responseBody.orEmpty(), second.responseBody.orEmpty()),
            decryptedResponseBody = ProtocolPayloadDiff.compare(first.responseBodyDecrypted.orEmpty(), second.responseBodyDecrypted.orEmpty())
        )
    }

    private fun headersText(headers: Map<String, String>?): String = headers.orEmpty().entries
        .joinToString(separator = "\n") { (name, value) -> "$name: $value" }
}
