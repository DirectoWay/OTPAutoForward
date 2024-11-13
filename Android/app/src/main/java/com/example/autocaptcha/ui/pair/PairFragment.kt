package com.example.autocaptcha.ui.pair

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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.autocaptcha.CaptureQRCodeActivity
import com.example.autocaptcha.R
import com.example.autocaptcha.databinding.FragmentPairBinding
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.autocaptcha.handler.DeviceHandler
import com.example.autocaptcha.handler.QRCodeHandler
import com.example.autocaptcha.handler.WebSocketHandler
import com.google.android.material.switchmaterial.SwitchMaterial
import java.net.Inet4Address

class PairFragment : Fragment() {
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
        binding.viewQrPair.setOnClickListener { checkCameraPermissionAndLaunch() }
        binding.viewCodePair.setOnClickListener { checkCameraPermissionAndLaunch() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 获取设备名称
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        // 获取设备IP地址
        val deviceIPAddress = getDeviceIPAddress(requireContext())

        // 将设备名称和IP地址设置到 TextView
        binding.deviceName.text = deviceName
        binding.deviceIPAddress.text = deviceIPAddress

        val deviceContainer: LinearLayout = view.findViewById(R.id.layout_paired_deviceInfo)
        val deviceView: CardView = view.findViewById(R.id.view_paired_deviceInfo)

        // 创建适配器并绑定设备信息并根据设备数量设置 CardView 的可见性
        val deviceHandler = DeviceHandler(deviceContainer)
        val deviceList = deviceHandler.getDeviceInfo(requireContext())
        if (deviceList.isEmpty()) {
            // 已连接的设备数为0的时候不显示整个cardview
            deviceView.visibility = View.GONE
        } else {
            deviceView.visibility = View.VISIBLE
            deviceHandler.bindDeviceInfo(deviceList)
        }

        val switchTotal: SwitchMaterial = view.findViewById(R.id.switch_total)
        val retractableContainer: ConstraintLayout =
            view.findViewById(R.id.container_switch_retractable)
        val switchWarnView: CardView = view.findViewById(R.id.view_switch_warn)
        val switchTotalView: CardView = view.findViewById(R.id.view_total_switch)

        // 初始状态设置
        switchWarnView.visibility = View.INVISIBLE

        switchTotal.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 打开状态，展开 retractableContainer，隐藏 switchWarnView
                switchWarnView.animate().alpha(0f)
                    .translationY(switchWarnView.height.toFloat()) // 从上到下隐藏
                    .setDuration(500).setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            switchWarnView.visibility = View.INVISIBLE
                        }
                    })

                retractableContainer.visibility = View.VISIBLE
                retractableContainer.alpha = 0f
                retractableContainer.translationY = retractableContainer.height.toFloat() // 预设为下方
                retractableContainer.animate().alpha(1f).translationY(0f) // 从上到下展开
                    .setDuration(500).setListener(null)

                // 更改 switchTotalView 颜色
                val colorFrom = ContextCompat.getColor(requireContext(), R.color.white)
                val colorTo = Color.parseColor("#D7D7D7")
                val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
                colorAnimation.duration = 300 // 动画时长
                colorAnimation.addUpdateListener { animator ->
                    switchTotalView.setCardBackgroundColor(animator.animatedValue as Int)
                }
                colorAnimation.start()
            } else {

                // 关闭状态，收起 retractableContainer，显示 switchWarnView
                retractableContainer.animate().alpha(0f)
                    .translationY(retractableContainer.height.toFloat()) // 从下到上收起
                    .setDuration(500).setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            retractableContainer.visibility = View.INVISIBLE
                        }
                    })

                switchWarnView.visibility = View.VISIBLE
                switchWarnView.alpha = 0f
                switchWarnView.translationY = -switchWarnView.height.toFloat() // 预设为上方
                switchWarnView.animate().alpha(1f).translationY(0f) // 从上到下显示
                    .setDuration(500).setListener(null)

                // 更改 switchTotalView 颜色
                val colorFrom = Color.parseColor("#D7D7D7")
                val colorTo = ContextCompat.getColor(requireContext(), R.color.white)
                val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
                colorAnimation.duration = 600 // 动画时长
                colorAnimation.addUpdateListener { animator ->
                    switchTotalView.setCardBackgroundColor(animator.animatedValue as Int)
                }
                colorAnimation.start()
            }
        }
    }

    /* 获取当前设备的 IP地址 */
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

    /* 检查相机权限并启动相机 */
    private fun checkCameraPermissionAndLaunch() {
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

    // 请求相机权限
    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                val intent = Intent(activity, CaptureQRCodeActivity::class.java)
                qrCodeLauncher.launch(intent)
            } else {
                println("相机权限已遭拒绝")
            }
        }

    // 处理扫码结果
    private fun handleQRCodeResult(qrData: String?) {
        qrData?.let {
            val pairingInfo = qrCodeHandler.decryptAndVerify(it)
            pairingInfo?.let {
                val webSocketHandler = WebSocketHandler.getInstance()

                webSocketHandler.connectToWebSocket(
                    requireContext(), pairingInfo
                ) { context, pairingInfo ->
                    webSocketHandler.showPairedSuccessDialog(context, pairingInfo)
                }
            } ?: Log.e("Connect", "解密或签名验证失败")
        } ?: Log.e("Connect", "二维码数据为空")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}





