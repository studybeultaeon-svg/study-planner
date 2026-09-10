package com.phonelock.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.phonelock.app.ui.theme.Spacing
import java.time.DayOfWeek
import java.time.LocalDate

private val MINI_CAL_MONTHS_KO = arrayOf("1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월")
private val MINI_CAL_WEEKDAYS_KO = arrayOf("일", "월", "화", "수", "목", "금", "토")

/**
 * 96차, 사용자 요청: 공부앱 캘린더 탭(CalendarScreen.kt의 CalendarMonthGrid)과 같은 시각 언어를 쓰는
 * 가벼운 "날짜 하나 고르기" 전용 미니 캘린더 — 일정/회독 점 표시 없이 순수 월 이동 + 날짜 선택만 한다.
 * 그룹 편집 화면의 "이 기간엔 끄기 금지" 날짜 범위처럼, 기본 Material DatePicker 대신 앱 자체 캘린더
 * 느낌을 쓰고 싶은 곳에서 [CompactDateField]와 함께 쓴다.
 */
@Composable
fun MiniCalendarDialog(
    initialDate: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    var year by remember { mutableStateOf((initialDate ?: LocalDate.now()).year) }
    var month by remember { mutableStateOf((initialDate ?: LocalDate.now()).monthValue - 1) }
    var selected by remember { mutableStateOf(initialDate) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(Modifier.padding(Spacing.md)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { if (month == 0) { month = 11; year-- } else month-- }) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "이전 달")
                    }
                    Text("${year}년 ${MINI_CAL_MONTHS_KO[month]}", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { if (month == 11) { month = 0; year++ } else month++ }) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음 달")
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(Modifier.fillMaxWidth()) {
                    MINI_CAL_WEEKDAYS_KO.forEachIndexed { i, d ->
                        val c = when (i) { 0 -> Color(0xFFF87171); 6 -> Color(0xFF6B9FFF); else -> MaterialTheme.colorScheme.onSurface }
                        Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = c)
                    }
                }
                val firstOfMonth = LocalDate.of(year, month + 1, 1)
                val firstDow = firstOfMonth.dayOfWeek.value % 7
                val daysInMonth = firstOfMonth.lengthOfMonth()
                val rows = (firstDow + daysInMonth + 6) / 7
                val today = LocalDate.now()
                for (row in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val dayNum = row * 7 + col - firstDow + 1
                            if (dayNum in 1..daysInMonth) {
                                val date = LocalDate.of(year, month + 1, dayNum)
                                val isToday = date == today
                                val isSelected = selected == date
                                Box(
                                    Modifier.weight(1f).padding(1.dp).size(40.dp)
                                        .border(if (isSelected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        .clickable { selected = date },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier.size(28.dp)
                                            .background(if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val dayColor = when {
                                            date.dayOfWeek == DayOfWeek.SUNDAY -> Color(0xFFF87171)
                                            date.dayOfWeek == DayOfWeek.SATURDAY -> Color(0xFF6B9FFF)
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                        Text(
                                            "$dayNum",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = dayColor,
                                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            } else {
                                Box(Modifier.weight(1f).size(40.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("취소") }
                    TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) { Text("확인") }
                }
            }
        }
    }
}

/** [CompactField]와 같은 테두리 박스 모양이지만 탭하면 키보드 대신 [MiniCalendarDialog]가 뜬다
 *  (96차, "이 기간엔 끄기 금지" 날짜 필드 — 사용자 요청으로 공부앱 캘린더 탭의 미니 캘린더를 재사용). */
@Composable
fun CompactDateField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "날짜 선택"
) {
    var showDialog by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable { showDialog = true }
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📅", style = calcFieldTextStyle())
            Spacer(Modifier.width(6.dp))
            Text(
                value.ifBlank { placeholder },
                style = calcFieldTextStyle(),
                color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
    if (showDialog) {
        val initial = runCatching { LocalDate.parse(value) }.getOrNull()
        MiniCalendarDialog(
            initialDate = initial,
            onDismiss = { showDialog = false },
            onConfirm = { date ->
                onValueChange(date.toString())
                showDialog = false
            }
        )
    }
}
