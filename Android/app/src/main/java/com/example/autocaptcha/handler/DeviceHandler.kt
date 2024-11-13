package com.example.autocaptcha.handler

import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autocaptcha.R

/* DeviceInfo类用于保存已经配对的设备信息 */
data class DeviceInfo(val typeIcon: Int, val deviceName: String, val settingIcon: Int)

/* 动态的给配对页面的RecyclerView中生成元素, 用以动态生成已经配对的设备信息 */
class DeviceHandler(private val container: LinearLayout) {

    fun getDeviceInfo(context: Context): List<DeviceInfo> {
        val webSocketHandler = WebSocketHandler.getInstance();
        val allDevicesInfo = webSocketHandler.getAllDevicesInfo(context);

        return allDevicesInfo.map { pairingInfo ->
            DeviceInfo(
                typeIcon = getDeviceIconForType(pairingInfo.deviceType),
                deviceName = pairingInfo.deviceName,
                settingIcon = R.drawable.baseline_settings_24 // 设置图标
            )
        }
    }

    // 按照设备类型设置图标
    private fun getDeviceIconForType(deviceType: String): Int {
        return when (deviceType.lowercase()) {
            "desktop" -> R.drawable.sharp_desktop_windows_24
            "laptop" -> R.drawable.sharp_laptop_mac_24
            "mobile" -> R.drawable.baseline_phone_iphone_24
            else -> R.drawable.baseline_device_unknown_24 // 默认未知设备图标
        }
    }

    // 动态绑定设备信息与图标
    fun bindDeviceInfo(devices: List<DeviceInfo>) {
        container.removeAllViews() // 清空之前的视图
        for (device in devices) {
            val itemView = LayoutInflater.from(container.context)
                .inflate(R.layout.fragment_pair_deviceinfo, container, false)
            val iconType: ImageView = itemView.findViewById(R.id.icon_paired_deviceType)
            val iconSetting: ImageView = itemView.findViewById(R.id.icon_paired_setting)
            val name: TextView = itemView.findViewById(R.id.text_paired_deviceName)

            iconType.setImageResource(device.typeIcon)
            iconSetting.setImageResource(device.settingIcon)
            name.text = device.deviceName

            container.addView(itemView) // 将设备视图添加到容器
        }
    }
}
