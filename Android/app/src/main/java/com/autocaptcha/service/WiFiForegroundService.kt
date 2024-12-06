package com.autocaptcha.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autocaptcha.R

/** WiFi 状态监控服务 */
class WiFiForegroundService : Service() {
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    // 记录上一次的网络状态
    private var lastNetworkType: Int? = null

    override fun onCreate() {
        super.onCreate()

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                checkNetworkStatus()
            }

            override fun onLost(network: Network) {
                Log.d("WiFiForegroundService", "回调：网络已断开")
                lastNetworkType = null
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val currentNetworkType = when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapabilities.TRANSPORT_WIFI
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapabilities.TRANSPORT_CELLULAR
                    else -> null
                }

                if (currentNetworkType != null && currentNetworkType != lastNetworkType) {
                    lastNetworkType = currentNetworkType
                    when (currentNetworkType) {
                        NetworkCapabilities.TRANSPORT_WIFI -> Log.d(
                            "WiFiForegroundService",
                            "回调：WiFi 网络已连接"
                        )

                        NetworkCapabilities.TRANSPORT_CELLULAR -> Log.d(
                            "WiFiForegroundService",
                            "回调：当前使用移动网络"
                        )
                    }
                }
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        startForeground(20011, createNotification())
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d("WiFiForegroundService", "WiFi 监听服务已启动")
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

        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("WiFiMonitor")
            .setContentText("WiFi 监听服务正在后台运行")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun checkNetworkStatus() {
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

        if (networkCapabilities != null) {
            when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    Log.d("WiFiForegroundService", "WiFi 已连接")
                }

                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    Log.d("WiFiForegroundService", "当前使用移动网络")
                }

                else -> {
                    Log.d("WiFiForegroundService", "无可用网络")
                }
            }
        } else {
            Log.d("WiFiForegroundService", "无可用网络")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        Log.d("WiFiForegroundService", "WiFi 监听服务已停止")
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}


