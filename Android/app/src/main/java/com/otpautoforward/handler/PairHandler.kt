package com.otpautoforward.handler

import android.util.Log
import com.google.gson.Gson
import com.otpautoforward.dataclass.PairedDeviceInfo

private const val tag = "PairHandler"

/** 处理 Win 端提供的二维码 */
class PairHandler {
    private val keyHandler = KeyHandler()

    /** 解密和验证二维码信息 */
    fun analyzeQRCode(qrData: String?): PairedDeviceInfo {
        try {
            if (qrData.isNullOrBlank()) throw Exception("二维码信息为空")

            // 分割加密内容和签名
            val parts = qrData.split(".")
            if (parts.size != 2) throw Exception("二维码信息分割异常")

            val encryptedText = parts[0]
            val signature = parts[1]

            // 解密二维码中的配对信息
            val decryptedText = keyHandler.decryptString(encryptedText) ?: throw Exception("二维码解密失败")
            val pairedDeviceInfo = Gson().fromJson(decryptedText, PairedDeviceInfo::class.java)

            // 验证二维码中的签名
            val isValidSignature =
                keyHandler.verifySignature(decryptedText, signature, pairedDeviceInfo.windowsPublicKey)
            if (!isValidSignature) throw Exception("签名验证失败")

            return pairedDeviceInfo
        } catch (ex: Exception) {
            Log.d(tag, "解析二维码时发生异常: $ex")
            throw ex
        }
    }

    /** 解密和验证 Win 端通过 WebSocket 发来的配对信息 */
    fun analyzePairInfo(message: String): PairedDeviceInfo? {
        try {
            // 分割加密内容和签名
            val parts = message.split(".")
            if (parts.size != 3) throw Exception("Win 端的配对信息不符合格式")

            val encryptedText = parts[1]
            val signature = parts[2]

            // 解密配对信息
            val plainText = keyHandler.decryptString(encryptedText) ?: throw Exception("Win 端的配对信息为空")
            val pairedDeviceInfo = Gson().fromJson(plainText, PairedDeviceInfo::class.java)

            val isValidSignature = keyHandler.verifySignature(plainText, signature, pairedDeviceInfo.windowsPublicKey)
            if (!isValidSignature) throw Exception("通过 IP 进行配对时, 签名验证失败")

            return pairedDeviceInfo
        } catch (ex: Exception) {
            Log.e(tag, "通过 IP 进行配对时, Win 端的配对信息异常: $ex")
            throw ex
        }
    }
}