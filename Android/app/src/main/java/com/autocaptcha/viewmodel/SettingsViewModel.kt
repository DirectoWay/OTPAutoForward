package com.autocaptcha.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPreferences =
        application.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
    private val editor = sharedPreferences.edit()

    private val _settings = MutableLiveData<Map<String, Boolean>>()
    val settings: LiveData<Map<String, Boolean>> = _settings

    init {
        loadSettings()
    }

    fun updateSetting(key: String, value: Boolean) {
        editor.putBoolean(key, value).apply()
        loadSettings()
    }

    /** 加载设置页面的用户设置 */
    private fun loadSettings() {
        val map = mutableMapOf(
            "forwardOnlyScreenLocked" to sharedPreferences.getBoolean(
                "forwardOnlyScreenLocked", true
            ),
        )
        // 设置默认值
        if (!sharedPreferences.contains("forwardOnlyScreenLocked")) {
            editor.putBoolean("forwardOnlyScreenLocked", true)
            editor.apply()
        }

        _settings.value = map
    }
}
