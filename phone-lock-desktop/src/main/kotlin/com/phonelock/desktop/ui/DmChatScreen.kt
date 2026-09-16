package com.phonelock.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.ChatSyncClient
import com.phonelock.desktop.monitor.SocialGroupSyncClient
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 1:1 DM 채팅방(92차 소셜 개편 Phase 2, 안드로이드판과 대칭) — [ChatThreadScreen]에 dmChats 경로
 *  콜백만 연결(그룹 대화 [GroupChatScreen]과 UI는 완전히 같고 저장 경로만 다름). */
@Composable
fun DmChatScreen(repository: Repository, chatId: String, peerUid: String, peerLabel: String, onBack: () -> Unit) {
    val url = repository.fbDatabaseUrl
    val key = repository.fbApiKey
    // 122차(사용자 요청): 상대 이름 옆 레벨/칭호 배지 — DM은 그룹 소속이 보장되지 않아 내가 속한 모임들을
    // 뒤져서 상대가 sharePlant 켠 곳을 찾는다([SocialGroupSyncClient.findMemberPlantBadge]).
    var peerBadge by remember { mutableStateOf<SocialGroupSyncClient.PlantBadge?>(null) }
    LaunchedEffect(peerUid) {
        peerBadge = withContext(Dispatchers.IO) { SocialGroupSyncClient.findMemberPlantBadge(url, key, peerUid) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< 목록") }
            MemberDisplayName(
                title = peerBadge?.title,
                name = peerLabel,
                level = peerBadge?.level,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.padding(start = Spacing.sm)
            )
        }
        ChatThreadScreen(
            chatId = chatId,
            myUid = AuthManager.currentUid,
            loadMessages = { ChatSyncClient.readDmMessages(url, key, chatId) },
            sendMessage = { text -> ChatSyncClient.sendDmMessage(url, key, chatId, peerUid, text) },
            toggleReaction = { msgId, emoji, alreadySet -> ChatSyncClient.toggleDmMessageReaction(url, key, chatId, msgId, emoji, alreadySet) },
            senderBadges = peerBadge?.let { mapOf(peerUid to it) } ?: emptyMap()
        )
    }
}
