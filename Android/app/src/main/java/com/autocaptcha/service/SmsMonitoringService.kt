package com.autocaptcha.service

import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.autocaptcha.handler.WebSocketWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationReceiver : NotificationListenerService() {
    private val tag = "NotificationReceiver"

    /** 默认的短信包名 */
    private var smsPackageName: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(tag, "NOTIFICATION_LISTENER 权限已授予")
        smsPackageName = Telephony.Sms.getDefaultSmsPackage(applicationContext)
        queryActiveSmsNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(tag, "NOTIFICATION_LISTENER 权限已被移除")
    }

    /** 查询是否有正在活动的通知 */
    private fun queryActiveSmsNotifications() {
        val activeNotifications = activeNotifications
        activeNotifications?.forEach { sbn ->
            val packageName = sbn.packageName // 过滤短信应用的通知
            if (packageName == smsPackageName) {
                val notificationTitle = sbn.notification.extras.getString("android.title")
                val notificationText = sbn.notification.extras.getString("android.text")
                Log.d(
                    tag,
                    "现存短信通知: 标题: $notificationTitle, 内容: $notificationText"
                )
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 只处理短信通知
        val packageName = sbn.packageName
        if (packageName != smsPackageName) {
            return
        }

        // 读取 settings 配置
        val sharedPreferences =
            applicationContext.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)

        // 关闭 "短信转发" 后, 不再处理任何后续逻辑
        if (!sharedPreferences.getBoolean("SmsSwitch", true)) {
            return
        }

        if (!isWifiConnected()) {
            Log.d(tag, "当前 WiFi 不可用, 不再转发短信")
            return
        }

        val notificationTitle = sbn.notification.extras.getString("android.title")
        val notificationText = sbn.notification.extras.getString("android.text")
        val notificationTime =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sbn.postTime))

        Log.d(tag, "收到短信: 标题: $notificationTitle, 内容: $notificationText")

        val forwardOnlyScreenLocked =
            sharedPreferences.getBoolean("forwardOnlyScreenLocked", true)

        val isScreenlocked = isScreenlocked()
        if (forwardOnlyScreenLocked) {
            // 锁屏转发开启并且处于锁定状态时才转发短信
            if (isScreenlocked) {
                Log.d(tag, "屏幕已锁定，转发短信")
                WebSocketWorker.sendWebSocketMessage(
                    applicationContext,
                    "$notificationText\n发送者：$notificationTitle - $notificationTime"
                )
            } else {
                Log.d(tag, "屏幕未锁定，不转发短信")
            }
        } else {
            Log.d(tag, "任何屏幕状态都转发短信")
            WebSocketWorker.sendWebSocketMessage(
                applicationContext,
                "$notificationText\n发送者：$notificationTitle - $notificationTime"
            )
        }
    }

    /** 判断当前是否处于 WiFi 状态 */
    private fun isWifiConnected(): Boolean {
        val connectivityManager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities =
            connectivityManager.getNetworkCapabilities(network)
        return capabilities != null && capabilities.hasTransport(
            NetworkCapabilities.TRANSPORT_WIFI
        )
    }

    /** 判断是否处于锁屏状态 */
    private fun isScreenlocked(): Boolean {
        val keyguardManager =
            applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isKeyguardLocked
    }
}










