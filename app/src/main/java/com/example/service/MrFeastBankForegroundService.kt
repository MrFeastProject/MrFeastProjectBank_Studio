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
import okhttp3.OkHttpClient
import okhttp3.Request
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

    private fun startBackgroundMonitoring() {
        serviceScope.launch {
            Log.d(TAG, "MrFeast Bank background monitoring started.")
            while (isActive) {
                try {
                    checkNewTransactions()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking background transactions", e)
                }
                // Poll every 20 seconds while in background
                delay(20_000)
            }
        }
    }

    private fun checkNewTransactions() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString(KEY_USER_ID, null) ?: return
        val lastSeenTxId = prefs.getString(KEY_LAST_TX_ID, null)

        val supabaseUrl = "https://mlvrnmnbwmaeuabzrtlb.supabase.co"
        val supabaseKey = "sb_publishable_5zD51AsVKtmTzhtR20Fh7A_XFLK2iVk"

        // Query transactions where recipient_id == userId or sender_id == userId
        val url = "$supabaseUrl/rest/v1/transactions?or=(recipient_id.eq.$userId,sender_id.eq.$userId)&order=created_at.desc&limit=1"

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", supabaseKey)
            .addHeader("Authorization", "Bearer $supabaseKey")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return

            val bodyString = response.body?.string() ?: return
            val jsonArray = JSONArray(bodyString)
            if (jsonArray.length() == 0) return

            val latestTx = jsonArray.getJSONObject(0)
            val txId = latestTx.optString("id")
            val recipientId = latestTx.optString("recipient_id")
            val senderId = latestTx.optString("sender_id")
            val amount = latestTx.optDouble("amount", 0.0)
            val desc = latestTx.optString("description", "")

            if (lastSeenTxId != null && txId != lastSeenTxId) {
                // New transaction found!
                val isIncoming = recipientId == userId
                val formattedAmount = String.format("%.2f", amount)

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

            // Update last seen transaction
            prefs.edit().putString(KEY_LAST_TX_ID, txId).apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        Log.d(TAG, "MrFeast Bank foreground service destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
