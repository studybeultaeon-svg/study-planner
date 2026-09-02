package com.phonelock.app.ui

import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.*
import com.phonelock.app.service.AuthManager
import com.phonelock.app.service.SocialGroupSyncClient
import com.phonelock.app.service.VoiceRecorder
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.components.TextMessageDialog
import com.phonelock.app.ui.components.VoiceRecordDialog
import com.phonelock.app.ui.components.WakeOptionsDialog
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.launch

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
 * 모임 멤버 상세 — 루틴별 오늘 완료 체크리스트(아이콘/시간 포함) / 오늘 공부시간·진행률(원형 그래프) /
 * 스트릭. 상대가 설정에서 해당 항목 공유를 꺼뒀으면 "비공개"만 표시한다(계획 문서 참고).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialGroupMemberDetailScreen(
    repository: PhoneLockRepository,
    groupId: String,
    targetUid: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val myUid = AuthManager.currentUser?.uid
    var stats by remember { mutableStateOf<SocialGroupSyncClient.MemberStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    var displayName by remember { mutableStateOf("") }
    // "모임 내 사용자 상세 설정" — 이 사람에게 내 정보를 숨길지(RTDB에 반영돼 상대 화면에 보임)와
    // 이 사람 정보를 내 화면에서만 안 보이게 할지(순수 로컬)는 서로 독립적인 두 방향 설정이다.
    var hideMyInfoFromThem by remember(groupId, targetUid) { mutableStateOf(repository.hiddenFromUidsFor(groupId).contains(targetUid)) }
    var hideTheirInfoFromMe by remember(groupId, targetUid) { mutableStateOf(repository.hiddenPeerUidsFor(groupId).contains(targetUid)) }
    // 깨우기 흐름 — 알림만/음성/텍스트 중 고르는 선택창부터 시작한다("무전기"는 "😴 깨우기"의 확장이라는
    // 관점, SocialGroupMembersScreen과 같은 다이얼로그를 공유).
    var wakeStep by remember { mutableStateOf<String?>(null) }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) wakeStep = "voice"
        else Toast.makeText(context, "마이크 권한이 필요합니다", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(groupId, targetUid) {
        val all = repository.readSocialGroupStats(groupId)
        stats = all.find { it.uid == targetUid }
        displayName = stats?.displayName ?: "멤버"
        loading = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(displayName) },
            actions = {
                if (targetUid != myUid) {
                    Button(onClick = { wakeStep = "options" }) { Text("😴 깨우기") }
                }
            }
        )
    }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md)
        ) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val s = stats
            if (s == null) {
                Text("아직 동기화된 통계가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            MemberHeaderCard(displayName = displayName, updatedAt = s.updatedAt)
            Spacer(Modifier.height(Spacing.md))

            if (targetUid != myUid) {
                SectionCard("👤 이 사람에 대한 내 설정") {
                    PrivacyToggleRow(
                        title = "이 사람에게 내 정보 숨기기",
                        description = "켜면 이 사람 화면에서 내 정보가 전부 \"비공개\"로 보입니다.",
                        checked = hideMyInfoFromThem,
                        onCheckedChange = { checked ->
                            hideMyInfoFromThem = checked
                            repository.setHiddenFromUid(groupId, targetUid, checked)
                            scope.launch { repository.pushMySocialStats(groupId) }
                        }
                    )
                    PrivacyToggleRow(
                        title = "이 사람 정보 숨기기",
                        description = "켜면 이 사람의 정보가 내 화면에서만 안 보입니다(관심 없을 때).",
                        checked = hideTheirInfoFromMe,
                        onCheckedChange = { checked ->
                            hideTheirInfoFromMe = checked
                            repository.setHiddenPeerUid(groupId, targetUid, checked)
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
            if (myUid != null && s.hiddenFromUids.contains(myUid)) {
                Text(
                    "이 사람이 나에게 자신의 정보를 비공개로 설정했습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            // 77차: 이 사람의 데이터를 한 화면에 쭉 나열하던 걸, 내 앱 본체와 똑같은 탭 구조(루틴/공부 +
            // 각 서브탭)로 바꿔서 "내가 그 탭을 눌렀을 때 보는 화면"과 같은 형태로 클릭해서 들어가게 했다
            // (편집 기능은 전부 뺀 읽기전용 버전, 사용자 요청). 라이브 화면(RoutineScreen 등)을 직접
            // 재사용하지 않고 이 파일 안에 별도로 새로 작성했다 — 라이브 화면은 내 실제 데이터를 읽고 쓰는
            // 핵심 화면이라 그대로 재사용하면 버그 위험이 크다는 판단(사용자 확인).
            // "관리"(차단 그룹) 정보는 81차에 공유 항목에서 완전히 제외됨 — 다른 사람이 내가 뭘 차단
            // 중인지까지 알 필요는 없다는 판단(사용자 요청).
            var section by remember { mutableStateOf(0) }
            var routineSubTab by remember { mutableStateOf(0) }
            var studySubTab by remember { mutableStateOf(0) }

            TabRow(selectedTabIndex = section) {
                Tab(selected = section == 0, onClick = { section = 0 }, text = { Text("🌱 루틴") })
                Tab(selected = section == 1, onClick = { section = 1 }, text = { Text("📘 공부") })
            }
            Spacer(Modifier.height(Spacing.sm))

            when (section) {
                0 -> {
                    TabRow(selectedTabIndex = routineSubTab) {
                        Tab(selected = routineSubTab == 0, onClick = { routineSubTab = 0 }, text = { Text("오늘") })
                        Tab(selected = routineSubTab == 1, onClick = { routineSubTab = 1 }, text = { Text("통계") })
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    if (!s.shareRoutines) {
                        Text("비공개", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (routineSubTab == 0) {
                        MemberRoutineTodayTab(s)
                    } else {
                        MemberRoutineStatsTab(s)
                    }
                }
                1 -> {
                    if (s.shareStudy || s.shareStudyingNow) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.shareStudy) {
                                CircularPercentGauge(percent = s.studyProgressPercent ?: 0, color = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(Spacing.md))
                                Column {
                                    Text(formatSeconds(s.studyTodaySeconds ?: 0), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("오늘 공부 · 진행률 ${s.studyProgressPercent ?: 0}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (s.shareStudyingNow) {
                                if (s.shareStudy) Spacer(Modifier.width(Spacing.md))
                                Text(
                                    if (s.studyingNow == true) {
                                        "🟢 공부 중" + if (!s.studyingTaskName.isNullOrBlank()) " · ${s.studyingTaskName}" else ""
                                    } else "지금은 공부 중이 아닙니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (s.studyingNow == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.sm))
                    }
                    TabRow(selectedTabIndex = studySubTab) {
                        Tab(selected = studySubTab == 0, onClick = { studySubTab = 0 }, text = { Text("캘린더") })
                        Tab(selected = studySubTab == 1, onClick = { studySubTab = 1 }, text = { Text("일정표") })
                        Tab(selected = studySubTab == 2, onClick = { studySubTab = 2 }, text = { Text("통계") })
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    if (!s.shareSchedule) {
                        Text("비공개", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else when (studySubTab) {
                        0 -> ReadOnlyMiniCalendar(s.schedule ?: emptyList(), s.studySecondsByDate ?: emptyMap())
                        1 -> MemberStudyTimetableTab(s)
                        else -> MemberStudyStatsTab(s)
                    }
                }
            }
        }
    }

    if (wakeStep == "options") {
        WakeOptionsDialog(
            targetName = displayName,
            onDismiss = { wakeStep = null },
            onNudge = {
                scope.launch {
                    repository.sendSocialGroupNudge(groupId, targetUid)
                    Toast.makeText(context, "깨우기를 보냈습니다", Toast.LENGTH_SHORT).show()
                }
                wakeStep = null
            },
            onOpenVoiceRecorder = {
                if (VoiceRecorder.hasPermission(context)) wakeStep = "voice"
                else recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            onOpenTextMessage = { wakeStep = "text" }
        )
    }

    if (wakeStep == "voice") {
        VoiceRecordDialog(
            onDismiss = { wakeStep = null },
            onSend = { wavBytes, durationMs ->
                scope.launch {
                    val audioBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                    val result = repository.sendVoiceMessage(groupId, targetUid, audioBase64, durationMs)
                    Toast.makeText(
                        context,
                        if (result.isSuccess) "무전을 보냈습니다" else (result.exceptionOrNull()?.message ?: "전송 실패"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                wakeStep = null
            }
        )
    }

    if (wakeStep == "text") {
        TextMessageDialog(
            onDismiss = { wakeStep = null },
            onSend = { text ->
                scope.launch {
                    val result = repository.sendTextMessage(groupId, targetUid, text)
                    Toast.makeText(
                        context,
                        if (result.isSuccess) "메시지를 보냈습니다" else (result.exceptionOrNull()?.message ?: "전송 실패"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                wakeStep = null
            }
        )
    }
}

@Composable
private fun MemberHeaderCard(displayName: String, updatedAt: Long) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * (clamped / 100f),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                size = Size(size.width - stroke, size.height - stroke),
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2)
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
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Spacer(Modifier.height(Spacing.xs))
}

private val MEMBER_CAL_MONTHS_KO = arrayOf("1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월")
private val MEMBER_CAL_WEEKDAYS_KO = arrayOf("일", "월", "화", "수", "목", "금", "토")

/** CalendarScreen.kt의 stageTextColor와 동일한 팔레트 — 파일 간 top-level 이름 충돌(desktop판에서도
 *  겪음)을 피하려고 이 화면 전용으로 복제해뒀다. */
private fun memberCalStageColor(stage: String): Color = when (stage) {
    "white" -> Color(0xFF9CA3AF)
    "red" -> Color(0xFFEF4444)
    "orange" -> Color(0xFFF97316)
    "yellow" -> Color(0xFFEAB308)
    "green" -> Color(0xFF22C55E)
    "blue" -> Color(0xFF3B82F6)
    "indigo" -> Color(0xFF6366F1)
    "purple" -> Color(0xFFA855F7)
    else -> Color(0xFFAAAAAA)
}

@Composable
private fun MemberCalTaskChip(name: String, stage: String, status: String?, modifier: Modifier = Modifier) {
    val accent = memberCalStageColor(stage)
    Text(
        buildAnnotatedString {
            if (status == "O") withStyle(SpanStyle(color = Color(0xFF34D399), fontWeight = FontWeight.Black)) { append("O ") }
            else if (status == "X") withStyle(SpanStyle(color = Color(0xFFF87171), fontWeight = FontWeight.Black)) { append("X ") }
            append(name)
        },
        modifier = modifier
            .background(accent.copy(alpha = 0.15f), MaterialTheme.shapes.extraSmall)
            .border(1.dp, accent, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * "오늘 일정" 텍스트 목록이었던 걸 76차에 실제 캘린더 탭(CalendarScreen)과 같은 시각 언어(색상 배지)로
 * 그리는 읽기전용 미니 월 그리드로 바꿨다 — 편집 불가라는 점만 다르고 배지 스타일은 캘린더 탭과 동일하다.
 * 날짜 칸을 클릭하면 그 날의 일정 전체(이름/상태)와 그 날 공부시간을 아래에 펼쳐 보여준다(77차, 한 페이지에
 * 다 욱여넣지 말고 클릭해서 상세를 보게 해달라는 요청).
 */
@Composable
private fun ReadOnlyMiniCalendar(
    schedule: List<com.phonelock.app.service.SocialGroupSyncClient.ScheduleStat>,
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
            Row(Modifier.fillMaxWidth().height(64.dp)) {
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
                                .padding(2.dp)
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
                                    MemberCalTaskChip(name = t.name, stage = t.color, status = t.status, modifier = Modifier.fillMaxWidth().padding(top = 1.dp))
                                }
                                if (dayTasks.size > 2) {
                                    Text("+${dayTasks.size - 2}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(t.name, style = MaterialTheme.typography.bodyMedium, color = memberCalStageColor(t.color))
                            }
                        }
                    }
                }
            }
        }
    }
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
private fun MemberRoutineTodayTab(s: SocialGroupSyncClient.MemberStats) {
    val routines = (s.routines ?: emptyList()).sortedWith(compareBy(nullsLast()) { it.timeSlot })
    if (routines.isEmpty()) {
        Text("오늘 예정된 루틴이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
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
                        modifier = Modifier.padding(end = Spacing.sm)
                    )
                    if (r.icon.isNotBlank()) {
                        Text(r.icon, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = Spacing.xs))
                    }
                    Text(r.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
 * "루틴 - 통계" 탭 — 라이브 화면(RoutineScreen.kt)의 현재/최고 스트릭 톤을 옮겼다. 7일/30일 추이 그래프는
 * 오늘 루틴 완료여부만 동기화되고 과거 이력은 동기화 대상이 아니라서(이력까지 공유 범위를 넓히는 건
 * 별도 논의 필요) 뺐다 — 스트릭/오늘 완료율만 정확히 보여준다.
 */
@Composable
private fun MemberRoutineStatsTab(s: SocialGroupSyncClient.MemberStats) {
    val routines = s.routines ?: emptyList()
    val doneCount = routines.count { it.doneToday }
    val rate = if (routines.isNotEmpty()) Math.round(doneCount * 100.0 / routines.size).toInt() else 0

    if (!s.shareStreak) {
        Text("스트릭은 비공개입니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
        Column(Modifier.fillMaxWidth().padding(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("현재 스트릭", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StreakVisual(streak = s.streak ?: 0)
        }
    }
    Spacer(Modifier.height(Spacing.sm))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        MemberStatTile("최고 스트릭", "${s.routineBestStreak ?: 0}일", Modifier.weight(1f))
        MemberStatTile("오늘 완료율", "$rate%", Modifier.weight(1f))
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
 * "공부 - 일정표" 탭 — 라이브 TimetableScreen(할당량 계산기 업무를 요일별 목표량 표로 보여주는 화면)을
 * 그대로 옮긴다(78차). 이전엔 계산기 데이터가 모임 공유 대상이 아니라서 대신 그 주 캘린더 일정을
 * 나열하는 형태로 단순화했었는데, 사용자가 "일정표는 진짜 일정표 화면을 의미한다"고 정정해 [MemberStats.calcTasks]
 * (shareSchedule 토글에 함께 묶임)를 새로 동기화해 반영했다. **79차**: 라이브 화면의 빨강(미달성)/초록(달성)
 * 색 시스템도 그대로 이식 — [MemberStats.schedule]에 함께 실려오는 linkedCalc/progressStep으로
 * [PhoneLockRepository.isLinkedGoalAchieved]와 동일한 판정(그날 연동 완료 일정의 progressStep 합 ≥ 목표량)을 재현한다.
 */
@Composable
private fun MemberStudyTimetableTab(s: SocialGroupSyncClient.MemberStats) {
    var cursor by remember { mutableStateOf(LocalDate.now()) }
    val today = LocalDate.now()
    val isToday = cursor == today
    val jsDow = cursor.dayOfWeek.value % 7
    val weekdayLabels = listOf("일", "월", "화", "수", "목", "금", "토")
    val dateLabel = "${cursor.monthValue}월 ${cursor.dayOfMonth}일 (${weekdayLabels[jsDow]})" + if (isToday) " · 오늘" else ""

    val tasks = s.calcTasks ?: emptyList()
    val dayTasks = tasks.filter { t ->
        if (t.name.isBlank() || t.dday.isBlank()) return@filter false
        val dday = runCatching { LocalDate.parse(t.dday) }.getOrNull() ?: return@filter false
        val start = if (t.start.isBlank()) today else (runCatching { LocalDate.parse(t.start) }.getOrNull() ?: today)
        !cursor.isBefore(start) && !cursor.isAfter(dday)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { cursor = cursor.minusDays(1) }) { Text("◀") }
            Spacer(Modifier.width(Spacing.sm))
            Text(dateLabel, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(Spacing.sm))
            OutlinedButton(onClick = { cursor = cursor.plusDays(1) }) { Text("▶") }
        }
        Spacer(Modifier.height(Spacing.sm))

        if (dayTasks.isEmpty()) {
            Text(
                if (tasks.isEmpty()) "등록된 일정표 업무가 없습니다" else "이 날은 진행 중인 업무가 없습니다",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            var dayTotal = 0.0
            dayTasks.forEach { t ->
                val v = memberTimetableDayValue(t, jsDow).toDoubleOrNull() ?: 0.0
                dayTotal += v
                val achieved = v > 0 && memberIsLinkedGoalAchieved(s, cursor.toString(), t.name, v)
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (v > 0) "${memberTimetableFmtDec(v)}${t.unit}" + if (achieved) " ✅" else "" else "—",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (v > 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (v <= 0) MaterialTheme.colorScheme.onSurfaceVariant
                            else if (achieved) Color(0xFF34D399)
                            else if (isToday) Color(0xFFF87171) else MaterialTheme.colorScheme.primary
                    )
                }
                HorizontalDivider()
            }
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("합계", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(memberTimetableFmtDec(dayTotal), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** [PhoneLockRepository.isLinkedGoalAchieved]와 동일 판정을 [MemberStats.schedule](동기화된 캘린더 일정)로 재현한다. */
private fun memberIsLinkedGoalAchieved(s: SocialGroupSyncClient.MemberStats, dateKey: String, calcTaskName: String, dayQuota: Double): Boolean {
    if (dayQuota <= 0) return false
    val doneTotal = (s.schedule ?: emptyList())
        .filter { it.dateKey == dateKey && it.linkedCalc == calcTaskName && it.status == "O" }
        .sumOf { it.progressStep?.toDoubleOrNull() ?: 0.0 }
    return doneTotal >= dayQuota
}

private fun memberTimetableDayValue(task: SocialGroupSyncClient.CalcTaskStat, jsDow: Int): String = when (jsDow) {
    0 -> task.sun; 1 -> task.mon; 2 -> task.tue; 3 -> task.wed
    4 -> task.thu; 5 -> task.fri; else -> task.sat
}

private fun memberTimetableFmtDec(n: Double): String {
    val r = Math.round(n * 100) / 100.0
    return if (r == Math.floor(r)) r.toLong().toString() else String.format(java.util.Locale.KOREA, "%.2f", r).trimEnd('0').trimEnd('.')
}

/**
 * "공부 - 통계" 탭 — 라이브 StudyStatsScreen의 오늘 통계/스트릭 톤을 옮기되, 전체 이력이 아니라 동기화된
 * 달 범위(±7일 버퍼) 안에서만 계산한다 — 캘린더 탭과 같은 데이터([MemberStats.schedule])를 재사용하는
 * 만큼 정확한 전체 기록이 아니라 "최근" 범위 근사치임을 라벨로 밝혀둔다.
 */
@Composable
private fun MemberStudyStatsTab(s: SocialGroupSyncClient.MemberStats) {
    val today = remember { LocalDate.now() }
    val schedule = s.schedule ?: emptyList()
    val byDate = remember(schedule) { schedule.groupBy { it.dateKey } }
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

    val stageCounts = schedule.groupBy { it.color }.mapValues { it.value.size }

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
                    Text(stage, style = MaterialTheme.typography.bodySmall, color = memberCalStageColor(stage))
                    Text("${count}개", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

