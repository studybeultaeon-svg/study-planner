package com.phonelock.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * 잠긴 앱의 백그라운드 재생 차단(125차, 사용자 요청) — 화면을 막아도 Spotify 같은 앱은 뒤에서 계속 재생할 수
 * 있어서, 재생 중인 미디어 세션을 찾아 멈춘다. 어떤 앱을 멈출지는 [AppMonitorAccessibilityService]가 기존
 * 잠금 판정으로 정하고, 이 객체는 시스템 미디어 세션 조회/일시정지만 맡는다.
 *
 * 다른 앱의 미디어 세션은 사용자가 "알림 접근"을 켠 앱([MediaListenerService])만 조회할 수 있다(그 밖의 공개
 * API로는 어느 앱이 재생 중인지 알 수 없음). 권한이 없으면 이 기능은 동작하지 않고, 권한 설정 가이드/설정
 * 화면의 "권한 설정" 경고로 켜도록 안내한다.
 */
object BackgroundMediaGuard {
    /** 알림 접근이 켜져 있는지 — 꺼져 있으면 [playingPackages]는 항상 비어 있다. */
    fun hasSessionAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** 지금 재생 중(버퍼링 포함)인 미디어 세션의 패키지들. */
    fun playingPackages(context: Context): Set<String> =
        controllers(context).filter { it.isPlaying() }.map { it.packageName }.toSet()

    /** [packageName]의 재생 중인 세션을 모두 일시정지한다. 멈춘 세션이 하나라도 있으면 true. */
    fun pause(context: Context, packageName: String): Boolean {
        val targets = controllers(context).filter { it.packageName == packageName && it.isPlaying() }
        targets.forEach { runCatching { it.transportControls.pause() } }
        return targets.isNotEmpty()
    }

    /** 시스템 "알림 접근" 설정 화면을 연다(API 30+는 이 앱 항목 상세로 바로). */
    fun openAccessSettings(context: Context) {
        val component = ComponentName(context, MediaListenerService::class.java)
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else null
        val fallback = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        for (intent in listOfNotNull(detail, fallback)) {
            val launched = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (launched) return
        }
    }

    private fun controllers(context: Context): List<MediaController> {
        if (!hasSessionAccess(context)) return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        // 권한 확인 직후 사용자가 알림 접근을 꺼버리면 SecurityException이 날 수 있다.
        return runCatching {
            manager.getActiveSessions(ComponentName(context, MediaListenerService::class.java))
        }.getOrDefault(emptyList())
    }

    private fun MediaController.isPlaying(): Boolean = when (playbackState?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> true
        else -> false
    }
}

/**
 * 알림 접근 권한을 받기 위한 빈 리스너. [MediaSessionManager.getActiveSessions]는 호출자가 "사용자가 켠
 * 알림 리스너"를 가진 앱일 때만 다른 앱의 미디어 세션을 돌려주기 때문에 필요하다 — 알림 내용 자체는
 * 읽지도 저장하지도 않는다(콜백을 하나도 재정의하지 않음).
 */
class MediaListenerService : NotificationListenerService()
