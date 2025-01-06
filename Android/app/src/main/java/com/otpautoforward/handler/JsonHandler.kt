package com.otpautoforward.handler

import android.content.Context
import android.graphics.Color
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException

private const val tag = "OTPAutoForward"

class JsonHandler(private val context: Context) {
    private val client = OkHttpClient()

    /** 获取 "常见问题" 中的问答内容 */
    suspend fun fetchQAJson(url: String?): String? {
        return withContext(Dispatchers.IO) {
            try {
                // url 不为空时先从网络上拉取 QA 数据
                if (url != null) {
                    val request = Request.Builder()
                        .url(url)
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Unexpected response code: $response")
                        }
                        return@withContext response.body?.string()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "从网络读取 QA 数据时发生异常", e)
            }

            // 从本地读取 QA 数据
            return@withContext try {
                context.assets.open("questionAndAnswer.json").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(tag, "从本地读取 QA 数据时发生异常", e)
                null
            }
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

}