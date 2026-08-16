package com.uma.workbench.agent

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.collect

data class AiProviderConnectionTestResult(
    val model: String,
    val discoveredModelCount: Int,
    val responseReceived: Boolean,
    val completeDetail: String
)

class AiProviderConnectionTester(
    private val discovery: AiModelDiscovery = AiModelDiscovery(),
    private val providerFactory: ((() -> AiProviderProfile?) -> AiStreamingProvider) = { CatalogAiStreamingProvider(it) }
) {
    suspend fun test(profile: AiProviderProfile): AiProviderConnectionTestResult {
        profile.validate()
        val discovered = discovery.fetch(profile)
        val model = profile.models.firstOrNull { it in discovered } ?: discovered.firstOrNull()
            ?: error("模型列表连接成功，但没有可用于最小聊天测试的模型")
        var textReceived = false
        var completed = false
        providerFactory { profile.copy(models = (profile.models + discovered).distinct()) }.stream(
            AiGenerationRequest(
                requestId = "connection-test",
                messages = listOf(AiPromptMessage("user", "Reply with OK.")),
                model = model
            )
        ).collect { event ->
            if (event is AiStreamEvent.TextDelta && event.completeDelta.isNotEmpty()) textReceived = true
            if (event == AiStreamEvent.Completed) completed = true
        }
        check(textReceived || completed) { "最小聊天连接结束，但没有收到文本或完成事件" }
        return AiProviderConnectionTestResult(
            model = model,
            discoveredModelCount = discovered.size,
            responseReceived = textReceived,
            completeDetail = "模型列表 ${discovered.size} 个；最小聊天模型 $model；${if (textReceived) "已收到回复" else "已收到完成事件"}"
        )
    }
}

enum class AiConnectionFailureKind { DNS, TLS, CONNECT, TIMEOUT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, RATE_LIMITED, SERVER, RESPONSE, UNKNOWN }

data class AiConnectionFailure(val kind: AiConnectionFailureKind, val completeDetail: String)

object AiConnectionFailureClassifier {
    fun classify(error: Throwable): AiConnectionFailure {
        val chain = generateSequence(error) { it.cause }.toList()
        val detail = error.stackTraceToString()
        val http = Regex("HTTP\\s+(\\d{3})").find(chain.joinToString("\n") { it.message.orEmpty() })?.groupValues?.get(1)?.toIntOrNull()
        val kind = when {
            chain.any { it is UnknownHostException } -> AiConnectionFailureKind.DNS
            chain.any { it is SSLException } -> AiConnectionFailureKind.TLS
            chain.any { it is SocketTimeoutException } -> AiConnectionFailureKind.TIMEOUT
            chain.any { it is ConnectException } -> AiConnectionFailureKind.CONNECT
            http == 401 -> AiConnectionFailureKind.UNAUTHORIZED
            http == 403 -> AiConnectionFailureKind.FORBIDDEN
            http == 404 -> AiConnectionFailureKind.NOT_FOUND
            http == 429 -> AiConnectionFailureKind.RATE_LIMITED
            http != null && http >= 500 -> AiConnectionFailureKind.SERVER
            error is IllegalArgumentException || error is IllegalStateException -> AiConnectionFailureKind.RESPONSE
            else -> AiConnectionFailureKind.UNKNOWN
        }
        return AiConnectionFailure(kind, detail)
    }
}
