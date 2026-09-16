package com.phonelock.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.findSocialMemberPlantBadge
import com.phonelock.app.data.readDmChatMessages
import com.phonelock.app.data.sendDmChatMessage
import com.phonelock.app.data.toggleDmChatReaction
import com.phonelock.app.service.AuthManager
import com.phonelock.app.service.SocialGroupSyncClient

/** 1:1 DM 채팅방(92차 소셜 개편 Phase 2) — [ChatThreadScreen]에 dmChats 경로 콜백만 연결
 *  (그룹 대화 [GroupChatScreen]과 UI는 완전히 같고 저장 경로만 다름). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmChatScreen(repository: PhoneLockRepository, chatId: String, peerUid: String, peerLabel: String, onBack: () -> Unit) {
    // 122차(사용자 요청): 상대 이름 옆 레벨/칭호 배지 — DM은 그룹 소속이 보장되지 않아 내가 속한 모임들을
    // 뒤져서 상대가 sharePlant 켠 곳을 찾는다([SocialGroupSyncClient.findMemberPlantBadge]).
    var peerBadge by remember { mutableStateOf<SocialGroupSyncClient.PlantBadge?>(null) }
    LaunchedEffect(peerUid) { peerBadge = repository.findSocialMemberPlantBadge(peerUid) }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                MemberDisplayName(
                    title = peerBadge?.title,
                    name = peerLabel,
                    level = peerBadge?.level,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1
                )
            },
            navigationIcon = { IconButton(onClick = onBack) { Text("<") } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ChatThreadScreen(
                chatId = chatId,
                myUid = AuthManager.currentUser?.uid,
                loadMessages = { repository.readDmChatMessages(chatId) },
                sendMessage = { text -> repository.sendDmChatMessage(chatId, peerUid, text) },
                toggleReaction = { msgId, emoji, alreadySet -> repository.toggleDmChatReaction(chatId, msgId, emoji, alreadySet) },
                senderBadges = peerBadge?.let { mapOf(peerUid to it) } ?: emptyMap()
            )
        }
    }
}
