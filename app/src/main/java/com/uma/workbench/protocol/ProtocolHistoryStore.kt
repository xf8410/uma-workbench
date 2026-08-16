package com.uma.workbench.protocol

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class ProtocolHistoryRecord(
    val id: String,
    val timestamp: Long,
    val channel: SendChannel,
    val endpoint: String,
    val sid: String?,
    val viewerId: Long?,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val requestBodyEncrypted: Boolean,
    val httpStatus: Int?,
    val protocolCode: Int?,
    val responseHeaders: Map<String, String>?,
    val responseBody: String?,
    val responseBodyDecrypted: String?,
    val latencyMs: Long?,
    val success: Boolean?,
    val error: String?
) {
    fun toLogEntry(): ProtocolLogEntry {
        val request = GameRequest(
            endpoint = GameEndpoint.entries.firstOrNull { it.path == endpoint } ?: GameEndpoint.LOGIN,
            sid = sid,
            viewerId = viewerId,
            body = requestBody,
            bodyEncrypted = requestBodyEncrypted,
            headers = requestHeaders,
            timestamp = timestamp
        )
        val response = httpStatus?.let { status ->
            GameResponse(status, ProtocolStatusCode.fromCode(protocolCode ?: status), responseHeaders.orEmpty(), responseBody.orEmpty(), responseBodyDecrypted, latencyMs ?: 0L, timestamp, success == true)
        }
        return ProtocolLogEntry(timestamp, request, response, error, channel)
    }

    companion object {
        fun from(entry: ProtocolLogEntry): ProtocolHistoryRecord = ProtocolHistoryRecord(
            UUID.randomUUID().toString(), entry.timestamp, entry.channel, entry.request.endpoint.path,
            entry.request.sid, entry.request.viewerId, entry.request.headers, entry.request.body,
            entry.request.bodyEncrypted, entry.response?.statusCode, entry.response?.protocolCode?.code,
            entry.response?.headers, entry.response?.body, entry.response?.bodyDecrypted,
            entry.response?.latencyMs, entry.response?.success, entry.error
        )
    }
}

object ProtocolHeaderCodec {
    private val json = Json
    fun encode(headers: Map<String, String>): String = JsonObject(headers.mapValues { JsonPrimitive(it.value) }).toString()
    fun decode(encoded: String): Map<String, String> = json.parseToJsonElement(encoded).jsonObject.mapValues { it.value.jsonPrimitive.content }
}

class ProtocolHistoryStore(context: Context) {
    private val helper = ProtocolHistoryOpenHelper(context.applicationContext)

    suspend fun append(entry: ProtocolLogEntry): ProtocolHistoryRecord = withContext(Dispatchers.IO) { append(ProtocolHistoryRecord.from(entry)) }
    suspend fun append(record: ProtocolHistoryRecord): ProtocolHistoryRecord = withContext(Dispatchers.IO) { helper.writableDatabase.insertOrThrow(TABLE, null, record.toValues()); record }
    suspend fun get(id: String): ProtocolHistoryRecord? = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(TABLE, COLUMNS, "id = ?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) it.toRecord() else null }
    }
    suspend fun all(): List<ProtocolHistoryRecord> = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(TABLE, COLUMNS, null, null, null, null, "timestamp ASC, rowid ASC").use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecord()) } }
    }
    suspend fun compare(firstId: String, secondId: String): ProtocolHistoryComparison? = withContext(Dispatchers.IO) {
        val first = get(firstId) ?: return@withContext null
        val second = get(secondId) ?: return@withContext null
        ProtocolHistoryComparison(first, second, ProtocolPayloadDiff.compare(first.requestBody, second.requestBody), ProtocolPayloadDiff.compare(first.responseBody.orEmpty(), second.responseBody.orEmpty()))
    }

    private fun ProtocolHistoryRecord.toValues() = ContentValues().apply {
        put("id", id); put("timestamp", timestamp); put("channel", channel.name); put("endpoint", endpoint)
        putNullable("sid", sid); putNullable("viewer_id", viewerId); put("request_headers", ProtocolHeaderCodec.encode(requestHeaders))
        put("request_body", requestBody); put("request_body_encrypted", if (requestBodyEncrypted) 1 else 0)
        putNullable("http_status", httpStatus); putNullable("protocol_code", protocolCode)
        putNullable("response_headers", responseHeaders?.let(ProtocolHeaderCodec::encode)); putNullable("response_body", responseBody)
        putNullable("response_body_decrypted", responseBodyDecrypted); putNullable("latency_ms", latencyMs)
        putNullable("success", success?.let { if (it) 1 else 0 }); putNullable("error", error)
    }

    private fun Cursor.toRecord() = ProtocolHistoryRecord(
        getString(index("id")), getLong(index("timestamp")), SendChannel.valueOf(getString(index("channel"))), getString(index("endpoint")),
        nullableString("sid"), nullableLong("viewer_id"), ProtocolHeaderCodec.decode(getString(index("request_headers"))), getString(index("request_body")),
        getInt(index("request_body_encrypted")) != 0, nullableInt("http_status"), nullableInt("protocol_code"),
        nullableString("response_headers")?.let(ProtocolHeaderCodec::decode), nullableString("response_body"), nullableString("response_body_decrypted"),
        nullableLong("latency_ms"), nullableInt("success")?.let { it != 0 }, nullableString("error")
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
