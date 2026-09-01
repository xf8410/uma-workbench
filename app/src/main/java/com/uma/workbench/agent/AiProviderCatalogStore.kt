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

/** The complete catalog is encrypted because every provider may contain multiple credentials. */
class AiProviderCatalogStore(context: Context) {
    private val ctx = context.applicationContext
    private val preferences = context.getSharedPreferences("ai-provider-catalog", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): AiProviderCatalog = mergeFreeModels(loadRaw())

    /** 持久层原始数据（不含自动注入的免费模型）。 */
    fun loadRaw(): AiProviderCatalog = decrypt(preferences.getString("catalog", null))
        .takeIf(String::isNotEmpty)
        ?.let { runCatching { json.decodeFromString<AiProviderCatalog>(it) }.getOrNull() }
        ?: AiProviderCatalog()

    fun save(catalog: AiProviderCatalog) {
        catalog.providers.forEach(AiProviderProfile::validate)
        preferences.edit().putString("catalog", encrypt(json.encodeToString(stripFreeModels(catalog)))).apply()
    }

    // ── OpenRouter 每日免费模型注入 ──
    // load() 时把当日免费模型并入 OpenRouter provider 的 models（打开），
    // save() 时剥离，避免免费池轮换后旧免费模型被固化在持久层（关不上）。

    private fun freeStore(context: Context): OpenRouterFreeModelStore? =
        runCatching { OpenRouterFreeModelStore(context.applicationContext) }.getOrNull()

    private fun mergeFreeModels(catalog: AiProviderCatalog): AiProviderCatalog {
        val store = freeStore(ctx) ?: return catalog
        return catalog.mergedWithFreeModels(store.load().freeModels)
    }

    private fun stripFreeModels(catalog: AiProviderCatalog): AiProviderCatalog {
        val store = freeStore(ctx) ?: return catalog
        return catalog.strippedOfFreeModels(store.load().freeModels)
    }

    fun clear() { preferences.edit().clear().apply() }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP); require(bytes.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES))) }
            String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private companion object { const val ALIAS = "uma-workbench-ai-provider-catalog"; const val TRANSFORMATION = "AES/GCM/NoPadding"; const val IV_BYTES = 12 }
}

fun AiProviderProfile.isLikelyOpenRouter(): Boolean = baseUrl.contains("openrouter", ignoreCase = true)
