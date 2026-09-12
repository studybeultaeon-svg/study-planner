package com.phonelock.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.readGroupChatMessages
import com.phonelock.app.data.readSocialGroupStats
import com.phonelock.app.data.sendGroupChatMessage
import com.phonelock.app.data.toggleGroupChatReaction
import com.phonelock.app.service.AuthManager
import com.phonelock.app.service.SocialGroupSyncClient

/** 모임 "💬 대화" 채널(92차 소셜 개편 Phase 1) — [ChatThreadScreen]에 groupChats 경로 콜백만 연결. */
@Composable
fun GroupChatScreen(repository: PhoneLockRepository, groupId: String) {
    // 122차(사용자 요청): 발신자 이름 옆 레벨/칭호 배지 — 이 모임의 멤버 통계에서 sharePlant 켠 사람만 뽑는다.
    var senderBadges by remember { mutableStateOf<Map<String, SocialGroupSyncClient.PlantBadge>>(emptyMap()) }
    LaunchedEffect(groupId) {
        senderBadges = repository.readSocialGroupStats(groupId)
            .filter { it.sharePlant && !it.plantTitle.isNullOrBlank() }
            .associate { it.uid to SocialGroupSyncClient.PlantBadge(it.plantLevel ?: 1, it.plantTitle!!) }
    }
    ChatThreadScreen(
        chatId = groupId,
        myUid = AuthManager.currentUser?.uid,
        loadMessages = { repository.readGroupChatMessages(groupId) },
        sendMessage = { text -> repository.sendGroupChatMessage(groupId, text) },
        toggleReaction = { msgId, emoji, alreadySet -> repository.toggleGroupChatReaction(groupId, msgId, emoji, alreadySet) },
        senderBadges = senderBadges
    )
}
