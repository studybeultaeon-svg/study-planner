package com.phonelock.desktop.routine

import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.SocialGroupSyncClient
import com.phonelock.desktop.monitor.TtsPlayer
import com.phonelock.desktop.monitor.VoicePlayer
import java.time.LocalDateTime
import java.util.Base64

/**
 * "무전기" 음성/텍스트 메시지 폴링 → 모임마다 다르게 설정할 수 있는 방식([SocialGroupSyncClient.GroupWalkieSettings])에
 * 따라 처리 — 이 모임에서 무전기가 켜져 있고 모드가 "FORCED"고 허용 일정 안이면 즉시 재생 후 삭제, 그 외엔
 * 알림만 띄우고 남겨서(사용자가 "모임" 탭 인박스에서 직접 재생) 지우지 않는다. [SocialGroupNotifier]와 같은
 * fire-and-forget 스레드 패턴 — 로그인 안 돼 있으면 아무 일도 하지 않는다(폴링 주기 자체는 호출부인
 * Main.kt에서 조정).
 */
object VoiceMessageNotifier {
    @Volatile
    private var running = false

    fun tick(repository: Repository) {
        if (running) return
        if (!AuthManager.isSignedIn) return
        val url = repository.fbDatabaseUrl
        val key = repository.fbApiKey
        if (url.isNullOrBlank() || key.isNullOrBlank()) return

        running = true
        Thread {
            try {
                val myUid = AuthManager.currentUid ?: return@Thread
                val groupIds = SocialGroupSyncClient.readMyGroupIds(url, key)
                if (groupIds.isEmpty()) return@Thread
                val messages = SocialGroupSyncClient.readIncomingVoiceMessages(url, key, groupIds, myUid)
                if (messages.isEmpty()) return@Thread
                // 모임별로 설정이 다르므로, 이번 폴링에 등장한 모임들만 골라 한 번씩만 조회해둔다.
                val settingsByGroup = messages.map { it.groupId }.distinct().associateWith { groupId ->
                    SocialGroupSyncClient.readGroupWalkieSettings(url, key, groupId)
                }
                messages.forEach { msg ->
                    val settings = settingsByGroup[msg.groupId] ?: SocialGroupSyncClient.GroupWalkieSettings()
                    if (!settings.enabled) return@forEach // 이 모임에서 무전기 자체를 안 켜뒀으면 건드리지 않고 남겨둔다.
                    val withinAllowedTime = isWithinAllowedTime(settings)
                    if (withinAllowedTime && settings.mode == "FORCED") {
                        // 삭제를 먼저 확인하고 재생한다 — 삭제가 서버에서 실패하면(네트워크 등) 이번엔 재생을
                        // 건너뛰고 다음 폴링에서 다시 시도한다. 순서가 반대(재생 먼저)였을 때는 삭제가 계속
                        // 실패하는 동안 같은 메시지가 폴링마다 계속 재생되는 버그가 있었다.
                        if (SocialGroupSyncClient.deleteVoiceMessage(url, key, msg.groupId, msg.msgId).isSuccess) {
                            if (msg.textMessage.isNotBlank()) {
                                DesktopNotifier.notify("${msg.fromName}님의 무전 🎙️", "읽어주는 중...")
                                TtsPlayer.speak(msg.textMessage, settings.volume, settings.voiceGender)
                            } else {
                                val wavBytes = runCatching { Base64.getDecoder().decode(msg.audioBase64) }.getOrNull()
                                if (wavBytes != null) {
                                    DesktopNotifier.notify("${msg.fromName}님의 무전 🎙️", "재생 중...")
                                    VoicePlayer.play(wavBytes, settings.volume)
                                }
                            }
                        }
                    } else {
                        // MESSAGE_ONLY 모드거나 허용 시간대 밖 — 지우지 않고 남겨서 "모임" 탭 인박스에서
                        // 사용자가 직접 재생(그때 삭제)하게 한다.
                        DesktopNotifier.notify("🎙️ ${msg.fromName}님의 음성메시지", "\"모임\" 탭에서 확인하세요")
                    }
                }
            } finally {
                running = false
            }
        }.start()
    }

    private fun isWithinAllowedTime(settings: SocialGroupSyncClient.GroupWalkieSettings): Boolean {
        if (settings.schedules.isEmpty()) return true // 일정을 하나도 안 뒀으면 항상 허용.
        val now = LocalDateTime.now()
        val nowMinute = now.hour * 60 + now.minute
        val dayBit = 1 shl (now.dayOfWeek.value - 1) // 월=0 ... 일=6, AppGroup daysMask와 동일 규칙.
        return settings.schedules.any { s ->
            if (s.daysMask and dayBit == 0) return@any false
            if (s.startMinute <= s.endMinute) nowMinute in s.startMinute until s.endMinute
            else nowMinute >= s.startMinute || nowMinute < s.endMinute
        }
    }
}
