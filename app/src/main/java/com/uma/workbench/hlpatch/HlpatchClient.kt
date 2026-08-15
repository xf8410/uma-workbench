package com.uma.workbench.hlpatch

import android.util.Log
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.HlpatchSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** HTTP client to hlpatch SO at 127.0.0.1:18765. Features 361-400. */
class HlpatchClient(private val db: AppDatabase, private val baseUrl: String = "http://127.0.0.1:18765") {

    enum class ConnectionState { DISCONNECTED, CONNECTING, READY, DEGRADED, OVERLOADED, INCOMPATIBLE }

    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    suspend fun health(): HlpatchResult = get("/health")
    suspend fun status(): HlpatchResult = get("/summary")

    /** Probes only the real loopback service and retains every complete response and error. */
    suspend fun discoverCapabilities(): HlpatchCapabilityReport {
        val probes = listOf(
            "/health" to true,
            "/summary" to true,
            "/api/proxy" to false,
            "/il2cpp/search?q=&limit=1" to false,
            "/api/md5log" to false
        )
        val observations = probes.map { (path, required) ->
            val result = get(path)
            HlpatchEndpointCapability(
                path = path,
                required = required,
                supported = result.ok,
                statusCode = result.statusCode,
                responseBody = result.body,
                error = result.error
            )
        }
        val compatibility = HlpatchCapabilityClassifier.classify(observations)
        state = when (compatibility) {
            HlpatchCompatibility.COMPATIBLE -> ConnectionState.READY
            HlpatchCompatibility.DEGRADED -> ConnectionState.DEGRADED
            HlpatchCompatibility.INCOMPATIBLE -> ConnectionState.INCOMPATIBLE
            HlpatchCompatibility.UNREACHABLE, HlpatchCompatibility.NOT_CHECKED -> ConnectionState.DISCONNECTED
        }
        return HlpatchCapabilityReport(
            checkedAt = System.currentTimeMillis(),
            compatibility = compatibility,
            endpoints = observations
        )
    }

    suspend fun il2cppTree(name: String, depth: Int = 2): HlpatchResult =
        get("/il2cpp/tree?name=${java.net.URLEncoder.encode(name, "UTF-8")}&depth=$depth")

    suspend fun il2cppSearch(query: String, limit: Int = 50): HlpatchResult =
        get("/il2cpp/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit")

    suspend fun snapshot(): HlpatchResult = get("/debug/ramen_planner_state")

    suspend fun md5Log(): HlpatchResult = get("/api/md5log")

    suspend fun mdbRaw(sql: String): HlpatchResult =
        get("/mdb/raw?sql=${java.net.URLEncoder.encode(sql, "UTF-8")}")

    suspend fun installHooks(): HlpatchResult {
        val r1 = post("/api/md5log/install", "")
        val r2 = post("/api/sniff/toggle?enabled=1", "")
        val r3 = post("/api/md5log/clear", "")
        return if (r1.ok && r2.ok && r3.ok) HlpatchResult.ok("hooks installed") else HlpatchResult.error("hook install failed")
    }

    suspend fun get(path: String): HlpatchResult = request("GET", path, null)
    suspend fun post(path: String, body: String): HlpatchResult = request("POST", path, body)

    private suspend fun request(method: String, path: String, body: String?): HlpatchResult = withContext(Dispatchers.IO) {
        state = ConnectionState.CONNECTING
        try {
            val url = java.net.URL(baseUrl + path)
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                this.requestMethod = method
                connectTimeout = 3_000
                readTimeout = 10_000
                if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            db.hlpatchSnapshots().upsert(HlpatchSnapshotEntity(
                id = UUID.randomUUID().toString(), endpoint = path, responseBody = respBody, statusCode = code,
                capturedAt = System.currentTimeMillis()
            ))

            state = if (code in 200..299) ConnectionState.READY else ConnectionState.DEGRADED
            HlpatchResult(code in 200..299, code, respBody, null)
        } catch (e: Exception) {
            state = ConnectionState.DISCONNECTED
            Log.w("HlpatchClient", "request $path failed: ${e.message}")
            HlpatchResult(false, 0, "", e.stackTraceToString())
        }
    }

    fun observeSnapshots(): Flow<List<HlpatchSnapshotEntity>> = db.hlpatchSnapshots().observeRecent()
}

data class HlpatchResult(val ok: Boolean, val statusCode: Int, val body: String, val error: String?) {
    companion object { fun ok(body: String) = HlpatchResult(true, 200, body, null); fun error(msg: String) = HlpatchResult(false, 0, "", msg) }
}
