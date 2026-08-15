package com.uma.workbench.protocol

import com.uma.workbench.data.AppDatabase
import com.uma.workbench.hlpatch.HlpatchClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** SID dump 管理器：从 hlpatch /summary 和 /api/md5log 获取运行时 SID 和请求头 */
class SidDumper(
    private val db: AppDatabase,
    private val hlpatchClient: HlpatchClient,
    private val sessionManager: SessionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _dumpState = MutableStateFlow<DumpState>(DumpState.IDLE)
    val dumpState: StateFlow<DumpState> = _dumpState.asStateFlow()

    private val _lastDump = MutableStateFlow<DumpResult?>(null)
    val lastDump: StateFlow<DumpResult?> = _lastDump.asStateFlow()

    /** 从 hlpatch /summary 提取 SID 和 viewer_id */
    suspend fun dumpFromHlpatch(): DumpResult {
        _dumpState.value = DumpState.CONNECTING
        val health = hlpatchClient.health()
        if (!health.ok) {
            _dumpState.value = DumpState.FAILED
            return DumpResult(null, null, null, "hlpatch 未连接: ${health.error}")
        }

        _dumpState.value = DumpState.READING
        val summary = hlpatchClient.status()
        if (!summary.ok) {
            _dumpState.value = DumpState.FAILED
            return DumpResult(null, null, null, "读取 /summary 失败: ${summary.error}")
        }

        // 从 /summary 响应中解析 SID 和 viewer_id
        // 实际字段路径需要根据 hlpatch 返回格式调整
        val sid = extractField(summary.body, "sid") ?: extractField(summary.body, "SID")
        val viewerId = extractField(summary.body, "viewer_id")?.toLongOrNull()
        val appVer = extractField(summary.body, "app_ver") ?: "2.29.0"

        if (sid != null && viewerId != null) {
            val headers = mapOf(
                "APP-VER" to appVer,
                "SID" to sid,
                "ViewerID" to viewerId.toString()
            )
            sessionManager.importFromHlpatch(sid, viewerId, headers)
            _dumpState.value = DumpState.SUCCESS
            _lastDump.value = DumpResult(sid, viewerId, headers, null)
            return _lastDump.value!!
        }

        // /summary 没有 SID，尝试从 md5log 获取
        _dumpState.value = DumpState.READING_LOG
        val md5log = hlpatchClient.get("/api/md5log")
        if (md5log.ok) {
            val logSid = extractField(md5log.body, "sid") ?: extractField(md5log.body, "SID")
            val logViewerId = extractField(md5log.body, "viewer_id")?.toLongOrNull()
            if (logSid != null && logViewerId != null) {
                sessionManager.importFromHlpatch(logSid, logViewerId, mapOf("SID" to logSid))
                _dumpState.value = DumpState.SUCCESS
                _lastDump.value = DumpResult(logSid, logViewerId, null, null)
                return _lastDump.value!!
            }
        }

        _dumpState.value = DumpState.FAILED
        return DumpResult(null, null, null, "无法从 hlpatch 提取 SID/viewer_id")
    }

    /** 从 JSON 响应中提取字段值（简单解析，不依赖 JSON 库） */
    private fun extractField(json: String, field: String): String? {
        val patterns = listOf("\"$field\"", "'$field'", "$field:")
        for (pattern in patterns) {
            val idx = json.indexOf(pattern, ignoreCase = true)
            if (idx >= 0) {
                val after = json.substring(idx + pattern.length).trimStart(' ', ':', '"', '\'')
                val end = after.indexOfFirst { it == '"' || it == '\'' || it == ',' || it == '}' || it == '\n' }
                val value = if (end >= 0) after.substring(0, end) else after.take(64)
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    enum class DumpState { IDLE, CONNECTING, READING, READING_LOG, SUCCESS, FAILED }

    data class DumpResult(
        val sid: String?,
        val viewerId: Long?,
        val headers: Map<String, String>?,
        val error: String?
    ) {
        val success: Boolean get() = sid != null && viewerId != null
    }
}
