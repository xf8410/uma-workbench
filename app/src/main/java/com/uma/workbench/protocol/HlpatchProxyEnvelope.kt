package com.uma.workbench.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

data class HlpatchProxyRequestEnvelope(
    val endpoint: String,
    val sid: String?,
    val viewerId: Long?,
    val headers: Map<String, String>,
    val body: String,
    val bodyEncrypted: Boolean,
    val timestamp: Long,
    val headerEntries: List<ProtocolHeader> = ProtocolHeaders.fromMap(headers)
)

data class HlpatchProxyResponseEnvelope(
    val httpStatus: Int,
    val protocolCode: Int?,
    val headers: Map<String, String>,
    val body: String,
    val bodyDecrypted: String?,
    val latencyMs: Long,
    val success: Boolean,
    val error: String?,
    val headerEntries: List<ProtocolHeader> = ProtocolHeaders.fromMap(headers)
) {
    fun toGameResponse(timestamp: Long): GameResponse = GameResponse(
        statusCode = httpStatus,
        protocolCode = ProtocolStatusCode.fromCode(protocolCode ?: httpStatus),
        headers = headers,
        body = body,
        bodyDecrypted = bodyDecrypted,
        latencyMs = latencyMs,
        timestamp = timestamp,
        success = success,
        headerEntries = headerEntries
    )
}

object HlpatchProxyEnvelopeCodec {
    private val json = Json

    fun from(request: GameRequest) = HlpatchProxyRequestEnvelope(
        endpoint = request.rawEndpoint,
        sid = request.sid,
        viewerId = request.viewerId,
        headers = request.headers,
        body = request.body,
        bodyEncrypted = request.bodyEncrypted,
        timestamp = request.timestamp,
        headerEntries = request.headerEntries
    )

    fun encodeRequest(request: GameRequest): String = encodeRequest(from(request)).toString()

    fun encodeRequest(value: HlpatchProxyRequestEnvelope): JsonObject = JsonObject(linkedMapOf(
        "endpoint" to JsonPrimitive(value.endpoint),
        "sid" to nullable(value.sid),
        "viewer_id" to nullable(value.viewerId),
        "headers" to encodeHeaders(value.headerEntries),
        "body" to JsonPrimitive(value.body),
        "body_encrypted" to JsonPrimitive(value.bodyEncrypted),
        "timestamp" to JsonPrimitive(value.timestamp)
    ))

    fun decodeRequest(encoded: String): HlpatchProxyRequestEnvelope =
        decodeRequest(json.parseToJsonElement(encoded).jsonObject)

    fun decodeRequest(value: JsonObject): HlpatchProxyRequestEnvelope {
        val entries = decodeHeaders(value["headers"] ?: error("missing headers"))
        return HlpatchProxyRequestEnvelope(
            endpoint = value.requiredString("endpoint"),
            sid = value.optionalString("sid"),
            viewerId = value.optionalLong("viewer_id"),
            headers = ProtocolHeaders.compatibilityMap(entries),
            body = value.requiredString("body"),
            bodyEncrypted = value.requiredBoolean("body_encrypted"),
            timestamp = value.requiredLong("timestamp"),
            headerEntries = entries
        )
    }

    fun encodeResponse(value: HlpatchProxyResponseEnvelope): String = JsonObject(linkedMapOf(
        "http_status" to JsonPrimitive(value.httpStatus),
        "protocol_code" to nullable(value.protocolCode),
        "headers" to encodeHeaders(value.headerEntries),
        "body" to JsonPrimitive(value.body),
        "body_decrypted" to nullable(value.bodyDecrypted),
        "latency_ms" to JsonPrimitive(value.latencyMs),
        "success" to JsonPrimitive(value.success),
        "error" to nullable(value.error)
    )).toString()

    fun decodeResponse(encoded: String): HlpatchProxyResponseEnvelope {
        val value = json.parseToJsonElement(encoded).jsonObject
        val entries = decodeHeaders(value["headers"] ?: error("missing headers"))
        return HlpatchProxyResponseEnvelope(
            httpStatus = value.requiredInt("http_status"),
            protocolCode = value.optionalInt("protocol_code"),
            headers = ProtocolHeaders.compatibilityMap(entries),
            body = value.requiredString("body"),
            bodyDecrypted = value.optionalString("body_decrypted"),
            latencyMs = value.requiredLong("latency_ms"),
            success = value.requiredBoolean("success"),
            error = value.optionalString("error"),
            headerEntries = entries
        )
    }

    private fun encodeHeaders(headers: List<ProtocolHeader>): JsonArray = JsonArray(headers.map {
        JsonObject(linkedMapOf(
            "name" to JsonPrimitive(it.name),
            "value" to JsonPrimitive(it.value)
        ))
    })

    private fun decodeHeaders(value: JsonElement): List<ProtocolHeader> = when (value) {
        is JsonArray -> value.mapIndexed { index, element ->
            val item = element as? JsonObject ?: error("header[$index] must be object")
            ProtocolHeader(item.requiredString("name"), item.requiredString("value"))
        }
        is JsonObject -> value.entries.map { ProtocolHeader(it.key, it.value.jsonPrimitive.content) }
        else -> error("headers must be array or legacy object")
    }

    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun nullable(value: Long?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun nullable(value: Int?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun JsonObject.requiredString(name: String) = get(name)?.jsonPrimitive?.contentOrNull ?: error("missing string: $name")
    private fun JsonObject.requiredLong(name: String) = get(name)?.jsonPrimitive?.longOrNull ?: error("missing long: $name")
    private fun JsonObject.requiredInt(name: String) = get(name)?.jsonPrimitive?.intOrNull ?: error("missing int: $name")
    private fun JsonObject.requiredBoolean(name: String) = get(name)?.jsonPrimitive?.booleanOrNull ?: error("missing boolean: $name")
    private fun JsonObject.optionalString(name: String) = get(name).unlessNull()?.jsonPrimitive?.contentOrNull
    private fun JsonObject.optionalLong(name: String) = get(name).unlessNull()?.jsonPrimitive?.longOrNull
    private fun JsonObject.optionalInt(name: String) = get(name).unlessNull()?.jsonPrimitive?.intOrNull
    private fun JsonElement?.unlessNull(): JsonElement? = if (this == null || this is JsonNull) null else this
}
