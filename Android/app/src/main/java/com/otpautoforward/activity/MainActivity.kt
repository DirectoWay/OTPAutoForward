package com.otpautoforward.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import com.google.android.material.snackbar.Snackbar
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.net.Uri
import android.view.MenuItem
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import com.otpautoforward.R
import com.otpautoforward.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    // 用于获取 MainActivity 的实例
    companion object {
        private var instance: MainActivity? = null
        fun getInstance(): MainActivity {
            return instance ?: throw IllegalStateException("MainActivity is not initialized")
        }
    }

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val smsPermissionCode = 100
    private lateinit var toolbar: Toolbar

    /** 当前动画状态 */
    private var isAnimating = false

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

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_main
            ), drawerLayout
        )
        setSupportActionBar(binding.appBarMain.toolbar)
        setupActionBarWithNavController(navController, appBarConfiguration)

        // 设置左上角导航图标
        supportActionBar?.setHomeAsUpIndicator(R.drawable.outline_rocket_launch_24)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 点击右下角短信 icon 的触发事件
        binding.appBarMain.fab.setOnClickListener { view ->
            Snackbar.make(view, "敬请期待", Snackbar.LENGTH_LONG).setAction("Action", null)
                .setAnchorView(R.id.fab).show()
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

            android.R.id.home -> {
                if (!isAnimating) {
                    animateNavIcon()
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkAndRequestSmsPermission() {
        val smsPermission = Manifest.permission.RECEIVE_SMS
        val readSmsPermission = Manifest.permission.READ_SMS
        if (ContextCompat.checkSelfPermission(
                this, smsPermission
            ) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this, readSmsPermission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(smsPermission, readSmsPermission), smsPermissionCode
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == smsPermissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("SMS", "短信权限已获取")
            } else {
                Log.e("SMS", "短信权限获取失败")
            }
        }
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

    /** 播放导航图标动画 */
    private fun animateNavIcon() {
        // 导航图标起始位置
        val navIconView = toolbar.getChildAt(1) as? ImageView ?: return
        val startX = navIconView.x
        val endX = resources.displayMetrics.widthPixels.toFloat()

        isAnimating = true

        // 抖动动画
        val shakeAnimator1 =
            ObjectAnimator.ofFloat(navIconView, "translationX", 0f, 2f, -2f, 2f, -2f, 1f, -1f, 0f)
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
        shakeAnimatorSet.playSequentially(shakeAnimator1, shakeAnimator2, shakeAnimator3)

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
        val resetPosition =
            ObjectAnimator.ofFloat(navIconView, "x", -navIconView.width.toFloat(), startX)
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