package com.phonelock.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.ui.theme.Spacing

/**
 * 모임 하나에 무엇을 공유할지 — [GroupWalkieSettingsDialog]와 같은 이유로 전역(Settings)이 아니라
 * 모임마다 따로 설정한다(가족 모임엔 공부시간만, 스터디 모임엔 루틴까지 등). 항목은 62차의 루틴/공부/
 * 스트릭 3종에서 오늘 일정/공부중 여부까지 5종으로 확장했다. "현재 작동 중인 관리(차단) 그룹"은 77차에
 * 추가됐다가 81차에 완전히 제외됨 — 다른 사람에게 내가 뭘 차단 중인지까지 공유할 필요는 없다는 판단
 * (사용자 요청).
 */
@Composable
fun GroupShareSettingsDialog(
    initial: AppPreferences.GroupShareSettings,
    onDismiss: () -> Unit,
    onSave: (AppPreferences.GroupShareSettings) -> Unit
) {
    var shareRoutines by remember { mutableStateOf(initial.shareRoutines) }
    var shareStudy by remember { mutableStateOf(initial.shareStudy) }
    var shareStreak by remember { mutableStateOf(initial.shareStreak) }
    var shareSchedule by remember { mutableStateOf(initial.shareSchedule) }
    var shareStudyingNow by remember { mutableStateOf(initial.shareStudyingNow) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔒 공유 설정") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "이 모임에서 다른 멤버에게 보여줄 내 정보를 항목별로 켜고 끌 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                ShareToggleRow("루틴", "오늘 루틴 목록과 완료 여부", shareRoutines) { shareRoutines = it }
                ShareToggleRow("공부", "오늘 공부 시간·진행률", shareStudy) { shareStudy = it }
                ShareToggleRow("스트릭", null, shareStreak) { shareStreak = it }
                ShareToggleRow("오늘 일정", "오늘 캘린더 일정 목록과 완료 여부", shareSchedule) { shareSchedule = it }
                ShareToggleRow("공부중 여부", "지금 공부(뽀모도로 포함) 중인지와 업무 이름", shareStudyingNow) { shareStudyingNow = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    AppPreferences.GroupShareSettings(
                        shareRoutines, shareStudy, shareStreak, shareSchedule, shareStudyingNow
                    )
                )
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun ShareToggleRow(title: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Spacer(Modifier.height(4.dp))
}
