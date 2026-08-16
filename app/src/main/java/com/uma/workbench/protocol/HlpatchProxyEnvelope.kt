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

/** Complete request envelope sent to hlpatch /api/proxy. */
data class HlpatchProxyRequestEnvelope(
    val endpoint: String,
    val sid: String?,
    val viewerId: Long?,
    val headers: Map<String, String>,
    val headerEntries: List<ProtocolHeader> = ProtocolHeaders.fromMap(headers),
    val body: String,
    val bodyEncrypted: Boolean,
    val timestamp: Long
)

/** Complete response envelope returned by hlpatch /api/proxy. */
data class HlpatchProxyResponseEnvelope(
    val httpStatus: Int,
    val protocolCode: Int?,
    val headers: Map<String, String>,
    val headerEntries: List<ProtocolHeader> = ProtocolHeaders.fromMap(headers),
    val body: String,
    val bodyDecrypted: String?,
    val latencyMs: Long,
    val success: Boolean,
    val error: String?
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

/** JSON codec that escapes values correctly and retains complete proxy request/response data. */
object HlpatchProxyEnvelopeCodec {
    private val json = Json

    fun from(request: GameRequest) = HlpatchProxyRequestEnvelope(
        endpoint = request.rawEndpoint.ifEmpty { request.endpoint.path },
        sid = request.sid,
        viewerId = request.viewerId,
        headers = request.headers,
        headerEntries = request.headerEntries,
        body = request.body,
        bodyEncrypted = request.bodyEncrypted,
        timestamp = request.timestamp
    )

    fun encodeRequest(request: GameRequest): String = encodeRequest(from(request)).toString()

    fun encodeRequest(value: HlpatchProxyRequestEnvelope): JsonObject = JsonObject(linkedMapOf(
        "endpoint" to JsonPrimitive(value.endpoint),
        "sid" to nullable(value.sid),
        "viewer_id" to nullable(value.viewerId),
        "headers" to encodeHeaderEntries(value.headerEntries),
        "body" to JsonPrimitive(value.body),
        "body_encrypted" to JsonPrimitive(value.bodyEncrypted),
        "timestamp" to JsonPrimitive(value.timestamp)
    ))

    fun decodeRequest(encoded: String): HlpatchProxyRequestEnvelope = decodeRequest(json.parseToJsonElement(encoded).jsonObject)

    fun decodeRequest(value: JsonObject) = HlpatchProxyRequestEnvelope(
        endpoint = value.requiredString("endpoint"),
        sid = value.optionalString("sid"),
        viewerId = value.optionalLong("viewer_id"),
        headers = decodeHeaderMap(value.get("headers")),
        headerEntries = decodeHeaderEntries(value.get("headers")),
        body = value.requiredString("body"),
        bodyEncrypted = value.requiredBoolean("body_encrypted"),
        timestamp = value.requiredLong("timestamp")
    )

    fun encodeResponse(value: HlpatchProxyResponseEnvelope): String = JsonObject(linkedMapOf(
        "http_status" to JsonPrimitive(value.httpStatus),
        "protocol_code" to nullable(value.protocolCode),
        "headers" to encodeHeaderEntries(value.headerEntries),
        "body" to JsonPrimitive(value.body),
        "body_decrypted" to nullable(value.bodyDecrypted),
        "latency_ms" to JsonPrimitive(value.latencyMs),
        "success" to JsonPrimitive(value.success),
        "error" to nullable(value.error)
    )).toString()

    fun decodeResponse(encoded: String): HlpatchProxyResponseEnvelope {
        val value = json.parseToJsonElement(encoded).jsonObject
        return HlpatchProxyResponseEnvelope(
            httpStatus = value.requiredInt("http_status"),
            protocolCode = value.optionalInt("protocol_code"),
            headers = decodeHeaderMap(value.get("headers")),
            headerEntries = decodeHeaderEntries(value.get("headers")),
            body = value.requiredString("body"),
            bodyDecrypted = value.optionalString("body_decrypted"),
            latencyMs = value.requiredLong("latency_ms"),
            success = value.requiredBoolean("success"),
            error = value.optionalString("error")
        )
    }

    private fun decodeHeaderMap(value: JsonElement?): Map<String, String> {
        return when (value) {
            is kotlinx.serialization.json.JsonArray -> ProtocolHeaders.toMap(decodeHeaderEntries(value))
            is JsonObject -> value.mapValues { (_, item) -> item.jsonPrimitive.content }
            else -> emptyMap()
        }
    }

    private fun encodeHeaderEntries(entries: List<ProtocolHeader>): JsonElement {
        // 编码为有序数组格式，保留重复项和顺序
        return kotlinx.serialization.json.JsonArray(entries.map { entry ->
            JsonObject(linkedMapOf(
                "name" to JsonPrimitive(entry.name),
                "value" to JsonPrimitive(entry.value)
            ))
        })
    }

    private fun decodeHeaderEntries(value: JsonElement?): List<ProtocolHeader> {
        return when (value) {
            is kotlinx.serialization.json.JsonArray -> value.map { item ->
                val obj = item.jsonObject
                ProtocolHeader(
                    name = obj.getValue("name").jsonPrimitive.content,
                    value = obj.getValue("value").jsonPrimitive.content
                )
            }
            // 旧格式兼容：JSON object {"name":"value",...}
            is JsonObject -> value.entries.map { (name, v) -> ProtocolHeader(name, v.jsonPrimitive.content) }
            else -> emptyList()
        }
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
