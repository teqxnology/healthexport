package com.healthexport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ManualExportReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val channelId = "health_export_reminder"
    private val notificationId = 1001

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("health_export_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("manual_reminder_enabled", false)
        if (!enabled) return Result.success()

        createChannelIfNeeded()

        val intent = Intent(applicationContext, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Health export ready")
            .setContentText("Tap to export")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)

        // Reschedule next day at the same time
        scheduleNext()

        return Result.success()
    }

    private fun scheduleNext() {
        val prefs = applicationContext.getSharedPreferences("health_export_prefs", Context.MODE_PRIVATE)
        val hour = prefs.getInt("manual_reminder_hour", -1)
        val minute = prefs.getInt("manual_reminder_minute", -1)
        if (hour < 0 || minute < 0) return

        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val delayMs = next.timeInMillis - now.timeInMillis

        val req = OneTimeWorkRequestBuilder<ManualExportReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "manual_export_reminder",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                channelId,
                "Export reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Daily reminder to manually export Health Connect data"
                enableVibration(true)
            }
            nm.createNotificationChannel(ch)
        }
    }
}