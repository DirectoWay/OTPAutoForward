package com.example.autocaptcha.handler

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDateTime


class WebSocketHandler private constructor() : WebSocketListener() {

    private var currentWebSocket: WebSocket? = null

    @Volatile
    private var isConnected = false
    private lateinit var appContext: Context
    private lateinit var pairingInfo: PairingInfo

    companion object {
        @Volatile
        private var instance: WebSocketHandler? = null
        fun getInstance(): WebSocketHandler {
            return instance ?: synchronized(this) {
                instance ?: WebSocketHandler().also {
                    instance = it
                }
            }
        }
    }

    fun connectToWebSocket(
        context: Context, pairingInfo: PairingInfo, onSuccess: (Context, PairingInfo) -> Unit
    ) {
        this.appContext = context.applicationContext
        this.pairingInfo = pairingInfo
        val deviceId = pairingInfo.deviceId

        // 检查是否已配对
        if (isDeviceKnown(context, deviceId)) {
            if (isConnected && currentWebSocket != null) {
                showAlreadyPairedDialog(context)
                return
            }
        }

        val client = OkHttpClient()
        val request = Request.Builder().url(pairingInfo.serverUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                isConnected = true
                Log.d("WebSocket", "WebSocket连接已打开--" + LocalDateTime.now())
                saveDeviceInfo(pairingInfo) // 连接成功后保存设备信息
                Handler(Looper.getMainLooper()).post {
                    onSuccess(context, pairingInfo) // 回调外层业务
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                isConnected = false
                Log.e("WebSocket", "连接失败：${t.message}")
            }
        }
        currentWebSocket = client.newWebSocket(request, listener)
    }

    // 检查是否配对过该设备
    private fun isDeviceKnown(context: Context, deviceId: String): Boolean {
        val sharedPreferences = context.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        return sharedPreferences.contains(deviceId) // 判断是否存在该设备
    }

    private fun showAlreadyPairedDialog(context: Context) {
        AlertDialog.Builder(context).setTitle("重复配对")
            .setMessage("当前设备已经配对成功，请勿重复配对。")
            .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }.show()
    }

    fun showPairedSuccessDialog(context: Context, pairingInfo: PairingInfo) {
        AlertDialog.Builder(context).setTitle("配对成功")
            .setMessage("设备 ${pairingInfo.deviceName} 已成功配对！")
            .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }.show()
    }

    // 记录已经匹配成功过的设备
    private fun saveDeviceInfo(pairingInfo: PairingInfo) {
        val sharedPreferences =
            appContext.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        val deviceInfo = JSONObject().apply {
            put("serverUrl", pairingInfo.serverUrl)
            put("windowsPublicKey", pairingInfo.windowsPublicKey)
            put("deviceId", pairingInfo.deviceId)
            put("deviceName", pairingInfo.deviceName)
            put("deviceType", pairingInfo.deviceType)
        }

        editor.putString(pairingInfo.deviceId, deviceInfo.toString()) // 标识已配对的设备
        editor.apply()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        super.onMessage(webSocket, text)
        Log.d("WebSocket", "接收到文本消息: $text--" + LocalDateTime.now())
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        super.onMessage(webSocket, bytes)
        Log.d("WebSocket", "接收到二进制消息: ${bytes.hex()}--" + LocalDateTime.now())
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        super.onClosed(
            webSocket, code, reason
        )
        isConnected = false
        Log.d("WebSocket", "WebSocket连接已关闭，原因: $reason--" + LocalDateTime.now())
    }

    fun sendMessage(message: String) {
        if (isConnected && currentWebSocket != null) {
            currentWebSocket?.send(message)
            Log.d("WebSocket", "发送消息: $message")
        } else {
            Log.e("WebSocket", "WebSocket未连接，无法发送消息")
        }
    }


    // 获取已配对的设备信息
    fun getDeviceInfoByDeviceId(deviceId: String): PairingInfo? {
        val sharedPreferences =
            appContext.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        val deviceInfoString = sharedPreferences.getString(deviceId, null)

        return if (deviceInfoString != null) {
            try {
                val deviceInfo = JSONObject(deviceInfoString)
                PairingInfo(
                    serverUrl = deviceInfo.getString("serverUrl"),
                    windowsPublicKey = deviceInfo.getString("windowsPublicKey"),
                    deviceId = deviceInfo.getString("deviceId"),
                    deviceType = deviceInfo.getString("deviceType"),
                    deviceName = deviceInfo.getString("deviceName")
                )
            } catch (e: JSONException) {
                e.printStackTrace()
                null
            }
        } else {
            null
        }
    }

    fun getAllDevicesInfo(context: Context): List<PairingInfo> {
        val sharedPreferences = context.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        val allDeviceIds = sharedPreferences.all.keys // 获取所有设备的ID
        val allDevicesInfo = mutableListOf<PairingInfo>()

        for (deviceId in allDeviceIds) {
            // 获取设备信息字符串
            val deviceInfoString = sharedPreferences.getString(deviceId, null)

            if (deviceInfoString != null) {
                try {
                    val deviceInfo = JSONObject(deviceInfoString)
                    val pairingInfo = PairingInfo(
                        serverUrl = deviceInfo.getString("serverUrl"),
                        windowsPublicKey = deviceInfo.getString("windowsPublicKey"),
                        deviceId = deviceInfo.getString("deviceId"),
                        deviceType = deviceInfo.getString("deviceType"),
                        deviceName = deviceInfo.getString("deviceName")
                    )

                    allDevicesInfo.add(pairingInfo)
                } catch (e: JSONException) {
                    Log.e("DeviceInfo", "获取设备信息失败: deviceId: $deviceId", e)
                }
            } else {
                Log.w("DeviceInfo", "获取设备信息失败: deviceId: $deviceId")
            }
        }

        return allDevicesInfo
    }
}