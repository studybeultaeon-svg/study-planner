package com.phonelock.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.phonelock.app.ui.components.LedgerNavBar
import com.phonelock.app.ui.components.LedgerNavItem
import com.phonelock.app.ui.components.LedgerNavRail
import com.phonelock.app.ui.components.PageMasthead
import com.phonelock.app.ui.components.SectionTabs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.phonelock.app.ui.theme.applyThemeWindowBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** 하단 탭은 "관리앱"(그룹/통계)/"공부앱"(타이머/캘린더/계산기)/"설정" 3개로만 두고, 그 안을
 * 서브탭으로 나눈다 — 데스크탑판(왼쪽 사이드바 + 서브탭)과 같은 2단 구조를 모바일에서는 하단 탭으로 구현. */
// 144차 리디자인: 탭 아이콘을 이모지에서 벡터 아이콘(외곽선/채움 한 쌍)으로 — 이모지는 기기마다 그림·기준선이 달라
// 글자와 줄이 맞지 않았고, 미니멀 모드에서만 빼던 것도 이제 두 모드가 같은 구조를 쓴다(흑백 단색 아이콘이라 자극이 아니다).
private sealed class Tab(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    object Home : Tab(MainActivity.ROUTE_HOME, "홈", Icons.Outlined.Spa, Icons.Filled.Spa)
    object Routine : Tab(MainActivity.ROUTE_ROUTINE, "루틴", Icons.Outlined.TaskAlt, Icons.Filled.TaskAlt)
    object Study : Tab(MainActivity.ROUTE_STUDY, "집중", Icons.Outlined.Timer, Icons.Filled.Timer)
    object Manage : Tab(MainActivity.ROUTE_MANAGE, "관리", Icons.Outlined.Shield, Icons.Filled.Shield)
    object Group : Tab(MainActivity.ROUTE_GROUP, "모임", Icons.Outlined.Groups, Icons.Filled.Groups)
    // 118차부터 설정은 탭이 아니라 홈 화면 우상단 버튼으로만 들어가는 독립 라우트 — visibleTabs()엔
    // 포함하지 않지만 NavHost 등록/네비게이션 대상으로는 그대로 쓴다.
    object Settings : Tab(MainActivity.ROUTE_SETTINGS, "설정", Icons.Outlined.Settings, Icons.Filled.Settings)

    fun navItem() = LedgerNavItem(route, label, icon, selectedIcon)
}

/** 관리자가 승인 시 지정한 기능 범위(루틴/공부/관리/모임)에 맞춰 보이는 탭만 남긴다 — 홈은 설정 진입점이
 *  이 화면에만 있으므로 항상 맨 앞에 보인다. 옛 승인 사용자는 필드가 없으면 [AppPreferences]가 전부 true를
 *  기본값으로 주므로 이 필터링으로 인한 회귀는 없다. */
private fun visibleTabs(prefs: AppPreferences): List<Tab> = listOfNotNull(
    Tab.Home,
    Tab.Routine.takeIf { prefs.permRoutine },
    Tab.Study.takeIf { prefs.permStudy },
    Tab.Manage.takeIf { prefs.permManage },
    // 98차(사용자 요청): 게스트(익명 계정)는 소셜 탭을 아예 못 쓰게 한다 — 서버 profile.permissions가
    // 아직 없으면(하위호환) 전부 true로 취급하는 fromProfile() 기본값 때문에 이 조건 없이는 게스트도
    // 그냥 소셜 탭이 보였다.
    Tab.Group.takeIf { prefs.permSocial && com.phonelock.app.service.AuthManager.currentUser?.isAnonymous != true }
)

/** 런처 바로가기가 요청한 진입 지점 — [MainActivity.EXTRA_START_ROUTE]/[MainActivity.EXTRA_START_SUB_TAB]을 담는다. */
private data class StartRequest(val route: String, val subTab: Int, val seq: Int)

class MainActivity : ComponentActivity() {
    companion object {
        // 131차: 미니멀 런처 홈이 "루틴/타이머/캘린더/..."로 바로 들어올 때 쓰는 진입 지점. 라우트 문자열을
        // 런처 쪽에 한 번 더 적어두면 둘이 어긋나므로 여기 상수를 [Tab]과 런처가 함께 쓴다.
        const val ROUTE_HOME = "home"
        const val ROUTE_ROUTINE = "routine"
        const val ROUTE_STUDY = "study"
        const val ROUTE_MANAGE = "manage"
        const val ROUTE_GROUP = "group"
        const val ROUTE_SETTINGS = "settings"

        /** 관리 탭 안의 서브탭 번호 — 전체 잠금 화면의 "타이머 열기"가 쓴다(142차). 143차: 차단 규칙(0)과 사용 기록 사이로 옮겼다. */
        const val MANAGE_SUB_TAB_RULES = 0
        const val MANAGE_SUB_TAB_TIMER = 1
        const val MANAGE_SUB_TAB_STATS = 2

        const val EXTRA_START_ROUTE = "start_route"
        /** 공부/관리 탭 안의 서브탭 번호(타이머=0, 캘린더=1 …). -1이면 "탭만 열고 서브탭은 그대로". */
        const val EXTRA_START_SUB_TAB = "start_sub_tab"

        /** 런처 바로가기가 쓰는 인텐트 — 어디서 부르든 같은 모양이 되도록 여기서만 만든다. */
        fun openIntent(context: Context, route: String, subTab: Int = -1): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_START_ROUTE, route)
                .putExtra(EXTRA_START_SUB_TAB, subTab)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 거부해도 앱의 다른 기능에는 지장 없음 — 그냥 알림을 못 받을 뿐 */ }

    /**
     * 런처에서 요청한 진입 지점(131차). `launchMode=singleTask`라 앱이 이미 떠 있으면 새 인텐트가
     * [onNewIntent]로 들어오므로 onCreate에서 한 번 읽고 마는 게 아니라 상태로 들고 있어야 한다.
     * [StartRequest.seq]는 "같은 곳으로 두 번 연속" 요청해도 다시 이동하게 하는 구분자다.
     */
    private val startRequest = mutableStateOf<StartRequest?>(null)
    private var startSeq = 0

    private fun readStartRequest(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_START_ROUTE) ?: return
        startRequest.value = StartRequest(route, intent.getIntExtra(EXTRA_START_SUB_TAB, -1), ++startSeq)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readStartRequest(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readStartRequest(intent)
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

        // 106차: 신규 사용자에게 알림 권한을 앱 시작과 동시에 불쑥 요청하던 것을 그만두고,
        // 로그인/승인 직후에 뜨는 PermissionOnboardingScreen 안에서 다른 권한들과 함께 설명 후 요청하도록
        // 옮겼다. 이미 온보딩을 마친 사용자(예전에 거부했거나 시스템 업데이트로 권한이 다시 꺼진 경우)만
        // 앱을 열 때마다 조용히 재요청한다.
        if (AppPreferences(applicationContext).onboardingShown &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            // 121차: 성장(레벨/EXP/장식)도 시작할 때 한 번 받아온다 — 이 기기가 원격 상태를 모르는 채로
            // 먼저 EXP를 적립해 올려버리면 다른 기기가 쌓아둔 레벨을 덮어쓸 수 있다.
            repository.syncGrowthFromFirebase()
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
            // 공부 알림(122차) — 켜둔 경우에만 예약한다(예약은 발화할 때마다 스스로 이어진다).
            if (AppPreferences(applicationContext).studyAlertEnabled) {
                RoutineAlarmScheduler.scheduleStudyAlertCheck(applicationContext)
            }
            // 알림 묶음 요약(130차) — 거를 앱을 안 골랐으면 안에서 예약을 취소하고 끝난다.
            RoutineAlarmScheduler.scheduleNotificationDigest(applicationContext)
        }

        setContent {
            // 130차: 미니멀 모드가 켜져 있으면 고른 테마와 무관하게 흑백 팔레트를 쓰므로 effectiveThemeMode를 읽는다.
            var themeMode by remember { mutableStateOf(AppPreferences(applicationContext).effectiveThemeMode) }
            // 79차: 커스텀 테마는 themeMode 문자열("CUSTOM")이 안 바뀌어도 색만 바뀔 수 있어서 별도
            // 카운터로 강제 재계산(데스크탑판 Main.kt와 동일 패턴).
            var themeRefreshTick by remember { mutableStateOf(0) }
            val prefs = remember(themeRefreshTick) { AppPreferences(applicationContext) }
            // 121차: 창 배경도 테마가 바뀔 때마다 같이 갈아준다 — 안 그러면 화면 전환/첫 프레임에
            // Theme.PhoneLock(=Material.Light)의 흰 바탕이 그대로 비친다.
            LaunchedEffect(themeMode, themeRefreshTick) { applyThemeWindowBackground(prefs) }
            var showOnboarding by remember { mutableStateOf(!AppPreferences(applicationContext).onboardingShown) }
            PhoneLockTheme(themeMode, prefs.customThemeBackground, prefs.customThemeAccent, prefs.fontScale) {
                Surface(modifier = Modifier) {
                    AccountGate(repository) {
                        // 106차: 로그인/가입승인 직후(=AccountGate가 content()를 보여주는 시점)에만
                        // 권한 설정 가이드를 띄운다 — 요청된 사용자 흐름(로그인 완료 → 권한 가이드 → 메인
                        // 화면) 그대로. 이미 마친 사용자는 다시 안 보임(AppPreferences.onboardingShown).
                        if (showOnboarding) {
                            PermissionOnboardingScreen(
                                repository = repository,
                                onDone = {
                                    AppPreferences(applicationContext).onboardingShown = true
                                    showOnboarding = false
                                }
                            )
                        } else {
                            PhoneLockApp(
                                repository,
                                onThemeChange = { themeMode = it; themeRefreshTick++ },
                                startRequest = startRequest.value,
                                onStartRequestHandled = { startRequest.value = null }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneLockApp(
    repository: PhoneLockRepository,
    onThemeChange: (String) -> Unit = {},
    startRequest: StartRequest? = null,
    onStartRequestHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val tabs = remember { visibleTabs(prefs) }
    // 131차: 공부/규칙 섹션의 서브탭을 각 섹션 안(remember)이 아니라 여기로 올렸다 — 런처 바로가기가
    // "공부 > 캘린더"처럼 서브탭까지 지정해서 들어오는데, 섹션 안에 숨어 있으면 이미 한 번 그려진 뒤에는
    // 바깥에서 바꿀 방법이 없기 때문이다. 동작(탭을 눌러 오갈 때 마지막 서브탭 유지)은 이전과 같다.
    var studySubTab by rememberSaveable { mutableIntStateOf(0) }
    var manageSubTab by rememberSaveable { mutableIntStateOf(0) }
    var pendingUpdateApkUrl by remember { mutableStateOf<String?>(null) }
    // 런처 바로가기로 들어온 진입 지점 처리(131차). 승인 범위 밖 라우트가 들어와도 NavHost엔 모든 라우트가
    // 등록돼 있어 이동 자체는 되지만, 애초에 런처가 권한에 맞는 바로가기만 보여준다.
    LaunchedEffect(startRequest) {
        val request = startRequest ?: return@LaunchedEffect
        if (request.subTab >= 0) when (request.route) {
            Tab.Study.route -> studySubTab = request.subTab
            Tab.Manage.route -> manageSubTab = request.subTab
        }
        runCatching {
            navController.navigate(request.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        // 이동을 마친 뒤에 비운다 — 먼저 비우면 재구성이 이 이펙트를 취소할 여지가 생긴다.
        onStartRequestHandled()
    }
    // 135차: 예전엔 여기서 딱 한 번만 읽어서, 백그라운드(AppMonitorAccessibilityService)가 새 릴리스를
    // 찾아 저장해 둬도 이 화면이 살아 있는 동안엔 배너가 끝내 안 떴다 — 앱을 껐다 켜서 액티비티가 새로
    // 만들어져야만 보였으니 "새 버전이 나오면 알려준다"는 기능이 사실상 죽어 있었다. 확인 호출 자체는
    // checkForUpdateIfNeeded의 주기 가드(15분)가 막아주므로, 이 루프가 실제로 하는 일은 저장된 값을
    // 다시 읽는 것뿐이다(SharedPreferences 읽기 한 번).
    LaunchedEffect(Unit) {
        repository.runDailyMaintenanceIfNeeded()
        while (true) {
            repository.checkForUpdateIfNeeded()
            pendingUpdateApkUrl = repository.pendingUpdateApkUrl()
            delay(60_000)
        }
    }

    // 83차: 태블릿(sw600dp 이상)은 하단 NavigationBar 대신 데스크탑 MainScreen.kt와 같은 좌측
    // NavigationRail로 — 탭 구성/동작은 동일하고 배치만 옆으로 옮긴다. 폰은 기존 Scaffold 그대로 유지.
    val isTablet = com.phonelock.app.ui.components.isTabletWidth()

    val navHostContent: @Composable (Modifier) -> Unit = { navModifier ->
        // 144차 화면 전환: 탭끼리는 짧은 크로스페이드(같은 층위를 오간다), 상세 화면(규칙 편집·대화·모임·설정)은 오른쪽에서
        // 살짝 밀려 들어오며 겹쳐진다(깊이로 들어간다). 성능 모드에선 90ms 페이드만 남는다.
        val motion = com.phonelock.app.ui.theme.LocalAppMotion.current
        val tabRoutes = remember { setOf(Tab.Home.route, Tab.Routine.route, Tab.Study.route, Tab.Manage.route, Tab.Group.route) }
        fun isTab(route: String?) = route in tabRoutes
        NavHost(
            navController = navController,
            startDestination = tabs.first().route,
            modifier = navModifier,
            enterTransition = {
                if (motion.reduced || isTab(targetState.destination.route)) fadeIn(motion.standard())
                else fadeIn(motion.emphasized()) + slideInHorizontally(motion.emphasized()) { it / 10 }
            },
            exitTransition = {
                if (motion.reduced || isTab(targetState.destination.route)) fadeOut(motion.exit())
                else fadeOut(motion.exit()) + slideOutHorizontally(motion.emphasized()) { -it / 20 }
            },
            popEnterTransition = {
                if (motion.reduced || isTab(initialState.destination.route)) fadeIn(motion.standard())
                else fadeIn(motion.emphasized()) + slideInHorizontally(motion.emphasized()) { -it / 20 }
            },
            popExitTransition = {
                if (motion.reduced || isTab(initialState.destination.route)) fadeOut(motion.exit())
                else fadeOut(motion.exit()) + slideOutHorizontally(motion.emphasized()) { it / 10 }
            }
        ) {
            composable(Tab.Manage.route) {
                ManageSection(repository, navController, manageSubTab) { manageSubTab = it }
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
                StudySection(repository, studySubTab) { studySubTab = it }
            }
            composable(Tab.Routine.route) {
                RoutineScreen(repository)
            }
            composable(Tab.Home.route) {
                PlantScreen(
                    repository,
                    permPlant = prefs.permPlant,
                    minimalMode = prefs.minimalMode,
                    onOpenSettings = { navController.navigate(Tab.Settings.route) }
                )
            }
            composable(Tab.Group.route) {
                SocialGroupScreen(
                    repository,
                    onOpenGroup = { groupId -> navController.navigate("social_group/$groupId") }
                )
            }
            composable(
                "dm_chat/{chatId}/{peerUid}/{peerLabel}",
                arguments = listOf(
                    navArgument("chatId") { type = NavType.StringType },
                    navArgument("peerUid") { type = NavType.StringType },
                    navArgument("peerLabel") { type = NavType.StringType }
                )
            ) { entry ->
                val chatId = entry.arguments?.getString("chatId") ?: ""
                val peerUid = entry.arguments?.getString("peerUid") ?: ""
                val peerLabel = java.net.URLDecoder.decode(entry.arguments?.getString("peerLabel") ?: "", "UTF-8")
                DmChatScreen(repository, chatId, peerUid, peerLabel, onBack = { navController.popBackStack() })
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
                    onOpenDm = { chatId, peerUid, peerLabel ->
                        val encodedLabel = java.net.URLEncoder.encode(peerLabel, "UTF-8")
                        navController.navigate("dm_chat/$chatId/$peerUid/$encodedLabel")
                    },
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
                SocialGroupMemberDetailScreen(
                    repository, groupId, uid,
                    onOpenDm = { chatId, peerUid, peerLabel ->
                        val encodedLabel = java.net.URLEncoder.encode(peerLabel, "UTF-8")
                        navController.navigate("dm_chat/$chatId/$peerUid/$encodedLabel")
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Tab.Settings.route) {
                SettingsScreen(
                    repository,
                    onNavigateToStudyLockApps = { navController.navigate("study_lock_apps") },
                    onThemeChange = onThemeChange,
                    onClose = { navController.popBackStack() }
                )
            }
            composable("study_lock_apps") {
                StudyLockAppsScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    // 144차: 저사양 기기·배터리 절약 중이면 성능(미니멀) 모드를 한 번 권한다 — 바꾸는 건 사용자가 고를 때만.
    PerformanceModeSuggestion(onEnable = {
        prefs.minimalMode = true
        onThemeChange(prefs.effectiveThemeMode)
        com.phonelock.app.widget.RoutineWidgetProvider.updateAll(context)
    })

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // 설정은 118차부터 탭이 아니라 홈의 원형 버튼으로만 들어가는 전용 화면이라, 그 위에 있는 동안은
    // 하단 탭/좌측 레일을 아예 숨겨 카테고리→세부설정 흐름에 화면을 온전히 내준다.
    val onSettingsRoute = currentDestination?.hierarchy?.any { it.route == Tab.Settings.route } == true

    val navItems = remember(tabs) { tabs.map { it.navItem() } }
    val selectedKey = tabs.firstOrNull { tab -> currentDestination?.hierarchy?.any { it.route == tab.route } == true }?.route
    val onSelectTab: (LedgerNavItem) -> Unit = { item ->
        navController.navigate(item.key) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    if (isTablet) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (!onSettingsRoute) {
                LedgerNavRail(navItems, selectedKey, onSelectTab)
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                pendingUpdateApkUrl?.let { url -> UpdateBanner(url) }
                navHostContent(Modifier.weight(1f))
            }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!onSettingsRoute) {
                    LedgerNavBar(navItems, selectedKey, onSelectTab)
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

/**
 * 관리 탭 — 144차: Material 탭 줄 대신 편집형 머리(큰 제목) + 텍스트 탭. 서브탭 내용은 고른 방향으로 살짝 밀리며 바뀐다.
 * 번호(0 차단 규칙 / 1 타이머 / 2 사용 기록)는 [MainActivity.MANAGE_SUB_TAB_TIMER] 등과 런처 바로가기가 함께 쓴다.
 */
@Composable
private fun ManageSection(
    repository: PhoneLockRepository,
    navController: NavController,
    subTab: Int,
    onSubTabChange: (Int) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PageMasthead(title = "관리", overline = "차단 · 타이머 · 기록")
        SectionTabs(listOf("차단 규칙", "타이머", "사용 기록"), subTab, onSubTabChange)
        SectionContent(subTab, Modifier.weight(1f)) { tab ->
            when (tab) {
                MainActivity.MANAGE_SUB_TAB_RULES -> GroupListScreen(repository) { groupId ->
                    val route = if (groupId == null) "group_edit/new" else "group_edit/$groupId"
                    navController.navigate(route)
                }
                MainActivity.MANAGE_SUB_TAB_STATS -> StatsScreen(repository)
                MainActivity.MANAGE_SUB_TAB_TIMER -> LockTimerScreen(repository)
            }
        }
    }
}

/** 집중 탭(구 공부) — 타이머/캘린더/계산기/일정표/통계. 머리 위 작은 줄은 "하루 시작 기준"을 따른 오늘 날짜. */
@Composable
private fun StudySection(repository: PhoneLockRepository, subTab: Int, onSubTabChange: (Int) -> Unit) {
    val todayLabel = remember {
        val date = java.time.LocalDate.parse(repository.todayCalendarDateKey())
        date.format(java.time.format.DateTimeFormatter.ofPattern("M월 d일 EEEE", java.util.Locale.KOREAN))
    }
    Column(Modifier.fillMaxSize()) {
        PageMasthead(title = "집중", overline = todayLabel)
        SectionTabs(listOf("타이머", "캘린더", "계산기", "일정표", "통계"), subTab, onSubTabChange)
        SectionContent(subTab, Modifier.weight(1f)) { tab ->
            when (tab) {
                0 -> StudyTimerScreen(repository)
                1 -> CalendarScreen(repository)
                2 -> CalculatorScreen(repository)
                3 -> TimetableScreen(repository)
                4 -> StudyStatsScreen(repository)
            }
        }
    }
}

/**
 * 서브탭 내용 전환 — 오른쪽 탭으로 가면 내용이 왼쪽으로, 왼쪽 탭으로 가면 오른쪽으로 살짝(1/12폭) 밀리며 바뀐다.
 * 탭 막대가 움직이는 방향과 내용이 움직이는 방향을 맞춰 "어디로 갔는지"가 손에 남게 한다. 성능 모드에선 짧은 페이드만.
 */
@Composable
private fun SectionContent(subTab: Int, modifier: Modifier, content: @Composable (Int) -> Unit) {
    val motion = com.phonelock.app.ui.theme.LocalAppMotion.current
    androidx.compose.animation.AnimatedContent(
        targetState = subTab,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val forward = targetState > initialState
            if (motion.reduced) {
                fadeIn(motion.standard()) togetherWith fadeOut(motion.exit())
            } else {
                (fadeIn(motion.standard()) + slideInHorizontally(motion.standard()) { w -> (if (forward) w else -w) / 12 }) togetherWith
                    (fadeOut(motion.exit()) + slideOutHorizontally(motion.exit()) { w -> (if (forward) -w else w) / 12 })
            }
        },
        label = "sectionContent"
    ) { tab ->
        Box(Modifier.fillMaxSize()) { content(tab) }
    }
}
