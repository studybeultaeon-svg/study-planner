package com.phonelock.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.delay

private enum class TopSection { MANAGE, STUDY, ROUTINE, SOCIAL_GROUP, SETTINGS }

/**
 * 데스크탑 전용 레이아웃: 왼쪽 사이드바(NavigationRail)로 관리앱/공부앱/설정을 고르고, 관리앱·공부앱은
 * 안에서 다시 서브탭(그룹/통계, 타이머/캘린더/계산기)으로 나뉜다. 넓은 화면을 옆으로 활용하는
 * 데스크탑다운 구조로, 모바일(하단 탭)과는 별개로 유지한다.
 */
@Composable
fun MainScreen(repository: Repository, onThemeChange: (String) -> Unit = {}) {
    // 관리자가 승인 시 지정한 기능 범위(루틴/공부/관리/모임)에 맞춰 보이는 섹션만 남긴다 — 설정은 항상
    // 보임(로그아웃/비밀번호 변경 등을 위해). 옛 승인 사용자는 필드가 없으면 Repository가 전부 true를
    // 기본값으로 주므로 이 필터링으로 인한 회귀는 없다.
    val visibleSections = remember {
        listOfNotNull(
            TopSection.ROUTINE.takeIf { repository.permRoutine },
            TopSection.STUDY.takeIf { repository.permStudy },
            TopSection.MANAGE.takeIf { repository.permManage },
            TopSection.SOCIAL_GROUP.takeIf { repository.permSocial },
            TopSection.SETTINGS
        )
    }
    var section by remember { mutableStateOf(visibleSections.first()) }
    var manageSubTab by remember { mutableIntStateOf(0) }
    var studySubTab by remember { mutableIntStateOf(0) }
    var editingGroupId by remember { mutableStateOf<Long?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf(repository.getGroups()) }
    var extensionWarning by remember { mutableStateOf(false) }
    var selectedSocialGroupId by remember { mutableStateOf<String?>(null) }
    var updateInstallerUrl by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        groups = repository.getGroups()
    }

    // 탭을 옮겼다 와야만 그룹 목록이 새로고침되던 문제를 없애기 위해, 그룹 탭을 보고 있는 동안 주기적으로 다시 읽어온다.
    // 28차 세션의 좌우 분할처럼 그룹 목록은 편집 중에도 항상 왼쪽에 보이므로, 편집 여부와 무관하게 갱신한다.
    LaunchedEffect(section, manageSubTab) {
        if (section == TopSection.MANAGE && manageSubTab == 0) {
            while (true) {
                delay(1000)
                refresh()
            }
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

    val railColors = NavigationRailItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Column(Modifier.fillMaxSize()) {
        // 공부앱(index.html)의 상단 로고 바를 흉내낸 슬림 타이틀 바 — 데스크탑 창의 첫인상을 잡아준다.
        Surface(color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = Spacing.md), contentAlignment = Alignment.CenterStart) {
                Text(
                    "갓생살기종합세트",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                if (TopSection.ROUTINE in visibleSections) {
                    NavigationRailItem(
                        selected = section == TopSection.ROUTINE,
                        onClick = { section = TopSection.ROUTINE },
                        icon = { Text("🌱") },
                        label = { Text("루틴") },
                        colors = railColors
                    )
                }
                if (TopSection.STUDY in visibleSections) {
                    NavigationRailItem(
                        selected = section == TopSection.STUDY,
                        onClick = { section = TopSection.STUDY },
                        icon = { Text("📘") },
                        label = { Text("공부") },
                        colors = railColors
                    )
                }
                if (TopSection.MANAGE in visibleSections) {
                    NavigationRailItem(
                        selected = section == TopSection.MANAGE,
                        onClick = { section = TopSection.MANAGE; refresh() },
                        icon = { Text("🗂️") },
                        label = { Text("관리") },
                        colors = railColors
                    )
                }
                if (TopSection.SOCIAL_GROUP in visibleSections) {
                    NavigationRailItem(
                        selected = section == TopSection.SOCIAL_GROUP,
                        onClick = { section = TopSection.SOCIAL_GROUP },
                        icon = { Text("👥") },
                        label = { Text("모임") },
                        colors = railColors
                    )
                }
                NavigationRailItem(
                    selected = section == TopSection.SETTINGS,
                    onClick = { section = TopSection.SETTINGS },
                    icon = { Text("⚙️") },
                    label = { Text("설정") },
                    colors = railColors
                )
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                updateInstallerUrl?.let { url -> UpdateBanner(repository, url) }
                if (extensionWarning) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            "⚠ 브라우저 확장프로그램과 연결이 끊겼습니다 — 사이트 차단이 동작하지 않을 수 있습니다. " +
                                "확장프로그램이 켜져 있는지, 시크릿 창이 아닌지 확인하세요.",
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                when (section) {
                    TopSection.MANAGE -> {
                        TabRow(
                            selectedTabIndex = manageSubTab,
                            containerColor = MaterialTheme.colorScheme.background,
                            contentColor = MaterialTheme.colorScheme.onBackground
                        ) {
                            Tab(
                                selected = manageSubTab == 0,
                                onClick = { manageSubTab = 0; editingGroupId = null; isCreatingNew = false; refresh() },
                                text = { Text("그룹") }
                            )
                            Tab(selected = manageSubTab == 1, onClick = { manageSubTab = 1; refresh() }, text = { Text("통계") })
                        }
                        Box(Modifier.weight(1f)) {
                            when (manageSubTab) {
                                // 28차 세션의 타이머/캘린더/계산기와 같은 좌우 분할(마스터-디테일):
                                // 왼쪽은 항상 그룹 목록, 오른쪽은 선택한 그룹의 편집 폼(선택 없으면 안내문).
                                0 -> {
                                    Row(Modifier.fillMaxSize()) {
                                        Column(Modifier.weight(1f).fillMaxHeight()) {
                                            GroupListScreen(
                                                repository = repository,
                                                groups = groups,
                                                selectedGroupId = if (isCreatingNew) null else editingGroupId,
                                                onAddClick = { isCreatingNew = true; editingGroupId = null },
                                                onEditClick = { isCreatingNew = false; editingGroupId = it }
                                            )
                                        }
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
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Text(
                                                        "그룹을 선택하면 여기서 편집할 수 있습니다.",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                1 -> StatsScreen(repository)
                            }
                        }
                    }
                    TopSection.STUDY -> {
                        TabRow(
                            selectedTabIndex = studySubTab,
                            containerColor = MaterialTheme.colorScheme.background,
                            contentColor = MaterialTheme.colorScheme.onBackground
                        ) {
                            Tab(selected = studySubTab == 0, onClick = { studySubTab = 0 }, text = { Text("⏱️ 시간 측정") })
                            Tab(selected = studySubTab == 1, onClick = { studySubTab = 1 }, text = { Text("📅 캘린더") })
                            Tab(selected = studySubTab == 2, onClick = { studySubTab = 2 }, text = { Text("🧮 계산기") })
                            Tab(selected = studySubTab == 3, onClick = { studySubTab = 3 }, text = { Text("🗓️ 일정표") })
                            Tab(selected = studySubTab == 4, onClick = { studySubTab = 4 }, text = { Text("📈 통계") })
                        }
                        Box(Modifier.weight(1f)) {
                            when (studySubTab) {
                                0 -> StudyTimerScreen(repository)
                                1 -> CalendarScreen(repository)
                                2 -> CalculatorScreen(repository)
                                3 -> TimetableScreen(repository)
                                4 -> StudyStatsScreen(repository)
                            }
                        }
                    }
                    TopSection.ROUTINE -> {
                        Box(Modifier.weight(1f)) {
                            RoutineScreen(repository)
                        }
                    }
                    TopSection.SOCIAL_GROUP -> {
                        Box(Modifier.weight(1f)) {
                            val groupId = selectedSocialGroupId
                            if (groupId != null) {
                                SocialGroupMembersScreen(repository, groupId, onBack = { selectedSocialGroupId = null })
                            } else {
                                SocialGroupScreen(repository, onSelectGroup = { selectedSocialGroupId = it })
                            }
                        }
                    }
                    TopSection.SETTINGS -> {
                        Box(Modifier.weight(1f)) {
                            SettingsScreen(repository, onThemeChange = onThemeChange)
                        }
                    }
                }
            }
        }
    }
}
