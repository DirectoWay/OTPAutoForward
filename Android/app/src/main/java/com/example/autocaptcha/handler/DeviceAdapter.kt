package com.example.autocaptcha.handler

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.autocaptcha.R

/* DeviceInfo类用于保存已经配对的设备信息 */
data class DeviceInfo(val typeIcon: Int, val deviceName: String, val settingIcon: Int)

/* 动态的给配对页面的RecyclerView中生成元素, 用以动态生成已经配对的设备信息 */
class DeviceAdapter(private val devices: List<DeviceInfo>, private val container: LinearLayout) {
    fun bind() {
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
