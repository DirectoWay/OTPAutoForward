package com.otpautoforward.handler

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.otpautoforward.dataclass.WebSocketEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Win 端提供 WebSocket 服务的端口号 */
private const val WebSocketPort = 9224

/** 建立 WebSocket 连接时, 请求头中的自定义字段 */
private const val WebSocketHeaderField = "X-OTPAutoForward-Auth"

/** 建立 WebSocket 连接时, 请求头密文中必须包含的内容 */
private const val WebSocketHeaderKey = "OTPAForward-encryptedKey"

/** 等待 Win 端回复 WebSocket 消息的时间 */
private const val MESSAGE_TIMEOUT = 5 * 1000L

/** WebSocket 建立成功后, 验证 Win 端身份时, 消息头中必须包含的字段 */
private const val VALIDATION_FIELD = "verification"

/** 通过 IP 地址进行配对时, Win 端 Websocket 消息中必须包含的消息头与确认字段 */
private const val PAIRING_FIELD = "pairByIp"

private const val PAIRINFO_FIELD = "pairInfo"

/** Win 端结束 WebSocket 连接时, 消息头中的确认字段 */
private const val CONFIRMED_FIELD = "confirmed"

/** 用于给 WorkManager 传递待发消息的 Key */
private const val KEY_MESSAGE = "message"

private const val KEY_WEBSOCKET = "websocketPath"

/** WorkManager 操作异常后当前重试次数的 Key */
const val KEY_RETRY_COUNT = "retry_count"

/** WorkManager 操作异常后最大的重试次数 */
const val WORK_MAX_RETRY_COUNT = 3


/** 为 WebSocket 连接时提供工具方法 */
class WebSocketWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    private val globalHandler = GlobalHandler()
    private val keyHandler = KeyHandler()
    private val tag = "WebSocketWorker"

    companion object {
        /** 创建给 Win 端发送消息的 WorkRequest */
        fun sendWebSocketMessage(context: Context, message: String, websocketPath: String? = null) {

            val inputData = Data.Builder()
                .putString(KEY_MESSAGE, message)
                .putString(KEY_WEBSOCKET, websocketPath)
                .build()

            val expeditedWorkRequest =
                OneTimeWorkRequestBuilder<WebSocketWorker>().setInputData(inputData)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL, 2_000, // 最小退避时间
                        TimeUnit.MILLISECONDS
                    ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()

            WorkManager.getInstance(context.applicationContext).enqueue(expeditedWorkRequest)
        }
    }

    override suspend fun doWork(): Result {
        val currentRetryCount = inputData.getInt(KEY_RETRY_COUNT, 0)
        var message = inputData.getString(KEY_MESSAGE)
        val webSocketPath = inputData.getString(KEY_WEBSOCKET)

        return try {
            val webSocketInfo = mutableListOf<String>()
            if (webSocketPath != null) {
                webSocketInfo.add(webSocketPath)
            } else {
                webSocketInfo.addAll(globalHandler.getOnlineDevices(WebSocketPort))
            }

            if (message == null) {
                Log.e(tag, "WorkManager 获取 WebSocket 待发消息时异常")
                return Result.failure()
            }
            if (webSocketInfo.isEmpty()) {
                Log.e(tag, "该局域网中暂无在线设备")
                return Result.failure()
            }
            message = keyHandler.encryptString(message) // 加密信息内容
            connectWebSocket(webSocketInfo, message)
            Result.success()
        } catch (e: TimeoutCancellationException) {
            // 处理超时情况
            Log.e(tag, "WebSocket 连接超时")
            Result.failure()
        } catch (e: Exception) {
            if (currentRetryCount >= WORK_MAX_RETRY_COUNT) {
                Result.failure() // 超过最大重试次数直接失败
            }

            val outputData = Data.Builder()
                .putInt(KEY_RETRY_COUNT, currentRetryCount + 1)
                .build()

            setProgressAsync(outputData) // 更新重试进度
            Log.e(tag, "WebSocket 连接失败", e)
            Result.retry()
        }
    }

    private suspend fun connectWebSocket(webSocketInfo: List<String>, message: String) {
        withContext(Dispatchers.IO) {
            val deferredResults = webSocketInfo.map { serverUrl ->
                async {
                    handleWebSocket(serverUrl, message)
                }
            }

            val webSockets = deferredResults.awaitAll()

            // WebSocket 连接失败的设备
            val failedDevices = webSockets.filter { it == null }
            if (failedDevices.isNotEmpty()) {
                Log.w(tag, "${failedDevices.size}台设备未能成功连接")
            }
        }
    }

    /** 处理每个设备的 WebSocket 连接状态 */
    private suspend fun handleWebSocket(serverUrl: String, message: String): WebSocket? {
        val webSocketRef = AtomicReference<WebSocket>()
        val client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(serverUrl)
            .addHeader(WebSocketHeaderField, generateWebSocketHeader())
            .build()

        val connectionResult = CompletableDeferred<WebSocket?>()

        fun webSocketComplete(result: WebSocket?) {
            if (!connectionResult.isCompleted) {
                connectionResult.complete(result)
            }
        }

        val webSocketListener = object : WebSocketListener() {
            private var lastReceivedMessage: String? = null
            private val isVerified = AtomicBoolean(false)

            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.d(tag, "WebSocket 连接成功: $serverUrl")
                webSocketRef.set(webSocket)
                webSocketComplete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(tag, "收到 WebSocket 消息: $text\n设备: $serverUrl")
                lastReceivedMessage = text

                if (!isVerified.get() && !handleVerification(webSocket, text)) return
                if (handleConfirmedMessage(webSocket, text)) return

                if (text.contains(PAIRINFO_FIELD)) {
                    EventBus.getDefault().post(WebSocketEvent(text))
                    return
                }
                webSocket.send(message)
            }

            private fun handleVerification(webSocket: WebSocket, text: String): Boolean {
                if (text.contains(PAIRING_FIELD)) {
                    if (!verifyPairingField(text)) {
                        closeWithFailure(webSocket, "通过 IP 进行配对时 Win 端身份验证失败")
                        return false
                    }
                } else if (!verifyWebSocketInfo(text)) {
                    closeWithFailure(webSocket, "Win 端身份验证失败")
                    return false
                }
                isVerified.set(true)
                return true
            }

            private fun handleConfirmedMessage(webSocket: WebSocket, text: String): Boolean {
                if (!text.contains(CONFIRMED_FIELD)) return false
                Log.d(tag, "收到确认消息，关闭 WebSocket 连接")
                webSocket.close(1000, "消息已确认")
                return true
            }

            private fun closeWithFailure(webSocket: WebSocket, reason: String) {
                Log.e(tag, "WebSocket 验证失败: $serverUrl")
                webSocket.close(1000, reason)
                webSocketComplete(null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(tag, "WebSocket 连接失败: ${t.message}, 设备: $serverUrl")
                webSocketComplete(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket 已关闭: $reason, 设备: $serverUrl")
                webSocketComplete(webSocket)
            }
        }

        client.newWebSocket(request, webSocketListener)
        return try {
            connectionResult.await()
        } catch (e: Exception) {
            Log.e(tag, "连接 WebSocket 过程中出现异常: ${e.message}")
            null
        }
    }

    /** 生成 WebSocket 请求头内容 用于给 Win 端验证 App 端的身份 */
    private fun generateWebSocketHeader(): String {
        val timestamp = System.currentTimeMillis() / 1000 // 精准到秒即可, 与 Win 端的时间戳格式保持一致
        val plainHeader = "$WebSocketHeaderKey+$timestamp" // 合并密钥与时间戳, 用加号进行分隔
        val cipherHeader = keyHandler.encryptString(plainHeader) // 加密请求头内容
        return cipherHeader
    }

    /** 校验 Win 端的 WebSocket验证信息 */
    private fun verifyWebSocketInfo(verifyInfo: String): Boolean {
        // 检查消息头
        if (!verifyInfo.contains(VALIDATION_FIELD)) {
            return false
        }

        try {
            // 分割加密内容和签名
            val parts = verifyInfo.split(".")
            if (parts.size != 3) throw Exception("Win 端的验证消息格式异常")

            val encryptedText = parts[1]
            val signature = parts[2]

            // 进行验证内容解密, Win 端需要传递设备 ID
            val deviceId = keyHandler.decryptString(encryptedText) ?: throw Exception()
            val publicKey =
                globalHandler.getWindowsPublicKey(applicationContext, deviceId) ?: throw Exception()
            return keyHandler.verifySignature(deviceId, signature, publicKey) // 校验签名
        } catch (ex: Exception) {
            Log.e(tag, "校验 Win 端的 WebSocket验证信息时发生异常: $ex")
            return false
        }
    }

    private fun verifyPairingField(message: String): Boolean {
        // 检查消息头
        if (!message.contains(PAIRING_FIELD)) {
            return false
        }

        try {
            // 分割消息头和消息内容
            val parts = message.split(".")
            if (parts.size != 2) throw Exception("通过 IP 地址进行配对时, Win 端的验证消息不符合格式")

            val encryptedText = parts[1] // 消息正文

            val plainText = keyHandler.decryptString(encryptedText) ?: throw Exception()

            // 分割用于验证的字段和时间戳
            val messageParts = plainText.split("+")
            if (messageParts.size != 2) throw Exception("通过 IP 地址进行配对时, Win 端的验证内容不符合格式")

            val pairInfoField = messageParts[0] // 用于验证的字段
            if (pairInfoField != PAIRING_FIELD) return false

            val serverTimestamp = messageParts[1].toLong() // Win 端发来的时间戳
            val timestamp = System.currentTimeMillis() / 1000 // 精准到秒即可, 与 Win 端的时间戳格式保持一致

            val differenceInSeconds = abs(timestamp - serverTimestamp)
            return differenceInSeconds <= MESSAGE_TIMEOUT

        } catch (ex: Exception) {
            Log.e(tag, "通过 IP 进行配对时, Win 端身份校验异常: $ex")
            return false
        }
    }
}

