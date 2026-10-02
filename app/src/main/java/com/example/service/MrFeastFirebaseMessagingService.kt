package com.example.service

import android.util.Log
import com.example.util.NotificationHelper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MrFeastFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "MrFeastFCM"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New Firebase FCM Registration Token: $token")
        // Token can be sent to backend or saved in SharedPreferences
        val prefs = getSharedPreferences(MrFeastBankForegroundService.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        // 1. Check data payload
        val data = remoteMessage.data
        var title = data["title"]
        var body = data["body"]
        val type = data["type"] ?: "transfer"
        val amount = data["amount"]

        // 2. Check notification payload fallback
        remoteMessage.notification?.let {
            if (title == null) title = it.title
            if (body == null) body = it.body
        }

        if (title.isNullOrEmpty()) {
            title = "MrFeastProjectBank"
        }

        if (body.isNullOrEmpty()) {
            body = if (!amount.isNullOrEmpty()) {
                "Операция на сумму $$amount выполнена"
            } else {
                "Новая банковская операция в вашем аккаунте"
            }
        }

        val isIncoming = type != "outgoing" && !title!!.contains("Списание")

        NotificationHelper.showTransferNotification(
            context = applicationContext,
            title = title!!,
            message = body!!,
            isIncoming = isIncoming
        )
    }
}
