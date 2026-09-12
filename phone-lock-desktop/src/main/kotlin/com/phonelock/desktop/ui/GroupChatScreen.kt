package com.phonelock.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.ChatSyncClient
import com.phonelock.desktop.monitor.SocialGroupSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 모임 "💬 대화" 채널(92차 소셜 개편 Phase 1, 안드로이드판과 대칭) — [ChatThreadScreen]에 groupChats
 *  경로 콜백만 연결. */
@Composable
fun GroupChatScreen(repository: Repository, groupId: String) {
    val url = repository.fbDatabaseUrl
    val key = repository.fbApiKey
    // 122차(사용자 요청): 발신자 이름 옆 레벨/칭호 배지 — 이 모임의 멤버 통계에서 sharePlant 켠 사람만 뽑는다.
    var senderBadges by remember { mutableStateOf<Map<String, SocialGroupSyncClient.PlantBadge>>(emptyMap()) }
    LaunchedEffect(groupId) {
        senderBadges = withContext(Dispatchers.IO) {
            SocialGroupSyncClient.readGroupStats(url, key, groupId)
                .filter { it.sharePlant && it.plantTitle.isNotBlank() }
                .associate { it.uid to SocialGroupSyncClient.PlantBadge(it.plantLevel, it.plantTitle) }
        }
    }
    ChatThreadScreen(
        chatId = groupId,
        myUid = AuthManager.currentUid,
        loadMessages = { ChatSyncClient.readGroupMessages(url, key, groupId) },
        sendMessage = { text -> ChatSyncClient.sendGroupMessage(url, key, groupId, text) },
        toggleReaction = { msgId, emoji, alreadySet -> ChatSyncClient.toggleGroupMessageReaction(url, key, groupId, msgId, emoji, alreadySet) },
        senderBadges = senderBadges
    )
}
