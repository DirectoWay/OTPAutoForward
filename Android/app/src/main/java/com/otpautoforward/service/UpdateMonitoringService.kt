package com.otpautoforward.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_REPLACED) {
            return
        }
        val data = intent.dataString
        if (data != null && data == "package:${context.packageName}") {
            // 更新完毕后删除安装包
            val externalFilesDir = context.getExternalFilesDir(null)
            externalFilesDir?.listFiles()?.forEach { file ->
                if (file.extension == "apk") {
                    file.delete()
                }
            }
            val currentVersion =
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            Log.d("UpdateReceiver", "成功更新至最新版本$currentVersion")
        }
    }
}