package com.otpautoforward.handler

import android.content.Context
import android.text.Html
import android.text.SpannableString
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.Request
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val tag = "OTPAutoForward"

class JsonHandler(private val context: Context) {

    /**
     * 从远程仓库中获取 Json 数据
     *
     * @param url 远程仓库中的资源地址, 参数为空时默认从本地读取 Json 数据
     * @param localFile assets 目录中, 本地 Json 的完整文件名
     * @return Json 数据
     */
    suspend fun fetchJson(url: String?, localFile: String): String? = withContext(Dispatchers.IO) {
        if (url.isNullOrEmpty()) {
            return@withContext try {
                context.assets.open(localFile).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(tag, "从本地读取 Json 数据时发生异常", e)
                null
            }
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .proxy(Proxy.NO_PROXY)
                .dns(Dns.SYSTEM)
                .build()

            val request = Request.Builder()
                .url(url)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Unexpected response code: $response")
                }
                response.body?.string()?.let { return@withContext it }
            }
        } catch (e: Exception) {
            Log.e(tag, "从网络读取 Json 数据时发生异常", e)
        }

        return@withContext try {
            context.assets.open(localFile).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(tag, "从本地读取 Json 数据时发生异常", e)
            null
        }
    }

    fun formatQAJson(jsonString: String): CharSequence {
        return try {
            val jsonObject = JSONObject(jsonString)
            val qaObject = jsonObject.getJSONObject("qa")
            val stringBuilder = StringBuilder()

            // "问" 的部分为黑色, "答" 的部分为浅灰色
            qaObject.keys().forEach { key ->
                val value = qaObject.getString(key)
                val color = if (key.startsWith("问")) "#000000" else "#666666"
                stringBuilder.append(
                    "<font color='$color'>$key：$value</font><br>${if (!key.startsWith("问")) "<br>" else ""}"
                )
            }

            Html.fromHtml(stringBuilder.toString(), Html.FROM_HTML_MODE_LEGACY)
        } catch (e: Exception) {
            SpannableString("暂无数据")
        }
    }

    fun formatPrivacyPolicyJson(jsonString: String): CharSequence {
        return try {
            val jsonObject = JSONObject(jsonString)
            val privacyArray = jsonObject.getJSONArray("privacyPolicy")
            val stringBuilder = StringBuilder()

            for (i in 0 until privacyArray.length()) {
                val item = privacyArray.getJSONObject(i)
                val title = item.getString("title")
                val content = item.getString("content")

                // 标题颜色改为黑色
                stringBuilder.append("<font color='#000000'>$title</font><br>")
                stringBuilder.append("<font color='#666666'>$content</font><br><br>")
            }

            if (jsonObject.has("publishTime")) {
                stringBuilder.append("<font color='#666666'>")
                stringBuilder.append(jsonObject.getString("publishTime"))
                stringBuilder.append("</font>")
            }

            Html.fromHtml(stringBuilder.toString(), Html.FROM_HTML_MODE_LEGACY)
        } catch (e: Exception) {
            SpannableString("暂无数据")
        }
    }

}