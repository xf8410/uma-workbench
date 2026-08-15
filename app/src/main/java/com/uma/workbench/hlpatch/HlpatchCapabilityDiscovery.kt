package com.uma.workbench.hlpatch

/** One real local hlpatch endpoint observation. Complete bodies and errors are retained. */
data class HlpatchEndpointCapability(
    val path: String,
    val required: Boolean,
    val supported: Boolean,
    val statusCode: Int,
    val responseBody: String,
    val error: String?
)

enum class HlpatchCompatibility {
    NOT_CHECKED,
    COMPATIBLE,
    DEGRADED,
    INCOMPATIBLE,
    UNREACHABLE
}

data class HlpatchCapabilityReport(
    val running: Boolean = false,
    val checkedAt: Long? = null,
    val compatibility: HlpatchCompatibility = HlpatchCompatibility.NOT_CHECKED,
    val endpoints: List<HlpatchEndpointCapability> = emptyList()
) {
    val summary: String
        get() = when {
            running -> "正在检测本机 hlpatch 能力"
            compatibility == HlpatchCompatibility.NOT_CHECKED -> "尚未检测 hlpatch 能力"
            else -> "兼容状态：${compatibility.name}；支持 ${endpoints.count { it.supported }}/${endpoints.size} 个已检测端点"
        }
}

/**
 * Classifies observations without shortening response bodies, errors, or endpoint records.
 * A reachable health endpoint with a missing required summary endpoint is incompatible; optional
 * endpoint differences are degraded but do not erase otherwise available capabilities.
 */
object HlpatchCapabilityClassifier {
    fun classify(endpoints: List<HlpatchEndpointCapability>): HlpatchCompatibility {
        val health = endpoints.firstOrNull { it.path == "/health" }
        if (health == null || (!health.supported && health.statusCode == 0)) return HlpatchCompatibility.UNREACHABLE
        if (!health.supported) return HlpatchCompatibility.INCOMPATIBLE
        if (endpoints.any { it.required && !it.supported }) return HlpatchCompatibility.INCOMPATIBLE
        return if (endpoints.all { it.supported }) HlpatchCompatibility.COMPATIBLE else HlpatchCompatibility.DEGRADED
    }

    /**
     * Returns the complete presentation without trimEnd/trim so trailing whitespace that belongs to
     * a response body or stack trace is not silently removed. Structural newlines are added only
     * between labels and records; endpoint values themselves are appended unchanged.
     */
    fun presentation(report: HlpatchCapabilityReport): String = buildString {
        appendLine(report.summary)
        report.checkedAt?.let { appendLine("检测时间：$it") }
        report.endpoints.forEach { endpoint ->
            appendLine("端点：${endpoint.path}")
            appendLine("必需：${endpoint.required}")
            appendLine("支持：${endpoint.supported}")
            appendLine("HTTP：${endpoint.statusCode}")
            append("完整响应体：")
            append(endpoint.responseBody)
            append('\n')
            append("完整错误：")
            append(endpoint.error ?: "")
            append('\n')
        }
    }
}
