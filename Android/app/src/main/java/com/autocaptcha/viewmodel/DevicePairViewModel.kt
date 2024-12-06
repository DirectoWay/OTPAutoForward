package com.autocaptcha.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class DevicePairViewModel(application: Application) : AndroidViewModel(application) {
    /** 用于通知 PairFragment 刷新当前已配对的设备 */
    val refreshPairedDevice = MutableLiveData<Unit>()

    private val sharedPreferences =
        application.getSharedPreferences("SmsSwitch", Context.MODE_PRIVATE)
    private val editor = sharedPreferences.edit()
    private val _smsEnabled = MutableLiveData<Boolean>()
    val smsEnabled: LiveData<Boolean> = _smsEnabled

    init {
        refreshPairedDevice.postValue(Unit) // 初始化默认值
        loadSmsEnabled()
    }

    fun updateSmsEnabled(value: Boolean) {
        editor.putBoolean("SmsSwitch", value).apply()
        _smsEnabled.value = value
    }

    /** 获取 "短信转发" 开关的状态 */
    fun getSmsEnabledStatus(): Boolean {
        return sharedPreferences.getBoolean("SmsSwitch", true)
    }

    private fun loadSmsEnabled() {
        // 设置 "短信转发" 起始状态的默认值
        if (!sharedPreferences.contains("SmsSwitch")) {
            editor.putBoolean("SmsSwitch", true).apply()
        }
        _smsEnabled.value = sharedPreferences.getBoolean("SmsSwitch", true)
    }
}