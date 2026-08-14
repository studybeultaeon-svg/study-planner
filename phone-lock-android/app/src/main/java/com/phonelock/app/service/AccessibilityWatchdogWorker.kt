package com.phonelock.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phonelock.app.R
import com.phonelock.app.data.PhoneLockRepository

private const val CHANNEL_ID = "accessibility_watchdog"
private const val NOTIFICATION_ID = 1001

/** 주기적으로(WorkManager 최소 간격인 15분마다) 접근성 서비스가 꺼져있는지 확인하고, 꺼져있으면서
 *  차단 대상 그룹이 하나라도 있으면 알림으로 알려준다. 앱을 열지 않아도 동작해야 하는 감시이므로
 *  WorkManager를 쓴다. */
class AccessibilityWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (AccessibilityServiceChecker.isEnabled(context)) return Result.success()

        val repository = PhoneLockRepository(context)
        val hasAnyGroup = repository.getAllEnabledGroups().isNotEmpty()
        if (!hasAnyGroup) return Result.success()

        showWarningNotification(context)
        return Result.success()
    }

    private fun showWarningNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "접근성 서비스 감시",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "접근성 서비스가 꺼지면 차단 기능이 동작하지 않는다는 것을 알려줍니다."
            }
            manager.createNotificationChannel(channel)
        }

        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, settingsIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("접근성 서비스가 꺼져 있습니다")
            .setContentText("지금 어떤 앱/사이트도 차단되지 않고 있습니다. 눌러서 다시 켜세요.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}
