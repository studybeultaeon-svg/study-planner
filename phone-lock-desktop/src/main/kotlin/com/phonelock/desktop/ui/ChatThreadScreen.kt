package com.phonelock.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.monitor.ChatSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
private const val POLL_INTERVAL_MS = 4_000L

/**
 * "💬 대화" 채널의 공용 메시지 스레드 UI(92차 소셜 개편 Phase 1=모임 대화, Phase 2=1:1 DM이 함께 씀,
 * 안드로이드판 ChatThreadScreen.kt와 대칭) — 텍스트 + 이모지 리액션만. 실시간성은 "이 화면이 켜져있는
 * 동안만"으로 확정돼 [POLL_INTERVAL_MS] 주기로 폴링한다(CalendarScreen.DayDetailSection과 같은 패턴).
 * 그룹 대화/DM은 저장 경로만 다르고 UI는 완전히 같아 콜백으로 차이를 흡수한다.
 */
@Composable
fun ChatThreadScreen(
    myUid: String?,
    loadMessages: suspend () -> List<ChatSyncClient.ChatMessage>,
    sendMessage: suspend (String) -> Result<Unit>,
    toggleReaction: suspend (msgId: String, emoji: String, alreadySet: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<ChatSyncClient.ChatMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var openReactionsFor by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    // 98차: sendMessage 실패(Result.failure)를 그동안 아무도 확인하지 않고 버려서 "쳐서 올려도
    // 안 올라간다"는 제보가 원인 불명으로 남아있었다(안드로이드판과 대칭) — 실패 사유를 화면에 보여준다.
    var sendError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            val latest = withContext(Dispatchers.IO) { loadMessages() }
            if (latest != messages) {
                val wasAtBottom = listState.firstVisibleItemIndex >= (messages.size - 2).coerceAtLeast(0)
                messages = latest
                if (wasAtBottom && messages.isNotEmpty()) {
                    scope.launch { listState.scrollToItem(messages.size - 1) }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    fun sendCurrentInput() {
        val text = input.trim()
        if (text.isBlank() || sending) return
        input = ""
        sending = true
        sendError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { sendMessage(text) }
            result.onFailure {
                sendError = it.message ?: "메시지 전송에 실패했습니다."
                input = text
            }
            messages = withContext(Dispatchers.IO) { loadMessages() }
            if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
            sending = false
        }
    }

    Column(Modifier.fillMaxSize().background(socialGradientBackground())) {
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "아직 대화가 없습니다. 첫 메시지를 보내보세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.msgId }) { msg ->
                    val mine = msg.senderUid == myUid
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
                    ) {
                        if (!mine) {
                            Text(
                                msg.senderName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .widthIn(max = 360.dp)
                                .clickable { openReactionsFor = if (openReactionsFor == msg.msgId) null else msg.msgId },
                            shape = RoundedCornerShape(14.dp),
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                msg.text,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (msg.reactions.isNotEmpty()) {
                            val counts = msg.reactions.values.groupingBy { it }.eachCount()
                            Text(
                                counts.entries.joinToString("  ") { (emoji, count) -> if (count > 1) "$emoji $count" else emoji },
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp)
                            )
                        }
                        if (openReactionsFor == msg.msgId) {
                            Row(
                                Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                QUICK_REACTIONS.forEach { emoji ->
                                    val alreadySet = msg.reactions[myUid] == emoji
                                    Text(
                                        emoji,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier
                                            .background(
                                                if (alreadySet) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                openReactionsFor = null
                                                scope.launch {
                                                    withContext(Dispatchers.IO) { toggleReaction(msg.msgId, emoji, alreadySet) }
                                                    messages = withContext(Dispatchers.IO) { loadMessages() }
                                                }
                                            }
                                            .padding(4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        sendError?.let {
            Text(
                "전송 실패: $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("메시지 보내기") },
                singleLine = true,
                // 96차 버그 수정(안드로이드판과 대칭): 채팅을 치고 엔터를 눌러도 메시지가 올라가지
                // 않던 버그 — 필드에 키보드 전송 액션 자체가 연결돼 있지 않아서, 엔터를 눌러도
                // singleLine이라 줄바꿈도 안 되고 아무 일도 안 일어났다. imeAction=Send + onSend로
                // 전송 버튼(➤)과 동일한 sendCurrentInput()을 호출하도록 연결한다.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { sendCurrentInput() })
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { sendCurrentInput() }, enabled = input.isNotBlank() && !sending) {
                Text("➤")
            }
        }
    }
}
