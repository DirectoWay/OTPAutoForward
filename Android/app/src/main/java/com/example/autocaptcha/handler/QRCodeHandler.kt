package com.example.autocaptcha.handler

import com.google.gson.Gson

// 用于封装和存储MQTT连接所需的配置信息
data class PairingInfo(
    val broker: String,
    val port: Int,
    val username: String,
    val password: String,
    val clientId: String
)

object QRCodeProcessor {
    fun parseQRCodeData(qrData: String?): PairingInfo? {
        return if (qrData != null) {
            Gson().fromJson(qrData, PairingInfo::class.java)
        } else {
            null
        }
    }
}