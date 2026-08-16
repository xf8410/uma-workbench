package com.uma.workbench.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the user's GitHub token encrypted with a non-exportable Android Keystore key. */
class GitHubCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun loadToken(): String = decrypt(preferences.getString(TOKEN, null))

    fun saveToken(token: String) {
        require(token.isNotBlank()) { "GitHub Token 不能为空" }
        preferences.edit().putString(TOKEN, encrypt(token.trim())).apply()
    }

    fun clear() {
        preferences.edit().remove(TOKEN).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES))
                )
            }
            String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "github-credentials"
        const val TOKEN = "token"
        const val ALIAS = "uma-workbench-github-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
