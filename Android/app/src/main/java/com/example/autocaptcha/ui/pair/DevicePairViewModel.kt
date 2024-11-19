package com.example.autocaptcha.ui.pair

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData

class DevicePairViewModel(application: Application) : AndroidViewModel(application) {
    // 用于通知 PairFragment 刷新当前已配对的设备
    val refreshPairedDevice = MutableLiveData<Unit>()

    init {
        refreshPairedDevice.postValue(Unit) // 初始化默认值
    }
}