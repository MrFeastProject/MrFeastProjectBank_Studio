package com.example.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.example.service.MrFeastBankForegroundService
import com.example.util.NotificationHelper
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AndroidBankBridge(
    private val context: Context,
    private val webViewProvider: () -> android.webkit.WebView? = { null }
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun showNotification(title: String, message: String, isIncoming: Boolean) {
        NotificationHelper.showTransferNotification(
            context = context,
            title = title,
            message = message,
            isIncoming = isIncoming
        )
    }

    @JavascriptInterface
    fun onUserAuthenticated(userId: String, userName: String, cardNumber: String, balance: Double) {
        val prefs = context.getSharedPreferences(
            MrFeastBankForegroundService.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putString(MrFeastBankForegroundService.KEY_USER_ID, userId)
            .putString("bank_user_name", userName)
            .putString("bank_card_number", cardNumber)
            .putFloat(MrFeastBankForegroundService.KEY_LAST_BALANCE, balance.toFloat())
            .apply()

        // Ensure the foreground service is running for this user
        MrFeastBankForegroundService.startService(context)
    }

    @JavascriptInterface
    fun saveUserPin(pin: String) {
        val prefs = context.getSharedPreferences(
            MrFeastBankForegroundService.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().putString("bank_user_pin", pin).apply()
    }

    @JavascriptInterface
    fun getUserPin(): String {
        val prefs = context.getSharedPreferences(
            MrFeastBankForegroundService.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        return prefs.getString("bank_user_pin", "") ?: ""
    }

    @JavascriptInterface
    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    @JavascriptInterface
    fun getDeviceOs(): String {
        return "Android ${Build.VERSION.RELEASE}"
    }

    @JavascriptInterface
    fun getDeviceInfoJson(): String {
        val json = JSONObject()
        json.put("model", getDeviceModel())
        json.put("os", getDeviceOs())
        return json.toString()
    }

    @JavascriptInterface
    fun vibratePaymentSuccess() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 90, 60, 160)
            val amplitudes = intArrayOf(0, 200, 0, 255)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 90, 60, 160), -1)
        }
    }

    @JavascriptInterface
    fun vibrateTerminalSuccess() {
        vibratePaymentSuccess()
    }

    @JavascriptInterface
    fun vibrateTap() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    @JavascriptInterface
    fun openExternalUrl(url: String) {
        mainHandler.post {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось открыть ссылку: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        mainHandler.post {
            try {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("MrFeastBank", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Скопировано: $text", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun shareAppApk() {
        mainHandler.post {
            try {
                val appInfo = context.applicationInfo
                val originalApk = java.io.File(appInfo.sourceDir)
                if (!originalApk.exists()) {
                    Toast.makeText(context, "Файл APK не найден", Toast.LENGTH_SHORT).show()
                    return@post
                }

                // Copy to cache dir with clean name "MrFeastProjectBank.apk"
                val cacheApk = java.io.File(context.cacheDir, "MrFeastProjectBank.apk")
                originalApk.copyTo(cacheApk, overwrite = true)

                val authority = "${context.packageName}.fileprovider"
                val apkUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    authority,
                    cacheApk
                )

                val shareText = "Я пользуюсь банковским приложением от MrFeastProject (@MrFeast_Official)!"

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, apkUri)
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    putExtra(Intent.EXTRA_SUBJECT, "MrFeastProjectBank")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(intent, "Поделиться MrFeastProjectBank").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                // Fallback: share text
                try {
                    val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Я пользуюсь банковским приложением от MrFeastProject (@MrFeast_Official)!")
                        putExtra(Intent.EXTRA_SUBJECT, "MrFeastProjectBank")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(fallbackIntent, "Поделиться MrFeastProjectBank").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (fallbackEx: Exception) {
                    Toast.makeText(context, "Не удалось поделиться: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        return com.example.BuildConfig.VERSION_NAME
    }

    @JavascriptInterface
    fun getAppVersionCode(): Int {
        return com.example.BuildConfig.VERSION_CODE
    }

    @JavascriptInterface
    fun checkForAppUpdate(forceShowIfLatest: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            val updateInfo = com.example.util.AppUpdateManager.checkForUpdate(context)
            val json = JSONObject().apply {
                put("isAvailable", updateInfo.isAvailable)
                put("currentVersion", updateInfo.currentVersion)
                put("latestVersion", updateInfo.latestVersion)
                put("releaseTag", updateInfo.releaseTag)
                put("apkUrl", updateInfo.apkUrl)
                put("releaseNotes", updateInfo.releaseNotes)
                put("forceShow", forceShowIfLatest)
            }
            mainHandler.post {
                val script = "if(window.onAppUpdateChecked) { window.onAppUpdateChecked(${json}); }"
                webViewProvider()?.evaluateJavascript(script, null)
            }
        }
    }

    @JavascriptInterface
    fun downloadAndInstallUpdate(apkUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val apkFile = com.example.util.AppUpdateManager.downloadApk(context, apkUrl) { percent ->
                mainHandler.post {
                    webViewProvider()?.evaluateJavascript(
                        "if(window.onAppUpdateProgress) { window.onAppUpdateProgress($percent); }",
                        null
                    )
                }
            }

            if (apkFile != null && apkFile.exists()) {
                mainHandler.post {
                    webViewProvider()?.evaluateJavascript(
                        "if(window.onAppUpdateReady) { window.onAppUpdateReady(); }",
                        null
                    )
                    com.example.util.AppUpdateManager.installApk(context, apkFile)
                }
            } else {
                mainHandler.post {
                    Toast.makeText(context, "Не удалось скачать обновление", Toast.LENGTH_SHORT).show()
                    webViewProvider()?.evaluateJavascript(
                        "if(window.onAppUpdateFailed) { window.onAppUpdateFailed('Ошибка загрузки файла'); }",
                        null
                    )
                }
            }
        }
    }

    @JavascriptInterface
    fun downloadUpdateInBackground(apkUrl: String, latestVersion: String) {
        CoroutineScope(Dispatchers.IO).launch {
            mainHandler.post {
                Toast.makeText(context, "Загрузка обновления v$latestVersion в фоне...", Toast.LENGTH_SHORT).show()
            }
            val apkFile = com.example.util.AppUpdateManager.downloadApk(context, apkUrl) { }
            if (apkFile != null && apkFile.exists()) {
                val updateInfo = com.example.util.AppUpdateManager.UpdateInfo(
                    isAvailable = true,
                    currentVersion = com.example.BuildConfig.VERSION_NAME,
                    latestVersion = latestVersion,
                    releaseTag = "v$latestVersion",
                    apkUrl = apkUrl,
                    releaseNotes = ""
                )
                com.example.util.AppUpdateManager.showUpdateNotification(context, updateInfo, apkFile)
            }
        }
    }
}
