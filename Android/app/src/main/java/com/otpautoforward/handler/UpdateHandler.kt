package com.otpautoforward.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.otpautoforward.dataclass.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val tag = "UpdateHandler"

/** 更新 App 时的逻辑 */
class UpdateHandler {
    /** 最新版本号 */
    private lateinit var _latestVersion: String

    /** 新版本安装包的下载地址 */
    private lateinit var _downloadUrl: String

    private lateinit var currentVersion: String

    private val releaseSource = AppConfig.ReleasesSource.value.lowercase()

    private val repositoryOwner = AppConfig.RepositoryOwner.value

    private val repository = AppConfig.Repository.value

    private val client = OkHttpClient()

    suspend fun checkUpdatesAsync(context: Context) {
        if (AppConfig.ReleasesSource.value.isBlank()) {
            Log.e(tag, "未配置更新仓库源, 更新失败")
            showToastNotification(context, "暂无更新")
            return
        }

        try {
            WaitDialog.show("检查更新中")
            val latestRelease = getLatestReleaseAsync()
            WaitDialog.dismiss()

            if (latestRelease == null) {
                Log.e(tag, "获取最新版本信息失败")
                showToastNotification(context, "暂无更新")
                return
            }

            val latestVersion = latestRelease.first
            val downloadUrl = latestRelease.second

            if (latestVersion.isNullOrBlank() || downloadUrl.isNullOrBlank()) {
                showToastNotification(context, "暂无更新")
                return
            }

            currentVersion =
                context.packageManager.getPackageInfo(context.packageName, 0).versionName

            if (latestVersion == currentVersion) {
                showToastNotification(
                    context, "当前版本已是最新版\n当前版本: $currentVersion"
                )
            }

            _latestVersion = latestVersion
            _downloadUrl = downloadUrl
            showUpdateDialog(context)

        } catch (ex: Exception) {
            Log.e(tag, "检查更新时发生异常: $ex")
            showToastNotification(context, "检查更新失败, 请稍后再试")
        }
    }

    /**
     * 获取最新版本
     *
     * @return 最新版本号, 安装包下载地址
     */
    private suspend fun getLatestReleaseAsync(): Pair<String?, String?>? {
        return when {
            releaseSource.contains("gitee") -> {
                val id = getGiteeReleaseIdAsync() ?: return Pair(null, null)
                return getReleaseAsync(
                    "https://gitee.com/api/v5/repos/$repositoryOwner/$repository", "/releases/", id
                )
            }

            releaseSource.contains("github") -> {
                return getReleaseAsync(
                    "https://api.github.com/repos/$repositoryOwner/$repository", "/releases/latest"
                )
            }

            else -> null
        }
    }

    /**
     * 从 Gitee 仓库源获取最新版本的 ID
     *
     * @return 最新发行版的 ID 号
     */
    private suspend fun getGiteeReleaseIdAsync(): Int? {
        val giteeUrl = "https://gitee.com/api/v5/repos/"
        val domain = "/releases?"
        val owner = repositoryOwner
        val repo = repository
        val page = 1
        val perPage = 1
        val direction = "desc" // 降序排列 release 版本 (首号永远为最新版)
        val requestUrlString =
            "$giteeUrl$owner/$repo$domain&page=$page&per_page=$perPage&direction=$direction"

        return try {
            withContext(Dispatchers.IO) {
                val requestUrl = URL(requestUrlString)
                val connection = requestUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                val responseCode = connection.responseCode

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext null
                }

                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                val releases = JSONArray(responseBody)

                if (releases.length() <= 0) return@withContext null

                val firstRelease = releases.getJSONObject(0)
                val releaseId = firstRelease.optInt("id")
                if (releaseId == 0) null else releaseId
            }
        } catch (ex: IOException) {
            Log.e(tag, "从 Gitee 获取发行版ID失败: $ex")
            null
        } catch (ex: Exception) {
            Log.e(tag, "从 Gitee 获取发行版ID失败: $ex")
            null
        }
    }

    /**
     * 从不同的仓库源获取最新发行版
     *
     * @param baseUrl 仓库源地址, 支持 Gitee 与 GitHub
     * @param domain API 操作
     * @param id 远程仓库 ID
     * @return 最新版本号, apk 安装包的下载地址
     */
    private suspend fun getReleaseAsync(
        baseUrl: String, domain: String, id: Int? = null
    ): Pair<String?, String?> {
        val requestUrlString = if (id == null) "$baseUrl$domain" else "$baseUrl$domain$id"
        return try {
            withContext(Dispatchers.IO) {
                val requestUrl = URL(requestUrlString)
                val connection = requestUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Pair(null, null)
                }

                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                val releaseObject = JSONObject(responseBody)

                val latestVersion = releaseObject
                    .optString("tag_name")
                    .lowercase()
                    .replace("v", "")
                    .replace("ver", "")
                val assets = releaseObject.optJSONArray("assets")

                val downloadUrl = assets?.let {
                    for (i in 0 until it.length()) {
                        val asset = it.getJSONObject(i)
                        val url = asset.optString("browser_download_url")
                        if (url.contains("apk")) {
                            return@let url
                        }
                    }
                    null
                }
                Pair(latestVersion, downloadUrl)
            }
        } catch (ex: Exception) {
            Log.e(tag, "从 $baseUrl 获取更新包失败: $ex")
            Pair(null, null)
        }
    }

    /**
     * 根据提供的 url 下载 apk 文件
     *
     * @param context
     * @return 下载好的 apk 文件的绝对路径
     */
    private suspend fun downloadFileAsync(
        context: Context
    ): String? {
        WaitDialog.show("加载中")
        return try {
            val result = withContext(Dispatchers.IO) {
                val externalCacheDir = context.externalCacheDir
                    ?: throw Exception("获取安装包存储目录时发生异常")

                if (!externalCacheDir.exists()) {
                    externalCacheDir.mkdirs()
                }

                // 清空可能残留的 apk 文件
                deleteApk(externalCacheDir)

                val request = Request.Builder().url(_downloadUrl).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("下载文件时发生异常${response.message}")
                }

                WaitDialog.dismiss()

                response.body?.let { body ->
                    val contentLength = body.contentLength()
                    if (contentLength == -1L) {
                        throw Exception("无法获取文件大小, 取消下载")
                    }

                    val outputFile = File(externalCacheDir, "update.apk")
                    val inputStream: InputStream = body.byteStream()
                    val outputStream = FileOutputStream(outputFile)

                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead: Long = 0

                    val dialog = WaitDialog.show("正在下载... 0%").setProgress(0f)
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val progress = (totalBytesRead.toFloat() / contentLength)
                        val progressPercentage = (progress * 100).toInt()
                        dialog.setProgress(progress)
                        dialog.setMessageContent("正在下载... $progressPercentage%")
                    }

                    outputStream.close()
                    inputStream.close()
                    dialog.doDismiss()
                    TipDialog.show("下载完成", WaitDialog.TYPE.SUCCESS)
                    outputFile.absolutePath
                }
            }
            result
        } catch (ex: Exception) {
            WaitDialog.dismiss()
            Log.e(tag, "下载更新包时发生异常: $ex")
            null
        }
    }

    /**
     * 安装 apk 文件
     *
     * @param context
     * @param apkPath  apk 文件所在的路径
     */
    private fun installApk(context: Context, apkPath: String) {
        val apkFile = File(apkPath)
        val apkUri: Uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法启动安装程序: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 删除指定目录下的所有 apk 文件
     *
     * @param filesDir 文件目录
     */
    private fun deleteApk(filesDir: File) {
        filesDir.listFiles()?.forEach { file ->
            if (file.extension == "apk") {
                file.delete()
            }
        }
    }

    /** 请求更新弹窗 */
    private fun showUpdateDialog(context: Context) {
        MessageDialog.build()
            .setTitle("版本更新")
            .setMessage("已经检测到新版本, 确定要更新吗?\n更新版本: $_latestVersion\n当前版本: $currentVersion")
            .setOkButton("确定更新") { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    val filePath = downloadFileAsync(context)
                    if (filePath == null) {
                        showToastNotification(context, "安装包下载失败, 请您稍后再试")
                        return@launch
                    }
                    showToastNotification(context, "安装包下载完毕, 即将进行版本更新")
                    installApk(context, filePath)
                }
                false
            }
            .setCancelButton("暂不更新")
            .show()
    }

    private fun showToastNotification(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

}