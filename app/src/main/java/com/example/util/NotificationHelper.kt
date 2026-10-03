package com.example.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

object NotificationHelper {

    const val CHANNEL_TRANSFERS_ID = "channel_transfers"
    const val CHANNEL_SERVICE_ID = "channel_service"
    const val CHANNEL_UPDATES_ID = "channel_updates"

    private const val FOREGROUND_NOTIFICATION_ID = 9001

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. High-priority channel for Transfers / Money changes
            val transferChannel = NotificationChannel(
                CHANNEL_TRANSFERS_ID,
                context.getString(R.string.channel_transfers_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_transfers_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 100, 250)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 2. Low-priority channel for persistent Foreground Service
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_service_desc)
                setShowBadge(false)
            }

            // 3. High-priority channel for App Updates & Auto-update
            val updatesChannel = NotificationChannel(
                CHANNEL_UPDATES_ID,
                context.getString(R.string.channel_updates_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_updates_desc)
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(transferChannel)
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(updatesChannel)
        }
    }

    fun buildForegroundNotification(context: Context): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE_ID)
            .setContentTitle("MrFeastProjectBank активен")
            .setContentText("Мониторинг безопасности и мгновенных переводов 24/7")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showTransferNotification(
        context: Context,
        title: String,
        message: String,
        isIncoming: Boolean = true,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt()
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFERS_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 300, 150, 300))
            .setContentIntent(pendingIntent)

        notificationManager.notify(notificationId, builder.build())
    }
}
