package com.phonelock.desktop.ui

import com.phonelock.desktop.ui.components.BigNumber
import com.phonelock.desktop.ui.components.Hairline
import com.phonelock.desktop.ui.components.Overline
import com.phonelock.desktop.ui.components.ProgressLine
import com.phonelock.desktop.ui.components.StatBlock
import com.phonelock.desktop.ui.components.StatRow
import com.phonelock.desktop.ui.theme.LocalPhoneLockPalette
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.phonelock.shared.GrowthSystem
import com.phonelock.shared.study.StudyStats

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
 * 모임 멤버 상세(안드로이드판과 대칭) — 루틴별 오늘 완료 체크리스트 / 오늘 집중 시간·진행률 / 연속 기록. 상대가 해당 항목
 * 공유를 꺼뒀으면 "비공개"만 표시한다.
 *
 * 146차: 색 바탕 머리 카드 + 이모지 버튼 → 종이 위 머리(아바타 + 큰 이름) + 아이콘 동작, 탭 내용도 라이브 화면처럼 큰 숫자·가는 선
 * (이 화면은 라이브 화면의 복제본이라 144차 라이브 개편을 그대로 따라간다). "이 사람과의 공개 설정"은 맨 아래로 옮겼다.
 */
@Composable
fun SocialGroupMemberDetailScreen(
    repository: Repository,
    groupId: String,
    member: SocialGroupSyncClient.MemberStats,
    isSelf: Boolean,
    onNudge: () -> Unit,
    onOpenDm: (String, String, String) -> Unit = { _, _, _ -> },
    onSendVoice: (ByteArray, Long) -> Unit = { _, _ -> },
    onSendText: (String) -> Unit = {},
    onShareSettingsChanged: () -> Unit = {}
) {
    // 깨우기 흐름 — 알림만/음성/텍스트 중 고르는 선택창부터 시작한다(SocialGroupMembersScreen과 같은 다이얼로그).
    var wakeStep by remember { mutableStateOf<String?>(null) }
    // "모임 내 사용자 상세 설정" — 이 사람에게 내 정보를 숨길지(RTDB에 반영돼 상대 화면에 보임)와
    // 이 사람 정보를 내 화면에서만 안 보이게 할지(순수 로컬)는 서로 독립적인 두 방향 설정이다.
    var hideMyInfoFromThem by remember(groupId, member.uid) { mutableStateOf(repository.hiddenFromUidsFor(groupId).contains(member.uid)) }
    var hideTheirInfoFromMe by remember(groupId, member.uid) { mutableStateOf(repository.hiddenPeerUidsFor(groupId).contains(member.uid)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MemberHeader(
                member.displayName, member.updatedAt, Modifier.weight(1f), profileImage = member.profileImage,
                sharePlant = member.sharePlant, plantLevel = member.plantLevel, plantTitle = member.plantTitle
            )
            if (!isSelf) {
                androidx.compose.material3.IconButton(onClick = {
                    val url = repository.fbDatabaseUrl
                    val key = repository.fbApiKey
                    Thread {
                        com.phonelock.desktop.monitor.ChatSyncClient.ensureDmChat(url, key, member.uid, member.displayName).onSuccess { chatId ->
                            onOpenDm(chatId, member.uid, member.displayName)
                        }
                    }.start()
                }) { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = "DM 보내기") }
                androidx.compose.material3.IconButton(onClick = { wakeStep = "options" }) {
                    Icon(Icons.Outlined.NotificationsActive, contentDescription = "깨우기", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))

        val myUid = com.phonelock.desktop.monitor.AuthManager.currentUid
        when {
            hideTheirInfoFromMe -> Text(
                "이 사람의 정보를 숨겼습니다. 아래 설정에서 다시 켤 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
            myUid != null && member.hiddenFromUids.contains(myUid) -> Text(
                "이 사람이 나에게 정보를 비공개로 했습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
            else -> MemberSections(repository, member)
        }

        if (!isSelf) {
            Spacer(Modifier.height(Spacing.lg))
            SectionCard("이 사람과의 공개 설정") {
                com.phonelock.desktop.ui.components.ToggleRow(
                    title = "이 사람에게 내 정보 숨기기",
                    description = "이 사람에겐 내 정보가 비공개로 보입니다.",
                    checked = hideMyInfoFromThem,
                    onCheckedChange = { checked ->
                        hideMyInfoFromThem = checked
                        repository.setHiddenFromUid(groupId, member.uid, checked)
                        Thread { SocialGroupSyncClient.pushMyStats(repository.fbDatabaseUrl, repository.fbApiKey, groupId, repository) }.start()
                    }
                )
                com.phonelock.desktop.ui.components.ToggleRow(
                    title = "이 사람 정보 숨기기",
                    description = "내 화면에서만 안 보입니다.",
                    checked = hideTheirInfoFromMe,
                    onCheckedChange = { checked ->
                        hideTheirInfoFromMe = checked
                        repository.setHiddenPeerUid(groupId, member.uid, checked)
                        onShareSettingsChanged()
                    }
                )
            }
        }
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

/**
 * 77차: 이 사람의 데이터를 내 앱 본체와 똑같은 탭 구조(루틴/집중 + 각 서브탭)로 보여 준다(편집 기능은 전부 뺀 읽기전용, 사용자
 * 요청). 각 리프 탭은 라이브 화면을 직접 재사용하지 않고 이 파일 안에 따로 작성했다 — 라이브 화면은 내 실제 데이터를 읽고 쓰는
 * 핵심 화면이라 그대로 재사용하면 버그 위험이 크다는 판단(사용자 확인). "관리"(차단 그룹) 정보는 81차에 공유 항목에서 제외.
 */
@Composable
private fun MemberSections(repository: Repository, s: SocialGroupSyncClient.MemberStats) {
    var section by remember { mutableStateOf(0) }
    var routineSubTab by remember { mutableStateOf(0) }
    var studySubTab by remember { mutableStateOf(0) }

    com.phonelock.desktop.ui.components.SectionTabs(listOf("홈", "루틴", "집중"), section, { section = it })
    Spacer(Modifier.height(Spacing.md))

    when (section) {
        0 -> {
            if (!s.sharePlant) PrivateNote() else MemberHomeTab(s)
        }
        1 -> {
            com.phonelock.desktop.ui.components.SegmentedTabs(listOf("오늘", "연속 기록"), routineSubTab, { routineSubTab = it }, Modifier.widthIn(max = 420.dp))
            Spacer(Modifier.height(Spacing.md))
            if (!s.shareRoutines) {
                PrivateNote()
            } else if (routineSubTab == 0) {
                MemberRoutineTodayTab(s)
            } else {
                MemberRoutineStatsTab(s)
            }
        }
        2 -> {
            if (s.shareStudy || s.shareStudyingNow) {
                if (s.shareStudy) {
                    com.phonelock.desktop.ui.components.StatRow {
                        com.phonelock.desktop.ui.components.StatBlock("오늘 집중", formatSeconds(s.studyTodaySeconds), Modifier.weight(1f))
                        com.phonelock.desktop.ui.components.StatBlock("진행률", "${s.studyProgressPercent}", Modifier.weight(1f), unit = "%")
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (s.shareStudyingNow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.studyingNow) {
                            com.phonelock.desktop.ui.components.LiveDot(MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (s.studyingNow) "집중 중" + if (s.studyingTaskName.isNotBlank()) " · ${s.studyingTaskName}" else ""
                            else "지금은 집중 중이 아닙니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (s.studyingNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.md))
            }
            com.phonelock.desktop.ui.components.SegmentedTabs(listOf("캘린더", "일정표", "집중 통계"), studySubTab, { studySubTab = it }, Modifier.widthIn(max = 520.dp))
            Spacer(Modifier.height(Spacing.md))
            if (!s.shareSchedule) {
                PrivateNote()
            } else {
                // 143차(141차 하루 시작 기준 반영): "오늘"은 달력 날짜가 아니라 이 사람 앱이 올린 하루 시작 기준의
                // 날짜다(옛 버전이 올린 데이터면 보는 사람의 기준).
                val memberToday = remember(s.studyDayKey) {
                    s.studyDayKey?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        ?: LocalDate.parse(repository.todayCalendarDateKey())
                }
                when (studySubTab) {
                    0 -> ReadOnlyMiniCalendar(memberToday, s.schedule, s.studySecondsByDate)
                    1 -> MemberStudyTimetableTab(s, memberToday)
                    else -> MemberStudyStatsTab(s, memberToday)
                }
            }
        }
    }
}

@Composable
private fun PrivateNote() {
    Text("비공개", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.sm))
}

/** 머리 — 아바타 + 이름(칭호·레벨 배지 포함) + 마지막 갱신. 색 바탕 카드 대신 종이 위에 바로(146차). */
@Composable
private fun MemberHeader(
    displayName: String, updatedAt: Long, modifier: Modifier = Modifier, profileImage: String? = null,
    sharePlant: Boolean = false, plantLevel: Int = 1, plantTitle: String = ""
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            val emoji = com.phonelock.desktop.ui.components.AvatarCatalog.emojiFor(profileImage)
            if (emoji != null) {
                Text(emoji, style = MaterialTheme.typography.headlineSmall)
            } else {
                Text(displayName.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            MemberDisplayName(
                title = plantTitle.takeIf { sharePlant },
                name = displayName,
                level = plantLevel.takeIf { sharePlant },
                style = MaterialTheme.typography.headlineSmall
            )
            Text(formatRelativeTime(updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * "홈" 탭(112차 신설, 120차 개편, 안드로이드판과 대칭) — 처음엔 레벨/칭호/등급 카드 요약만 보여줬지만
 * (사용자 피드백: 축약하지 말고 실제 홈 화면을 그대로 보여달라) 라이브 [PlantScreen]과 같은 [CosmosScene]으로
 * 상대의 천체를 그린다(149차, 식물 장면 대신). 다만 이 값들은
 * [SocialGroupSyncClient.MemberStats] 스냅샷(공유 시점 값)이라 실시간이 아니고, 설정/경험치 적용/빅뱅 같은
 * 조작 버튼은 내 계정 전용이라 여기선 뺐다(읽기전용).
 * 150차(사용자 지적 "구식 UI"): 떠 있는 테두리 카드 대신 라이브 홈과 같은 문법 — 장면 위 왼쪽에 큰 레벨 숫자([HomeLevelHero]),
 * 아래에 "다음 레벨까지" 진행 선. 글자는 테마와 상관없이 장면 색([CosmosOverlayTheme]).
 */
@Composable
private fun MemberHomeTab(member: SocialGroupSyncClient.MemberStats) {
    val stage = GrowthSystem.stageForLevel(member.plantLevel)
    val isMaxLevel = GrowthSystem.isMaxLevel(member.plantLevel)
    // 천체가 레벨 숫자와 진행 선 사이 가운데에 그려지도록 숫자 묶음의 높이를 잰다(라이브 홈과 같은 방식).
    var heroHeightPx by remember { mutableIntStateOf(0) }
    val heroHeight = if (heroHeightPx > 0) with(LocalDensity.current) { heroHeightPx.toDp() } else 170.dp

    Box(Modifier.fillMaxWidth().height(520.dp).clip(RoundedCornerShape(20.dp))) {
        CosmosScene(stage.illustrationId, Modifier.fillMaxSize(), contentTopInset = heroHeight, contentBottomInset = 72.dp)
        CosmosOverlayTheme {
            HomeLevelHero(
                level = member.plantLevel,
                stage = stage,
                isMaxLevel = isMaxLevel,
                rebirthCount = member.plantRebirthCount,
                numberStyle = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-2).sp),
                modifier = Modifier.align(Alignment.TopStart).onSizeChanged { heroHeightPx = it.height }.padding(Spacing.lg)
            )
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Spacing.lg)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Overline(if (isMaxLevel) "이번 시즌 최대 레벨" else "다음 레벨까지", Modifier.weight(1f))
                    Text(
                        if (isMaxLevel) "MAX" else "${Math.round(member.plantProgress * 100)}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Spacer(Modifier.height(6.dp))
                ProgressLine(member.plantProgress, thickness = 6.dp)
            }
        }
    }
}

private val MEMBER_CAL_MONTHS_KO = arrayOf("1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월")
private val MEMBER_CAL_WEEKDAYS_KO = arrayOf("일", "월", "화", "수", "목", "금", "토")

/** 128차: 레거시 color 문자열 팔레트를 복제해 쓰던 걸 CalendarScreen.kt의 passColor와 같은 회독
 *  그라데이션으로 맞췄다 — 4회독 이상("pass{N}") 일정이 전부 회색으로 보이고 3회독 이하도 라이브
 *  캘린더와 색이 달랐던 문제. */
private fun memberCalPassColor(
    stat: com.phonelock.desktop.monitor.SocialGroupSyncClient.ScheduleStat
): Color = Color(com.phonelock.shared.calc.PassSchedule.passColor(stat.passIndex, stat.passTotal))

/**
 * 읽기전용 미니 월 그리드 — 라이브 캘린더([CalendarScreen])와 같은 시각 언어. 날짜 칸을 누르면 그 날의 일정 전체와
 * 그 날 집중 시간을 아래에 펼쳐 보여준다(77차). 122차: 칸 안엔 일정 이름 대신 완료 개수 배지만.
 * 146차: 라이브 캘린더의 144차 개편(테두리 없는 칸, 오늘 = 강조색 원, 고른 날 = 먹색 고리, 상태 색 점 + 개수, 날짜 상세는
 * 가는 선으로 나뉜 줄)을 그대로 따라간다.
 */
@Composable
private fun ReadOnlyMiniCalendar(
    today: LocalDate,
    schedule: List<com.phonelock.desktop.monitor.SocialGroupSyncClient.ScheduleStat>,
    studySecondsByDate: Map<String, Int>
) {
    val year = today.year
    val month = today.monthValue - 1
    val tasksByDate = remember(schedule) { schedule.groupBy { it.dateKey } }
    val firstOfMonth = LocalDate.of(year, month + 1, 1)
    val firstDow = firstOfMonth.dayOfWeek.value % 7
    val daysInMonth = firstOfMonth.lengthOfMonth()
    val rows = (firstDow + daysInMonth + 6) / 7
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val palette = LocalPhoneLockPalette.current
    val sundayColor = MaterialTheme.colorScheme.error
    val saturdayColor = com.phonelock.desktop.ui.components.saturdayInk()

    Column(Modifier.fillMaxWidth()) {
        Overline("${year}년")
        Text(MEMBER_CAL_MONTHS_KO[month], style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth()) {
            MEMBER_CAL_WEEKDAYS_KO.forEachIndexed { i, d ->
                val c = when (i) { 0 -> sundayColor; 6 -> saturdayColor; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = c)
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Hairline()
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNum = row * 7 + col - firstDow + 1
                    if (dayNum in 1..daysInMonth) {
                        val date = LocalDate.of(year, month + 1, dayNum)
                        val dayTasks = tasksByDate[date.toString()].orEmpty()
                        val isToday = date == today
                        val isSelected = selectedDate == date
                        Column(
                            Modifier.weight(1f).height(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                .clickable { selectedDate = if (isSelected) null else date }
                                .padding(top = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val dayNumColor = when {
                                isToday -> MaterialTheme.colorScheme.onPrimary
                                date.dayOfWeek == java.time.DayOfWeek.SUNDAY -> sundayColor
                                date.dayOfWeek == java.time.DayOfWeek.SATURDAY -> saturdayColor
                                else -> MaterialTheme.colorScheme.onBackground
                            }
                            Box(
                                Modifier.size(26.dp)
                                    .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                    .then(if (isSelected && !isToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("$dayNum", style = MaterialTheme.typography.labelLarge, color = dayNumColor, maxLines = 1, softWrap = false)
                            }
                            if (dayTasks.isNotEmpty()) {
                                val doneCount = dayTasks.count { it.status == "O" }
                                val badgeColor = when {
                                    doneCount == dayTasks.size -> palette.fillGood
                                    doneCount > 0 -> palette.fillPartial
                                    else -> palette.fillBad
                                }
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).background(badgeColor, CircleShape))
                                    Spacer(Modifier.width(3.dp))
                                    Text("${dayTasks.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f).height(56.dp))
                    }
                }
            }
        }

        selectedDate?.let { date ->
            val dayTasks = tasksByDate[date.toString()].orEmpty()
            val seconds = studySecondsByDate[date.toString()] ?: 0
            Spacer(Modifier.height(Spacing.md))
            Overline("${date.monthValue}월 ${date.dayOfMonth}일" + if (seconds > 0) " · 집중 ${formatSeconds(seconds)}" else "")
            Spacer(Modifier.height(Spacing.xs))
            Hairline()
            if (dayTasks.isEmpty()) {
                Text("등록된 일정이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.sm))
            } else {
                dayTasks.forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(memberCalPassColor(t), CircleShape))
                        Spacer(Modifier.width(Spacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge)
                            // 143차: 라이브 캘린더 날짜 상세와 같이 이름 아래에 회차와, 그 일정에 실제로 잰 시간을 붙인다.
                            val loggedSeconds = t.studySeconds ?: 0
                            val meta = "${t.passIndex + 1}회차" + if (loggedSeconds > 0) " · ${formatSeconds(loggedSeconds)}" else ""
                            Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when (t.status) {
                            "O" -> Icon(Icons.Filled.Check, contentDescription = "완료", tint = palette.success)
                            "X" -> Icon(Icons.Filled.Close, contentDescription = "미완료", tint = MaterialTheme.colorScheme.error)
                            else -> Text("미완", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Hairline()
                }
            }
        }
    }
}

/** 연속 기록 큰 숫자 — 라이브 루틴·통계 화면과 같은 모양(146차: 불꽃 이모지 대신 강조색 큰 숫자). */
@Composable
private fun StreakHero(label: String, streak: Int, caption: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Overline(label)
        BigNumber(
            "$streak",
            unit = "일",
            style = MaterialTheme.typography.displayMedium,
            color = if (streak > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
        )
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "루틴 - 오늘" 탭 — 라이브 RoutineScreen의 "오늘" 탭과 같은 체크리스트를 보기전용으로. */
@Composable
private fun MemberRoutineTodayTab(s: SocialGroupSyncClient.MemberStats) {
    val routines = s.routines.sortedWith(compareBy(nullsLast()) { it.timeSlot })
    if (routines.isEmpty()) {
        Text("오늘 예정된 루틴이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val doneCount = routines.count { it.doneToday }
    Column(Modifier.fillMaxWidth()) {
        Overline("오늘 완료")
        BigNumber("$doneCount", unit = "/ ${routines.size}", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(Spacing.sm))
        ProgressLine(doneCount.toFloat() / routines.size, color = LocalPhoneLockPalette.current.fillGood)
        Spacer(Modifier.height(Spacing.md))
        Hairline()
        routines.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .background(if (r.doneToday) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .then(if (!r.doneToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (r.doneToday) Icon(Icons.Filled.Check, contentDescription = "완료", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(Spacing.md))
                if (r.icon.isNotBlank()) {
                    Text(r.icon, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = Spacing.xs))
                }
                Text(
                    r.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (r.doneToday) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                if (!r.timeSlot.isNullOrBlank()) {
                    Text(r.timeSlot, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
            Hairline()
        }
    }
}

/**
 * "루틴 - 통계" 탭 — 라이브 화면(RoutineScreen.kt)의 현재/최고 연속 기록 모양을 옮겼다. 7일/30일 추이 그래프는
 * 오늘 루틴 완료여부만 동기화되고 과거 이력은 동기화 대상이 아니라서 뺐다 — 연속 기록/오늘 완료율만 정확히 보여준다.
 */
@Composable
private fun MemberRoutineStatsTab(s: SocialGroupSyncClient.MemberStats) {
    val routines = s.routines
    val doneCount = routines.count { it.doneToday }
    val rate = if (routines.isNotEmpty()) Math.round(doneCount * 100.0 / routines.size).toInt() else 0

    if (!s.shareStreak) {
        Text("연속 기록은 비공개입니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    StreakHero("현재 연속 기록", s.streak)
    Spacer(Modifier.height(Spacing.md))
    Hairline()
    Spacer(Modifier.height(Spacing.md))
    StatRow {
        StatBlock("최고 기록", "${s.routineBestStreak}", Modifier.weight(1f), unit = "일")
        StatBlock("오늘 완료율", "$rate", Modifier.weight(1f), unit = "%")
    }
}

/**
 * "집중 - 일정표" 탭 — 라이브 TimetableScreen(계산기 업무를 요일별 목표량 표로 보여주는 화면)을 그대로 옮긴다(78차).
 * [MemberStats.calcTasks](shareSchedule 토글에 함께 묶임)를 쓰고, 79차: 라이브 화면의 달성(초록) 판정도 이식 —
 * [MemberStats.schedule]에 함께 실려오는 linkedCalc/progressStep으로 [PhoneLockRepository.isLinkedGoalAchieved]와 동일하게.
 */
@Composable
private fun MemberStudyTimetableTab(s: SocialGroupSyncClient.MemberStats, today: LocalDate) {
    var cursor by remember(today) { mutableStateOf(today) }
    val isToday = cursor == today
    val jsDow = cursor.dayOfWeek.value % 7
    val weekdayLabels = listOf("일", "월", "화", "수", "목", "금", "토")
    val dateLabel = "${cursor.monthValue}월 ${cursor.dayOfMonth}일 (${weekdayLabels[jsDow]})" + if (isToday) " · 오늘" else ""
    val palette = LocalPhoneLockPalette.current

    val tasks = s.calcTasks
    val dayTasks = tasks.filter { t ->
        if (t.name.isBlank() || t.dday.isBlank()) return@filter false
        val dday = runCatching { LocalDate.parse(t.dday) }.getOrNull() ?: return@filter false
        val start = if (t.start.isBlank()) today else (runCatching { LocalDate.parse(t.start) }.getOrNull() ?: today)
        !cursor.isBefore(start) && !cursor.isAfter(dday)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(dateLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { cursor = cursor.minusDays(1) }) { Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "이전 날") }
            IconButton(onClick = { cursor = cursor.plusDays(1) }) { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음 날") }
        }
        Hairline()

        if (dayTasks.isEmpty()) {
            Text(
                if (tasks.isEmpty()) "등록된 일정표 업무가 없습니다" else "이 날은 진행 중인 업무가 없습니다",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
        } else {
            var dayTotal = 0.0
            dayTasks.forEach { t ->
                val v = memberTimetableDayValue(t, jsDow).toDoubleOrNull() ?: 0.0
                dayTotal += v
                val achieved = v > 0 && memberIsLinkedGoalAchieved(s, cursor.toString(), t.name, v)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 86차 버그 수정(TimetableScreen.kt와 동일 원인/대칭 수정): weight 없는 SpaceBetween만
                    // 쓰면 이름이 길 때 값 Text가 화면 밖으로 밀려 안 보였다.
                    Text(
                        t.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(end = Spacing.xs)
                    )
                    if (achieved) {
                        Icon(Icons.Filled.Check, contentDescription = "달성", tint = palette.success, modifier = Modifier.size(18.dp).padding(end = 2.dp))
                    }
                    // 139차(양 플랫폼 일정표): 분량은 기본 글자색, 달성만 초록.
                    Text(
                        if (v > 0) "${memberTimetableFmtDec(v)}${t.unit}" else "—",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (v <= 0) MaterialTheme.colorScheme.onSurfaceVariant
                            else if (achieved) palette.success
                            else MaterialTheme.colorScheme.onSurface
                    )
                }
                Hairline()
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("합계", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(memberTimetableFmtDec(dayTotal), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** [Repository.isLinkedGoalAchieved]와 동일 판정을 [MemberStats.schedule](동기화된 캘린더 일정)로 재현한다. */
private fun memberIsLinkedGoalAchieved(member: SocialGroupSyncClient.MemberStats, dateKey: String, calcTaskName: String, dayQuota: Double): Boolean {
    if (dayQuota <= 0) return false
    val doneTotal = member.schedule
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
 * "집중 - 통계" 탭 — 라이브 StudyStatsScreen의 오늘 통계/연속 기록 모양을 옮기되, 전체 이력이 아니라 동기화된
 * 달 범위(±7일 버퍼) 안에서만 계산한다 — 캘린더 탭과 같은 데이터([MemberStats.schedule])를 재사용하는
 * 만큼 정확한 전체 기록이 아니라 "최근" 범위 근사치임을 라벨로 밝혀둔다.
 */
@Composable
private fun MemberStudyStatsTab(s: SocialGroupSyncClient.MemberStats, today: LocalDate) {
    val schedule = s.schedule
    val byDate = remember(schedule) { schedule.groupBy { it.dateKey } }
    val todayTasks = byDate[today.toString()].orEmpty()
    val doneCount = todayTasks.count { it.status == "O" }
    val rate = if (todayTasks.isNotEmpty()) Math.round(doneCount * 100.0 / todayTasks.size).toInt() else 0

    // 143차: 142차에 라이브 통계의 연속 기록이 "캘린더 일정을 전부 완료한 날"에서 "집중 시간이 기록된 날"로 바뀌었고 하루 평균
    // 집중 시간 묶음이 생겼다. 상대 앱이 그 값을 직접 계산해 올려 주면(새 버전) 그대로 보여 주고, 옛 버전이 올린 데이터면
    // 예전 근사치(최근 일정 범위 안의 연속 완료일)로 돌아간다.
    if (s.studyStreak != null) {
        val streak = s.studyStreak
        Column(Modifier.fillMaxWidth()) {
            StreakHero("현재 연속 기록", streak, "집중 시간이 기록된 날이 이어진 일수")
            Spacer(Modifier.height(Spacing.md))
            Hairline()
            Spacer(Modifier.height(Spacing.md))
            StatRow {
                StatBlock("오늘 일정", "$doneCount/${todayTasks.size}", Modifier.weight(1f))
                StatBlock("완료율", "$rate", Modifier.weight(1f), unit = "%")
                StatBlock("최고 기록", "${s.studyBestStreak ?: streak}", Modifier.weight(1f), unit = "일")
            }
            Spacer(Modifier.height(Spacing.lg))
            SectionCard("하루 평균 집중 시간") {
                MemberAverageRow("오늘", StudyStats.durationLabel(s.studyTodaySeconds.toLong()))
                MemberAverageRow("최근 ${StudyStats.SHORT_WINDOW_DAYS}일 평균", s.studyAvgShortSeconds?.let { StudyStats.durationLabel(it.toLong()) } ?: "—")
                MemberAverageRow("최근 ${StudyStats.LONG_WINDOW_DAYS}일 평균", s.studyAvgLongSeconds?.let { StudyStats.durationLabel(it.toLong()) } ?: "—")
                MemberAverageRow("집중한 날 평균", s.studyActiveAvgSeconds?.let { StudyStats.durationLabel(it.toLong()) } ?: "—")
            }
        }
        return
    }

    var streak = 0
    for (i in 0 until 90) {
        val key = today.minusDays(i.toLong()).toString()
        val dayTasks = byDate[key] ?: continue
        if (dayTasks.isEmpty()) continue
        if (dayTasks.count { it.status == "O" } == dayTasks.size) streak++ else break
    }

    Column(Modifier.fillMaxWidth()) {
        StreakHero("연속 완료일(최근 범위 안)", streak)
        Spacer(Modifier.height(Spacing.md))
        Hairline()
        Spacer(Modifier.height(Spacing.md))
        StatRow {
            StatBlock("오늘 일정", "${todayTasks.size}", Modifier.weight(1f), unit = "개")
            StatBlock("완료", "$doneCount", Modifier.weight(1f), unit = "개")
            StatBlock("완료율", "$rate", Modifier.weight(1f), unit = "%")
        }
    }
}

/** 하루 평균 집중 시간 묶음의 "이름 … 값" 줄 — 라이브 통계 화면과 같은 모양(폰 폭에서도 값이 잘리지 않게 줄로 쌓는다). */
@Composable
private fun MemberAverageRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}
