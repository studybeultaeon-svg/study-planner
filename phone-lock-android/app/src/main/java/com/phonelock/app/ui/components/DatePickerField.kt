package com.phonelock.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * "YYYY-MM-DD" 문자열을 다루는 날짜 입력칸(83차 재설계). 처음엔 읽기전용 OutlinedTextField+floating
 * label로 만들었는데, "시작"/"마감"을 좌우로 나란히 두면 "마감" 같은 짧은 라벨도 좁은 폭에서 줄바꿈돼
 * 아래 캘린더 아이콘과 겹치는 문제가 있었다(데스크탑판에서 먼저 발견, 사용자 지적) — NumberStepperField와
 * 같은 "라벨은 위에 별도 텍스트로" 패턴으로 통일하고, 입력칸 자체는 버튼(눌러서 고르는 동작이라는 게
 * 명확해짐)으로 바꿔 좁은 폭에서도 절대 겹치지 않게 했다. 값 저장 포맷은 기존과 동일한 "YYYY-MM-DD".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    var showDialog by remember { mutableStateOf(false) }
    // 147차: 라벨 + 테두리 버튼 상자 → [LedgerInputFrame](라벨 + 값 + 아래 가는 선), 누르면 달력 창.
    LedgerInputFrame(modifier.clickable { showDialog = true }, label = label) {
        // 146차: 📅 이모지 대신 벡터 아이콘(Ledger 규칙 — 버튼에 이모지 금지).
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Outlined.CalendarToday,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp).size(18.dp)
        )
        // 85차(사용자 지적): 폰에서 "시작"/"마감"을 좌우로 나란히 두면 폭이 좁아 bodyLarge로는 "YYYY-MM-DD" 10글자가
        // 다 안 보이고 말줄임됐다 — 날짜 글자만 bodyMedium으로 줄여 항상 다 보이게 한다.
        Text(
            value.ifBlank { "날짜 선택" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        )
    }
    if (showDialog) {
        // 146차: Material 날짜 창(톤 입힌 판·큰 머리) → 앱 자체 달력 창(떠 있는 창 공용 판, 주말색 팔레트).
        MiniCalendarDialog(
            initialDate = runCatching { LocalDate.parse(value) }.getOrNull(),
            onDismiss = { showDialog = false },
            onConfirm = { date ->
                onValueChange(date.toString())
                showDialog = false
            }
        )
    }
}
