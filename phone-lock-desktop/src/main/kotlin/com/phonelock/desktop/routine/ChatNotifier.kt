package com.phonelock.desktop.routine

import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.ChatSyncClient
import com.phonelock.desktop.ui.ActiveChatTracker

/**
 * 채팅 알림 신규(2026-09-10, 사용자 요청 — "진동 없이") — 내 1:1 DM을 돌면서 마지막으로 확인한 시각
 * 이후 온 메시지가 있으면 트레이 알림([DesktopNotifier])으로 알린다. [SocialGroupNotifier]와 동일한
 * tick() 구조로 Main.kt의 7초 주기 루프에 얹는다. [ActiveChatTracker]에 지금 보고 있는 방이면 건너뛴다
 * (화면이 이미 폴링 중이라 중복 알림이 된다). 대화방 개수만큼 요청이 늘어나는 구조라 무전기/넛지처럼
 * 가벼운 "최신 메시지 1개만" 조회로 비용을 줄였다(안드로이드판과 대칭).
 * 데스크탑엔 진동 개념 자체가 없어(트레이 풍선 알림) 사용자 요청 "진동 없이"는 자동으로 충족된다.
 */
object ChatNotifier {
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

                val dmChats = ChatSyncClient.readMyDmChats(url, key)
                dmChats.forEach dmLoop@{ dm ->
                    if (dm.chatId == ActiveChatTracker.openChatId) return@dmLoop
                    val latest = ChatSyncClient.peekLatestDmMessage(url, key, dm.chatId) ?: return@dmLoop
                    if (latest.senderUid == myUid) return@dmLoop
                    val lastSeen = repository.chatLastSeenFor(dm.chatId)
                    if (latest.sentAtMillis > lastSeen) {
                        DesktopNotifier.notify(latest.senderName, latest.text)
                        repository.setChatLastSeen(dm.chatId, latest.sentAtMillis)
                    }
                }
            } finally {
                running = false
            }
        }.start()
    }
}
