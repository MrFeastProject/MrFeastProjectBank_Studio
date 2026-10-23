package com.example.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object FrameworkManager {
    private const val TAG = "FrameworkManager"

    const val PACKAGE_NAME = "com.mrfeastproject.framework.connect"
    const val DEFAULT_APK_URL = "https://github.com/MrFeastProject/MrFeastProjectBank-Web/raw/main/MrFeastProjectFramework.apk"
    const val BLOB_APK_URL = "https://github.com/MrFeastProject/MrFeastProjectBank-Web/blob/main/MrFeastProjectFramework.apk"
    const val FALLBACK_APK_URL = "https://raw.githubusercontent.com/MrFeastProject/MrFeastProjectBank-Web/main/MrFeastProjectFramework.apk"
    const val REPO_URL = "https://github.com/MrFeastProject/MrFeastProject-Framework"

    private const val PREFS_NAME = "mrfeast_framework_prefs"
    private const val KEY_LAST_LAUNCHED = "key_last_launched_timestamp"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var isSessionLaunched = false

    /**
     * Checks if com.mrfeastproject.framework.connect is installed on the device.
     */
    fun isInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Exception checking framework package: ${e.message}")
            false
        }
    }

    /**
     * Checks if com.mrfeastproject.framework.connect is currently running.
     */
    fun isRunning(context: Context): Boolean {
        if (!isInstalled(context)) return false

        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val runningProcesses = am.runningAppProcesses
                if (runningProcesses != null) {
                    for (proc in runningProcesses) {
                        if (proc.processName == PACKAGE_NAME || proc.processName.startsWith("$PACKAGE_NAME:")) {
                            return true
                        }
                    }
                }

                @Suppress("DEPRECATION")
                val runningServices = am.getRunningServices(100)
                if (runningServices != null) {
                    for (srv in runningServices) {
                        if (srv.service.packageName == PACKAGE_NAME) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking if framework is running: ${e.message}")
        }

        // Check if it was launched in this current process session
        if (isSessionLaunched) {
            return true
        }

        // Check if launched recently within last 2 minutes
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastLaunched = prefs.getLong(KEY_LAST_LAUNCHED, 0L)
        if (lastLaunched > 0 && System.currentTimeMillis() - lastLaunched < 120_000L) {
            return true
        }

        return false
    }

    /**
     * Launches com.mrfeastproject.framework.connect
     */
    fun launch(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(PACKAGE_NAME)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                markLaunched(context)
                true
            } else {
                Log.w(TAG, "No launch intent found for $PACKAGE_NAME")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $PACKAGE_NAME: ${e.message}", e)
            false
        }
    }

    fun markLaunched(context: Context) {
        isSessionLaunched = true
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_LAST_LAUNCHED, System.currentTimeMillis()).apply()
        } catch (_: Exception) {}
    }

    /**
     * Downloads MrFeastProjectFramework.apk with progress tracking.
     */
    suspend fun downloadFrameworkApk(
        context: Context,
        rawUrl: String = DEFAULT_APK_URL,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        // Resolve raw URL if github blob URL was passed
        val candidateUrls = mutableListOf<String>()
        val directUrl = if (rawUrl.contains("github.com") && rawUrl.contains("/blob/")) {
            rawUrl.replace("/blob/", "/raw/")
        } else {
            rawUrl
        }
        candidateUrls.add(directUrl)
        if (directUrl != DEFAULT_APK_URL) candidateUrls.add(DEFAULT_APK_URL)
        if (directUrl != FALLBACK_APK_URL) candidateUrls.add(FALLBACK_APK_URL)

        val targetDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val destFile = File(targetDir, "MrFeastProjectFramework.apk")

        for (url in candidateUrls) {
            try {
                Log.d(TAG, "Attempting to download framework from: $url")
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "MrFeastProjectBank/${BuildConfig.VERSION_NAME}")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Download attempt failed for $url: HTTP ${response.code}")
                    continue
                }

                val body = response.body ?: continue
                val totalBytes = body.contentLength()

                if (destFile.exists()) {
                    destFile.delete()
                }

                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        var totalRead: Long = 0
                        var lastPercent = 0

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                val percent = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 99)
                                if (percent > lastPercent) {
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

                // Verify file size
                if (destFile.exists() && destFile.length() > 1024) {
                    destFile.setReadable(true, false)
                    destFile.setWritable(true, false)
                    withContext(Dispatchers.Main) {
                        onProgress(100)
                    }
                    Log.d(TAG, "Framework APK downloaded successfully (${destFile.length()} bytes)")
                    return@withContext destFile
                } else {
                    Log.w(TAG, "Downloaded file is suspiciously small or missing: ${destFile.length()} bytes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error from $url: ${e.message}")
            }
        }
        null
    }
}
