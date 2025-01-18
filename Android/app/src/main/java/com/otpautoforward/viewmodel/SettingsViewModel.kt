package com.otpautoforward.viewmodel

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.otpautoforward.R
import com.otpautoforward.dataclass.SettingKey

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    /** 用于通知 MainFragment 刷新当前已配对的设备 */
    val refreshPairedDevice = MutableLiveData<Unit>()

    private val sharedPreferences =
        application.getSharedPreferences(SettingKey.AppPreferences.key, Context.MODE_PRIVATE)
    private val editor = sharedPreferences.edit()

    private val _settings = MutableLiveData<Map<String, Boolean>>()
    val settings: LiveData<Map<String, Boolean>> = _settings

    private val _uiColor = MutableLiveData<Int>()
    val uiColor: LiveData<Int> get() = _uiColor

    private val _subColor = MutableLiveData<Int>()
    val subColor: LiveData<Int> get() = _subColor

    private val _negativeColor = MutableLiveData<Int>()

    private val _iconColor = MutableLiveData<Map<String, Int>>()
    val iconColor: LiveData<Map<String, Int>> = _iconColor

    init {
        refreshPairedDevice.postValue(Unit) // 初始化 "已配对设备" 的默认值
        loadSettings()  // 加载 Settings 的默认值
        loadColor()
        loadIconColor()
    }

    /** 更新设置 */
    fun updateSetting(key: String, value: Boolean) {
        editor.putBoolean(key, value).apply()
        loadSettings()  // 更新设置后重新加载
        loadIconColor()
    }

    fun updateUIColor(color: Int) {
        editor.putInt(SettingKey.UIColor.key, color).apply()
        _uiColor.value = color
    }

    /** 设置副系颜色 */
    fun updateSubColor(subColor: Int) {
        editor.putInt(SettingKey.SubColor.key, subColor).apply()
        _subColor.value = subColor
    }

    /** 获取当前的主题色色值, 非资源 ID */
    fun getUIColor(): Int {
        val color = sharedPreferences.getInt(
            SettingKey.UIColor.key,
            ContextCompat.getColor(getApplication(), R.color.default_ui_color)
        )
        return color
    }

    private fun getSubColor(): Int {
        val color = sharedPreferences.getInt(
            SettingKey.SubColor.key,
            ContextCompat.getColor(getApplication(), R.color.default_sub_color)
        )
        return color
    }

    private fun getNegativeColor(): Int {
        val color = ContextCompat.getColor(getApplication(), R.color.negative_color)
        return color
    }

    private fun loadColor() {
        _uiColor.value = getUIColor()
        _subColor.value = getSubColor()
        _negativeColor.value = getNegativeColor()
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
        _settings.value = map
    }

    fun loadIconColor() {
        val currentStates = _settings.value ?: return
        val currentUiColor = _uiColor.value ?: return
        val negativeColor = _negativeColor.value ?: return

        // 根据 switch 状态分配颜色
        val colorMap = currentStates.mapValues { entry ->
            if (entry.value) currentUiColor else negativeColor
        }

        _iconColor.value = colorMap
    }
}