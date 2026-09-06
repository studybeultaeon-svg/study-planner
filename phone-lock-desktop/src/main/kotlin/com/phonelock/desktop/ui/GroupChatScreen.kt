package com.phonelock.desktop.ui

import androidx.compose.runtime.Composable
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.ChatSyncClient

/** 모임 "💬 대화" 채널(92차 소셜 개편 Phase 1, 안드로이드판과 대칭) — [ChatThreadScreen]에 groupChats
 *  경로 콜백만 연결. */
@Composable
fun GroupChatScreen(repository: Repository, groupId: String) {
    val url = repository.fbDatabaseUrl
    val key = repository.fbApiKey
    ChatThreadScreen(
        myUid = AuthManager.currentUid,
        loadMessages = { ChatSyncClient.readGroupMessages(url, key, groupId) },
        sendMessage = { text -> ChatSyncClient.sendGroupMessage(url, key, groupId, text) },
        toggleReaction = { msgId, emoji, alreadySet -> ChatSyncClient.toggleGroupMessageReaction(url, key, groupId, msgId, emoji, alreadySet) }
    )
}
