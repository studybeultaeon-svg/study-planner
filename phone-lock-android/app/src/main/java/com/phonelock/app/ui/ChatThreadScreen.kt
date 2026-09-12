package com.phonelock.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.DisposableEffect
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
import com.phonelock.app.service.ChatSyncClient
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
private const val POLL_INTERVAL_MS = 4_000L

/** [WalkieTalkieService]의 새 메시지 알림 폴링이, 지금 사용자가 보고 있는 대화방과 같은 대화방이면
 *  중복 알림을 건너뛰기 위해 참조하는 전역 상태(2026-09-10, 채팅 알림 신규) — [ChatThreadScreen]이
 *  화면에 떠 있는 동안만 자신의 chatId를 채워두고, 사라지면 비운다. */
object ActiveChatTracker {
    @Volatile
    var openChatId: String? = null
}

/**
 * 1:1 DM(92차 소셜 개편 Phase 2)의 공용 메시지 스레드 UI — 텍스트 + 이모지 리액션만(사용자 확정 범위).
 * 실시간성은 "이 화면이 켜져있는 동안만"으로 확정돼 화면이 보이는 동안 [POLL_INTERVAL_MS] 주기로
 * 폴링한다(무전기 7초 폴링과 같은 스타일, 새 SDK/FCM 없음). 저장 경로(`dmChats`)는 [loadMessages]/
 * [sendMessage]/[toggleReaction] 콜백으로 주입한다. [chatId]는 [ActiveChatTracker] 갱신용(새 메시지
 * 알림이 지금 보고 있는 방이면 중복 알림을 건너뛰기 위함).
 */
@Composable
fun ChatThreadScreen(
    chatId: String,
    myUid: String?,
    loadMessages: suspend () -> Result<List<ChatSyncClient.ChatMessage>>,
    sendMessage: suspend (String) -> Result<Unit>,
    toggleReaction: suspend (msgId: String, emoji: String, alreadySet: Boolean) -> Unit,
    /** 발신자 uid -> 레벨/칭호 배지(122차, 사용자 요청) — 값이 없는 발신자는 이름만 보여준다. */
    senderBadges: Map<String, com.phonelock.app.service.SocialGroupSyncClient.PlantBadge> = emptyMap()
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<ChatSyncClient.ChatMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var openReactionsFor by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    // 98차: sendMessage 실패(Result.failure)를 그동안 아무도 확인하지 않고 버려서 "쳐서 올려도
    // 안 올라간다"는 제보가 원인 불명으로 남아있었다 — 실패 사유를 화면에 그대로 보여준다.
    var sendError by remember { mutableStateOf<String?>(null) }
    // 98차 후속: sendMessage는 항상 성공(Result.success)하는데도 목록엔 안 뜬다는 재현이 나와서,
    // loadMessages 쪽 실패도 똑같이 드러낸다 — 이전엔 실패해도 빈 목록으로 조용히 덮어썼다.
    var loadError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    DisposableEffect(chatId) {
        ActiveChatTracker.openChatId = chatId
        onDispose { if (ActiveChatTracker.openChatId == chatId) ActiveChatTracker.openChatId = null }
    }

    fun applyLoadResult(result: Result<List<ChatSyncClient.ChatMessage>>) {
        result.onSuccess { latest ->
            loadError = null
            if (latest != messages) {
                val wasAtBottom = listState.firstVisibleItemIndex >= (messages.size - 2).coerceAtLeast(0)
                messages = latest
                if (wasAtBottom && messages.isNotEmpty()) {
                    scope.launch { listState.scrollToItem(messages.size - 1) }
                }
            }
        }.onFailure {
            loadError = it.message ?: "메시지 목록을 불러오지 못했습니다."
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            applyLoadResult(loadMessages())
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
            val result = sendMessage(text)
            result.onFailure {
                sendError = it.message ?: "메시지 전송에 실패했습니다."
                input = text
            }
            applyLoadResult(loadMessages())
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
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(messages, key = { it.msgId }) { msg ->
                    val mine = msg.senderUid == myUid
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
                    ) {
                        if (!mine) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = Spacing.xs, bottom = 2.dp)
                            ) {
                                senderBadges[msg.senderUid]?.let { badge ->
                                    PlantLevelBadge(badge.level, badge.title)
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    msg.senderName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .clickable { openReactionsFor = if (openReactionsFor == msg.msgId) null else msg.msgId },
                            shape = RoundedCornerShape(14.dp),
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                msg.text,
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (msg.reactions.isNotEmpty()) {
                            val counts = msg.reactions.values.groupingBy { it }.eachCount()
                            Text(
                                counts.entries.joinToString("  ") { (emoji, count) -> if (count > 1) "$emoji $count" else emoji },
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = Spacing.xs, end = Spacing.xs, top = 2.dp)
                            )
                        }
                        if (openReactionsFor == msg.msgId) {
                            Row(
                                Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
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
                                                    toggleReaction(msg.msgId, emoji, alreadySet)
                                                    applyLoadResult(loadMessages())
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

        loadError?.let {
            Text(
                "목록 불러오기 실패: $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.sm)
            )
        }
        sendError?.let {
            Text(
                "전송 실패: $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.sm)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("메시지 보내기") },
                singleLine = true,
                // 96차 버그 수정: 채팅을 치고 엔터(모바일은 키보드의 "전송" 액션)를 눌러도 메시지가
                // 올라가지 않던 버그 — 필드에 키보드 전송 액션 자체가 연결돼 있지 않아서, 엔터를
                // 눌러도 singleLine이라 줄바꿈도 안 되고 아무 일도 안 일어났다. imeAction=Send +
                // onSend로 전송 버튼(➤)과 동일한 sendCurrentInput()을 호출하도록 연결한다.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { sendCurrentInput() })
            )
            Spacer(Modifier.width(Spacing.xs))
            IconButton(onClick = { sendCurrentInput() }, enabled = input.isNotBlank() && !sending) {
                Text("➤")
            }
        }
    }
}
