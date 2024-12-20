package com.otpautoforward.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

enum class SettingKey(val key: String) {
    AppPreferences("AppPreferences"),
    SmsEnabled("SmsEnabled"),
    ScreenLocked("forwardOnlyScreenLocked"),
    SyncDoNotDistribute("syncDoNotDistribute"),
    ForwardOnlyOTP("forwardOnlyOTP")
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    /** 用于通知 MainFragment 刷新当前已配对的设备 */
    val refreshPairedDevice = MutableLiveData<Unit>()

    private val sharedPreferences =
        application.getSharedPreferences(
            SettingKey.AppPreferences.key,
            Context.MODE_PRIVATE
        )
    private val editor = sharedPreferences.edit()

    private val _settings = MutableLiveData<Map<String, Boolean>>()
    val settings: LiveData<Map<String, Boolean>> = _settings

    init {
        refreshPairedDevice.postValue(Unit) // 初始化 "已配对设备" 的默认值
        loadSettings()  // 加载 Settings 的默认值
    }

    /** 更新设置 */
    fun updateSetting(key: String, value: Boolean) {
        editor.putBoolean(key, value).apply()
        loadSettings()  // 更新设置后重新加载
    }

    /** 加载设置页面的用户设置 */
    private fun loadSettings() {
        val map = mutableMapOf(
            SettingKey.SmsEnabled.key to sharedPreferences.getBoolean(
                SettingKey.SmsEnabled.key,
                true
            ),
            SettingKey.ScreenLocked.key to sharedPreferences.getBoolean(
                SettingKey.ScreenLocked.key, true
            ),
            SettingKey.SyncDoNotDistribute.key to sharedPreferences.getBoolean(
                SettingKey.SyncDoNotDistribute.key,
                true
            ),
            SettingKey.ForwardOnlyOTP.key to sharedPreferences.getBoolean(
                SettingKey.ForwardOnlyOTP.key,
                true
            )
        )
        // 设置默认值
        if (!sharedPreferences.contains(SettingKey.SmsEnabled.key)) {
            editor.putBoolean(SettingKey.SmsEnabled.key, true).apply()
        }

        if (!sharedPreferences.contains(SettingKey.ScreenLocked.key)) {
            editor.putBoolean(SettingKey.ScreenLocked.key, true)
            editor.apply()
        }

        if (!sharedPreferences.contains(SettingKey.SyncDoNotDistribute.key)) {
            editor.putBoolean(SettingKey.SyncDoNotDistribute.key, true)
            editor.apply()
        }

        if (!sharedPreferences.contains(SettingKey.ForwardOnlyOTP.key)) {
            editor.putBoolean(SettingKey.ForwardOnlyOTP.key, true)
            editor.apply()
        }
        _settings.value = map
    }
}