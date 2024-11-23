package com.example.autocaptcha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.navigation.NavigationView
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.autocaptcha.databinding.ActivityMainBinding
import android.Manifest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModelProvider
import com.example.autocaptcha.service.SmsForegroundService
import com.example.autocaptcha.ui.pair.DevicePairViewModel

class MainActivity : AppCompatActivity() {
    // 用于获取 MainActivity 的实例
    companion object {
        private var instance: MainActivity? = null
        fun getInstance(): MainActivity {
            return instance ?: throw IllegalStateException("MainActivity is not initialized")
        }
    }

    private lateinit var devicePairViewModel: DevicePairViewModel
    private lateinit var networkReceiver: BroadcastReceiver
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val smsPermissionCode = 100

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        devicePairViewModel = ViewModelProvider(this)[DevicePairViewModel::class.java]

        // 点击右下角短信 icon 的触发事件
        binding.appBarMain.fab.setOnClickListener { view ->
            Snackbar.make(view, "敬请期待", Snackbar.LENGTH_LONG).setAction("Action", null)
                .setAnchorView(R.id.fab).show()
        }
        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_pair, R.id.nav_gallery, R.id.nav_settings
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        // 检查并请求短信权限
        checkAndRequestSmsPermission()

        // 启动短信服务
        if (devicePairViewModel.getSmsEnabledStatus()) {
            val intent = Intent(this, SmsForegroundService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        networkReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val connectivityManager =
                    context?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val networkInfo = connectivityManager.activeNetworkInfo
                if (networkInfo != null && networkInfo.isConnected) {
                    Log.d("network", "网络已连接")
                } else {
                    Log.d("network", "网络已断开")
                }
            }
        }
        registerReceiver(networkReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(networkReceiver)
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
                Log.d("SMS", "短信权限获取失败")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }
}