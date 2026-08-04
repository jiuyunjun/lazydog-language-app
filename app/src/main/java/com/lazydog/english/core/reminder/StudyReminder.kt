package com.lazydog.english.core.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.lazydog.english.MainActivity
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 每日学习提醒：WorkManager 周期任务（非精确时间，前后可能有些浮动）。
 */
object StudyReminder {

    private const val WORK_NAME = "daily_study_reminder"
    private const val CHANNEL_ID = "study_reminder"

    /** [time] 形如 "20:00"。 */
    fun schedule(context: Context, time: String) {
        val parsed = runCatching { LocalTime.parse(time) }.getOrNull() ?: return
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(parsed)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val initialDelay = Duration.between(now, next)

        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay.toMinutes(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    class ReminderWorker(
        context: Context,
        params: WorkerParameters,
    ) : Worker(context, params) {

        override fun doWork(): Result {
            val context = applicationContext
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted && android.os.Build.VERSION.SDK_INT >= 33) return Result.success()

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "学习提醒", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val intent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("该放洋屁了")
                .setContentText("今天的十几分钟还没动，进来点两下就行。")
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
            manager.notify(1, notification)
            return Result.success()
        }
    }
}
