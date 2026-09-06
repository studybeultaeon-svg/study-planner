package com.phonelock.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.ChatSyncClient
import com.phonelock.desktop.ui.theme.Spacing

/** 1:1 DM 채팅방(92차 소셜 개편 Phase 2, 안드로이드판과 대칭) — [ChatThreadScreen]에 dmChats 경로
 *  콜백만 연결(그룹 대화 [GroupChatScreen]과 UI는 완전히 같고 저장 경로만 다름). */
@Composable
fun DmChatScreen(repository: Repository, chatId: String, peerUid: String, peerLabel: String, onBack: () -> Unit) {
    val url = repository.fbDatabaseUrl
    val key = repository.fbApiKey
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< 목록") }
            Text(peerLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Spacing.sm))
        }
        ChatThreadScreen(
            myUid = AuthManager.currentUid,
            loadMessages = { ChatSyncClient.readDmMessages(url, key, chatId) },
            sendMessage = { text -> ChatSyncClient.sendDmMessage(url, key, chatId, peerUid, text) },
            toggleReaction = { msgId, emoji, alreadySet -> ChatSyncClient.toggleDmMessageReaction(url, key, chatId, msgId, emoji, alreadySet) }
        )
    }
}
