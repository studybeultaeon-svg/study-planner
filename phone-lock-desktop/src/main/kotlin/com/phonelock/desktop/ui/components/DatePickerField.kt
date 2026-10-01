package com.phonelock.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import java.time.LocalDate
import java.time.YearMonth

private val DATE_PICKER_WEEKDAYS = arrayOf("일", "월", "화", "수", "목", "금", "토")

/**
 * "YYYY-MM-DD" 문자열을 다루는 날짜 입력칸(83차 재설계). 처음엔 읽기전용 OutlinedTextField+floating
 * label로 만들었는데, "시작"/"마감"을 좌우로 나란히 두면 "마감" 같은 짧은 라벨도 좁은 폭에서 줄바꿈돼
 * 아래 캘린더 아이콘과 겹치는 문제가 있었다 — NumberStepperField와 같은 "라벨은 위에 별도 텍스트로"
 * 패턴으로 통일하고, 입력칸 자체는 버튼(눌러서 고르는 동작이라는 게 명확해짐)으로 바꿔 좁은 폭에서도
 * 절대 겹치지 않게 했다. 값 저장 포맷은 기존과 동일한 "YYYY-MM-DD".
 */
@Composable
fun DatePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var viewMonth by remember(expanded) {
        mutableStateOf(runCatching { YearMonth.from(LocalDate.parse(value)) }.getOrDefault(YearMonth.now()))
    }
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
        }
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
        ) {
            // 146차: 📅 이모지 대신 벡터 아이콘(Ledger 규칙 — 버튼에 이모지 금지).
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
            // 85차(사용자 지적, 안드로이드판과 대칭): 스마트폰에서 "시작"/"마감"을 좌우로 나란히 두면
            // 폭이 좁아 계산기 필드와 같은 bodyLarge로는 "YYYY-MM-DD" 10글자가 다 안 보이고 말줄임됐다
            // — 이 버튼만 bodyMedium으로 줄여 날짜 전체가 항상 다 보이게 했다.
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
        if (expanded) {
            Popup(onDismissRequest = { expanded = false }) {
                // 146차: 톤 입힌 판 → 떠 있는 창 공용 판(종이 바탕 + 가는 테두리), 주말색은 팔레트.
                LedgerDialogSurface {
                    Column(Modifier.padding(12.dp).width(260.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { viewMonth = viewMonth.minusMonths(1) }) {
                                androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "이전 달")
                            }
                            Text("${viewMonth.year}년 ${viewMonth.monthValue}월", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            TextButton(onClick = { viewMonth = viewMonth.plusMonths(1) }) {
                                androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음 달")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth()) {
                            val saturdayColor = saturdayInk()
                            DATE_PICKER_WEEKDAYS.forEachIndexed { i, d ->
                                val c = when (i) { 0 -> MaterialTheme.colorScheme.error; 6 -> saturdayColor; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                                Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = c)
                            }
                        }
                        val first = viewMonth.atDay(1)
                        val firstDow = first.dayOfWeek.value % 7
                        val daysInMonth = viewMonth.lengthOfMonth()
                        val rows = (firstDow + daysInMonth + 6) / 7
                        for (row in 0 until rows) {
                            Row(Modifier.fillMaxWidth()) {
                                for (col in 0 until 7) {
                                    val dayNum = row * 7 + col - firstDow + 1
                                    if (dayNum in 1..daysInMonth) {
                                        val date = viewMonth.atDay(dayNum)
                                        val isSelected = date.toString() == value
                                        Box(
                                            Modifier.weight(1f).padding(2.dp).size(28.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                                .clickable { onValueChange(date.toString()); expanded = false },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "$dayNum",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground
                                            )
                                        }
                                    } else {
                                        Box(Modifier.weight(1f).padding(2.dp).size(28.dp))
                                    }
                                }
                            }
                        }
                        if (value.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { onValueChange(""); expanded = false }, modifier = Modifier.fillMaxWidth()) { Text("지우기") }
                        }
                    }
                }
            }
        }
    }
}
