package com.example.autocaptcha.handler

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.lang.ref.WeakReference

/** 为 WebSocket 连接时提供工具方法 */
class WebSocketHandler private constructor() {
    private lateinit var appContext: Context
    private var uiContext: WeakReference<Context>? = null

    companion object {
        @Volatile
        private var instance: WebSocketHandler? = null

        fun getInstance(): WebSocketHandler {
            return instance ?: synchronized(this) {
                instance ?: WebSocketHandler().also { instance = it }
            }
        }
    }

    /** 将 appContext 传递给 WebSocketHandler 使用*/
    fun setAppContext(context: Context) {
        appContext = context.applicationContext
    }

    /** 将 uiContext 传递给涉及页面的业务方法使用 */
    fun setUIContext(context: Context?) {
        uiContext = WeakReference(context)
    }

    fun destroy() {
        instance = null
    }

    /** 检查是否配对过该设备 */
    fun isDeviceKnown(deviceId: String): Boolean {
        Log.d("isDeviceKnown", "isDeviceKnown已被执行")
        val sharedPreferences =
            appContext.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        return sharedPreferences.contains(deviceId)
    }

    /** 记录已经匹配成功过的设备 */
    fun saveDeviceInfo(pairingInfo: PairingInfo) {
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

        editor.putString(pairingInfo.deviceId, deviceInfo.toString())
        editor.apply()
    }

    /** 通过设备 ID 获取已配对的设备信息 */
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

    /** 获取所有已配对的设备信息 */
    fun getAllDevicesInfo(): List<PairingInfo> {
        val sharedPreferences =
            appContext.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        val allDeviceIds = sharedPreferences.all.keys
        val allDevicesInfo = mutableListOf<PairingInfo>()

        for (deviceId in allDeviceIds) {
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
            }
        }

        return allDevicesInfo
    }

    /** 显示重复配对的对话框 */
    fun showAlreadyPairedDialog() {
        Log.d("showAlreadyPairedDialog", "showAlreadyPairedDialog已被执行")
        uiContext?.get()?.let { context ->
            AlertDialog.Builder(context).setTitle("重复配对")
                .setMessage("当前设备已经配对成功，请勿重复配对。")
                .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }.show()
        } ?: throw IllegalStateException("context is null, cannot show AlertDialog")
    }

    /** 显示配对成功的对话框 */
    fun showPairedSuccessDialog(pairingInfo: PairingInfo) {
        uiContext?.get()?.let { context ->
            AlertDialog.Builder(context).setTitle("配对成功")
                .setMessage("设备 ${pairingInfo.deviceName} 已成功配对！")
                .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }.show()
        }
    }

}