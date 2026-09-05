package com.phonelock.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.phonelock.app.data.PhoneLockRepository
// 루틴/캘린더/공부기록/모임 조회는 Repository 본체가 아니라 파일별 확장 함수로 나뉘어 있어(다른 화면들과
// 동일하게) 패키지 전체를 가져온다.
import com.phonelock.app.data.*
import com.phonelock.app.routine.RoutineEngine
import com.phonelock.app.service.LockEvaluator
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.theme.Spacing
import java.time.LocalDate

/**
 * "🏠 홈" — 앱을 열었을 때 가장 먼저 보이는 오늘 요약 화면(90차 신설, 데스크탑 HomeScreen.kt와 대칭).
 * 새 데이터/판정 로직을 만들지 않고 이미 각 섹션이 쓰고 있는 조회 함수만 다시 호출해서 텍스트로 요약한다.
 * 카드를 누르면 해당 섹션으로 이동한다. 보이는 카드는 MainActivity의 visibleTabs(권한 필터링)와 같은
 * 기준으로 상위에서 켜고 끈다.
 */
@Composable
fun HomeScreen(
    repository: PhoneLockRepository,
    showManage: Boolean,
    showStudy: Boolean,
    showRoutine: Boolean,
    showSocial: Boolean,
    onGoManage: () -> Unit,
    onGoStudy: () -> Unit,
    onGoRoutine: () -> Unit,
    onGoSocial: () -> Unit
) {
    val today = remember { LocalDate.now() }
    val evaluator = remember { LockEvaluator(repository) }

    // --- 관리: 지금 실제로 제한이 걸려 있는 그룹(그룹 목록 배지와 동일한 판정) ---
    val groups by repository.observeGroups().collectAsState(initial = emptyList())
    val restrictingNames = groups
        .filter { it.groupEnabled && !evaluator.isSnoozeActive(it) && evaluator.isAnyManagementActiveToday(it) }
        .map { it.name }

    // --- 루틴: 오늘 완료/전체 + 현재 스트릭 ---
    var routineDone by remember { mutableIntStateOf(0) }
    var routineTotal by remember { mutableIntStateOf(0) }
    var routineStreak by remember { mutableIntStateOf(0) }
    LaunchedEffect(showRoutine) {
        if (!showRoutine) return@LaunchedEffect
        val routines = repository.getRoutines()
        val completedByRoutine = routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
        val scheduled = routines.filter { isScheduledOn(it, today) }
        routineTotal = scheduled.size
        routineDone = scheduled.count { today.toString() in (completedByRoutine[it.id] ?: emptySet()) }
        routineStreak = RoutineEngine.currentStreak(routines, completedByRoutine, today)
    }

    // --- 공부: 오늘 누적 공부시간 + 오늘 캘린더 일정 완료/전체 ---
    var studySeconds by remember { mutableStateOf(0L) }
    var calendarDone by remember { mutableIntStateOf(0) }
    var calendarTotal by remember { mutableIntStateOf(0) }
    LaunchedEffect(showStudy) {
        if (!showStudy) return@LaunchedEffect
        studySeconds = repository.getTodayStudyLog().sumOf { it.seconds }.toLong()
        val tasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
        calendarTotal = tasks.size
        calendarDone = tasks.count { it.status == "O" }
    }

    // --- 모임: 내가 속한 모임 수 + 오늘 아직 아무것도 안 한 멤버 수(SocialGroupScreen과 같은 조회 경로) ---
    var socialGroupCount by remember { mutableIntStateOf(0) }
    var socialNotDoneCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(showSocial) {
        if (!showSocial) return@LaunchedEffect
        val ids = repository.readMySocialGroupIds()
        var notDone = 0
        ids.forEach { id ->
            // SocialGroupMembersScreen의 notDoneCount와 같은 판정: 루틴을 공유하고 있는데 오늘 하나도 안 한 사람.
            notDone += repository.readSocialGroupStats(id).count { m ->
                val routines = m.routines
                m.shareRoutines && !routines.isNullOrEmpty() && routines.none { r -> r.doneToday }
            }
        }
        socialGroupCount = ids.size
        socialNotDoneCount = notDone
    }

    Column(Modifier.fillMaxSize().padding(Spacing.md).verticalScroll(rememberScrollState())) {
        Text("🏠 홈", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "${today.monthValue}월 ${today.dayOfMonth}일 · 오늘 상태 요약",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))

        if (showManage) {
            SectionCard("🗂️ 관리", modifier = Modifier.clickable { onGoManage() }) {
                if (restrictingNames.isEmpty()) {
                    HomeValueText("오늘 차단 중인 규칙 없음")
                } else {
                    HomeValueText("오늘 차단 중인 규칙 ${restrictingNames.size}개")
                    Spacer(Modifier.height(Spacing.xs))
                    HomeSubText(restrictingNames.joinToString(", "))
                }
                HomeGoHint()
            }
            Spacer(Modifier.height(Spacing.md))
        }

        if (showRoutine) {
            SectionCard("🌱 루틴", modifier = Modifier.clickable { onGoRoutine() }) {
                if (routineTotal == 0) {
                    HomeValueText("오늘 예정된 루틴 없음")
                } else {
                    HomeValueText("오늘 $routineDone / $routineTotal 완료")
                }
                Spacer(Modifier.height(Spacing.xs))
                HomeSubText("🔥 현재 연속 기록 ${routineStreak}일")
                HomeGoHint()
            }
            Spacer(Modifier.height(Spacing.md))
        }

        if (showStudy) {
            SectionCard("📘 공부", modifier = Modifier.clickable { onGoStudy() }) {
                HomeValueText("오늘 공부 시간 ${formatHmsLog(studySeconds)}")
                Spacer(Modifier.height(Spacing.xs))
                HomeSubText(
                    if (calendarTotal == 0) "오늘 캘린더 일정 없음"
                    else "오늘 캘린더 일정 $calendarDone / $calendarTotal 완료"
                )
                HomeGoHint()
            }
            Spacer(Modifier.height(Spacing.md))
        }

        if (showSocial) {
            SectionCard("👥 모임", modifier = Modifier.clickable { onGoSocial() }) {
                when {
                    socialGroupCount == 0 -> HomeValueText("참여 중인 모임 없음")
                    socialNotDoneCount > 0 -> {
                        HomeValueText("오늘 아직 안 한 사람 ${socialNotDoneCount}명")
                        Spacer(Modifier.height(Spacing.xs))
                        HomeSubText("참여 중인 모임 ${socialGroupCount}개")
                    }
                    else -> {
                        HomeValueText("모두 오늘 몫을 하고 있습니다")
                        Spacer(Modifier.height(Spacing.xs))
                        HomeSubText("참여 중인 모임 ${socialGroupCount}개")
                    }
                }
                HomeGoHint()
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun HomeValueText(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun HomeSubText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 카드 전체가 눌린다는 걸 알려주는 작은 힌트 — 별도 버튼을 두지 않고 카드 자체를 진입점으로 쓴다. */
@Composable
private fun HomeGoHint() {
    Spacer(Modifier.height(Spacing.sm))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text("바로가기 →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}
