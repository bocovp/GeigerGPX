package com.github.bocovp.geigergpx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.Locale

class TrackingNotificationManager(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    TRACKING_CHANNEL_ID,
                    "Geiger GPX Tracking",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Dose rate alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    fun buildTrackingNotification(text: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, pendingFlags)

        return NotificationCompat.Builder(context, TRACKING_CHANNEL_ID)
            .setContentTitle("Geiger GPX")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    fun postAlertNotification(meanDoseRate: Double, unit: String) {
        val message = String.format(Locale.US, "Dose rate %.2f %s", meanDoseRate, unit)
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Radiation alert")
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(ALERT_NOTIF_ID, notification)
    }

    fun showSaveToast(message: String?) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.util.Log.e("MYTAG", "Could not show toast: ${e.message}")
            }
        }
    }

    fun cancelTracking() {
        nm.cancel(NOTIF_ID)
    }

    companion object {
        const val TRACKING_CHANNEL_ID = "geigergpx_channel"
        const val ALERT_CHANNEL_ID = "geigergpx_alert_channel"
        const val NOTIF_ID = 1001
        private const val ALERT_NOTIF_ID = 2002
    }
}
