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
import com.otpautoforward.dataclass.PairedDeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Win 端提供 WebSocket 服务的端口号 */
private const val WebSocketPort = 9224

/** WebSocket 请求头自定义字段 */
private const val WebSocketHeaderField = "X-OTPAutoForward-Auth"

/** WebSocket 请求头中必须包含的密钥 */
private const val WebSocketHeaderKey = "OTPAForward-encryptedKey"

/** 等待 Win 端回复 WebSocket 消息的时间 */
private const val MESSAGE_TIMEOUT = 5 * 1000L

/** Win 端没收到消息的时候, 重发消息的次数 */
private const val WEBSOCKET_MESSAGE_RETRY = 1

/** 用于验证 Win 端 WebSocket 身份的字段名称 */
private const val VALIDATION_FIELD = "verification"

/** Win 端 WebSocket 消息的确认字段 */
private const val CONFIRMED_FIELD = "confirmed"

/** 用于给 WorkManager 传递待发消息的 Key */
private const val KEY_MESSAGE = "message"

/** WorkManager 操作异常后当前重试次数的 Key */
const val KEY_RETRY_COUNT = "retry_count"

/** WorkManager 操作异常后最大的重试次数 */
const val WORK_MAX_RETRY_COUNT = 3


/** 为 WebSocket 连接时提供工具方法 */
class WebSocketWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    private val webSocketHandler = WebSocketHandler()
    private val keyHandler = KeyHandler()
    private val tag = "WebSocketWorker"

    companion object {
        /** 创建给 Win 端发送消息的 WorkRequest */
        fun sendWebSocketMessage(context: Context, message: String) {

            val inputData = Data.Builder().putString(KEY_MESSAGE, message).build()

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
        return try {
            val webSocketInfo = webSocketHandler.getOnlineDevices(WebSocketPort)

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
            } else {
                val outputData = Data.Builder()
                    .putInt(KEY_RETRY_COUNT, currentRetryCount + 1)
                    .build()

                setProgressAsync(outputData) // 更新重试进度
                Log.e(tag, "WebSocket 连接失败", e)
                Result.retry()
            }
        }
    }

    private suspend fun connectWebSocket(webSocketInfo: List<String>, message: String) {
        withContext(Dispatchers.IO) {
            /** 每个设备的 WebSocket连接状态 */
            val webSocketRefs = webSocketInfo.map { AtomicReference<WebSocket>() }

            val deferredResults = webSocketInfo.mapIndexed { index, serverUrl ->
                async {
                    webSocketRefs[index].let { webSocketRef ->
                        try {
                            val client = OkHttpClient.Builder()
                                .pingInterval(30, TimeUnit.SECONDS) // 保持连接活跃
                                .build()

                            val request = Request.Builder().url(serverUrl)
                                .addHeader(WebSocketHeaderField, generateWebSocketHeader())
                                .build()

                            val connectionResult = CompletableDeferred<WebSocket?>()

                            val webSocketListener = object : WebSocketListener() {
                                private var timeoutJob: Job? = null

                                /** WebSocket消息重发次数 */
                                private var retryCount = 0

                                /** Win 端回复的最近一条消息 */
                                private var lastReceivedMessage: String? = null

                                /** Win 端身份验证标识, true 表示本次连接中该设备已通过验证 */
                                private val isVerified = AtomicBoolean(false)

                                override fun onOpen(
                                    webSocket: WebSocket,
                                    response: okhttp3.Response
                                ) {
                                    Log.d(tag, "WebSocket 连接成功: $serverUrl")
                                    webSocketRef.set(webSocket)
                                    // 等待 Win 端传递他自己的身份信息
                                    connectionResult.complete(webSocket)
                                    startConnectionTimeout(webSocket)
                                }

                                override fun onMessage(webSocket: WebSocket, text: String) {
                                    Log.d(tag, "WebSocket 消息: $text 设备: $serverUrl")

                                    lastReceivedMessage = text

                                    // 验证 Win 端的身份
                                    if (!isVerified.get() && text.contains(VALIDATION_FIELD)) {
                                        if (verifyWebSocketInfo(text) == true) {
                                            isVerified.set(true)
                                            message.let { webSocket.send(it) }
                                        } else {
                                            Log.e(tag, "WebSocket 验证失败: 设备 $serverUrl")
                                            webSocket.close(1000, "Win 端身份验证失败")
                                        }
                                    }

                                    // 判断是否收到确认消息
                                    if (text.contains(CONFIRMED_FIELD)) {
                                        Log.d(tag, "收到确认消息，关闭 WebSocket 连接")
                                        webSocket.close(1000, "消息已确认")
                                    }

                                    resetConnectionTimeout(webSocket)
                                }

                                /** 重置连接超时计时器 */
                                private fun resetConnectionTimeout(webSocket: WebSocket) {
                                    timeoutJob?.cancel()
                                    startConnectionTimeout(webSocket)
                                }

                                private fun startConnectionTimeout(webSocket: WebSocket) {
                                    timeoutJob = CoroutineScope(Dispatchers.IO).launch {
                                        delay(MESSAGE_TIMEOUT)

                                        if (lastReceivedMessage != null && !lastReceivedMessage!!.contains(
                                                CONFIRMED_FIELD
                                            )
                                        ) {
                                            if (retryCount < WEBSOCKET_MESSAGE_RETRY) {
                                                retryCount++
                                                Log.d(tag, "WebSocket 未收到确认消息，正在重发")
                                                webSocket.send(message)
                                            } else {
                                                Log.d(tag, "WebSocket 重试次数已达到上限")
                                                webSocket.close(1000, "未收到确认消息")
                                            }
                                        }
                                    }
                                }

                                override fun onFailure(
                                    webSocket: WebSocket,
                                    t: Throwable,
                                    response: okhttp3.Response?
                                ) {
                                    Log.e(tag, "WebSocket 连接失败: ${t.message}, 设备: $serverUrl")
                                    connectionResult.complete(null)
                                }

                                override fun onClosed(
                                    webSocket: WebSocket,
                                    code: Int,
                                    reason: String
                                ) {
                                    Log.d(tag, "WebSocket 已关闭: $reason, 设备: $serverUrl")
                                    webSocket.close(1000, reason)
                                    connectionResult.complete(webSocket)
                                }
                            }

                            client.newWebSocket(request, webSocketListener)

                            // 返回 WebSocket 连接对象
                            connectionResult.await()
                        } catch (e: Exception) {
                            Log.e(tag, "连接 WebSocket 过程中出现异常: ${e.message}")
                            null
                        }
                    }
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

    /** 生成 WebSocket 请求头内容 用于给 Win 端验证 App 端的身份 */
    private fun generateWebSocketHeader(): String {
        val timestamp = System.currentTimeMillis() / 1000 // 精准到秒即可, 与 Win 端的时间戳格式保持一致
        val plainHeader = "$WebSocketHeaderKey+$timestamp" // 合并密钥与时间戳, 用加号进行分隔
        val cipherHeader = keyHandler.encryptString(plainHeader) // 加密请求头内容
        return cipherHeader
    }

    /** 校验 Win 端的 WebSocket验证信息 */
    private fun verifyWebSocketInfo(verifyInfo: String): Boolean? {
        // 分割加密内容和签名
        val parts = verifyInfo.split(".")
        if (parts.size != 3) return null

        val encryptedText = parts[1]
        val signature = parts[2]

        // 进行验证内容解密, Win 端需要传递设备 ID
        val deviceId = keyHandler.decryptString(encryptedText) ?: return false
        val publicKey =
            webSocketHandler.getWindowsPublicKey(applicationContext, deviceId) ?: return false
        return keyHandler.verifySignature(deviceId, signature, publicKey) // 校验签名
    }
}

class WebSocketHandler {
    private val tag = "WebSocketHandler"

    /** 局域网中获取在线的设备 */
    suspend fun getOnlineDevices(targetPort: Int): List<String> {
        val localNetworkPrefix = getLocalNetworkPrefix()
        val devices = mutableListOf<String>()

        Log.d(tag, "开始扫描局域网设备...")
        val startTime = System.currentTimeMillis()
        val chunkSize = 50 // 每批次 50 个 IP
        val ipBatches = (1..254).chunked(chunkSize)

        coroutineScope {
            ipBatches.forEach { batch ->
                val jobs = batch.map { i ->
                    launch(Dispatchers.IO) {
                        val ip = "$localNetworkPrefix.$i"
                        try {
                            withTimeout(1000) { // 扫描任务超时时间
                                Socket().use { socket ->
                                    val address = InetSocketAddress(ip, targetPort)
                                    socket.connect(address, 200) // 连接探测的超时时间
                                    synchronized(devices) {
                                        val websocketUrl = "ws://$ip:$targetPort"
                                        devices.add(websocketUrl)
                                        Log.d(tag, "目标设备在线: $websocketUrl")
                                    }
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            Log.d(tag, "局域网设备扫描超时: IP = $ip")
                        } catch (e: IOException) {
                            // 扫到不在线的设备的时候, 这里异常会非常多, 不处理异常, 继续操作
                        } catch (e: Exception) {
                            Log.e(tag, "扫描局域网 IP = $ip 出现异常: ${e.message}")
                        }
                    }
                }

                jobs.joinAll()
            }
        }

        val endTime = System.currentTimeMillis()
        Log.d(tag, "局域网设备扫描结束，在线设备总数: ${devices.size}")
        Log.d(tag, "局域网设备扫描耗时: ${endTime - startTime} ms")

        return devices
    }

    /** 获取局域网 IP 的前缀 */
    private fun getLocalNetworkPrefix(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        val prefix = ip?.substring(0, ip.lastIndexOf('.'))
                        return prefix
                    }
                }
            }
        } catch (e: SocketException) {
            Log.e(tag, "获取局域网 IP 前缀失败")
        }
        return ""
    }

    /** 获取所有已配对的设备信息 */
    fun getAllDevicesInfo(context: Context): List<PairedDeviceInfo> {
        val sharedPreferences = context.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        val allDeviceIds = sharedPreferences.all.keys
        val allDevicesInfo = mutableListOf<PairedDeviceInfo>()

        for (deviceId in allDeviceIds) {
            val deviceInfoString = sharedPreferences.getString(deviceId, null)

            if (deviceInfoString != null) {
                try {
                    val deviceInfo = JSONObject(deviceInfoString)
                    val pairingInfo = PairedDeviceInfo(
                        deviceName = deviceInfo.getString("deviceName"),
                        deviceId = deviceInfo.getString("deviceId"),
                        deviceType = deviceInfo.getString("deviceType"),
                        windowsPublicKey = deviceInfo.getString("windowsPublicKey"),
                    )
                    allDevicesInfo.add(pairingInfo)
                } catch (e: JSONException) {
                    Log.e(tag, "获取设备信息失败: deviceId: $deviceId", e)
                }
            }
        }

        return allDevicesInfo
    }

    /** 通过设备 ID 查询对应的公钥 */
    fun getWindowsPublicKey(context: Context, deviceId: String): String? {
        val sharedPreferences = context.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        val deviceInfoString = sharedPreferences.getString(deviceId, null)
        return if (deviceInfoString != null) {
            val deviceInfo = JSONObject(deviceInfoString)
            deviceInfo.getString("windowsPublicKey")
        } else {
            return null
        }
    }

}
