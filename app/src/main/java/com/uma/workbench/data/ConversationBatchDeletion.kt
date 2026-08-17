package com.uma.workbench.data

import androidx.room.withTransaction

/** Result of one atomic database batch deletion. */
data class ConversationBatchDeleteResult(
    val conversationIds: Set<String>,
    val deletedMessages: Int
)

/**
 * Soft-deletes conversations so existing history queries stop exposing them, while hard-deleting
 * their messages in the same Room transaction. Workspace ownership is verified before mutation.
 * Workspace files and artifacts are deliberately not deleted.
 */
suspend fun AppDatabase.deleteConversationsAtomically(
    workspaceId: String,
    conversationIds: Collection<String>,
    now: Long = System.currentTimeMillis()
): ConversationBatchDeleteResult = withTransaction {
    val requested = conversationIds.filter { it.isNotBlank() }.toSet()
    require(requested.isNotEmpty()) { "至少选择一个对话" }
    val placeholders = requested.joinToString(",") { "?" }
    val queryArgs = arrayOf(workspaceId, *requested.toTypedArray())
    val sql = "SELECT id FROM conversations WHERE workspaceId = ? AND status != 'DELETED' AND id IN ($placeholders)"
    val allowed = linkedSetOf<String>()
    openHelper.writableDatabase.query(sql, queryArgs).use { cursor ->
        while (cursor.moveToNext()) allowed += cursor.getString(0)
    }
    require(allowed == requested) { "选择中包含不存在、已删除或不属于当前工作区的对话" }

    val countSql = "SELECT COUNT(*) FROM messages WHERE conversationId IN ($placeholders)"
    val deletedMessages = openHelper.writableDatabase.query(countSql, requested.toTypedArray()).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }
    openHelper.writableDatabase.execSQL(
        "DELETE FROM messages WHERE conversationId IN ($placeholders)",
        requested.map { it as Any }.toTypedArray()
    )
    openHelper.writableDatabase.execSQL(
        "UPDATE conversations SET status = 'DELETED', updatedAt = ? WHERE workspaceId = ? AND id IN ($placeholders)",
        arrayOf<Any>(now, workspaceId, *requested.toTypedArray())
    )
    ConversationBatchDeleteResult(requested, deletedMessages)
}
