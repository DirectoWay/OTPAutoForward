package com.otpautoforward.dataclass

/** SharedPreferences 会用到的键值 */
enum class SettingKey(val key: String) {
    /** 用于从 sharedPreferences 中获取 App 设置的键 */
    AppPreferences("AppPreferences"),

    /** "短信转发" 开关的状态 */
    SmsEnabled("SmsEnabled"),

    /** "仅在锁屏时转发" 开关的状态 */
    ScreenLocked("forwardOnlyScreenLocked"),

    /** "同步系统免打扰" 开关的状态 */
    SyncDoNotDistribute("syncDoNotDistribute"),

    /** "仅转发验证码" 开关的状态 */
    ForwardOnlyOTP("forwardOnlyOTP"),

    /** "蓝牙优先模式" 开关的状态 */
    BluetoothPriorityMode("BluetoothPriorityMode"),

    /** 已配对的设备 */
    PairedDevices("PairedDevices"),

    /** 当前的 UI 配色 */
    UIColor("UIColor"),

    /** 当前的副系配色 */
    SubColor("SubColor"),

    /** 局域网网络前缀 */
    LocalNetworkPrefixes("LocalNetworkPrefixes"),

    /** 测试用消息的内容 */
    TestMessage("TestMassage"),

    /** 权限申请记录 */
    PermissionFlags("PermissionFlags"),

    /** 蓝牙权限申请 */
    BluetoothPermission("BluetoothPermission")
}