package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.example.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class MrFeastBankForegroundService : Service() {

    companion object {
        private const val TAG = "BankForegroundService"
        private const val FOREGROUND_NOTIFICATION_ID = 9001
        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_STOP = "com.example.service.action.STOP"

        const val PREFS_NAME = "mrfeast_bank_prefs"
        const val KEY_USER_ID = "bank_user_id"
        const val KEY_LAST_TX_ID = "last_seen_tx_id"
        const val KEY_LAST_BALANCE = "last_seen_balance"

        fun startService(context: Context) {
            val intent = Intent(context, MrFeastBankForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MrFeastBankForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
        startAsForeground()
        startBackgroundMonitoring()
    }

    private fun startAsForeground() {
        val notification = NotificationHelper.buildForegroundNotification(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, FOREGROUND_NOTIFICATION_ID, notification, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        return START_STICKY
    }

    private var lastUpdateCheckTime = 0L

    private fun startBackgroundMonitoring() {
        serviceScope.launch {
            Log.d(TAG, "MrFeast Bank background monitoring started.")
            while (isActive) {
                try {
                    checkNewTransactions()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking background transactions", e)
                }
                try {
                    checkBackgroundAppUpdate()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking background app update", e)
                }
                // Poll every 15 seconds while in background
                delay(15_000)
            }
        }
    }

    private suspend fun checkBackgroundAppUpdate() {
        val now = System.currentTimeMillis()
        // Check GitHub release every 15 minutes in background
        if (now - lastUpdateCheckTime < 15 * 60 * 1000) return
        lastUpdateCheckTime = now

        try {
            val updateInfo = com.example.util.AppUpdateManager.checkForUpdate(this)
            if (updateInfo.isAvailable && updateInfo.apkUrl.isNotEmpty()) {
                Log.d(TAG, "Background update detected: v${updateInfo.latestVersion}")
                val apk = com.example.util.AppUpdateManager.downloadApk(this, updateInfo.apkUrl) { }
                if (apk != null && apk.exists()) {
                    com.example.util.AppUpdateManager.showUpdateNotification(this, updateInfo, apk)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background update check failed: ${e.message}")
        }
    }

    private fun checkNewTransactions() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userPin = prefs.getString("bank_user_pin", null)
        val userId = prefs.getString(KEY_USER_ID, null)
        val lastSeenTxId = prefs.getString(KEY_LAST_TX_ID, null)

        val supabaseUrl = "https://mlvrnmnbwmaeuabzrtlb.supabase.co"
        val supabaseKey = "sb_publishable_5zD51AsVKtmTzhtR20Fh7A_XFLK2iVk"

        try {
            val emptyBody = "{}".toRequestBody("application/json".toMediaType())
            val requestBuilder = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/list_bank_transactions")
                .post(emptyBody)
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")

            if (!userPin.isNullOrEmpty()) {
                requestBuilder.addHeader("x-bank-access-code", userPin)
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return

                val bodyString = response.body?.string() ?: return
                val jsonArray = JSONArray(bodyString)
                if (jsonArray.length() == 0) return

                val latestTx = jsonArray.getJSONObject(0)
                val txId = latestTx.optString("id")
                val recipientId = latestTx.optString("recipient_id")
                val amount = latestTx.optDouble("amount", 0.0)
                val desc = latestTx.optString("description", "")
                val isIncoming = (userId != null && recipientId == userId)

                if (lastSeenTxId != null && txId.isNotEmpty() && txId != lastSeenTxId) {
                    val formattedAmount = String.format(java.util.Locale.US, "%.2f", amount)
                    val title = if (isIncoming) {
                        "💰 Входящий перевод: +$$formattedAmount"
                    } else {
                        "💸 Списание: -$$formattedAmount"
                    }

                    val descText = if (desc.isNotEmpty()) " • $desc" else ""
                    val message = if (isIncoming) {
                        "Вам поступил перевод на сумму $$formattedAmount$descText"
                    } else {
                        "Выполнен перевод на сумму $$formattedAmount$descText"
                    }

                    NotificationHelper.showTransferNotification(
                        context = this@MrFeastBankForegroundService,
                        title = title,
                        message = message,
                        isIncoming = isIncoming
                    )
                }

                if (txId.isNotEmpty()) {
                    prefs.edit().putString(KEY_LAST_TX_ID, txId).apply()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check transactions RPC: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        Log.d(TAG, "MrFeast Bank foreground service destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
