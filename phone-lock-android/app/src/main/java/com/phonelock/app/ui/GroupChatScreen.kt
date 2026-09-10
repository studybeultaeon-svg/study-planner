package com.phonelock.app.ui

import androidx.compose.runtime.Composable
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.readGroupChatMessages
import com.phonelock.app.data.sendGroupChatMessage
import com.phonelock.app.data.toggleGroupChatReaction
import com.phonelock.app.service.AuthManager

/** 모임 "💬 대화" 채널(92차 소셜 개편 Phase 1) — [ChatThreadScreen]에 groupChats 경로 콜백만 연결. */
@Composable
fun GroupChatScreen(repository: PhoneLockRepository, groupId: String) {
    ChatThreadScreen(
        chatId = groupId,
        myUid = AuthManager.currentUser?.uid,
        loadMessages = { repository.readGroupChatMessages(groupId) },
        sendMessage = { text -> repository.sendGroupChatMessage(groupId, text) },
        toggleReaction = { msgId, emoji, alreadySet -> repository.toggleGroupChatReaction(groupId, msgId, emoji, alreadySet) }
    )
}
