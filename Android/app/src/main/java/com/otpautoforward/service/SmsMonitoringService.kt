package com.otpautoforward.service

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SmsMessage
import android.util.Log
import com.otpautoforward.handler.WebSocketWorker
import com.otpautoforward.dataclass.SettingKey

@Suppress("DEPRECATION")
class SmsReceiver : BroadcastReceiver() {
    private lateinit var appContext: Context
    private val tag = "SmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        appContext = context.applicationContext // 获取应用上下文
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED" &&
            intent.action != "com.OTPAutoForward.TEST_SMS_RECEIVED"
        ) {
            Log.d(tag, "收到无效操作的广播：${intent.action}")
            return
        }

        if (!isWifiConnected()) {
            Log.d(tag, "当前 WiFi 不可用, 不再转发短信")
            return
        }

        // 读取 settings 配置
        val sharedPreferences =
            appContext.getSharedPreferences(
                SettingKey.AppPreferences.key,
                Context.MODE_PRIVATE
            )

        // 关闭 "短信转发" 后, 不再处理任何后续逻辑
        if (!sharedPreferences.getBoolean(SettingKey.SmsEnabled.key, true)) {
            return
        }

        // 开启 "跟随系统免打扰" 且系统处于免打扰模式的时候, 不再转发短信
        if (sharedPreferences.getBoolean(SettingKey.SyncDoNotDistribute.key, true)) {
            if (isDoNotDisturb()) {
                Log.d(tag, "系统正处于免打扰模式, 不再转发短信")
                return
            }
        }

        if (isScreenOn()) {
            if (sharedPreferences.getBoolean(SettingKey.ScreenLocked.key, true)) {
                Log.d(tag, "仅锁屏转发已开启, 当前处于亮屏状态, 不再转发短信")
                return
            }
        }

        when (intent.action) {
            "android.provider.Telephony.SMS_RECEIVED" -> {
                handleSms(intent, sharedPreferences)
            }

            // 单独处理测试用的短信内容
            "com.OTPAutoForward.TEST_SMS_RECEIVED" -> {
                handleTestSms(intent)
            }
        }
    }

    private fun handleSms(intent: Intent, sharedPreferences: SharedPreferences) {
        val bundle = intent.extras
        if (bundle != null) {
            try {
                val pdus = bundle.get("pdus") as Array<*>
                val format = bundle.getString("format")
                for (pdu in pdus) {
                    val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray, format)
                    val messageBody = smsMessage.displayMessageBody
                    val sender = smsMessage.displayOriginatingAddress

                    if (!isOTPMessage(messageBody)) {
                        if (sharedPreferences.getBoolean(SettingKey.ForwardOnlyOTP.key, true)) {
                            Log.d(tag, "仅转发验证码已开启, 不转发非验证码短信")
                            continue
                        }
                    }

                    WebSocketWorker.sendWebSocketMessage(appContext, "$messageBody\n发送者：$sender")
                    Log.d(tag, "已转发短信：$messageBody 发送者：$sender")
                }
            } catch (e: Exception) {
                Log.e(tag, "解析短信pdus数据时发生异常:", e)
                WebSocketWorker.sendWebSocketMessage(
                    appContext,
                    "【短信异常】解析短信时发生错误, 请您在手机上查看原短信内容\n" + "发送者：自动异常处理"
                )
            }
        } else {
            Log.d(tag, "未收到有效的短信数据")
        }
    }

    private fun handleTestSms(intent: Intent) {
        val extraValue = intent.getStringExtra("extra_test_sms")
        WebSocketWorker.sendWebSocketMessage(appContext, "$extraValue")
    }

    /** 判断当前是否处于 WiFi 状态 */
    private fun isWifiConnected(): Boolean {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities =
            connectivityManager.getNetworkCapabilities(network)
        return capabilities != null && capabilities.hasTransport(
            NetworkCapabilities.TRANSPORT_WIFI
        )
    }

    /** 判断系统是否开启了免打扰模式 */
    private fun isDoNotDisturb(): Boolean {
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val currentInterruptionFilter = notificationManager.currentInterruptionFilter
        return currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    /** 判断是否处于亮屏(非锁屏)状态 */
    private fun isScreenOn(): Boolean {
        val keyguardManager =
            appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return !keyguardManager.isKeyguardLocked
    }

    /** 判断是否为验证码短信 */
    private fun isOTPMessage(message: String?): Boolean {
        val keywords = hashSetOf(
            "验证码", "校验码", "检验码", "确认码", "激活码", "动态码", "安全码", "验证代码",
            "校验代码", "检验代码", "激活代码", "确认代码", "动态代码", "安全代码", "登入码",
            "认证码", "识别码", "短信口令", "动态密码", "交易码", "上网密码", "随机码", "动态口令",
            "驗證碼", "校驗碼", "檢驗碼", "確認碼", "激活碼", "動態碼", "驗證代碼", "校驗代碼",
            "檢驗代碼", "確認代碼", "激活代碼", "動態代碼", "登入碼", "認證碼", "識別碼",
            "Code", "code", "CODE"
        )
        message?.let {
            keywords.forEach { keyword ->
                if (it.contains(keyword, ignoreCase = true)) {
                    println("Matched keyword: $keyword")
                    return true
                }
            }
        }

        return message?.let {
            keywords.any { keyword -> it.contains(keyword, ignoreCase = true) }
        } ?: false
    }
}






