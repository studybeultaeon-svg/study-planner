package com.phonelock.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.readDmChatMessages
import com.phonelock.app.data.sendDmChatMessage
import com.phonelock.app.data.toggleDmChatReaction
import com.phonelock.app.service.AuthManager

/** 1:1 DM 채팅방(92차 소셜 개편 Phase 2) — [ChatThreadScreen]에 dmChats 경로 콜백만 연결
 *  (그룹 대화 [GroupChatScreen]과 UI는 완전히 같고 저장 경로만 다름). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmChatScreen(repository: PhoneLockRepository, chatId: String, peerUid: String, peerLabel: String, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(peerLabel) },
            navigationIcon = { IconButton(onClick = onBack) { Text("<") } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ChatThreadScreen(
                chatId = chatId,
                myUid = AuthManager.currentUser?.uid,
                loadMessages = { repository.readDmChatMessages(chatId) },
                sendMessage = { text -> repository.sendDmChatMessage(chatId, peerUid, text) },
                toggleReaction = { msgId, emoji, alreadySet -> repository.toggleDmChatReaction(chatId, msgId, emoji, alreadySet) }
            )
        }
    }
}
