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

    /** 从请求体粗略提取顶层 JSON 键名，用于端点目录的 jsonFields；无法解析返回 null。 */
    companion object {
        internal fun jsonKeys(body: String): String? {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
            return Regex("\"([A-Za-z0-9_]+)\"\\s*:")
                .findAll(trimmed)
                .map { it.groupValues[1] }
                .distinct()
                .take(32)
                .joinToString(",")
                .ifBlank { null }
        }
    }
}
