package com.example.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    const val GITHUB_REPO = "MrFeastProject/MrFeastProjectBank_Studio"
    const val NOTIFICATION_ID_UPDATE = 8002

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class UpdateInfo(
        val isAvailable: Boolean,
        val currentVersion: String,
        val latestVersion: String,
        val releaseTag: String,
        val apkUrl: String,
        val releaseNotes: String
    )

    suspend fun checkForUpdate(context: Context): UpdateInfo = withContext(Dispatchers.IO) {
        val currentVersion = BuildConfig.VERSION_NAME
        var latestVersion = currentVersion
        var releaseTag = "v$currentVersion"
        var releaseNotes = "Регулярное обновление безопасности и производительности банка MrFeast."
        var apkUrl = ""

        try {
            // First try GitHub API
            val apiUrl = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
            val apiRequest = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "MrFeastProjectBank/$currentVersion")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            var fetchedFromApi = false
            try {
                httpClient.newCall(apiRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrEmpty()) {
                            val json = JSONObject(body)
                            val tag = json.optString("tag_name", "").trim()
                            val bodyNotes = json.optString("body", "").trim()
                            if (tag.isNotEmpty()) {
                                releaseTag = tag
                                latestVersion = tag.removePrefix("v").trim()
                                if (bodyNotes.isNotEmpty()) {
                                    releaseNotes = bodyNotes
                                }
                                // Look for APK in assets
                                val assets = json.optJSONArray("assets")
                                if (assets != null) {
                                    for (i in 0 until assets.length()) {
                                        val asset = assets.getJSONObject(i)
                                        val name = asset.optString("name", "")
                                        if (name.endsWith(".apk", ignoreCase = true)) {
                                            apkUrl = asset.optString("browser_download_url", "")
                                            break
                                        }
                                    }
                                }
                                fetchedFromApi = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "GitHub API call failed, falling back to releases HTML redirect: ${e.message}")
            }

            // Fallback: If API was rate-limited, query the releases/latest web redirect
            if (!fetchedFromApi) {
                val webClient = OkHttpClient.Builder()
                    .followRedirects(false)
                    .build()
                val webRequest = Request.Builder()
                    .url("https://github.com/$GITHUB_REPO/releases/latest")
                    .addHeader("User-Agent", "MrFeastProjectBank/$currentVersion")
                    .head()
                    .build()

                webClient.newCall(webRequest).execute().use { response ->
                    val location = response.header("Location")
                    if (!location.isNullOrEmpty() && location.contains("/tag/")) {
                        val tag = location.substringAfterLast("/tag/").trim()
                        if (tag.isNotEmpty()) {
                            releaseTag = tag
                            latestVersion = tag.removePrefix("v").trim()
                        }
                    }
                }
            }

            if (apkUrl.isEmpty()) {
                apkUrl = "https://github.com/$GITHUB_REPO/releases/download/$releaseTag/MrFeastProjectBank-$latestVersion.apk"
            }

            val isNewer = isVersionGreater(latestVersion, currentVersion)
            Log.d(TAG, "Check update result: current=$currentVersion, latest=$latestVersion, isNewer=$isNewer, apkUrl=$apkUrl")

            UpdateInfo(
                isAvailable = isNewer,
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                releaseTag = releaseTag,
                apkUrl = apkUrl,
                releaseNotes = releaseNotes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}", e)
            UpdateInfo(
                isAvailable = false,
                currentVersion = currentVersion,
                latestVersion = currentVersion,
                releaseTag = "v$currentVersion",
                apkUrl = "",
                releaseNotes = ""
            )
        }
    }

    fun isVersionGreater(remote: String, local: String): Boolean {
        try {
            val rParts = remote.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val lParts = local.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val maxLen = maxOf(rParts.size, lParts.size)

            for (i in 0 until maxLen) {
                val r = if (i < rParts.size) rParts[i] else 0
                val l = if (i < lParts.size) lParts[i] else 0
                if (r > l) return true
                if (r < l) return false
            }
        } catch (e: Exception) {
            return remote != local
        }
        return false
    }

    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apkUrl)
                .addHeader("User-Agent", "MrFeastProjectBank/${BuildConfig.VERSION_NAME}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download APK: HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body ?: return@withContext null
            val totalBytes = body.contentLength()

            val destFile = File(context.cacheDir, "MrFeastProjectBank-update.apk")
            if (destFile.exists()) destFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead: Long = 0
                    var lastPercent = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((totalRead * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                    output.flush()
                }
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
            }
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading update APK: ${e.message}", e)
            null
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
        }
    }

    fun showUpdateNotification(context: Context, updateInfo: UpdateInfo, downloadedApk: File? = null) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = if (downloadedApk != null && downloadedApk.exists()) {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                downloadedApk
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "🚀 Доступно обновление MrFeast Bank v${updateInfo.latestVersion}"
        val text = if (downloadedApk != null && downloadedApk.exists()) {
            "Новая версия v${updateInfo.latestVersion} загружена и готова к установке. Нажмите для обновления!"
        } else {
            "Вышла версия v${updateInfo.latestVersion}. Нажмите для обновления банка."
        }

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_TRANSFERS_ID)
            .setSmallIcon(com.example.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(NOTIFICATION_ID_UPDATE, notification)
    }
}
