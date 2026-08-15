package com.uma.workbench.protocol

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 包体加解密。安卓端育成协议包体通常是 AES + 自定义 prefix。
 * 实际密钥/prefix/算法需要从 hlpatch hook 或 SO 逆向获取。
 * 这里提供框架和占位实现，拿到真实参数后替换。
 */
object PacketCrypto {

    /** 加密模式 */
    enum class CryptoMode { NONE, AES_CBC, AES_ECB, CUSTOM }

    /** 从 hlpatch 抓到的加密参数 */
    data class CryptoConfig(
        val mode: CryptoMode,
        val key: ByteArray?,
        val iv: ByteArray?,
        val prefix: ByteArray?,  // 包体前缀（如版本号/魔数）
        val encoding: String = "Base64"
    )

    private var config: CryptoConfig = CryptoConfig(CryptoMode.NONE, null, null, null)

    /** 设置加密参数（从 hlpatch dump 或手动输入） */
    fun configure(cfg: CryptoConfig) { config = cfg }

    /** 加密明文 → 密文（含 prefix） */
    fun encrypt(plaintext: String): ByteArray {
        val cfg = config
        if (cfg.mode == CryptoMode.NONE) return plaintext.toByteArray(Charsets.UTF_8)
        val data = plaintext.toByteArray(Charsets.UTF_8)
        val encrypted = when (cfg.mode) {
            CryptoMode.AES_CBC -> aesEncrypt(data, cfg.key!!, cfg.iv!!)
            CryptoMode.AES_ECB -> aesEncrypt(data, cfg.key!!, null)
            CryptoMode.CUSTOM -> customEncrypt(data)  // 占位
            CryptoMode.NONE -> data
        }
        return if (cfg.prefix != null) cfg.prefix + encrypted else encrypted
    }

    /** 解密密文 → 明文 */
    fun decrypt(ciphertext: ByteArray): String {
        val cfg = config
        if (cfg.mode == CryptoMode.NONE) return String(ciphertext, Charsets.UTF_8)
        val payload = if (cfg.prefix != null && ciphertext.size > cfg.prefix.size && ciphertext.copyOfRange(0, cfg.prefix.size).contentEquals(cfg.prefix)) {
            ciphertext.copyOfRange(cfg.prefix.size, ciphertext.size)
        } else ciphertext
        val decrypted = when (cfg.mode) {
            CryptoMode.AES_CBC -> aesDecrypt(payload, cfg.key!!, cfg.iv!!)
            CryptoMode.AES_ECB -> aesDecrypt(payload, cfg.key!!, null)
            CryptoMode.CUSTOM -> customDecrypt(payload)
            CryptoMode.NONE -> payload
        }
        return String(decrypted, Charsets.UTF_8)
    }

    /** 请求体 Base64 编码 */
    fun encodeBody(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    /** 响应体 Base64 解码 */
    fun decodeBody(data: String): ByteArray = Base64.decode(data, Base64.DEFAULT)

    /** SHA-256 指纹 */
    fun sha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun aesEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance(if (iv != null) "AES/CBC/PKCS5Padding" else "AES/ECB/PKCS5Padding")
        val secretKey = SecretKeySpec(key, "AES")
        if (iv != null) cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        else cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(data)
    }

    private fun aesDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance(if (iv != null) "AES/CBC/PKCS5Padding" else "AES/ECB/PKCS5Padding")
        val secretKey = SecretKeySpec(key, "AES")
        if (iv != null) cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        else cipher.init(Cipher.DECRYPT_MODE, secretKey)
        return cipher.doFinal(data)
    }

    /** 自定义加解密占位 — 拿到真实算法后替换 */
    private fun customEncrypt(data: ByteArray): ByteArray = data
    private fun customDecrypt(data: ByteArray): ByteArray = data
}
