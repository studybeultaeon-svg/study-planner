package com.phonelock.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import java.time.LocalDate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Switch
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.SocialGroupSyncClient
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.components.TextMessageDialog
import com.phonelock.desktop.ui.components.VoiceRecordDialog
import com.phonelock.desktop.ui.components.WakeOptionsDialog
import com.phonelock.desktop.ui.theme.Spacing

private fun formatSeconds(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분"
}

private fun formatRelativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "갱신 기록 없음"
    val diffSec = (System.currentTimeMillis() - epochMillis) / 1000
    return when {
        diffSec < 60 -> "방금 전 갱신"
        diffSec < 3600 -> "${diffSec / 60}분 전 갱신"
        diffSec < 86400 -> "${diffSec / 3600}시간 전 갱신"
        else -> "${diffSec / 86400}일 전 갱신"
    }
}

/**
 * 모임 멤버 한 명의 상세 — 루틴별 오늘 완료 체크리스트(아이콘/시간 포함), 오늘 공부시간/진행률(원형
 * 그래프), 스트릭. 상대가 그 항목의 공유 토글을 꺼뒀으면 "비공개"로 표시한다.
 */
@Composable
fun SocialGroupMemberDetailScreen(
    repository: Repository,
    groupId: String,
    member: SocialGroupSyncClient.MemberStats,
    isSelf: Boolean,
    onNudge: () -> Unit,
    onSendVoice: (ByteArray, Long) -> Unit = { _, _ -> },
    onSendText: (String) -> Unit = {},
    onShareSettingsChanged: () -> Unit = {}
) {
    // 깨우기 흐름 — 알림만/음성/텍스트 중 고르는 선택창부터 시작한다("무전기"는 "😴 깨우기"의 확장이라는
    // 관점, SocialGroupMembersScreen과 같은 다이얼로그를 공유).
    var wakeStep by remember { mutableStateOf<String?>(null) }
    // "🗂️ 관리 그룹" 항목 클릭 시 상세 설정(스케줄/일일한도/실행확인/차단 앱·사이트)을 보여줄 다이얼로그 대상.
    var detailGroup by remember { mutableStateOf<com.phonelock.desktop.monitor.SocialGroupSyncClient.ActiveGroupStat?>(null) }
    // "모임 내 사용자 상세 설정" — 이 사람에게 내 정보를 숨길지(RTDB에 반영돼 상대 화면에 보임)와
    // 이 사람 정보를 내 화면에서만 안 보이게 할지(순수 로컬)는 서로 독립적인 두 방향 설정이다.
    var hideMyInfoFromThem by remember(groupId, member.uid) { mutableStateOf(repository.hiddenFromUidsFor(groupId).contains(member.uid)) }
    var hideTheirInfoFromMe by remember(groupId, member.uid) { mutableStateOf(repository.hiddenPeerUidsFor(groupId).contains(member.uid)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MemberHeaderCard(member.displayName, member.updatedAt, Modifier.weight(1f))
            if (!isSelf) {
                Spacer(Modifier.width(Spacing.sm))
                Button(onClick = { wakeStep = "options" }) { Text("😴 깨우기") }
            }
        }
        Spacer(Modifier.height(Spacing.lg))

        if (!isSelf) {
            SectionCard("👤 이 사람에 대한 내 설정") {
                PrivacyToggleRow(
                    title = "이 사람에게 내 정보 숨기기",
                    description = "켜면 이 사람 화면에서 내 정보가 전부 \"비공개\"로 보입니다.",
                    checked = hideMyInfoFromThem,
                    onCheckedChange = { checked ->
                        hideMyInfoFromThem = checked
                        repository.setHiddenFromUid(groupId, member.uid, checked)
                        Thread { SocialGroupSyncClient.pushMyStats(repository.fbDatabaseUrl, repository.fbApiKey, groupId, repository) }.start()
                    }
                )
                PrivacyToggleRow(
                    title = "이 사람 정보 숨기기",
                    description = "켜면 이 사람의 정보가 내 화면에서만 안 보입니다(관심 없을 때).",
                    checked = hideTheirInfoFromMe,
                    onCheckedChange = { checked ->
                        hideTheirInfoFromMe = checked
                        repository.setHiddenPeerUid(groupId, member.uid, checked)
                        onShareSettingsChanged()
                    }
                )
            }
            Spacer(Modifier.height(Spacing.md))
        }

        if (hideTheirInfoFromMe) {
            Text(
                "이 사람의 정보를 숨겼습니다. 위 설정에서 다시 켤 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        val myUid = com.phonelock.desktop.monitor.AuthManager.currentUid
        if (myUid != null && member.hiddenFromUids.contains(myUid)) {
            Text(
                "이 사람이 나에게 자신의 정보를 비공개로 설정했습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        // 77차: 이 사람의 데이터를 한 화면에 쭉 나열하던 걸, 내 앱 본체와 똑같은 탭 구조(루틴/공부/관리 +
        // 각 서브탭)로 바꿔서 "내가 그 탭을 눌렀을 때 보는 화면"과 같은 형태로 클릭해서 들어가게 했다
        // (편집 기능은 전부 뺀 읽기전용 버전, 사용자 요청). 각 리프 탭 컴포저블은 라이브 화면(RoutineScreen
        // 등)을 직접 재사용하지 않고 이 파일 안에 별도로 새로 작성했다 — 라이브 화면은 내 실제 데이터를
        // 읽고 쓰는 핵심 화면이라 그대로 재사용하면 버그 위험이 크다는 판단(사용자 확인).
        var section by remember { mutableStateOf(0) }
        var routineSubTab by remember { mutableStateOf(0) }
        var studySubTab by remember { mutableStateOf(0) }
        var manageSubTab by remember { mutableStateOf(0) }

        TabRow(selectedTabIndex = section, containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            Tab(selected = section == 0, onClick = { section = 0 }, text = { Text("🌱 루틴") })
            Tab(selected = section == 1, onClick = { section = 1 }, text = { Text("📘 공부") })
            Tab(selected = section == 2, onClick = { section = 2 }, text = { Text("🗂️ 관리") })
        }
        Spacer(Modifier.height(Spacing.sm))

        when (section) {
            0 -> {
                TabRow(selectedTabIndex = routineSubTab, containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Tab(selected = routineSubTab == 0, onClick = { routineSubTab = 0 }, text = { Text("오늘") })
                    Tab(selected = routineSubTab == 1, onClick = { routineSubTab = 1 }, text = { Text("통계") })
                }
                Spacer(Modifier.height(Spacing.sm))
                if (!member.shareRoutines) {
                    Text("비공개", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else when (routineSubTab) {
                    0 -> MemberRoutineTodayTab(member)
                    else -> MemberRoutineStatsTab(member)
                }
            }
            1 -> {
                if (member.shareStudy || member.shareStudyingNow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (member.shareStudy) {
                            CircularPercentGauge(percent = member.studyProgressPercent, color = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(Spacing.md))
                            Column {
                                Text(formatSeconds(member.studyTodaySeconds), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("오늘 공부 · 진행률 ${member.studyProgressPercent}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (member.shareStudyingNow) {
                            if (member.shareStudy) Spacer(Modifier.width(Spacing.md))
                            Text(
                                if (member.studyingNow) {
                                    "🟢 공부 중" + if (member.studyingTaskName.isNotBlank()) " · ${member.studyingTaskName}" else ""
                                } else "지금은 공부 중이 아닙니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (member.studyingNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
                TabRow(selectedTabIndex = studySubTab, containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Tab(selected = studySubTab == 0, onClick = { studySubTab = 0 }, text = { Text("캘린더") })
                    Tab(selected = studySubTab == 1, onClick = { studySubTab = 1 }, text = { Text("일정표") })
                    Tab(selected = studySubTab == 2, onClick = { studySubTab = 2 }, text = { Text("통계") })
                }
                Spacer(Modifier.height(Spacing.sm))
                if (!member.shareSchedule) {
                    Text("비공개", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else when (studySubTab) {
                    0 -> ReadOnlyMiniCalendar(member.schedule, member.studySecondsByDate)
                    1 -> MemberStudyTimetableTab(member)
                    else -> MemberStudyStatsTab(member)
                }
            }
            else -> {
                TabRow(selectedTabIndex = manageSubTab, containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Tab(selected = manageSubTab == 0, onClick = { manageSubTab = 0 }, text = { Text("그룹") })
                    Tab(selected = manageSubTab == 1, onClick = { manageSubTab = 1 }, text = { Text("통계") })
                }
                Spacer(Modifier.height(Spacing.sm))
                if (!member.shareActiveGroup) {
                    Text("비공개", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (member.activeGroups.isEmpty()) {
                    Text("등록된 그룹이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else when (manageSubTab) {
                    0 -> MemberManageGroupsTab(member) { detailGroup = it }
                    else -> MemberManageStatsTab(member) { detailGroup = it }
                }
            }
        }
    }

    detailGroup?.let { g ->
        ActiveGroupDetailDialog(group = g, onDismiss = { detailGroup = null })
    }

    if (wakeStep == "options") {
        WakeOptionsDialog(
            targetName = member.displayName,
            onDismiss = { wakeStep = null },
            onNudge = { onNudge(); wakeStep = null },
            onOpenVoiceRecorder = { wakeStep = "voice" },
            onOpenTextMessage = { wakeStep = "text" }
        )
    }

    if (wakeStep == "voice") {
        VoiceRecordDialog(
            onDismiss = { wakeStep = null },
            onSend = { wavBytes, durationMs ->
                onSendVoice(wavBytes, durationMs)
                wakeStep = null
            }
        )
    }

    if (wakeStep == "text") {
        TextMessageDialog(
            onDismiss = { wakeStep = null },
            onSend = { text ->
                onSendText(text)
                wakeStep = null
            }
        )
    }
}

@Composable
private fun MemberHeaderCard(displayName: String, updatedAt: Long, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(Modifier.fillMaxWidth().padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(Spacing.md))
            Column {
                Text(displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    formatRelativeTime(updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun CircularPercentGauge(percent: Int, color: Color) {
    val clamped = percent.coerceIn(0, 100)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(64.dp)) {
            val stroke = 8.dp.toPx()
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                size = Size(size.width - stroke, size.height - stroke),
                topLeft = Offset(stroke / 2, stroke / 2)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * (clamped / 100f),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                size = Size(size.width - stroke, size.height - stroke),
                topLeft = Offset(stroke / 2, stroke / 2)
            )
        }
        Text("$clamped%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PrivacyToggleRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Spacer(Modifier.height(Spacing.xs))
}

private val MEMBER_CAL_MONTHS_KO = arrayOf("1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월")
private val MEMBER_CAL_WEEKDAYS_KO = arrayOf("일", "월", "화", "수", "목", "금", "토")

/**
 * "오늘 일정" 텍스트 목록이었던 걸 76차에 실제 캘린더 탭(CalendarScreen)과 같은 시각 언어(TaskChip,
 * stageTextColor 등 internal로 열어둔 함수 재사용)로 그리는 읽기전용 미니 월 그리드로 바꿨다 —
 * 편집 불가(색상변경/이동복사 없음)라는 점만 다르고, 배지/칩 스타일은 캘린더 탭과 동일하다.
 * 날짜 칸을 클릭하면 그 날의 일정 전체(이름/상태)와 그 날 공부시간을 아래에 펼쳐 보여준다(77차, 한 페이지에
 * 다 욱여넣지 말고 클릭해서 상세를 보게 해달라는 요청).
 */
@Composable
private fun ReadOnlyMiniCalendar(
    schedule: List<com.phonelock.desktop.monitor.SocialGroupSyncClient.ScheduleStat>,
    studySecondsByDate: Map<String, Int>
) {
    val today = remember { LocalDate.now() }
    val year = today.year
    val month = today.monthValue - 1
    val tasksByDate = remember(schedule) { schedule.groupBy { it.dateKey } }
    val firstOfMonth = LocalDate.of(year, month + 1, 1)
    val firstDow = firstOfMonth.dayOfWeek.value % 7
    val daysInMonth = firstOfMonth.lengthOfMonth()
    val rows = (firstDow + daysInMonth + 6) / 7
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text("${year}년 ${MEMBER_CAL_MONTHS_KO[month]}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        Row(Modifier.fillMaxWidth()) {
            MEMBER_CAL_WEEKDAYS_KO.forEachIndexed { i, d ->
                val c = when (i) { 0 -> Color(0xFFF87171); 6 -> Color(0xFF6B9FFF); else -> MaterialTheme.colorScheme.onSurface }
                Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = c)
            }
        }
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth().height(72.dp)) {
                for (col in 0 until 7) {
                    val dayNum = row * 7 + col - firstDow + 1
                    if (dayNum in 1..daysInMonth) {
                        val date = LocalDate.of(year, month + 1, dayNum)
                        val dayTasks = tasksByDate[date.toString()].orEmpty()
                        val isToday = date == today
                        val isSelected = selectedDate == date
                        Box(
                            Modifier.weight(1f).fillMaxHeight().padding(1.dp)
                                .border(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    MaterialTheme.shapes.extraSmall
                                )
                                .clickable { selectedDate = if (isSelected) null else date }
                                .padding(3.dp)
                        ) {
                            Column {
                                Box(
                                    Modifier.size(16.dp).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "$dayNum",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                dayTasks.take(2).forEach { t ->
                                    TaskChip(name = t.name, stage = t.color, status = t.status, modifier = Modifier.fillMaxWidth().padding(top = 1.dp))
                                }
                                if (dayTasks.size > 2) {
                                    Text("+${dayTasks.size - 2}개 더", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f).fillMaxHeight().padding(1.dp))
                    }
                }
            }
        }

        selectedDate?.let { date ->
            Spacer(Modifier.height(Spacing.sm))
            val dayTasks = tasksByDate[date.toString()].orEmpty()
            val seconds = studySecondsByDate[date.toString()] ?: 0
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(Modifier.padding(Spacing.sm)) {
                    Text(
                        "${date.monthValue}월 ${date.dayOfMonth}일" + if (seconds > 0) " · ⏱ ${formatSeconds(seconds)}" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    if (dayTasks.isEmpty()) {
                        Text("등록된 일정이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        dayTasks.forEach { t ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(when (t.status) { "O" -> "✅"; "X" -> "❌"; else -> "▫" }, modifier = Modifier.padding(end = Spacing.sm))
                                Text(t.name, style = MaterialTheme.typography.bodyMedium, color = stageTextColor(t.color))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val GROUP_DETAIL_DAY_LABELS = listOf("월", "화", "수", "목", "금", "토", "일")

private fun groupDetailMinutes(m: Int?): String = if (m == null) "" else "%02d:%02d".format(m / 60, m % 60)

private fun groupDetailDaysMask(mask: Int): String {
    if (mask == 127) return "매일"
    val days = GROUP_DETAIL_DAY_LABELS.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }
    return if (days.isEmpty()) "없음" else days.joinToString(", ")
}

/**
 * "🗂️ 관리 그룹" 항목을 클릭하면 뜨는 상세 — 이 그룹이 어떤 방식(스케줄/일일한도/실행확인)으로,
 * 언제, 무엇(앱/사이트)을 차단하는지 전부 보여준다(77차, "그룹은 뭐하는 그룹인지"까지 보고 싶다는 요청).
 */
@Composable
private fun ActiveGroupDetailDialog(group: com.phonelock.desktop.monitor.SocialGroupSyncClient.ActiveGroupStat, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(group.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (group.description.isNotBlank()) {
                    Text(group.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.md))
                }
                if (group.scheduleEnabled) {
                    Text("⏰ 스케줄 차단", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${groupDetailMinutes(group.scheduleStartMinute)} ~ ${groupDetailMinutes(group.scheduleEndMinute)} · ${groupDetailDaysMask(group.scheduleDaysMask)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (group.dailyLimitSeconds != null) {
                    Text("⏳ 일일 사용한도", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${group.dailyLimitSeconds / 60}분 · ${groupDetailMinutes(group.dailyLimitApplyStartMinute)} ~ ${groupDetailMinutes(group.dailyLimitApplyEndMinute)} · ${groupDetailDaysMask(group.dailyLimitDaysMask)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (group.confirmEnabled) {
                    Text("✅ 실행 확인", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${groupDetailMinutes(group.confirmApplyStartMinute)} ~ ${groupDetailMinutes(group.confirmApplyEndMinute)} · ${groupDetailDaysMask(group.confirmDaysMask)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (!group.scheduleEnabled && group.dailyLimitSeconds == null && !group.confirmEnabled) {
                    Text("적용된 관리 종류가 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (group.processNames.isNotEmpty()) {
                    Text("🖥️ 차단 프로그램 (${group.processNames.size}개)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(group.processNames.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (group.domains.isNotEmpty()) {
                    Text("🌐 차단 사이트 (${group.domains.size}개)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(group.domains.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@Composable
private fun StreakVisual(streak: Int) {
    val flameCount = when {
        streak <= 0 -> 0
        streak < 3 -> 1
        streak < 7 -> 2
        else -> 3
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$streak",
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary
        )
        Text("일", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Spacing.xs))
        Spacer(Modifier.width(Spacing.sm))
        repeat(flameCount) {
            Text("🔥", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** "루틴 - 오늘" 탭 — 라이브 RoutineScreen의 "오늘" 탭과 같은 체크리스트를 보기전용으로. */
@Composable
private fun MemberRoutineTodayTab(member: SocialGroupSyncClient.MemberStats) {
    if (member.routines.isEmpty()) {
        Text("오늘 예정된 루틴이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val routines = member.routines.sortedWith(compareBy(nullsLast()) { it.timeSlot })
    val doneCount = routines.count { it.doneToday }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("완료 현황", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$doneCount / ${routines.size}개", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(Spacing.xs))
        LinearProgressIndicator(
            progress = { if (routines.isEmpty()) 0f else doneCount.toFloat() / routines.size },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.height(Spacing.sm))
        routines.forEach { r ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                shape = MaterialTheme.shapes.small,
                color = if (r.doneToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
            ) {
                Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (r.doneToday) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (r.doneToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = Spacing.xs)
                    )
                    if (r.icon.isNotBlank()) {
                        Text(r.icon, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = Spacing.xs))
                    }
                    Text(r.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (!r.timeSlot.isNullOrBlank()) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                r.timeSlot,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * "루틴 - 통계" 탭 — 라이브 RoutineStatsTab(RoutineScreen.kt)의 현재/최고 스트릭 톤을 옮겼다. 7일/30일
 * 추이 그래프는 오늘 루틴 완료여부만 동기화되고 과거 이력은 동기화 대상이 아니라서(76차 이전부터 그랬음,
 * 이력까지 공유 범위를 넓히는 건 별도 논의 필요) 뺐다 — 스트릭/오늘 완료율만 정확히 보여준다.
 */
@Composable
private fun MemberRoutineStatsTab(member: SocialGroupSyncClient.MemberStats) {
    val routines = member.routines
    val doneCount = routines.count { it.doneToday }
    val rate = if (routines.isNotEmpty()) Math.round(doneCount * 100.0 / routines.size).toInt() else 0

    if (!member.shareStreak) {
        Text("스트릭은 비공개입니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.sm))
    } else {
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("현재 스트릭", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StreakVisual(member.streak)
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MemberStatTile("최고 스트릭", "${member.routineBestStreak}일", Modifier.weight(1f))
            MemberStatTile("오늘 완료율", "$rate%", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MemberStatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * "공부 - 일정표" 탭 — 라이브 TimetableScreen의 요일별 표를 옮기되, 계산기 목표량(linkedCalc)은 "모임"
 * 공유 대상이 아니라서(사용자 확인) 대신 그 주 캘린더 일정을 요일별로 나열하는 형태로 단순화했다.
 */
@Composable
private fun MemberStudyTimetableTab(member: SocialGroupSyncClient.MemberStats) {
    val today = remember { LocalDate.now() }
    val sunday = remember(today) { today.minusDays(today.dayOfWeek.value.toLong() % 7) }
    val tasksByDate = remember(member.schedule) { member.schedule.groupBy { it.dateKey } }
    val weekDates = remember(sunday) { (0..6).map { sunday.plusDays(it.toLong()) } }
    val weekdayLabels = listOf("일", "월", "화", "수", "목", "금", "토")

    Column(Modifier.fillMaxWidth()) {
        weekDates.forEachIndexed { i, date ->
            val dayTasks = tasksByDate[date.toString()].orEmpty()
            val isToday = date == today
            Surface(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                shape = MaterialTheme.shapes.small,
                color = if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
            ) {
                Row(Modifier.fillMaxWidth().padding(Spacing.sm)) {
                    Text(
                        "${date.monthValue}/${date.dayOfMonth}(${weekdayLabels[i]})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(64.dp)
                    )
                    if (dayTasks.isEmpty()) {
                        Text("-", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column {
                            dayTasks.forEach { t ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(when (t.status) { "O" -> "✅"; "X" -> "❌"; else -> "▫" }, modifier = Modifier.padding(end = Spacing.xs))
                                    Text(t.name, style = MaterialTheme.typography.bodySmall, color = stageTextColor(t.color))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "공부 - 통계" 탭 — 라이브 StudyStatsScreen의 오늘 통계/스트릭 톤을 옮기되, 전체 이력이 아니라 동기화된
 * 달 범위(±7일 버퍼) 안에서만 계산한다 — 캘린더 탭과 같은 데이터([member.schedule])를 재사용하는 만큼
 * 정확한 전체 기록이 아니라 "최근" 범위 근사치임을 라벨로 밝혀둔다.
 */
@Composable
private fun MemberStudyStatsTab(member: SocialGroupSyncClient.MemberStats) {
    val today = remember { LocalDate.now() }
    val byDate = remember(member.schedule) { member.schedule.groupBy { it.dateKey } }
    val todayTasks = byDate[today.toString()].orEmpty()
    val doneCount = todayTasks.count { it.status == "O" }
    val rate = if (todayTasks.isNotEmpty()) Math.round(doneCount * 100.0 / todayTasks.size).toInt() else 0

    var streak = 0
    for (i in 0 until 90) {
        val key = today.minusDays(i.toLong()).toString()
        val dayTasks = byDate[key] ?: continue
        if (dayTasks.isEmpty()) continue
        if (dayTasks.count { it.status == "O" } == dayTasks.size) streak++ else break
    }

    val stageCounts = member.schedule.groupBy { it.color }.mapValues { it.value.size }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MemberStatTile("오늘 일정", "${todayTasks.size}개", Modifier.weight(1f))
            MemberStatTile("완료", "${doneCount}개", Modifier.weight(1f))
            MemberStatTile("완료율", "$rate%", Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.sm))
        MemberStatTile("연속 완료일(최근 범위 내)", "${streak}일", Modifier.fillMaxWidth())
        if (stageCounts.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            Text("회독 단계별 일정 수", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.xs))
            stageCounts.entries.sortedByDescending { it.value }.forEach { (stage, count) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stage, style = MaterialTheme.typography.bodySmall, color = stageTextColor(stage))
                    Text("${count}개", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** "관리 - 그룹" 탭 — 그룹 이름/설명 목록, 클릭하면 [ActiveGroupDetailDialog]로 전체 설정을 보여준다. */
@Composable
private fun MemberManageGroupsTab(member: SocialGroupSyncClient.MemberStats, onClick: (SocialGroupSyncClient.ActiveGroupStat) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "그룹을 눌러 자세한 설정을 볼 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xs))
        member.activeGroups.forEach { g ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onClick(g) }.padding(vertical = 4.dp)
            ) {
                Text("• ${g.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (g.description.isNotBlank()) {
                    Text(
                        g.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.md)
                    )
                }
            }
        }
    }
}

/** "관리 - 통계" 탭 — 라이브 StatsScreen의 오늘 사용량/한도/재확인 횟수/최근 평균을 그룹별로 옮겼다. */
@Composable
private fun MemberManageStatsTab(member: SocialGroupSyncClient.MemberStats, onClick: (SocialGroupSyncClient.ActiveGroupStat) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        member.activeGroups.forEach { g ->
            Surface(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onClick(g) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(Modifier.padding(Spacing.sm)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(g.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(
                            formatSeconds(g.todayUsageSeconds) + if (g.dailyLimitSeconds != null) " / ${g.dailyLimitSeconds / 60}분" else "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (g.dailyLimitSeconds != null) {
                        Spacer(Modifier.height(Spacing.xs))
                        LinearProgressIndicator(
                            progress = { (g.todayUsageSeconds.toFloat() / g.dailyLimitSeconds).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "재확인 오늘 ${g.confirmCountToday}회 · 어제 ${g.confirmCountYesterday}회 · 최근 평균 ${formatSeconds(g.recentAverageSeconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
