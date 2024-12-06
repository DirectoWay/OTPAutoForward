package com.autocaptcha.dataclass

/** 用于在页面上展示已经配对的设备信息 */
data class DisplayDeviceInfo(
    val typeIcon: Int,
    val deviceName: String,
    val settingIcon: Int,
    val deviceId: String
)