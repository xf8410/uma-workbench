package com.uma.workbench.protocol

import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.EndpointCatalogEntity
import java.util.UUID

/**
 * 阶段18：游戏端点目录（endpoint_catalog）。
 *
 * 把每次协议请求/响应归纳进目录；同一 workspace 下相同 path 的端点合并
 * （callCount + 1、lastSeen 刷新），便于后续做端点清单、版本对比和证据关联。
 */
class EndpointCatalogRecorder(private val db: AppDatabase) {

    suspend fun record(
        request: GameRequest,
        response: GameResponse?,
        workspaceId: String?,
        gameVersion: String?
    ) {
        val now = System.currentTimeMillis()
        val path = request.rawEndpoint.ifBlank { request.endpoint.path }
        if (path.isBlank()) return

        // 作用域：优先按工作区归档；无工作区时也用空字符串占位，保证可查询。
        val scope = workspaceId ?: ""

        val existing = db.endpointCatalog().find(scope, path)
        if (existing != null) {
            db.endpointCatalog().touch(existing.id, now)
            return
        }

        db.endpointCatalog().upsert(
            EndpointCatalogEntity(
                id = UUID.randomUUID().toString(),
                workspaceId = scope,
                scheme = null,
                host = request.headers["host"],
                method = "POST",
                path = path,
                queryParams = null,
                headerNames = request.headerEntries.joinToString(",") { it.name }.ifBlank { null },
                jsonFields = jsonKeys(request.body),
                statusCode = response?.statusCode,
                firstSeen = now,
                lastSeen = now,
                callCount = 1,
                gameVersion = gameVersion,
                evidenceSource = "hlpatch",
                confidence = "DERIVED"
            )
        )
    }

    /**
     * 从请求体提取**顶层** JSON 键名，用于端点目录的 jsonFields。
     * 只抓对象最外层的 `"key":`，跳过嵌套对象/数组里的键。无法解析返回 null。
     */
    companion object {
        internal fun jsonKeys(body: String): String? {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
            val keys = mutableListOf<String>()
            var depth = 0
            var i = 0
            while (i < trimmed.length) {
                when (trimmed[i]) {
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                    '"' -> {
                        if (depth == 1) {
                            val endQuote = trimmed.indexOf('"', i + 1)
                            if (endQuote > i) {
                                val after = trimmed.substring(endQuote + 1).trimStart()
                                if (after.startsWith(":")) {
                                    val key = trimmed.substring(i + 1, endQuote)
                                    if (key.matches(Regex("[A-Za-z0-9_]+"))) keys.add(key)
                                }
                                i = endQuote
                            }
                        }
                    }
                }
                i++
            }
            return keys.distinct().take(32).joinToString(",").ifBlank { null }
        }
    }
}
