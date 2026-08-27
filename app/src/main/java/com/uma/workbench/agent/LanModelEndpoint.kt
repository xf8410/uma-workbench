package com.uma.workbench.agent

import android.content.Context
import java.net.InetAddress
import java.net.URL
import kotlinx.serialization.Serializable

/**
 * Configuration for a self-hosted model server on the local network (feature: 局域网自托管模型连接).
 *
 * Unlike [AiProviderProfile], this allows plain HTTP for private network addresses
 * (192.168.x.x, 10.x.x.x, 172.16-31.x.x, localhost, 127.0.0.1).
 * Cloud endpoints still require HTTPS.
 */
@Serializable
data class LanModelEndpoint(
    val baseUrl: String = "",
    val model: String = "",
    val authToken: String = "",
    val label: String = "局域网模型"
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    fun chatUrl(): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/chat/completions")) base
        else "$base/v1/chat/completions"
    }

    fun modelsUrl(): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/models")) base
        else "$base/v1/models"
    }

    fun validate() {
        require(baseUrl.isNotBlank()) { "局域网模型地址不能为空" }
        require(model.isNotBlank()) { "模型名称不能为空" }
        val url = runCatching { URL(baseUrl) }.getOrElse {
            throw IllegalArgumentException("无效的 URL：$baseUrl")
        }
        val host = url.host
        when {
            url.protocol == "https" -> { /* HTTPS is always allowed */ }
            url.protocol == "http" && isPrivateNetworkAddress(host) -> { /* HTTP allowed for LAN */ }
            url.protocol == "http" -> throw IllegalArgumentException("HTTP 仅允许用于局域网地址（192.168.x.x / 10.x.x.x / 172.16-31.x.x / localhost）")
            else -> throw IllegalArgumentException("不支持的协议：${url.protocol}，请使用 http:// 或 https://")
        }
    }

    companion object {
        /**
         * Checks whether the given host is a private network address where HTTP is acceptable.
         */
        fun isPrivateNetworkAddress(host: String): Boolean {
            return when {
                host == "localhost" || host == "127.0.0.1" || host == "::1" -> true
                host.startsWith("192.168.") -> true
                host.startsWith("10.") -> true
                host.startsWith("172.") -> {
                    val second = host.substringAfter("172.").substringBefore('.').toIntOrNull()
                    second != null && second in 16..31
                }
                host.endsWith(".local") -> true
                else -> {
                    // Try to resolve and check if it's a private IP
                    runCatching {
                        val addr = InetAddress.getByName(host)
                        addr.isSiteLocalAddress || addr.isLoopbackAddress
                    }.getOrDefault(false)
                }
            }
        }
    }
}

/**
 * Persists [LanModelEndpoint] configuration using SharedPreferences.
 * The auth token is stored in plain text (it's for a LAN server, not a cloud credential).
 * If higher security is needed, wrap with AndroidKeyStore encryption like [AiProviderSettingsStore].
 */
class LanModelSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lan-model-settings", Context.MODE_PRIVATE)

    fun load(): LanModelEndpoint = LanModelEndpoint(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
        authToken = prefs.getString(KEY_AUTH_TOKEN, "").orEmpty(),
        label = prefs.getString(KEY_LABEL, "局域网模型").orEmpty()
    )

    fun save(endpoint: LanModelEndpoint) {
        endpoint.validate()
        prefs.edit()
            .putString(KEY_BASE_URL, endpoint.baseUrl.trim())
            .putString(KEY_MODEL, endpoint.model.trim())
            .putString(KEY_AUTH_TOKEN, endpoint.authToken.trim())
            .putString(KEY_LABEL, endpoint.label.ifBlank { "局域网模型" })
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_LABEL = "label"
    }
}
