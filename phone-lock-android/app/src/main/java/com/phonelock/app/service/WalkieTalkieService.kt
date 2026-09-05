package com.phonelock.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.phonelock.app.R
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.*
import com.phonelock.app.ui.MainActivity
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SERVICE_CHANNEL_ID = "walkie_service"
private const val SERVICE_NOTIFICATION_ID = 40000
// v2: 진동 없이 기존 채널이 이미 만들어진 기기가 많아(진동 추가 전 테스트로) 코드로 나중에 진동을 켜도
// 반영이 안 됨(안드로이드 정책 — 채널은 한 번 만들어지면 앱이 재정의 못 함) — 새 채널 ID로 우회(2026-08-30).
private const val MESSAGE_CHANNEL_ID = "walkie_message_v2"
private const val MESSAGE_NOTIFICATION_ID_BASE = 41000
private const val POLL_INTERVAL_MS = 7_000L
private val MESSAGE_VIBRATE_PATTERN = longArrayOf(0, 300, 200, 300)

/**
 * "무전기" 수신 포그라운드 서비스 — 모임마다 받는 방식(켜짐/모드/볼륨/요일·시간대 일정)이 다를 수 있어
 * ([SocialGroupSyncClient.GroupWalkieSettings]) 로그인만 돼 있으면 항상 실행하고, 매 폴링마다 내가 속한
 * 모임별로 그 모임의 설정을 따로 조회해서 처리한다. 기존 [AppMonitorAccessibilityService](접근성 켜짐에만
 * 동작)나 [com.phonelock.app.routine.GroupNudgeWorker](WorkManager 15분 하한)에 얹지 않고 독립 서비스로
 * 둔 이유는 계획 문서(peppy-toasting-floyd.md) 참고 — 접근성이 꺼져 있어도, 15분보다 훨씬 빠르게
 * 폴링해야 하기 때문이다.
 *
 * "강제 재생" 배너는 별도 오버레이 창 대신 일반 알림으로 띄운다 — `TYPE_ACCESSIBILITY_OVERLAY`는
 * 접근성 서비스 전용이라 이 서비스에서는 쓸 수 없고, `SYSTEM_ALERT_WINDOW`는 이 앱이 요구한 적 없는
 * 별도 권한이라 새로 추가하지 않았다.
 */
class WalkieTalkieService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val started = runCatching {
            ensureChannels()
            startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification())
        }
        if (started.isFailure) {
            // startForeground()가 실패(권한/OS 제약 등)하면 이 서비스는 무의미하니 그냥 조용히 멈춘다 —
            // 어차피 이 서비스가 죽어도 나머지 앱 기능(그룹 차단 등)에는 전혀 지장이 없다.
            stopSelf()
            return
        }
        pollJob = serviceScope.launch { pollLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        pollJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun pollLoop() {
        val repository = PhoneLockRepository(applicationContext)
        val prefs = AppPreferences(applicationContext)
        while (true) {
            runCatching { pollOnce(repository) }
            runCatching { pollNudges(repository, prefs) }
            delay(POLL_INTERVAL_MS)
        }
    }

    /** "그냥 깨우기"(알림만) 도착 확인 — 원래 [com.phonelock.app.routine.GroupNudgeWorker]가 WorkManager
     *  최소 주기(15분)로만 처리해서 무전기(음성/텍스트, 7초 주기)에 비해 너무 늦게 왔다. 이 서비스가 이미
     *  로그인한 모두에게 항상 짧은 주기로 도는 만큼, 같은 루프에서 넛지도 함께 확인해 근접 실시간으로
     *  알려준다(GroupNudgeWorker는 이 서비스가 죽어있는 드문 경우를 위한 보완용으로 그대로 둔다). */
    private suspend fun pollNudges(repository: PhoneLockRepository, prefs: AppPreferences) {
        // 공부 중이면 이번 폴링은 통째로 건너뛴다 — 넛지는 서버에서 안 지워지고 lastSeen도 안 갱신되므로
        // (StudyNotificationGate 참고), 공부가 끝난 뒤 다음 폴링(7초 후)이 그대로 다시 알려준다.
        if (StudyNotificationGate.isStudying(repository)) return
        val myUid = com.phonelock.app.service.AuthManager.currentUser?.uid ?: return
        val nudges = repository.readIncomingSocialGroupNudges()
        if (nudges.isEmpty()) return
        val lastSeenByGroup = prefs.nudgeLastSeenByGroup()
        nudges.forEach { nudge ->
            if (nudge.fromUid == myUid) return@forEach
            val lastSeen = lastSeenByGroup[nudge.groupId] ?: 0L
            if (nudge.sentAtMillis > lastSeen) {
                notifyBanner("😴 ${nudge.fromName}님이 깨웠어요", "모임에서 오늘 진행 상황을 확인해보세요")
                prefs.setNudgeLastSeen(nudge.groupId, nudge.sentAtMillis)
            }
        }
    }

    private suspend fun pollOnce(repository: PhoneLockRepository) {
        // 공부 중이면 통째로 건너뛴다 — 메시지는 삭제하지 않는 한 서버에 그대로 남아있으므로
        // (FORCED 모드도 재생 전 삭제라 이 시점에 건드리지 않으면 그대로 보존됨), 공부가 끝난 뒤
        // 다음 폴링(7초 후)이 방금 도착한 메시지처럼 자연스럽게 재생/알림 처리한다.
        if (StudyNotificationGate.isStudying(repository)) return
        val messages = repository.readIncomingVoiceMessages()
        if (messages.isEmpty()) return
        // 모임별로 설정이 다르므로, 이번 폴링에 등장한 모임들만 골라 한 번씩만 조회해둔다(같은 모임에서
        // 온 메시지가 여러 개여도 설정 조회는 한 번).
        val settingsByGroup = messages.map { it.groupId }.distinct().associateWith { groupId ->
            repository.readGroupWalkieSettings(groupId)
        }
        messages.forEach { msg ->
            val settings = settingsByGroup[msg.groupId] ?: SocialGroupSyncClient.GroupWalkieSettings()
            if (!settings.enabled) return@forEach // 이 모임에서 무전기 자체를 안 켜뒀으면 건드리지 않고 남겨둔다.
            val withinAllowedTime = isWithinAllowedTime(settings)
            if (withinAllowedTime && settings.mode == "FORCED") {
                // 삭제를 먼저 확인하고 재생한다 — 삭제가 서버에서 실패하면(네트워크 등) 이번엔 재생을
                // 건너뛰고 다음 폴링(7초 후)에서 다시 시도한다. 순서가 반대(재생 먼저)였을 때는 삭제가
                // 계속 실패하는 동안 같은 메시지가 폴링마다 계속 재생되는 버그가 있었다.
                if (repository.deleteVoiceMessage(msg.groupId, msg.msgId).isSuccess) {
                    if (msg.textMessage.isNotBlank()) {
                        notifyBanner("${msg.fromName}님의 무전 🎙️", "읽어주는 중...")
                        TtsPlayer.speak(applicationContext, msg.textMessage, settings.volume, settings.voiceGender)
                    } else {
                        val wavBytes = runCatching { Base64.decode(msg.audioBase64, Base64.NO_WRAP) }.getOrNull()
                        if (wavBytes != null) {
                            notifyBanner("${msg.fromName}님의 무전 🎙️", "재생 중...")
                            VoicePlayer.play(applicationContext, wavBytes, settings.volume)
                        }
                    }
                }
            } else {
                // MESSAGE_ONLY 모드거나 허용 시간대 밖 — 여기선 지우지 않고 남겨서 "모임" 탭 인박스에서
                // 사용자가 직접 재생(그때 삭제)하게 한다.
                notifyBanner("🎙️ ${msg.fromName}님의 음성메시지", "\"모임\" 탭에서 확인하세요")
            }
        }
    }

    private fun isWithinAllowedTime(settings: SocialGroupSyncClient.GroupWalkieSettings): Boolean {
        if (settings.schedules.isEmpty()) return true // 일정을 하나도 안 뒀으면 항상 허용.
        val now = LocalDateTime.now()
        val nowMinute = now.hour * 60 + now.minute
        val dayBit = 1 shl ((now.dayOfWeek.value - 1)) // 월=0 ... 일=6, AppGroup daysMask와 동일 규칙.
        return settings.schedules.any { s ->
            if (s.daysMask and dayBit == 0) return@any false
            if (s.startMinute <= s.endMinute) nowMinute in s.startMinute until s.endMinute
            else nowMinute >= s.startMinute || nowMinute < s.endMinute
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(SERVICE_CHANNEL_ID, "무전기 대기", NotificationManager.IMPORTANCE_MIN)
                )
            }
            if (manager.getNotificationChannel(MESSAGE_CHANNEL_ID) == null) {
                // enableVibration 기본값은 false라서 명시적으로 켜야 한다 — 안 켜면 "메시지로 받기" 모드에서
                // 알림만 조용히 뜨고 진동은 안 오는 문제가 있었다(RoutineReminderReceiver와 동일 패턴).
                val channel = NotificationChannel(MESSAGE_CHANNEL_ID, "무전기 메시지", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = MESSAGE_VIBRATE_PATTERN
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildServiceNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("무전기 대기 중")
            .setContentText("모임 멤버의 음성메시지를 받을 수 있습니다")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun notifyBanner(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val requestCode = (title + text).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this, requestCode, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Android 8 미만은 채널이 아니라 이 값을 직접 본다 — 채널 설정과 중복이어도 안전.
            .setVibrate(MESSAGE_VIBRATE_PATTERN)
            .build()
        manager.notify(MESSAGE_NOTIFICATION_ID_BASE + (requestCode % 1000).let { if (it < 0) it + 1000 else it }, notification)
    }

    companion object {
        /** 예전엔 "무전기 수신 허용"을 켠 일부 사용자에게만 호출됐지만, 이제 로그인한 모두에게 매 실행마다
         *  호출된다(모임별 설정으로 옮기면서 전역 껐다/켰다 스위치가 없어졌기 때문). 안드로이드 12+는
         *  앱이 백그라운드 시작 제한 상태일 때 `startForegroundService()`가 즉시
         *  `ForegroundServiceStartNotAllowedException`을 던질 수 있는데, 이전엔 이 경로를 타는 사용자가
         *  적어서 드러나지 않았을 수 있다 — 호출부(`MainActivity.onCreate`/`RoutineReminderReceiver`
         *  BOOT_COMPLETED)에서 앱이 죽지 않도록 여기서 흡수한다.
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, WalkieTalkieService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WalkieTalkieService::class.java))
        }
    }
}
