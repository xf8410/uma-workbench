package com.uma.workbench.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.Reader
import java.io.Writer

object ProtocolHistoryArchive {
    private val json = Json
    fun export(records: Sequence<ProtocolHistoryRecord>, writer: Writer): Long {
        var count = 0L
        records.forEach { writer.write(encode(it).toString()); writer.write("\n"); count++ }
        writer.flush(); return count
    }
    fun import(reader: Reader, consume: (ProtocolHistoryRecord) -> Unit): ProtocolArchiveImportResult = importRecords(reader, consume)
    fun importRecords(reader: Reader, consume: (ProtocolHistoryRecord) -> Unit): ProtocolArchiveImportResult {
        var lineNumber = 0L; var imported = 0L; val errors = mutableListOf<ProtocolArchiveLineError>()
        val buffered = if (reader is BufferedReader) reader else reader.buffered()
        while (true) {
            val line = buffered.readLine() ?: break; lineNumber++
            if (line.isEmpty()) { errors += ProtocolArchiveLineError(lineNumber, line, "empty JSONL record"); continue }
            runCatching { consume(decode(json.parseToJsonElement(line).jsonObject)) }
                .onSuccess { imported++ }
                .onFailure { errors += ProtocolArchiveLineError(lineNumber, line, it.message ?: it::class.java.name) }
        }
        return ProtocolArchiveImportResult(imported, lineNumber, errors)
    }
    fun encode(record: ProtocolHistoryRecord): JsonObject = JsonObject(linkedMapOf(
        "id" to JsonPrimitive(record.id), "timestamp" to JsonPrimitive(record.timestamp), "channel" to JsonPrimitive(record.channel.name),
        "endpoint" to JsonPrimitive(record.endpoint), "sid" to nullable(record.sid), "viewer_id" to nullable(record.viewerId),
        "request_headers" to encodeHeaders(record.requestHeaders), "request_body" to JsonPrimitive(record.requestBody),
        "request_body_encrypted" to JsonPrimitive(record.requestBodyEncrypted), "http_status" to nullable(record.httpStatus),
        "protocol_code" to nullable(record.protocolCode), "response_headers" to (record.responseHeaders?.let(::encodeHeaders) ?: JsonNull),
        "response_body" to nullable(record.responseBody), "response_body_decrypted" to nullable(record.responseBodyDecrypted),
        "latency_ms" to nullable(record.latencyMs), "success" to nullable(record.success), "error" to nullable(record.error)
    ))
    fun decode(value: JsonObject): ProtocolHistoryRecord = ProtocolHistoryRecord(
        value.requiredString("id"), value.requiredLong("timestamp"), SendChannel.valueOf(value.requiredString("channel")),
        value.requiredString("endpoint"), value.optionalString("sid"), value.optionalLong("viewer_id"),
        decodeHeaders(value.requiredObject("request_headers")), value.requiredString("request_body"), value.requiredBoolean("request_body_encrypted"),
        value.optionalInt("http_status"), value.optionalInt("protocol_code"), value.optionalObject("response_headers")?.let(::decodeHeaders),
        value.optionalString("response_body"), value.optionalString("response_body_decrypted"), value.optionalLong("latency_ms"),
        value.optionalBoolean("success"), value.optionalString("error")
    )
    private fun encodeHeaders(headers: Map<String, String>) = JsonObject(headers.mapValues { JsonPrimitive(it.value) })
    private fun decodeHeaders(value: JsonObject) = value.mapValues { (_, item) -> item.jsonPrimitive.content }
    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun nullable(value: Long?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun nullable(value: Int?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun nullable(value: Boolean?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun JsonObject.requiredString(name: String) = get(name)?.jsonPrimitive?.contentOrNull ?: error("missing string: $name")
    private fun JsonObject.requiredLong(name: String) = get(name)?.jsonPrimitive?.longOrNull ?: error("missing long: $name")
    private fun JsonObject.requiredBoolean(name: String) = get(name)?.jsonPrimitive?.booleanOrNull ?: error("missing boolean: $name")
    private fun JsonObject.requiredObject(name: String) = get(name) as? JsonObject ?: error("missing object: $name")
    private fun JsonObject.optionalString(name: String) = get(name).unlessNull()?.jsonPrimitive?.contentOrNull
    private fun JsonObject.optionalLong(name: String) = get(name).unlessNull()?.jsonPrimitive?.longOrNull
    private fun JsonObject.optionalInt(name: String) = get(name).unlessNull()?.jsonPrimitive?.intOrNull
    private fun JsonObject.optionalBoolean(name: String) = get(name).unlessNull()?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.optionalObject(name: String) = get(name).unlessNull() as? JsonObject
    private fun JsonElement?.unlessNull(): JsonElement? = if (this == null || this is JsonNull) null else this
}
data class ProtocolArchiveLineError(val lineNumber: Long, val completeLine: String, val message: String)
data class ProtocolArchiveImportResult(val importedRecords: Long, val totalLines: Long, val errors: List<ProtocolArchiveLineError>)
