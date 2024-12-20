package com.otpautoforward.dataclass

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
/** 与 Win 端连接所需的配置信息 */
data class PairedDeviceInfo(
    val deviceName: String,
    val deviceId: String,
    val deviceType: String,
    val windowsPublicKey: String
) : Parcelable
