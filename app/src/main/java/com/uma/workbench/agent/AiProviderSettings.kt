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

data class AiProviderSettings(
    val endpointUrl: String = "",
    val model: String = "",
    val apiKey: String = ""
) {
    val configured: Boolean get() = endpointUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    fun validate() {
        require(endpointUrl.startsWith("https://")) { "AI API 地址必须使用 https://" }
        require(model.isNotBlank()) { "模型名称不能为空" }
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
    }
}

/** Stores the API key encrypted by a non-exportable Android Keystore AES key. */
class AiProviderSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("ai-provider-settings", Context.MODE_PRIVATE)

    fun load(): AiProviderSettings = AiProviderSettings(
        endpointUrl = preferences.getString("endpoint", "").orEmpty(),
        model = preferences.getString("model", "").orEmpty(),
        apiKey = decrypt(preferences.getString("key", null))
    )

    fun save(settings: AiProviderSettings) {
        settings.validate()
        preferences.edit()
            .putString("endpoint", settings.endpointUrl.trim())
            .putString("model", settings.model.trim())
            .putString("key", encrypt(settings.apiKey))
            .apply()
    }

    fun clear() {
        preferences.edit().remove("endpoint").remove("model").remove("key").apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "uma-workbench-ai-api-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
