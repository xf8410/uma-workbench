package com.uma.workbench.memory

enum class MemoryPriority { SYSTEM_SAFETY, PROJECT_RULE, TASK_RULE, RELATED_EVIDENCE, ORDINARY }
data class MemoryRecord(val id: String, val text: String, val priority: MemoryPriority, val version: String, val project: String?, val appliesToVersion: String?, val expiresWhen: String?)
data class MemoryLoadEvent(val conversationId: String, val loadedIds: List<String>, val conflicts: List<String>, val failed: Boolean, val createdAt: Long)

interface MemoryLoader {
    suspend fun loadForConversation(conversationId: String, project: String?, version: String?): MemoryLoadResult
}
data class MemoryLoadResult(val memories: List<MemoryRecord>, val conflicts: List<String>, val event: MemoryLoadEvent, val error: String? = null)
