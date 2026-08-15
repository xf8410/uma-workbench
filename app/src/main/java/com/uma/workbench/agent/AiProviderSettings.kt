package com.uma.workbench.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Complete user-owned custom API configuration. Header JSON may contain authentication secrets. */
data class AiProviderSettings(
    val endpointUrl: String = "",
    val model: String = "",
    val headersJson: String = "{}",
    val protocol: CustomAiApiProtocol = CustomAiApiProtocol()
) {
    val configured: Boolean get() = endpointUrl.isNotBlank() && model.isNotBlank()
    fun validate() {
        require(endpointUrl.startsWith("https://")) { "AI API 地址必须使用 https://" }
        require(model.isNotBlank()) { "模型名称不能为空" }
        require(runCatching { Json.parseToJsonElement(headersJson) }.getOrNull() is kotlinx.serialization.json.JsonObject) { "请求头必须是 JSON 对象" }
        protocol.validate()
    }
}

/** Endpoint/model are ordinary settings; complete headers and protocol are encrypted at rest. */
class AiProviderSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("ai-provider-settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): AiProviderSettings {
        val secret = decrypt(preferences.getString("secret", null))
        val parts = secret.split('\u0000', limit = 9)
        return AiProviderSettings(
            endpointUrl = preferences.getString("endpoint", "").orEmpty(),
            model = preferences.getString("model", "").orEmpty(),
            headersJson = parts.getOrNull(0) ?: "{}",
            protocol = CustomAiApiProtocol(
                requestTemplate = parts.getOrNull(1) ?: CustomAiApiProtocol.OPENAI_REQUEST_TEMPLATE,
                streamFormat = runCatching { AiApiStreamFormat.valueOf(parts.getOrNull(2).orEmpty()) }.getOrDefault(AiApiStreamFormat.SSE),
                textPath = parts.getOrNull(3) ?: "choices.0.delta.content",
                modelPath = parts.getOrNull(4) ?: "model",
                inputTokensPath = parts.getOrNull(5) ?: "usage.prompt_tokens",
                outputTokensPath = parts.getOrNull(6) ?: "usage.completion_tokens",
                totalTokensPath = parts.getOrNull(7) ?: "usage.total_tokens",
                doneValue = parts.getOrNull(8) ?: "[DONE]"
            )
        )
    }

    fun save(settings: AiProviderSettings) {
        settings.validate()
        val p = settings.protocol
        val secret = listOf(settings.headersJson, p.requestTemplate, p.streamFormat.name, p.textPath, p.modelPath, p.inputTokensPath, p.outputTokensPath, p.totalTokensPath, p.doneValue).joinToString("\u0000")
        preferences.edit().putString("endpoint", settings.endpointUrl.trim()).putString("model", settings.model.trim()).putString("secret", encrypt(secret)).apply()
    }

    fun clear() { preferences.edit().clear().apply() }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION); cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP); require(bytes.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    private companion object { const val ALIAS = "uma-workbench-ai-api-key"; const val TRANSFORMATION = "AES/GCM/NoPadding"; const val IV_BYTES = 12 }
}
