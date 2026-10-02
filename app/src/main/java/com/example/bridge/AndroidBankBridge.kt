package com.example.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.example.service.MrFeastBankForegroundService
import com.example.util.NotificationHelper
import org.json.JSONObject
import java.util.Locale

class AndroidBankBridge(private val context: Context) {

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
}
