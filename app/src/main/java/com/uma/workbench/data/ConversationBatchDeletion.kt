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
    val allowed = conversations().activeIdsInWorkspace(workspaceId, requested).toSet()
    require(allowed == requested) { "选择中包含不存在、已删除或不属于当前工作区的对话" }
    val deletedMessages = messages().deleteByConversationIds(requested)
    val changed = conversations().markDeletedInWorkspace(workspaceId, requested, now)
    check(changed == requested.size) { "批量删除对话数量不一致：期望 ${requested.size}，实际 $changed" }
    ConversationBatchDeleteResult(requested, deletedMessages)
}
