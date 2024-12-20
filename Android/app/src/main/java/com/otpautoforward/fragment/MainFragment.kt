package com.otpautoforward.fragment

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.otpautoforward.R
import com.otpautoforward.activity.CaptureQRCodeActivity
import com.otpautoforward.databinding.FragmentMainBinding
import com.otpautoforward.handler.DeviceHandler
import com.otpautoforward.handler.QRCodeHandler
import com.otpautoforward.viewmodel.SettingKey
import com.otpautoforward.viewmodel.SettingsViewModel
import com.google.android.material.snackbar.Snackbar
import java.net.Inet4Address

class MainFragment : Fragment() {
    private lateinit var settingsViewModel: SettingsViewModel
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private val qrCodeHandler = QRCodeHandler()
    private val qrCodeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val qrData = result.data?.getStringExtra("SCAN_RESULT")
                handleQRCodeResult(qrData)
            } else {
                Log.d("camera", "相机调用失败或取消")
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)

        // 初始化配对按钮点击事件
        binding.viewQrPair.setOnClickListener { checkCameraPermission() }
        binding.viewCodePair.setOnClickListener { view ->
            Snackbar.make(view, "暂未开通", Snackbar.LENGTH_LONG).setAction("Action", null)
                .setAnchorView(R.id.fab).show()
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 前台获取设备名称与 IP 地址
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val deviceIPAddress = getDeviceIPAddress(requireContext())
        binding.deviceName.text = deviceName
        binding.deviceIPAddress.text = deviceIPAddress
        binding.viewQrPair.elevation = 0f

        // 观察开关的变化
        settingsViewModel = ViewModelProvider(requireActivity())[SettingsViewModel::class.java]

        settingsViewModel.settings.observe(viewLifecycleOwner) { settings ->
            binding.switchSms.isChecked = settings[SettingKey.SmsEnabled.key] ?: true
            binding.switchForwardScreenoff.isChecked = settings[SettingKey.ScreenLocked.key] ?: true
            binding.switchSyncDoNotDisturb.isChecked =
                settings[SettingKey.SyncDoNotDistribute.key] ?: true
            binding.switchForwardOnlyOTP.isChecked =
                settings[SettingKey.ForwardOnlyOTP.key] ?: true
        }

        settingsViewModel.refreshPairedDevice.observe(viewLifecycleOwner) {
            refreshPairedDevice()
        }

        initializeSwitchState()

        switchListener()
    }

    /** 检查相机权限 */
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                val intent =
                    Intent(activity, CaptureQRCodeActivity::class.java) // 有相机权限直接启动扫码Activity
                qrCodeLauncher.launch(intent)
            }

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // 用户页面弹出对话框申请权限
            }

            // 没有相机权限则请求权限
            else -> requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** 请求相机权限 */
    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                val intent = Intent(activity, CaptureQRCodeActivity::class.java)
                qrCodeLauncher.launch(intent)
            } else {
                Log.d("", "相机权限已遭拒绝")
            }
        }

    /** 处理扫码结果 */
    private fun handleQRCodeResult(qrData: String?) {
        qrData?.let {
            val pairingInfo = qrCodeHandler.analyzeQRCode(it)
            pairingInfo?.let {
                try {
                    // 保存配对信息并刷新页面
                    qrCodeHandler.saveDeviceInfo(requireContext().applicationContext, pairingInfo)
                    Handler(Looper.getMainLooper()).postDelayed({
                        settingsViewModel.refreshPairedDevice.value = Unit
                    }, 100)

                    AlertDialog.Builder(requireContext()).setTitle("配对成功")
                        .setMessage("设备 ${pairingInfo.deviceName} 已成功配对！")
                        .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }.show()
                } catch (e: Exception) {
                    AlertDialog.Builder(requireContext()).setTitle("配对失败")
                        .setMessage("保存配对信息或刷新页面失败: ${e.message}")
                        .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }.show()
                }
            } ?: AlertDialog.Builder(requireContext()).setTitle("配对失败")
                .setMessage("二维码解析失败")
                .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }.show()
        } ?: AlertDialog.Builder(requireContext()).setTitle("配对失败")
            .setMessage("二维码数据为空")
            .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }.show()
    }

    private fun getDeviceIPAddress(context: Context): String? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: Network = connectivityManager.activeNetwork ?: return null
        val capabilities: NetworkCapabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        val linkProperties: LinkProperties =
            connectivityManager.getLinkProperties(activeNetwork) ?: return null

        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            val addresses = linkProperties.linkAddresses
            for (address in addresses) {
                if (!address.address.isLoopbackAddress && address.address is Inet4Address) { // 检查是否是 IPv4 地址
                    return address.address.hostAddress
                }
            }
        }
        return null
    }

    /** 刷新已配对的设备 */
    private fun refreshPairedDevice() {
        val deviceHandler = DeviceHandler(binding.layoutPairedDeviceInfo)
        val deviceList = deviceHandler.getDeviceInfo(requireContext())
        if (deviceList.isEmpty()) {
            // 已连接的设备数为0的时候不显示整个 CardView
            binding.textPairedDevice.visibility = View.INVISIBLE
            binding.viewPairedDeviceInfo.visibility = View.GONE
        } else {
            binding.textPairedDevice.visibility = View.VISIBLE
            binding.viewPairedDeviceInfo.visibility = View.VISIBLE
            deviceHandler.bindDeviceInfo(deviceList)
        }
    }

    /** 开关监听器, 页面上的开关发生变动时执行对应的逻辑 */
    private fun switchListener() {
        binding.switchSms.setOnCheckedChangeListener { _, isChecked ->
            settingsViewModel.updateSetting(SettingKey.SmsEnabled.key, isChecked)
            if (isChecked) {
                expandAnimation()
                animateIconChange(
                    binding.iconSms,
                    R.drawable.baseline_forward_to_inbox_24,
                    Color.parseColor("#0F826E")
                )
            } else {
                collapseAnimation()
                animateIconChange(
                    binding.iconSms,
                    R.drawable.outline_mail_lock_24,
                    Color.parseColor("#808080")
                )
            }
        }
        binding.switchForwardScreenoff.setOnCheckedChangeListener { _, isChecked ->
            settingsViewModel.updateSetting(SettingKey.ScreenLocked.key, isChecked)
            if (isChecked) {
                animateIconChange(
                    binding.iconForwardScreenoff,
                    R.drawable.baseline_screen_lock_portrait_24,
                    Color.parseColor("#0F826E")
                )
            } else {
                animateIconChange(
                    binding.iconForwardScreenoff,
                    R.drawable.baseline_smartphone_24,
                    Color.parseColor("#808080")
                )
            }
        }
        binding.switchSyncDoNotDisturb.setOnCheckedChangeListener { _, isChecked ->
            settingsViewModel.updateSetting(SettingKey.SyncDoNotDistribute.key, isChecked)
            if (isChecked) {
                animateIconChange(
                    binding.iconSyncDoNotDisturb,
                    R.drawable.outline_notifications_off_24,
                    Color.parseColor("#0F826E")
                )
            } else {
                animateIconChange(
                    binding.iconSyncDoNotDisturb,
                    R.drawable.outline_notifications_24,
                    Color.parseColor("#808080")
                )
            }
        }
        binding.switchForwardOnlyOTP.setOnCheckedChangeListener { _, isChecked ->
            settingsViewModel.updateSetting(SettingKey.ForwardOnlyOTP.key, isChecked)
            if (isChecked) {
                animateIconChange(
                    binding.iconForwardOnlyOTP,
                    R.drawable.outline_verified_24,
                    Color.parseColor("#0F826E")
                )
            } else {
                animateIconChange(
                    binding.iconForwardOnlyOTP,
                    R.drawable.outline_sms_24,
                    Color.parseColor("#808080")
                )
            }
        }
    }

    /** 图标切换时的动画 */
    private fun animateIconChange(imageView: ImageView, newImageResId: Int, newTintColor: Int) {
        val fadeOut = ObjectAnimator.ofFloat(imageView, "alpha", 1f, 0f)
        val scaleOutX = ObjectAnimator.ofFloat(imageView, "scaleX", 1f, 0f)
        val scaleOutY = ObjectAnimator.ofFloat(imageView, "scaleY", 1f, 0f)
        val fadeIn = ObjectAnimator.ofFloat(imageView, "alpha", 0f, 1f)
        val scaleInX = ObjectAnimator.ofFloat(imageView, "scaleX", 0f, 1f)
        val scaleInY = ObjectAnimator.ofFloat(imageView, "scaleY", 0f, 1f)

        val outAnimatorSet = AnimatorSet().apply {
            duration = 250
            playTogether(fadeOut, scaleOutX, scaleOutY)
        }
        val inAnimatorSet = AnimatorSet().apply {
            duration = 250
            playTogether(fadeIn, scaleInX, scaleInY)
        }
        outAnimatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                imageView.setImageResource(newImageResId)
                imageView.setColorFilter(newTintColor)
                inAnimatorSet.start()
            }
        })
        outAnimatorSet.start()
    }

    /** 初始化页面上的静态 UI */
    private fun initializeSwitchState() {
        val switchMap = mapOf(
            SettingKey.SmsEnabled.key to binding.switchSms,
            SettingKey.ScreenLocked.key to binding.switchForwardScreenoff,
            SettingKey.SyncDoNotDistribute.key to binding.switchSyncDoNotDisturb,
            SettingKey.ForwardOnlyOTP.key to binding.switchForwardOnlyOTP
        )
        switchMap.forEach { (key, switch) -> // 获取开关当前的值并禁用监听器
            val isEnabled = settingsViewModel.settings.value?.get(key) ?: true
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = isEnabled
            // 设置开关图标和状态
            setInitialStatus(key, isEnabled)
        }
    }

    /** 设置页面元素的初始化状态 */
    private fun setInitialStatus(key: String, isEnabled: Boolean) {
        when (key) {
            SettingKey.SmsEnabled.key -> {
                if (isEnabled) {
                    setExpandedState()
                    binding.iconSms.setImageResource(R.drawable.baseline_forward_to_inbox_24)
                    binding.iconSms.setColorFilter(Color.parseColor("#0F826E"))
                } else {
                    setCollapsedState()
                    binding.iconSms.setImageResource(R.drawable.outline_mail_lock_24)
                    binding.iconSms.setColorFilter(Color.parseColor("#808080"))
                }
            }

            SettingKey.ScreenLocked.key -> {
                if (isEnabled) {
                    binding.iconForwardScreenoff.setImageResource(R.drawable.baseline_screen_lock_portrait_24)
                    binding.iconForwardScreenoff.setColorFilter(Color.parseColor("#0F826E"))
                } else {
                    binding.iconForwardScreenoff.setImageResource(R.drawable.baseline_smartphone_24)
                    binding.iconForwardScreenoff.setColorFilter(Color.parseColor("#808080"))
                }
            }

            SettingKey.SyncDoNotDistribute.key -> {
                if (isEnabled) {
                    binding.iconSyncDoNotDisturb.setImageResource(R.drawable.outline_notifications_off_24)
                    binding.iconSyncDoNotDisturb.setColorFilter(Color.parseColor("#0F826E"))
                } else {
                    binding.iconSyncDoNotDisturb.setImageResource(R.drawable.outline_notifications_24)
                    binding.iconSyncDoNotDisturb.setColorFilter(Color.parseColor("#808080"))
                }
            }

            SettingKey.ForwardOnlyOTP.key -> {
                if (isEnabled) {
                    binding.iconForwardOnlyOTP.setImageResource(R.drawable.outline_verified_24)
                    binding.iconForwardOnlyOTP.setColorFilter(Color.parseColor("#0F826E"))
                } else {
                    binding.iconForwardOnlyOTP.setImageResource(R.drawable.outline_sms_24)
                    binding.iconForwardOnlyOTP.setColorFilter(Color.parseColor("#808080"))
                }
            }
        }
    }

    /** 展开状态的静态 UI */
    private fun setExpandedState() {
        binding.containerSwitchRetractable.visibility = View.VISIBLE
        binding.containerSwitchRetractable.alpha = 1f
        binding.containerSwitchRetractable.translationY = 0f

        binding.viewSwitchWarn.visibility = View.INVISIBLE
        binding.viewSwitchWarn.alpha = 0f
        binding.viewSwitchWarn.translationY = binding.viewSwitchWarn.height.toFloat()

        changeSwitchColor(
            binding.viewSmsSwitch,
            ContextCompat.getColor(requireContext(), R.color.white),
            Color.parseColor("#D7D7D7"),
            0 // 直接无动画
        )
    }

    /** 收起状态的静态 UI */
    private fun setCollapsedState() {
        binding.containerSwitchRetractable.visibility = View.INVISIBLE
        binding.containerSwitchRetractable.alpha = 0f
        binding.containerSwitchRetractable.translationY =
            binding.containerSwitchRetractable.height.toFloat()

        binding.viewSwitchWarn.visibility = View.VISIBLE
        binding.viewSwitchWarn.alpha = 1f
        binding.viewSwitchWarn.translationY = 0f

        changeSwitchColor(
            binding.viewSmsSwitch,
            Color.parseColor("#D7D7D7"),
            ContextCompat.getColor(requireContext(), R.color.white),
            0 // 直接无动画
        )
    }

    /** 拨动 "短信转发" 开关时的展开动画 */
    private fun expandAnimation() {
        val retractableContainer = binding.containerSwitchRetractable
        val switchWarnView = binding.viewSwitchWarn
        val switchSmsView = binding.viewSmsSwitch

        // 隐藏提示内容
        switchWarnView.animate().alpha(0f).translationY(switchWarnView.height.toFloat())
            .setDuration(500).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    switchWarnView.visibility = View.INVISIBLE
                }
            })

        // 展开页面内容
        retractableContainer.visibility = View.VISIBLE
        retractableContainer.alpha = 0f
        retractableContainer.translationY = retractableContainer.height.toFloat()
        retractableContainer.animate().alpha(1f).translationY(0f).setDuration(500).setListener(null)

        changeSwitchColor(
            switchSmsView,
            ContextCompat.getColor(requireContext(), R.color.white),
            Color.parseColor("#D7D7D7"),
            300
        )
    }

    /** 拨动 "短信转发" 开关时的收起动画 */
    private fun collapseAnimation() {
        val retractableContainer = binding.containerSwitchRetractable
        val switchWarnView = binding.viewSwitchWarn
        val switchTotalView = binding.viewSmsSwitch

        // 收起页面内容
        retractableContainer.animate().alpha(0f).translationY(retractableContainer.height.toFloat())
            .setDuration(500).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    retractableContainer.visibility = View.INVISIBLE
                }
            })

        // 显示提示内容
        switchWarnView.visibility = View.VISIBLE
        switchWarnView.alpha = 0f
        switchWarnView.translationY = -switchWarnView.height.toFloat()
        switchWarnView.animate().alpha(1f).translationY(0f).setDuration(500).setListener(null)

        changeSwitchColor(
            switchTotalView,
            Color.parseColor("#D7D7D7"),
            ContextCompat.getColor(requireContext(), R.color.white),
            600
        )
    }

    /** 更改 "短信转发" 开关的颜色 */
    private fun changeSwitchColor(view: View, colorFrom: Int, colorTo: Int, duration: Long) {
        val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
        colorAnimation.duration = duration
        colorAnimation.addUpdateListener { animator ->
            (view as? CardView)?.setCardBackgroundColor(animator.animatedValue as Int)
        }
        colorAnimation.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}






