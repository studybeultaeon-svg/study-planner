package com.phonelock.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.phonelock.desktop.ui.components.LedgerNavItem
import com.phonelock.desktop.ui.components.LedgerNavRail
import com.phonelock.desktop.ui.components.NoticeStrip
import com.phonelock.desktop.ui.components.Overline
import com.phonelock.desktop.ui.components.PageMasthead
import com.phonelock.desktop.ui.components.SectionTabs
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.isEffectivelyOffline
import com.phonelock.desktop.data.syncGroupSettingsFromFirebase
import com.phonelock.desktop.data.syncGrowthFromFirebase
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 관리 탭 안의 "타이머" 서브탭 번호 — 전체 잠금 화면의 "타이머 열기"가 쓴다(142차). 143차: 차단 규칙(0)과 사용 기록 사이로 옮겼다. */
const val MANAGE_SUB_TAB_TIMER = 1
private const val MANAGE_SUB_TAB_STATS = 2

private enum class TopSection { HOME, ROUTINE, STUDY, MANAGE, SOCIAL_GROUP }

/**
 * 데스크탑 전용 레이아웃: 왼쪽 레일(144차부터 [LedgerNavRail])로 관리앱/공부앱/설정을 고르고, 관리앱·공부앱은
 * 안에서 다시 서브탭(그룹/통계, 타이머/캘린더/계산기)으로 나뉜다. 넓은 화면을 옆으로 활용하는
 * 데스크탑다운 구조로, 모바일(하단 탭)과는 별개로 유지한다.
 */
@Composable
fun MainScreen(
    repository: Repository,
    onThemeChange: (String) -> Unit = {},
    /** 전체 잠금 화면에서 "타이머 열기"/"차단 규칙 열기"를 누를 때마다 올라가는 번호와, 그때 열 관리 서브탭(142차). */
    openManageSeq: Int = 0,
    openManageSubTab: Int = 0
) {
    // 관리자가 승인 시 지정한 기능 범위(루틴/공부/관리/모임)에 맞춰 보이는 섹션만 남긴다 — 설정은 항상
    // 보임(로그아웃/비밀번호 변경 등을 위해). 옛 승인 사용자는 필드가 없으면 Repository가 전부 true를
    // 기본값으로 주므로 이 필터링으로 인한 회귀는 없다.
    val visibleSections = remember {
        listOfNotNull(
            // 홈(구 "식물")은 항상 맨 앞 — 설정 진입점(우상단 원형 버튼)이 이 화면에만 있으므로 절대 숨기지
            // 않는다. permPlant가 꺼진 사용자는 HOME 화면 안에서 식물 성장 콘텐츠 대신 안내문+설정 버튼만 봄.
            TopSection.HOME,
            TopSection.ROUTINE.takeIf { repository.permRoutine },
            TopSection.STUDY.takeIf { repository.permStudy },
            TopSection.MANAGE.takeIf { repository.permManage },
            // 98차(사용자 요청, 안드로이드판과 대칭): 게스트(익명 계정)는 소셜 탭을 아예 못 쓰게 한다.
            TopSection.SOCIAL_GROUP.takeIf { repository.permSocial && !com.phonelock.desktop.monitor.AuthManager.isAnonymous }
        )
    }
    var section by remember { mutableStateOf(visibleSections.first()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var manageSubTab by remember { mutableIntStateOf(0) }
    var studySubTab by remember { mutableIntStateOf(0) }
    var editingGroupId by remember { mutableStateOf<Long?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf(repository.getGroups()) }
    var extensionWarning by remember { mutableStateOf(false) }
    var selectedSocialGroupId by remember { mutableStateOf<String?>(null) }
    // 92차 소셜 개편 Phase 2: 1:1 DM 채팅방 진입 상태(chatId, peerUid, peerLabel).
    var selectedDmChat by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var updateInstallerUrl by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        groups = repository.getGroups()
    }

    // 전체 잠금 화면이 보낸 요청 — 관리 탭의 그 서브탭으로 바로 간다.
    LaunchedEffect(openManageSeq) {
        if (openManageSeq > 0 && TopSection.MANAGE in visibleSections) {
            settingsOpen = false
            section = TopSection.MANAGE
            manageSubTab = openManageSubTab
            refresh()
        }
    }

    // 탭을 옮겼다 와야만 그룹 목록이 새로고침되던 문제를 없애기 위해, 그룹 탭을 보고 있는 동안 주기적으로 다시 읽어온다.
    // 28차 세션의 좌우 분할처럼 그룹 목록은 편집 중에도 항상 왼쪽에 보이므로, 편집 여부와 무관하게 갱신한다.
    LaunchedEffect(section, manageSubTab) {
        if (section == TopSection.MANAGE && manageSubTab == 0) {
            // 그룹 탭 진입 시 1회 그룹 설정(제어할 앱/사이트·groupEnabled 등 제외) 동기화 — RoutineScreen의
            // syncRoutinesFromFirebase() 진입 시 호출과 동일 패턴(87차+).
            withContext(Dispatchers.IO) { if (!repository.isEffectivelyOffline()) repository.syncGroupSettingsFromFirebase() }
            refresh()
            while (true) {
                delay(1000)
                refresh()
            }
        }
    }

    // 121차: 성장(레벨/EXP/장식)을 시작할 때 한 번 받아온다 — 이 기기가 원격 상태를 모르는 채로 먼저
    // EXP를 적립해 올려버리면 다른 기기가 쌓아둔 레벨을 덮어쓸 수 있다(안드로이드판 MainActivity와 대칭).
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (!repository.isEffectivelyOffline()) repository.syncGrowthFromFirebase()
        }
    }

    // 사이트가 등록된 그룹이 있는데 브라우저 확장의 heartbeat가 끊겼으면(확장 비활성화/삭제/시크릿
    // 모드 전환 등) 사용자가 눈치채지 못한 채 사이트 차단이 무력화될 수 있으므로 배너로 알려준다.
    LaunchedEffect(Unit) {
        while (true) {
            extensionWarning = repository.hasSiteGroups() && repository.isExtensionHeartbeatStale()
            delay(5000)
        }
    }

    // EnforcementService.tick()이 하루 1회 GitHub Releases를 확인해 남겨둔 값을 폴링만 한다(네트워크
    // 호출 없음, Repository.checkForUpdateIfNeeded 참고).
    LaunchedEffect(Unit) {
        while (true) {
            updateInstallerUrl = repository.pendingUpdateInstallerUrl()
            delay(30_000)
        }
    }

    // 144차 리디자인(안드로이드판과 같은 언어): 상단 로고 바와 Material 레일 대신 "갓생" 워드마크가 있는 레일 + 강조 막대,
    // 섹션마다 편집형 머리(큰 제목) + 텍스트 탭. 섹션·서브탭이 바뀔 때 짧게 페이드/슬라이드(성능 모드에선 페이드만).
    val motion = com.phonelock.desktop.ui.theme.LocalAppMotion.current
    val navItems = remember(visibleSections) {
        visibleSections.map { s ->
            when (s) {
                TopSection.HOME -> LedgerNavItem(s.name, "홈", Icons.Outlined.StarOutline, Icons.Filled.Star)
                TopSection.ROUTINE -> LedgerNavItem(s.name, "루틴", Icons.Outlined.TaskAlt, Icons.Filled.TaskAlt)
                TopSection.STUDY -> LedgerNavItem(s.name, "집중", Icons.Outlined.Timer, Icons.Filled.Timer)
                TopSection.MANAGE -> LedgerNavItem(s.name, "관리", Icons.Outlined.Shield, Icons.Filled.Shield)
                TopSection.SOCIAL_GROUP -> LedgerNavItem(s.name, "모임", Icons.Outlined.Groups, Icons.Filled.Groups)
            }
        }
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LedgerNavRail(
            navItems,
            selectedKey = if (settingsOpen) null else section.name,
            onSelect = { item ->
                val target = TopSection.valueOf(item.key)
                section = target
                settingsOpen = false
                if (target == TopSection.MANAGE) refresh()
            }
        )

        Column(Modifier.weight(1f).fillMaxHeight()) {
            updateInstallerUrl?.let { url -> UpdateBanner(repository, url) }
            if (extensionWarning) {
                NoticeStrip(
                    "브라우저 확장프로그램과 연결이 끊겼습니다 — 사이트 차단이 동작하지 않을 수 있습니다. 확장프로그램이 켜져 있는지, 시크릿 창이 아닌지 확인하세요.",
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
            }

            AnimatedContent(
                targetState = if (settingsOpen) null else section,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn(motion.standard()) togetherWith fadeOut(motion.exit()) },
                label = "desktopSection"
            ) { shown ->
                Column(Modifier.fillMaxSize()) {
                    when (shown) {
                        null -> Box(Modifier.weight(1f)) {
                            SettingsScreen(
                                repository,
                                onThemeChange = onThemeChange,
                                onClose = { settingsOpen = false }
                            )
                        }
                        TopSection.MANAGE -> {
                            PageMasthead(title = "관리", overline = "차단 · 타이머 · 기록")
                            SectionTabs(
                                listOf("차단 규칙", "타이머", "사용 기록"),
                                manageSubTab,
                                { tab ->
                                    manageSubTab = tab
                                    if (tab == 0) { editingGroupId = null; isCreatingNew = false }
                                    if (tab != MANAGE_SUB_TAB_TIMER) refresh()
                                }
                            )
                            DesktopSubTabContent(manageSubTab, Modifier.weight(1f)) { tab ->
                                when (tab) {
                                    // 28차 세션의 좌우 분할(마스터-디테일): 왼쪽은 항상 그룹 목록, 오른쪽은 선택한 그룹의 편집 폼.
                                    0 -> Row(Modifier.fillMaxSize()) {
                                        Column(Modifier.weight(1f).fillMaxHeight()) {
                                            GroupListScreen(
                                                repository = repository,
                                                groups = groups,
                                                selectedGroupId = if (isCreatingNew) null else editingGroupId,
                                                onAddClick = { isCreatingNew = true; editingGroupId = null },
                                                onEditClick = { isCreatingNew = false; editingGroupId = it }
                                            )
                                        }
                                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Column(Modifier.weight(1f).fillMaxHeight()) {
                                            if (isCreatingNew || editingGroupId != null) {
                                                GroupEditScreen(
                                                    repository = repository,
                                                    groupId = editingGroupId,
                                                    onDone = {
                                                        isCreatingNew = false
                                                        editingGroupId = null
                                                        refresh()
                                                    }
                                                )
                                            } else {
                                                Column(Modifier.fillMaxSize().padding(Spacing.xl), verticalArrangement = Arrangement.Center) {
                                                    Overline("편집")
                                                    Text("왼쪽에서 차단 규칙을 고르면\n여기서 편집할 수 있습니다.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                    MANAGE_SUB_TAB_STATS -> StatsScreen(repository)
                                    MANAGE_SUB_TAB_TIMER -> LockTimerScreen(repository)
                                }
                            }
                        }
                        TopSection.STUDY -> {
                            val todayLabel = remember {
                                java.time.LocalDate.parse(repository.todayCalendarDateKey())
                                    .format(java.time.format.DateTimeFormatter.ofPattern("M월 d일 EEEE", java.util.Locale.KOREAN))
                            }
                            PageMasthead(title = "집중", overline = todayLabel)
                            SectionTabs(listOf("타이머", "캘린더", "계산기", "일정표", "통계"), studySubTab, { studySubTab = it })
                            DesktopSubTabContent(studySubTab, Modifier.weight(1f)) { tab ->
                                when (tab) {
                                    0 -> StudyTimerScreen(repository)
                                    1 -> CalendarScreen(repository)
                                    2 -> CalculatorScreen(repository)
                                    3 -> TimetableScreen(repository)
                                    4 -> StudyStatsScreen(repository)
                                }
                            }
                        }
                        TopSection.ROUTINE -> Box(Modifier.weight(1f)) { RoutineScreen(repository) }
                        TopSection.HOME -> Box(Modifier.weight(1f)) {
                            PlantScreen(
                                repository,
                                permPlant = repository.permPlant,
                                onOpenSettings = { settingsOpen = true }
                            )
                        }
                        TopSection.SOCIAL_GROUP -> Box(Modifier.weight(1f)) {
                            val dmChat = selectedDmChat
                            val groupId = selectedSocialGroupId
                            if (dmChat != null) {
                                val (chatId, peerUid, peerLabel) = dmChat
                                DmChatScreen(repository, chatId, peerUid, peerLabel, onBack = { selectedDmChat = null })
                            } else if (groupId != null) {
                                SocialGroupMembersScreen(
                                    repository, groupId,
                                    onOpenDm = { chatId, peerUid, peerLabel -> selectedDmChat = Triple(chatId, peerUid, peerLabel) },
                                    onBack = { selectedSocialGroupId = null }
                                )
                            } else {
                                SocialGroupScreen(
                                    repository,
                                    onSelectGroup = { selectedSocialGroupId = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 서브탭 내용 전환 — 고른 쪽으로 살짝(1/16 폭) 밀리며 바뀐다(안드로이드판 SectionContent와 같은 규칙, 성능 모드에선 페이드만). */
@Composable
private fun DesktopSubTabContent(subTab: Int, modifier: Modifier, content: @Composable (Int) -> Unit) {
    val motion = com.phonelock.desktop.ui.theme.LocalAppMotion.current
    AnimatedContent(
        targetState = subTab,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val forward = targetState > initialState
            if (motion.reduced) {
                fadeIn(motion.standard()) togetherWith fadeOut(motion.exit())
            } else {
                (fadeIn(motion.standard()) + slideInHorizontally(motion.standard()) { w -> (if (forward) w else -w) / 16 }) togetherWith
                    (fadeOut(motion.exit()) + slideOutHorizontally(motion.exit()) { w -> (if (forward) -w else w) / 16 })
            }
        },
        label = "desktopSubTab"
    ) { tab ->
        Box(Modifier.fillMaxSize()) { content(tab) }
    }
}
