package com.phonelock.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.service.media.MediaBrowserService
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat

/**
 * 차단(잠김/실행확인)·공부 잠금 화면에서 백그라운드로 재생 중인 음악 앱(Spotify 등)을 그 앱 화면을
 * 열지 않고 제어한다(125차, 사용자 요청). 두 경로가 있다.
 *
 * - "알림 접근"이 허용돼 있으면 [MediaSessionManager]로 대상 앱의 미디어 세션을 직접 찾아 그 앱에만
 *   명령을 보낸다. 곡 제목/아티스트/재생 상태도 이 경로에서만 알 수 있다.
 * - 허용 안 돼 있으면 [AudioManager.dispatchMediaKeyEvent]로 시스템 미디어 키를 보낸다. 안드로이드가
 *   "현재 미디어 앱"으로 고른 앱(보통 지금 재생 중이거나 마지막으로 재생한 앱)이 받으므로 대상 앱을
 *   지정할 수 없고, 멈춰 있을 때 "재생"을 누르면 다른 앱이 켜질 수도 있다 — 화면에서 이 한계를 안내한다.
 *
 * 어느 쪽이든 명령은 대상 앱 프로세스의 미디어 세션(또는 미디어 버튼 수신기)으로 바로 전달되므로
 * 그 앱이 포그라운드일 필요가 없다.
 */
object MediaControlClient {
    data class Session(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val artist: String,
        val isPlaying: Boolean
    )

    enum class Action { PREVIOUS, PLAY_PAUSE, NEXT }

    /** 알림 접근([MediaListenerService])이 켜져 있는지 — 켜져 있어야 [activeSessions]가 결과를 준다. */
    fun hasSessionAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** 지금 활성화된 미디어 세션들(시스템 우선순위 순, 재생 중인 앱이 앞). 알림 접근이 없으면 빈 목록. */
    fun activeSessions(context: Context): List<Session> =
        controllers(context).map { c ->
            val metadata = c.metadata
            Session(
                packageName = c.packageName,
                appLabel = appLabel(context, c.packageName),
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
                isPlaying = c.isPlaying()
            )
        }

    /**
     * 알림 접근 없이도 판단할 수 있는 "음악 앱인가" — 미디어 버튼 수신기나 MediaBrowserService를
     * 선언한 앱(Spotify/유튜브 뮤직 등 백그라운드 재생 앱은 둘 중 하나 이상을 갖는다).
     */
    @Suppress("DEPRECATION")
    fun isMediaApp(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        return runCatching {
            pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageName), 0).isNotEmpty() ||
                pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName), 0).isNotEmpty()
        }.getOrDefault(false)
    }

    /** 이 기기에서 뭔가(앱 무관) 음악을 재생 중인지 — 알림 접근이 없을 때의 대략적인 상태 표시용. */
    fun isAnyMusicPlaying(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java)?.isMusicActive == true

    /**
     * [packageName]의 세션을 찾을 수 있으면(알림 접근 필요) 그 앱에만 명령을 보내고 true를 반환한다.
     * 못 찾으면 시스템 미디어 키로 보내고 false를 반환한다.
     */
    fun send(context: Context, packageName: String?, action: Action): Boolean {
        val controller = packageName?.let { pkg -> controllers(context).firstOrNull { it.packageName == pkg } }
        if (controller != null) {
            val controls = controller.transportControls
            when (action) {
                Action.PREVIOUS -> controls.skipToPrevious()
                Action.NEXT -> controls.skipToNext()
                Action.PLAY_PAUSE -> if (controller.isPlaying()) controls.pause() else controls.play()
            }
            return true
        }
        val keyCode = when (action) {
            Action.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            Action.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            Action.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        context.getSystemService(AudioManager::class.java)?.let { am ->
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        return false
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

    private val labelCache = mutableMapOf<String, String>()

    private fun appLabel(context: Context, packageName: String): String = synchronized(labelCache) {
        labelCache.getOrPut(packageName) {
            val pm = context.packageManager
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
                .getOrDefault(packageName)
        }
    }
}

/**
 * 알림 접근 권한을 받기 위한 빈 리스너. [MediaSessionManager.getActiveSessions]는 호출자가 "사용자가 켠
 * 알림 리스너"를 가진 앱일 때만 다른 앱의 미디어 세션을 돌려주기 때문에 필요하다 — 알림 내용 자체는
 * 읽지도 저장하지도 않는다(콜백을 하나도 재정의하지 않음).
 */
class MediaListenerService : NotificationListenerService()
