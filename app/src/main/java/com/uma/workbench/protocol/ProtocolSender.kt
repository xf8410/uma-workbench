package com.uma.workbench.protocol

import android.content.Context
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.hlpatch.HlpatchClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
 */
class ProtocolSender(
    private val context: Context,
    private val db: AppDatabase,
    private val hlpatchClient: HlpatchClient
) {
    private val _logs = MutableStateFlow<List<ProtocolLogEntry>>(emptyList())
    val logs: StateFlow<List<ProtocolLogEntry>> = _logs

    /** 通道1：OkHttp 直发 */
    suspend fun sendDirect(
        url: String,
        request: GameRequest,
        onProgress: (String) -> Unit = {}
    ): GameResponse = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            onProgress("正在发送（直连）…")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("User-Agent", "UnityPlayer/2022.3.62f2")
                doOutput = true
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (request.sid != null) setRequestProperty("SID", request.sid)
                if (request.viewerId != null) setRequestProperty("ViewerID", request.viewerId.toString())
            }
            conn.outputStream.use { it.write(request.body.toByteArray()) }
            val code = conn.responseCode
            val respHeaders = conn.headerFields.entries.filter { it.key != null }
                .associate { it.key to it.value.first() }
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val protoCode = ProtocolStatusCode.fromCode(code)
            val resp = GameResponse(
                statusCode = code, protocolCode = protoCode,
                headers = respHeaders, body = respBody, bodyDecrypted = null,
                latencyMs = System.currentTimeMillis() - start,
                timestamp = System.currentTimeMillis(),
                success = code in 200..299 && protoCode == ProtocolStatusCode.OK
            )
            _logs.value = _logs.value + ProtocolLogEntry(System.currentTimeMillis(), request, resp, null, SendChannel.OKHTTP_DIRECT)
            onProgress(if (resp.success) "成功 (${resp.latencyMs}ms)" else "失败: ${protoCode.label}")
            resp
        } catch (e: Exception) {
            val resp = GameResponse(0, ProtocolStatusCode.UNKNOWN, emptyMap(), "", null, System.currentTimeMillis() - start, System.currentTimeMillis(), false)
            _logs.value = _logs.value + ProtocolLogEntry(System.currentTimeMillis(), request, null, e.message, SendChannel.OKHTTP_DIRECT)
            onProgress("错误: ${e.message}")
            resp
        }
    }

    /** 通道2：自定义 TLS — 仿 BoringSSL 指纹 */
    suspend fun sendCustomTls(
        url: String,
        request: GameRequest,
        onProgress: (String) -> Unit = {}
    ): GameResponse = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            onProgress("正在发送（自定义TLS）…")
            val sslContext = SSLContext.getInstance("TLSv1.2").apply {
                init(null, arrayOf<TrustManager>(BoringTrustManager()), null)
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("User-Agent", "UnityPlayer/2022.3.62f2")
                doOutput = true
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (request.sid != null) setRequestProperty("SID", request.sid)
                if (request.viewerId != null) setRequestProperty("ViewerID", request.viewerId.toString())
            }
            if (conn is javax.net.ssl.HttpsURLConnection) {
                conn.sslSocketFactory = createBoringSslSocketFactory()
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.outputStream.use { it.write(request.body.toByteArray()) }
            val code = conn.responseCode
            val respHeaders = conn.headerFields.entries.filter { it.key != null }
                .associate { it.key to it.value.first() }
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val protoCode = ProtocolStatusCode.fromCode(code)
            val resp = GameResponse(
                statusCode = code, protocolCode = protoCode,
                headers = respHeaders, body = respBody, bodyDecrypted = null,
                latencyMs = System.currentTimeMillis() - start,
                timestamp = System.currentTimeMillis(),
                success = code in 200..299 && protoCode == ProtocolStatusCode.OK
            )
            _logs.value = _logs.value + ProtocolLogEntry(System.currentTimeMillis(), request, resp, null, SendChannel.OKHTTP_CUSTOM_TLS)
            onProgress(if (resp.success) "成功 (${resp.latencyMs}ms)" else "失败: ${protoCode.label}")
            resp
        } catch (e: Exception) {
            val resp = GameResponse(0, ProtocolStatusCode.UNKNOWN, emptyMap(), "", null, System.currentTimeMillis() - start, System.currentTimeMillis(), false)
            _logs.value = _logs.value + ProtocolLogEntry(System.currentTimeMillis(), request, null, e.message, SendChannel.OKHTTP_CUSTOM_TLS)
            onProgress("错误: ${e.message}")
            resp
        }
    }

    /** 通道3：hlpatch 转发 — 游戏原生 TLS 栈 */
    suspend fun sendViaHlpatch(
        request: GameRequest,
        onProgress: (String) -> Unit = {}
    ): GameResponse = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            onProgress("正在发送（hlpatch转发）…")
            // 构造转发请求给 hlpatch 的 /api/proxy 端点
            val proxyBody = buildString {
                append("{\"endpoint\":\"${request.endpoint.path}\"")
                request.sid?.let { append(",\"sid\":\"$it\"") }
                request.viewerId?.let { append(",\"viewer_id\":$it") }
                append(",\"body\":\"${request.body}\"")
                append("}")
            }
            val result = hlpatchClient.post("/api/proxy", proxyBody)
            val resp = if (result.ok) {
                GameResponse(
                    statusCode = result.statusCode,
                    protocolCode = ProtocolStatusCode.OK,
                    headers = emptyMap(),
                    body = result.body,
                    bodyDecrypted = result.body,
                    latencyMs = System.currentTimeMillis() - start,
                    timestamp = System.currentTimeMillis(),
                    success = true
                )
            } else {
                GameResponse(
                    statusCode = result.statusCode,
                    protocolCode = ProtocolStatusCode.UNKNOWN,
                    headers = emptyMap(),
                    body = result.body,
                    bodyDecrypted = null,
                    latencyMs = System.currentTimeMillis() - start,
                    timestamp = System.currentTimeMillis(),
                    success = false
                )
            }
            _logs.value = _logs.value + ProtocolLogEntry(System.currentTimeMillis(), request, resp, if (!result.ok) result.error else null, SendChannel.HLPATCH_PROXY)
            onProgress(if (resp.success) "成功 (${resp.latencyMs}ms)" else "失败: ${result.error ?: result.statusCode}")
            resp
        } catch (e: Exception) {
            val resp = GameResponse(0, ProtocolStatusCode.UNKNOWN, emptyMap(), "", null, System.currentTimeMillis() - start, System.currentTimeMillis(), false)
            _logs.value = _logs.value + ProtocolLogEntry(System.currentTimeMillis(), request, null, e.message, SendChannel.HLPATCH_PROXY)
            onProgress("错误: ${e.message}")
            resp
        }
    }

    /** 统一发送接口：按通道分发 */
    suspend fun send(
        url: String,
        request: GameRequest,
        channel: SendChannel,
        onProgress: (String) -> Unit = {}
    ): GameResponse = when (channel) {
        SendChannel.OKHTTP_DIRECT -> sendDirect(url, request, onProgress)
        SendChannel.OKHTTP_CUSTOM_TLS -> sendCustomTls(url, request, onProgress)
        SendChannel.HLPATCH_PROXY -> sendViaHlpatch(request, onProgress)
    }

    private fun createBoringSslSocketFactory(): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLSv1.2").apply {
            init(null, arrayOf<TrustManager>(BoringTrustManager()), null)
        }
        // 覆盖默认 Cipher Suites 顺序以仿 BoringSSL
        return object : SSLSocketFactory() {
            private val delegate = sslContext.socketFactory
            override fun getDefaultCipherSuites() = BORING_CIPHER_SUITES
            override fun getSupportedCipherSuites() = BORING_CIPHER_SUITES
            override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean) =
                (delegate.createSocket(s, host, port, autoClose) as javax.net.ssl.SSLSocket).apply {
                    enabledCipherSuites = BORING_CIPHER_SUITES
                    enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
                }
            override fun createSocket(host: String, port: Int) =
                (delegate.createSocket(host, port) as javax.net.ssl.SSLSocket).apply { enabledCipherSuites = BORING_CIPHER_SUITES }
            override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) =
                (delegate.createSocket(host, port, localHost, localPort) as javax.net.ssl.SSLSocket).apply { enabledCipherSuites = BORING_CIPHER_SUITES }
            override fun createSocket(host: java.net.InetAddress, port: Int) =
                (delegate.createSocket(host, port) as javax.net.ssl.SSLSocket).apply { enabledCipherSuites = BORING_CIPHER_SUITES }
            override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int) =
                (delegate.createSocket(address, port, localAddress, localPort) as javax.net.ssl.SSLSocket).apply { enabledCipherSuites = BORING_CIPHER_SUITES }
        }
    }

    companion object {
        /** BoringSSL 常用 Cipher Suites 顺序 */
        val BORING_CIPHER_SUITES = arrayOf(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
        )
    }
}

/** 信任所有证书（用于抓包调试，不用于生产） */
private class BoringTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
}
