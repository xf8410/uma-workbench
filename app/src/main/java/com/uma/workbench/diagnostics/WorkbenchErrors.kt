package com.uma.workbench.diagnostics

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException

/** Stable Uma Workbench codes. These do not inherit Agora error names or messages. */
enum class WorkbenchErrorCode(val stableCode: String) {
    NETWORK_OFFLINE("WB-NET-001"),
    NETWORK_DNS_FAILURE("WB-NET-002"),
    NETWORK_CONNECT_FAILED("WB-NET-003"),
    NETWORK_CONNECTION_LOST("WB-NET-004"),
    NETWORK_TIMEOUT("WB-NET-005"),
    NETWORK_TLS_FAILED("WB-NET-006"),
    AI_AUTHENTICATION_FAILED("WB-AI-001"),
    AI_PERMISSION_DENIED("WB-AI-002"),
    AI_RATE_LIMITED("WB-AI-003"),
    AI_QUOTA_EXHAUSTED("WB-AI-004"),
    AI_MODEL_OR_ENDPOINT_NOT_FOUND("WB-AI-005"),
    AI_REQUEST_TOO_LARGE("WB-AI-006"),
    AI_SERVER_UNAVAILABLE("WB-AI-007"),
    AI_RESPONSE_FORMAT_INVALID("WB-AI-008"),
    UNKNOWN("WB-SYS-001")
}

data class UserFacingError(
    val code: WorkbenchErrorCode,
    val title: String,
    val explanation: String,
    val impact: String,
    val suggestedAction: String,
    val retryable: Boolean,
    val diagnosticId: String
) {
    /** Safe for normal UI. Never contains Throwable.message or a stack trace. */
    val displayText: String
        get() = buildString {
            append(title)
            append("\n\n")
            append(explanation)
            append("\n\n")
            append(impact)
            append("\n\n建议：")
            append(suggestedAction)
            append("\n诊断编号：")
            append(diagnosticId)
            append("（")
            append(code.stableCode)
            append('）')
        }
}

/** Structured provider failure; raw response remains diagnostic-only. */
class AiHttpException(
    val statusCode: Int,
    val responseBody: String,
    val providerName: String? = null
) : IOException("AI provider HTTP $statusCode")

data class MappedWorkbenchError(
    val userFacing: UserFacingError,
    val diagnosticCause: Throwable
)

object WorkbenchErrorMapper {
    fun map(error: Throwable, partialCharacters: Int = 0): MappedWorkbenchError {
        val code = classify(error)
        val diagnosticId = "${code.stableCode}-${UUID.randomUUID().toString().take(8).uppercase()}"
        return MappedWorkbenchError(presentation(code, partialCharacters, diagnosticId), error)
    }

    private fun classify(error: Throwable): WorkbenchErrorCode = when (error) {
        is AiHttpException -> when (error.statusCode) {
            401 -> WorkbenchErrorCode.AI_AUTHENTICATION_FAILED
            402 -> WorkbenchErrorCode.AI_QUOTA_EXHAUSTED
            403 -> WorkbenchErrorCode.AI_PERMISSION_DENIED
            404 -> WorkbenchErrorCode.AI_MODEL_OR_ENDPOINT_NOT_FOUND
            408 -> WorkbenchErrorCode.NETWORK_TIMEOUT
            413 -> WorkbenchErrorCode.AI_REQUEST_TOO_LARGE
            429 -> WorkbenchErrorCode.AI_RATE_LIMITED
            in 500..599 -> WorkbenchErrorCode.AI_SERVER_UNAVAILABLE
            else -> WorkbenchErrorCode.UNKNOWN
        }
        is UnknownHostException -> WorkbenchErrorCode.NETWORK_DNS_FAILURE
        is SocketTimeoutException -> WorkbenchErrorCode.NETWORK_TIMEOUT
        is SSLException -> WorkbenchErrorCode.NETWORK_TLS_FAILED
        is ConnectException -> WorkbenchErrorCode.NETWORK_CONNECT_FAILED
        is SocketException -> WorkbenchErrorCode.NETWORK_CONNECTION_LOST
        is SerializationException -> WorkbenchErrorCode.AI_RESPONSE_FORMAT_INVALID
        is IOException -> WorkbenchErrorCode.NETWORK_CONNECTION_LOST
        else -> WorkbenchErrorCode.UNKNOWN
    }

    private fun presentation(code: WorkbenchErrorCode, partial: Int, id: String): UserFacingError {
        val saved = if (partial > 0) "已收到的 $partial 个字符仍然保留。" else "当前输入和对话记录没有丢失。"
        return when (code) {
            WorkbenchErrorCode.NETWORK_OFFLINE -> user(code, "当前设备没有可用网络", "软件暂时无法连接 AI 服务。", saved, "连接网络后继续。", true, id)
            WorkbenchErrorCode.NETWORK_DNS_FAILURE -> user(code, "无法找到 AI 服务器地址", "域名解析失败，可能是网络、DNS 或服务地址配置问题。", saved, "检查网络和服务地址后重试。", true, id)
            WorkbenchErrorCode.NETWORK_CONNECT_FAILED -> user(code, "无法连接 AI 服务器", "服务器、代理或当前网络拒绝了连接。", saved, "检查服务状态和代理设置后重试。", true, id)
            WorkbenchErrorCode.NETWORK_CONNECTION_LOST -> user(code, "网络连接在回复过程中断开", "连接尚未正常结束，后续内容可能还没有接收。", saved, "保持当前对话，网络恢复后继续回复，不要重复发送原问题。", true, id)
            WorkbenchErrorCode.NETWORK_TIMEOUT -> user(code, "等待 AI 服务响应超时", "连接或读取数据超过了允许等待时间。", saved, "确认网络稳定后继续。", true, id)
            WorkbenchErrorCode.NETWORK_TLS_FAILED -> user(code, "无法建立安全连接", "HTTPS 证书或安全连接协商失败。", saved, "检查系统时间、证书、代理和服务地址。", false, id)
            WorkbenchErrorCode.AI_AUTHENTICATION_FAILED -> user(code, "API 凭据无效或已经过期", "AI 服务拒绝了身份验证。", saved, "更新当前提供商凭据后重试。", false, id)
            WorkbenchErrorCode.AI_PERMISSION_DENIED -> user(code, "当前凭据无权执行此请求", "账户可能没有模型或接口权限。", saved, "检查账户权限和所选模型。", false, id)
            WorkbenchErrorCode.AI_RATE_LIMITED -> user(code, "请求过于频繁", "AI 服务暂时限制了新的请求。", saved, "稍后重试，或检查提供商限额。", true, id)
            WorkbenchErrorCode.AI_QUOTA_EXHAUSTED -> user(code, "AI 服务额度不足", "当前账户没有足够额度继续生成。", saved, "补充额度或切换提供商。", false, id)
            WorkbenchErrorCode.AI_MODEL_OR_ENDPOINT_NOT_FOUND -> user(code, "找不到所选模型或接口", "当前模型名称或服务地址与提供商不匹配。", saved, "检查模型和接口地址配置。", false, id)
            WorkbenchErrorCode.AI_REQUEST_TOO_LARGE -> user(code, "本次发送内容超过服务限制", "对话、附件或工具定义过大。", saved, "减少附件或创建新分支后重试。", false, id)
            WorkbenchErrorCode.AI_SERVER_UNAVAILABLE -> user(code, "AI 服务暂时不可用", "服务端发生故障或正在维护。", saved, "稍后继续，避免重复提交。", true, id)
            WorkbenchErrorCode.AI_RESPONSE_FORMAT_INVALID -> user(code, "AI 服务返回了无法识别的数据", "返回格式与当前流式协议配置不一致。", saved, "检查流格式和字段路径配置。", false, id)
            WorkbenchErrorCode.UNKNOWN -> user(code, "操作未能完成", "软件遇到了尚未分类的问题。", saved, "保留诊断编号并查看诊断详情。", false, id)
        }
    }

    private fun user(code: WorkbenchErrorCode, title: String, explanation: String, impact: String, action: String, retryable: Boolean, id: String) =
        UserFacingError(code, title, explanation, impact, action, retryable, id)
}
