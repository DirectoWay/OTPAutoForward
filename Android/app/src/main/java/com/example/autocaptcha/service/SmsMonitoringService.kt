package com.example.autocaptcha.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.autocaptcha.R

/** 短信前台服务 */
class SmsForegroundService : Service() {
    private var smsReceiver: SmsReceiver? = null
    private var webSocketService: WebSocketForegroundService? = null
    private var isBound = false

    companion object {
        private var instance: SmsForegroundService? = null
        private fun getInstance(): SmsForegroundService {
            if (instance == null) {
                instance = SmsForegroundService()
            }
            return instance!!
        }

        val isBound: Boolean get() = getInstance().isBound
        val webSocketService: WebSocketForegroundService? get() = getInstance().webSocketService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 注册短信接收器
        if (smsReceiver == null) {
            smsReceiver = SmsReceiver()
            val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
            registerReceiver(smsReceiver, filter)
        }
        // 创建前台通知
        val notification = createNotification()
        startForeground(1, notification)

        val intent = Intent(this, WebSocketForegroundService::class.java)
        bindService(intent, serviceBinding, Context.BIND_AUTO_CREATE)
    }

    /** 短信服务与 WebSocket 服务的绑定 */
    private val serviceBinding = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? WebSocketForegroundService.WebSocketBinder
            webSocketService = binder?.getService()
            isBound = true
            Log.d("SmsService", "短信服务已绑定至 WebSocket 服务")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            webSocketService = null
            isBound = false
            Log.d("SmsService", "短信服务已与 WebSocket 服务解绑")
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (instance == null) {
            instance = this
        }
        Log.d("SmsForegroundService", "正在尝试重启短信服务...")
        if (smsReceiver == null) {
            smsReceiver = SmsReceiver()
            val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
            registerReceiver(smsReceiver, filter)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        smsReceiver?.let {
            unregisterReceiver(it)
            smsReceiver = null
        }
        if (isBound) {
            unbindService(serviceBinding)
            isBound = false
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "SMS_CHANNEL"
        val channel = NotificationChannel(
            notificationChannelId,
            "SMS Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notificationBuilder = NotificationCompat.Builder(this, notificationChannelId)
        val notification = notificationBuilder
            .setContentTitle("SmsReceiver")
            .setContentText("短信服务正在后台运行")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return notification
    }
}

@Suppress("DEPRECATION")
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            // 读取 settings 配置
            val sharedPreferences =
                context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            val forwardOnlyScreenLocked =
                sharedPreferences.getBoolean("forwardOnlyScreenLocked", true)

            val isScreenlocked = isScreenlocked(context)
            if (forwardOnlyScreenLocked) {
                // 锁屏转发开启并且处于锁定状态时才转发短信
                if (isScreenlocked) {
                    Log.d("SmsReceiver", "屏幕已锁定，转发短信")
                    receiveSMS(intent)
                } else {
                    Log.d("SmsReceiver", "屏幕未锁定，不转发短信")
                }
            } else {
                Log.d("SmsReceiver", "任何屏幕状态都转发短信")
                receiveSMS(intent)
            }
        } else {
            Log.d("SmsReceiver", "收到无效操作的广播：${intent.action}")
        }
    }

    /** 接收短信并发送给 Win 端*/
    private fun receiveSMS(intent: Intent) {
        val bundle = intent.extras
        if (bundle != null) {
            Log.d("SmsReceiver", "SmsReceiver 准备接收短信")
            val pdus = bundle.get("pdus") as Array<*>
            val format = bundle.getString("format")
            for (pdu in pdus) {
                val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray, format)
                val messageBody = smsMessage.messageBody
                val sender = smsMessage.originatingAddress
                // 处理短信内容
                Log.d("SmsReceiver", "SmsReceiver 收到短信：$messageBody 发送者：$sender")

                if (SmsForegroundService.isBound) {
                    SmsForegroundService.webSocketService?.sendMessage("收到短信：$messageBody\n发送者：$sender ")
                } else {
                    Log.e("SmsReceiver", "WebSocket 服务未绑定，无法发送消息")
                }
            }
        }
    }

    /** 判断是否处于锁屏状态 */
    private fun isScreenlocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isKeyguardLocked
    }
}










