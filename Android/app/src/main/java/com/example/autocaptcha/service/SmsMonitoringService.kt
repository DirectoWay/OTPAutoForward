package com.example.autocaptcha.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.telephony.SmsMessage
import android.util.Log
import androidx.annotation.RequiresApi

class SmsMonitoringService : Service() {
    private lateinit var smsReceiver: SmsReceiver

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        smsReceiver = SmsReceiver()
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        registerReceiver(smsReceiver, filter, Context.RECEIVER_EXPORTED)
        Log.d("SmsReceiver", "SmsReceiver已注册")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(smsReceiver)
        Log.d("SmsReceiver", "SmsReceiver已取消注册")
    }
}

@Suppress("DEPRECATION")
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
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
                    Log.d("SmsReceiver", "SmsReceiver收到短信：$messageBody，发送者：$sender")
                }
            }
        } else {
            Log.d("SmsReceiver", "收到无效操作的广播：${intent.action}")
        }
    }
}










