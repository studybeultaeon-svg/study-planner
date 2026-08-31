package com.phonelock.app.routine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phonelock.app.R
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.ui.MainActivity

// v2: 진동 없이 기존 채널이 이미 만들어진 기기가 많아(진동 추가 전 테스트로) 코드로 나중에 진동을 켜도
// 반영이 안 됨(안드로이드 정책 — 채널은 한 번 만들어지면 앱이 재정의 못 함) — 새 채널 ID로 우회(2026-08-30).
private const val CHANNEL_ID = "group_nudge_v2"
private const val NOTIFICATION_ID_BASE = 30000
private val VIBRATE_PATTERN = longArrayOf(0, 250, 150, 250)

/**
 * "모임" 넛지("깨우기") 폴링 워커 — [com.phonelock.app.service.AccessibilityWatchdogWorker]와 같은
 * PeriodicWork 패턴(WorkManager 최소 주기 15분이라 실시간 알림은 아니다, 계획 문서에서 고지된 한계).
 * 로그인/Firebase 설정이 없으면 repository 쪽에서 조용히 빈 목록을 돌려주므로 여기선 별도 분기 불필요.
 */
class GroupNudgeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val repository = PhoneLockRepository(context)
        val prefs = AppPreferences(context)
        // 공부 중이면 이번 실행은 건너뛴다 — lastSeen을 안 갱신하므로 다음 실행(WorkManager 주기)이나
        // WalkieTalkieService의 더 빠른 폴링이 공부가 끝난 뒤 그대로 다시 알려준다.
        if (com.phonelock.app.service.StudyNotificationGate.isStudying(repository)) return Result.success()
        val nudges = repository.readIncomingSocialGroupNudges()
        if (nudges.isEmpty()) return Result.success()

        ensureChannel(context)
        nudges.forEachIndexed { index, nudge ->
            notify(
                context,
                NOTIFICATION_ID_BASE + index,
                "😴 ${nudge.fromName}님이 깨웠어요",
                "모임에서 오늘 진행 상황을 확인해보세요"
            )
            prefs.setNudgeLastSeen(nudge.groupId, nudge.sentAtMillis)
        }
        return Result.success()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                // enableVibration 기본값은 false라서 명시적으로 켜야 한다(RoutineReminderReceiver와 동일 원칙) —
                // 앱이 공식적으로 보내는 알림은 전부 진동이 오게 하라는 요청(2026-08-30).
                val channel = NotificationChannel(CHANNEL_ID, "모임 깨우기", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = VIBRATE_PATTERN
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, id, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
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
