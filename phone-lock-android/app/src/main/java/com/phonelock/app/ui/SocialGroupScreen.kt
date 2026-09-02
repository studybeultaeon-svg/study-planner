package com.phonelock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.*
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.launch

private data class GroupSummary(val id: String, val name: String, val memberCount: Int, val avgTodayRate: Int)

/** 모임 이름 첫 글자를 원형 배지로(데스크탑판 GroupAvatar와 대칭). */
@Composable
private fun GroupAvatar(name: String) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * "모임" 탭 메인 화면 — 내가 속한 모임 목록(이름/멤버수/오늘 완료율 평균)과 만들기/참여하기 진입점.
 * 계획 문서(dynamic-shimmying-map.md) 참고 — groups/{id} 데이터는 로컬에 캐싱하지 않고 진입할 때마다
 * Firebase에서 직접 읽는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialGroupScreen(repository: PhoneLockRepository, onOpenGroup: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var summaries by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reload() {
        loading = true
        scope.launch {
            val ids = repository.readMySocialGroupIds()
            summaries = ids.mapNotNull { id ->
                val info = repository.readSocialGroupInfo(id) ?: return@mapNotNull null
                val members = repository.readSocialGroupMembers(id)
                val stats = repository.readSocialGroupStats(id)
                val rates = stats.filter { it.shareRoutines }.map { s ->
                    val total = s.routines?.size ?: 0
                    val done = s.routines?.count { it.doneToday } ?: 0
                    if (total > 0) done * 100 / total else 0
                }
                val avg = if (rates.isNotEmpty()) rates.sum() / rates.size else 0
                GroupSummary(id, info.name, members.size, avg)
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    if (showCreateDialog) {
        var nameText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("모임 만들기") },
            text = {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("모임 이름") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = nameText.trim()
                    if (name.isNotEmpty()) {
                        showCreateDialog = false
                        scope.launch {
                            val result = repository.createSocialGroup(name)
                            result.onFailure { e -> errorMessage = e.message ?: "모임 생성에 실패했습니다." }
                            result.onSuccess { reload() }
                        }
                    }
                }) { Text("만들기") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("취소") } }
        )
    }

    if (showJoinDialog) {
        var codeText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("모임 참여하기") },
            text = {
                OutlinedTextField(
                    value = codeText,
                    onValueChange = { codeText = it },
                    label = { Text("초대 코드 (6자리)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val code = codeText.trim()
                    if (code.isNotEmpty()) {
                        showJoinDialog = false
                        scope.launch {
                            val result = repository.joinSocialGroup(code)
                            result.onFailure { e -> errorMessage = e.message ?: "참여에 실패했습니다." }
                            result.onSuccess { reload() }
                        }
                    }
                }) { Text("참여") }
            },
            dismissButton = { TextButton(onClick = { showJoinDialog = false }) { Text("취소") } }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("👥 모임") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
            Text(
                "함께 갓생 사는 사람들과 서로 진행 상황을 확인해요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = { showCreateDialog = true }, modifier = Modifier.weight(1f)) { Text("+ 모임 만들기") }
                Button(onClick = { showJoinDialog = true }, modifier = Modifier.weight(1f)) { Text("참여하기") }
            }
            Spacer(Modifier.height(Spacing.md))
            errorMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(Spacing.sm))
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                summaries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🌱", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(Spacing.sm))
                        Text("아직 속한 모임이 없습니다", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "새로 만들거나 초대 코드로 참여해보세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(summaries) { s ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenGroup(s.id) },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(Modifier.fillMaxWidth().padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                                GroupAvatar(s.name)
                                Spacer(Modifier.width(Spacing.md))
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${s.memberCount}명", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(Spacing.xs))
                                    LinearProgressIndicator(
                                        progress = { s.avgTodayRate / 100f },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                    )
                                }
                                Spacer(Modifier.width(Spacing.md))
                                Text(
                                    "${s.avgTodayRate}%",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
