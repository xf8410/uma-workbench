package com.uma.workbench.hlpatch

/** The operation selected in the IL2CPP explorer. */
enum class Il2CppExplorerOperation {
    SEARCH_CLASSES,
    READ_FIELDS,
    READ_METHODS
}

/**
 * Complete result of one local hlpatch IL2CPP query. Raw response and error text are retained
 * unchanged so malformed or version-specific payloads remain available for diagnosis.
 */
data class Il2CppExplorerResult(
    val operation: Il2CppExplorerOperation,
    val query: String,
    val endpoint: String,
    val statusCode: Int,
    val responseBody: String,
    val error: String?,
    val completedAt: Long
) {
    val succeeded: Boolean get() = statusCode in 200..299 && error == null
}

data class Il2CppExplorerState(
    val running: Boolean = false,
    val result: Il2CppExplorerResult? = null
)

/** Builds a complete, copyable diagnostic view without parsing away unknown response fields. */
object Il2CppExplorerPresentation {
    fun render(result: Il2CppExplorerResult): String = buildString {
        appendLine("操作：${result.operation.name}")
        append("查询：")
        append(result.query)
        append('\n')
        append("端点：")
        append(result.endpoint)
        append('\n')
        appendLine("HTTP：${result.statusCode}")
        appendLine("完成时间：${result.completedAt}")
        append("完整响应体：")
        append(result.responseBody)
        append('\n')
        append("完整错误：")
        append(result.error ?: "")
        append('\n')
    }
}
