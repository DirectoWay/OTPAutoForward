package com.otpautoforward.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.otpautoforward.dataclass.PairedDeviceInfo
import com.otpautoforward.dataclass.SettingKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException

/** 通用工具类 */
class GlobalHandler {
    private val tag = "GlobalHandler"

    fun getDeviceIPAddress(context: Context): String? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: Network = connectivityManager.activeNetwork ?: return null
        val capabilities: NetworkCapabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        val linkProperties: LinkProperties =
            connectivityManager.getLinkProperties(activeNetwork) ?: return null

        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            val addresses = linkProperties.linkAddresses
            for (address in addresses) {
                if (!address.address.isLoopbackAddress && address.address is Inet4Address) { // 检查是否是 IPv4 地址
                    return address.address.hostAddress
                }
            }
        }
        return null
    }

    /** 模拟获取局域网中在线的设备 */
    fun getOnlineDevicesTest(): List<String> {
        val devices = mutableListOf<String>()
        val websocketUrl = "ws://192.168.1.107:9224"
        devices.add(websocketUrl)
        return devices
    }

    suspend fun getOnlineDevices(targetPort: Int): List<String> {
        val localNetworkPrefix = getLocalNetworkPrefix()
        val devices = mutableListOf<String>()

        Log.d(tag, "开始扫描局域网设备...")
        val startTime = System.currentTimeMillis()
        val chunkSize = 50 // 每批次 50 个 IP
        val ipBatches = (1..254).chunked(chunkSize)

        suspend fun scanIp(ip: String) {
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
            } catch (e: Exception) {
                Log.e(tag, "扫描局域网 IP = $ip 出现异常: $e")
            }
        }

        coroutineScope {
            ipBatches.forEach { batch ->
                batch.map { i ->
                    async(Dispatchers.IO) {
                        val ip = "$localNetworkPrefix.$i"
                        scanIp(ip)
                    }
                }.awaitAll()
            }
        }

        val endTime = System.currentTimeMillis()
        Log.d(tag, "局域网设备扫描结束，在线设备总数: ${devices.size}")
        Log.d(tag, "局域网设备扫描耗时: ${endTime - startTime} ms")

        return devices
    }

    /** 获取局域网 IP 的前缀 */
    private fun getLocalNetworkPrefix(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }        // 展开 IP 地址
                .filterIsInstance<Inet4Address>()                 // 筛选 IPv4 地址
                .firstOrNull { !it.isLoopbackAddress }            // 找到第一个非回环地址
                ?.hostAddress                                     // 提取 IP 地址
                ?.substringBeforeLast('.')                 // 提取前缀
        } catch (e: SocketException) {
            Log.e(tag, "获取局域网 IP 前缀失败", e)
            null
        }
    }

    /** 判断是否存在已配对的设备信息 返回 true 时表示至少存在一个已配对的设备 */
    fun hasPairedDevice(context: Context): Boolean {
        val sharedPreferences =
            context.getSharedPreferences(SettingKey.PairedDevices.key, Context.MODE_PRIVATE)
        val allDeviceIds = sharedPreferences.all.keys
        return allDeviceIds.isNotEmpty()
    }

    /** 获取所有已配对的设备信息 */
    fun getAllDevicesInfo(context: Context): List<PairedDeviceInfo> {
        val sharedPreferences =
            context.getSharedPreferences(SettingKey.PairedDevices.key, Context.MODE_PRIVATE)
        val allDeviceIds = sharedPreferences.all.keys
        val allDevicesInfo = mutableListOf<PairedDeviceInfo>()

        for (deviceId in allDeviceIds) {
            val deviceInfoString = sharedPreferences.getString(deviceId, null)
                ?: throw Exception("获取到的设备信息为空")

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

        return allDevicesInfo
    }

    /** 通过设备 ID 查询对应的公钥 */
    fun getWindowsPublicKey(context: Context, deviceId: String): String? {
        val sharedPreferences =
            context.getSharedPreferences(SettingKey.PairedDevices.key, Context.MODE_PRIVATE)
        val deviceInfoString = sharedPreferences.getString(deviceId, null)
        val deviceInfo = deviceInfoString?.let { JSONObject(it) }
        if (deviceInfo == null) {
            return null
        }
        return deviceInfo.getString("windowsPublicKey")
    }

}