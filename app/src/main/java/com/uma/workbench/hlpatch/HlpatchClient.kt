package com.uma.workbench.hlpatch

import android.util.Log
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.HlpatchSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import java.net.URLEncoder
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
            HlpatchEndpointCapability(path, required, result.ok, result.statusCode, result.body, result.error)
        }
        val compatibility = HlpatchCapabilityClassifier.classify(observations)
        state = when (compatibility) {
            HlpatchCompatibility.COMPATIBLE -> ConnectionState.READY
            HlpatchCompatibility.DEGRADED -> ConnectionState.DEGRADED
            HlpatchCompatibility.INCOMPATIBLE -> ConnectionState.INCOMPATIBLE
            HlpatchCompatibility.UNREACHABLE, HlpatchCompatibility.NOT_CHECKED -> ConnectionState.DISCONNECTED
        }
        return HlpatchCapabilityReport(System.currentTimeMillis(), compatibility, observations)
    }

    suspend fun il2cppTree(name: String, depth: Int = 2): HlpatchResult =
        get("/il2cpp/tree?name=${encode(name)}&depth=$depth")

    suspend fun il2cppSearch(query: String, limit: Int = 50): HlpatchResult =
        get("/il2cpp/search?q=${encode(query)}&limit=$limit")

    /** Uses the observed class endpoint and preserves the complete response even if its schema is unknown. */
    suspend fun il2cppClasses(query: String): Il2CppExplorerResult =
        il2cppExplorerQuery(Il2CppExplorerOperation.SEARCH_CLASSES, query, "/il2cpp/search?q=${encode(query)}")

    suspend fun il2cppFields(className: String): Il2CppExplorerResult =
        il2cppExplorerQuery(Il2CppExplorerOperation.READ_FIELDS, className, "/il2cpp/fields?class=${encode(className)}")

    suspend fun il2cppMethods(className: String): Il2CppExplorerResult =
        il2cppExplorerQuery(Il2CppExplorerOperation.READ_METHODS, className, "/il2cpp/methods?class=${encode(className)}")

    private suspend fun il2cppExplorerQuery(operation: Il2CppExplorerOperation, query: String, endpoint: String): Il2CppExplorerResult {
        val result = get(endpoint)
        return Il2CppExplorerResult(operation, query, endpoint, result.statusCode, result.body, result.error, System.currentTimeMillis())
    }

    suspend fun snapshot(): HlpatchResult = get("/debug/ramen_planner_state")
    suspend fun md5Log(): HlpatchResult = get("/api/md5log")
    suspend fun mdbRaw(sql: String): HlpatchResult = get("/mdb/raw?sql=${encode(sql)}")

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
            val conn = (java.net.URL(baseUrl + path).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3_000
                readTimeout = 10_000
                if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            db.hlpatchSnapshots().upsert(HlpatchSnapshotEntity(UUID.randomUUID().toString(), path, respBody, code, System.currentTimeMillis()))
            state = if (code in 200..299) ConnectionState.READY else ConnectionState.DEGRADED
            HlpatchResult(code in 200..299, code, respBody, null)
        } catch (e: Exception) {
            state = ConnectionState.DISCONNECTED
            Log.w("HlpatchClient", "request $path failed: ${e.message}")
            HlpatchResult(false, 0, "", e.stackTraceToString())
        }
    }

    fun observeSnapshots(): Flow<List<HlpatchSnapshotEntity>> = db.hlpatchSnapshots().observeRecent()
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

data class HlpatchResult(val ok: Boolean, val statusCode: Int, val body: String, val error: String?) {
    companion object { fun ok(body: String) = HlpatchResult(true, 200, body, null); fun error(msg: String) = HlpatchResult(false, 0, "", msg) }
}
