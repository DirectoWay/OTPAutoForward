package com.example.autocaptcha

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.autocaptcha.databinding.ActivityPairDeviceSettingsBinding
import com.example.autocaptcha.ui.pair.DevicePairViewModel

class PairedDeviceSettingsActivity : AppCompatActivity() {
    private lateinit var devicePairViewModel: DevicePairViewModel
    private lateinit var binding: ActivityPairDeviceSettingsBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairDeviceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 获取 viewModel 实例,与 MainActivity 共享 fragment
        devicePairViewModel =
            ViewModelProvider(MainActivity.getInstance())[DevicePairViewModel::class.java]

        // 点击已配对的设备后可查看的配对信息
        val deviceId = intent.getStringExtra("DEVICE_ID")
        val deviceName = intent.getStringExtra("DEVICE_Name")
        val deviceTypeIcon =
            intent.getIntExtra("DEVICE_TYPE_ICON", R.drawable.baseline_device_unknown_24)

        binding.textPairedDeviceId.text = deviceId
        binding.textPairedDeviceName.text = deviceName
        binding.iconPairedDeviceType.setImageResource(deviceTypeIcon)

        // 取消配对, 并移除已配对的设备信息
        binding.viewPairedCancel.setOnClickListener {
            deviceId?.let {
                removeDeviceInfo(it)
                devicePairViewModel.refreshPairedDevice.postValue(Unit) // 事件驱动, 刷新已配对的设备信息
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 100)
            }
        }
    }

    private fun removeDeviceInfo(deviceId: String) {
        val sharedPreferences =
            applicationContext.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.remove(deviceId)
        editor.apply()
    }
}