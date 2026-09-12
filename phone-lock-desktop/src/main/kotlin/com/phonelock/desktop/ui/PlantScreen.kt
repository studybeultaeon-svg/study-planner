package com.phonelock.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.applyPendingGrowthExp
import com.phonelock.desktop.data.getGrowthExpPending
import com.phonelock.desktop.data.getGrowthExpTotal
import com.phonelock.desktop.data.getPointsBalance
import com.phonelock.desktop.data.getRebirthCount
import com.phonelock.desktop.data.rebirth
import com.phonelock.desktop.monitor.GrowthSoundPlayer
import com.phonelock.desktop.ui.theme.Spacing
import com.phonelock.shared.GrowthSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * "식물" 탭(105차 신설, 108차 게임성 강화 개편, 109차 500레벨/등급 체계 개편) — 레벨/칭호는
 * `shared/GrowthSystem.kt` 하나로 계산한다. 108차부터 EXP는 적립 즉시 레벨에 반영되지 않고 "대기
 * EXP"([Repository.getGrowthExpPending])로 먼저 쌓이며, 사용자가 아래쪽 HUD의 "경험치 적용" 버튼을
 * 눌러야 그 순간 [Repository.applyPendingGrowthExp]가 레벨에 실제로 반영한다 — 그 반영 과정을 경험치바가
 * 차오르고(레벨업 시 넘치면 다음 레벨로 이어서) 레벨업 연출이 뜨는 애니메이션으로 보여줘서 사용자가
 * 레벨업 과정에 직접 참여하는 느낌을 준다(사용자 요청). 환생도 기존부터 버튼+확인 다이얼로그로 이미
 * 수동이었다(자동 환생 로직 없음). 보상함(포인트→보상 교환) UI는 108차에 완전히 삭제됨.
 */
@Composable
fun PlantScreen(repository: Repository, permPlant: Boolean = true, onOpenSettings: () -> Unit = {}) {
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
    var refreshTick by remember { mutableIntStateOf(0) }
    val balance = remember(refreshTick) { repository.getPointsBalance() }
    val growthExpPending = remember(refreshTick) { repository.getGrowthExpPending() }
    val rebirthCount = remember(refreshTick) { repository.getRebirthCount() }
    fun refresh() { refreshTick++ }

    var displayedExp by remember { mutableDoubleStateOf(repository.getGrowthExpTotal()) }
    var displayedLevel by remember { mutableIntStateOf(GrowthSystem.levelForExp(displayedExp)) }
    var isApplying by remember { mutableStateOf(false) }
    var levelUpFlash by remember { mutableStateOf<Int?>(null) }
    var showRebirthDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 대기 EXP 적립 등 외부 변화로 growthExpTotal이 바뀌었는데 지금 애니메이션 중이 아니면 그대로 동기화.
    LaunchedEffect(refreshTick) {
        if (!isApplying) {
            displayedExp = repository.getGrowthExpTotal()
            displayedLevel = GrowthSystem.levelForExp(displayedExp)
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

    Box(Modifier.fillMaxSize()) {
        GroundScene(stageIndex = stageIndex, stage = stage, rebirthCount = rebirthCount, modifier = Modifier.fillMaxSize())

        HomeSettingsButton(onOpenSettings, Modifier.align(Alignment.TopEnd).padding(Spacing.lg))

        // 레벨/경험치 HUD — 108차부터 화면 아래쪽에 도킹(기존엔 위쪽), 위쪽은 전부 비워 식물이 잘 보이게 함.
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Spacing.lg)
                .heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.md)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isMaxLevel) "Lv.$displayedLevel (이번 시즌 최고)" else "Lv.$displayedLevel",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                    )
                    if (rebirthCount > 0) {
                        Text("환생 ${rebirthCount}회 · EXP ×${"%.1f".format(multiplier)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stage.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(Spacing.xs))
                LinearProgressIndicator(
                    progress = { levelProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("보유 ${balance}P", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (canRebirth && !isApplying) {
                        TextButton(onClick = { showRebirthDialog = true }) { Text("🔁 환생 가능!") }
                    } else if (isMaxLevel) {
                        Text("🎉 최대 레벨 달성", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("환생까지 Lv.$nextRebirthLevel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (growthExpPending > 0.0) "대기 중 경험치 +${"%.1f".format(growthExpPending)}" else "적용할 경험치가 없습니다",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (growthExpPending > 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(enabled = growthExpPending > 0.0 && !isApplying, onClick = { applyPendingExp() }) {
                        Text(if (isApplying) "적용 중..." else "✨ 경험치 적용")
                    }
                }
                toastMessage?.let {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 레벨업 연출 — 화면 중앙, 짧게 튀어나왔다가 사라진다.
        AnimatedVisibility(
            visible = levelUpFlash != null,
            modifier = Modifier.align(Alignment.Center),
            enter = scaleIn(initialScale = 0.6f, animationSpec = tween(220)) + fadeIn(tween(150)),
            exit = scaleOut(targetScale = 1.15f, animationSpec = tween(300)) + fadeOut(tween(300))
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "레벨업!",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                levelUpFlash?.let { lvl ->
                    Text(
                        "Lv.$lvl 달성",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    if (showRebirthDialog) {
        AlertDialog(
            onDismissRequest = { showRebirthDialog = false },
            title = { Text("환생하시겠습니까?") },
            text = {
                Text("현재 레벨과 경험치가 초기화되고 씨앗부터 다시 시작합니다. 대신 EXP 획득 배율이 ×${"%.1f".format(GrowthSystem.expMultiplier(rebirthCount + 1))}로 영구히 올라갑니다.")
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
}

/** 환생 횟수에 따라 경험치 주입 애니메이션 한 스텝의 재생 시간(ms) — 환생 배율(`GrowthSystem.
 *  expMultiplier`)이 커질수록 애니메이션도 함께 빨라지게 한다(사용자 요청: "환생에 따라 경험치를
 *  입력하는 속도도 늘어나야"). 환생 0(배율 1배) 기준 16ms/스텝(총 10스텝 ≈ 160ms/구간)에서 시작해
 *  배율의 제곱근에 반비례해 줄어들고, 너무 빨라 손맛이 사라지지 않도록 4ms 밑으로는 안 내려간다. */
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
// 땅 배경 + 식물 일러스트(109차 전면 개편) — 등급(Stage.tier)마다 하늘/땅/나무 형태 자체가 달라진다.
// 0=정상(평범한 나무) 1=이상함(뒤틀린 나무) 2=초월급(신성한 화신) 3=종말급(재앙의 존재) 4=최강자급(세계수).
// 칭호(stage.title)와 illustrationId가 1:1로 짝지어져 있어 텍스트와 그림이 항상 일치한다.
// ══════════════════════════════════════════════════════

private data class GrowthAnim(
    val swayPx: Float,
    val pulse: Float,
    val rotate: Float,
    val shakeX: Float,
    val shakeY: Float,
    val flicker: Float
)

/** 홈 화면 우상단 설정 진입점 — 118차부터 설정은 탭이 아니라 이 버튼을 통해서만 들어간다. */
@Composable
private fun HomeSettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(44.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("⚙️", fontSize = 20.sp)
        }
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
private fun GroundScene(stageIndex: Int, stage: GrowthSystem.Stage, rebirthCount: Int, modifier: Modifier = Modifier) {
    val startTime = remember { System.nanoTime() }
    var nowMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { t -> nowMs = (t - startTime) / 1_000_000f }
        }
    }
    val anim = growthAnimForTier(stage.tier, nowMs)

    // tier2+ 장식·흔들림 효과가 이 컴포저블에 할당된 영역(왼쪽 NavigationRail 옆 콘텐츠 영역) 밖으로
    // 번져 나가 탭 바를 가리지 않도록 그리기 자체를 자기 경계 안으로 가둔다.
    Canvas(modifier.clipToBounds()) {
        val w = size.width
        val h = size.height
        val scale = (min(w, h) / 400f).coerceIn(0.7f, 3.5f)
        val tier = stage.tier
        val horizonY = h * 0.2f
        val margin = SHAKE_MARGIN * scale

        translate(anim.shakeX * scale, anim.shakeY * scale) {
            drawSky(tier, w, h, horizonY, scale, nowMs, margin)
            drawGroundLayer(tier, w, h, horizonY, scale, margin)

            if (tier == 3) drawCrackedGroundPatch(w, h, scale, 1 + (stageIndex - 17).coerceAtLeast(0), 0.35f + anim.flicker * 0.3f)
            if (tier == 4) drawCrackedGroundPatch(w, h, scale, 3, 0.4f + anim.flicker * 0.3f)
            if (tier >= 2) drawCosmicBackdrop(tier, w, h, scale, anim, margin)

            val potW = 74f * scale
            val potH = 46f * scale
            val potLeft = w / 2f - potW / 2f
            val potTop = h * 0.62f
            val potPath = Path().apply {
                moveTo(potLeft, potTop)
                lineTo(potLeft + potW, potTop)
                lineTo(potLeft + potW - 10f * scale, potTop + potH)
                lineTo(potLeft + 10f * scale, potTop + potH)
                close()
            }
            drawPath(potPath, color = Color(0xFFD08B5B))
            drawRect(color = Color(0xFFB5723F), topLeft = Offset(potLeft - 4f * scale, potTop - 6f * scale), size = Size(potW + 8f * scale, 8f * scale))

            val cx = w / 2f
            val isTree = stageIndex >= 8 // "든든한 나무"(Lv.95)부터 실제 가지 뻗은 나무
            val anchor: Offset = when {
                !isTree -> drawYoungPlant(cx, potTop, h * 0.3f, scale, stageIndex, anim.swayPx)
                tier == 0 -> drawBranchingTree(
                    cx, potTop, h * 0.42f, scale,
                    (1f + (stageIndex - 8) * 0.35f).coerceAtMost(3.2f), anim.swayPx,
                    Color(0xFF5FA043), Color(0xFF7A5A36), false
                )
                tier == 1 -> drawBranchingTree(
                    cx, potTop, h * 0.44f, scale,
                    3.2f + (stageIndex - 12) * 0.15f, anim.swayPx,
                    Color(0xFF7C8B6E), Color(0xFF6B5A48), true
                )
                tier == 2 -> drawRadiantTree(cx, potTop, h * 0.5f, scale, (stageIndex - 15) / 3f, anim, w)
                tier == 3 -> drawCorruptedTree(cx, potTop, h * 0.56f, scale, (stageIndex - 18) / 3f, anim, w)
                else -> drawWorldTree(cx, potTop, h * 0.68f, scale, (stageIndex - 21) / 2f, anim, w)
            }

            if (rebirthCount > 0) drawRebirthAura(cx, potTop - h * 0.1f, scale, rebirthCount)

            drawGrowthIllustration(stage.illustrationId, cx, potTop, anchor, w, h, scale, anim)
            if (tier == 3) drawEmberOverlay(w, h, scale, anim, nowMs)
            if (tier == 4) drawTranscendentOverlay(w, h, scale, anim, nowMs)
        }
    }
}

private fun DrawScope.drawSky(tier: Int, w: Float, h: Float, horizonY: Float, scale: Float, tMs: Float, margin: Float) {
    val skies = listOf(
        Color(0xFFBEE3F8) to Color(0xFFEAF6FF),
        Color(0xFF8FA08A) to Color(0xFFC7D3BE),
        Color(0xFF241344) to Color(0xFF3E2564),
        Color(0xFF3E1210) to Color(0xFF8A2E1C),
        Color(0xFF07040D) to Color(0xFF170C28)
    )
    val (top, bottom) = skies[tier]
    drawRect(
        brush = Brush.verticalGradient(listOf(top, bottom), startY = 0f, endY = horizonY),
        topLeft = Offset(-margin, -margin),
        size = Size(w + margin * 2, horizonY + margin * 2)
    )
    when (tier) {
        0 -> {
            drawCircle(color = Color(0xFFFFE17D), radius = 22f * scale, center = Offset(w * 0.72f, h * 0.08f))
            drawCloudPuff(Offset(w * 0.2f, h * 0.07f), scale)
            drawCloudPuff(Offset(w * 0.55f, h * 0.13f), scale)
        }
        1 -> drawCircle(color = Color(0xFFC8C8BE).copy(alpha = 0.55f), radius = 18f * scale, center = Offset(w * 0.82f, h * 0.09f))
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

private fun DrawScope.drawCloudPuff(center: Offset, scale: Float) {
    val color = Color.White.copy(alpha = 0.85f)
    drawCircle(color = color, radius = 14f * scale, center = center)
    drawCircle(color = color, radius = 10f * scale, center = center + Offset(16f * scale, 3f * scale))
    drawCircle(color = color, radius = 10f * scale, center = center + Offset(-16f * scale, 3f * scale))
}

private fun DrawScope.drawGroundLayer(tier: Int, w: Float, h: Float, horizonY: Float, scale: Float, margin: Float) {
    val grounds = listOf(
        Triple(Color(0xFF8BC34A), Color(0xFF7CB342), Color(0xFF6D4C2F)),
        Triple(Color(0xFF7C8B5C), Color(0xFF6B7A4E), Color(0xFF463A2A)),
        Triple(Color(0xFF4A3B6B), Color(0xFF3C2F58), Color(0xFF241C3A)),
        Triple(Color(0xFF6B3A2A), Color(0xFF5A2E1E), Color(0xFF301810)),
        Triple(Color(0xFF241C38), Color(0xFF1A1428), Color(0xFF0D0A18))
    )
    val (c1, c2, c3) = grounds[tier]
    drawRect(
        brush = Brush.verticalGradient(listOf(c1, c2, c3), startY = horizonY, endY = h),
        topLeft = Offset(-margin, horizonY),
        size = Size(w + margin * 2, h - horizonY + margin)
    )
    drawLine(
        color = if (tier == 0) Color(0xFF689F38) else Color.White.copy(alpha = 0.15f),
        start = Offset(0f, horizonY), end = Offset(w, horizonY), strokeWidth = 3f * scale
    )
    if (tier == 2) {
        drawRect(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFD778).copy(alpha = 0.25f), Color(0xFFFFD778).copy(alpha = 0f)),
                center = Offset(w / 2f, h * 0.62f), radius = (w * 0.4f).coerceAtLeast(1f)
            ),
            topLeft = Offset(0f, horizonY), size = Size(w, h - horizonY)
        )
    }
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

/** 초반(레벨 1~95 미만, "든든한 나무" 이전) 어린 식물 — 줄기 하나에 잎 몇 쌍, 고레벨 트리 렌더러들과
 *  달리 단순하고 친근한 느낌을 그대로 유지한다(사용자 요청: 초반은 단순해도 된다). */
private fun DrawScope.drawYoungPlant(baseX: Float, baseY: Float, maxHeight: Float, scale: Float, stageIndex: Int, swayPx: Float): Offset {
    val growthFrac = ((stageIndex + 1) / 9f).coerceAtMost(1f)
    val stemHeight = maxHeight * growthFrac
    val sway = swayPx * scale
    val topX = baseX + sway
    val topY = baseY - stemHeight
    if (stemHeight > 6f * scale) {
        drawLine(color = Color(0xFF4CAF50), start = Offset(baseX, baseY), end = Offset(topX, topY), strokeWidth = 6f * scale, cap = StrokeCap.Round)
        val leafPairs = stageIndex.coerceIn(0, 6)
        for (i in 1..leafPairs) {
            val t = i / (leafPairs + 1f)
            val ly = baseY - stemHeight * t
            drawOval(color = Color(0xFF66BB6A), topLeft = Offset(baseX - 20f * scale, ly - 5.5f * scale), size = Size(18f * scale, 11f * scale))
            drawOval(color = Color(0xFF66BB6A), topLeft = Offset(baseX + 2f * scale, ly - 5.5f * scale), size = Size(18f * scale, 11f * scale))
        }
        if (stageIndex >= 5) {
            val petalColor = if (stageIndex >= 7) Color(0xFFFFA726) else Color(0xFFE91E63)
            for (angleDeg in 0 until 360 step 60) {
                val rad = Math.toRadians(angleDeg.toDouble())
                drawCircle(
                    color = petalColor, radius = 9f * scale,
                    center = Offset(topX + (14f * scale * cos(rad)).toFloat(), topY + (14f * scale * sin(rad)).toFloat())
                )
            }
            drawCircle(color = Color(0xFFFFF176), radius = 7f * scale, center = Offset(topX, topY))
        }
    } else {
        drawCircle(color = Color(0xFF6D4C41), radius = 6f * scale, center = Offset(baseX, baseY - 4f * scale))
        return Offset(baseX, baseY - 4f * scale)
    }
    return Offset(topX, topY)
}

/** 정상/이상함(tier 0·1) 공용 — 굵은 줄기+여러 갈래 가지+잎 뭉치를 가진 가지 뻗은 나무. twisted=true
 *  (이상함 등급)면 가지 하나가 부자연스럽게 꺾여서 위화감을 준다. */
private fun DrawScope.drawBranchingTree(
    baseX: Float, baseY: Float, height: Float, scale: Float, canopyScale: Float, swayPx: Float,
    leafColor: Color, trunkColor: Color, twisted: Boolean
): Offset {
    val sway = swayPx * scale
    val trunkH = height * 0.55f
    val trunkTopY = baseY - trunkH
    val trunkTopX = baseX + sway * 0.3f
    val trunkW = (9f + canopyScale * 3f) * scale

    drawLine(
        brush = Brush.verticalGradient(listOf(Color(0xFF5C4326), trunkColor), startY = baseY, endY = trunkTopY),
        start = Offset(baseX, baseY), end = Offset(trunkTopX, trunkTopY), strokeWidth = trunkW, cap = StrokeCap.Round
    )

    val branchCount = (3 + canopyScale.toInt()).coerceAtMost(7)
    val branchLen = height * (0.32f + canopyScale * 0.05f)
    val leafR = 13f * scale * canopyScale.coerceAtMost(2.6f)
    var topmostY = trunkTopY

    for (i in 0 until branchCount) {
        val frac = if (branchCount == 1) 0.5f else i / (branchCount - 1).toFloat()
        var angleDeg = -85f + frac * 170f
        if (twisted && i == 1) angleDeg += 55f
        val rad = Math.toRadians(angleDeg.toDouble())
        val forkY = trunkTopY + trunkH * 0.15f * (i % 2)
        val endX = trunkTopX + (sin(rad) * branchLen).toFloat() + sway
        val endY = forkY - (cos(rad) * branchLen * 0.82).toFloat()
        drawLine(color = Color(0xFF6B4E30), start = Offset(trunkTopX, forkY), end = Offset(endX, endY), strokeWidth = trunkW * 0.32f, cap = StrokeCap.Round)

        listOf(0f to 0f, leafR * 0.55f to -leafR * 0.25f, -leafR * 0.55f to -leafR * 0.25f, 0f to leafR * 0.4f).forEach { (ox, oy) ->
            drawCircle(color = leafColor, radius = leafR * 0.62f, center = Offset(endX + ox, endY + oy))
        }
        topmostY = min(topmostY, endY - leafR * 0.6f)
    }

    drawCircle(color = leafColor, radius = leafR * 0.85f, center = Offset(trunkTopX + sway, trunkTopY - leafR * 0.3f))
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
        quadraticBezierTo(trunkTopX + beingW, trunkTopY - beingH * 0.5f, trunkTopX, trunkTopY)
        quadraticBezierTo(trunkTopX - beingW, trunkTopY - beingH * 0.5f, trunkTopX, trunkTopY - beingH)
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
        quadraticBezierTo(baseX - 10f * scale, baseY - trunkH * 0.5f, trunkTopX, trunkTopY)
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
            quadraticBezierTo(midX, midY, topX, crownY)
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
        quadraticBezierTo(baseX + beingW, crownY - beingH * 0.5f, baseX + beingW * 0.5f, crownY)
        quadraticBezierTo(baseX, crownY - beingH * 0.1f, baseX - beingW * 0.5f, crownY)
        quadraticBezierTo(baseX - beingW, crownY - beingH * 0.5f, baseX, crownY - beingH)
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
                    quadraticBezierTo(baseX + d * 16f * scale, baseY + 10f * scale, baseX + d * 30f * scale, baseY + 16f * scale)
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
