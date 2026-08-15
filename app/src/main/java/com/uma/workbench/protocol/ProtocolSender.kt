package com.uma.workbench.protocol

import android.content.Context
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.hlpatch.HlpatchClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 育成协议请求发送器。三条通道：
 * 1. OkHttp 直发 — 不查指纹的接口（社团采集、排行榜）
 * 2. 自定义 TLS — 调 Cipher Suites 仿 BoringSSL
 * 3. hlpatch 转发 — 游戏在线时用原生栈
 *
 * Every completed attempt is persisted with all original request/response fields before it is
 * published to [logs]. Persistence does not cap rows or body sizes and does not filter headers.
 */
class ProtocolSender(
    context: Context,
    @Suppress("UNUSED_PARAMETER") db: AppDatabase,
    private val hlpatchClient: HlpatchClient,
    historyStore: ProtocolHistoryStore = ProtocolHistoryStore(context)
) {
    private val historyRecorder = ProtocolHistoryRecorder { historyStore.append(it) }
    val logs: StateFlow<List<ProtocolLogEntry>> = historyRecorder.entries

    suspend fun sendDirect(url: String, request: GameRequest, onProgress: (String) -> Unit = {}): GameResponse =
        sendHttp(url, request, SendChannel.OKHTTP_DIRECT, null, onProgress)

    suspend fun sendCustomTls(url: String, request: GameRequest, onProgress: (String) -> Unit = {}): GameResponse =
        sendHttp(url, request, SendChannel.OKHTTP_CUSTOM_TLS, createBoringSslSocketFactory(), onProgress)

    private suspend fun sendHttp(
        url: String,
        request: GameRequest,
        channel: SendChannel,
        sslSocketFactory: SSLSocketFactory?,
        onProgress: (String) -> Unit
    ): GameResponse = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            onProgress("正在发送…")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("User-Agent", "UnityPlayer/2022.3.62f2")
                doOutput = true
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                request.sid?.let { setRequestProperty("SID", it) }
                request.viewerId?.let { setRequestProperty("ViewerID", it.toString()) }
            }
            if (sslSocketFactory != null && conn is javax.net.ssl.HttpsURLConnection) {
                conn.sslSocketFactory = sslSocketFactory
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val respHeaders = conn.headerFields.entries.filter { it.key != null }
                .associate { it.key to it.value.joinToString(",") }
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val interpreted = ProtocolResponseInterpreter.interpret(code, respBody)
            val resp = GameResponse(
                statusCode = code,
                protocolCode = ProtocolStatusCode.fromCode(interpreted.protocolCode),
                headers = respHeaders,
                body = respBody,
                bodyDecrypted = null,
                latencyMs = System.currentTimeMillis() - start,
                timestamp = System.currentTimeMillis(),
                success = code in 200..299 && interpreted.protocolCode == 200
            )
            record(ProtocolLogEntry(System.currentTimeMillis(), request, resp, null, channel))
            onProgress(if (resp.success) "成功 (${resp.latencyMs}ms)" else "失败: ${interpreted.diagnosis.title}")
            resp
        } catch (e: Exception) {
            val resp = GameResponse(0, ProtocolStatusCode.UNKNOWN, emptyMap(), "", null, System.currentTimeMillis() - start, System.currentTimeMillis(), false)
            record(ProtocolLogEntry(System.currentTimeMillis(), request, null, e.message, channel))
            onProgress("错误: ${e.message}")
            resp
        }
    }

    suspend fun sendViaHlpatch(request: GameRequest, onProgress: (String) -> Unit = {}): GameResponse = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            onProgress("正在发送（hlpatch转发）…")
            val result = hlpatchClient.post("/api/proxy", HlpatchProxyEnvelopeCodec.encodeRequest(request))
            val envelope = if (result.ok) runCatching { HlpatchProxyEnvelopeCodec.decodeResponse(result.body) }.getOrNull() else null
            val resp = when {
                envelope != null -> envelope.toGameResponse(System.currentTimeMillis())
                result.ok -> {
                    val interpreted = ProtocolResponseInterpreter.interpret(result.statusCode, result.body)
                    GameResponse(result.statusCode, ProtocolStatusCode.fromCode(interpreted.protocolCode), emptyMap(), result.body, result.body, System.currentTimeMillis() - start, System.currentTimeMillis(), interpreted.protocolCode == 200)
                }
                else -> GameResponse(result.statusCode, ProtocolStatusCode.UNKNOWN, emptyMap(), result.body, null, System.currentTimeMillis() - start, System.currentTimeMillis(), false)
            }
            val error = envelope?.error ?: if (!result.ok) result.error else null
            record(ProtocolLogEntry(System.currentTimeMillis(), request, resp, error, SendChannel.HLPATCH_PROXY))
            onProgress(if (resp.success) "成功 (${resp.latencyMs}ms)" else "失败: ${error ?: resp.protocolCode.label}")
            resp
        } catch (e: Exception) {
            val resp = GameResponse(0, ProtocolStatusCode.UNKNOWN, emptyMap(), "", null, System.currentTimeMillis() - start, System.currentTimeMillis(), false)
            record(ProtocolLogEntry(System.currentTimeMillis(), request, null, e.message, SendChannel.HLPATCH_PROXY))
            onProgress("错误: ${e.message}")
            resp
        }
    }

    suspend fun send(url: String, request: GameRequest, channel: SendChannel, onProgress: (String) -> Unit = {}): GameResponse = when (channel) {
        SendChannel.OKHTTP_DIRECT -> sendDirect(url, request, onProgress)
        SendChannel.OKHTTP_CUSTOM_TLS -> sendCustomTls(url, request, onProgress)
        SendChannel.HLPATCH_PROXY -> sendViaHlpatch(request, onProgress)
    }

    private suspend fun record(entry: ProtocolLogEntry) = historyRecorder.record(entry)

    private fun createBoringSslSocketFactory(): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLSv1.2").apply { init(null, arrayOf<TrustManager>(BoringTrustManager()), null) }
        return object : SSLSocketFactory() {
            private val delegate = sslContext.socketFactory
            override fun getDefaultCipherSuites() = BORING_CIPHER_SUITES
            override fun getSupportedCipherSuites() = BORING_CIPHER_SUITES
            override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean) = (delegate.createSocket(s, host, port, autoClose) as javax.net.ssl.SSLSocket).apply { enabledCipherSuites = BORING_CIPHER_SUITES; enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3") }
            override fun createSocket(host: String, port: Int) = (delegate.createSocket(host, port) as javax.net.ssl.SSLSocket).apply { enabledCipherSuites = BORING_CIPHER_SUITES }
            override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) = (delegate.createSocket(host, port, localHost, localPort) as javax.net.ssl.SSLSocket).apply { enabledCipherSuites = BORING_CIPHER_SUITES }
            override fun createSocket(host: java.net.InetAddress, port: Int) = (delegate.createSocket(host, port) as javax.net.ssl.SSLSocket).apply { enabledCipherSuites = BORING_CIPHER_SUITES }
            override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int) = (delegate.createSocket(address, port, localAddress, localPort) as javax.net.ssl.SSLSocket).apply { enabledCipherSuites = BORING_CIPHER_SUITES }
        }
    }

    companion object {
        val BORING_CIPHER_SUITES = arrayOf(
            "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
        )
    }
}

private class BoringTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
}
