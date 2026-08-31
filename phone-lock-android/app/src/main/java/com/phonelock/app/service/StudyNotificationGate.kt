package com.phonelock.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.phonelock.app.R
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.ui.MainActivity

// AppMonitorAccessibilityService.REMOTE_STUDY_SIGNAL_STALE_MS와 같은 값.
private const val REMOTE_STUDY_SIGNAL_STALE_MS = 20 * 60 * 1000L

// RoutineReminderReceiver.VIBRATE_PATTERN과 같은 값 — 이 창구를 거치는 알림(루틴 리마인더/스트릭/
// 모임 무작위 알림)의 채널이 전부 이 패턴으로 만들어져 있다. Android 8 미만은 채널이 아니라 이 값을
// 직접 보므로, 채널 설정과 중복이어도 안전하게 명시해둔다(RoutineReminderReceiver.notify()와 동일 원칙).
private val VIBRATE_PATTERN = longArrayOf(0, 250, 150, 250)

/**
 * 공부 페이즈 중 이 앱 자신이 보내는 알림(루틴 리마인더/스트릭/모임 깨우기/무전기)을 억제하는 창구.
 * 시스템 방해금지(DND)로 모든 알림을 무음 처리하던 이전 구현을 사용자 요청으로 걷어내고, 이 앱의
 * 알림만 개별적으로 막는 방식으로 교체했다(다른 앱 알림은 건드리지 않음).
 *
 * 모임 깨우기/무전기 메시지는 서버(RTDB)에 안 읽힌 채로 남아있으므로 공부가 끝난 뒤 다음 폴링에서
 * 저절로 다시 알림이 온다 — [WalkieTalkieService]/[GroupNudgeWorker]는 공부 중이면 그 폴링 처리
 * 자체를 건너뛰기만 하면 된다(별도 큐 불필요). 반면 루틴 리마인더/스트릭은 정해진 시각에 한 번만
 * 발화하는 일회성 알람이라 그 순간을 놓치면 다시 올 계기가 없으므로, 이 창구가 [showOrQueue]로
 * 큐에 쌓아뒀다가 공부가 끝나면 [flushQueued]로 전부 다시 띄운다.
 */
object StudyNotificationGate {

    suspend fun isStudying(repository: PhoneLockRepository): Boolean =
        repository.isStudyLockActive() || isRemoteStudyTimerActive(repository)

    private suspend fun isRemoteStudyTimerActive(repository: PhoneLockRepository): Boolean {
        val url = repository.fbDatabaseUrl
        val key = repository.fbApiKey
        if (!PomodoroSyncClient.isStudyTimerActive(url, key)) return false
        val updatedAt = PomodoroSyncClient.remoteUpdatedAtMillis(url, key)
        return updatedAt > 0 && System.currentTimeMillis() - updatedAt < REMOTE_STUDY_SIGNAL_STALE_MS
    }

    /** 일회성 알림(루틴 리마인더/스트릭) 전용 — 공부 중이면 지금 띄우는 대신 큐에 쌓아둔다. */
    suspend fun showOrQueue(context: Context, repository: PhoneLockRepository, id: Int, channelId: String, title: String, text: String) {
        if (isStudying(repository)) {
            AppPreferences(context).addQueuedStudyNotification(
                AppPreferences.QueuedNotification(id, channelId, title, text)
            )
        } else {
            post(context, id, channelId, title, text)
        }
    }

    /** 공부가 끝났을 때(AppMonitorAccessibilityService의 tick에서 호출) 큐에 쌓인 알림을 전부 다시 띄운다. */
    fun flushQueued(context: Context) {
        val prefs = AppPreferences(context)
        val queued = prefs.queuedStudyNotifications()
        if (queued.isEmpty()) return
        queued.forEach { post(context, it.id, it.channelId, it.title, it.text) }
        prefs.clearQueuedStudyNotifications()
    }

    private fun post(context: Context, id: Int, channelId: String, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val pendingIntent = PendingIntent.getActivity(
            context, id, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(VIBRATE_PATTERN)
            .build()
        manager.notify(id, notification)
    }
}
