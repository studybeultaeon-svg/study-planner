package com.phonelock.desktop.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.phonelock.desktop.data.Repository
// 루틴/캘린더/공부기록 조회는 Repository 본체가 아니라 파일별 확장 함수로 나뉘어 있어(다른 화면들과
// 동일하게) 패키지 전체를 가져온다.
import com.phonelock.desktop.data.*
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.LockEvaluator
import com.phonelock.desktop.monitor.SocialGroupSyncClient
import com.phonelock.desktop.routine.RoutineEngine
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * "🏠 홈" — 앱을 열었을 때 가장 먼저 보이는 오늘 요약 화면(90차 신설). 새 데이터/판정 로직을 만들지 않고
 * 이미 각 섹션이 쓰고 있는 조회 함수만 다시 호출해서 텍스트로 요약한다(타이머 탭의 "오늘 한눈에"와 같은
 * 성격의 순수 파생 뷰). 카드를 누르면 해당 섹션으로 이동한다.
 *
 * 보이는 카드는 MainScreen의 visibleSections(권한 필터링)와 같은 기준으로 상위에서 켜고 끈다 — 안 쓰는
 * 기능의 카드는 아예 렌더링하지 않는다.
 */
@Composable
fun HomeScreen(
    repository: Repository,
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
    var tick by remember { mutableIntStateOf(0) }

    // 관리(제한 중 그룹)와 루틴/공부는 로컬 조회라 가볍다 — 30초마다만 다시 읽어 화면을 최신으로 유지한다.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }

    // --- 관리: 지금 실제로 제한이 걸려 있는 그룹(그룹 목록 배지와 동일한 판정) ---
    val restrictingNames = remember(tick) {
        if (!showManage) emptyList() else repository.getGroups()
            .filter { it.groupEnabled && !evaluator.isSnoozeActive(it) && evaluator.isAnyManagementActiveToday(it) }
            .map { it.name }
    }

    // --- 루틴: 오늘 완료/전체 + 현재 스트릭 ---
    var routineDone by remember { mutableIntStateOf(0) }
    var routineTotal by remember { mutableIntStateOf(0) }
    var routineStreak by remember { mutableIntStateOf(0) }
    LaunchedEffect(showRoutine, tick) {
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
    LaunchedEffect(showStudy, tick) {
        if (!showStudy) return@LaunchedEffect
        studySeconds = repository.getTodayStudyLog().sumOf { it.seconds }.toLong()
        val tasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
        calendarTotal = tasks.size
        calendarDone = tasks.count { it.status == "O" }
    }

    // --- 모임: 내가 속한 모임 수 + 오늘 아직 아무것도 안 한 멤버 수(SocialGroupScreen과 같은 방식으로 조회) ---
    var socialGroupCount by remember { mutableIntStateOf(0) }
    var socialNotDoneCount by remember { mutableIntStateOf(0) }
    var socialUnavailable by remember { mutableStateOf(false) }
    LaunchedEffect(showSocial) {
        if (!showSocial) return@LaunchedEffect
        val url = repository.fbDatabaseUrl
        val key = repository.fbApiKey
        if (!AuthManager.isSignedIn || url.isNullOrBlank() || key.isNullOrBlank()) {
            socialUnavailable = true
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val ids = SocialGroupSyncClient.readMyGroupIds(url, key)
            var notDone = 0
            ids.forEach { id ->
                val stats = SocialGroupSyncClient.readGroupStats(url, key, id)
                // SocialGroupMembersScreen의 notDoneCount와 같은 판정: 루틴을 공유하고 있는데 오늘 하나도 안 한 사람.
                notDone += stats.count { m ->
                    m.shareRoutines && m.routines.isNotEmpty() && m.routines.none { r -> r.doneToday }
                }
            }
            socialGroupCount = ids.size
            socialNotDoneCount = notDone
        }
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
                    socialUnavailable -> HomeValueText("로그인하면 모임 현황이 보입니다")
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
