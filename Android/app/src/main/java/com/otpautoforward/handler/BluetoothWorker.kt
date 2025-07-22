package com.otpautoforward.handler

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.akexorcist.bluetotohspp.library.BluetoothSPP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** 用于给 WorkManager 传递待发消息的 Key */
private const val KEY_MESSAGE = "message"
private const val KEY_BLUETOOTH = "deviceName"

/** 蓝牙消息确认字段 */
private const val CONFIRMED_FIELD = "confirmed"

class BluetoothWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    companion object {
        fun sendBluetoothMessage(context: Context, message: String, deviceName: String? = null) {

            val inputData = Data.Builder()
                .putString(KEY_MESSAGE, message)
                .putString(KEY_BLUETOOTH, deviceName)
                .build()

            val expeditedWorkRequest =
                OneTimeWorkRequestBuilder<BluetoothWorker>().setInputData(inputData)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL, 2_000, // 最小退避时间
                        TimeUnit.MILLISECONDS
                    ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()

            WorkManager.getInstance(context.applicationContext).enqueue(expeditedWorkRequest)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun doWork(): Result {
        messagePendingToSend = inputData.getString(KEY_MESSAGE)
        if (messagePendingToSend == null) {
            Log.e(tag, "WorkManager 获取蓝牙待发消息时异常")
            return Result.failure()
        }

        val deviceName = inputData.getString(KEY_BLUETOOTH)

        return try {
            if (deviceName != null) {
                connect(listOf(deviceName))
            } else {
                val deviceList = getDeviceName()
                if (deviceList == null) {
                    Log.w(tag, "暂无已配对的设备")
                    return Result.failure()
                }
                connect(deviceList)
            }
            Result.success()
        } catch (e: TimeoutCancellationException) {
            Log.e(tag, "蓝牙连接超时$e")
            messagePendingToSend?.let { WebSocketWorker.sendWebSocketMessage(context, it) }
            Result.failure()
        } catch (e: Exception) {
            Log.e(tag, "蓝牙消息任务执行异常 $e")
            messagePendingToSend?.let { WebSocketWorker.sendWebSocketMessage(context, it) }
            Result.failure()
        }
    }

    private val tag = "BluetoothHandler"
    private val keyHandler = KeyHandler()
    private val globalHandler = GlobalHandler()
    private lateinit var bluetoothSPP: BluetoothSPP

    /** 待发消息的原文 */
    private var messagePendingToSend: String? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getDeviceName(): List<String>? {
        return globalHandler.getAllDevicesInfo(context).map { it.deviceName }
    }

    /**
     * 向多个设备发起蓝牙连接
     *
     * @param deviceNames 用于进行蓝牙连接的设备名称
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connect(deviceNames: List<String>) {
        try {
            withContext(Dispatchers.IO) {
                val deferredResults = deviceNames.map { deviceName ->
                    async {
                        initBluetooth()
                        deviceName to handleBluetooth(deviceName)
                    }
                }.awaitAll()

                val failedDevices = deferredResults.filter { !it.second }.map { it.first }

                if (failedDevices.isNotEmpty()) {
                    Log.w(tag, "蓝牙设备连接失败: $failedDevices")
                }
            }
        } catch (ex: Exception) {
            throw ex
        }
    }

    private suspend fun initBluetooth() = withContext(Dispatchers.Main) {
        try {
            if (!::bluetoothSPP.isInitialized) {

                bluetoothSPP = BluetoothSPP(context)

                if (bluetoothSPP.bluetoothAdapter == null) {
                    Log.e(tag, "蓝牙适配器初始化失败")
                    throw Exception("蓝牙适配器初始化失败")
                }

                if (!bluetoothSPP.isBluetoothAvailable || !bluetoothSPP.isBluetoothEnabled) {
                    Log.e(tag, "蓝牙未开启或设备不支持蓝牙")
                    throw Exception("蓝牙未开启或设备不支持蓝牙")
                }

                bluetoothSPP.setOnDataReceivedListener { _, message ->
                    Log.d(tag, "收到消息: $message")
                    if (message.contains(CONFIRMED_FIELD)) {
                        disconnect()
                    } else {
                        Log.w(tag, "未收到 Win 端的确认消息, 即将通过 Websocket 补发")
                        throw Exception("未收到 Win 端的确认消息")
                    }
                    messagePendingToSend = null
                }

                bluetoothSPP.setBluetoothConnectionListener(object : BluetoothSPP.BluetoothConnectionListener {
                    override fun onDeviceConnected(name: String?, address: String?) {
                        Log.i(tag, "已通过蓝牙连接设备: $name [$address]")
                        messagePendingToSend?.let {
                            bluetoothSPP.send(keyHandler.encryptString(it), true) // 加密消息
                            Log.d(tag, "蓝牙消息已发送: $it")
                        }
                    }

                    override fun onDeviceDisconnected() {
                        Log.i(tag, "蓝牙已断开连接")
                    }

                    override fun onDeviceConnectionFailed() {
                        Log.e(tag, "蓝牙连接失败")
                    }
                })

                bluetoothSPP.setupService()
                bluetoothSPP.startService(false)
            }
        } catch (ex: Exception) {
            throw ex
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleBluetooth(deviceName: String): Boolean {
        val pairedDevices = bluetoothSPP.bluetoothAdapter?.bondedDevices
        val device = pairedDevices?.firstOrNull { it.name == deviceName }
        if (device == null) {
            Log.w(tag, "设备 $deviceName 暂未与 Windows 电脑进行蓝牙配对")
            return false
        }

        return try {
            bluetoothSPP.connect(device.address)
            Log.i(tag, "已找到配对的蓝牙设备: ${device.name}")
            true
        } catch (ex: Exception) {
            Log.e(tag, "蓝牙连接设备: ${device.name} 时异常: $ex")
            throw ex
        }
    }

    fun disconnect() {
        bluetoothSPP.disconnect()
        bluetoothSPP.stopService()
        Log.i(tag, "已断开蓝牙并停止蓝牙服务")
    }
}