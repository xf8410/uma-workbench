package com.uma.workbench.protocol

/** Lossless text projection used by the protocol-history UI and copy actions. */
object ProtocolHistoryPresentation {
    fun headers(headers: Map<String, String>?): String = headers.orEmpty().entries
        .joinToString("\n") { (name, value) -> "$name: $value" }

    fun detail(detail: ProtocolHistoryDetail): String = buildString {
        val record = detail.record
        appendLine("id: ${record.id}")
        appendLine("timestamp: ${record.timestamp}")
        appendLine("channel: ${record.channel}")
        appendLine("endpoint: ${record.endpoint}")
        appendLine("sid: ${record.sid.orEmpty()}")
        appendLine("viewer_id: ${record.viewerId?.toString().orEmpty()}")
        appendLine("request_body_encrypted: ${record.requestBodyEncrypted}")
        appendLine("http_status: ${record.httpStatus?.toString().orEmpty()}")
        appendLine("protocol_code: ${record.protocolCode?.toString().orEmpty()}")
        appendLine("latency_ms: ${record.latencyMs?.toString().orEmpty()}")
        appendLine("success: ${record.success?.toString().orEmpty()}")
        appendLine("error:")
        appendLine(record.error.orEmpty())
        appendLine("request_headers:")
        appendLine(headers(record.requestHeaders))
        appendLine("request_body:")
        appendLine(record.requestBody)
        appendLine("response_headers:")
        appendLine(headers(record.responseHeaders))
        appendLine("response_body_raw:")
        appendLine(record.responseBody.orEmpty())
        appendLine("response_body_decrypted:")
        appendLine(record.responseBodyDecrypted.orEmpty())
        detail.diagnosis?.let {
            appendLine("diagnosis: ${it.title}")
            appendLine("explanation: ${it.explanation}")
            appendLine("suggested_action: ${it.suggestedAction}")
            appendLine("retryable: ${it.retryable}")
        }
    }

    fun diff(diff: ProtocolHistoryDiff): String = buildString {
        appendLine("first_id: ${diff.first.id}")
        appendLine("second_id: ${diff.second.id}")
        section("request_headers", diff.requestHeaders)
        section("request_body", diff.requestBody)
        section("response_headers", diff.responseHeaders)
        section("response_body_raw", diff.rawResponseBody)
        section("response_body_decrypted", diff.decryptedResponseBody)
    }

    private fun StringBuilder.section(name: String, entries: List<ProtocolDiffEntry>) {
        appendLine("$name:")
        if (entries.isEmpty()) appendLine("UNCHANGED")
        entries.forEach { entry ->
            appendLine("${entry.kind} ${entry.path}")
            appendLine("before:")
            appendLine(entry.before.orEmpty())
            appendLine("after:")
            appendLine(entry.after.orEmpty())
        }
    }
}
