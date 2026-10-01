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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.applyPendingGrowthExp
import com.phonelock.app.data.equippedDecorationIds
import com.phonelock.app.data.getGrowthExpPending
import com.phonelock.app.data.getGrowthExpTotal
import com.phonelock.app.data.getPointsBalance
import com.phonelock.app.data.getRebirthCount
import com.phonelock.app.data.MAX_EQUIPPED_DECORATIONS
import com.phonelock.app.data.syncGrowthFromFirebase
import com.phonelock.app.data.syncPointsFromFirebase
import com.phonelock.app.data.observePointsBalance
import com.phonelock.app.data.ownedDecorationIds
import com.phonelock.app.data.purchaseDecoration
import com.phonelock.app.data.rebirth
import com.phonelock.app.data.setEquippedDecorationIds
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 홈(식물) 화면이 저장값을 다시 읽는 주기(121차) — EXP는 이 화면 밖(공부 타이머·루틴 체크·다른 기기
 *  동기화)에서도 쌓이므로, 화면이 떠 있는 동안 이 간격으로 다시 읽어 표시가 실제 값과 벌어지지 않게 한다. */
private const val GROWTH_POLL_INTERVAL_MS = 2_000L

/**
 * "식물" 탭(105차 신설, 108차 게임성 강화 개편, 109차 500레벨/등급 체계 개편, 데스크탑판과 대칭) —
 * 레벨/칭호는 `shared/GrowthSystem.kt` 하나로 계산한다. 108차부터 EXP는 적립 즉시 레벨에 반영되지 않고
 * "대기 EXP"([PhoneLockRepository.getGrowthExpPending])로 먼저 쌓이며, 사용자가 아래쪽 HUD의
 * "경험치 적용" 버튼을 눌러야 그 순간 [PhoneLockRepository.applyPendingGrowthExp]가 레벨에 실제로
 * 반영한다 — 그 반영 과정을 경험치바가 차오르고(레벨업 시 넘치면 다음 레벨로 이어서) 레벨업 연출이 뜨는
 * 애니메이션으로 보여줘서 사용자가 레벨업 과정에 직접 참여하는 느낌을 준다(사용자 요청). 환생도 기존부터
 * 버튼+확인 다이얼로그로 이미 수동이었다(자동 환생 로직 없음). 보상함(포인트→보상 교환) UI는 108차에
 * 완전히 삭제됨.
 */
@Composable
fun PlantScreen(
    repository: PhoneLockRepository,
    permPlant: Boolean = true,
    /** 130차 미니멀 모드 — 움직이는 식물 씬(GroundScene) 대신 레벨/오늘 요약 카드만 세로로 쌓는다. */
    minimalMode: Boolean = false,
    onOpenSettings: () -> Unit = {}
) {
    if (!permPlant) {
        // 관리자가 이 사용자의 식물 기능을 껐어도 홈은 항상 존재해야 설정 진입점(우상단 버튼)을 잃지 않는다.
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.Center).padding(Spacing.lg)) {
                Text(
                    "식물 기능이 비활성화되어 있습니다.",
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

    // 진입 시 원격과 맞춰본다 — 성장 값(레벨/EXP/장식)은 포인트 원장에서 파생되므로 둘을 같이 당겨와야
    // "포인트는 최신인데 나무만 옛날"이 생기지 않는다.
    LaunchedEffect(Unit) {
        repository.syncPointsFromFirebase()
        if (repository.syncGrowthFromFirebase()) refresh()
    }

    var showShop by remember { mutableStateOf(false) }
    val equippedDecorations = remember(growthTick) { repository.equippedDecorationIds }
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
    val stageIndex = GrowthSystem.STAGES.indexOf(stage)
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
    // 당겨온 뒤 화면이 쓰는 값(레벨/EXP/식물/포인트/꾸미기/루틴·캘린더 요약)을 전부 재조회한다. 이미
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

    // 122차: 태블릿에서 하단 전폭 HUD가 화분과 꾸미기 소품을 그대로 덮어버린다는 지적 → 화면 폭에 따라
    // 배치를 바꾼다. 화면 크기를 Configuration이 아니라 실제 할당 폭(BoxWithConstraints)으로 판단해서
    // 좌측 NavigationRail이 폭을 먹는 경우나 특이한 화면 비율에서도 어긋나지 않게 했다.
    //  - 넓은 가로(840dp+, 가로 태블릿/큰 화면): 씬 | 성장 패널 좌우 2열 — HUD가 씬을 아예 안 덮는다.
    //  - 태블릿 폭(600dp+): 씬 | HUD 위아래 2단 — 씬이 자기 영역 안에서 다시 중앙 정렬되므로 화분이
    //    HUD 위로 올라오고, 소품도 씬 영역 안에 전부 들어온다.
    //  - 폰: 기존처럼 씬을 전면에 깔고 HUD를 아래쪽에 겹쳐 띄우되, 화분/소품은 HUD 위 영역에 그린다(contentBottomInset).
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
                        stageIndex = stageIndex, stage = stage, rebirthCount = rebirthCount,
                        decorationIds = equippedDecorations, levelUpFlash = levelUpFlash,
                        topEndControls = null,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // 오른쪽 패널 — 레벨 히어로 + 자세한 성장 + "오늘". 씬에는 식물/꾸미기만 온전히 보인다.
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
                    HomeSceneArea(
                        stageIndex = stageIndex, stage = stage, rebirthCount = rebirthCount,
                        decorationIds = equippedDecorations, levelUpFlash = levelUpFlash,
                        topEndControls = controls,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        HomeSkyScrim(Modifier.align(Alignment.TopStart).fillMaxWidth().height(220.dp))
                        levelHero(Modifier.align(Alignment.TopStart).padding(Spacing.lg), MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp, lineHeight = 82.sp, letterSpacing = (-3).sp))
                    }
                    Hairline()
                    // 세로 태블릿: 씬 아래 영역에 자세한 성장과 "오늘"을 나란히(식물을 가리지 않는다).
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
                    // 폰: 씬을 전면에 깔고 아래쪽에 시트를 붙인다. 시트가 덮는 만큼 씬에 알려줘서 화분/소품이 그 위에 그려지게 한다.
                    var hudHeightPx by remember { mutableIntStateOf(0) }
                    val hudHeight = if (hudHeightPx > 0) with(LocalDensity.current) { hudHeightPx.toDp() } else 250.dp
                    HomeSceneArea(
                        stageIndex = stageIndex, stage = stage, rebirthCount = rebirthCount,
                        decorationIds = equippedDecorations, levelUpFlash = levelUpFlash,
                        topEndControls = controls,
                        modifier = Modifier.fillMaxSize(),
                        contentBottomInset = hudHeight + Spacing.md
                    ) {
                        HomeSkyScrim(Modifier.align(Alignment.TopStart).fillMaxWidth().height(200.dp))
                        levelHero(Modifier.align(Alignment.TopStart).padding(start = Spacing.gutter, top = Spacing.md, end = 88.dp), MaterialTheme.typography.displayLarge.copy(fontSize = 76.sp, lineHeight = 78.sp, letterSpacing = (-3).sp))
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
            title = { Text("환생하시겠습니까?") },
            text = {
                // 138차: 배율이 지수라 후반엔 "×15913789.3"처럼 길어져 만/억 표기로 줄이고, 적용 안 한 대기 경험치는
                // 환생과 함께 사라지는데 그걸 알려주지 않아 모르고 잃을 수 있어서 있을 때만 한 줄 덧붙인다.
                Text(
                    "현재 레벨과 경험치가 초기화되고 씨앗부터 다시 시작합니다. 대신 앞으로 얻는 경험치 배율이 " +
                        "×${formatMultiplier(GrowthSystem.expMultiplier(rebirthCount + 1))}로 영구히 올라갑니다." +
                        if (growthExpPending > 0.0) "\n\n아직 적용하지 않은 대기 경험치 +${GrowthSystem.formatExp(growthExpPending)}도 함께 사라집니다. 먼저 \"✨ 경험치 적용\"을 누르면 레벨에 반영돼요." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = repository.rebirth()
                    toastMessage = if (ok) "환생했습니다! 이제 더 빠르게 자랄 거예요 🌱" else "아직 환생 조건을 만족하지 못했습니다"
                    showRebirthDialog = false
                    refresh()
                }) { Text("환생한다") }
            },
            dismissButton = { TextButton(onClick = { showRebirthDialog = false }) { Text("취소") } }
        )
    }

    if (showShop) {
        HomeShopDialog(
            balance = balance,
            ownedIds = remember(growthTick) { repository.ownedDecorationIds },
            equippedIds = remember(growthTick) { repository.equippedDecorationIds },
            activeBoost = activeBoost,
            nowMillis = boostNowMillis,
            onBuyPotion = { potion ->
                val extending = activeBoost?.potionId == potion.id
                scope.launch {
                    if (repository.purchasePotion(potion.id)) {
                        toastMessage = if (extending) "${potion.emoji} ${potion.label} 효과 시간이 ${GrowthBoost.durationLabel(potion.durationMinutes)} 늘었어요"
                        else "${potion.emoji} ${potion.label} 효과 시작 — 지금부터 집중 경험치 ${GrowthBoost.multiplierLabel(potion.multiplier)}"
                        refresh()
                    }
                }
            },
            onBuyDecoration = { deco -> scope.launch { if (repository.purchaseDecoration(deco.id, deco.cost)) refresh() } },
            onSetEquipped = { ids ->
                repository.setEquippedDecorationIds(ids)
                refresh()
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

// ══════════════════════════════════════════════════════
// 땅 배경 + 식물 일러스트(109차 전면 개편, 데스크탑판과 대칭) — 등급(Stage.tier)마다 하늘/땅/나무 형태
// 자체가 달라진다. 0=정상(평범한 나무) 1=이상함(뒤틀린 나무) 2=초월급(신성한 화신) 3=종말급(재앙의 존재)
// 4=최강자급(세계수). 칭호(stage.title)와 illustrationId가 1:1로 짝지어져 있어 텍스트와 그림이 항상 일치한다.
// ══════════════════════════════════════════════════════

private data class GrowthAnim(
    val swayPx: Float,
    val pulse: Float,
    val rotate: Float,
    val shakeX: Float,
    val shakeY: Float,
    val flicker: Float
)

/**
 * 하늘 위 레벨 히어로를 읽히게 하는 스크림(144차) — 테마 바탕색이 위에서 아래로 옅어진다. 등급마다 하늘색이 달라도
 * (낮 하늘·우주·불길) 글자 뒤가 항상 바탕색이라 대비가 일정하다. 성능 모드에선 씬 자체를 안 그리므로 쓰이지 않는다.
 */
@Composable
private fun HomeSkyScrim(modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.92f), bg.copy(alpha = 0.55f), bg.copy(alpha = 0f)))))
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

/** 홈 화면 상단에 떠 있는 원형 아이콘 버튼(설정 버튼 외형). */
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
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

/** 등급 라벨 — 칭호는 23단계라 자주 바뀌지만 등급은 5개뿐이라, "지금 어느 대에 있는가"를 한 줄로 준다
 *  ([GrowthSystem.Stage.tier]가 그대로 배경/나무 실루엣을 결정하므로 화면과도 항상 일치한다). */
private fun tierLabel(tier: Int): String = when (tier) {
    0 -> "정상"
    1 -> "이상함"
    2 -> "초월급"
    3 -> "종말급"
    else -> "최강자급"
}

/**
 * 레벨 히어로(144차) — 작은 라벨 + 아주 큰 레벨 숫자 + 칭호 + 등급. 경험치를 적용해 레벨이 오르면 숫자가 아래에서
 * 위로 넘어가듯 바뀐다(성능 모드에선 바로 바뀐다). 칭호가 길면 두 줄까지 감싼다(잘라 숨기지 않는다).
 */
@Composable
private fun HomeLevelHero(
    level: Int,
    stage: GrowthSystem.Stage,
    isMaxLevel: Boolean,
    rebirthCount: Int,
    numberStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier
) {
    val motion = com.phonelock.app.ui.theme.LocalAppMotion.current
    Column(modifier) {
        Overline(if (rebirthCount > 0) "레벨 · 환생 ${rebirthCount}회" else "레벨")
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
            if (isMaxLevel) "${tierLabel(stage.tier)} · 이번 시즌 최고" else tierLabel(stage.tier),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 성장 패널(108차부터의 값·동작은 그대로) — 144차: 진행 막대 하나 + 수치 세 칸(포인트·대기 경험치·환생) + 물약 줄 +
 * 행동 버튼(경험치 적용 / 상점 / 환생). 레벨 숫자는 [HomeLevelHero]가 따로 크게 보여준다. [detailed](태블릿·미니멀)면
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
        // 환생 칸은 "지금 무엇을 알려줘야 하는가"만 — 가능하면 그 사실, 아니면 다음 환생 레벨, 환생했다면 EXP 배율까지.
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
                if (canRebirth) "환생" else "다음 환생",
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
                ) { Text("환생", maxLines = 1, softWrap = false) }
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
    val title = if (active != null) "${potion?.label ?: "성장 물약"} ${GrowthBoost.multiplierLabel(active.multiplier)}" else "사용 중인 물약 없음"
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
                    if (remaining != null) "$remaining · 집중 경험치에 적용 중" else "상점에서 물약을 사면 그동안 집중 경험치가 늘어나요",
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
 * 홈 "상점"(138차, 사용자 요청: 꾸미기 → 상점 개편, 데스크탑판과 대칭). 두 칸으로 나눈다.
 * - 🧪 물약: 사는 즉시 효과가 시작되는 소모품([GrowthBoost.POTIONS]). 한 번에 하나, 같은 물약은 시간 연장.
 * - 🎨 꾸미기: 한 번 사면 영구 소유하고 최대 [MAX_EQUIPPED_DECORATIONS]개까지 홈 장면에 배치(116·121차 그대로).
 * 물약 칸을 먼저 연다 — 꾸미기는 다 사고 나면 더 쓸 데가 없지만 물약은 매일 쓰는 포인트 소비처라 상점을 여는
 * 이유의 대부분이 된다.
 */
@Composable
private fun HomeShopDialog(
    balance: Int,
    ownedIds: Set<String>,
    equippedIds: List<String>,
    activeBoost: GrowthBoost.Window?,
    nowMillis: Long,
    onBuyPotion: (GrowthBoost.Potion) -> Unit,
    onBuyDecoration: (DecorationItem) -> Unit,
    onSetEquipped: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    LedgerAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🛒 상점") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShopTabChip("🧪 물약", tab == 0) { tab = 0 }
                    ShopTabChip("🎨 꾸미기", tab == 1) { tab = 1 }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "🪙 ${balance}P",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (tab == 0) {
                        val activePotion = activeBoost?.let { GrowthBoost.potionById(it.potionId) }
                        Text(
                            if (activeBoost != null) "지금 ${activePotion?.label ?: "물약"} 효과 중 · ${GrowthBoost.remainingLabel(activeBoost.endMillis - nowMillis)} 남음\n같은 물약을 사면 시간이 늘어나요(최대 ${GrowthBoost.durationLabel(GrowthBoost.MAX_REMAINING_MINUTES)})."
                            else "사면 바로 효과가 시작돼요(한 번에 하나).\n효과 시간 동안 집중으로 얻는 경험치가 늘어나요.",
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
                                    check == GrowthBoost.PurchaseCheck.OTHER_ACTIVE -> "다른 물약 효과 중"
                                    check == GrowthBoost.PurchaseCheck.TOO_LONG -> "이미 최대 ${GrowthBoost.durationLabel(GrowthBoost.MAX_REMAINING_MINUTES)}"
                                    balance < potion.cost -> "${potion.cost - balance}P 부족"
                                    else -> null
                                },
                                actionLabel = if (check == GrowthBoost.PurchaseCheck.EXTEND) "${potion.cost}P 연장" else "${potion.cost}P 구매",
                                actionEnabled = buyable && balance >= potion.cost,
                                onAction = { onBuyPotion(potion) }
                            )
                        }
                    } else {
                        Text(
                            "한 번 사면 계속 가져요.\n홈에 ${MAX_EQUIPPED_DECORATIONS}개까지 놓을 수 있어요 · 지금 ${equippedIds.size}개",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DECORATION_CATALOG.forEach { deco ->
                            val owned = deco.id in ownedIds
                            val equipped = deco.id in equippedIds
                            ShopItemRow(
                                // 121차: 이모지 대신 실제로 홈 화면에 그려질 그림 그대로를 미리보기로 보여준다.
                                icon = { DecorationPreview(deco, Modifier.size(48.dp)) },
                                title = deco.label,
                                tag = if (deco.kind == DecorationKind.SCENERY) "배경" else null,
                                description = deco.description,
                                note = when {
                                    equipped -> "홈에 놓여 있어요"
                                    owned && equippedIds.size >= MAX_EQUIPPED_DECORATIONS -> "자리가 꽉 찼어요"
                                    owned -> "보유 중"
                                    balance < deco.cost -> "${deco.cost - balance}P 부족"
                                    else -> null
                                },
                                actionLabel = when {
                                    !owned -> "${deco.cost}P 구매"
                                    equipped -> "빼기"
                                    else -> "놓기"
                                },
                                actionEnabled = when {
                                    !owned -> balance >= deco.cost
                                    equipped -> true
                                    else -> equippedIds.size < MAX_EQUIPPED_DECORATIONS
                                },
                                onAction = {
                                    when {
                                        !owned -> onBuyDecoration(deco)
                                        equipped -> onSetEquipped(equippedIds - deco.id)
                                        else -> onSetEquipped(equippedIds + deco.id)
                                    }
                                }
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

/** 상점 칸 전환 칩 — 고른 칸은 채운 색, 나머지는 옅은 색. */
@Composable
private fun ShopTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
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
 * 홈의 "씬 영역"(122차) — 식물 캔버스와 그 위에 떠 있는 것들(상단 버튼 / 레벨업 연출)을
 * 한 묶음으로 만든다. 폰·세로 태블릿·가로 태블릿 세 레이아웃이 전부 이 영역을 그대로 재사용하고,
 * 달라지는 건 이 영역에 주는 공간과 성장 패널의 위치뿐이다. [GroundScene]은 자기에게 할당된 영역 기준으로
 * 화분 위치를 계산하므로, 영역만 나눠주면 화분/꾸미기 소품이 HUD에 가려지지 않는다.
 */
@Composable
private fun HomeSceneArea(
    stageIndex: Int,
    stage: GrowthSystem.Stage,
    rebirthCount: Int,
    decorationIds: List<String>,
    levelUpFlash: Int?,
    topEndControls: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    contentBottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier) {
        GroundScene(
            stageIndex = stageIndex,
            stage = stage,
            rebirthCount = rebirthCount,
            decorationIds = decorationIds,
            modifier = Modifier.fillMaxSize(),
            contentBottomInset = contentBottomInset
        )
        // 133차: 씨 위에 얹는 건 우상단 설정 버튼 하나뿐이다 — 오늘 요약 카드는 런처 홈으로 옮기고
        // 꾸미기 버튼은 아래 성장 HUD로 합쳌다("카드가 하늘을 과하게 가린다"는 사용자 지적, 133차).
        if (topEndControls != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(Spacing.lg)) { topEndControls() }
        }
        overlay()
        LevelUpFlash(levelUpFlash, Modifier.align(Alignment.Center))
    }
}

private const val RAD2DEG = 57.29578f
private const val SHAKE_MARGIN = 34f

/** 등급별 애니메이션 파라미터 — 정상은 바람에 살랑이는 정도, 등급이 오를수록 그 컨셉에 맞는 움직임으로
 *  바뀐다(이상함=불규칙한 경련, 초월급=오라 맥동+서서히 회전, 종말급=화면 진동+깜빡임, 최강자급=전부 결합). */
private fun growthAnimForTier(tier: Int, tMs: Float): GrowthAnim {
    val s = tMs / 1000f
    return when (tier) {
        1 -> GrowthAnim(sin(s * 1.6f) * 2f + sin(s * 6.1f) * 2.6f, 1f, 0f, 0f, 0f, 1f)
        2 -> GrowthAnim(sin(s * 0.7f) * 1.6f, 1f + sin(s * 1.6f) * 0.09f, s * 0.35f, 0f, 0f, 1f)
        3 -> GrowthAnim(
            sin(s * 0.6f) * 1.2f, 1f + sin(s * 2.3f) * 0.07f, s * 0.25f,
            sin(s * 9.3f) * 2.2f, cos(s * 7.7f) * 2.2f, 0.65f + abs(sin(s * 5.5f)) * 0.35f
        )
        4 -> GrowthAnim(
            sin(s * 0.5f) * 1f, 1f + sin(s * 2.8f) * 0.14f, s * 0.55f,
            sin(s * 11.3f) * 3f, cos(s * 13.1f) * 3f, if (sin(s * 6.5f) > 0.15f) 1f else 0.35f
        )
        else -> GrowthAnim(sin(s * 1.3f) * 3f, 1f, 0f, 0f, 0f, 1f)
    }
}

@Composable
fun GroundScene(
    stageIndex: Int,
    stage: GrowthSystem.Stage,
    rebirthCount: Int,
    decorationIds: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    /** 캔버스 아래쪽을 다른 UI(폰 홈의 성장 HUD)가 덮는 높이(122차) — 화분/나무/소품은 이만큼 뺀 영역
     *  기준으로 배치해서 가려지지 않게 하고, 땅 배경만 캔버스 끝까지 칠해 전면 배경 느낌은 유지한다. */
    contentBottomInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    val startTime = remember { System.nanoTime() }
    var nowMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { t -> nowMs = (t - startTime) / 1_000_000f }
        }
    }

    // tier2+ 장식·흔들림 효과가 이 컴포저블에 할당된 영역(좌측 NavigationRail 옆 콘텐츠 영역, 태블릿 기준) 밖으로
    // 번져 나가 탭 바를 가리지 않도록 그리기 자체를 자기 경계 안으로 가둔다.
    //
    // 134차: 시간에 따라 모양이 바뀌지 않는 배경 도형(언덕·풀 포기·울타리 말뚝)은 drawWithCache 블록에서 크기·등급·
    // 꾸미기가 바뀔 때만 한 번 만든다. 매 프레임 수백 개의 Path를 새로 만들면 GPU 경로 캐시가 계속 갈려 나가
    // 느려지고(에뮬레이터는 아예 죽었다) 배터리도 먹는다. 같은 이유로 애니메이션 시각(nowMs)은 컴포지션이 아니라
    // 그리기 단계에서만 읽는다 — 예전엔 컴포지션에서 읽어 이 컴포저블 전체가 매 프레임 다시 구성됐다.
    Spacer(
        modifier.clipToBounds().drawWithCache {
            val w = size.width
            val fullH = size.height
            // 콘텐츠 높이가 너무 줄어 나무가 찌그러지지 않도록 전체의 절반 아래로는 줄이지 않는다.
            val h = (fullH - contentBottomInset.toPx()).coerceAtLeast(fullH * 0.5f)
            val scale = (min(w, h) / 400f).coerceIn(0.7f, 3.5f)
            val tier = stage.tier
            val horizonY = h * HORIZON_FRACTION
            val margin = SHAKE_MARGIN * scale
            val cx = w / 2f
            // 땅을 "깊이"가 있는 정원으로 본다 — 지평선(깊이 0)부터 캔버스 앞 가장자리(깊이 1)까지. 화분은 깊이
            // POT_DEPTH에 놓이고, 꾸미기 소품은 각자 정해진 깊이에서 원근에 맞는 크기로 그려진다.
            val sceneAt = { t: Float -> DecorationScene(w = w, h = h, scale = scale, horizonY = horizonY, tMs = t, plantAnchor = Offset(cx, horizonY)) }
            val staticScene = sceneAt(0f)
            val pot = PotGeometry(cx, staticScene.yAt(POT_DEPTH), scale)
            val hills = buildHills(tier, w, horizonY, scale, margin)
            val tufts = buildGroundTufts(tier, staticScene, pot)
            val fence = if ("fence" in decorationIds) buildFence(staticScene) else null

            onDrawBehind {
                val tMs = nowMs
                val anim = growthAnimForTier(tier, tMs)
                val baseScene = sceneAt(tMs)
                translate(anim.shakeX * scale, anim.shakeY * scale) {
                    drawSky(tier, w, h, horizonY, scale, tMs, margin)
                    drawHillScenery(hills)
                    drawGroundLayer(tier, w, fullH, h, horizonY, margin)
                    drawGroundTufts(tufts)

                    if (tier == 3) drawCrackedGroundPatch(w, h, scale, 1 + (stageIndex - 17).coerceAtLeast(0), 0.35f + anim.flicker * 0.3f)
                    if (tier == 4) drawCrackedGroundPatch(w, h, scale, 3, 0.4f + anim.flicker * 0.3f)
                    if (tier >= 2) drawCosmicBackdrop(tier, w, h, scale, anim, margin)

                    // 화분보다 멀리 있는 꾸미기(울타리·길·연못 같은 배경과 화분 뒤편 소품)는 화분·식물보다 먼저 그린다.
                    drawDecorationsBehindPot(decorationIds, baseScene, fence)
                    drawPot(tier, pot, anim, tMs)

                    val soilY = pot.soilY
                    val isTree = stageIndex >= 8 // "든든한 나무"(Lv.95)부터 실제 가지 뻗은 나무
                    val anchor: Offset = when {
                        !isTree -> drawYoungPlant(cx, soilY, h * 0.30f, scale, stageIndex, anim.swayPx, tMs)
                        tier == 0 -> drawBranchingTree(
                            cx, soilY, h * 0.42f, scale,
                            (1f + (stageIndex - 8) * 0.35f).coerceAtMost(3.2f), anim.swayPx,
                            Color(0xFF5FA043), Color(0xFF7A5A36), twisted = false, fruit = stageIndex >= 9
                        )
                        tier == 1 -> drawBranchingTree(
                            cx, soilY, h * 0.44f, scale,
                            3.2f + (stageIndex - 12) * 0.15f, anim.swayPx,
                            Color(0xFF7C8B6E), Color(0xFF6B5A48), twisted = true, fruit = false
                        )
                        tier == 2 -> drawRadiantTree(cx, soilY, h * 0.5f, scale, (stageIndex - 15) / 3f, anim, w)
                        tier == 3 -> drawCorruptedTree(cx, soilY, h * 0.56f, scale, (stageIndex - 18) / 3f, anim, w)
                        else -> drawWorldTree(cx, soilY, h * 0.68f, scale, (stageIndex - 21) / 2f, anim, w)
                    }

                    if (rebirthCount > 0) drawRebirthAura(cx, soilY - h * 0.1f, scale, rebirthCount)

                    drawGrowthIllustration(stage.illustrationId, cx, soilY, anchor, w, h, scale, anim)
                    // 화분보다 앞에 놓인 소품(화분 발치를 살짝 가린다) → 공중에 떠다니는 것(나비·반딧불이) 순서.
                    val scene = baseScene.copy(plantAnchor = anchor)
                    drawDecorationsInFrontOfPot(decorationIds, scene)
                    drawAirborneDecorations(decorationIds, scene)
                    if (tier == 3) drawEmberOverlay(w, h, scale, anim, tMs)
                    if (tier == 4) drawTranscendentOverlay(w, h, scale, anim, tMs)
                }
            }
        }
    )
}

/** 지평선 높이(캔버스 콘텐츠 높이 대비). 134차에 0.2 → 0.4 — 하늘이 좁은 띠로만 남고 평평한 초록 땅이
 *  화면 대부분을 덮어 밋밋했다(사용자 지적 "홈 탭 퀄리티"). */
private const val HORIZON_FRACTION = 0.4f

/** 화분이 놓이는 땅의 깊이(0 = 지평선, 1 = 캔버스 앞 가장자리). 이 깊이에서 원근 배율이 정확히 1이다. */
internal const val POT_DEPTH = 0.74f

/** 등급별 하늘(위, 지평선 쪽). */
private val SKY_COLORS = listOf(
    Color(0xFFA6D7F5) to Color(0xFFE9F6FF),
    Color(0xFF8FA08A) to Color(0xFFC7D3BE),
    Color(0xFF241344) to Color(0xFF3E2564),
    Color(0xFF3E1210) to Color(0xFF8A2E1C),
    Color(0xFF07040D) to Color(0xFF170C28)
)

/** 등급별 땅(먼 곳, 중간, 가까운 곳) — 멀수록 옅게(대기 원근), 가까울수록 짙게. */
private val GROUND_COLORS = listOf(
    Triple(Color(0xFFB2D98A), Color(0xFF93C85A), Color(0xFF6FA83E)),
    Triple(Color(0xFF9AA586), Color(0xFF7C8B5C), Color(0xFF5C6843)),
    Triple(Color(0xFF6A5A8E), Color(0xFF4A3B6B), Color(0xFF30264A)),
    Triple(Color(0xFF81483A), Color(0xFF6B3A2A), Color(0xFF41211A)),
    Triple(Color(0xFF3A2E55), Color(0xFF241C38), Color(0xFF120E20))
)

private fun mix(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

/** 매 프레임 같은 값이 나오는 0~1 난수 — 풀 포기처럼 "흩어져 있되 움직이면 안 되는" 배치에 쓴다. */
private fun hash01(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16777215f
}

private fun cubicPoint(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    val a = u * u * u
    val b = 3f * u * u * t
    val c = 3f * u * t * t
    val d = t * t * t
    return Offset(p0.x * a + p1.x * b + p2.x * c + p3.x * d, p0.y * a + p1.y * b + p2.y * c + p3.y * d)
}

/** 가장자리가 부드럽게 옅어지는 타원 그림자 — 크기를 줄여 가며 옅은 단색 타원을 겹쳐 가운데만 진하게 만든다.
 *  (radialGradient를 비균등 scale로 눌러 그리는 방식은 에뮬레이터 GPU 계층을 죽여서 134차에 이렇게 바꿨다.) */
private fun DrawScope.drawSoftShadow(center: Offset, halfW: Float, halfH: Float, alpha: Float) {
    val layers = 5
    for (k in 0 until layers) {
        val f = 1f - k / layers.toFloat() * 0.75f
        drawOval(
            color = Color.Black.copy(alpha = alpha / layers * 1.4f),
            topLeft = Offset(center.x - halfW * f, center.y - halfH * f),
            size = Size(halfW * 2f * f, halfH * 2f * f)
        )
    }
}

private fun DrawScope.drawSky(tier: Int, w: Float, h: Float, horizonY: Float, scale: Float, tMs: Float, margin: Float) {
    val (top, bottom) = SKY_COLORS[tier]
    drawRect(
        brush = Brush.verticalGradient(listOf(top, bottom), startY = 0f, endY = horizonY),
        topLeft = Offset(-margin, -margin),
        size = Size(w + margin * 2, horizonY + margin * 2)
    )
    when (tier) {
        0 -> {
            // 해는 우상단 설정 버튼과 겹치지 않게 왼쪽 위에 — 번지는 빛무리 위에 둥근 해.
            val sun = Offset(w * 0.2f, horizonY * 0.26f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFFFFF4C2).copy(alpha = 0.8f), Color(0xFFFFF4C2).copy(alpha = 0f)),
                    center = sun, radius = 66f * scale
                ),
                radius = 66f * scale, center = sun
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFFFFF9DC), Color(0xFFFFD54F)), center = sun, radius = 21f * scale),
                radius = 21f * scale, center = sun
            )
            val cloudSpan = w + 100f * scale
            val x1 = ((w * 0.12f + tMs * 0.006f * scale).mod(cloudSpan)) - 50f * scale
            val x2 = ((w * 0.55f + tMs * 0.004f * scale).mod(cloudSpan)) - 50f * scale
            val x3 = ((w * 0.86f + tMs * 0.005f * scale).mod(cloudSpan)) - 50f * scale
            drawCloudPuff(Offset(x1, horizonY * 0.2f), scale * 1.1f)
            drawCloudPuff(Offset(x2, horizonY * 0.42f), scale * 0.85f)
            drawCloudPuff(Offset(x3, horizonY * 0.63f), scale * 0.62f)

            val birdSpan = w + 40f * scale
            val bx1 = ((w * 0.1f + tMs * 0.03f * scale).mod(birdSpan)) - 20f * scale
            val bx2 = ((w * 0.4f + tMs * 0.035f * scale).mod(birdSpan)) - 20f * scale
            drawBird(Offset(bx1, horizonY * 0.5f + sin(tMs * 0.002f) * 6f * scale), scale, tMs * 0.01f)
            drawBird(Offset(bx2, horizonY * 0.58f + sin(tMs * 0.002f + 1f) * 6f * scale), scale, tMs * 0.012f + 1f)
        }
        1 -> drawCircle(color = Color(0xFFC8C8BE).copy(alpha = 0.55f), radius = 18f * scale, center = Offset(w * 0.2f, h * 0.09f))
        2 -> {
            val stars = listOf(0.1f to 0.05f, 0.25f to 0.12f, 0.4f to 0.03f, 0.62f to 0.09f, 0.78f to 0.04f, 0.9f to 0.14f, 0.5f to 0.15f, 0.15f to 0.16f)
            stars.forEach { (fx, fy) -> drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 1.6f * scale, center = Offset(w * fx, h * fy)) }
        }
        3 -> {
            listOf(0.3f to 0f, 0.55f to 0.02f, 0.7f to 0f).forEach { (fx, fy) ->
                drawLine(
                    color = Color(0xFFFFDCB4).copy(alpha = 0.5f),
                    start = Offset(w * fx, h * fy),
                    end = Offset(w * fx + 8f * scale, horizonY * 0.7f),
                    strokeWidth = 1.5f * scale
                )
            }
        }
        4 -> {
            for (i in 0 until 3) {
                val yy = horizonY * (0.2f + i * 0.25f)
                val hue = when (i) {
                    0 -> Color(0xFF7846C8).copy(alpha = 0.35f)
                    1 -> Color(0xFF3CB4AA).copy(alpha = 0.3f)
                    else -> Color(0xFFC85096).copy(alpha = 0.28f)
                }
                val path = Path()
                path.moveTo(0f, yy + sin(tMs * 0.001f + i) * 6f * scale)
                var x = 0f
                while (x <= w) {
                    path.lineTo(x, yy + sin(tMs * 0.001f + i + x * 0.02f) * 6f * scale)
                    x += w / 12f
                }
                drawPath(path, color = hue, style = Stroke(width = 4f * scale))
            }
        }
    }
}

/** 뭉게구름 — 아래쪽 옅은 그늘 위에 크기가 다른 원 세 개와 납작한 밑면을 겹쳐 입체감을 준다. */
private fun DrawScope.drawCloudPuff(center: Offset, scale: Float) {
    val white = Color.White.copy(alpha = 0.96f)
    drawOval(
        color = Color(0xFFD5E6F3).copy(alpha = 0.9f),
        topLeft = Offset(center.x - 31f * scale, center.y - 1f * scale),
        size = Size(62f * scale, 14f * scale)
    )
    drawCircle(color = white, radius = 13f * scale, center = center + Offset(-15f * scale, 0f))
    drawCircle(color = white, radius = 17f * scale, center = center + Offset(2f * scale, -6f * scale))
    drawCircle(color = white, radius = 11f * scale, center = center + Offset(18f * scale, 1f * scale))
    drawOval(color = white, topLeft = Offset(center.x - 29f * scale, center.y - 3f * scale), size = Size(58f * scale, 12f * scale))
}

/** 정상 등급 하늘을 날아다니는 작은 새 실루엣 — wingPhase로 날갯짓하는 "V"자 곡선만 그리는 최소 표현. */
private fun DrawScope.drawBird(center: Offset, scale: Float, wingPhase: Float) {
    val wingSpan = 8f * scale
    val wingLift = (3f + 2.5f * sin(wingPhase)) * scale
    val path = Path().apply {
        moveTo(center.x - wingSpan, center.y - wingLift)
        quadraticBezierTo(center.x - wingSpan / 2, center.y, center.x, center.y - 1f * scale)
        quadraticBezierTo(center.x + wingSpan / 2, center.y, center.x + wingSpan, center.y - wingLift)
    }
    drawPath(path, color = Color(0xFF5C5C5C).copy(alpha = 0.6f), style = Stroke(width = 1.6f * scale))
}

/** 지평선 위 먼 언덕 두 겹(134차) — 하늘과 땅이 직선 한 줄로 딱 잘리던 경계를 부드럽게 잇고 거리감을 준다.
 *  색은 등급별 하늘 아래쪽 색과 먼 땅 색을 섞어 만들어 어느 등급에서도 장면 톤을 따른다. 정상 등급은 먼 능선
 *  위에 작은 나무들을 세워 풍경에 규모감을 준다. 모양이 변하지 않으므로 [GroundScene]의 캐시에서 한 번만 만든다. */
private class HillScenery(val far: Path, val farColor: Color, val near: Path, val nearColor: Color, val trees: Path?, val treeColor: Color)

private fun buildHills(tier: Int, w: Float, horizonY: Float, scale: Float, margin: Float): HillScenery {
    val skyBottom = SKY_COLORS[tier].second
    val groundFar = GROUND_COLORS[tier].first
    val left = -margin
    val right = w + margin
    fun hillY(x: Float, layer: Int): Float {
        val f = x / w
        val amp = (if (layer == 0) 30f else 15f) * scale
        val phase = if (layer == 0) 0.3f else 1.9f
        val wave = 0.55f + 0.3f * sin(f * 6.283f * (1.1f + layer * 0.7f) + phase) + 0.15f * sin(f * 6.283f * 3.3f + phase * 2f)
        return horizonY - amp * wave
    }
    fun layerPath(layer: Int) = Path().apply {
        moveTo(left, horizonY + 2f * scale)
        val steps = 40
        for (i in 0..steps) {
            val x = left + (right - left) * i / steps
            lineTo(x, hillY(x, layer))
        }
        lineTo(right, horizonY + 2f * scale)
        close()
    }
    val farColor = mix(skyBottom, groundFar, 0.45f)
    val treeColor = mix(farColor, Color(0xFF4F8A3A), 0.4f)
    val trees = if (tier == 0) Path().apply {
        for (i in 0 until 7) {
            val x = w * (0.05f + i * 0.145f + hash01(i, 3) * 0.05f)
            val y = hillY(x, 0)
            val r = (4f + hash01(i, 9) * 2.5f) * scale
            addRect(androidx.compose.ui.geometry.Rect(x - 0.6f * scale, y - r, x + 0.6f * scale, y + 1f * scale))
            addOval(androidx.compose.ui.geometry.Rect(center = Offset(x, y - r * 1.2f), radius = r))
        }
    } else null
    return HillScenery(layerPath(0), farColor, layerPath(1), mix(skyBottom, groundFar, 0.8f), trees, treeColor)
}

private fun DrawScope.drawHillScenery(hills: HillScenery) {
    drawPath(hills.far, color = hills.farColor)
    hills.trees?.let { drawPath(it, color = hills.treeColor) }
    drawPath(hills.near, color = hills.nearColor)
}

private fun DrawScope.drawGroundLayer(tier: Int, w: Float, fullH: Float, contentH: Float, horizonY: Float, margin: Float) {
    val (far, mid, near) = GROUND_COLORS[tier]
    drawRect(
        brush = Brush.verticalGradient(0f to far, 0.4f to mid, 1f to near, startY = horizonY, endY = contentH),
        topLeft = Offset(-margin, horizonY),
        size = Size(w + margin * 2, fullH - horizonY + margin)
    )
    if (tier == 2) {
        drawRect(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFD778).copy(alpha = 0.25f), Color(0xFFFFD778).copy(alpha = 0f)),
                center = Offset(w / 2f, contentH * 0.72f), radius = (w * 0.4f).coerceAtLeast(1f)
            ),
            topLeft = Offset(0f, horizonY), size = Size(w, fullH - horizonY)
        )
    }
}

/** 땅 위 풀 포기(정상 등급은 작은 들꽃 포함) — 멀수록 작고 위에, 가까울수록 크고 아래에 흩어 원근감을 준다.
 *  등급마다 색이 달라진다(이상함 = 칙칙한 풀, 초월급 = 수정 조각, 종말급 = 마른 풀, 최강자급 = 금빛). 잎 수십 장을
 *  색별로 Path 두 개에 모아 한 번씩만 칠한다. */
private class GroundTufts(
    val dark: Path, val light: Path, val darkColor: Color, val lightColor: Color,
    val flowers: List<Triple<Offset, Float, Color>>
)

private fun buildGroundTufts(tier: Int, scene: DecorationScene, pot: PotGeometry): GroundTufts {
    val darkColor = listOf(Color(0xFF5E9E36), Color(0xFF5B6641), Color(0xFFB7A3EC), Color(0xFF4A2A1C), Color(0xFFE8C66A))[tier]
    val lightColor = listOf(Color(0xFF9CD068), Color(0xFF8C9870), Color(0xFFE2D6FF), Color(0xFF7A4A34), Color(0xFFFFF1B8))[tier]
    val dark = Path()
    val light = Path()
    val flowers = mutableListOf<Triple<Offset, Float, Color>>()
    for (i in 0 until 40) {
        val d = 0.06f + 0.94f * hash01(i, 11)
        val x = scene.w * hash01(i, 23)
        // 화분 발치 바로 앞에 난 풀은 나중에 그려지는 화분에 덮여 어색해진다 — 그 자리만 비운다.
        if (d > POT_DEPTH - 0.04f && d < POT_DEPTH + 0.12f && abs(x - pot.cx) < pot.rimHalfW * 1.3f) continue
        val y = scene.yAt(d)
        val s = scene.scaleAt(d)
        val bh = (5f + 4f * hash01(i, 37)) * s
        val lean = (hash01(i, 41) - 0.5f) * 2f * s
        val target = if (i % 2 == 0) dark else light
        val half = 0.7f * s
        for (k in -1..1) {
            val bx = x + k * 1.6f * s
            val midX = x + k * 2.2f * s
            val tip = Offset(x + k * 4.2f * s + lean, y - bh * (if (k == 0) 1f else 0.72f))
            target.moveTo(bx - half, y)
            target.quadraticTo(midX - half * 0.4f, y - bh * 0.6f, tip.x, tip.y)
            target.quadraticTo(midX + half * 0.4f, y - bh * 0.6f, bx + half, y)
            target.close()
        }
        if (tier == 0 && i % 4 == 1) {
            val flower = listOf(Color.White, Color(0xFFFFE082), Color(0xFFF8BBD0))[i % 3]
            flowers += Triple(Offset(x + lean, y - bh - 0.6f * s), 1.9f * s, flower)
        }
    }
    return GroundTufts(dark, light, darkColor, lightColor, flowers)
}

private fun DrawScope.drawGroundTufts(t: GroundTufts) {
    drawPath(t.dark, color = t.darkColor.copy(alpha = 0.85f))
    drawPath(t.light, color = t.lightColor.copy(alpha = 0.85f))
    t.flowers.forEach { (c, r, color) ->
        drawCircle(color = color, radius = r, center = c)
        drawCircle(color = Color(0xFFFFB300), radius = r * 0.42f, center = c)
    }
}

/** 화분 기하(134차) — 모든 치수는 씬 배율 기준 단위. [baseY]가 땅에 닿는 바닥선, [soilY]가 식물이 올라오는 흙 표면. */
private data class PotGeometry(val cx: Float, val baseY: Float, val scale: Float) {
    val rimHalfW: Float get() = 44f * scale
    val rimH: Float get() = 11f * scale
    val bodyTopHalfW: Float get() = 38f * scale
    val bottomHalfW: Float get() = 28f * scale
    val bodyH: Float get() = 44f * scale
    val rimBottomY: Float get() = baseY - bodyH
    val rimTopY: Float get() = rimBottomY - rimH
    val soilY: Float get() = rimTopY + 1.6f * scale
}

/** 등급별 화분 재질 — 정상 = 테라코타, 이상함 = 이끼 낀 돌, 초월급 = 대리석+금 테두리, 종말급 = 흑요석+용암 균열,
 *  최강자급 = 황금+보석. 식물·배경만 바뀌고 화분은 그대로라 등급이 올라도 "같은 화분"처럼 보이던 문제를 없앤다. */
private data class PotStyle(
    val dark: Color, val base: Color, val light: Color,
    val rimDark: Color, val rimLight: Color,
    val soil: Color, val soilLight: Color
)

private val POT_STYLES = listOf(
    PotStyle(Color(0xFF93461F), Color(0xFFC86A3A), Color(0xFFEBA06C), Color(0xFFA9552B), Color(0xFFF0AE7C), Color(0xFF3F2A1C), Color(0xFF6B4A33)),
    PotStyle(Color(0xFF454C3E), Color(0xFF727C66), Color(0xFFA2AB93), Color(0xFF555D4B), Color(0xFFB2BAA2), Color(0xFF2A261F), Color(0xFF4A4336)),
    PotStyle(Color(0xFFB3A994), Color(0xFFEAE4D8), Color(0xFFFFFFFF), Color(0xFFB07F26), Color(0xFFF8DE92), Color(0xFF34284A), Color(0xFF5A4A78)),
    PotStyle(Color(0xFF0C0808), Color(0xFF2A1F1D), Color(0xFF4F3E39), Color(0xFF170F0E), Color(0xFF5C4842), Color(0xFF170C09), Color(0xFF3A1A10)),
    PotStyle(Color(0xFF8A600F), Color(0xFFD6A332), Color(0xFFFFEBA6), Color(0xFFA47418), Color(0xFFFFF4CB), Color(0xFF261B38), Color(0xFF4B3A6E))
)

private fun DrawScope.drawPot(tier: Int, pot: PotGeometry, anim: GrowthAnim, tMs: Float) {
    val st = POT_STYLES[tier.coerceIn(0, POT_STYLES.size - 1)]
    val s = pot.scale
    val cx = pot.cx
    val top = pot.rimBottomY
    val base = pot.baseY
    val tw = pot.bodyTopHalfW
    val bw = pot.bottomHalfW

    drawSoftShadow(Offset(cx, base), bw * 1.55f, 6.5f * s, 0.3f)

    // 몸통 — 위는 넓고 아래로 갈수록 둥글게 좁아진다. 왼쪽 위에서 빛이 드는 원통 음영.
    val body = Path().apply {
        moveTo(cx - tw, top)
        lineTo(cx + tw, top)
        cubicTo(cx + tw, top + pot.bodyH * 0.4f, cx + bw + 2f * s, base - 10f * s, cx + bw, base - 2.5f * s)
        quadraticTo(cx, base + 3.5f * s, cx - bw, base - 2.5f * s)
        cubicTo(cx - bw - 2f * s, base - 10f * s, cx - tw, top + pot.bodyH * 0.4f, cx - tw, top)
        close()
    }
    drawPath(
        body,
        brush = Brush.horizontalGradient(
            0f to st.dark, 0.22f to st.base, 0.38f to st.light, 0.62f to st.base, 1f to st.dark,
            startX = cx - tw, endX = cx + tw
        )
    )
    // 테두리가 드리운 그늘(위) + 바닥 쪽 어둠(아래).
    drawPath(
        body,
        brush = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.26f), 0.22f to Color.Black.copy(alpha = 0f),
            0.75f to Color.Black.copy(alpha = 0f), 1f to Color.Black.copy(alpha = 0.16f),
            startY = top, endY = base + 3f * s
        )
    )

    fun halfWAt(f: Float) = tw + (bw - tw) * f
    fun bandPath(f: Float, inset: Float): Path {
        val y = top + pot.bodyH * f
        val hw = halfWAt(f) - inset
        return Path().apply {
            moveTo(cx - hw, y)
            quadraticTo(cx, y + 3.5f * s, cx + hw, y)
        }
    }
    when (tier) {
        0 -> {
            // 테라코타: 가는 음각 띠 두 줄(어두운 선 + 바로 아래 밝은 선).
            drawPath(bandPath(0.46f, 1f * s), color = Color.Black.copy(alpha = 0.18f), style = Stroke(width = 1.5f * s))
            drawPath(bandPath(0.51f, 1f * s), color = Color.White.copy(alpha = 0.2f), style = Stroke(width = 1.1f * s))
        }
        1 -> {
            // 이끼 낀 돌: 이끼 얼룩 + 가는 금.
            val moss = Color(0xFF6F8F3F).copy(alpha = 0.85f)
            listOf(Triple(-0.62f, 0.12f, 4.5f), Triple(-0.45f, 0.2f, 3f), Triple(0.5f, 0.72f, 3.6f), Triple(0.66f, 0.62f, 2.4f), Triple(-0.1f, 0.9f, 2.8f))
                .forEach { (fx, fy, r) -> drawCircle(color = moss, radius = r * s, center = Offset(cx + fx * halfWAt(fy), top + fy * pot.bodyH)) }
            val crack = Path().apply {
                moveTo(cx + tw * 0.3f, top + 1f * s)
                lineTo(cx + tw * 0.2f, top + pot.bodyH * 0.22f)
                lineTo(cx + tw * 0.33f, top + pot.bodyH * 0.38f)
                lineTo(cx + tw * 0.16f, top + pot.bodyH * 0.6f)
            }
            drawPath(crack, color = Color.Black.copy(alpha = 0.38f), style = Stroke(width = 1.2f * s, cap = StrokeCap.Round))
        }
        2 -> {
            // 대리석 결 + 금 띠.
            val vein = Path().apply {
                moveTo(cx - tw * 0.8f, top + pot.bodyH * 0.2f)
                quadraticTo(cx - tw * 0.1f, top + pot.bodyH * 0.35f, cx + tw * 0.3f, top + pot.bodyH * 0.78f)
            }
            drawPath(vein, color = Color(0xFFB9B2A6).copy(alpha = 0.45f), style = Stroke(width = 1f * s))
            drawPath(
                bandPath(0.48f, 0f),
                brush = Brush.horizontalGradient(listOf(st.rimDark, st.rimLight, st.rimDark), startX = cx - tw, endX = cx + tw),
                style = Stroke(width = 4f * s)
            )
        }
        3 -> {
            // 흑요석 + 이글거리는 용암 균열(깜빡임은 종말급 애니메이션 값을 따른다).
            val glow = (0.55f + anim.flicker * 0.4f).coerceIn(0f, 1f)
            listOf(
                listOf(-0.5f to 0.05f, -0.35f to 0.35f, -0.52f to 0.6f, -0.3f to 0.92f),
                listOf(0.42f to 0.1f, 0.3f to 0.4f, 0.46f to 0.7f)
            ).forEach { pts ->
                val crack = Path()
                pts.forEachIndexed { i, (fx, fy) ->
                    val p = Offset(cx + fx * halfWAt(fy), top + fy * pot.bodyH)
                    if (i == 0) crack.moveTo(p.x, p.y) else crack.lineTo(p.x, p.y)
                }
                drawPath(crack, color = Color(0xFFFF5A1F).copy(alpha = glow * 0.35f), style = Stroke(width = 4f * s, cap = StrokeCap.Round))
                drawPath(crack, color = Color(0xFFFFB35C).copy(alpha = glow), style = Stroke(width = 1.4f * s, cap = StrokeCap.Round))
            }
        }
        4 -> {
            // 황금: 돋을새김 띠 + 가운데 보석 + 반짝임.
            drawPath(bandPath(0.46f, 0f), color = st.light.copy(alpha = 0.8f), style = Stroke(width = 5f * s))
            drawPath(bandPath(0.46f, 0f), color = st.dark.copy(alpha = 0.55f), style = Stroke(width = 1.2f * s))
            val gem = Offset(cx, top + pot.bodyH * 0.46f + 1.8f * s)
            val gr = 6.5f * s
            val gemPath = Path().apply {
                moveTo(gem.x, gem.y - gr)
                lineTo(gem.x + gr * 0.8f, gem.y)
                lineTo(gem.x, gem.y + gr)
                lineTo(gem.x - gr * 0.8f, gem.y)
                close()
            }
            drawPath(gemPath, brush = Brush.radialGradient(listOf(Color(0xFFFF8FB1), Color(0xFFB0103A)), center = gem, radius = gr))
            drawPath(gemPath, color = st.dark, style = Stroke(width = 1f * s))
            val twinkle = (0.5f + 0.5f * sin(tMs / 380f)).coerceIn(0f, 1f)
            val sp = Offset(gem.x - gr * 0.3f, gem.y - gr * 0.35f)
            drawLine(color = Color.White.copy(alpha = twinkle), start = sp + Offset(-4f * s, 0f), end = sp + Offset(4f * s, 0f), strokeWidth = 1f * s)
            drawLine(color = Color.White.copy(alpha = twinkle), start = sp + Offset(0f, -4f * s), end = sp + Offset(0f, 4f * s), strokeWidth = 1f * s)
        }
    }

    // 테두리 앞면 → 윗면(타원) → 안쪽 그늘 → 흙 순서로 겹쳐 "살짝 위에서 내려다본" 화분 입구를 만든다.
    val rimLeft = cx - pot.rimHalfW
    // 그라디언트 둥근 사각형은 drawRoundRect 대신 Path로 칠한다 — 에뮬레이터 GPU 계층이 "그라디언트 + drawRoundRect"
    // 조합에서만 매번 통째로 죽었다(134차에 원인만 골라내는 실험으로 확인, 그라디언트 Path는 문제없음).
    val rim = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = rimLeft, top = pot.rimTopY, right = cx + pot.rimHalfW, bottom = pot.rimTopY + pot.rimH,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.5f * s, 4.5f * s)
            )
        )
    }
    drawPath(
        rim,
        brush = Brush.horizontalGradient(
            0f to st.rimDark, 0.3f to st.rimLight, 0.6f to mix(st.rimLight, st.rimDark, 0.35f), 1f to st.rimDark,
            startX = rimLeft, endX = cx + pot.rimHalfW
        )
    )
    drawLine(
        color = Color.Black.copy(alpha = 0.2f),
        start = Offset(rimLeft + 3f * s, pot.rimBottomY - 0.6f * s),
        end = Offset(cx + pot.rimHalfW - 3f * s, pot.rimBottomY - 0.6f * s),
        strokeWidth = 1.2f * s
    )
    drawOval(
        color = mix(st.rimLight, st.rimDark, 0.25f),
        topLeft = Offset(rimLeft + 0.5f * s, pot.rimTopY - 4f * s),
        size = Size((pot.rimHalfW - 0.5f * s) * 2f, 9f * s)
    )
    val soilHalfW = pot.rimHalfW - 5.5f * s
    drawOval(
        color = mix(st.soil, Color.Black, 0.35f),
        topLeft = Offset(cx - soilHalfW - 1.2f * s, pot.rimTopY - 2.8f * s),
        size = Size((soilHalfW + 1.2f * s) * 2f, 7.6f * s)
    )
    drawOval(
        brush = Brush.verticalGradient(listOf(st.soil, st.soilLight), startY = pot.rimTopY - 1.6f * s, endY = pot.rimTopY + 4.8f * s),
        topLeft = Offset(cx - soilHalfW, pot.rimTopY - 1.6f * s),
        size = Size(soilHalfW * 2f, 6.4f * s)
    )
    listOf(-0.6f to 0.3f, -0.25f to 0.7f, 0.2f to 0.45f, 0.55f to 0.65f, 0.75f to 0.35f).forEach { (fx, fy) ->
        drawCircle(
            color = st.soilLight.copy(alpha = 0.8f), radius = 0.9f * s,
            center = Offset(cx + fx * soilHalfW, pot.rimTopY - 1.6f * s + fy * 6.4f * s)
        )
    }
    // 테두리 앞면 왼쪽 윗단의 반사광.
    drawLine(
        color = Color.White.copy(alpha = 0.3f),
        start = Offset(rimLeft + 5f * s, pot.rimTopY + 6.6f * s),
        end = Offset(cx - pot.rimHalfW * 0.2f, pot.rimTopY + 6.6f * s),
        strokeWidth = 1.2f * s, cap = StrokeCap.Round
    )
}

private fun DrawScope.drawCrackedGroundPatch(w: Float, h: Float, scale: Float, intensity: Int, alpha: Float) {
    val count = 2 + intensity.coerceAtLeast(0)
    for (i in 0 until count) {
        val sx = w * (0.2f + 0.6f * i / count)
        val sy = h * 0.95f
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(sx + 10f * scale, sy - 14f * scale)
            lineTo(sx - 6f * scale, sy - 26f * scale)
        }
        drawPath(path, color = Color.Black.copy(alpha = alpha.coerceIn(0f, 1f)), style = Stroke(width = 2f * scale))
    }
}

/** 초월급 이상에서 깔리는 전체 화면 배경 연출 — 나무 한 그루만이 아니라 장면 전체의 색조/빛을 물들여서
 *  "존재감이 화면을 압도한다"는 느낌을 준다. */
private fun DrawScope.drawCosmicBackdrop(tier: Int, w: Float, h: Float, scale: Float, anim: GrowthAnim, margin: Float) {
    val cx = w / 2f
    val cy = h * 0.55f
    when (tier) {
        2 -> drawRect(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFE096).copy(alpha = 0.22f), Color(0xFFFFC864).copy(alpha = 0.08f), Color(0xFFFFC864).copy(alpha = 0f)),
                center = Offset(cx, cy), radius = (w * 0.75f * anim.pulse).coerceAtLeast(1f)
            ),
            topLeft = Offset(-margin, -margin), size = Size(w + margin * 2, h + margin * 2)
        )
        3 -> drawRect(
            brush = Brush.radialGradient(
                listOf(Color(0xFF780A0A).copy(alpha = 0.1f + anim.flicker * 0.12f), Color(0xFF280000).copy(alpha = 0f)),
                center = Offset(cx, cy), radius = (w * 0.85f).coerceAtLeast(1f)
            ),
            topLeft = Offset(-margin, -margin), size = Size(w + margin * 2, h + margin * 2)
        )
        4 -> {
            drawRect(
                brush = Brush.radialGradient(
                    listOf(Color(0xFFFFFAE1).copy(alpha = 0.4f), Color(0xFFFFD778).copy(alpha = 0.2f), Color(0xFFFFD778).copy(alpha = 0f)),
                    center = Offset(cx, h * 0.42f), radius = (w * 1.05f * anim.pulse).coerceAtLeast(1f)
                ),
                topLeft = Offset(-margin, -margin), size = Size(w + margin * 2, h + margin * 2)
            )
            for (i in 0 until 3) {
                val r = w * (0.55f + i * 0.22f) * anim.pulse
                rotate(degrees = anim.rotate * (if (i % 2 == 0) 0.4f else -0.4f) * RAD2DEG, pivot = Offset(cx, h * 0.42f)) {
                    drawOval(
                        color = Color(0xFFFFF0C8).copy(alpha = 0.18f + anim.pulse * 0.1f),
                        topLeft = Offset(cx - r, h * 0.42f - r * 0.4f), size = Size(r * 2, r * 0.8f),
                        style = Stroke(width = 2f * scale)
                    )
                }
            }
        }
    }
}

/** 최강자급 전용 전경 연출 — 화면 전체에 은은한 빛 파티클을 흩뿌려서 나무 한 그루가 아니라 공간 전체가
 *  초월적 존재의 영향 아래 있는 것처럼 보이게 한다. */
private fun DrawScope.drawTranscendentOverlay(w: Float, h: Float, scale: Float, anim: GrowthAnim, tMs: Float) {
    for (i in 0 until 26) {
        val seed = i * 137.5f
        val fx = (seed % 97f) / 97f
        val fy = (seed * 1.7f % 89f) / 89f
        val bob = sin(tMs * 0.0006f + i) * 10f * scale
        val r = (1.2f + (i % 4) * 0.6f) * scale
        drawCircle(color = Color(0xFFFFF7D6).copy(alpha = (0.25f + anim.flicker * 0.2f).coerceIn(0f, 1f)), radius = r, center = Offset(w * fx, h * fy + bob))
    }
}

/** 종말급 전용 전경 연출 — 화면 전체에 떠오르는 잉걸불 재를 흩뿌려서 재앙이 나무 한 그루가 아니라
 *  주변 공간 전체를 잠식하는 느낌을 준다. */
private fun DrawScope.drawEmberOverlay(w: Float, h: Float, scale: Float, anim: GrowthAnim, tMs: Float) {
    for (i in 0 until 20) {
        val seed = i * 91.3f
        val fx = (seed % 83f) / 83f
        val rise = (tMs * 0.00004f + i * 0.13f) % 1f
        val r = (1.5f + (i % 3) * 0.8f) * scale
        val alpha = ((0.2f + anim.flicker * 0.25f) * (1f - rise * 0.6f)).coerceIn(0f, 1f)
        drawCircle(color = Color(0xFFFF9646).copy(alpha = alpha), radius = r, center = Offset(w * fx, h * (1f - rise)))
    }
}

/** 환생 횟수를 나타내는 영구적인 "관록" — 레벨(칭호)과 별개로, 환생을 거듭할수록 은은하게 짙어지는
 *  금빛 링. 갓 환생 직후(레벨1=씨앗)라도 관록이 있는 개체라는 걸 시각적으로 드러낸다(사용자 요청). */
private fun DrawScope.drawRebirthAura(cx: Float, cy: Float, scale: Float, rebirthCount: Int) {
    val n = rebirthCount.coerceAtMost(6)
    val alpha = (0.12f + n * 0.05f).coerceAtMost(0.4f)
    for (i in 0 until n) {
        drawCircle(
            color = Color(0xFFFFC440).copy(alpha = alpha),
            radius = (60f + i * 10f) * scale,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f * scale)
        )
    }
}

private val LEAF_VEIN = Color(0xFF3D8B37)
private val LEAF_BASE = Color(0xFF58AE45)
private val LEAF_LIGHT = Color(0xFF8ED36A)
private val COTYLEDON_BASE = Color(0xFFA5CF63)
private val COTYLEDON_LIGHT = Color(0xFFD4EE9A)
private val COTYLEDON_VEIN = Color(0xFF6E9A3A)
private val STEM_DARK = Color(0xFF3F7F32)
private val STEM_LIGHT = Color(0xFF79C257)

/**
 * 잎 한 장(134차) — 끝이 뾰족한 잎 모양 + 빛을 받는 윗면 반쪽을 밝게 + 잎맥. [angleDeg]는 +x축 기준
 * 각도(음수 = 위쪽, -90 = 똑바로 위). 왼쪽을 향한 잎도 항상 "위를 향한 반쪽"이 밝도록 뒤집어 칠한다.
 */
private fun DrawScope.drawLeaf(
    base: Offset, angleDeg: Float, length: Float, width: Float,
    color: Color = LEAF_BASE, light: Color = LEAF_LIGHT, vein: Color = LEAF_VEIN
) {
    val upSign = if (cos(Math.toRadians(angleDeg.toDouble())) >= 0.0) -1f else 1f
    rotate(degrees = angleDeg, pivot = base) {
        val x0 = base.x
        val y0 = base.y
        val tipX = x0 + length
        val leaf = Path().apply {
            moveTo(x0, y0)
            cubicTo(x0 + length * 0.22f, y0 - width, x0 + length * 0.68f, y0 - width * 0.95f, tipX, y0)
            cubicTo(x0 + length * 0.68f, y0 + width * 0.95f, x0 + length * 0.22f, y0 + width, x0, y0)
            close()
        }
        drawPath(leaf, color = color)
        val upper = Path().apply {
            moveTo(x0, y0)
            cubicTo(x0 + length * 0.22f, y0 + width * upSign, x0 + length * 0.68f, y0 + width * 0.95f * upSign, tipX, y0)
            close()
        }
        drawPath(upper, color = light.copy(alpha = 0.75f))
        drawLine(
            color = vein.copy(alpha = 0.55f),
            start = Offset(x0 + length * 0.04f, y0), end = Offset(tipX - length * 0.1f, y0),
            strokeWidth = (width * 0.14f).coerceAtLeast(0.8f), cap = StrokeCap.Round
        )
    }
}

/** 다섯 장 꽃잎 꽃 — 꽃잎은 가운데가 밝고 끝이 짙은 방사형 음영, 가운데는 노란 꽃술. */
private fun DrawScope.drawFlower(center: Offset, radius: Float, petal: Color, petalLight: Color, rotationDeg: Float) {
    for (k in 0 until 5) {
        rotate(degrees = rotationDeg + k * 72f, pivot = center) {
            drawOval(
                brush = Brush.radialGradient(listOf(petalLight, petal), center = center, radius = radius * 1.05f),
                topLeft = Offset(center.x + radius * 0.06f, center.y - radius * 0.34f),
                size = Size(radius * 0.98f, radius * 0.68f)
            )
        }
    }
    drawCircle(
        brush = Brush.radialGradient(listOf(Color(0xFFFFF59D), Color(0xFFFFA000)), center = center, radius = radius * 0.34f),
        radius = radius * 0.34f, center = center
    )
    for (k in 0 until 6) {
        val a = Math.toRadians((rotationDeg + k * 60f).toDouble())
        drawCircle(
            color = Color(0xFFB26A00), radius = radius * 0.05f,
            center = Offset(center.x + (cos(a) * radius * 0.19f).toFloat(), center.y + (sin(a) * radius * 0.19f).toFloat())
        )
    }
}

/** 꽃봉오리 — 분홍 물방울 모양 꽃잎을 초록 꽃받침 두 장이 감싼다. [stemTop]은 줄기 끝. */
private fun DrawScope.drawBud(stemTop: Offset, size: Float) {
    val cx = stemTop.x
    val by = stemTop.y
    val bud = Path().apply {
        moveTo(cx, by - size * 1.6f)
        cubicTo(cx + size * 0.8f, by - size * 1.15f, cx + size * 0.62f, by - size * 0.15f, cx, by)
        cubicTo(cx - size * 0.62f, by - size * 0.15f, cx - size * 0.8f, by - size * 1.15f, cx, by - size * 1.6f)
        close()
    }
    drawPath(bud, brush = Brush.verticalGradient(listOf(Color(0xFFF8BBD0), Color(0xFFE2457A)), startY = by - size * 1.6f, endY = by))
    drawLeaf(stemTop, -58f, size * 1.1f, size * 0.34f)
    drawLeaf(stemTop, -122f, size * 1.1f, size * 0.34f)
}

/**
 * 초반(레벨 1~95 미만, "든든한 나무" 이전) 식물 — 134차에 칭호마다 모습이 분명히 달라지도록 다시 그렸다
 * (예전엔 줄기 선 하나에 타원 잎을 좌우 대칭으로 붙인 모양이라 단계 차이가 잎 개수뿐이었다).
 * 씨앗 → 발아(휜 줄기 + 덜 펴진 떡잎) → 새싹(떡잎 + 첫 본잎) → 어린잎·무럭무럭(어긋나는 본잎이 늘어남)
 * → 꽃봉오리 → 첫 개화 → 풍성한 화분(곁가지 둘 + 꽃 세 송이). 돌려주는 값은 식물 꼭대기(나비가 맴도는 중심).
 */
private fun DrawScope.drawYoungPlant(baseX: Float, baseY: Float, maxHeight: Float, scale: Float, stageIndex: Int, swayPx: Float, tMs: Float): Offset {
    val s = scale
    val sway = swayPx * scale
    val flutter = sin(tMs / 900f) * 3f
    when (stageIndex) {
        0 -> {
            // 씨앗: 흙 위에 반쯤 묻힌 씨앗 + 살짝 비친 싹 끝.
            val c = Offset(baseX, baseY - 1.5f * s)
            rotate(degrees = -18f, pivot = c) {
                drawOval(
                    brush = Brush.linearGradient(listOf(Color(0xFFC89060), Color(0xFF7A4A26)), start = Offset(c.x - 7f * s, c.y - 4f * s), end = Offset(c.x + 7f * s, c.y + 4f * s)),
                    topLeft = Offset(c.x - 7.5f * s, c.y - 4.5f * s), size = Size(15f * s, 9f * s)
                )
                drawOval(color = Color.White.copy(alpha = 0.35f), topLeft = Offset(c.x - 4.5f * s, c.y - 3.2f * s), size = Size(5f * s, 2.2f * s))
            }
            val sprout = Path().apply {
                moveTo(c.x + 3f * s, c.y - 3f * s)
                quadraticTo(c.x + 6f * s, c.y - 8f * s, c.x + 2.5f * s + sway * 0.2f, c.y - 10f * s)
            }
            drawPath(sprout, color = STEM_LIGHT, style = Stroke(width = 2f * s, cap = StrokeCap.Round))
            return Offset(baseX, baseY - 14f * s)
        }
        1 -> {
            // 발아: 갈고리처럼 휜 줄기 끝에 아직 덜 펴진 떡잎 두 장, 흙 위엔 벗겨진 씨앗 껍질.
            val top = Offset(baseX + 3f * s + sway * 0.4f, baseY - 20f * s)
            val stem = Path().apply {
                moveTo(baseX, baseY)
                cubicTo(baseX - 3f * s, baseY - 8f * s, baseX + 6f * s, baseY - 15f * s, top.x, top.y)
            }
            drawPath(stem, color = STEM_LIGHT, style = Stroke(width = 3f * s, cap = StrokeCap.Round))
            drawLeaf(top, -150f + flutter, 9f * s, 3.6f * s, COTYLEDON_BASE, COTYLEDON_LIGHT, COTYLEDON_VEIN)
            drawLeaf(top, -30f - flutter, 9f * s, 3.6f * s, COTYLEDON_BASE, COTYLEDON_LIGHT, COTYLEDON_VEIN)
            drawArc(
                color = Color(0xFF8D5A34), startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(baseX - 14f * s, baseY - 3.5f * s), size = Size(8f * s, 6f * s),
                style = Stroke(width = 1.8f * s, cap = StrokeCap.Round)
            )
            return Offset(top.x, top.y - 6f * s)
        }
        2 -> {
            // 새싹: 동그란 떡잎 두 장이 활짝 펴지고 가운데서 첫 본잎이 올라온다.
            val top = Offset(baseX + sway * 0.6f, baseY - 34f * s)
            val stem = Path().apply {
                moveTo(baseX, baseY)
                cubicTo(baseX - 2f * s, baseY - 12f * s, baseX + 3f * s + sway * 0.3f, baseY - 24f * s, top.x, top.y)
            }
            drawPath(
                stem,
                brush = Brush.verticalGradient(listOf(STEM_LIGHT, STEM_DARK), startY = top.y, endY = baseY),
                style = Stroke(width = 3.4f * s, cap = StrokeCap.Round)
            )
            drawLeaf(top, -165f + flutter, 15f * s, 7f * s, COTYLEDON_BASE, COTYLEDON_LIGHT, COTYLEDON_VEIN)
            drawLeaf(top, -15f - flutter, 15f * s, 7f * s, COTYLEDON_BASE, COTYLEDON_LIGHT, COTYLEDON_VEIN)
            drawLeaf(top, -90f + flutter * 0.5f, 9f * s, 3.2f * s)
            return Offset(top.x, top.y - 10f * s)
        }
    }

    // 3단계 이상: 살짝 휜 줄기 + 어긋나게 붙는 본잎. 단계가 오를수록 키·잎 수가 늘고 꼭대기가 봉오리 → 꽃으로 바뀐다.
    val heightFrac = when (stageIndex) {
        3 -> 0.38f
        4 -> 0.54f
        5 -> 0.68f
        6 -> 0.82f
        else -> 0.95f
    }
    val stemH = maxHeight * heightFrac
    val base = Offset(baseX, baseY)
    val top = Offset(baseX + sway, baseY - stemH)
    val c1 = Offset(baseX - 4f * s, baseY - stemH * 0.35f)
    val c2 = Offset(baseX + sway * 0.5f + 5f * s, baseY - stemH * 0.7f)
    fun stemAt(t: Float) = cubicPoint(base, c1, c2, top, t)

    // 풍성한 화분: 곁가지 두 개를 본줄기보다 먼저(뒤에) 그린다.
    val sideFlowers = mutableListOf<Pair<Offset, Int>>()
    if (stageIndex >= 7) {
        listOf(Triple(0.42f, -1f, 0.22f), Triple(0.56f, 1f, 0.18f)).forEachIndexed { idx, (t, dir, rise) ->
            val start = stemAt(t)
            val end = Offset(start.x + dir * maxHeight * 0.19f + sway * 0.6f, start.y - maxHeight * rise)
            val ctrl = Offset(start.x + dir * maxHeight * 0.16f, start.y - maxHeight * 0.03f)
            val branch = Path().apply {
                moveTo(start.x, start.y)
                quadraticTo(ctrl.x, ctrl.y, end.x, end.y)
            }
            drawPath(branch, color = STEM_DARK, style = Stroke(width = 3.2f * s, cap = StrokeCap.Round))
            val mid = Offset((start.x + 2 * ctrl.x + end.x) / 4f, (start.y + 2 * ctrl.y + end.y) / 4f)
            val leafLen = 26f * s
            drawLeaf(mid, (if (dir < 0) -155f else -25f) + flutter, leafLen, leafLen * 0.46f)
            drawLeaf(mid, (if (dir < 0) -80f else -100f) - flutter, leafLen * 0.8f, leafLen * 0.38f)
            sideFlowers += end to idx
        }
    }

    val segments = 14
    var prev = stemAt(0f)
    for (i in 1..segments) {
        val t = i / segments.toFloat()
        val p = stemAt(t)
        drawLine(color = mix(STEM_DARK, STEM_LIGHT, t), start = prev, end = p, strokeWidth = (6.2f - 3f * t) * s, cap = StrokeCap.Round)
        prev = p
    }
    if (stageIndex >= 7) {
        // 풍성한 화분: 밑동에 넓게 퍼진 잎 두 장으로 덤불 느낌을 준다.
        val low = stemAt(0.07f)
        drawLeaf(low, -168f + flutter, 32f * s, 14f * s)
        drawLeaf(low, -12f - flutter, 32f * s, 14f * s)
    }
    if (stageIndex == 3) {
        // 어린잎 단계까지는 떡잎이 아래쪽에 남아 있다.
        val low = stemAt(0.12f)
        drawLeaf(low, -170f + flutter, 11f * s, 5.2f * s, COTYLEDON_BASE, COTYLEDON_LIGHT, COTYLEDON_VEIN)
        drawLeaf(low, -10f - flutter, 11f * s, 5.2f * s, COTYLEDON_BASE, COTYLEDON_LIGHT, COTYLEDON_VEIN)
    }
    val leafCount = when (stageIndex) {
        3 -> 4
        4 -> 6
        5 -> 7
        6 -> 8
        else -> 9
    }
    for (i in 0 until leafCount) {
        val t = 0.2f + 0.72f * i / (leafCount - 1).coerceAtLeast(1)
        val p = stemAt(t)
        val len = (20f + stageIndex * 2.6f) * s * (1.1f - 0.45f * t)
        val lift = 30f + 28f * t // 위쪽 잎일수록 더 곧추선다
        val wobble = flutter * (if (i % 2 == 0) 1f else -1f)
        val angle = if (i % 2 == 0) -180f + lift + wobble else -lift + wobble
        drawLeaf(p, angle, len, len * 0.46f)
    }

    sideFlowers.forEach { (p, idx) ->
        if (idx == 0) drawFlower(p, 13f * s, Color(0xFFFF7A59), Color(0xFFFFD2C2), 12f + flutter * 2f)
        else drawFlower(p, 12f * s, Color(0xFFFFB300), Color(0xFFFFF1B8), -8f - flutter * 2f)
    }
    return when {
        stageIndex == 5 -> {
            drawBud(top, 12f * s)
            Offset(top.x, top.y - 19f * s)
        }
        stageIndex >= 6 -> {
            val flowerCenter = Offset(top.x, top.y - 4f * s)
            drawFlower(flowerCenter, (if (stageIndex >= 7) 18f else 16f) * s, Color(0xFFE94E86), Color(0xFFFFD0E1), flutter * 3f)
            flowerCenter
        }
        else -> {
            drawLeaf(top, -118f + flutter, 9f * s, 3.4f * s)
            drawLeaf(top, -62f - flutter, 9f * s, 3.4f * s)
            Offset(top.x, top.y - 6f * s)
        }
    }
}

/** 굵기가 줄어드는 가지 한 줄기(밑동 쪽이 굵다). */
private fun DrawScope.drawTaperedLimb(from: Offset, to: Offset, fromHalfW: Float, toHalfW: Float, color: Color) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    val nx = -dy / len
    val ny = dx / len
    val path = Path().apply {
        moveTo(from.x + nx * fromHalfW, from.y + ny * fromHalfW)
        lineTo(to.x + nx * toHalfW, to.y + ny * toHalfW)
        lineTo(to.x - nx * toHalfW, to.y - ny * toHalfW)
        lineTo(from.x - nx * fromHalfW, from.y - ny * fromHalfW)
        close()
    }
    drawPath(path, color = color)
    drawCircle(color = color, radius = toHalfW, center = to)
}

/**
 * 정상/이상함(tier 0·1) 공용 나무 — 134차에 다시 그렸다: 뿌리가 퍼진 줄기(원통 음영 + 나무껍질 결), 끝으로 갈수록
 * 가늘어지는 가지, 그리고 잎 뭉치를 "그늘 → 기본색 → 빛 받는 면" 세 겹으로 한꺼번에 칠해 하나의 풍성한 수관으로 보이게
 * 했다(예전엔 원을 따로따로 붙여 공 여러 개처럼 보였다). twisted=true(이상함 등급)면 가지 하나가 부자연스럽게 꺾인다.
 * fruit=true(거목)면 수관에 열매가 맺힌다.
 */
private fun DrawScope.drawBranchingTree(
    baseX: Float, baseY: Float, height: Float, scale: Float, canopyScale: Float, swayPx: Float,
    leafColor: Color, trunkColor: Color, twisted: Boolean, fruit: Boolean
): Offset {
    val s = scale
    val sway = swayPx * scale
    val trunkH = height * 0.46f
    val trunkTopX = baseX + sway * 0.3f
    val trunkTopY = baseY - trunkH
    val bw = (9.5f + canopyScale * 2.2f) * s
    val tw = bw * 0.55f
    val barkDark = mix(trunkColor, Color.Black, 0.35f)
    val barkLight = mix(trunkColor, Color.White, 0.22f)

    val branchCount = (3 + canopyScale.toInt()).coerceAtMost(7)
    val branchLen = height * (0.25f + canopyScale * 0.02f)
    val leafR = height * (0.11f + 0.025f * canopyScale.coerceAtMost(3.4f))
    val clusters = mutableListOf<Offset>()
    for (i in 0 until branchCount) {
        val frac = if (branchCount == 1) 0.5f else i / (branchCount - 1).toFloat()
        var angleDeg = -70f + frac * 140f
        if (twisted && i == 1) angleDeg += 55f
        val rad = Math.toRadians(angleDeg.toDouble())
        val forkY = trunkTopY + trunkH * (0.08f + 0.14f * (i % 3))
        val forkX = trunkTopX + (baseX - trunkTopX) * ((forkY - trunkTopY) / trunkH)
        val len = branchLen * (0.85f + 0.3f * hash01(i, 5))
        val end = Offset(forkX + (sin(rad) * len).toFloat() + sway, forkY - (cos(rad) * len * 0.85).toFloat())
        drawTaperedLimb(Offset(forkX, forkY), end, tw * 0.62f, tw * 0.2f, mix(trunkColor, barkDark, 0.2f))
        clusters += end
    }

    val trunk = Path().apply {
        moveTo(baseX - bw * 1.7f, baseY + 1f * s)
        quadraticTo(baseX - bw * 0.9f, baseY - bw * 0.3f, baseX - bw, baseY - bw * 1.3f)
        quadraticTo(trunkTopX - tw * 1.1f, baseY - trunkH * 0.55f, trunkTopX - tw, trunkTopY)
        lineTo(trunkTopX + tw, trunkTopY)
        quadraticTo(trunkTopX + tw * 1.1f, baseY - trunkH * 0.55f, baseX + bw, baseY - bw * 1.3f)
        quadraticTo(baseX + bw * 0.9f, baseY - bw * 0.3f, baseX + bw * 1.7f, baseY + 1f * s)
        close()
    }
    drawPath(
        trunk,
        brush = Brush.horizontalGradient(
            0f to barkDark, 0.3f to barkLight, 0.55f to trunkColor, 1f to barkDark,
            startX = baseX - bw * 1.2f, endX = baseX + bw * 1.2f
        )
    )
    for (k in 0 until 3) {
        val ox = (-0.45f + k * 0.45f) * bw * 0.7f
        val y1 = baseY - bw * 1.6f - k * trunkH * 0.08f
        val line = Path().apply {
            moveTo(baseX + ox, y1)
            quadraticTo(baseX + ox + 2f * s, y1 - trunkH * 0.2f, trunkTopX + ox * 0.55f, y1 - trunkH * 0.42f)
        }
        drawPath(line, color = barkDark.copy(alpha = 0.45f), style = Stroke(width = 1.2f * s, cap = StrokeCap.Round))
    }

    // 수관: 가지 끝 + 가운데 + 꼭대기 + 이웃한 가지 끝 사이를 메우는 뭉치.
    val branchEnds = clusters.toList()
    clusters += Offset(trunkTopX + sway, trunkTopY - leafR * 0.55f)
    clusters += Offset(trunkTopX + sway * 0.8f, trunkTopY - leafR * 1.35f)
    for (i in 0 until branchEnds.size - 1) {
        clusters += Offset((branchEnds[i].x + branchEnds[i + 1].x) / 2f, (branchEnds[i].y + branchEnds[i + 1].y) / 2f - leafR * 0.35f)
    }
    val leafDark = mix(leafColor, Color.Black, 0.28f)
    val leafLight = mix(leafColor, Color(0xFFFFFFE0), 0.35f)
    clusters.forEach { c -> drawCircle(color = leafDark, radius = leafR * 0.98f, center = c + Offset(leafR * 0.1f, leafR * 0.16f)) }
    clusters.forEach { c -> drawCircle(color = leafColor, radius = leafR * 0.9f, center = c) }
    clusters.forEach { c -> drawCircle(color = leafLight.copy(alpha = 0.32f), radius = leafR * 0.46f, center = c + Offset(-leafR * 0.3f, -leafR * 0.34f)) }
    if (fruit) {
        clusters.forEachIndexed { i, c ->
            if (i % 2 == 0) {
                val p = c + Offset(leafR * (hash01(i, 17) - 0.5f) * 0.9f, leafR * 0.28f)
                drawCircle(color = Color(0xFFE0443A), radius = leafR * 0.13f, center = p)
                drawCircle(color = Color.White.copy(alpha = 0.5f), radius = leafR * 0.045f, center = p + Offset(-leafR * 0.04f, -leafR * 0.04f))
            }
        }
    }
    val topmostY = clusters.minOf { it.y } - leafR
    return Offset(baseX, topmostY)
}

/** 초월급(Lv.250~350) — 화면 폭 상당 부분을 차지하는 만다라 광륜과 그 중심에서 떠오르는 빛의 존재로
 *  구성된 "천상의 화신". growth(0~1)가 커질수록 광륜이 훨씬 넓게 펼쳐진다. */
private fun DrawScope.drawRadiantTree(baseX: Float, baseY: Float, height: Float, scale: Float, growthIn: Float, anim: GrowthAnim, canvasW: Float): Offset {
    val growth = growthIn.coerceIn(0f, 1f)
    val sway = anim.swayPx * scale
    val trunkH = height * (0.55f + growth * 0.12f)
    val trunkTopX = baseX + sway * 0.3f
    val trunkTopY = baseY - trunkH
    val trunkW = (13f + growth * 6f) * scale

    val haloR = (canvasW * (0.32f + growth * 0.2f) * anim.pulse).coerceAtLeast(1f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFECB3).copy(alpha = 0.45f), Color(0xFFFFE082).copy(alpha = 0.2f), Color(0xFFFFE082).copy(alpha = 0f)),
            center = Offset(baseX, trunkTopY), radius = haloR
        ),
        radius = haloR, center = Offset(baseX, trunkTopY)
    )
    for (i in 0 until 2) {
        val r = haloR * (0.55f + i * 0.28f)
        rotate(degrees = anim.rotate * (if (i == 0) 1f else -0.7f) * RAD2DEG, pivot = Offset(baseX, trunkTopY)) {
            drawOval(
                color = Color(0xFFFFF1C4).copy(alpha = (0.35f - i * 0.1f).coerceIn(0f, 1f)),
                topLeft = Offset(baseX - r, trunkTopY - r * 0.88f), size = Size(r * 2, r * 1.76f),
                style = Stroke(width = 2f * scale)
            )
        }
    }

    drawLine(
        brush = Brush.verticalGradient(listOf(Color(0xFF4A2E12), Color(0xFFD4A24C), Color(0xFFFFF3D6)), startY = baseY, endY = trunkTopY),
        start = Offset(baseX, baseY), end = Offset(trunkTopX, trunkTopY), strokeWidth = trunkW, cap = StrokeCap.Round
    )

    val branchCount = 6 + (growth * 5).toInt()
    val branchLen = haloR * (0.42f + growth * 0.1f)
    val orbR = (10f + growth * 6f) * scale
    var topmostY = trunkTopY
    for (i in 0 until branchCount) {
        val angleDeg = (360f / branchCount) * i + anim.rotate * 12f * RAD2DEG / 57.29578f * 57.29578f
        val rad = Math.toRadians(angleDeg.toDouble())
        val endX = trunkTopX + (sin(rad) * branchLen).toFloat()
        val endY = trunkTopY - (abs(cos(rad)) * branchLen * 0.5).toFloat() - branchLen * 0.22f
        drawLine(color = Color(0xFFD4A24C).copy(alpha = 0.75f), start = Offset(trunkTopX, trunkTopY), end = Offset(endX, endY), strokeWidth = trunkW * 0.13f)
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFFFFFDF2), Color(0xFFE8B84B)), center = Offset(endX, endY), radius = orbR),
            radius = orbR, center = Offset(endX, endY)
        )
        topmostY = min(topmostY, endY - orbR)
    }

    val beingH = (50f + growth * 55f) * scale * anim.pulse
    val beingW = beingH * 0.3f
    val beingPath = Path().apply {
        moveTo(trunkTopX, trunkTopY - beingH)
        quadraticTo(trunkTopX + beingW, trunkTopY - beingH * 0.5f, trunkTopX, trunkTopY)
        quadraticTo(trunkTopX - beingW, trunkTopY - beingH * 0.5f, trunkTopX, trunkTopY - beingH)
        close()
    }
    drawPath(
        beingPath,
        brush = Brush.verticalGradient(listOf(Color(0xFFFFFDF0).copy(alpha = 0.8f), Color(0xFFFFECB3).copy(alpha = 0f)), startY = trunkTopY - beingH, endY = trunkTopY)
    )

    val coreR = orbR * 1.8f * anim.pulse
    drawCircle(
        brush = Brush.radialGradient(listOf(Color(0xFFFFFDE7), Color(0xFFFFD54F)), center = Offset(trunkTopX, trunkTopY), radius = coreR.coerceAtLeast(1f)),
        radius = coreR.coerceAtLeast(1f), center = Offset(trunkTopX, trunkTopY)
    )

    return Offset(trunkTopX, trunkTopY - beingH - 12f * scale)
}

/** 종말급(Lv.350~450) — 화면 상당 부분을 뒤덮는 뒤틀린 검은 덩어리와 그 중심에서 이글거리는 "심연의
 *  눈"으로 구성된 재앙 그 자체. growth가 커질수록 덩어리가 부풀고 균열이 하늘 전체로 뻗어나간다. */
private fun DrawScope.drawCorruptedTree(baseX: Float, baseY: Float, height: Float, scale: Float, growthIn: Float, anim: GrowthAnim, canvasW: Float): Offset {
    val growth = growthIn.coerceIn(0f, 1f)
    val sway = anim.swayPx * scale
    val trunkH = height * (0.5f + growth * 0.14f)
    val trunkTopX = baseX + sway * 0.3f
    val trunkTopY = baseY - trunkH
    val trunkW = (15f + growth * 8f) * scale

    val shadowR = (canvasW * (0.5f + growth * 0.35f)).coerceAtLeast(1f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFF230505).copy(alpha = 0.34f + anim.flicker * 0.14f), Color(0xFF230505).copy(alpha = 0f)),
            center = Offset(baseX, trunkTopY), radius = shadowR
        ),
        radius = shadowR, center = Offset(baseX, trunkTopY)
    )

    val trunkPath = Path().apply {
        moveTo(baseX, baseY)
        quadraticTo(baseX - 10f * scale, baseY - trunkH * 0.5f, trunkTopX, trunkTopY)
    }
    drawPath(trunkPath, color = Color(0xFF1A1210), style = Stroke(width = trunkW, cap = StrokeCap.Round))
    drawPath(trunkPath, color = Color(0xFFFF5028).copy(alpha = (0.5f + anim.flicker * 0.4f).coerceIn(0f, 1f)), style = Stroke(width = trunkW * 0.15f, cap = StrokeCap.Round))

    val massR = (60f + growth * 70f) * scale
    val lobeCount = 7 + (growth * 3).toInt()
    val massPath = Path()
    for (i in 0..lobeCount) {
        val ang = (360f / lobeCount) * i
        val rad = Math.toRadians(ang.toDouble())
        val wobble = massR * (0.75f + 0.35f * sin(Math.toRadians((ang * 0.13f + growth * 4f * RAD2DEG).toDouble())).toFloat())
        val px = trunkTopX + (cos(rad) * wobble).toFloat()
        val py = trunkTopY - massR * 0.5f + (sin(rad) * wobble * 0.72).toFloat()
        if (i == 0) massPath.moveTo(px, py) else massPath.lineTo(px, py)
    }
    massPath.close()
    drawPath(massPath, color = Color(0xFF15100E))

    val spikeCount = 6 + (growth * 5).toInt()
    val spikeLen = massR * (0.55f + growth * 0.25f)
    var topmostY = trunkTopY - massR * 0.5f - spikeLen
    for (i in 0 until spikeCount) {
        val frac = i / (spikeCount - 1).toFloat()
        val angleDeg = -110f + frac * 220f + if (i % 2 == 0) -8f else 8f
        val rad = Math.toRadians(angleDeg.toDouble())
        val startY = trunkTopY - massR * 0.5f
        val midX = trunkTopX + (sin(rad) * spikeLen * 0.5).toFloat()
        val midY = startY - (cos(rad) * spikeLen * 0.4).toFloat()
        val rad2 = Math.toRadians(angleDeg.toDouble() + 22.9)
        val endX = trunkTopX + (sin(rad2) * spikeLen).toFloat()
        val endY = startY - (cos(rad2) * spikeLen * 0.85).toFloat()
        val spikePath = Path().apply {
            moveTo(trunkTopX, startY)
            lineTo(midX, midY)
            lineTo(endX, endY)
        }
        drawPath(spikePath, color = Color(0xFF231815), style = Stroke(width = trunkW * 0.2f, cap = StrokeCap.Round))
        val emberR = (7f + growth * 5f) * scale
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFC850).copy(alpha = (0.7f + anim.flicker * 0.3f).coerceIn(0f, 1f)), Color(0xFFB41E0A).copy(alpha = 0.2f)),
                center = Offset(endX, endY), radius = emberR
            ),
            radius = emberR, center = Offset(endX, endY)
        )
        topmostY = min(topmostY, endY - emberR)
    }

    val eyeY = trunkTopY - massR * 0.5f
    val eyeR = (10f + growth * 10f) * scale * anim.pulse
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFF8C3C).copy(alpha = (0.55f + anim.flicker * 0.35f).coerceIn(0f, 1f)), Color(0xFFFF3C14).copy(alpha = 0f)),
            center = Offset(trunkTopX, eyeY), radius = (eyeR * 2.4f).coerceAtLeast(1f)
        ),
        radius = (eyeR * 2.4f).coerceAtLeast(1f), center = Offset(trunkTopX, eyeY)
    )
    drawOval(color = Color(0xFFFFD9A0), topLeft = Offset(trunkTopX - eyeR, eyeY - eyeR * 0.55f), size = Size(eyeR * 2, eyeR * 1.1f))
    drawCircle(color = Color(0xFF1A0A05), radius = eyeR * 0.32f, center = Offset(trunkTopX, eyeY))

    listOf(-1, 0, 1).forEach { dir ->
        val crackPath = Path().apply {
            moveTo(trunkTopX + dir * 14f * scale, eyeY)
            lineTo(trunkTopX + dir * 30f * scale + 10f * scale, eyeY - height * (0.35f + growth * 0.4f))
            lineTo(trunkTopX + dir * 30f * scale - 6f * scale, eyeY - height * (0.55f + growth * 0.55f))
        }
        drawPath(crackPath, color = Color(0xFFFF8C3C).copy(alpha = (0.4f + anim.flicker * 0.45f).coerceIn(0f, 1f)), style = Stroke(width = (2f + growth * 2.5f) * scale))
    }

    return Offset(trunkTopX, topmostY)
}

/** 최강자급(Lv.450~500) — "세계수". 세 기둥이 뒤틀리며 솟아오르고, 꼭대기엔 화면 폭 대부분을 차지하는
 *  다층 화관(halo crown)이 펼쳐지며 그 중심에서 빛의 존재가 떠오른다. 이전 나무들과는 실루엣 자체가
 *  다른 "같은 식물이라고 믿기 힘든" 최종 형태. */
private fun DrawScope.drawWorldTree(baseX: Float, baseY: Float, height: Float, scale: Float, growthIn: Float, anim: GrowthAnim, canvasW: Float): Offset {
    val growth = growthIn.coerceIn(0f, 1f)
    val sway = anim.swayPx * scale
    val crownY = baseY - height * (0.66f + growth * 0.16f)
    val pillarSpread = (20f + growth * 12f) * scale

    listOf(-pillarSpread, 0f, pillarSpread).forEach { dx ->
        val bend = sin(Math.toRadians((dx * 0.05f * RAD2DEG).toDouble())).toFloat() * 14f * scale
        val topX = baseX + dx * 0.5f + sway * 0.3f
        val midX = baseX + dx * 0.25f + bend
        val midY = baseY - (baseY - crownY) * 0.5f
        val path = Path().apply {
            moveTo(baseX + dx * 0.18f, baseY)
            quadraticTo(midX, midY, topX, crownY)
        }
        drawPath(
            path,
            brush = Brush.verticalGradient(listOf(Color(0xFF2A1808), Color(0xFF8A6A3A), Color(0xFFFFE9A8)), startY = baseY, endY = crownY),
            style = Stroke(width = (13f + growth * 6f) * scale, cap = StrokeCap.Round)
        )
    }

    val crownSpan = canvasW * (0.34f + growth * 0.22f)
    val petalLayers = 4
    val petalCount = 9 + (growth * 5).toInt()
    for (layer in petalLayers downTo 1) {
        val layerFrac = layer / petalLayers.toFloat()
        val r = crownSpan * (0.4f + layerFrac * 0.6f) * anim.pulse
        val layerRotateDeg = anim.rotate * (if (layer % 2 == 0) 1f else -1f) * (6f + layer) * RAD2DEG
        for (i in 0 until petalCount) {
            val ang = (360f / petalCount) * i + layerRotateDeg
            val rad = Math.toRadians(ang.toDouble())
            val px = baseX + (cos(rad) * r).toFloat()
            val py = crownY + (sin(rad) * r * 0.42).toFloat()
            val petalR = (14f + layer * 4f + growth * 6f) * scale
            val hue = if (layer >= 3) Color(0xFFFFF8E1).copy(alpha = 0.9f) else if (layer == 2) Color(0xFFFFD56E).copy(alpha = 0.78f) else Color(0xFFFFAA5A).copy(alpha = 0.55f)
            drawCircle(
                brush = Brush.radialGradient(listOf(hue, Color(0xFFFFD56E).copy(alpha = 0f)), center = Offset(px, py), radius = petalR),
                radius = petalR, center = Offset(px, py)
            )
        }
    }

    val beingH = (70f + growth * 60f) * scale * anim.pulse
    val beingW = beingH * 0.32f
    val beingPath = Path().apply {
        moveTo(baseX, crownY - beingH)
        quadraticTo(baseX + beingW, crownY - beingH * 0.5f, baseX + beingW * 0.5f, crownY)
        quadraticTo(baseX, crownY - beingH * 0.1f, baseX - beingW * 0.5f, crownY)
        quadraticTo(baseX - beingW, crownY - beingH * 0.5f, baseX, crownY - beingH)
        close()
    }
    drawPath(
        beingPath,
        brush = Brush.verticalGradient(
            listOf(Color(0xFFFFFFFF).copy(alpha = 0.85f), Color(0xFFFFECB3).copy(alpha = 0.4f), Color(0xFFFFECB3).copy(alpha = 0f)),
            startY = crownY - beingH, endY = crownY
        )
    )

    val coreR = (20f + growth * 12f) * scale * anim.pulse
    drawCircle(
        brush = Brush.radialGradient(listOf(Color.White, Color(0xFFFFE9A8), Color(0xFFFFB43C).copy(alpha = 0f)), center = Offset(baseX, crownY), radius = coreR.coerceAtLeast(1f)),
        radius = coreR.coerceAtLeast(1f), center = Offset(baseX, crownY)
    )

    return Offset(baseX, crownY - beingH - 20f * scale)
}

/** 칭호별 전용 장식 — illustrationId가 곧 칭호이므로 텍스트와 그림이 항상 일치한다. 정상 등급(씨앗~거목)은
 *  전용 장식이 없다(나무 자체 형태만으로 충분). */
private fun DrawScope.drawGrowthIllustration(ill: String, baseX: Float, baseY: Float, anchor: Offset, w: Float, h: Float, scale: Float, anim: GrowthAnim) {
    val cx = anchor.x
    val cy = anchor.y
    val pulse = anim.pulse
    val rotate = anim.rotate
    val flicker = anim.flicker
    when (ill) {
        "eye_pot" -> {
            val blink = if (abs(sin(rotate * 0.001f + System.nanoTime() * 0.0000000004f)) > 0.06f) 1f else 0.15f
            drawOval(color = Color.White, topLeft = Offset(baseX - 9f * scale, baseY - 18f * scale - 5f * scale * blink / 2f), size = Size(18f * scale, 10f * scale * blink))
            drawCircle(color = Color(0xFF2B2B2B), radius = 3f * scale * blink, center = Offset(baseX, baseY - 18f * scale))
        }
        "murmur_tree" -> {
            drawArc(
                color = Color.Black.copy(alpha = 0.6f), startAngle = 27f, sweepAngle = 126f, useCenter = false,
                topLeft = Offset(baseX - 5f * scale, baseY - 65f * scale), size = Size(10f * scale, 10f * scale),
                style = Stroke(width = 1.5f * scale)
            )
            for (i in 0..2) {
                drawCircle(color = Color(0xFFE6DCFF).copy(alpha = 0.7f), radius = (3 - i) * scale, center = Offset(baseX + 14f * scale + i * 8f * scale, baseY - 70f * scale - i * 6f * scale))
            }
        }
        "shadow_leaf" -> {
            drawCircle(color = Color(0xFF05050F).copy(alpha = 0.6f), radius = 30f * scale * pulse, center = Offset(cx, cy))
            drawCircle(color = Color(0xFF783CC8).copy(alpha = 0.4f), radius = 38f * scale * pulse, center = Offset(cx, cy), style = Stroke(width = 1.5f * scale))
        }
        "warped_bloom" -> {
            for (a in 0 until 360 step 51) {
                val rad = Math.toRadians((a + rotate * 20f * RAD2DEG).toDouble())
                drawOval(color = Color(0xFFB266FF), topLeft = Offset(cx + (18f * scale * cos(rad)).toFloat() - 11f * scale, cy + (18f * scale * sin(rad)).toFloat() - 5f * scale), size = Size(22f * scale, 10f * scale))
            }
            drawCircle(color = Color(0xFF7B1FA2), radius = 6f * scale, center = Offset(cx, cy))
        }
        "glowing_roots" -> {
            val rootAlpha = (0.55f + pulse * 0.35f).coerceIn(0f, 1f)
            listOf(-1.1f, -0.5f, 0f, 0.5f, 1.1f).forEach { d ->
                val path = Path().apply {
                    moveTo(baseX, baseY)
                    quadraticTo(baseX + d * 16f * scale, baseY + 10f * scale, baseX + d * 30f * scale, baseY + 16f * scale)
                }
                drawPath(path, color = Color(0xFFFFD778).copy(alpha = rootAlpha), style = Stroke(width = 2.2f * scale))
            }
            drawAuraRingsFx(cx, cy, scale, 2, Color(0xFFFFD778).copy(alpha = 0.4f), pulse, rotate)
        }
        "geometric_halo" -> {
            rotate(degrees = rotate * RAD2DEG, pivot = Offset(cx, cy)) {
                drawCircle(color = Color(0xFFB39DDB).copy(alpha = 0.7f), radius = 42f * scale * pulse, center = Offset(cx, cy), style = Stroke(width = 2f * scale))
                listOf(3, 6).forEach { sides ->
                    val path = Path()
                    for (i in 0..sides) {
                        val a = (2 * Math.PI / sides) * i - Math.PI / 2
                        val px = cx + (cos(a) * 40f * scale * pulse).toFloat()
                        val py = cy + (sin(a) * 40f * scale * pulse).toFloat()
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    drawPath(path, color = Color(0xFFB39DDB).copy(alpha = 0.7f), style = Stroke(width = 2f * scale))
                }
            }
            drawAuraRingsFx(cx, cy, scale, 3, Color(0xFFB39DDB).copy(alpha = 0.3f), pulse, rotate)
        }
        "afterimage" -> {
            for (i in 1..4) {
                drawCircle(color = Color(0xFFB39DDB).copy(alpha = 0.15f), radius = 22f * scale, center = Offset(cx - i * 7f * scale, cy))
                drawCircle(color = Color(0xFFB39DDB).copy(alpha = 0.15f), radius = 22f * scale, center = Offset(cx + i * 7f * scale, cy))
            }
            drawGodRaysFx(cx, cy, scale, 10, rotate)
            drawAuraRingsFx(cx, cy, scale, 2, Color.White.copy(alpha = 0.25f), pulse, rotate)
        }
        "cracked_start" -> {
            drawCircle(color = Color(0xFF140A0A).copy(alpha = (0.35f + flicker * 0.25f).coerceIn(0f, 1f)), radius = 32f * scale, center = Offset(cx, cy))
            drawCircle(color = Color(0xFFFF7850).copy(alpha = (flicker * 0.5f).coerceIn(0f, 1f)), radius = 40f * scale, center = Offset(cx, cy), style = Stroke(width = 1.5f * scale))
        }
        "floating_debris" -> {
            listOf(0.3f to 0.5f, 0.68f to 0.42f, 0.78f to 0.6f, 0.22f to 0.65f, 0.55f to 0.35f).forEachIndexed { i, (fx, fy) ->
                val bob = sin(System.nanoTime() * 0.000000002f + i) * 4f * scale
                drawCircle(color = Color(0xFF8D6E63), radius = 5f * scale, center = Offset(w * fx, h * fy + bob))
            }
            drawAuraRingsFx(cx, cy, scale, 2, Color(0xFFDC5A32).copy(alpha = (0.25f + flicker * 0.2f).coerceIn(0f, 1f)), pulse, rotate)
        }
        "crown_shockwave" -> {
            val cw = 16f * scale
            val crownPath = Path().apply {
                moveTo(cx - cw, cy + 6f * scale)
                lineTo(cx - cw, cy - 6f * scale)
                lineTo(cx - cw / 2, cy + 1f * scale)
                lineTo(cx, cy - 12f * scale)
                lineTo(cx + cw / 2, cy + 1f * scale)
                lineTo(cx + cw, cy - 6f * scale)
                lineTo(cx + cw, cy + 6f * scale)
                close()
            }
            drawPath(crownPath, color = Color(0xFFFFD700))
            drawShockwaveFx(cx, cy, scale, pulse)
            drawCrackedGroundPatch(w, h, scale, 2, (0.3f + flicker * 0.3f).coerceIn(0f, 1f))
        }
        "giant_shadow" -> {
            val shadowPath = Path().apply {
                moveTo(w * 0.08f, 0f)
                lineTo(w * 0.92f, 0f)
                lineTo(w * 0.68f, h * 0.52f)
                lineTo(w * 0.32f, h * 0.52f)
                close()
            }
            drawPath(shadowPath, color = Color(0xFF0A0514).copy(alpha = (0.5f + flicker * 0.1f).coerceIn(0f, 1f)))
            drawLine(
                color = Color.White.copy(alpha = (flicker * 0.6f).coerceIn(0f, 1f)), strokeWidth = 1.5f * scale,
                start = Offset(w * 0.3f, 0f), end = Offset(w * 0.45f, h * 0.3f)
            )
            drawAuraRingsFx(cx, cy, scale, 3, Color(0xFF9664DC).copy(alpha = 0.4f), pulse, rotate)
        }
        "full_aura" -> {
            drawAuraRingsFx(cx, cy, scale, 4, Color(0xFFFFD54F).copy(alpha = 0.4f), pulse, rotate)
            drawLightningBoltsFx(cx, cy, scale, 5, flicker)
            drawGodRaysFx(cx, cy, scale, 10, rotate)
            drawShockwaveFx(cx, cy, scale, pulse)
        }
        "ultimate" -> {
            drawAuraRingsFx(cx, cy, scale, 6, Color(0xFFFFD700).copy(alpha = 0.45f), pulse, rotate)
            drawLightningBoltsFx(cx, cy, scale, 7, flicker)
            drawShockwaveFx(cx, cy, scale, pulse)
            drawShockwaveFx(cx, cy, scale, pulse * 1.25f)
            drawGodRaysFx(cx, cy, scale, 14, -rotate)
        }
        else -> {}
    }
}

private fun DrawScope.drawAuraRingsFx(cx: Float, cy: Float, scale: Float, count: Int, color: Color, pulse: Float, rotateVal: Float) {
    for (i in 1..count) {
        val r = (24f + i * 16f) * scale * pulse
        rotate(degrees = rotateVal * (if (i % 2 == 0) 1f else -1f) * 0.3f * RAD2DEG, pivot = Offset(cx, cy)) {
            drawOval(color = color, topLeft = Offset(cx - r, cy - r * 0.94f), size = Size(r * 2, r * 1.88f), style = Stroke(width = 2.5f * scale))
        }
    }
}

private fun DrawScope.drawLightningBoltsFx(cx: Float, cy: Float, scale: Float, count: Int, flicker: Float) {
    val color = Color(0xFFFFEB3B).copy(alpha = flicker.coerceIn(0f, 1f))
    for (i in 0 until count) {
        val angle = (360f / count) * i - 90f
        val rad = Math.toRadians(angle.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val r1 = 30f * scale
        val r2 = 54f * scale
        val midX = cx + dx * r1 + (if (i % 2 == 0) 7f else -7f) * scale
        val midY = cy + dy * r1
        val path = Path().apply {
            moveTo(cx + dx * 12f * scale, cy + dy * 12f * scale)
            lineTo(midX, midY)
            lineTo(cx + dx * r2, cy + dy * r2)
        }
        drawPath(path, color = color, style = Stroke(width = 2.5f * scale))
    }
}

private fun DrawScope.drawShockwaveFx(cx: Float, cy: Float, scale: Float, pulse: Float) {
    drawCircle(color = Color.White.copy(alpha = 0.7f), radius = 80f * scale * pulse, center = Offset(cx, cy), style = Stroke(width = 4f * scale))
    drawCircle(color = Color.White.copy(alpha = 0.35f), radius = 105f * scale * (2f - pulse), center = Offset(cx, cy), style = Stroke(width = 2.5f * scale))
}

private fun DrawScope.drawGodRaysFx(cx: Float, cy: Float, scale: Float, count: Int, rotateVal: Float) {
    val color = Color(0xFFFFF9C4).copy(alpha = 0.55f)
    for (i in 0 until count) {
        val rad = (360f / count) * i * Math.PI / 180.0 + rotateVal
        drawLine(
            color = color, strokeWidth = 3f * scale,
            start = Offset(cx, cy),
            end = Offset(cx + (cos(rad) * 100f * scale).toFloat(), cy + (sin(rad) * 100f * scale).toFloat())
        )
    }
}

// ══════════════════════════════════════════════════════
// 꾸미기 아이템 일러스트(121차, 사용자 요청: "포인트를 써서 산 게 이모지 하나만 뜬다") — 116차엔 장식이
// 씬 위에 Text로 이모지를 얹는 방식이었다. 그래서 ① 나무/땅 일러스트(전부 Canvas 벡터)와 화풍이 따로 놀고
// ② 크기가 작아 "샀는데 뭐가 달라졌는지" 잘 안 보였다. 이제 장식도 나무와 같은 Canvas 위에 같은 방식(도형
// 조합 + 등급별 애니메이션 파라미터 공유)으로 그린다.
//
// 종류는 두 가지다.
// - [DecorationKind.PROP]: 화분 주변 정원에 놓이는 소품(등/벤치/버섯/분수/깃발 — 나비는 식물 둘레를 난다).
//   134차부터 소품마다 정해진 자리([PROP_SPOTS], 가로 위치 + 깊이)가 있어 배치 순서와 상관없이 같은 곳에 놓인다.
// - [DecorationKind.SCENERY]: 장면에 깔리는 배경 요소(울타리/조약돌길/연못/반딧불이). 각자 제 깊이에 그려져
//   소품과 겹치지 않고, "배경까지 바꾸는" 확장 축을 열어둔다.
// 새 장식을 추가할 땐 [DECORATION_CATALOG]에 한 줄 + 소품이면 [PROP_SPOTS]에 자리 하나와 [drawProp] 분기, 배경이면
// [drawDecorationsBehindPot]/[drawAirborneDecorations]에 그리기 한 줄을 더한다.
// ══════════════════════════════════════════════════════

internal enum class DecorationKind { PROP, SCENERY }

internal data class DecorationItem(
    val id: String,
    val label: String,
    val description: String,
    val cost: Int,
    val kind: DecorationKind
)

internal val DECORATION_CATALOG = listOf(
    DecorationItem("mushroom", "버섯 무리", "화분 옆에 돋아난 빨간 버섯 세 송이", 15, DecorationKind.PROP),
    DecorationItem("flag", "깃발", "바람에 나부끼는 삼각 깃발", 20, DecorationKind.PROP),
    DecorationItem("butterfly_deco", "나비들", "나무 주위를 맴도는 나비 세 마리", 25, DecorationKind.PROP),
    DecorationItem("lamp", "종이등", "따뜻한 빛이 번지는 등불 기둥", 30, DecorationKind.PROP),
    DecorationItem("path", "조약돌 길", "화분 앞으로 이어지는 징검돌", 35, DecorationKind.SCENERY),
    DecorationItem("bench", "벤치", "앉아서 쉬어갈 수 있는 나무 벤치", 40, DecorationKind.PROP),
    DecorationItem("fence", "나무 울타리", "장면 전체를 감싸는 말뚝 울타리", 50, DecorationKind.SCENERY),
    DecorationItem("fountain", "작은 분수", "물줄기가 솟는 돌 분수", 60, DecorationKind.PROP),
    DecorationItem("pond", "작은 연못", "잔물결이 이는 연못과 수련잎", 70, DecorationKind.SCENERY),
    DecorationItem("fireflies", "반딧불이", "허공을 천천히 떠다니는 빛무리", 80, DecorationKind.SCENERY)
)

internal fun decorationById(id: String): DecorationItem? = DECORATION_CATALOG.find { it.id == id }

/**
 * 소품마다 정해둔 자리(134차) — 121차엔 배치한 순서대로 빈칸(가로 비율 5개)에 채워 넣어서 소품이 전부 화분
 * 바닥선 한 줄에 "진열대"처럼 늘어섰고, 칸이 좁아 등불이 벤치를 가리기도 했다(사용자 지적 "위치·조화가 마음에
 * 안 든다"). 이제 소품마다 화분을 중심으로 한 정원 안에서 어울리는 자리(가로 위치 + 깊이)를 하나씩 갖는다:
 * 멀리(뒤쪽) 깃발, 오른쪽 쉼터엔 벤치와 그 위로 불을 비추는 등불, 왼쪽엔 분수, 화분 발치엔 버섯. 깊이가 얕을수록
 * 위에·작게 그려지고, 화분보다 앞이면 화분을 살짝 가린다. 여섯 자리를 서로 겹치지 않게 맞춰 두었으므로 어떤
 * 조합으로 배치해도 겹치지 않는다. 나비는 땅에 놓이지 않고 식물 꼭대기 둘레를 맴돈다.
 */
private data class PropSpot(val x: Float, val depth: Float, val facingLeft: Boolean = false)

private val PROP_SPOTS = mapOf(
    "flag" to PropSpot(0.27f, 0.47f),
    "lamp" to PropSpot(0.87f, 0.62f, facingLeft = true),
    "bench" to PropSpot(0.72f, 0.68f),
    "fountain" to PropSpot(0.17f, 0.80f),
    "mushroom" to PropSpot(0.385f, 0.785f)
)

/** 소품은 원근 배율보다 조금 크게 그린다 — 그대로면 실제 홈 화면에서 너무 작아 "뭐가 달라졌는지" 안 보인다. */
private const val DECORATION_PROP_SCALE = 1.5f

/** 장식 그리기에 필요한 씬 기하 — [GroundScene]이 계산해둔 값을 넘겨 재계산을 막는다. 깊이(0 = 지평선,
 *  1 = 앞 가장자리)로 땅 위 위치와 원근 배율을 얻는다. */
internal data class DecorationScene(
    val w: Float,
    val h: Float,
    val scale: Float,
    val horizonY: Float,
    val tMs: Float,
    /** 나비가 맴도는 중심(식물 꼭대기). */
    val plantAnchor: Offset,
    /** 꾸미기 상점의 작은 미리보기 — 한쪽에 치우친 배경(연못)도 가운데에 그린다. */
    val preview: Boolean = false
) {
    fun yAt(depth: Float): Float = horizonY + (h - horizonY) * depth
    fun scaleAt(depth: Float): Float = scale * (1f + 0.825f * (depth - POT_DEPTH))
}

/** 화분보다 멀리 있는 꾸미기 — 땅에 깔리는 배경(먼 것부터 울타리 → 길 → 연못)과 화분 뒤편 소품(먼 것부터). */
private fun DrawScope.drawDecorationsBehindPot(ids: List<String>, scene: DecorationScene, fence: FencePaths? = null) {
    if ("fence" in ids) drawFence(fence ?: buildFence(scene))
    if ("path" in ids) drawStonePathScenery(scene)
    if ("pond" in ids) drawPondScenery(scene)
    drawPropsWhere(ids, scene) { it < POT_DEPTH }
}

/** 화분보다 앞에 있는 소품 — 화분·식물을 그린 뒤에 그려 화분 발치를 자연스럽게 가린다. */
private fun DrawScope.drawDecorationsInFrontOfPot(ids: List<String>, scene: DecorationScene) {
    drawPropsWhere(ids, scene) { it >= POT_DEPTH }
}

/** 공중에 떠 있는 꾸미기 — 맨 위 레이어(식물에 가려지지 않게). */
private fun DrawScope.drawAirborneDecorations(ids: List<String>, scene: DecorationScene) {
    if ("butterfly_deco" in ids) drawButterflies(scene)
    if ("fireflies" in ids) drawFirefliesScenery(scene)
}

private fun DrawScope.drawPropsWhere(ids: List<String>, scene: DecorationScene, depthFilter: (Float) -> Boolean) {
    ids.mapNotNull { id -> PROP_SPOTS[id]?.let { id to it } }
        .filter { depthFilter(it.second.depth) }
        .sortedBy { it.second.depth }
        .forEach { (id, spot) -> drawProp(id, scene, spot) }
}

private fun DrawScope.drawProp(id: String, scene: DecorationScene, spot: PropSpot) {
    val x = scene.w * spot.x
    val y = scene.yAt(spot.depth)
    val s = scene.scaleAt(spot.depth) * DECORATION_PROP_SCALE
    when (id) {
        "mushroom" -> drawMushroomCluster(x, y, s)
        "flag" -> drawFlagProp(x, y, s, scene.tMs)
        "lamp" -> drawLampProp(x, y, s, scene.tMs, spot.facingLeft)
        "bench" -> drawBenchProp(x, y, s)
        "fountain" -> drawFountainProp(x, y, s, scene.tMs)
    }
}

/** 소품 밑에 항상 깔아주는 타원 그림자 — 땅에 "놓여 있다"는 접지감을 준다. */
private fun DrawScope.drawPropShadow(x: Float, groundY: Float, scale: Float, widthUnits: Float) {
    drawOval(
        color = Color.Black.copy(alpha = 0.16f),
        topLeft = Offset(x - widthUnits * scale, groundY - 3f * scale),
        size = Size(widthUnits * 2f * scale, 6f * scale)
    )
}

private fun DrawScope.drawMushroomCluster(x: Float, groundY: Float, scale: Float) {
    drawPropShadow(x, groundY, scale, 16f)
    // 큰 것 하나 + 작은 것 둘. 갓은 반원, 기둥은 둥근 사각형, 갓 위 흰 점으로 "버섯"임을 분명히.
    data class Cap(val dx: Float, val capR: Float, val stemH: Float)
    listOf(Cap(0f, 11f, 13f), Cap(-12f, 7f, 8f), Cap(11f, 6f, 7f)).forEach { m ->
        val mx = x + m.dx * scale
        val stemTop = groundY - m.stemH * scale
        drawRoundRect(
            color = Color(0xFFF5EDDC),
            topLeft = Offset(mx - 2.6f * scale, stemTop),
            size = Size(5.2f * scale, m.stemH * scale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.4f * scale, 2.4f * scale)
        )
        val capPath = Path().apply {
            moveTo(mx - m.capR * scale, stemTop + 1f * scale)
            quadraticTo(mx, stemTop - m.capR * 1.5f * scale, mx + m.capR * scale, stemTop + 1f * scale)
            close()
        }
        drawPath(capPath, color = Color(0xFFD7453C))
        drawCircle(color = Color(0xFFFFF3E0), radius = m.capR * 0.2f * scale, center = Offset(mx - m.capR * 0.35f * scale, stemTop - m.capR * 0.45f * scale))
        drawCircle(color = Color(0xFFFFF3E0), radius = m.capR * 0.16f * scale, center = Offset(mx + m.capR * 0.4f * scale, stemTop - m.capR * 0.25f * scale))
    }
}

private fun DrawScope.drawFlagProp(x: Float, groundY: Float, scale: Float, tMs: Float) {
    drawPropShadow(x, groundY, scale, 8f)
    val poleH = 46f * scale
    val top = groundY - poleH
    drawLine(color = Color(0xFF8D6E63), start = Offset(x, groundY), end = Offset(x, top), strokeWidth = 3f * scale, cap = StrokeCap.Round)
    drawCircle(color = Color(0xFFFFD54F), radius = 3f * scale, center = Offset(x, top - 1f * scale))
    // 삼각 페넌트 — 끝점만 사인파로 흔들어 "펄럭임"을 만든다.
    val wave = sin(tMs / 260f) * 4f * scale
    val flagPath = Path().apply {
        moveTo(x + 1.5f * scale, top + 2f * scale)
        quadraticTo(x + 18f * scale, top + 6f * scale + wave, x + 30f * scale, top + 11f * scale + wave)
        quadraticTo(x + 16f * scale, top + 15f * scale, x + 1.5f * scale, top + 20f * scale)
        close()
    }
    drawPath(flagPath, color = Color(0xFFE2574C))
    drawPath(flagPath, color = Color(0xFF9E3A32).copy(alpha = 0.45f), style = Stroke(width = 1f * scale))
}

private fun DrawScope.drawButterflies(scene: DecorationScene) {
    // 땅에 놓이는 물건이 아니라 식물 꼭대기 둘레를 맴도는 장식 — 그림자 없이 타원 궤도를 돈다. 날개는 위/아래 두 장씩
    // 색을 달리해 겹치고, flap 비율로 가로만 눌러 정면에서 본 날갯짓처럼 보이게 한다.
    val scale = scene.scale * 1.25f
    val center = scene.plantAnchor + Offset(0f, 8f * scene.scale)
    val tMs = scene.tMs
    val colors = listOf(
        Color(0xFFFFB74D) to Color(0xFFF57C00),
        Color(0xFF9C89E8) to Color(0xFF6A4FC4),
        Color(0xFF5FCFC4) to Color(0xFF2E9E93)
    )
    val body = Color(0xFF3E2723)
    for (i in 0 until 3) {
        val phase = tMs / 1000f * (0.7f + i * 0.13f) + i * 2.1f
        val bx = center.x + cos(phase) * (28f + i * 7f) * scale
        val by = center.y + (i * 11f - 12f) * scale + sin(phase * 1.7f) * 8f * scale
        val flap = 0.3f + abs(sin(tMs / 110f + i)) * 0.7f
        val wingW = 9f * scale
        val wingH = 11f * scale
        val (upper, lower) = colors[i]
        listOf(-1f, 1f).forEach { dir ->
            val upperLeft = if (dir < 0f) bx - wingW * flap else bx
            drawOval(color = upper, topLeft = Offset(upperLeft, by - wingH * 0.55f), size = Size(wingW * flap, wingH * 0.9f))
            val lowerLeft = if (dir < 0f) bx - wingW * 0.72f * flap else bx
            drawOval(color = lower, topLeft = Offset(lowerLeft, by - wingH * 0.05f), size = Size(wingW * 0.72f * flap, wingH * 0.62f))
        }
        drawOval(color = body, topLeft = Offset(bx - 1.5f * scale, by - wingH * 0.6f), size = Size(3f * scale, wingH * 1.15f))
        listOf(-1f, 1f).forEach { dir ->
            drawLine(
                color = body,
                start = Offset(bx, by - wingH * 0.6f),
                end = Offset(bx + dir * 2.6f * scale, by - wingH * 1.05f),
                strokeWidth = 1f * scale, cap = StrokeCap.Round
            )
        }
    }
}

/** 종이등 기둥 — [facingLeft]면 팔이 왼쪽으로 뻗어 옆(벤치 쪽)을 비춘다. */
private fun DrawScope.drawLampProp(x: Float, groundY: Float, scale: Float, tMs: Float, facingLeft: Boolean = false) {
    drawPropShadow(x, groundY, scale, 10f)
    val dir = if (facingLeft) -1f else 1f
    val postH = 54f * scale
    val top = groundY - postH
    drawLine(color = Color(0xFF6D4C41), start = Offset(x, groundY), end = Offset(x, top), strokeWidth = 4f * scale, cap = StrokeCap.Round)
    drawLine(color = Color(0xFF6D4C41), start = Offset(x, top), end = Offset(x + dir * 15f * scale, top), strokeWidth = 3f * scale, cap = StrokeCap.Round)
    val lx = x + dir * 15f * scale
    val ly = top + 16f * scale
    val glow = 0.6f + 0.2f * sin(tMs / 700f)
    // 빛 번짐 → 매다는 줄 → 위 뚜껑 → 등 몸통 → 속 불빛 → 살 → 아래 뚜껑 → 술 순서로 겹쳐 종이등을 만든다.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFC460).copy(alpha = glow * 0.6f), Color(0xFFFFC460).copy(alpha = 0f)),
            center = Offset(lx, ly), radius = (36f * scale).coerceAtLeast(1f)
        ),
        radius = 36f * scale, center = Offset(lx, ly)
    )
    drawLine(color = Color(0xFF4E342E), start = Offset(lx, top), end = Offset(lx, ly - 13f * scale), strokeWidth = 1.2f * scale)
    drawOval(color = Color(0xFF8D3B2E), topLeft = Offset(lx - 7f * scale, ly - 15f * scale), size = Size(14f * scale, 4f * scale))
    drawOval(color = Color(0xFFE05B4B), topLeft = Offset(lx - 11f * scale, ly - 13f * scale), size = Size(22f * scale, 26f * scale))
    drawOval(color = Color(0xFFFFD696).copy(alpha = 0.8f), topLeft = Offset(lx - 7.5f * scale, ly - 10f * scale), size = Size(15f * scale, 20f * scale))
    listOf(-4.5f, 0f, 4.5f).forEach { dx ->
        drawLine(
            color = Color(0xFFB03B2E).copy(alpha = 0.55f),
            start = Offset(lx + dx * scale, ly - 11.5f * scale), end = Offset(lx + dx * scale, ly + 11f * scale),
            strokeWidth = 0.9f * scale
        )
    }
    drawOval(color = Color(0xFF8D3B2E), topLeft = Offset(lx - 7f * scale, ly + 10f * scale), size = Size(14f * scale, 4f * scale))
    drawLine(color = Color(0xFFC9463A), start = Offset(lx, ly + 12f * scale), end = Offset(lx, ly + 19f * scale), strokeWidth = 2f * scale, cap = StrokeCap.Round)
    drawCircle(color = Color(0xFFC9463A), radius = 2f * scale, center = Offset(lx, ly + 20f * scale))
}

private fun DrawScope.drawBenchProp(x: Float, groundY: Float, scale: Float) {
    drawPropShadow(x, groundY, scale, 22f)
    val seatY = groundY - 14f * scale
    val halfW = 20f * scale
    val wood = Color(0xFFB07B4F)
    val woodDark = Color(0xFF8B5E3C)
    // 다리 2개 → 앉는 판 → 등받이 살 2줄 순서.
    listOf(-halfW + 4f * scale, halfW - 8f * scale).forEach { dx ->
        drawRect(color = woodDark, topLeft = Offset(x + dx, seatY), size = Size(4f * scale, 14f * scale))
    }
    drawRoundRect(
        color = wood, topLeft = Offset(x - halfW, seatY - 4f * scale), size = Size(halfW * 2f, 5f * scale),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * scale, 2f * scale)
    )
    listOf(-halfW + 3f * scale, halfW - 6f * scale).forEach { dx ->
        drawRect(color = woodDark, topLeft = Offset(x + dx, seatY - 20f * scale), size = Size(3f * scale, 17f * scale))
    }
    listOf(20f, 14f).forEach { dy ->
        drawRoundRect(
            color = wood, topLeft = Offset(x - halfW + 2f * scale, seatY - dy * scale), size = Size(halfW * 2f - 4f * scale, 4f * scale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * scale, 1.5f * scale)
        )
    }
}

private fun DrawScope.drawFountainProp(x: Float, groundY: Float, scale: Float, tMs: Float) {
    drawPropShadow(x, groundY, scale, 20f)
    val basinTop = groundY - 12f * scale
    val halfW = 18f * scale
    drawOval(color = Color(0xFF9E9E93), topLeft = Offset(x - halfW, basinTop - 4f * scale), size = Size(halfW * 2f, 16f * scale))
    drawOval(color = Color(0xFF6FB6D8), topLeft = Offset(x - halfW + 3f * scale, basinTop - 1.5f * scale), size = Size(halfW * 2f - 6f * scale, 10f * scale))
    drawRect(color = Color(0xFFB0AFA4), topLeft = Offset(x - 3f * scale, basinTop - 20f * scale), size = Size(6f * scale, 20f * scale))
    drawOval(color = Color(0xFF9E9E93), topLeft = Offset(x - 8f * scale, basinTop - 24f * scale), size = Size(16f * scale, 6f * scale))
    // 좌우 대칭 물줄기 + 수면 잔물결(시간에 따라 반지름이 커지며 옅어짐).
    val spoutY = basinTop - 22f * scale
    listOf(-1f, 1f).forEach { dir ->
        val jet = Path().apply {
            moveTo(x, spoutY)
            quadraticTo(x + dir * 12f * scale, spoutY - 12f * scale, x + dir * 15f * scale, basinTop + 1f * scale)
        }
        drawPath(jet, color = Color(0xFF9CD6EE).copy(alpha = 0.85f), style = Stroke(width = 2.2f * scale, cap = StrokeCap.Round))
    }
    val ripple = (tMs / 900f) % 1f
    drawOval(
        color = Color.White.copy(alpha = 0.35f * (1f - ripple)),
        topLeft = Offset(x - halfW * 0.7f * ripple, basinTop + 2f * scale - 3f * scale * ripple),
        size = Size(halfW * 1.4f * ripple, 6f * scale * ripple),
        style = Stroke(width = 1.2f * scale)
    )
}

private fun DrawScope.drawStonePathScenery(scene: DecorationScene) {
    // 화면 앞쪽에서 화분 발치까지 굽이지며 이어지는 징검돌 한 줄 — 멀수록 작고 촘촘하게(원근).
    val steps = 6
    for (i in 0 until steps) {
        val t = i / (steps - 1f) // 0 = 맨 앞, 1 = 화분 앞
        // 미리보기 칸은 작아서 앞쪽 돌이 잘리므로 깊이 범위를 칸 안쪽으로 당긴다.
        val depth = if (scene.preview) 0.93f - 0.45f * t else 0.985f - 0.195f * t
        val zig = if (i % 2 == 0) 0.022f else -0.022f
        val x = scene.w * (0.5f + 0.06f * sin((1f - t) * 1.6f) + zig * (1f - t * 0.5f))
        val y = scene.yAt(depth)
        val s = scene.scaleAt(depth)
        val rx = 14f * s
        val ry = rx * 0.42f
        drawOval(color = Color(0xFF8F8A7C), topLeft = Offset(x - rx, y - ry + 1.4f * s), size = Size(rx * 2f, ry * 2f))
        drawOval(color = Color(0xFFCBC5B6), topLeft = Offset(x - rx, y - ry), size = Size(rx * 2f, ry * 2f))
        drawOval(color = Color(0xFFE6E1D5).copy(alpha = 0.75f), topLeft = Offset(x - rx * 0.55f, y - ry * 0.75f), size = Size(rx * 0.8f, ry * 0.6f))
    }
}

/** 먼 뒤쪽(깊이 0.2)을 가로지르는 말뚝 울타리 — 정원의 경계. 말뚝 수십 개를 Path 두 개(몸통·그늘 반쪽)에 모아
 *  한 번씩만 칠한다(모양이 안 변하므로 [GroundScene]의 캐시에서 한 번만 만든다). */
private class FencePaths(val y: Float, val s: Float, val w: Float, val pickets: Path, val shade: Path)

private fun buildFence(scene: DecorationScene): FencePaths {
    val depth = 0.2f
    val y = scene.yAt(depth)
    val s = scene.scaleAt(depth)
    val postH = 25f * s
    val step = 22f * s
    val halfW = 2.8f * s
    val pickets = Path()
    val shade = Path()
    var px = step / 2f
    while (px < scene.w + step) {
        pickets.moveTo(px - halfW, y)
        pickets.lineTo(px - halfW, y - postH)
        pickets.lineTo(px, y - postH - 4.5f * s)
        pickets.lineTo(px + halfW, y - postH)
        pickets.lineTo(px + halfW, y)
        pickets.close()
        shade.moveTo(px, y)
        shade.lineTo(px, y - postH - 4.5f * s)
        shade.lineTo(px + halfW, y - postH)
        shade.lineTo(px + halfW, y)
        shade.close()
        px += step
    }
    return FencePaths(y, s, scene.w, pickets, shade)
}

private fun DrawScope.drawFence(f: FencePaths) {
    val s = f.s
    val wood = Color(0xFFE3C9A0)
    val woodShade = Color(0xFFB9935F)
    val woodDark = Color(0xFF8E6B40)
    drawRect(color = Color.Black.copy(alpha = 0.1f), topLeft = Offset(-4f * s, f.y - 1f * s), size = Size(f.w + 8f * s, 3.5f * s))
    listOf(8f, 17f).forEach { dy ->
        val railTop = f.y - dy * s - 1.6f * s
        drawRect(
            brush = Brush.verticalGradient(listOf(wood, woodShade), startY = railTop, endY = railTop + 3.2f * s),
            topLeft = Offset(-4f * s, railTop), size = Size(f.w + 8f * s, 3.2f * s)
        )
    }
    drawPath(f.pickets, color = wood)
    drawPath(f.shade, color = woodShade.copy(alpha = 0.6f))
    drawPath(f.pickets, color = woodDark.copy(alpha = 0.35f), style = Stroke(width = 0.7f * s))
}

private fun DrawScope.drawPondScenery(scene: DecorationScene) {
    // 왼쪽 앞(분수 아래쪽) 땅의 작은 연못 — 물가 테두리 + 조약돌 + 물(하늘 반사) + 잔물결 + 수련잎과 연꽃.
    val depth = if (scene.preview) 0.7f else 0.935f
    val cx = scene.w * (if (scene.preview) 0.5f else 0.2f)
    val cy = scene.yAt(depth)
    val s = scene.scaleAt(depth)
    val rx = 44f * s
    val ry = 17f * s
    drawOval(
        color = Color(0xFF6B7F52).copy(alpha = 0.55f),
        topLeft = Offset(cx - rx - 4f * s, cy - ry - 3f * s),
        size = Size((rx + 4f * s) * 2f, (ry + 3f * s) * 2f + 2f * s)
    )
    drawOval(
        brush = Brush.verticalGradient(listOf(Color(0xFF7FC4E0), Color(0xFF3F7EA6)), startY = cy - ry, endY = cy + ry),
        topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2f, ry * 2f)
    )
    drawOval(color = Color.White.copy(alpha = 0.3f), topLeft = Offset(cx - rx * 0.55f, cy - ry * 0.62f), size = Size(rx * 0.7f, ry * 0.22f))
    for (i in 0 until 2) {
        val t = ((scene.tMs / 1400f) + i * 0.5f) % 1f
        drawOval(
            color = Color.White.copy(alpha = 0.3f * (1f - t)),
            topLeft = Offset(cx + rx * 0.15f - rx * 0.5f * t, cy + ry * 0.1f - ry * 0.5f * t), size = Size(rx * t, ry * t),
            style = Stroke(width = 1.2f * s)
        )
    }
    for (k in 0 until 12) {
        val a = k / 12f * 6.283f + 0.3f
        val p = Offset(cx + cos(a) * (rx + 2.5f * s), cy + sin(a) * (ry + 1.8f * s))
        val pr = (2.2f + hash01(k, 29) * 1.6f) * s
        drawOval(
            color = if (k % 2 == 0) Color(0xFFB9B3A3) else Color(0xFF9C9686),
            topLeft = Offset(p.x - pr, p.y - pr * 0.6f), size = Size(pr * 2f, pr * 1.2f)
        )
    }
    listOf(Triple(-0.42f, -0.2f, 20f), Triple(0.38f, 0.28f, 200f)).forEachIndexed { i, (fx, fy, rot) ->
        val lx = cx + rx * fx
        val ly = cy + ry * fy
        drawArc(
            color = Color(0xFF4E9B54), startAngle = rot, sweepAngle = 320f, useCenter = true,
            topLeft = Offset(lx - 8f * s, ly - 4f * s), size = Size(16f * s, 8f * s)
        )
        if (i == 0) {
            listOf(-3f, 0f, 3f).forEach { dx ->
                drawOval(color = Color(0xFFF8A5C2), topLeft = Offset(lx + dx * s - 2f * s, ly - 5.5f * s), size = Size(4f * s, 5f * s))
            }
            drawCircle(color = Color(0xFFFFE082), radius = 1.3f * s, center = Offset(lx, ly - 3f * s))
        }
    }
}

private fun DrawScope.drawFirefliesScenery(scene: DecorationScene) {
    // 정원 위를 천천히 떠다니는 빛무리 — 밝기가 제각기 다른 주기로 깜빡여 "살아있는" 느낌을 준다.
    val top = scene.horizonY * 0.9f
    val bottom = scene.yAt(0.92f)
    for (i in 0 until 12) {
        val seed = i * 1.37f
        val phase = scene.tMs / 1000f * (0.16f + (i % 4) * 0.05f) + seed
        val fx = ((sin(phase) * 0.5f + 0.5f) * 0.9f + 0.05f) * scene.w
        val fy = top + (sin(phase * 1.6f + seed) * 0.5f + 0.5f) * (bottom - top)
        val blink = (0.25f + 0.75f * abs(sin(scene.tMs / 620f + seed))).coerceIn(0f, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFF59D).copy(alpha = 0.55f * blink), Color(0xFFFFF59D).copy(alpha = 0f)),
                center = Offset(fx, fy), radius = (9f * scene.scale).coerceAtLeast(1f)
            ),
            radius = 9f * scene.scale, center = Offset(fx, fy)
        )
        drawCircle(color = Color(0xFFFFFDE7).copy(alpha = 0.9f * blink), radius = 1.8f * scene.scale, center = Offset(fx, fy))
    }
}

/**
 * 꾸미기 상점의 아이템 미리보기 — 목록에서도 실제 홈 화면에 그려질 모양 그대로 보여준다(이모지 목록으로는
 * "사면 뭐가 나오는지"를 알 수 없어 구매 판단이 안 된다는 지적). 홈 화면과 같은 그리기 함수를 작은 씬 기하로
 * 한 번 더 부르는 것이라 미리보기와 실제 모습이 어긋날 수 없다(소품은 화분 깊이, 배경은 제 깊이에 그린다).
 */
@Composable
internal fun DecorationPreview(item: DecorationItem, modifier: Modifier = Modifier) {
    val startTime = remember { System.nanoTime() }
    var nowMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(item.id) {
        while (true) {
            withFrameNanos { t -> nowMs = (t - startTime) / 1_000_000f }
        }
    }
    Canvas(modifier.size(56.dp).clip(RoundedCornerShape(10.dp))) {
        val horizonY = size.height * 0.42f
        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFCFE7F7), Color(0xFFEFF7FD)), startY = 0f, endY = horizonY),
            size = Size(size.width, horizonY)
        )
        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFB2D98A), Color(0xFF86BC52)), startY = horizonY, endY = size.height),
            topLeft = Offset(0f, horizonY), size = Size(size.width, size.height - horizonY)
        )
        val scene = DecorationScene(
            w = size.width, h = size.height,
            scale = (min(size.width, size.height) / 125f).coerceIn(0.3f, 1.4f),
            horizonY = horizonY, tMs = nowMs,
            plantAnchor = Offset(size.width / 2f, size.height * 0.5f),
            preview = true
        )
        val spot = PROP_SPOTS[item.id]
        when {
            spot != null -> drawProp(item.id, scene, PropSpot(0.5f, POT_DEPTH))
            item.id == "butterfly_deco" || item.id == "fireflies" -> drawAirborneDecorations(listOf(item.id), scene)
            else -> drawDecorationsBehindPot(listOf(item.id), scene)
        }
    }
}
