package com.phonelock.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
        }
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
        ) {
            Text("📅", modifier = Modifier.padding(end = 4.dp))
            // 85차(사용자 지적): 스마트폰에서 "시작"/"마감"을 좌우로 나란히 두면 폭이 좁아 계산기
            // 필드와 같은 bodyLarge로는 "YYYY-MM-DD" 10글자가 다 안 보이고 말줄임됐다 — 이 버튼만
            // bodyMedium으로 줄여 날짜 전체가 항상 다 보이게 했다.
            Text(
                value.ifBlank { "날짜 선택" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
        }
    }
    if (showDialog) {
        val initialMillis = runCatching {
            LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onValueChange(date.toString())
                    }
                    showDialog = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("취소") } }
        ) { DatePicker(state = state) }
    }
}
