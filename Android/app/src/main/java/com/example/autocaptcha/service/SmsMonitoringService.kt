package com.example.autocaptcha.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log

@Suppress("DEPRECATION")
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            // 读取 settings 配置
            val sharedPreferences =
                context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            val forwardOnlyScreenLocked =
                sharedPreferences.getBoolean("forwardOnlyScreenLocked", true)

            // 判断屏幕状态
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
                // 如果锁屏转发关闭，任何状态下都转发短信
                Log.d("SmsReceiver", "任何状态都转发短信")
                receiveSMS(intent)
            }
        } else {
            Log.d("SmsReceiver", "收到无效操作的广播：${intent.action}")
        }
    }

    private fun receiveSMS(intent: Intent) {
        val bundle = intent.extras
        if (bundle != null) {
            Log.d("SmsReceiver", "SmsReceiver准备接收短信")
            val pdus = bundle.get("pdus") as Array<*>
            val format = bundle.getString("format")
            for (pdu in pdus) {
                val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray, format)
                val messageBody = smsMessage.messageBody
                val sender = smsMessage.originatingAddress
                // 处理短信内容
                Log.d("SmsReceiver", "SmsReceiver收到短信：$messageBody 发送者：$sender")
            }
        }
    }

    private fun isScreenlocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isKeyguardLocked
    }
}










