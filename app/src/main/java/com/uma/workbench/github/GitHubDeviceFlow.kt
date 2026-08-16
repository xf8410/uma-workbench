package com.uma.workbench.github

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Public code shown to the user while GitHub Device Flow authorization is pending. */
data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val pollingIntervalSeconds: Int
)

sealed interface GitHubDeviceTokenResult {
    data class Authorized(val token: String) : GitHubDeviceTokenResult
    data class Pending(val nextIntervalSeconds: Int) : GitHubDeviceTokenResult
    data class Failed(val message: String) : GitHubDeviceTokenResult
}

/** OAuth Device Flow transport based on the working Agora-Workbench implementation. */
class GitHubDeviceFlow(
    private val webBaseUrl: String = "https://github.com"
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestCode(clientId: String): GitHubDeviceCode = withContext(Dispatchers.IO) {
        require(clientId.isNotBlank()) { "OAuth Client ID 不能为空" }
        val response = postForm(
            "/login/device/code",
            mapOf("client_id" to clientId.trim(), "scope" to "repo workflow read:user")
        )
        require(response.status in 200..299) { "GitHub Device Flow HTTP ${response.status}: ${response.body}" }
        val value = json.parseToJsonElement(response.body).jsonObject
        GitHubDeviceCode(
            deviceCode = value.requiredString("device_code"),
            userCode = value.requiredString("user_code"),
            verificationUri = value.requiredString("verification_uri"),
            expiresInSeconds = value.requiredInt("expires_in"),
            pollingIntervalSeconds = value["interval"]?.jsonPrimitive?.intOrNull ?: 5
        )
    }

    suspend fun awaitToken(clientId: String, code: GitHubDeviceCode): String {
        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        var interval = code.pollingIntervalSeconds.coerceAtLeast(5)
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            when (val result = poll(clientId, code.deviceCode, interval)) {
                is GitHubDeviceTokenResult.Authorized -> return result.token
                is GitHubDeviceTokenResult.Pending -> interval = result.nextIntervalSeconds
                is GitHubDeviceTokenResult.Failed -> error(result.message)
            }
        }
        error("GitHub 设备验证码已过期")
    }

    internal suspend fun poll(clientId: String, deviceCode: String, interval: Int): GitHubDeviceTokenResult =
        withContext(Dispatchers.IO) {
            val response = postForm(
                "/login/oauth/access_token",
                mapOf(
                    "client_id" to clientId.trim(),
                    "device_code" to deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                )
            )
            if (response.status !in 200..299) {
                return@withContext GitHubDeviceTokenResult.Failed("GitHub OAuth HTTP ${response.status}: ${response.body}")
            }
            val value = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrElse {
                return@withContext GitHubDeviceTokenResult.Failed("GitHub OAuth 返回了无效 JSON")
            }
            value["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                return@withContext GitHubDeviceTokenResult.Authorized(it)
            }
            when (value["error"]?.jsonPrimitive?.contentOrNull) {
                "authorization_pending" -> GitHubDeviceTokenResult.Pending(interval)
                "slow_down" -> GitHubDeviceTokenResult.Pending(interval + 5)
                "access_denied" -> GitHubDeviceTokenResult.Failed("GitHub 授权已被拒绝")
                "expired_token" -> GitHubDeviceTokenResult.Failed("GitHub 设备验证码已过期")
                else -> GitHubDeviceTokenResult.Failed(
                    value["error_description"]?.jsonPrimitive?.contentOrNull ?: "GitHub 授权失败"
                )
            }
        }

    private fun postForm(path: String, values: Map<String, String>): Response {
        val connection = URL(webBaseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "UMA-Workbench")
            connection.outputStream.use { stream ->
                stream.write(form(values).toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Response(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun form(values: Map<String, String>): String = values.entries.joinToString("&") { entry ->
        encode(entry.key) + "=" + encode(entry.value)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun kotlinx.serialization.json.JsonObject.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.contentOrNull ?: error("GitHub OAuth 响应缺少 $name")

    private fun kotlinx.serialization.json.JsonObject.requiredInt(name: String): Int =
        get(name)?.jsonPrimitive?.intOrNull ?: error("GitHub OAuth 响应缺少 $name")

    private data class Response(val status: Int, val body: String)
}
