package com.otpautoforward.activity

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.Html
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.kongzue.dialogx.DialogX
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.kongzue.dialogx.interfaces.OnBindView
import com.kongzue.dialogx.util.TextInfo
import com.kongzue.dialogxmaterialyou.style.MaterialYouStyle
import com.otpautoforward.R
import com.otpautoforward.databinding.ActivityMainBinding
import com.otpautoforward.handler.JsonHandler
import com.otpautoforward.handler.UpdateHandler
import com.otpautoforward.viewmodel.SettingsViewModel
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerView
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import com.skydoves.colorpickerview.sliders.AlphaSlideBar
import com.skydoves.colorpickerview.sliders.BrightnessSlideBar
import kotlinx.coroutines.launch


private const val testMessage =
    "【测试短信】尾号为1234的用户您好, 987123 是您的验证码, 这是一条测试短信"
private const val testSender = "测试员"
private const val tag = "OTPAutoForward"

/** 远程仓库中的 QA 数据 */
private const val qaJson =
    "https://gitee.com/DirectoWay/OTPAutoForward/raw/net472/Android/app/src/main/assets/questionAndAnswer.json"

class MainActivity : AppCompatActivity() {
    // 用于获取 MainActivity 的实例
    companion object {
        private var instance: MainActivity? = null
        fun getInstance(): MainActivity {
            return instance ?: throw IllegalStateException("MainActivity is not initialized")
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var batteryOptiLauncher: ActivityResultLauncher<Intent>
    private val smsPermissionCode = 100
    private lateinit var toolbar: Toolbar
    private val jsonHandler = JsonHandler(this)
    private val updateHandler = UpdateHandler()
    private lateinit var settingsViewModel: SettingsViewModel

    /** 导航栏的动画是否已经播放完毕 */
    private var isAnimating = false

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        toolbar = binding.appBarMain.toolbar

        settingsViewModel = ViewModelProvider(this@MainActivity)[SettingsViewModel::class.java]
        binding.settingsViewModel = settingsViewModel
        binding.lifecycleOwner = this@MainActivity

        configDialogX()
        settingsViewModel.uiColor.observe(this@MainActivity) { newColor ->
            DialogX.okButtonTextInfo.fontColor = newColor
        }

        // 设置左上角导航图标的偏移位置
        toolbar.post {
            val navIconView = toolbar.getChildAt(1) as? ImageView ?: return@post
            val marginInDp = 15
            val scale = resources.displayMetrics.density
            val marginInPx = (marginInDp * scale + 0.5f).toInt()
            val layoutParams = navIconView.layoutParams as Toolbar.LayoutParams
            layoutParams.leftMargin = marginInPx
            navIconView.layoutParams = layoutParams
        }
        setSupportActionBar(binding.appBarMain.toolbar)

        // 设置左上角导航图标
        supportActionBar?.setHomeAsUpIndicator(R.drawable.outline_rocket_launch_24)
        supportActionBar?.setHomeActionContentDescription("点我测试短信效果")
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 点击右下角短信 icon 的触发事件
        binding.appBarMain.fab.setOnClickListener { view ->
            Snackbar.make(view, "敬请期待", Snackbar.LENGTH_LONG).setAction("Action", null)
                .setAnchorView(R.id.fab).show()
        }

        batteryOptiLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (!checkBatteryOpti()) {
                    batteryOpti()
                }
            }

        // 检查电池白名单
        if (!checkBatteryOpti()) {
            Log.d(tag, "当前已受后台省电策略限制")
            batteryOpti()
        }

        // 检查并请求短信权限
        checkAndRequestSmsPermission()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    // 处理菜单栏逻辑
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_feedback -> {
                openFeedbackUrl()
                true
            }

            R.id.action_qa -> {
                openQADialog()
                true
            }

            android.R.id.home -> {
                if (!isAnimating) {
                    animateNavIcon()
                    sendTestSMS()
                }
                true
            }

            R.id.action_update -> {
                lifecycleScope.launch { updateHandler.checkUpdatesAsync(this@MainActivity) }
                true
            }

            R.id.action_changeUIColor -> {
                showColorPicker()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 检查当前是否处于电池优化白名单, 返回 false 时说明已受后台省电策略限制 */
    private fun checkBatteryOpti(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    /** 添加电池白名单 */
    private fun batteryOpti() {
        MessageDialog.build()
            .setTitle("电池优化白名单请求")
            .setMessage(
                Html.fromHtml(
                    "请允许 App 的<b><font color='#0F826E'>省电策略</font></b>被设置为" +
                            "<b><font color='#0F826E'>无限制</font></b>或" +
                            "<b><font color='#0F826E'>不受限制</font></b>, " +
                            "否则息屏后可能无法进行短信转发<br><br>" +
                            "如果您已经进行过相关设置, 可关闭弹窗",
                    Html.FROM_HTML_MODE_LEGACY
                )
            )
            .setOkButton("去设置") { _, _ ->
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                batteryOptiLauncher.launch(intent)
                false
            }
            .setCancelButton("取消")
            .show()
    }

    private fun checkAndRequestSmsPermission() {
        val smsPermission = Manifest.permission.RECEIVE_SMS
        val receiveSmsGranted = ContextCompat.checkSelfPermission(
            this, smsPermission
        ) == PackageManager.PERMISSION_GRANTED
        if (!receiveSmsGranted) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, smsPermission)) {
                MessageDialog.build()
                    .setTitle("短信权限请求")
                    .setMessage("为了 App 能正常工作, 请您授予接收短信的权限")
                    .setOkButton(
                        "授予权限"
                    ) { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.RECEIVE_SMS),
                            smsPermissionCode
                        )
                        false
                    }
                    .setCancelButton("拒绝") { _, _ ->
                        Log.e(tag, "短信权限已被拒绝")
                        false
                    }
                    .show()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(smsPermission), smsPermissionCode)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == smsPermissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(tag, "短信权限已获取")
            } else {
                showPermissionSettingsDialog()
                Log.e(tag, "短信权限获取失败")
            }
        }
    }

    /** 检测到短信权限被拒绝后, 跳转至应用设置页面重新授予短信权限 */
    private fun showPermissionSettingsDialog() {
        MessageDialog.build()
            .setTitle("短信权限请求")
            .setMessage("短信权限已被拒绝\nApp 可能无法正常使用")
            .setOkButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
                false
            }
            .setCancelButton("取消") { _, _ ->
                Log.e(tag, "短信权限未获取")
                false
            }
            .show()
    }

    /** 跳转至问题反馈页面 */
    private fun openFeedbackUrl() {
        MessageDialog.build()
            .setTitle("打开外部浏览器")
            .setMessage("即将跳转至反馈页面?")
            .setOkButton("确定") { _, _ ->
                val intent = Intent(
                    Intent.ACTION_VIEW, Uri.parse("https://shimo.im/forms/25q5X4Wl48fWJQ3D/fill")
                )
                startActivity(intent)
                false
            }
            .setCancelButton("取消")
            .show()
    }

    /** 跳转至常见问题页面 */
    private fun openQADialog() {
        WaitDialog.show("加载中")
        lifecycleScope.launch {
            val jsonContent = jsonHandler.fetchQAJson(qaJson)
            jsonContent?.let { // 处理获取到的 JSON 内容
                val formattedContent = jsonHandler.formatQAJson(it)
                WaitDialog.dismiss()
                MessageDialog.build()
                    .setTitle("常见问题")
                    .setMessage(formattedContent)
                    .setOkButton("确定")
                    .show()
            } ?: run {
                Log.e(tag, "处理 QA 数据时发生异常")
            }
        }
    }

    /** 播放导航图标动画 */
    private fun animateNavIcon() {
        val vibrator = getSystemService(this@MainActivity, Vibrator::class.java)

        // 导航图标起始位置
        val navIconView = toolbar.getChildAt(1) as? ImageView ?: return
        val startX = navIconView.x
        val endX = resources.displayMetrics.widthPixels.toFloat()

        isAnimating = true

        // 抖动动画
        val lightVibrationPattern = longArrayOf(0, 50) // 短震动效果
        val shakeAnimator1 =
            ObjectAnimator.ofFloat(navIconView, "translationX", 0f, 2f, -2f, 2f, -2f, 1f, -1f, 0f)
        shakeAnimator1.duration = 300
        shakeAnimator1.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(lightVibrationPattern, -1)
                )
            }
        })

        val shakeAnimator2 = ObjectAnimator.ofFloat(
            navIconView, "translationX", 0f, 5f, -5f, 5f, -5f, 2.5f, -2.5f, 0f
        )
        shakeAnimator2.duration = 300

        val shakeAnimator3 = ObjectAnimator.ofFloat(
            navIconView, "translationX", 0f, 10f, -10f, 10f, -10f, 5f, -5f, 0f
        )
        shakeAnimator3.duration = 300

        // 合并多段抖动动画
        val shakeAnimatorSet = AnimatorSet()
        shakeAnimatorSet.playSequentially(
            shakeAnimator1, shakeAnimator2, shakeAnimator3
        )

        // 颜色渐变
        val changeColor = ValueAnimator.ofArgb(Color.BLACK, settingsViewModel.getUIColor())
        changeColor.addUpdateListener { animator ->
            DrawableCompat.setTint(navIconView.drawable, animator.animatedValue as Int)
        }
        changeColor.duration = 500 // 颜色渐变时间

        val restoreColor = ValueAnimator.ofArgb(settingsViewModel.getUIColor(), Color.BLACK)
        restoreColor.addUpdateListener { animator ->
            DrawableCompat.setTint(navIconView.drawable, animator.animatedValue as Int)
        }

        restoreColor.duration = 1000 // 颜色复原时间

        // 图标从左向右移动
        val heavyVibrationPattern = longArrayOf(0, 200) // 长震动效果
        val moveToRight = ObjectAnimator.ofFloat(navIconView, "x", startX, endX)
        moveToRight.duration = 1500
        moveToRight.interpolator = AnticipateOvershootInterpolator()
        moveToRight.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                vibrator?.vibrate(VibrationEffect.createWaveform(heavyVibrationPattern, -1))
            }
        })

        // 图标复原
        val resetPosition = ObjectAnimator.ofFloat(
            navIconView, "x", -navIconView.width.toFloat(), startX
        )
        resetPosition.duration = 1500 // 动画持续时间1秒
        resetPosition.interpolator = DecelerateInterpolator(1.5f)

        // 合并动画
        val animatorSet = AnimatorSet()
        animatorSet.playSequentially(AnimatorSet().apply {
            playTogether(
                changeColor, shakeAnimatorSet
            )
        }, moveToRight, resetPosition, restoreColor)

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isAnimating = false
            }
        })
        animatorSet.start()
    }

    /** 往 Win 端发送测试用的短信 */
    private fun sendTestSMS() {
        val intent = Intent("com.OTPAutoForward.TEST_SMS_RECEIVED")
        intent.putExtra("extra_test_sms", "$testMessage\n发送者：$testSender")
        intent.setPackage(this@MainActivity.packageName)
        sendOrderedBroadcast(intent, null)
    }

    private fun showColorPicker() {
        var hexUIColor = ""
        var hexSubColor = ""
        val colorPickerDialog = MessageDialog.build()
        colorPickerDialog
            .setCustomView(object : OnBindView<MessageDialog?>(R.layout.app_color_picker) {
                override fun onBind(dialog: MessageDialog?, v: View) {
                    val colorPickerView = v.findViewById<ColorPickerView>(R.id.colorPickerView)
                    val brightnessSlideBar = v.findViewById<BrightnessSlideBar>(R.id.brightnessSlideBar)
                    val alphaSlideBar = v.findViewById<AlphaSlideBar>(R.id.alphaSlideBar)
                    val selectedColorView = v.findViewById<View>(R.id.selectedColorView)
                    val hexValueTextView = v.findViewById<TextView>(com.otpautoforward.R.id.hexValueTextView)

                    // 绑定滑块
                    colorPickerView.attachBrightnessSlider(brightnessSlideBar)
                    colorPickerView.attachAlphaSlider(alphaSlideBar)

                    // 颜色监听器
                    colorPickerView.setColorListener(ColorEnvelopeListener { envelope: ColorEnvelope, _: Boolean ->
                        val selectedColor = envelope.color
                        hexUIColor = "#" + envelope.hexCode
                        hexSubColor = generateSubColor(hexUIColor)
                        hexValueTextView.text = hexUIColor
                        selectedColorView.setBackgroundColor(selectedColor)
                    })
                }
            })
            .setTitle("自定义主题色")
            .setMessage("请在下方取色板中选择您喜好的主题色")
            .setOkButton("确定") { _, _ ->
                settingsViewModel.updateUIColor(Color.parseColor(hexUIColor))
                settingsViewModel.updateSubColor(Color.parseColor(hexSubColor))
                false
            }
            .setCancelButton("取消")
            .setOtherButton("恢复默认主题色") { dialog, _ ->
                showRestoreColorDialog(dialog)
                true
            }
            .setCancelable(false)
            .show()
    }

    private fun showRestoreColorDialog(dialog: MessageDialog) {
        val restoreColorDialog = MessageDialog.build()
        restoreColorDialog
            .setTitle("恢复默认主题色?")
            .setMessage("将恢复默认的主题色")
            .setOkButton("确定") { _, _ ->
                restoreColor()
                dialog.dismiss() // 关掉上一层弹窗
                false
            }
            .setCancelButton("取消")
            .show()
    }

    private fun restoreColor() {
        val defaultUIColor = ContextCompat.getColor(application, R.color.default_ui_color)
        settingsViewModel.updateUIColor(defaultUIColor)

        val defaultSubColor = generateSubColor(application.getString(R.string.default_ui_color))
        settingsViewModel.updateSubColor(Color.parseColor(defaultSubColor))
    }

    private fun generateSubColor(hexUIColor: String): String {
        // 检查输入格式是否符合 #AARRGGBB
        if (!hexUIColor.matches(Regex("#[A-Fa-f0-9]{8}"))) {
            throw IllegalArgumentException("不符合规范的颜色值")
        }

        // 提取 ARGB 通道值
        val alpha = hexUIColor.substring(1, 3).toInt(16)
        val red = hexUIColor.substring(3, 5).toInt(16)
        val green = hexUIColor.substring(5, 7).toInt(16)
        val blue = hexUIColor.substring(7, 9).toInt(16)

        // 减少 alpha 通道的值
        val subAlpha = (alpha / 3).coerceIn(0, 255)

        return "#%02X%02X%02X%02X".format(subAlpha, red, green, blue)
    }

    /** 全局的 DialogX 配置 */
    private fun configDialogX() {
        DialogX.init(this)
        DialogX.globalStyle = MaterialYouStyle() // 设置为 MaterialYou 主题
        MessageDialog.overrideExitDuration = 150 // 对话框淡去的动画持续时间
        DialogX.backgroundColor = Color.parseColor("#FFFFFFFF") // 对话框背景颜色

        // "确认" 按钮的样式
        val okTextInfo = TextInfo()
        okTextInfo.fontColor = settingsViewModel.getUIColor()
        okTextInfo.isBold = true
        okTextInfo.fontSize = 17
        DialogX.okButtonTextInfo = okTextInfo

        // 其他按钮样式
        val cancelTextInfo = TextInfo()
        cancelTextInfo.fontColor = Color.GRAY
        cancelTextInfo.isBold = true
        DialogX.buttonTextInfo = cancelTextInfo
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }
}



