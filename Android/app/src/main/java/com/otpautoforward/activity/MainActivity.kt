package com.otpautoforward.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.view.MenuItem
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import com.otpautoforward.R
import com.otpautoforward.databinding.ActivityMainBinding
import com.otpautoforward.handler.GlobalHandler
import com.otpautoforward.handler.WebSocketWorker
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.otpautoforward.handler.JsonHandler
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
    private val globalHandler = GlobalHandler()
    private val jsonHandler = JsonHandler(this)

    /** 导航栏的动画是否已经播放完毕 */
    private var isAnimating = false

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        toolbar = binding.appBarMain.toolbar

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
                    if (globalHandler.hasPairedDevice(this)) {
                        WebSocketWorker.sendWebSocketMessage(
                            this, "$testMessage\n发送者：$testSender"
                        )
                    }
                }
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
        val builder = AlertDialog.Builder(this)
        builder.setTitle("电池优化白名单请求")
            .setMessage("请允许 App 的省电策略被设置为无限制或不受限制, 以免息屏后无法进行短信转发")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                batteryOptiLauncher.launch(intent)
            }.setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
        val dialog = builder.create()
        dialog.show()
    }

    private fun checkAndRequestSmsPermission() {
        val smsPermission = Manifest.permission.RECEIVE_SMS
        val receiveSmsGranted = ContextCompat.checkSelfPermission(
            this, smsPermission
        ) == PackageManager.PERMISSION_GRANTED
        if (!receiveSmsGranted) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, smsPermission)) {
                AlertDialog.Builder(this).setTitle("短信权限请求")
                    .setMessage("为了 App 能正常工作, 请您授予接收短信的权限")
                    .setPositiveButton("授予权限") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.RECEIVE_SMS), smsPermissionCode
                        )
                    }.setNegativeButton("拒绝") { _, _ -> Log.e(tag, "短信权限已被拒绝") }.create()
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
        AlertDialog.Builder(this).setMessage("短信权限已被拒绝\nApp 可能无法正常使用")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }.setNegativeButton("取消") { _, _ ->
                Log.e(tag, "短信权限未获取")
            }.create().show()
    }

    /** 跳转至问题反馈页面 */
    private fun openFeedbackUrl() {
        AlertDialog.Builder(this).setTitle("打开外部浏览器").setMessage("即将跳转至反馈页面?")
            .setPositiveButton("确定") { _, _ ->
                val intent = Intent(
                    Intent.ACTION_VIEW, Uri.parse("https://shimo.im/forms/25q5X4Wl48fWJQ3D/fill")
                )
                startActivity(intent)
            }.setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }.show()

    }

    /** 跳转至常见问题页面 */
    private fun openQADialog() {
        lifecycleScope.launch {
            val jsonContent = jsonHandler.fetchQAJson(qaJson)
            jsonContent?.let { // 处理获取到的 JSON 内容
                val formattedContent = jsonHandler.formatQAJson(it)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("常见问题")
                    .setView(ScrollView(this@MainActivity).apply {
                        val scrollViewContext = context
                        val paddingHorizontal = 25.dpToPx(scrollViewContext)
                        val paddingVertical = 15.dpToPx(scrollViewContext)
                        addView(TextView(scrollViewContext).apply {
                            text = formattedContent
                            movementMethod = ScrollingMovementMethod()
                            setPadding(
                                paddingHorizontal,
                                paddingVertical,
                                paddingHorizontal,
                                paddingVertical
                            )
                        })
                    }).setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }.show()
            } ?: run {
                Log.e(tag, "处理 QA 数据时发生异常")
            }
        }
    }

    private fun Int.dpToPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return (this * density).toInt()
    }

    /** 播放导航图标动画 */
    private fun animateNavIcon() {
        // 导航图标起始位置
        val navIconView = toolbar.getChildAt(1) as? ImageView ?: return
        val startX = navIconView.x
        val endX = resources.displayMetrics.widthPixels.toFloat()

        isAnimating = true

        // 抖动动画
        val shakeAnimator1 = ObjectAnimator.ofFloat(
            navIconView, "translationX", 0f, 2f, -2f, 2f, -2f, 1f, -1f, 0f
        )
        shakeAnimator1.duration = 300

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
        val colorAnimatorToGreen = ValueAnimator.ofArgb(Color.BLACK, Color.parseColor("#0F826E"))
        colorAnimatorToGreen.addUpdateListener { animator ->
            DrawableCompat.setTint(navIconView.drawable, animator.animatedValue as Int)
        }
        colorAnimatorToGreen.duration = 500 // 颜色渐变时间

        val colorAnimatorToBlack = ValueAnimator.ofArgb(Color.parseColor("#0F826E"), Color.BLACK)
        colorAnimatorToBlack.addUpdateListener { animator ->
            DrawableCompat.setTint(navIconView.drawable, animator.animatedValue as Int)
        }

        colorAnimatorToBlack.duration = 1000 // 颜色复原时间

        // 图标从左向右移动
        val moveToRight = ObjectAnimator.ofFloat(navIconView, "x", startX, endX)
        moveToRight.duration = 1500
        moveToRight.interpolator = AnticipateOvershootInterpolator()
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
                colorAnimatorToGreen, shakeAnimatorSet
            )
        }, moveToRight, resetPosition, colorAnimatorToBlack)

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isAnimating = false
            }
        })
        animatorSet.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }
}



