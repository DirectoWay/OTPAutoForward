package com.autocaptcha.handler

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/** 更新 App 时的逻辑 */
class UpdateHandler(context: Context) {
    // 获取版本号
    var version: String? = null
        private set

    // 获取下载地址
    private var downloadUrl: String? = null

    // 获取发布说明地址
    private var releaseNotesUrl: String? = null

    init {
        readConfigFile(context)
    }

    private fun readConfigFile(context: Context) {
        try {
            val inputStream = context.assets.open(CONFIG_FILE_NAME)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val stringBuilder = StringBuilder()
            var line: String?
            while ((reader.readLine().also { line = it }) != null) {
                stringBuilder.append(line)
            }
            val jsonObject = JSONObject(stringBuilder.toString())

            version = jsonObject.getString("version")
            downloadUrl = jsonObject.getString("download_url")
            releaseNotesUrl = jsonObject.getString("release_notes")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val CONFIG_FILE_NAME = "config.json"
    }
}
