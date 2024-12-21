package com.otpautoforward.handler

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.otpautoforward.activity.PairedDeviceSettingsActivity
import com.otpautoforward.dataclass.DisplayDeviceInfo
import com.otpautoforward.R
import com.otpautoforward.databinding.FragmentPairDeviceinfoBinding
import com.otpautoforward.fragment.MainFragment

/** 动态的给配对页面的 RecyclerView 中生成元素, 用以动态生成已经配对的设备信息 */
class DeviceHandler(private val container: LinearLayout) {
    private var globalHandler: GlobalHandler = GlobalHandler()

    /** 测试用的模拟设备 */
    private val testDeviceList: List<DisplayDeviceInfo> = listOf(
        DisplayDeviceInfo(
            R.drawable.sharp_desktop_windows_24,
            "Administrator@PC-DIRECTOWAY",
            R.drawable.baseline_settings_24,
            "ABC"
        ),
        DisplayDeviceInfo(
            R.drawable.baseline_computer_24,
            "User@Laptop",
            R.drawable.baseline_settings_24,
            "ABDD"
        ),
        DisplayDeviceInfo(
            R.drawable.baseline_phone_iphone_24,
            "Mobile Device",
            R.drawable.baseline_settings_24,
            "AAAA"
        ),
    )

    fun getDeviceInfo(context: Context): List<DisplayDeviceInfo> {
        val allDevicesInfo = globalHandler.getAllDevicesInfo(context.applicationContext)

        return allDevicesInfo.map { pairingInfo ->
            DisplayDeviceInfo(
                typeIcon = getDeviceIconForType(pairingInfo.deviceType),
                deviceName = pairingInfo.deviceName,
                settingIcon = R.drawable.baseline_settings_24, // 设置图标
                deviceId = pairingInfo.deviceId
            )
        }
    }

    /** 按照设备类型设置图标 */
    private fun getDeviceIconForType(deviceType: String): Int {
        return when (deviceType.lowercase()) {
            "desktop" -> R.drawable.sharp_desktop_windows_24
            "laptop" -> R.drawable.sharp_laptop_mac_24
            "mobile" -> R.drawable.baseline_phone_iphone_24
            else -> R.drawable.baseline_device_unknown_24 // 默认未知设备图标
        }
    }

    /** 动态绑定设备信息与图标 */
    fun bindDeviceInfo(devices: List<DisplayDeviceInfo>, fragment: MainFragment) {
        container.removeAllViews() // 清空之前的视图
        for (device in devices) {
            val binding = FragmentPairDeviceinfoBinding.inflate(
                LayoutInflater.from(container.context), container, false
            )

            binding.iconPairedDeviceType.setImageResource(device.typeIcon)
            binding.iconPairedSetting.setImageResource(device.settingIcon)
            binding.textPairedDeviceName.text = device.deviceName

            // 给已配对的设备绑定点击事件
            binding.viewPairedDeviceContainer.setOnClickListener {
                fragment.settingIcon = binding.iconPairedSetting

                val intent =
                    Intent(container.context, PairedDeviceSettingsActivity::class.java).apply {
                        putExtra("DEVICE_ID", device.deviceId)
                        putExtra("DEVICE_Name", device.deviceName)
                        putExtra("DEVICE_TYPE_ICON", device.typeIcon)
                    }
                container.context.startActivity(intent)
            }
            container.addView(binding.root) // 将设备视图添加到容器
        }
    }

}
