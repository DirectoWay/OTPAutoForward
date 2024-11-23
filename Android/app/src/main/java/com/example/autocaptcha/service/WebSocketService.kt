package com.example.autocaptcha.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.autocaptcha.R
import com.example.autocaptcha.handler.PairingInfo
import com.example.autocaptcha.handler.WebSocketHandler
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** WebSocket 连接服务 */
class WebSocketForegroundService : Service() {
    /** 当前的 WebSocket 实例*/
    private var currentWebSocket: WebSocket? = null

    /** WebSocket 当前是否与 Win 端处于连接状态 */
    private var isConnected = false

    /** 用于给短信监听服务进行绑定 */
    private val binder = WebSocketBinder()
    private lateinit var pairingInfo: PairingInfo
    private val webSocketHandler: WebSocketHandler = WebSocketHandler.getInstance()

    override fun onCreate() {
        super.onCreate()
        // 保持前台运行，防止被系统回收
        startForeground(
            1, createNotification()
        )
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val pairingInfo: PairingInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("pairingInfo", PairingInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("pairingInfo")
        }
        pairingInfo?.let {
            connectToWebSocket(it)
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "WebSocketChannel"
        val channel = NotificationChannel(
            channelId, "WebSocket Service", NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebSocket Service")
            .setContentText("WebSocket 正在后台运行")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private fun connectToWebSocket(pairingInfo: PairingInfo) {
        this.pairingInfo = pairingInfo

        if (webSocketHandler.isDeviceKnown(pairingInfo.deviceId)) {
            if (isConnected && currentWebSocket != null) {
                webSocketHandler.showAlreadyPairedDialog()
                return
            }
        }
        val client = OkHttpClient()
        val request = Request.Builder().url(pairingInfo.serverUrl).build()
        currentWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                isConnected = true
                Log.d("WebSocketService", "WebSocket 连接成功")
                Handler(Looper.getMainLooper()).post {
                    webSocketHandler.showPairedSuccessDialog(pairingInfo)
                    webSocketHandler.saveDeviceInfo(pairingInfo)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                isConnected = false
                Log.e("WebSocketService", "连接失败: ${t.message}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                Log.d("WebSocketService", "收到消息: $text")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                isConnected = false
                Log.d("WebSocketService", "WebSocket 已关闭: $reason")
            }
        })
    }

    /** 给 Win 端的 WebSocket 服务器发送消息 */
    fun sendMessage(message: String) {
        if (isConnected && currentWebSocket != null) { // 确保连接已建立
            currentWebSocket?.send(message)
            Log.d("WebSocketService", "WebSocket 已发送消息: $message")
        } else {
            Log.e("WebSocketService", "WebSocket 未连接，无法发送消息")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentWebSocket?.close(1000, "WebSocket 服务被销毁")
        Log.d("WebSocketService", "WebSocket 连接已关闭")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /** 提供 WebSocket 服务的绑定 */
    inner class WebSocketBinder : Binder() {
        fun getService(): WebSocketForegroundService = this@WebSocketForegroundService
    }
}
