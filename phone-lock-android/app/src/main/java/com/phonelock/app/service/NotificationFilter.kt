package com.phonelock.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 알림 필터 + 묶음 요약(130차 4단계). 사용자가 고른 앱의 알림을 뜨는 즉시 지우고 따로 모아뒀다가,
 * 정해둔 시각에 "읽지 않은 알림 N건" 한 줄로 한 번에 알려준다.
 *
 * **새 리스너를 만들지 않고 이미 있는 [MediaListenerService]에 콜백만 더했다** — 알림 접근 권한은
 * 컴포넌트 단위라 리스너를 새로 만들면 사용자가 권한을 한 번 더 켜야 하고, 기존 미디어 세션 조회
 * (BackgroundMediaGuard)가 어느 컴포넌트에 묶여 있는지도 헷갈리게 된다.
 *
 * 안전장치: ① 기본값은 "아무 앱도 안 거름"(고른 앱이 없으면 아무 일도 하지 않는다) ② 통화/알람/진행 중
 * 알림과 기본 전화·문자 앱, 이 앱 자신은 사용자가 골랐더라도 **절대 거르지 않는다** ③ 거른 알림은
 * 되돌릴 수 없으므로(cancel한 알림은 복구 불가) 제목·본문을 그대로 저장해 요약에 싣는다.
 */
object NotificationFilter {

    /** 요약 알림 채널 — 조용히 알림창에만 쌓이도록 IMPORTANCE_LOW(헤드업/진동 없음). */
    const val CHANNEL_ID = "notification_digest_v1"
    private const val DIGEST_NOTIFICATION_ID = 45000

    /** 알림이 폭주해도 저장소가 무한정 커지지 않게 하는 상한 — 넘으면 오래된 것부터 버린다. */
    const val MAX_QUEUE = 100

    /** 알림 접근이 켜져 있고 거를 앱을 하나라도 골랐는지. */
    fun isActive(context: Context): Boolean =
        BackgroundMediaGuard.hasSessionAccess(context) && AppPreferences(context).notificationFilterPackages.isNotEmpty()

    /** [MediaListenerService.onNotificationPosted]에서 부른다(메인 스레드 — 무거운 일을 하지 않는다). */
    fun handlePosted(service: NotificationListenerService, sbn: StatusBarNotification) {
        val preferences = AppPreferences(service)
        if (sbn.packageName !in preferences.notificationFilterPackages) return
        val notification = sbn.notification ?: return
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isProtectedNotification(sbn.packageName, notification.category, isOngoing, protectedPackages(service))) return

        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        preferences.addFilteredNotification(
            AppPreferences.FilteredNotification(
                packageName = sbn.packageName,
                appLabel = appLabel(service, sbn.packageName),
                title = title,
                text = text,
                atMillis = System.currentTimeMillis()
            )
        )
        runCatching { service.cancelNotification(sbn.key) }
    }

    /** 요약 시각에 [com.phonelock.app.routine.RoutineReminderReceiver]가 부른다. */
    suspend fun postDigest(context: Context) {
        val preferences = AppPreferences(context)
        val items = preferences.filteredNotifications()
        if (items.isEmpty()) return
        ensureChannel(context)
        // 공부 중이면 StudyNotificationGate가 큐에 담아뒀다가 공부가 끝난 뒤 그대로 띄운다(내용은 보존됨).
        StudyNotificationGate.showOrQueue(
            context,
            PhoneLockRepository(context),
            DIGEST_NOTIFICATION_ID,
            CHANNEL_ID,
            "읽지 않은 알림 ${items.size}건",
            digestSummaryText(items.map { it.appLabel })
        )
        preferences.clearFilteredNotifications()
    }

    /** 사용자가 골랐더라도 거르지 않는 앱 — 이 앱 자신, 기본 문자 앱, 기본 전화 앱. */
    fun protectedPackages(context: Context): Set<String> = buildSet {
        add(context.packageName)
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let { add(it) }
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }
            .getOrNull()?.let { add(it) }
    }

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    }.getOrDefault(packageName)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "알림 묶음 요약", NotificationManager.IMPORTANCE_LOW).apply {
                    enableVibration(false)
                }
            )
        }
    }
}

// ---- 아래는 기기 API에 의존하지 않는 순수 로직(유닛테스트 대상, NotificationFilterTest) ----

/**
 * 거르면 안 되는 알림인지. 문자열 값은 `Notification.CATEGORY_CALL`/`CATEGORY_ALARM`과 같은 값을
 * 직접 적었다 — 이 함수를 기기 없이 테스트할 수 있게 하기 위함이다.
 *
 * 진행 중(ongoing) 알림을 빼는 이유: 포그라운드 서비스/음악 재생 같은 상태 표시라 지워도 곧 다시
 * 뜨고, 그 앱의 "지금 뭐가 돌고 있는지"를 감추면 오히려 상태를 알 수 없게 된다.
 */
fun isProtectedNotification(
    packageName: String,
    category: String?,
    isOngoing: Boolean,
    protectedPackages: Set<String>
): Boolean = isOngoing ||
    category == "call" ||
    category == "alarm" ||
    packageName in protectedPackages

/** "12:30, 18:30" 같은 입력을 시각 목록으로. 형식이 틀린 항목은 조용히 버린다(설정 입력칸이 자유 텍스트라서). */
fun parseDigestTimes(csv: String): List<LocalTime> = csv.split(",").mapNotNull { part ->
    val piece = part.trim()
    if (piece.isEmpty()) return@mapNotNull null
    val fields = piece.split(":")
    if (fields.size != 2) return@mapNotNull null
    val hour = fields[0].trim().toIntOrNull() ?: return@mapNotNull null
    val minute = fields[1].trim().toIntOrNull() ?: return@mapNotNull null
    if (hour !in 0..23 || minute !in 0..59) null else LocalTime.of(hour, minute)
}.distinct().sorted()

/** 다음 요약 시각 — 오늘 남은 시각 중 가장 이른 것, 없으면 내일 첫 시각. 시각이 하나도 없으면 null. */
fun nextDigestTrigger(now: LocalDateTime, times: List<LocalTime>): LocalDateTime? {
    val sorted = times.sorted()
    if (sorted.isEmpty()) return null
    return sorted.map { LocalDateTime.of(now.toLocalDate(), it) }.firstOrNull { it.isAfter(now) }
        ?: LocalDateTime.of(now.toLocalDate().plusDays(1), sorted.first())
}

/** "카카오톡 3건 · 인스타그램 1건" 형태의 한 줄 요약(많으면 "외 N개 앱"으로 줄인다). */
fun digestSummaryText(appLabels: List<String>, maxApps: Int = 3): String {
    if (appLabels.isEmpty()) return ""
    val counts = appLabels.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    val shown = counts.take(maxApps).joinToString(" · ") { "${it.key} ${it.value}건" }
    val rest = counts.size - maxApps
    return if (rest > 0) "$shown · 외 ${rest}개 앱" else shown
}
