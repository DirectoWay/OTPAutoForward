package com.example.autocaptcha.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.autocaptcha.R

/** WiFi 状态监控服务 */
class WiFiMonitorService : Service() {
    private var wifiMonitor: WiFiMonitor? = null
    override fun onCreate() {
        super.onCreate()
        // 注册 WiFi 监控器
        if (wifiMonitor == null) {
            wifiMonitor = WiFiMonitor()
            val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
            registerReceiver(wifiMonitor, filter)
        }

        // 创建前台通知
        val notification = createNotification()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d("WiFiMonitorService", "正在尝试重启WiFi 监听服务...")
        if (wifiMonitor == null) {
            wifiMonitor = WiFiMonitor()
            val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
            registerReceiver(wifiMonitor, filter)
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "WiFi_CHANNEL"
        val channel = NotificationChannel(
            notificationChannelId,
            "WiFi Service",
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notificationBuilder = NotificationCompat.Builder(this, notificationChannelId)
        val notification = notificationBuilder
            .setContentTitle("WiFiMonitor")
            .setContentText("WiFi 监听服务正在后台运行")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return notification
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiMonitor?.let {
            unregisterReceiver(it)
            wifiMonitor = null
        }

        // 关停 WebSocket 服务
        stopService(Intent(this, WebSocketForegroundService::class.java))
    }
}

class WiFiMonitor : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (WifiManager.WIFI_STATE_CHANGED_ACTION == intent.action) {
            val wifiState =
                intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
            when (wifiState) {
                WifiManager.WIFI_STATE_ENABLED -> Log.d("WiFiMonitor", "WiFi 已经可用")
                WifiManager.WIFI_STATE_DISABLED -> Log.d("WiFiMonitor", "WiFi 当前不可用")
            }
        }
    }
}