package com.uma.workbench.protocol

/**
 * UI state for one SID validity check. [checkedSid] is always the exact user input; it is never
 * shortened, masked, or replaced. A completed [result] retains the complete session and response.
 */
data class SidHealthCheckState(
    val running: Boolean = false,
    val checkedSid: String = "",
    val viewerId: Long? = null,
    val result: SidHealthResult? = null,
    val error: String? = null
)

/** Pure presentation helpers shared by Compose and unit tests. */
object SidHealthPresentation {
    fun statusText(state: SidHealthCheckState): String = when {
        state.running -> "正在检测 SID 有效性…"
        state.error != null -> "检测未完成：${state.error}"
        state.result != null -> buildString {
            append("诊断：${state.result.diagnosis.label}；${state.result.explanation}")
            state.result.httpStatus?.let { append("；HTTP $it") }
            state.result.protocolCode?.let { append("；协议码 $it") }
        }
        else -> "尚未检测"
    }

    fun loginChainText(result: SidHealthResult): String {
        val next = LoginChainPlanner.next(result) ?: return "登录链建议：当前 SID 可用，无需重新登录。"
        val remaining = LoginChainPlanner.remainingFrom(next)
        return "登录链建议：${result.suggestedAction} 后续端点：${remaining.joinToString(" → ") { it.path }}"
    }
}
