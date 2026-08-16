package com.uma.workbench.protocol

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One persistent protocol observation with complete request and response values. */
data class ProtocolHistoryRecord(
    val id: String,
    val timestamp: Long,
    val channel: SendChannel,
    val endpoint: String,
    val sid: String?,
    val viewerId: Long?,
    val requestHeaders: List<ProtocolHeader>,
    val requestBody: String,
    val requestBodyEncrypted: Boolean,
    val httpStatus: Int?,
    val protocolCode: Int?,
    val responseHeaders: List<ProtocolHeader>?,
    val responseBody: String?,
    val responseBodyDecrypted: String?,
    val latencyMs: Long?,
    val success: Boolean?,
    val error: String?
) {
    fun toLogEntry(): ProtocolLogEntry {
        val endpointKind = GameEndpoint.fromPath(endpoint)
        val request = GameRequest(
            endpoint = endpointKind,
            sid = sid,
            viewerId = viewerId,
            body = requestBody,
            bodyEncrypted = requestBodyEncrypted,
            headers = ProtocolHeaders.compatibilityMap(requestHeaders),
            timestamp = timestamp,
            rawEndpoint = endpoint,
            headerEntries = requestHeaders
        )
        val response = httpStatus?.let { status ->
            GameResponse(
                statusCode = status,
                protocolCode = ProtocolStatusCode.fromCode(protocolCode ?: status),
                headers = ProtocolHeaders.compatibilityMap(responseHeaders.orEmpty()),
                body = responseBody.orEmpty(),
                bodyDecrypted = responseBodyDecrypted,
                latencyMs = latencyMs ?: 0L,
                timestamp = timestamp,
                success = success == true,
                headerEntries = responseHeaders.orEmpty()
            )
        }
        return ProtocolLogEntry(timestamp, request, response, error, channel)
    }

    companion object {
        fun from(entry: ProtocolLogEntry): ProtocolHistoryRecord = ProtocolHistoryRecord(
            id = UUID.randomUUID().toString(),
            timestamp = entry.timestamp,
            channel = entry.channel,
            endpoint = entry.request.rawEndpoint,
            sid = entry.request.sid,
            viewerId = entry.request.viewerId,
            requestHeaders = entry.request.headerEntries,
            requestBody = entry.request.body,
            requestBodyEncrypted = entry.request.bodyEncrypted,
            httpStatus = entry.response?.statusCode,
            protocolCode = entry.response?.protocolCode?.code,
            responseHeaders = entry.response?.headerEntries,
            responseBody = entry.response?.body,
            responseBodyDecrypted = entry.response?.bodyDecrypted,
            latencyMs = entry.response?.latencyMs,
            success = entry.response?.success,
            error = entry.error
        )
    }
}

/** Ordered header-list codec. Legacy JSON objects remain readable. */
object ProtocolHeaderCodec {
    private val json = Json

    fun encode(headers: List<ProtocolHeader>): String = buildJsonArray {
        headers.forEach { header -> add(buildJsonObject {
            put("name", header.name)
            put("value", header.value)
        }) }
    }.toString()

    fun decode(encoded: String): List<ProtocolHeader> = when (val root = json.parseToJsonElement(encoded)) {
        is JsonArray -> root.mapIndexed { index, item ->
            val value = item as? JsonObject ?: error("header[$index] must be object")
            ProtocolHeader(
                value["name"]?.jsonPrimitive?.content ?: error("header[$index].name missing"),
                value["value"]?.jsonPrimitive?.content ?: error("header[$index].value missing")
            )
        }
        is JsonObject -> root.entries.map { ProtocolHeader(it.key, it.value.jsonPrimitive.content) }
        else -> error("headers must be JSON array or legacy object")
    }
}

/** Persistent protocol history without row, body or header-entry caps. */
class ProtocolHistoryStore(context: Context) {
    private val helper = ProtocolHistoryOpenHelper(context.applicationContext)

    suspend fun append(entry: ProtocolLogEntry): ProtocolHistoryRecord = withContext(Dispatchers.IO) {
        append(ProtocolHistoryRecord.from(entry))
    }

    suspend fun append(record: ProtocolHistoryRecord): ProtocolHistoryRecord = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertOrThrow(TABLE, null, record.toValues())
        record
    }

    suspend fun get(id: String): ProtocolHistoryRecord? = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(TABLE, COLUMNS, "id = ?", arrayOf(id), null, null, null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }
    }

    suspend fun all(): List<ProtocolHistoryRecord> = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(TABLE, COLUMNS, null, null, null, null, "timestamp ASC, rowid ASC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecord()) } }
    }

    suspend fun compare(firstId: String, secondId: String): ProtocolHistoryComparison? = withContext(Dispatchers.IO) {
        val first = get(firstId) ?: return@withContext null
        val second = get(secondId) ?: return@withContext null
        ProtocolHistoryComparison(
            first,
            second,
            ProtocolPayloadDiff.compare(first.requestBody, second.requestBody),
            ProtocolPayloadDiff.compare(first.responseBody.orEmpty(), second.responseBody.orEmpty())
        )
    }

    private fun ProtocolHistoryRecord.toValues() = ContentValues().apply {
        put("id", id); put("timestamp", timestamp); put("channel", channel.name); put("endpoint", endpoint)
        putNullable("sid", sid); putNullable("viewer_id", viewerId)
        put("request_headers", ProtocolHeaderCodec.encode(requestHeaders)); put("request_body", requestBody)
        put("request_body_encrypted", if (requestBodyEncrypted) 1 else 0); putNullable("http_status", httpStatus)
        putNullable("protocol_code", protocolCode); putNullable("response_headers", responseHeaders?.let(ProtocolHeaderCodec::encode))
        putNullable("response_body", responseBody); putNullable("response_body_decrypted", responseBodyDecrypted)
        putNullable("latency_ms", latencyMs); putNullable("success", success?.let { if (it) 1 else 0 }); putNullable("error", error)
    }

    private fun Cursor.toRecord() = ProtocolHistoryRecord(
        getString(index("id")), getLong(index("timestamp")), SendChannel.valueOf(getString(index("channel"))),
        getString(index("endpoint")), nullableString("sid"), nullableLong("viewer_id"),
        ProtocolHeaderCodec.decode(getString(index("request_headers"))), getString(index("request_body")),
        getInt(index("request_body_encrypted")) != 0, nullableInt("http_status"), nullableInt("protocol_code"),
        nullableString("response_headers")?.let(ProtocolHeaderCodec::decode), nullableString("response_body"),
        nullableString("response_body_decrypted"), nullableLong("latency_ms"), nullableInt("success")?.let { it != 0 }, nullableString("error")
    )

    private fun Cursor.index(name: String) = getColumnIndexOrThrow(name)
    private fun Cursor.nullableString(name: String) = index(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.nullableInt(name: String) = index(name).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.nullableLong(name: String) = index(name).let { if (isNull(it)) null else getLong(it) }
    private fun ContentValues.putNullable(name: String, value: String?) { if (value == null) putNull(name) else put(name, value) }
    private fun ContentValues.putNullable(name: String, value: Int?) { if (value == null) putNull(name) else put(name, value) }
    private fun ContentValues.putNullable(name: String, value: Long?) { if (value == null) putNull(name) else put(name, value) }

    companion object {
        private const val TABLE = "protocol_history"
        private val COLUMNS = arrayOf("id", "timestamp", "channel", "endpoint", "sid", "viewer_id", "request_headers", "request_body", "request_body_encrypted", "http_status", "protocol_code", "response_headers", "response_body", "response_body_decrypted", "latency_ms", "success", "error")
    }

    private class ProtocolHistoryOpenHelper(context: Context) : SQLiteOpenHelper(context, "uma-protocol-history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE protocol_history (id TEXT NOT NULL PRIMARY KEY,timestamp INTEGER NOT NULL,channel TEXT NOT NULL,endpoint TEXT NOT NULL,sid TEXT,viewer_id INTEGER,request_headers TEXT NOT NULL,request_body TEXT NOT NULL,request_body_encrypted INTEGER NOT NULL,http_status INTEGER,protocol_code INTEGER,response_headers TEXT,response_body TEXT,response_body_decrypted TEXT,latency_ms INTEGER,success INTEGER,error TEXT)""")
            db.execSQL("CREATE INDEX protocol_history_timestamp ON protocol_history(timestamp)")
            db.execSQL("CREATE INDEX protocol_history_endpoint ON protocol_history(endpoint)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}

data class ProtocolHistoryComparison(val first: ProtocolHistoryRecord, val second: ProtocolHistoryRecord, val requestBody: List<ProtocolDiffEntry>, val responseBody: List<ProtocolDiffEntry>)
