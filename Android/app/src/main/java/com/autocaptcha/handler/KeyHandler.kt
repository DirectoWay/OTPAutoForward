package com.autocaptcha.handler

import android.util.Base64
import android.util.Log
import java.util.Base64 as JavaBase64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 对称加密密钥 */
object AESKey {
    const val KEY = "autoCAPTCHA-encryptedKey"
}

class KeyHandler {
    /** 对称加密的解密方法 */
    fun decryptString(encrypted: String): String? {
        return try {
            val secretKey = SecretKeySpec(AESKey.KEY.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            val ivSpec = IvParameterSpec(ByteArray(16)) // 全零向量
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decodedBytes = Base64.decode(encrypted, Base64.DEFAULT)
            val originalBytes = cipher.doFinal(decodedBytes)
            String(originalBytes)
        } catch (e: Exception) {
            Log.e("KeyHandler", "解密失败", e)
            null
        }
    }

    /** 对称加密方法 */
    fun encryptString(plainText: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        val secretKey = SecretKeySpec(AESKey.KEY.toByteArray(Charsets.UTF_8), "AES")
        val iv = ByteArray(16) // 初始化向量

        // Cipher模式
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        return JavaBase64.getEncoder().encodeToString(encryptedBytes)
    }

    /** 使用公钥验证签名 */
    fun verifySignature(data: String, signature: String, publicKey: String): Boolean {
        return try {
            // 解析公钥
            val keyBytes = Base64.decode(publicKey, Base64.DEFAULT)
            val spec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val pubKey = keyFactory.generatePublic(spec)

            // 初始化签名验证
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(pubKey)
            sig.update(data.toByteArray(Charsets.UTF_8))

            // 验证签名
            val signatureBytes = Base64.decode(signature, Base64.DEFAULT)
            val result = sig.verify(signatureBytes)

            if (!result) {
                Log.e(
                    "KeyHandler", "签名验证失败: 数据 = $data, 签名 = $signature, 公钥 = $publicKey"
                )
            }

            result
        } catch (e: Exception) {
            Log.e("KeyHandler", "签名验证过程中出错", e)
            false
        }
    }
}


