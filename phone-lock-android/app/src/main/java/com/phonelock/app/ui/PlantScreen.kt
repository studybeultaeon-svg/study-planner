package com.phonelock.app.ui

import com.phonelock.app.ui.components.LedgerAlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.applyPendingGrowthExp
import com.phonelock.app.data.getGrowthExpPending
import com.phonelock.app.data.getGrowthExpTotal
import com.phonelock.app.data.getPointsBalance
import com.phonelock.app.data.getRebirthCount
import com.phonelock.app.data.syncGrowthFromFirebase
import com.phonelock.app.data.syncPointsFromFirebase
import com.phonelock.app.data.observePointsBalance
import com.phonelock.app.data.rebirth
import com.phonelock.app.data.activeGrowthBoost
import com.phonelock.app.data.purchasePotion
import com.phonelock.app.data.getAllRoutines
import com.phonelock.app.data.getRoutineCompletedDateKeys
import com.phonelock.app.data.getAllCalendarTasksOnce
import com.phonelock.app.routine.RoutineEngine
import com.phonelock.app.service.GrowthSoundPlayer
import com.phonelock.app.ui.components.BigNumber
import com.phonelock.app.ui.components.Hairline
import com.phonelock.app.ui.components.LedgerSection
import com.phonelock.app.ui.components.Overline
import com.phonelock.app.ui.components.ProgressLine
import com.phonelock.app.ui.components.StatRow
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.app.ui.theme.pressScale
import androidx.compose.material.icons.outlined.Settings
import com.phonelock.shared.GrowthBoost
import com.phonelock.shared.GrowthSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState

/** 홈 화면이 저장값을 다시 읽는 주기(121차) — EXP는 이 화면 밖(공부 타이머·루틴 체크·다른 기기
 *  동기화)에서도 쌓이므로, 화면이 떠 있는 동안 이 간격으로 다시 읽어 표시가 실제 값과 벌어지지 않게 한다. */
private const val GROWTH_POLL_INTERVAL_MS = 2_000L

/**
 * 홈 탭(105차 "식물" 탭으로 신설, 108차 게임성 강화 개편, 109차 500레벨/등급 체계 개편, 149차 식물 → 우주, 데스크탑판과 대칭) —
 * 레벨/칭호는 `shared/GrowthSystem.kt` 하나로 계산한다. 108차부터 EXP는 적립 즉시 레벨에 반영되지 않고
 * "대기 EXP"([PhoneLockRepository.getGrowthExpPending])로 먼저 쌓이며, 사용자가 아래쪽 HUD의
 * "경험치 적용" 버튼을 눌러야 그 순간 [PhoneLockRepository.applyPendingGrowthExp]가 레벨에 실제로
 * 반영한다 — 그 반영 과정을 경험치바가 차오르고(레벨업 시 넘치면 다음 레벨로 이어서) 레벨업 연출이 뜨는
 * 애니메이션으로 보여줘서 사용자가 레벨업 과정에 직접 참여하는 느낌을 준다(사용자 요청). 환생(화면 문구는 149차부터
 * "빅뱅")도 버튼+확인 다이얼로그로 수동이다(자동 환생 로직 없음). 149차: 장면은 [CosmosScene](천체 하나, 정지 그림)이고
 * 상점 꾸미기는 없앴다.
 */
@Composable
fun PlantScreen(
    repository: PhoneLockRepository,
    permPlant: Boolean = true,
    /** 130차 미니멀 모드 — 우주 장면 대신 레벨/오늘 요약 카드만 세로로 쌓는다. */
    minimalMode: Boolean = false,
    onOpenSettings: () -> Unit = {}
) {
    if (!permPlant) {
        // 관리자가 이 사용자의 성장 기능을 껐어도 홈은 항상 존재해야 설정 진입점(우상단 버튼)을 잃지 않는다.
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.Center).padding(Spacing.lg)) {
                Text(
                    "성장 기능이 꺼져 있습니다.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HomeSettingsButton(onOpenSettings, Modifier.align(Alignment.TopEnd).padding(Spacing.lg))
        }
        return
    }
    val scope = rememberCoroutineScope()
    val balance by repository.observePointsBalance().collectAsState(initial = 0)
    // growthExpPending/rebirthCount는 Room Flow가 아니라 AppPreferences 스칼라값이라 tick으로 재조회한다.
    // 133차: 홈 탭이 성장 정보만 보여주게 되면서(오늘 현황 요약은 런처 홈으로 이사) 121차에 tick을
    // 둘로 나눠 막아두었던 무거운 재조회(루틴/캘린더 전체 훑기) 자체가 없어져 growthTick 하나로 합쳤다.
    var growthTick by remember { mutableIntStateOf(0) }
    val growthExpPending = remember(growthTick) { repository.getGrowthExpPending() }
    val rebirthCount = remember(growthTick) { repository.getRebirthCount() }
    fun refresh() { growthTick++ }

    // 진입 시 원격과 맞춰본다 — 성장 값(레벨/EXP)은 포인트 원장에서 파생되므로 둘을 같이 당겨와야
    // "포인트는 최신인데 레벨만 옛날"이 생기지 않는다.
    LaunchedEffect(Unit) {
        repository.syncPointsFromFirebase()
        if (repository.syncGrowthFromFirebase()) refresh()
    }

    var showShop by remember { mutableStateOf(false) }
    // 138차 상점 물약 — 지금 켜진 효과와 남은 시간. 2초 tick마다 다시 읽어 남은 시간이 줄어드는 게 보이고,
    // 효과가 끝나면 다음 tick에 표시가 사라진다.
    val activeBoost = remember(growthTick) { repository.activeGrowthBoost() }
    val boostNowMillis = remember(growthTick) { System.currentTimeMillis() }
    // 태블릿 "오늘" 카드(138차)를 당겨서 새로고침 때 곧바로 다시 읽게 하는 키.
    var todaySummaryKey by remember { mutableIntStateOf(0) }

    var displayedExp by remember { mutableDoubleStateOf(repository.getGrowthExpTotal()) }
    var displayedLevel by remember { mutableIntStateOf(GrowthSystem.levelForExp(displayedExp)) }
    var isApplying by remember { mutableStateOf(false) }
    var levelUpFlash by remember { mutableStateOf<Int?>(null) }
    var showRebirthDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(growthTick) {
        if (!isApplying) {
            displayedExp = repository.getGrowthExpTotal()
            displayedLevel = GrowthSystem.levelForExp(displayedExp)
        }
    }

    // 121차(사용자 지적 "홈 탭 값이 실제 데이터와 다르다") — 이 화면이 떠 있는 동안 저장값을 주기적으로
    // 다시 읽는다. 백그라운드에 있다 돌아왔을 때, 그리고 공부 타이머/루틴 체크처럼 이 화면 밖에서 EXP가
    // 쌓였을 때도 별도 배선 없이 따라잡는다(경험치 적용 애니메이션 중에는 표시가 튀지 않게 쉰다).
    LaunchedEffect(Unit) {
        while (true) {
            delay(GROWTH_POLL_INTERVAL_MS)
            if (!isApplying) growthTick++
        }
    }

    val stage = GrowthSystem.stageForLevel(displayedLevel)
    val levelProgress = GrowthSystem.progressToNextLevel(displayedExp)
    val isMaxLevel = GrowthSystem.isMaxLevel(displayedLevel)
    val canRebirth = GrowthSystem.canRebirth(displayedLevel, rebirthCount)
    val nextRebirthLevel = GrowthSystem.rebirthRequiredLevel(rebirthCount + 1)
    val multiplier = GrowthSystem.expMultiplier(rebirthCount)

    fun applyPendingExp() {
        if (isApplying) return
        val result = repository.applyPendingGrowthExp() ?: return
        refresh()
        isApplying = true
        GrowthSoundPlayer.playExpTick()
        scope.launch {
            animateExpApplication(
                result = result,
                stepDelayMs = expInjectionStepDelayMs(rebirthCount),
                onProgress = { exp, level -> displayedExp = exp; displayedLevel = level },
                onLevelUp = { level ->
                    GrowthSoundPlayer.playLevelUp()
                    levelUpFlash = level
                    delay(900)
                    levelUpFlash = null
                }
            )
            isApplying = false
        }
    }

    // 122차(사용자 요청): 홈 탭 새로고침 — 다른 탭의 새로고침과 같은 의미로, 원격(포인트/성장)을 다시
    // 당겨온 뒤 화면이 쓰는 값(레벨/EXP/포인트/루틴·캘린더 요약)을 전부 재조회한다. 이미
    // 최신이어도 동작은 동일하고(동기화가 "변경 없음"을 돌려줄 뿐 화면은 다시 그려진다), 경험치 적용
    // 애니메이션 중이면 표시가 튀지 않게 값 반영을 LaunchedEffect(growthTick)의 isApplying 가드에 맡긴다.
    val doRefresh: suspend () -> Unit = {
        try {
            repository.syncPointsFromFirebase()
            repository.syncGrowthFromFirebase()
        } finally {
            refresh()
            todaySummaryKey++
        }
    }

    // 122차: 태블릿에서 하단 전폭 HUD가 장면을 덮어버린다는 지적 → 화면 폭에 따라
    // 배치를 바꾼다. 화면 크기를 Configuration이 아니라 실제 할당 폭(BoxWithConstraints)으로 판단해서
    // 좌측 NavigationRail이 폭을 먹는 경우나 특이한 화면 비율에서도 어긋나지 않게 했다.
    //  - 넓은 가로(840dp+, 가로 태블릿/큰 화면): 씬 | 성장 패널 좌우 2열 — HUD가 씬을 아예 안 덮는다.
    //  - 태블릿 폭(600dp+): 씬 | HUD 위아래 2단 — 천체는 씬 영역 안에서 다시 가운데에 놓인다.
    //  - 폰: 씬을 전면에 깔고 HUD를 아래쪽에 겹쳐 띄우되, 천체는 레벨 숫자와 HUD 사이 영역에 그린다(contentTop/BottomInset).
    // 138차: 두 번째 인자 detailed — 태블릿/넓은 화면은 데스크탑처럼 자세한 성장 카드(큰 레벨 배지·수치 타일·
    // 물약 상태 칸)를, 폰은 133차에 줄인 한 장짜리 카드를 쓴다.
    // 144차 리디자인: 레벨 숫자를 "주인공"으로 — 씬이 있는 화면에선 하늘 위 왼쪽에 크게(테마 바탕색 스크림 위라 하늘
    // 색과 무관하게 읽힌다), 씬이 없는 미니멀(성능) 모드와 태블릿 패널에선 패널 맨 위에 크게 둔다. 아래 패널은 진행 막대
    // 하나 + 수치 세 칸 + 행동 버튼만 남겨 씬을 덜 가린다(133차 "하늘을 가린다" 지적 유지).
    val levelHero: @Composable (Modifier, androidx.compose.ui.text.TextStyle) -> Unit = { m, style ->
        HomeLevelHero(
            level = displayedLevel,
            stage = stage,
            isMaxLevel = isMaxLevel,
            rebirthCount = rebirthCount,
            numberStyle = style,
            modifier = m
        )
    }
    val growthPanel: @Composable (Modifier, Boolean) -> Unit = { m, detailed ->
        HomeGrowthPanel(
            levelProgress = levelProgress,
            isMaxLevel = isMaxLevel,
            rebirthCount = rebirthCount,
            multiplier = multiplier,
            canRebirth = canRebirth,
            nextRebirthLevel = nextRebirthLevel,
            balance = balance,
            growthExpPending = growthExpPending,
            isApplying = isApplying,
            toastMessage = toastMessage,
            activeBoost = activeBoost,
            boostNowMillis = boostNowMillis,
            onApplyExp = { applyPendingExp() },
            onRebirth = { showRebirthDialog = true },
            onOpenShop = { showShop = true },
            detailed = detailed,
            modifier = m
        )
    }
    val controls: @Composable () -> Unit = { HomeSettingsButton(onOpenSettings) }
    val heroLarge = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp, lineHeight = 96.sp, letterSpacing = (-4).sp)

    // 당겨서 새로고침(124차, 사용자 요청 "다른 탭들처럼 화면 쓸어내리면 새로고침") — 홈 본문은 스크롤되지 않는 캔버스라
    // 화면 전체를 "스크롤 범위 0"인 세로 스크롤 컨테이너로 한 번 감싸 캔버스 어디를 당겨도 새로고침이 잡히게 한다.
    com.phonelock.app.ui.components.PullToRefreshBox(onRefresh = { doRefresh() }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Row/Column 스코프 안에서는 BoxWithConstraintsScope의 maxWidth가 가려지므로 여기서 받아둔다.
            val availableWidth = maxWidth
            val availableHeight = maxHeight
            val wideLayout = availableWidth >= 840.dp
            val tabletLayout = availableWidth >= 600.dp
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(availableHeight)) {
            when {
                // 130차 미니멀 모드 = 144차 성능 모드: 움직이는 장면 없이 같은 정보를 글자와 선으로만 — 레벨 숫자가 화면을
                // 차지하는 타이포그래피 홈. 태블릿 "오늘" 묶음도 여기서 보여준다(장면이 없어 공간이 남는다).
                minimalMode -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.gutter).padding(top = Spacing.md, bottom = Spacing.lg)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { controls() }
                    levelHero(Modifier.fillMaxWidth(), heroLarge)
                    Spacer(Modifier.height(Spacing.lg))
                    growthPanel(Modifier.fillMaxWidth(), true)
                    Spacer(Modifier.height(Spacing.xl))
                    HomeTodayCard(repository, todaySummaryKey, Modifier.fillMaxWidth())
                }
                wideLayout -> Row(Modifier.fillMaxSize()) {
                    HomeSceneArea(
                        stage = stage, levelUpFlash = levelUpFlash,
                        topEndControls = null,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // 오른쪽 패널 — 레벨 히어로 + 자세한 성장 + "오늘". 씬에는 천체만 온전히 보인다.
                    Column(
                        Modifier.width((availableWidth * 0.32f).coerceIn(340.dp, 440.dp)).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { controls() }
                        levelHero(Modifier.fillMaxWidth(), MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp, lineHeight = 82.sp, letterSpacing = (-3).sp))
                        Spacer(Modifier.height(Spacing.lg))
                        growthPanel(Modifier.fillMaxWidth(), true)
                        Spacer(Modifier.height(Spacing.xl))
                        HomeTodayCard(repository, todaySummaryKey, Modifier.fillMaxWidth())
                    }
                }
                tabletLayout -> Column(Modifier.fillMaxSize()) {
                    var heroHeightPx by remember { mutableIntStateOf(0) }
                    val heroHeight = if (heroHeightPx > 0) with(LocalDensity.current) { heroHeightPx.toDp() } else 190.dp
                    HomeSceneArea(
                        stage = stage, levelUpFlash = levelUpFlash,
                        topEndControls = controls,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentTopInset = heroHeight
                    ) {
                        CosmosOverlayTheme {
                            HomeSkyScrim(Modifier.align(Alignment.TopStart).fillMaxWidth().height(heroHeight + 72.dp))
                            levelHero(
                                Modifier.align(Alignment.TopStart).onSizeChanged { heroHeightPx = it.height }.padding(Spacing.lg),
                                MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp, lineHeight = 82.sp, letterSpacing = (-3).sp)
                            )
                        }
                    }
                    Hairline()
                    // 세로 태블릿: 씬 아래 영역에 자세한 성장과 "오늘"을 나란히(천체를 가리지 않는다).
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = Spacing.lg, vertical = Spacing.lg)
                            .heightIn(max = 380.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState())) {
                            growthPanel(Modifier.fillMaxWidth(), true)
                        }
                        HomeTodayCard(repository, todaySummaryKey, Modifier.weight(1f))
                    }
                }
                else -> {
                    // 폰: 씬을 전면에 깔고 아래쪽에 시트를 붙인다. 레벨 숫자와 시트가 덮는 만큼 씬에 알려줘서 천체가 그 사이에 그려지게 한다.
                    var heroHeightPx by remember { mutableIntStateOf(0) }
                    var hudHeightPx by remember { mutableIntStateOf(0) }
                    val density = LocalDensity.current
                    val heroHeight = if (heroHeightPx > 0) with(density) { heroHeightPx.toDp() } else 180.dp
                    val hudHeight = if (hudHeightPx > 0) with(density) { hudHeightPx.toDp() } else 250.dp
                    HomeSceneArea(
                        stage = stage, levelUpFlash = levelUpFlash,
                        topEndControls = controls,
                        modifier = Modifier.fillMaxSize(),
                        contentTopInset = heroHeight,
                        contentBottomInset = hudHeight + Spacing.md
                    ) {
                        CosmosOverlayTheme {
                            HomeSkyScrim(Modifier.align(Alignment.TopStart).fillMaxWidth().height(heroHeight + 72.dp))
                            levelHero(
                                Modifier.align(Alignment.TopStart).onSizeChanged { heroHeightPx = it.height }
                                    .padding(start = Spacing.gutter, top = Spacing.md, end = 88.dp),
                                MaterialTheme.typography.displayLarge.copy(fontSize = 76.sp, lineHeight = 78.sp, letterSpacing = (-3).sp)
                            )
                        }
                        HomeSheet(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .heightIn(max = 340.dp)
                                .onSizeChanged { hudHeightPx = it.height }
                        ) {
                            growthPanel(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.md), false)
                        }
                    }
                }
            }
            }
            }
        }
    }

    if (showRebirthDialog) {
        LedgerAlertDialog(
            onDismissRequest = { showRebirthDialog = false },
            title = { Text("빅뱅을 일으킬까요?") },
            text = {
                // 138차: 배율이 지수라 후반엔 "×15913789.3"처럼 길어져 만/억 표기로 줄이고, 적용 안 한 대기 경험치는
                // 빅뱅과 함께 사라지는데 그걸 알려주지 않아 모르고 잃을 수 있어서 있을 때만 한 줄 덧붙인다.
                Text(
                    "레벨과 경험치가 처음으로 돌아가 우주 먼지부터 다시 시작합니다. 대신 앞으로 얻는 경험치가 " +
                        "×${formatMultiplier(GrowthSystem.expMultiplier(rebirthCount + 1))}로 영구히 늘어납니다." +
                        if (growthExpPending > 0.0) "\n\n아직 적용하지 않은 대기 경험치 +${GrowthSystem.formatExp(growthExpPending)}도 함께 사라집니다. 먼저 \"경험치 적용\"을 누르면 레벨에 반영돼요." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = repository.rebirth()
                    toastMessage = if (ok) "빅뱅 — 새 우주가 시작됐어요. 이제 더 빠르게 자랍니다" else "아직 빅뱅을 일으킬 수 있는 레벨이 아닙니다"
                    showRebirthDialog = false
                    refresh()
                }) { Text("빅뱅") }
            },
            dismissButton = { TextButton(onClick = { showRebirthDialog = false }) { Text("취소") } }
        )
    }

    if (showShop) {
        HomeShopDialog(
            balance = balance,
            activeBoost = activeBoost,
            nowMillis = boostNowMillis,
            onBuyPotion = { potion ->
                val extending = activeBoost?.potionId == potion.id
                scope.launch {
                    if (repository.purchasePotion(potion.id)) {
                        toastMessage = if (extending) "${potion.label} 시간이 ${GrowthBoost.durationLabel(potion.durationMinutes)} 늘었어요"
                        else "${potion.label} 시작 — 지금부터 집중 경험치 ${GrowthBoost.multiplierLabel(potion.multiplier)}"
                        refresh()
                    }
                }
            },
            onDismiss = { showShop = false }
        )
    }
}

/** 환생 횟수에 따라 경험치 주입 애니메이션 한 스텝의 재생 시간(ms) — 환생 배율(GrowthSystem.
 *  expMultiplier)이 커질수록 애니메이션도 함께 빨라지게 한다(사용자 요청: "환생에 따라 경험치를 입력하는
 *  속도도 늘어나야", 데스크탑판과 대칭). 환생 0(배율 1배) 기준 16ms/스텝(총 10스텝 ≈ 160ms/구간)에서
 *  시작해 배율의 제곱근에 반비례해 줄어들고, 너무 빨라 손맛이 사라지지 않도록 4ms 밑으로는 안 내려간다. */
private fun expInjectionStepDelayMs(rebirthCount: Int): Long {
    val multiplier = GrowthSystem.expMultiplier(rebirthCount)
    return (16.0 / Math.sqrt(multiplier)).toLong().coerceAtLeast(4L)
}

/** 대기 EXP를 레벨에 적용하는 과정을 단계별로 재생 — 레벨이 여러 번 오르면 "경험치 주입 → 상승 → 레벨업 →
 *  다음 레벨 경험치 주입 → 다시 상승"을 레벨 경계마다 반복한 뒤 마지막 구간을 마저 채운다. */
private suspend fun animateExpApplication(
    result: GrowthSystem.ApplyResult,
    stepDelayMs: Long,
    onProgress: (exp: Double, level: Int) -> Unit,
    onLevelUp: suspend (level: Int) -> Unit
) {
    var level = result.levelBefore
    var currentExp = result.expBefore
    while (level < result.levelAfter) {
        val target = GrowthSystem.cumulativeExpForLevel(level + 1)
        animateExpSegment(currentExp, target, stepDelayMs) { v -> onProgress(v, level) }
        level += 1
        currentExp = target
        onProgress(currentExp, level)
        onLevelUp(level)
    }
    animateExpSegment(currentExp, result.expAfter, stepDelayMs) { v -> onProgress(v, level) }
}

private suspend fun animateExpSegment(from: Double, to: Double, stepDelayMs: Long, onProgress: (Double) -> Unit) {
    if (to <= from) {
        onProgress(to)
        return
    }
    val steps = 10
    for (i in 1..steps) {
        val frac = i / steps.toFloat()
        val eased = 1f - (1f - frac) * (1f - frac)
        onProgress(from + (to - from) * eased)
        delay(stepDelayMs)
    }
    onProgress(to)
}


/**
 * 장면 위 레벨 히어로를 읽히게 하는 스크림(144차) — 바탕색이 위에서 아래로 옅어지며 글자 뒤의 점별을 가린다. 글자가 끝나는 자리까지는
 * 거의 불투명하게 두고 그 아래에서 옅어진다. 150차: [CosmosOverlayTheme] 안에서 그려 라이트 테마에서도 우주와 같은 검정이다(149차엔
 * 종이 → 검정 띠였다 — 사용자 요청으로 없앰). 성능 모드에선 씬 자체를 안 그린다.
 */
@Composable
private fun HomeSkyScrim(modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.background(Brush.verticalGradient(0f to bg.copy(alpha = 0.96f), 0.7f to bg.copy(alpha = 0.92f), 1f to bg.copy(alpha = 0f))))
}

/** 폰 홈 아래쪽 시트 — 떠 있는 둥근 카드 대신 화면 아래 가장자리에 붙은 판(위쪽 모서리만 둥글게). */
@Composable
private fun HomeSheet(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val performance = com.phonelock.app.ui.theme.LocalPerformanceMode.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (performance) 1f else 0.97f),
        content = content
    )
}

/** 홈 화면 상단에 떠 있는 원형 아이콘 버튼(설정 버튼 외형). 150차: 바탕과 표면색이 가까워도 원이 보이게 가는 테두리를 둔다. */
@Composable
private fun HomeIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        modifier = modifier.size(44.dp).pressScale(interaction).clickable(interaction, indication = androidx.compose.material3.ripple(), onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** 홈 화면 설정 진입점 — 118차부터 설정은 탭이 아니라 이 버튼을 통해서만 들어간다. */
@Composable
private fun HomeSettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    HomeIconButton(onClick, modifier) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Outlined.Settings,
            contentDescription = "설정",
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 환생 EXP 배율 표기 — 배율이 지수로 커져서(6.32^n) 후반엔 수천만 배가 되므로, 타일 한 줄에
 *  들어가도록 만/억 단위로 줄여 쓴다(예: 37,780,000 → "3,778만"). */
private fun formatMultiplier(value: Double): String = when {
    value < 1_000 -> "%.1f".format(value)
    value < 10_000 -> "%,d".format(value.toLong())
    value < 100_000_000 -> "%,d만".format((value / 10_000).toLong())
    else -> "%,.1f억".format(value / 100_000_000)
}

/**
 * 레벨 히어로(144차) — 작은 라벨 + 아주 큰 레벨 숫자 + 칭호 + 등급. 경험치를 적용해 레벨이 오르면 숫자가 아래에서
 * 위로 넘어가듯 바뀐다(성능 모드에선 바로 바뀐다). 칭호가 길면 두 줄까지 감싼다(잘라 숨기지 않는다). 모임원 상세 홈 탭도 이걸 쓴다(150차).
 */
@Composable
internal fun HomeLevelHero(
    level: Int,
    stage: GrowthSystem.Stage,
    isMaxLevel: Boolean,
    rebirthCount: Int,
    numberStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier
) {
    val motion = com.phonelock.app.ui.theme.LocalAppMotion.current
    Column(modifier) {
        Overline(if (rebirthCount > 0) "레벨 · 빅뱅 ${rebirthCount}회" else "레벨")
        androidx.compose.animation.AnimatedContent(
            targetState = level,
            transitionSpec = {
                if (motion.reduced || targetState < initialState) {
                    androidx.compose.animation.fadeIn(motion.quick()) togetherWith androidx.compose.animation.fadeOut(motion.quick())
                } else {
                    (androidx.compose.animation.slideInVertically(motion.standard()) { it / 2 } + androidx.compose.animation.fadeIn(motion.standard())) togetherWith
                        (androidx.compose.animation.slideOutVertically(motion.exit()) { -it / 2 } + androidx.compose.animation.fadeOut(motion.exit()))
                }
            },
            label = "levelNumber"
        ) { lvl ->
            Text("$lvl", style = numberStyle, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, softWrap = false)
        }
        Text(
            stage.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2
        )
        Text(
            if (isMaxLevel) "${GrowthSystem.tierName(stage.tier)} · 이번 시즌 최고" else GrowthSystem.tierName(stage.tier),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 성장 패널(108차부터의 값·동작은 그대로) — 144차: 진행 막대 하나 + 수치 세 칸(포인트·대기 경험치·빅뱅) + 연료 줄 +
 * 행동 버튼(경험치 적용 / 상점 / 빅뱅). 레벨 숫자는 [HomeLevelHero]가 따로 크게 보여준다. [detailed](태블릿·미니멀)면
 * 수치 칸에 설명 한 줄씩과 물약 안내를 더한다.
 */
@Composable
private fun HomeGrowthPanel(
    levelProgress: Float,
    isMaxLevel: Boolean,
    rebirthCount: Int,
    multiplier: Double,
    canRebirth: Boolean,
    nextRebirthLevel: Int,
    balance: Int,
    growthExpPending: Double,
    isApplying: Boolean,
    toastMessage: String?,
    activeBoost: GrowthBoost.Window?,
    boostNowMillis: Long,
    onApplyExp: () -> Unit,
    onRebirth: () -> Unit,
    onOpenShop: () -> Unit,
    detailed: Boolean,
    modifier: Modifier = Modifier
) {
    // 경험치 적용 애니메이션이 한 스텝씩 값을 밀어넣을 때 막대가 계단처럼 튀지 않도록 살짝 따라가게 한다.
    val animatedProgress by animateFloatAsState(targetValue = levelProgress, animationSpec = tween(180), label = "levelProgress")
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Overline(if (isMaxLevel) "이번 시즌 최대 레벨" else "다음 레벨까지", Modifier.weight(1f))
            Text(
                if (isMaxLevel) "MAX" else "${Math.round(animatedProgress * 100)}%",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false
            )
        }
        Spacer(Modifier.height(6.dp))
        ProgressLine(animatedProgress, thickness = 6.dp)
        Spacer(Modifier.height(Spacing.md))
        // 빅뱅 칸은 "지금 무엇을 알려줘야 하는가"만 — 가능하면 그 사실, 아니면 다음 빅뱅 레벨, 빅뱅을 했다면 EXP 배율까지.
        val rebirthValue = when {
            canRebirth -> "가능"
            isMaxLevel -> "${rebirthCount}회"
            else -> "Lv.$nextRebirthLevel"
        }
        StatRow {
            HomeStat("포인트", "${balance}P", Modifier.weight(1f))
            HomeStat(
                "대기 경험치",
                if (growthExpPending > 0.0) "+${GrowthSystem.formatExp(growthExpPending)}" else "0",
                Modifier.weight(1f),
                highlight = growthExpPending > 0.0,
                caption = if (detailed && rebirthCount > 0) "EXP ×${formatMultiplier(multiplier)}" else null
            )
            HomeStat(
                if (canRebirth) "빅뱅" else "다음 빅뱅",
                rebirthValue,
                Modifier.weight(1f),
                highlight = canRebirth,
                caption = if (detailed) (if (rebirthCount > 0) "${rebirthCount}회 · ×${formatMultiplier(multiplier)}" else "아직 안 함") else null
            )
        }
        // 138차: 폰 시트는 물약이 켜져 있을 때만 한 줄 덧붙인다(133차의 "덜어내기" 기준 유지), 자세한 패널은 늘 자리를 둔다.
        if (activeBoost != null || detailed) {
            Spacer(Modifier.height(Spacing.md))
            HomeBoostStatus(activeBoost, boostNowMillis, onOpenShop, compact = !detailed, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Spacing.md))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val applyInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Button(
                onClick = onApplyExp,
                enabled = growthExpPending > 0.0 && !isApplying,
                interactionSource = applyInteraction,
                modifier = Modifier.weight(1f).height(48.dp).pressScale(applyInteraction)
            ) { Text(if (isApplying) "적용 중…" else "경험치 적용", maxLines = 1, softWrap = false) }
            if (canRebirth && !isApplying) {
                Button(
                    onClick = onRebirth,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    modifier = Modifier.height(48.dp)
                ) { Text("빅뱅", maxLines = 1, softWrap = false) }
            }
            androidx.compose.material3.OutlinedButton(onClick = onOpenShop, modifier = Modifier.height(48.dp)) {
                Text("상점", maxLines = 1, softWrap = false)
            }
        }
        toastMessage?.let {
            Spacer(Modifier.height(Spacing.sm))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 성장 패널의 수치 한 칸 — 작은 라벨 + 굵은 값(+선택 설명). 값은 한 줄(넘치면 …), 강조 상태면 강조색. */
@Composable
private fun HomeStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    caption: String? = null
) {
    Column(modifier) {
        Overline(label)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        caption?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 물약 효과 줄(138차) — 켜져 있으면 어떤 물약이 몇 배로 얼마나 남았는지. [compact](폰 시트)면 한 줄, 아니면 설명 한 줄을 더하고
 * 효과가 없을 때도 상점 안내로 자리를 지킨다. 누르면 상점이 열린다. 144차: 이모지 대신 왼쪽 색 막대로 상태를 보인다.
 */
@Composable
private fun HomeBoostStatus(
    active: GrowthBoost.Window?,
    nowMillis: Long,
    onClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val potion = active?.let { GrowthBoost.potionById(it.potionId) }
    val title = if (active != null) "${potion?.label ?: "연료"} ${GrowthBoost.multiplierLabel(active.multiplier)}" else "타는 연료 없음"
    val remaining = active?.let { "${GrowthBoost.remainingLabel(it.endMillis - nowMillis)} 남음" }
    val accent = if (active != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp)) {
            if (compact) {
                // 남은 시간은 절대 잘리지 않게 따로 두고, 폭이 모자라면 물약 이름 쪽만 줄인다.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    remaining?.let { Text(" · $it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, softWrap = false) }
                }
            } else {
                Text(title, style = MaterialTheme.typography.labelLarge, color = if (active != null) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (remaining != null) "$remaining · 집중 경험치에 적용 중" else "상점에서 연료를 넣으면 그동안 집중 경험치가 늘어나요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** "오늘" 묶음의 보조 정보 한 줄 — 왼쪽 고정폭 라벨 + 값(라벨 폭이 같아 줄끼리 세로로 가지런히 선다). */
@Composable
private fun HomeTodayLine(label: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp), maxLines = 1, softWrap = false)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 태블릿 "오늘" 카드가 루틴·일정을 다시 읽는 주기 — 루틴 전체를 훑는 조회라 홈의 2초 tick에 묶지 않는다. */
private const val TODAY_SUMMARY_POLL_MS = 30_000L

private data class HomeTodaySummary(
    val routineStreak: Int,
    val routineDoneToday: Int,
    val routineScheduledToday: Int,
    /** 최근 7일(오늘 포함) 일별 루틴 완료율 — -1은 그날 예정된 루틴이 없었다는 뜻(회색 표시). */
    val weekCompletionRates: List<Int>,
    /** 오늘 공부 시간(다른 기기 기록 포함 — [PhoneLockRepository.getTodayStudyLog]). */
    val studySecondsToday: Int,
    /** 완료 안 된 일정 중 오늘 이후로 가장 가까운 것 하나(제목, D-day 라벨). */
    val nextCalendarEvent: Pair<String, String>?
)

/** 계산식은 133차 전에 이 화면이 쓰던 것(데스크탑 홈의 같은 카드와 동일)을 그대로 되살렸다. */
private suspend fun loadHomeTodaySummary(repository: PhoneLockRepository): HomeTodaySummary = withContext(Dispatchers.IO) {
    val today = java.time.LocalDate.now()
    val dateKey = today.toString()
    val routines = repository.getAllRoutines()
    val completedByRoutine = routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
    val scheduledToday = routines.filter { RoutineEngine.isScheduledOn(it, today) }
    // 141차: 일정의 "오늘"은 루틴(자정)과 달리 "하루 시작 기준" — 캘린더 화면과 같은 날을 D-day로 센다.
    val calendarTodayKey = repository.todayCalendarDateKey()
    HomeTodaySummary(
        routineStreak = RoutineEngine.currentStreak(routines, completedByRoutine, today, repository.routineStreakFreezePerWeek),
        routineDoneToday = scheduledToday.count { dateKey in (completedByRoutine[it.id] ?: emptySet()) },
        routineScheduledToday = scheduledToday.size,
        weekCompletionRates = (0..6).map { i ->
            val d = today.minusDays((6 - i).toLong())
            val scheduled = routines.filter { RoutineEngine.isScheduledOn(it, d) }
            if (scheduled.isEmpty()) -1
            else scheduled.count { d.toString() in (completedByRoutine[it.id] ?: emptySet()) } * 100 / scheduled.size
        },
        studySecondsToday = repository.getTodayStudyLog().sumOf { it.seconds },
        nextCalendarEvent = repository.getAllCalendarTasksOnce()
            .filter { it.status != "O" && it.dateKey >= calendarTodayKey }
            .minByOrNull { it.dateKey }
            ?.let { task ->
                val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(calendarTodayKey), java.time.LocalDate.parse(task.dateKey))
                task.name to (if (days == 0L) "D-day" else "D-$days")
            }
    )
}

/**
 * 태블릿·미니멀 홈의 "오늘" 묶음(138차, 데스크탑 홈의 같은 카드와 같은 내용) — 144차: 카드 대신 편집형 섹션. 루틴 연속
 * 기록을 큰 숫자로, 오늘 루틴/집중/다음 일정을 라벨-값 줄로, 최근 7일 루틴 완료율을 같은 높이 막대로. 막대 색은
 * 완료(성공색)·일부(경고색)·0%(오류색)·예정 없음(가는 선 색) — 테마 팔레트에서 꺼낸다(고정색이면 라이트 테마에서 흐렸다).
 * 폰 씬 레이아웃에선 이 컴포저블 자체가 불리지 않으므로 루틴 조회도 돌지 않는다.
 */
@Composable
private fun HomeTodayCard(repository: PhoneLockRepository, refreshKey: Int, modifier: Modifier = Modifier) {
    var summary by remember { mutableStateOf<HomeTodaySummary?>(null) }
    LaunchedEffect(refreshKey) {
        while (true) {
            runCatching { loadHomeTodaySummary(repository) }.onSuccess { summary = it }
            delay(TODAY_SUMMARY_POLL_MS)
        }
    }
    val palette = com.phonelock.app.ui.theme.LocalPhoneLockPalette.current
    LedgerSection("오늘", modifier = modifier) {
        val s = summary
        if (s == null) {
            Text("불러오는 중…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (s.routineStreak > 0) {
                BigNumber("${s.routineStreak}", unit = "일 연속", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            } else {
                Text("오늘부터 시작해봐요", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.height(Spacing.sm))
            if (s.routineScheduledToday > 0) HomeTodayLine("루틴", "${s.routineDoneToday}/${s.routineScheduledToday} 완료")
            HomeTodayLine("집중", GrowthBoost.durationLabel(s.studySecondsToday / 60))
            s.nextCalendarEvent?.let { (title, ddayLabel) -> HomeTodayLine(ddayLabel, title) }
            Spacer(Modifier.height(Spacing.md))
            Overline("최근 7일 루틴")
            Spacer(Modifier.height(Spacing.sm))
            // 막대는 같은 높이의 트랙 위에 세워 바닥선을 맞춘다(데스크탑 카드와 같은 규칙).
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                s.weekCompletionRates.forEach { pct ->
                    val barColor = when {
                        pct < 0 -> MaterialTheme.colorScheme.outlineVariant
                        pct == 100 -> palette.fillGood
                        pct > 0 -> palette.fillPartial
                        else -> palette.fillBad
                    }
                    val heightFrac = if (pct < 0) 0.15f else (pct / 100f).coerceAtLeast(0.15f)
                    Box(
                        Modifier.width(14.dp).height(32.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(Modifier.fillMaxWidth().height((32 * heightFrac).dp).background(barColor, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
    }
}

/**
 * 홈 "상점"(138차, 데스크탑판과 대칭) — 연료([GrowthBoost.POTIONS]): 사는 즉시 타기 시작하는 소모품. 한 번에 하나,
 * 같은 연료는 시간 연장. 149차: 식물 장면 위에 얹던 "꾸미기" 칸은 장면이 우주로 바뀌면서 없앴다(산 포인트는 돌려주지
 * 않는다 — 사용자 결정, 저장 칸은 동기화 호환을 위해 남겨 둠).
 */
@Composable
private fun HomeShopDialog(
    balance: Int,
    activeBoost: GrowthBoost.Window?,
    nowMillis: Long,
    onBuyPotion: (GrowthBoost.Potion) -> Unit,
    onDismiss: () -> Unit
) {
    LedgerAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("상점") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Overline("연료", Modifier.weight(1f))
                    Text(
                        "${balance}P",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    run {
                        val activePotion = activeBoost?.let { GrowthBoost.potionById(it.potionId) }
                        Text(
                            if (activeBoost != null) "지금 ${activePotion?.label ?: "연료"} 타는 중 · ${GrowthBoost.remainingLabel(activeBoost.endMillis - nowMillis)} 남음\n같은 연료를 또 넣으면 시간이 늘어나요(최대 ${GrowthBoost.durationLabel(GrowthBoost.MAX_REMAINING_MINUTES)})."
                            else "넣으면 바로 타기 시작해요(한 번에 하나).\n타는 동안 집중으로 얻는 경험치가 늘어나요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GrowthBoost.POTIONS.forEach { potion ->
                            val check = GrowthBoost.checkPurchase(listOfNotNull(activeBoost), potion, nowMillis)
                            val buyable = check == GrowthBoost.PurchaseCheck.NEW || check == GrowthBoost.PurchaseCheck.EXTEND
                            ShopItemRow(
                                icon = {
                                    Box(
                                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) { Text(potion.emoji, fontSize = 24.sp) }
                                },
                                title = potion.label,
                                tag = "${GrowthBoost.multiplierLabel(potion.multiplier)} · ${GrowthBoost.durationLabel(potion.durationMinutes)}",
                                description = potion.description,
                                // 살 수 없는 이유는 버튼에 긴 글자로 넣지 않고 버튼 옆 짧은 안내로 — 좁은 폰에서 버튼 글자가 깨졌다.
                                note = when {
                                    check == GrowthBoost.PurchaseCheck.OTHER_ACTIVE -> "다른 연료가 타는 중"
                                    check == GrowthBoost.PurchaseCheck.TOO_LONG -> "이미 최대 ${GrowthBoost.durationLabel(GrowthBoost.MAX_REMAINING_MINUTES)}"
                                    balance < potion.cost -> "${potion.cost - balance}P 부족"
                                    else -> null
                                },
                                actionLabel = if (check == GrowthBoost.PurchaseCheck.EXTEND) "${potion.cost}P 연장" else "${potion.cost}P 구매",
                                actionEnabled = buyable && balance >= potion.cost,
                                onAction = { onBuyPotion(potion) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

/**
 * 상점 한 줄(138차) — 그림 | 이름·태그·설명, 그 아래 줄에 안내 문구와 버튼. 버튼을 이름 옆에 두면 좁은 폰에서
 * 이름·배율 태그·설명이 글자 단위로 줄바꿈돼 읽기 어려웠다(사용자 지적) — 글 영역이 그림 오른쪽 폭을 전부 쓰게 했다.
 */
@Composable
private fun ShopItemRow(
    icon: @Composable () -> Unit,
    title: String,
    tag: String?,
    description: String,
    note: String?,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.Top) {
        icon()
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                tag?.let {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)) {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 안내는 한 줄로 끝나는 짧은 말만 쓴다(길면 버튼 옆에서 두 줄로 접혀 버튼과 어긋났다).
                Text(
                    note.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onAction, enabled = actionEnabled) {
                    Text(actionLabel, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * 레벨업 연출 — 144차: 화면 가운데 바탕색 판 위에 "레벨업" 라벨과 새 레벨 숫자를 아주 크게, 짧게 커졌다가 사라진다
 * (판이 있어 어떤 하늘 위에서도 읽힌다). 성능 모드에선 크기 변화 없이 짧은 페이드만.
 */
@Composable
private fun LevelUpFlash(level: Int?, modifier: Modifier = Modifier) {
    val motion = com.phonelock.app.ui.theme.LocalAppMotion.current
    AnimatedVisibility(
        visible = level != null,
        modifier = modifier,
        enter = if (motion.reduced) fadeIn(motion.quick()) else scaleIn(initialScale = 0.7f, animationSpec = motion.standard()) + fadeIn(motion.quick()),
        exit = if (motion.reduced) fadeOut(motion.quick()) else scaleOut(targetScale = 1.1f, animationSpec = tween(300)) + fadeOut(tween(300))
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
            Column(Modifier.padding(horizontal = 36.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Overline("레벨업", color = MaterialTheme.colorScheme.primary)
                level?.let { lvl ->
                    Text(
                        "$lvl",
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 90.sp),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * 홈의 "씬 영역"(122차) — 우주 장면과 그 위에 떠 있는 것들(상단 버튼 / 레벨업 연출)을
 * 한 묶음으로 만든다. 폰·세로 태블릿·가로 태블릿 세 레이아웃이 전부 이 영역을 그대로 재사용하고,
 * 달라지는 건 이 영역에 주는 공간과 성장 패널의 위치뿐이다. [CosmosScene]은 [contentTopInset]/[contentBottomInset]을
 * 뺀 영역 가운데에 천체를 그려, 레벨 숫자·HUD에 가려지지 않는다.
 */
@Composable
private fun HomeSceneArea(
    stage: GrowthSystem.Stage,
    levelUpFlash: Int?,
    topEndControls: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    contentTopInset: androidx.compose.ui.unit.Dp = 0.dp,
    contentBottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier) {
        CosmosScene(
            illustrationId = stage.illustrationId,
            modifier = Modifier.fillMaxSize(),
            contentTopInset = contentTopInset,
            contentBottomInset = contentBottomInset
        )
        // 133차: 씨 위에 얹는 건 우상단 설정 버튼 하나뿐이다 — 오늘 요약 카드는 런처 홈으로 옮기고
        // 꾸미기 버튼은 아래 성장 HUD로 합쳌다("카드가 하늘을 과하게 가린다"는 사용자 지적, 133차).
        overlay()
        // 150차: 버튼은 overlay(레벨 숫자 뒤 스크림) 위에 그린다 — 예전엔 스크림 밑에 깔려 흐리게 보였다. 장면 위라 장면 색으로.
        if (topEndControls != null) {
            CosmosOverlayTheme {
                Box(Modifier.align(Alignment.TopEnd).padding(Spacing.lg)) { topEndControls() }
            }
        }
        LevelUpFlash(levelUpFlash, Modifier.align(Alignment.Center))
    }
}
