package com.otpautoforward.handler

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.otpautoforward.dataclass.PairedDeviceInfo
import org.json.JSONObject

/** 处理 Win 端提供的二维码 */
class QRCodeHandler {
    private val keyHandler = KeyHandler()

    /** 解密和验证二维码信息 */
    fun analyzeQRCode(qrData: String?): PairedDeviceInfo? {
        if (qrData.isNullOrBlank()) return null

        // 分割加密内容和签名
        val parts = qrData.split(".")
        if (parts.size != 2) return null

        val encryptedText = parts[0]
        val signature = parts[1]

        // 解密二维码中的配对信息
        val decryptedText = keyHandler.decryptString(encryptedText) ?: return null
        val pairedDeviceInfo = Gson().fromJson(decryptedText, PairedDeviceInfo::class.java)

        // 验证二维码中的签名
        val isValidSignature =
            keyHandler.verifySignature(decryptedText, signature, pairedDeviceInfo.windowsPublicKey)
        if (!isValidSignature) {
            Log.e("QRCodeHandler", "签名验证失败")
            return null
        }

        // 返回解密后的内容
        return pairedDeviceInfo
    }

    /** 记录已经匹配成功过的设备 */
    fun saveDeviceInfo(context: Context, pairedDeviceInfo: PairedDeviceInfo) {
        val sharedPreferences =
            context.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val deviceInfo = JSONObject().apply {
            put("deviceName", pairedDeviceInfo.deviceName)
            put("deviceId", pairedDeviceInfo.deviceId)
            put("deviceType", pairedDeviceInfo.deviceType)
            put("windowsPublicKey", pairedDeviceInfo.windowsPublicKey)
        }
        editor.putString(pairedDeviceInfo.deviceId, deviceInfo.toString())
        editor.apply()
    }
}