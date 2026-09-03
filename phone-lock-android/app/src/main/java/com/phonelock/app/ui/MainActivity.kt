package com.phonelock.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab as MaterialTab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.*
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.PreMigrationBackup
import com.phonelock.app.routine.GroupNudgeWorker
import com.phonelock.app.routine.RoutineAlarmScheduler
import com.phonelock.app.service.AccessibilityWatchdogWorker
import com.phonelock.app.ui.theme.PhoneLockTheme
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** 하단 탭은 "관리앱"(그룹/통계)/"공부앱"(타이머/캘린더/계산기)/"설정" 3개로만 두고, 그 안을
 * 서브탭으로 나눈다 — 데스크탑판(왼쪽 사이드바 + 서브탭)과 같은 2단 구조를 모바일에서는 하단 탭으로 구현. */
private sealed class Tab(val route: String, val label: String, val emoji: String) {
    object Manage : Tab("manage", "관리", "🗂️")
    object Study : Tab("study", "공부", "📘")
    object Routine : Tab("routine", "루틴", "🌱")
    object Group : Tab("group", "모임", "👥")
    object Settings : Tab("settings", "설정", "⚙️")
}

/** 관리자가 승인 시 지정한 기능 범위(루틴/공부/관리/모임)에 맞춰 보이는 탭만 남긴다 — 설정은 항상 보임
 *  (로그아웃/비밀번호 변경 등을 위해). 옛 승인 사용자는 필드가 없으면 [AppPreferences]가 전부 true를
 *  기본값으로 주므로 이 필터링으로 인한 회귀는 없다. */
private fun visibleTabs(prefs: AppPreferences): List<Tab> = listOfNotNull(
    Tab.Routine.takeIf { prefs.permRoutine },
    Tab.Study.takeIf { prefs.permStudy },
    Tab.Manage.takeIf { prefs.permManage },
    Tab.Group.takeIf { prefs.permSocial },
    Tab.Settings
)

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 거부해도 앱의 다른 기능에는 지장 없음 — 그냥 알림을 못 받을 뿐 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Room(=PhoneLockRepository)을 열기 전에 먼저 실행해야 한다 — Room이 fallbackToDestructiveMigration()을
        // 쓰고 있어서, 앱 버전이 바뀐 뒤 Room을 처음 여는 순간 스키마가 안 맞으면 DB가 통째로 삭제된다.
        // 그 삭제가 일어나기 전에 원본 SQLite 파일을 직접 읽어 백업해둔다.
        val backupFile = PreMigrationBackup.backupIfVersionChanged(applicationContext)
        if (backupFile != null) {
            android.widget.Toast.makeText(
                this,
                "앱이 업데이트되어 이전 데이터를 자동 백업했습니다",
                android.widget.Toast.LENGTH_LONG
            ).show()
            // Room이 destructive migration으로 로컬 DB를 지웠어도 캘린더/루틴/계산기 동기화 타임스탬프는
            // SharedPreferences라 살아남아 다음 동기화가 "이미 최신"으로 오판할 수 있다 — 강제로 리셋해서
            // 무조건 원격에서 다시 받아오게 한다(52차 발견, AppPreferences.resetSyncTimestamps 참고).
            AppPreferences(applicationContext).resetSyncTimestamps()
        }
        val repository = PhoneLockRepository(applicationContext)

        val watchdogRequest = PeriodicWorkRequestBuilder<AccessibilityWatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "accessibility_watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            watchdogRequest
        )

        // "모임" 넛지("깨우기") 폴링 — WorkManager 최소 주기(15분)라 실시간 알림은 아니다(계획 문서에 고지된 한계).
        val groupNudgeRequest = PeriodicWorkRequestBuilder<GroupNudgeWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "group_nudge",
            ExistingPeriodicWorkPolicy.KEEP,
            groupNudgeRequest
        )

        // "무전기" — 켜짐/모드/일정이 모임마다 다를 수 있어(그룹 설정 화면에서 관리) 서비스 자체는
        // 항상 띄워두고, 폴링할 때마다 모임별 설정을 따로 조회해서 처리한다.
        com.phonelock.app.service.WalkieTalkieService.start(applicationContext)

        val requestNotificationPermissionIfNeeded = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // 온보딩(82차, §6)을 이미 본 사용자는 그대로 바로 요청 — 최초 실행 사용자만 아래 다이얼로그를 먼저 본다.
        if (AppPreferences(applicationContext).onboardingShown) {
            requestNotificationPermissionIfNeeded()
        }

        // 알림 예약은 재부팅 시 초기화되므로, 부팅 리시버뿐 아니라 앱을 열 때마다도 다시 걸어준다(52차).
        // 56차: rescheduleAll은 로컬 DB만 보므로, 다른 기기에서 만들거나 수정한 루틴은 "루틴" 탭을 직접
        // 열기 전엔 반영이 안 돼 알림이 아예 예약되지 않는 문제가 있었음 — 여기서도 먼저 동기화한다.
        lifecycleScope.launch {
            // 루틴 동기화 때마다 예약 알람이 취소 안 되고 계속 쌓이던 버그(2026-08-30, 앱당 500개 한도에
            // 걸려 크래시 루프까지 났었음)로 이미 쌓인 알람을 한 번 정리한다 — 원인 자체는 고쳤지만
            // 기존에 쌓인 건 남아있으므로 한 번은 쓸어줘야 한다. IPC 호출이 많아 IO 디스패처에서.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                RoutineAlarmScheduler.cleanupLeakedAlarmsIfNeeded(applicationContext, AppPreferences(applicationContext))
            }
            repository.syncRoutinesFromFirebase()
            RoutineAlarmScheduler.rescheduleAll(applicationContext, repository)
            if (AppPreferences(applicationContext).routineStreakNotifyEnabled) {
                RoutineAlarmScheduler.scheduleStreakCheck(applicationContext)
            }
            // 무작위 알림(77차)은 모임별 켜짐 여부를 실제 체크 시점에 판단하므로(모임마다 다를 수 있어
            // 전역 스위치가 없음) 여기선 무조건 예약한다 — 켠 모임이 하나도 없으면 체크가 아무것도
            // 안 보낼 뿐, 알람 자체는 스트릭 알림과 같은 비용으로 하루 한 번만 돈다.
            RoutineAlarmScheduler.scheduleGroupNudgeCheck(applicationContext)
            // 주간 요약 알림(82차) — 스트릭/무작위 알림과 같은 비용으로 매주 일요일 20시 한 번만 돈다.
            RoutineAlarmScheduler.scheduleWeeklySummary(applicationContext)
        }

        setContent {
            var themeMode by remember { mutableStateOf(AppPreferences(applicationContext).themeMode) }
            // 79차: 커스텀 테마는 themeMode 문자열("CUSTOM")이 안 바뀌어도 색만 바뀔 수 있어서 별도
            // 카운터로 강제 재계산(데스크탑판 Main.kt와 동일 패턴).
            var themeRefreshTick by remember { mutableStateOf(0) }
            val prefs = remember(themeRefreshTick) { AppPreferences(applicationContext) }
            var showOnboarding by remember { mutableStateOf(!AppPreferences(applicationContext).onboardingShown) }
            PhoneLockTheme(themeMode, prefs.customThemeBackground, prefs.customThemeAccent, prefs.fontScale) {
                Surface(modifier = Modifier) {
                    AccountGate(repository) {
                        PhoneLockApp(repository, onThemeChange = { themeMode = it; themeRefreshTick++ })
                    }
                }
                if (showOnboarding) {
                    OnboardingDialog(
                        onDismiss = {
                            AppPreferences(applicationContext).onboardingShown = true
                            showOnboarding = false
                            requestNotificationPermissionIfNeeded()
                        }
                    )
                }
            }
        }
    }
}

/** 최초 실행 시 왜 이런 권한들이 필요한지 미리 설명 — 이후 각 권한은 실제 요청/설정 화면에서 개별적으로
 *  요청된다(알림은 이 다이얼로그를 닫는 즉시, 접근성 서비스/오버레이는 설정 탭에서). 이 다이얼로그 자체는
 *  아무 권한도 요청하지 않고 안내만 한다(82차, §6 "온보딩 권한 설명 다이얼로그").*/
@Composable
private fun OnboardingDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("시작하기 전에") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text("이 앱이 제대로 동작하려면 다음 권한들이 필요합니다.")
                Text("• 알림 — 루틴/스트릭/모임 알림을 보내려면 필요합니다.")
                Text("• 접근성 서비스 — 차단 대상 앱이 켜졌는지 감지하려면 필요합니다(설정 탭에서 별도로 켤 수 있습니다).")
                Text("• 다른 앱 위에 표시 — 차단 중 남은 시간 오버레이를 보여주려면 필요합니다(설정 탭에서 별도로 켤 수 있습니다).")
                Text("접근성 서비스와 오버레이 권한은 나중에 설정 탭에서 언제든 켤 수 있습니다.")
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("확인") }
        }
    )
}

@Composable
private fun PhoneLockApp(repository: PhoneLockRepository, onThemeChange: (String) -> Unit = {}) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val tabs = remember { visibleTabs(prefs) }
    var pendingUpdateApkUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        repository.checkForUpdateIfNeeded()
        pendingUpdateApkUrl = repository.pendingUpdateApkUrl()
        repository.runDailyMaintenanceIfNeeded()
    }

    // 83차: 태블릿(sw600dp 이상)은 하단 NavigationBar 대신 데스크탑 MainScreen.kt와 같은 좌측
    // NavigationRail로 — 탭 구성/동작은 동일하고 배치만 옆으로 옮긴다. 폰은 기존 Scaffold 그대로 유지.
    val isTablet = com.phonelock.app.ui.components.isTabletWidth()

    val navHostContent: @Composable (Modifier) -> Unit = { navModifier ->
        NavHost(
            navController = navController,
            startDestination = tabs.first().route,
            modifier = navModifier
        ) {
            composable(Tab.Manage.route) {
                ManageSection(repository, navController)
            }
            composable(
                "group_edit/{groupId}",
                arguments = listOf(navArgument("groupId") { type = NavType.StringType })
            ) { entry ->
                val arg = entry.arguments?.getString("groupId")
                val groupId = arg?.toLongOrNull()
                GroupEditScreen(repository, groupId) { navController.popBackStack() }
            }
            composable(Tab.Study.route) {
                StudySection(repository)
            }
            composable(Tab.Routine.route) {
                RoutineScreen(repository)
            }
            composable(Tab.Group.route) {
                SocialGroupScreen(repository) { groupId -> navController.navigate("social_group/$groupId") }
            }
            composable(
                "social_group/{groupId}",
                arguments = listOf(navArgument("groupId") { type = NavType.StringType })
            ) { entry ->
                val groupId = entry.arguments?.getString("groupId") ?: ""
                SocialGroupMembersScreen(
                    repository,
                    groupId,
                    onOpenMember = { uid -> navController.navigate("social_group_member/$groupId/$uid") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                "social_group_member/{groupId}/{uid}",
                arguments = listOf(
                    navArgument("groupId") { type = NavType.StringType },
                    navArgument("uid") { type = NavType.StringType }
                )
            ) { entry ->
                val groupId = entry.arguments?.getString("groupId") ?: ""
                val uid = entry.arguments?.getString("uid") ?: ""
                SocialGroupMemberDetailScreen(repository, groupId, uid) { navController.popBackStack() }
            }
            composable(Tab.Settings.route) {
                SettingsScreen(
                    repository,
                    onNavigateToStudyLockApps = { navController.navigate("study_lock_apps") },
                    onThemeChange = onThemeChange
                )
            }
            composable("study_lock_apps") {
                StudyLockAppsScreen()
            }
        }
    }

    if (isTablet) {
        Row(Modifier.fillMaxSize()) {
            androidx.compose.material3.NavigationRail {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                tabs.forEach { tab ->
                    androidx.compose.material3.NavigationRailItem(
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(tab.emoji) },
                        label = { Text(tab.label) }
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                pendingUpdateApkUrl?.let { url -> UpdateBanner(url) }
                navHostContent(Modifier.weight(1f))
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = backStackEntry?.destination

                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Text(tab.emoji) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                pendingUpdateApkUrl?.let { url -> UpdateBanner(url) }
                navHostContent(Modifier.weight(1f))
            }
        }
    }
}

/** "관리앱" 탭 내부의 그룹/통계 서브탭. */
@Composable
private fun ManageSection(repository: PhoneLockRepository, navController: NavController) {
    var subTab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = subTab) {
            MaterialTab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("그룹") })
            MaterialTab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("통계") })
        }
        Box(Modifier.weight(1f)) {
            when (subTab) {
                0 -> GroupListScreen(repository) { groupId ->
                    val route = if (groupId == null) "group_edit/new" else "group_edit/$groupId"
                    navController.navigate(route)
                }
                1 -> StatsScreen(repository)
            }
        }
    }
}

/** "공부앱" 탭 내부의 타이머/캘린더/계산기 서브탭. */
@Composable
private fun StudySection(repository: PhoneLockRepository) {
    var subTab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        // 85차: 이모지+텍스트를 하나의 text 슬롯에 나란히 넣으면 좁은 폰 화면에서 5칸이 우겨넣어져
        // 글자가 잘리거나 두 줄로 밀린다(사용자 지적) — Tab의 icon/text 슬롯을 분리하면 Material3가
        // 이모지를 위, 라벨을 아래로 항상 세로로 쌓아준다.
        TabRow(selectedTabIndex = subTab) {
            MaterialTab(selected = subTab == 0, onClick = { subTab = 0 }, icon = { Text("⏱️") }, text = { Text("시간 측정") })
            MaterialTab(selected = subTab == 1, onClick = { subTab = 1 }, icon = { Text("📅") }, text = { Text("캘린더") })
            MaterialTab(selected = subTab == 2, onClick = { subTab = 2 }, icon = { Text("🧮") }, text = { Text("계산기") })
            MaterialTab(selected = subTab == 3, onClick = { subTab = 3 }, icon = { Text("🗓️") }, text = { Text("일정표") })
            MaterialTab(selected = subTab == 4, onClick = { subTab = 4 }, icon = { Text("📈") }, text = { Text("통계") })
        }
        Box(Modifier.weight(1f)) {
            when (subTab) {
                0 -> StudyTimerScreen(repository)
                1 -> CalendarScreen(repository)
                2 -> CalculatorScreen(repository)
                3 -> TimetableScreen(repository)
                4 -> StudyStatsScreen(repository)
            }
        }
    }
}
