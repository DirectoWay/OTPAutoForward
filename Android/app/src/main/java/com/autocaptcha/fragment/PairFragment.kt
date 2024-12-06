package com.autocaptcha.fragment

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.autocaptcha.R
import com.autocaptcha.databinding.FragmentPairBinding
import com.autocaptcha.activity.CaptureQRCodeActivity
import com.autocaptcha.dataclass.PairedDeviceInfo
import com.autocaptcha.handler.DeviceHandler
import com.autocaptcha.handler.QRCodeHandler
import com.autocaptcha.viewmodel.DevicePairViewModel
import org.json.JSONObject
import java.net.Inet4Address

class PairFragment : Fragment() {
    private lateinit var devicePairViewModel: DevicePairViewModel
    private var _binding: FragmentPairBinding? = null
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
        _binding = FragmentPairBinding.inflate(inflater, container, false)

        // 初始化配对按钮点击事件
        binding.viewQrPair.setOnClickListener { checkCameraPermission() }
        binding.viewCodePair.setOnClickListener { checkCameraPermission() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 前台获取设备名称与 IP 地址
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val deviceIPAddress = getDeviceIPAddress(requireContext())
        binding.deviceName.text = deviceName
        binding.deviceIPAddress.text = deviceIPAddress

        devicePairViewModel = ViewModelProvider(requireActivity())[DevicePairViewModel::class.java]

        devicePairViewModel.smsEnabled.observe(viewLifecycleOwner) { smsEnabled ->
            binding.switchSms.isChecked = smsEnabled ?: true // 观察 "短信转发" 按钮的变更状态
        }

        devicePairViewModel.refreshPairedDevice.observe(viewLifecycleOwner) {
            refreshPairedDevice()
        }

        initializeSmsSwitchState()
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
                println("相机权限已遭拒绝")
            }
        }

    /** 处理扫码结果 */
    private fun handleQRCodeResult(qrData: String?) {
        qrData?.let {
            val pairingInfo = qrCodeHandler.analyzeQRCode(it)
            pairingInfo?.let {
                try {
                    // 保存配对信息并刷新页面
                    saveDeviceInfo(requireContext(), pairingInfo)
                    Handler(Looper.getMainLooper()).postDelayed({
                        devicePairViewModel.refreshPairedDevice.value = Unit
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

    private fun startSmsService() {

    }

    private fun stopSmsService() {

    }

    /** 记录已经匹配成功过的设备 */
    private fun saveDeviceInfo(context: Context, pairedDeviceInfo: PairedDeviceInfo) {
        val sharedPreferences =
            context.applicationContext.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val deviceInfo = JSONObject().apply {
            put("deviceIP", pairedDeviceInfo.deviceIP)
            put("webSocketPort", pairedDeviceInfo.webSocketPort)
            put("deviceName", pairedDeviceInfo.deviceName)
            put("deviceId", pairedDeviceInfo.deviceId)
            put("deviceType", pairedDeviceInfo.deviceType)
            put("windowsPublicKey", pairedDeviceInfo.windowsPublicKey)
        }
        editor.putString(pairedDeviceInfo.deviceId, deviceInfo.toString())
        editor.apply()
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

    /** "短信转发" 开关的监听器 */
    private fun smsSwitchListener() {
        binding.switchSms.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                expandAnimation()
                devicePairViewModel.updateSmsEnabled(true)
                startSmsService()
            } else {
                collapseAnimation()
                devicePairViewModel.updateSmsEnabled(false)
                stopSmsService()
            }
        }
    }

    /** 初始化配对页面的静态 UI */
    private fun initializeSmsSwitchState() {
        val smsEnabled = devicePairViewModel.smsEnabled.value ?: true

        // 禁用监听器，防止触发动画
        binding.switchSms.setOnCheckedChangeListener(null)
        binding.switchSms.isChecked = smsEnabled

        if (smsEnabled) {
            setExpandedState()
        } else {
            setCollapsedState()
        }

        smsSwitchListener()
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





