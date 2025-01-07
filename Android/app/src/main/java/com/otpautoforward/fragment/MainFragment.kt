package com.otpautoforward.fragment

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.kongzue.dialogx.DialogX
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.util.TextInfo
import com.kongzue.dialogxmaterialyou.style.MaterialYouStyle
import com.otpautoforward.R
import com.otpautoforward.activity.CaptureQRCodeActivity
import com.otpautoforward.databinding.FragmentMainBinding
import com.otpautoforward.databinding.FragmentPairDeviceinfoBinding
import com.otpautoforward.dataclass.SettingKey
import com.otpautoforward.handler.DeviceHandler
import com.otpautoforward.handler.GlobalHandler
import com.otpautoforward.handler.QRCodeHandler
import com.otpautoforward.viewmodel.SettingsViewModel


private const val tagF = "OTPAutoForward"

class MainFragment : Fragment() {
    private lateinit var settingsViewModel: SettingsViewModel

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private var _pairDeviceBinding: FragmentPairDeviceinfoBinding? = null
    private val pairDeviceBinding get() = _pairDeviceBinding!!

    private val globalHandler = GlobalHandler()
    private val qrCodeHandler = QRCodeHandler()

    private lateinit var qrCodeLauncher: ActivityResultLauncher<Intent>

    lateinit var settingIcon: ImageView
    private lateinit var rotateAnimation: Animation

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        _pairDeviceBinding = FragmentPairDeviceinfoBinding.inflate(inflater, container, false)

        configDialogX()

        // 注册二维码启动器, 有权限的情况下直接启动扫码
        qrCodeLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val qrData = result.data?.getStringExtra("SCAN_RESULT")
                    handleQRCodeResult(qrData)
                } else {
                    Log.d(tagF, "相机调用失败或取消")
                }
            }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 晃动手机图标动画效果
        binding.viewDeviceName.setOnClickListener {
            val rotateAnimator = ObjectAnimator.ofFloat(
                binding.iconDeviceName, "rotation", 0f, 30f, -30f, 15f, -15f, 0f
            )

            rotateAnimator.duration = 500
            rotateAnimator.addUpdateListener { animation ->
                val progress = animation.animatedFraction
                if (progress >= 0.3 && progress < 0.4) { // 动画播放到一半时震动
                    val vibrator =
                        ContextCompat.getSystemService(binding.root.context, Vibrator::class.java)
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(
                            100,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                }
            }
            rotateAnimator.start()
        }

        // WiFi 图标逐级递增动画效果
        binding.viewDeviceIp.setOnClickListener {
            val handler = Handler(Looper.getMainLooper())
            val updateIcon = Runnable {
                binding.iconDeviceIp.setImageResource(R.drawable.baseline_wifi_1_bar_24)
                handler.postDelayed({
                    binding.iconDeviceIp.setImageResource(R.drawable.baseline_wifi_2_bar_24)
                    handler.postDelayed(
                        { binding.iconDeviceIp.setImageResource(R.drawable.baseline_wifi_24) }, 300
                    )
                }, 300)
            }
            handler.post(updateIcon)
        }

        // 初始化配对按钮点击事件
        binding.viewQrPair.setOnClickListener { checkCameraPermission() }
        binding.viewQrPair.elevation = 0f
        binding.viewCodePair.setOnClickListener {
            Toast.makeText(requireContext(), "功能暂未开通", Toast.LENGTH_LONG).show()
        }

        // 前台获取设备名称与 IP 地址
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val deviceIPAddress = globalHandler.getDeviceIPAddress(requireContext())
        binding.deviceName.text = deviceName
        binding.deviceIPAddress.text = deviceIPAddress

        // 观察页面上开关的变化
        settingsViewModel = ViewModelProvider(requireActivity())[SettingsViewModel::class.java]

        settingsViewModel.settings.observe(viewLifecycleOwner) { settings ->
            binding.switchSms.isChecked = settings[SettingKey.SmsEnabled.key] ?: true
            binding.switchForwardScreenoff.isChecked = settings[SettingKey.ScreenLocked.key] ?: true
            binding.switchSyncDoNotDisturb.isChecked =
                settings[SettingKey.SyncDoNotDistribute.key] ?: true
            binding.switchForwardOnlyOTP.isChecked = settings[SettingKey.ForwardOnlyOTP.key] ?: true
        }

        settingsViewModel.refreshPairedDevice.observe(viewLifecycleOwner) {
            refreshPairedDevice()
        }

        initializeSwitchState()

        switchListener()

        // 初始化小齿轮的动画效果
        rotateAnimation =
            AnimationUtils.loadAnimation(requireContext(), R.anim.animation_device_typeicon)
        settingIcon = pairDeviceBinding.iconPairedSetting
    }

    override fun onResume() {
        super.onResume()
        settingIcon.startAnimation(rotateAnimation)
    }

    /** 检查相机权限 */
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // 有相机权限直接启动扫码 Activity
                startQRCodeActivity(binding.iconQrPair)
            }

            // 用户页面弹出对话框申请权限
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showPermissionRationaleDialog()
            }

            // 没有相机权限则请求权限
            else -> requestCameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    private fun showPermissionRationaleDialog() {
        MessageDialog.build()
            .setTitle("相机权限请求")
            .setMessage("请授予相机权限用于扫描二维码")
            .setOkButton("授予权限") { _, _ ->
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                false
            }
            .setCancelButton("拒绝")
            .show()
    }

    /** 相机权限被手动拒绝时跳转至设置页面 */
    private fun handlePermissionPermanentlyDenied() {
        MessageDialog.build()
            .setTitle("需要相机权限")
            .setMessage("相机权限被拒绝, 请前往设置授予权限")
            .setOkButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
                false
            }
            .setCancelButton("取消")
            .show()
    }

    /** 请求相机权限 */
    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                val intent = Intent(activity, CaptureQRCodeActivity::class.java)
                qrCodeLauncher.launch(intent)
            } else {
                Log.d(tagF, "相机权限已遭拒绝")
                if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    handlePermissionPermanentlyDenied()
                }
            }
        }

    /** 处理扫码结果 */
    private fun handleQRCodeResult(qrData: String?) {
        try {
            if (qrData == null) throw Exception("二维码数据为空")

            val pairingInfo = qrCodeHandler.analyzeQRCode(qrData)
                ?: throw Exception("从二维码中解析到的配对信息为空")

            // 保存配对信息并刷新页面
            qrCodeHandler.saveDeviceInfo(requireContext(), pairingInfo)
            settingsViewModel.refreshPairedDevice.value = Unit

            Toast.makeText(requireContext(), "设备配对成功", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(requireContext(), "配对失败", Toast.LENGTH_LONG).show()
            Log.e(tagF, "二维码配对发生异常: " + e.message)
            e.printStackTrace()
        }
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
            deviceHandler.bindDeviceInfo(deviceList, this)
        }
    }

    /** 开关监听器, 页面上的开关发生变动时执行对应的逻辑 */
    private fun switchListener() {
        binding.switchSms.setOnCheckedChangeListener { _, isChecked ->
            settingsViewModel.updateSetting(SettingKey.SmsEnabled.key, isChecked)

            // 短震动效果
            val vibrator =
                ContextCompat.getSystemService(binding.root.context, Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30), -1))

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
                    binding.iconSms, R.drawable.outline_mail_lock_24, Color.parseColor("#808080")
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

    /** 开始扫描二维码 */
    private fun startQRCodeActivity(view: View) {
        // 放大动画
        val scaleXUp = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.25f)
        val scaleYUp = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.25f)
        // 缩小动画
        val scaleXDown = ObjectAnimator.ofFloat(view, "scaleX", 1.2f, 1f)
        val scaleYDown = ObjectAnimator.ofFloat(view, "scaleY", 1.2f, 1f)
        // 插值器
        scaleXUp.interpolator = AccelerateDecelerateInterpolator()
        scaleYUp.interpolator = AccelerateDecelerateInterpolator()
        scaleXDown.interpolator = AccelerateDecelerateInterpolator()
        scaleYDown.interpolator = AccelerateDecelerateInterpolator()

        // 放大动画时长
        scaleXUp.duration = 250
        scaleYUp.duration = 250

        // 缩小动画时长
        scaleXDown.duration = 200
        scaleYDown.duration = 200

        // 合并动画
        val animatorSet = AnimatorSet()
        animatorSet.play(scaleXUp).with(scaleYUp)
        // 同时播放放大动画
        animatorSet.play(scaleXDown).after(scaleXUp)
        // 放大动画结束后播放缩小动画
        animatorSet.play(scaleYDown).after(scaleYUp)
        // 启动动画
        animatorSet.start()

        Handler(Looper.getMainLooper()).postDelayed({
            val intent =
                Intent(activity, CaptureQRCodeActivity::class.java)
            qrCodeLauncher.launch(intent)
        }, 200)
    }

    /**
     * 配置 DialogX,
     * 仅在当前文件作用域生效
     */
    private fun configDialogX() {
        DialogX.init(requireContext())
        DialogX.globalStyle = MaterialYouStyle() // 设置为 MaterialYou 主题
        MessageDialog.overrideExitDuration = 150 // 对话框淡去的动画持续时间
        DialogX.backgroundColor = Color.parseColor("#FFFFFFFF") // 对话框背景颜色

        // "确认" 按钮的样式
        val okTextInfo = TextInfo()
        okTextInfo.fontColor = Color.parseColor("#0F826E")
        okTextInfo.isBold = true
        okTextInfo.fontSize = 17
        DialogX.okButtonTextInfo = okTextInfo

        // 其他按钮样式
        val cancelTextInfo = TextInfo()
        cancelTextInfo.fontColor = Color.GRAY
        cancelTextInfo.isBold = true
        DialogX.buttonTextInfo = cancelTextInfo
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}






