package com.example.autocaptcha.handler

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

class KeyHandler {
    // 生成Android端的临时密钥对
    fun generateTempKeyPair(): Pair<String, String> {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        val publicKey = Base64.encodeToString(keyPair.public.encoded, Base64.DEFAULT)
        val privateKey = Base64.encodeToString(keyPair.private.encoded, Base64.DEFAULT)
        return Pair(publicKey, privateKey)
    }

    // 加密信息
    fun encryptMessage(message: String, publicKey: String): String {
        val keySpec = X509EncodedKeySpec(Base64.decode(publicKey, Base64.DEFAULT))
        val keyFactory = KeyFactory.getInstance("RSA")
        val key = keyFactory.generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encryptedData = cipher.doFinal(message.toByteArray())
        return Base64.encodeToString(encryptedData, Base64.DEFAULT)
    }

    // 解密信息
    fun decryptMessage(encryptedMessage: String, privateKey: String): String {
        val keySpec = X509EncodedKeySpec(Base64.decode(privateKey, Base64.DEFAULT))
        val keyFactory = KeyFactory.getInstance("RSA")
        val key = keyFactory.generatePrivate(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        val decryptedData = cipher.doFinal(Base64.decode(encryptedMessage, Base64.DEFAULT))
        return String(decryptedData)
    }
}


