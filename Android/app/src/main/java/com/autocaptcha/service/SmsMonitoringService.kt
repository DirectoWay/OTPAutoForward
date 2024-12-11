package com.autocaptcha.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import com.autocaptcha.handler.WebSocketHandler
import com.autocaptcha.handler.WebSocketWorker

@Suppress("DEPRECATION")
class SmsReceiver : BroadcastReceiver() {
    private lateinit var appContext: Context

    override fun onReceive(context: Context, intent: Intent) {
        appContext = context.applicationContext // 获取应用上下文
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
                WebSocketWorker.sendWebSocketMessage(appContext, "$messageBody\n发送者：$sender")
            }
        }
    }

    /** 判断是否处于锁屏状态 */
    private fun isScreenlocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isKeyguardLocked
    }
}










