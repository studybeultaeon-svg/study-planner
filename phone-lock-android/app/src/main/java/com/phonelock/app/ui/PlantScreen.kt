package com.phonelock.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.applyPendingGrowthExp
import com.phonelock.app.data.getGrowthExpPending
import com.phonelock.app.data.getGrowthExpTotal
import com.phonelock.app.data.getRebirthCount
import com.phonelock.app.data.observePointsBalance
import com.phonelock.app.data.rebirth
import com.phonelock.app.service.GrowthSoundPlayer
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.shared.GrowthSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * "식물" 탭(105차 신설, 108차 게임성 강화 개편, 데스크탑판과 대칭) — 레벨/칭호는
 * `shared/GrowthSystem.kt` 하나로 계산한다. 108차부터 EXP는 적립 즉시 레벨에 반영되지 않고
 * "대기 EXP"([PhoneLockRepository.getGrowthExpPending])로 먼저 쌓이며, 사용자가 아래쪽 HUD의
 * "경험치 적용" 버튼을 눌러야 그 순간 [PhoneLockRepository.applyPendingGrowthExp]가 레벨에 실제로
 * 반영한다 — 그 반영 과정을 경험치바가 차오르고(레벨업 시 넘치면 다음 레벨로 이어서) 레벨업 연출이 뜨는
 * 애니메이션으로 보여줘서 사용자가 레벨업 과정에 직접 참여하는 느낌을 준다(사용자 요청). 환생도 기존부터
 * 버튼+확인 다이얼로그로 이미 수동이었다(자동 환생 로직 없음, 이번에 구조 변경 없음). 보상함(포인트→보상
 * 교환) UI는 108차에 완전히 삭제됨 — 관련 로직(Reward 데이터/Room 테이블/함수)도 전부 제거했다
 * (사용자 확정, [[DECISIONS.md]] 108차 참고).
 */
@Composable
fun PlantScreen(repository: PhoneLockRepository) {
    val scope = rememberCoroutineScope()
    val balance by repository.observePointsBalance().collectAsState(initial = 0)
    // growthExpPending/rebirthCount는 Room Flow가 아니라 AppPreferences 스칼라값이라 refreshTick으로 재조회.
    var refreshTick by remember { mutableIntStateOf(0) }
    val growthExpPending = remember(refreshTick) { repository.getGrowthExpPending() }
    val rebirthCount = remember(refreshTick) { repository.getRebirthCount() }
    fun refresh() { refreshTick++ }

    var displayedExp by remember { mutableDoubleStateOf(repository.getGrowthExpTotal()) }
    var displayedLevel by remember { mutableIntStateOf(GrowthSystem.levelForExp(displayedExp)) }
    var isApplying by remember { mutableStateOf(false) }
    var levelUpFlash by remember { mutableStateOf<Int?>(null) }
    var showRebirthDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshTick) {
        if (!isApplying) {
            displayedExp = repository.getGrowthExpTotal()
            displayedLevel = GrowthSystem.levelForExp(displayedExp)
        }
    }

    val stage = GrowthSystem.stageForLevel(displayedLevel)
    val stageIndex = GrowthSystem.STAGES.indexOf(stage)
    val levelProgress = GrowthSystem.progressToNextLevel(displayedExp)
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
        GroundScene(stageIndex = stageIndex, stage = stage, modifier = Modifier.fillMaxSize())

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
                    Text("Lv.$displayedLevel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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

/** 대기 EXP를 레벨에 적용하는 과정을 단계별로 재생 — 레벨이 여러 번 오르면 "경험치 주입 → 상승 → 레벨업 →
 *  다음 레벨 경험치 주입 → 다시 상승"을 레벨 경계마다 반복한 뒤 마지막 구간을 마저 채운다. */
private suspend fun animateExpApplication(
    result: GrowthSystem.ApplyResult,
    onProgress: (exp: Double, level: Int) -> Unit,
    onLevelUp: suspend (level: Int) -> Unit
) {
    var level = result.levelBefore
    var currentExp = result.expBefore
    while (level < result.levelAfter) {
        val target = GrowthSystem.cumulativeExpForLevel(level + 1)
        animateExpSegment(currentExp, target) { v -> onProgress(v, level) }
        level += 1
        currentExp = target
        onProgress(currentExp, level)
        onLevelUp(level)
    }
    animateExpSegment(currentExp, result.expAfter) { v -> onProgress(v, level) }
}

private suspend fun animateExpSegment(from: Double, to: Double, onProgress: (Double) -> Unit) {
    if (to <= from) {
        onProgress(to)
        return
    }
    val steps = 22
    for (i in 1..steps) {
        val frac = i / steps.toFloat()
        val eased = 1f - (1f - frac) * (1f - frac)
        onProgress(from + (to - from) * eased)
        delay(18L)
    }
    onProgress(to)
}

// ══════════════════════════════════════════════════════
// 땅 배경 + 식물 일러스트 — 칭호(stage.title)와 항상 1:1로 짝지어진 전용 그림(데스크탑판과 대칭).
// ══════════════════════════════════════════════════════

@Composable
private fun GroundScene(stageIndex: Int, stage: GrowthSystem.Stage, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "plantSway")
    val sway by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(animation = tween(2400, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "sway"
    )
    val totalStages = (GrowthSystem.STAGES.size - 1).coerceAtLeast(1)
    val targetGrowth = stageIndex.toFloat() / totalStages
    val growthFrac by animateFloatAsState(targetValue = targetGrowth, animationSpec = tween(600), label = "growth")

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val scale = (min(w, h) / 400f).coerceIn(0.7f, 3.5f)
        val horizonY = h * 0.2f

        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFBEE3F8), Color(0xFFEAF6FF)), endY = horizonY),
            size = Size(w, horizonY)
        )
        drawCircle(color = Color(0xFFFFE17D), radius = 22f * scale, center = Offset(w * 0.85f, h * 0.08f))
        drawCloudPuff(Offset(w * 0.2f, h * 0.07f), scale)
        drawCloudPuff(Offset(w * 0.55f, h * 0.13f), scale)

        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF8BC34A), Color(0xFF7CB342), Color(0xFF6D4C2F)),
                startY = horizonY,
                endY = h
            ),
            topLeft = Offset(0f, horizonY),
            size = Size(w, h - horizonY)
        )
        drawLine(color = Color(0xFF689F38), start = Offset(0f, horizonY), end = Offset(w, horizonY), strokeWidth = 3f * scale)

        if (stage.illustrationId == "god_fearing_weed") {
            drawGiantShadowAndSkyCrack(w, h, scale)
        }

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

        val effectiveGrowth = if (stage.illustrationId == "god_fearing_weed") 0.12f else growthFrac
        val maxStemHeight = h * 0.34f
        val stemHeight = maxStemHeight * effectiveGrowth
        val stemBaseX = w / 2f
        val stemTopX = stemBaseX + sway
        val stemTopY = potTop - stemHeight

        if (stemHeight > 6f * scale) {
            drawLine(
                color = Color(0xFF4CAF50),
                start = Offset(stemBaseX, potTop),
                end = Offset(stemTopX, stemTopY),
                strokeWidth = 6f * scale,
                cap = StrokeCap.Round
            )
            val leafPairs = stageIndex.coerceIn(0, 6)
            for (i in 1..leafPairs) {
                val t = i / (leafPairs + 1f)
                val lx = stemBaseX + sway * t
                val ly = potTop - stemHeight * t
                drawOval(color = Color(0xFF66BB6A), topLeft = Offset(lx - 20f * scale, ly - 6f * scale), size = Size(18f * scale, 11f * scale))
                drawOval(color = Color(0xFF66BB6A), topLeft = Offset(lx + 2f * scale, ly - 6f * scale), size = Size(18f * scale, 11f * scale))
            }
            if (stageIndex >= 5) {
                val petalColor = if (stageIndex >= 9) Color(0xFFFFA726) else Color(0xFFE91E63)
                for (angleDeg in 0 until 360 step 60) {
                    val rad = Math.toRadians(angleDeg.toDouble())
                    drawCircle(
                        color = petalColor,
                        radius = 9f * scale,
                        center = Offset(stemTopX + (14f * scale * cos(rad)).toFloat(), stemTopY + (14f * scale * sin(rad)).toFloat())
                    )
                }
                drawCircle(color = Color(0xFFFFF176), radius = 7f * scale, center = Offset(stemTopX, stemTopY))
            }
        } else {
            drawCircle(color = Color(0xFF6D4C41), radius = 6f * scale, center = Offset(w / 2f, potTop - 4f * scale))
        }

        drawGrassTuft(Offset(w * 0.25f, h * 0.78f), scale)
        drawGrassTuft(Offset(w * 0.72f, h * 0.85f), scale)
        drawCircle(color = Color(0xFF9E9E9E), radius = 8f * scale, center = Offset(w * 0.15f, h * 0.9f))
        drawCircle(color = Color(0xFF9E9E9E), radius = 5f * scale, center = Offset(w * 0.82f, h * 0.93f))

        when (stage.illustrationId) {
            "nyanyang_bean" -> {
                drawCatEars(stemTopX, stemTopY, scale)
                drawExclamationMarks(stemTopX, stemTopY - 40f * scale, scale)
            }
            "tralalero_sprout" -> drawSharkFinAndSneakers(stemBaseX, potTop, stemTopX, stemTopY, scale)
            "john_pork_tree" -> {
                drawPigFace(stemTopX, stemTopY, scale)
                drawAuraRings(stemTopX, stemTopY, scale, count = 1, color = Color(0xFFFF8A00))
            }
            "bombardino_tree" -> {
                drawCrocJawAndWings(stemTopX, stemTopY, scale)
                drawAuraRings(stemTopX, stemTopY, scale, count = 1, color = Color(0xFF4DB6FF))
            }
            "cappuccino_fruit" -> {
                drawHoodAndCup(stemTopX, stemTopY, scale)
                drawAuraRings(stemTopX, stemTopY, scale, count = 2, color = Color(0xFFB39DDB))
                drawLightningBolts(stemTopX, stemTopY, scale, count = 2)
            }
            "chimpanzini_jonggeon" -> {
                drawMonkeyAndBanana(stemTopX, stemTopY, scale)
                drawCrown(stemTopX, stemTopY - 30f * scale, scale)
                drawAuraRings(stemTopX, stemTopY, scale, count = 3, color = Color(0xFFFFD54F))
                drawLightningBolts(stemTopX, stemTopY, scale, count = 3)
                drawCrackedGround(w, h, scale, intensity = 1)
            }
            "jinseok_tralalero" -> {
                drawSharkFinAndSneakers(stemBaseX, potTop, stemTopX, stemTopY, scale)
                drawGodRays(stemTopX, stemTopY, scale, intensity = 1)
                drawSpeedLines(stemTopX, stemTopY, scale)
                drawJinseokCameo(potLeft + potW + 24f * scale, potTop, scale)
            }
            "ultra_bombardino" -> {
                drawCrocJawAndWings(stemTopX, stemTopY, scale)
                drawAuraRings(stemTopX, stemTopY, scale, count = 4, color = Color(0xFFFF5252))
                drawLightningBolts(stemTopX, stemTopY, scale, count = 5)
                drawShockwaveRing(stemTopX, stemTopY, scale)
                drawCrackedGround(w, h, scale, intensity = 2)
                drawFloatingDebris(w, h, scale)
            }
            "god_fearing_weed" -> {
                drawHoodAndCup(stemTopX, stemTopY, scale, miniature = true)
            }
            "final_boss_tree" -> {
                drawCrocJawAndWings(stemTopX, stemTopY, scale)
                drawMonkeyAndBanana(stemTopX, stemTopY, scale)
                drawCrown(stemTopX, stemTopY - 30f * scale, scale)
                drawSharkFinAndSneakers(stemBaseX, potTop, stemTopX, stemTopY, scale)
                drawAuraRings(stemTopX, stemTopY, scale, count = 5, color = Color(0xFFFFD700))
                drawLightningBolts(stemTopX, stemTopY, scale, count = 6)
                drawShockwaveRing(stemTopX, stemTopY, scale)
                drawCrackedGround(w, h, scale, intensity = 3)
                drawFloatingDebris(w, h, scale)
                drawGodRays(stemTopX, stemTopY, scale, intensity = 2)
                drawJinseokCameo(potLeft + potW + 24f * scale, potTop, scale)
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

private fun DrawScope.drawGrassTuft(base: Offset, scale: Float) {
    val color = Color(0xFF558B2F)
    for (angleDeg in listOf(-20, 0, 20)) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val tip = base + Offset((14f * scale * sin(rad)).toFloat(), (-18f * scale * cos(rad)).toFloat())
        drawLine(color = color, start = base, end = tip, strokeWidth = 3f * scale, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawAuraRings(cx: Float, cy: Float, scale: Float, count: Int, color: Color) {
    for (i in 1..count) {
        drawCircle(
            color = color.copy(alpha = 0.35f),
            radius = (20f + i * 14f) * scale,
            center = Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f * scale)
        )
    }
}

private fun DrawScope.drawLightningBolts(cx: Float, cy: Float, scale: Float, count: Int) {
    val color = Color(0xFFFFEB3B)
    for (i in 0 until count) {
        val angle = Math.toRadians((360.0 / count * i) - 90.0)
        val dirX = cos(angle).toFloat(); val dirY = sin(angle).toFloat()
        val r1 = 26f * scale; val r2 = 46f * scale
        val midX = cx + dirX * r1 + (6f * scale * (if (i % 2 == 0) 1 else -1))
        val midY = cy + dirY * r1
        val endX = cx + dirX * r2; val endY = cy + dirY * r2
        val path = Path().apply {
            moveTo(cx + dirX * 10f * scale, cy + dirY * 10f * scale)
            lineTo(midX, midY)
            lineTo(endX, endY)
        }
        drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f * scale))
    }
}

private fun DrawScope.drawShockwaveRing(cx: Float, cy: Float, scale: Float) {
    drawCircle(
        color = Color.White.copy(alpha = 0.6f),
        radius = 70f * scale,
        center = Offset(cx, cy),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f * scale)
    )
}

private fun DrawScope.drawCrackedGround(w: Float, h: Float, scale: Float, intensity: Int) {
    val color = Color(0xFF3E2723)
    val crackCount = 2 + intensity
    for (i in 0 until crackCount) {
        val startX = w * (0.2f + 0.6f * i / crackCount)
        val startY = h * 0.95f
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX + 10f * scale, startY - 14f * scale)
            lineTo(startX - 6f * scale, startY - 26f * scale)
        }
        drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * scale))
    }
}

private fun DrawScope.drawFloatingDebris(w: Float, h: Float, scale: Float) {
    val color = Color(0xFF8D6E63)
    listOf(0.3f to 0.5f, 0.68f to 0.42f, 0.78f to 0.6f).forEach { (fx, fy) ->
        drawCircle(color = color, radius = 5f * scale, center = Offset(w * fx, h * fy))
    }
}

private fun DrawScope.drawGodRays(cx: Float, cy: Float, scale: Float, intensity: Int) {
    val color = Color(0xFFFFF9C4).copy(alpha = 0.5f)
    val rayCount = 6 + intensity * 2
    for (i in 0 until rayCount) {
        val angle = Math.toRadians(360.0 / rayCount * i)
        val dirX = cos(angle).toFloat(); val dirY = sin(angle).toFloat()
        drawLine(
            color = color,
            start = Offset(cx, cy),
            end = Offset(cx + dirX * 90f * scale, cy + dirY * 90f * scale),
            strokeWidth = 3f * scale
        )
    }
}

private fun DrawScope.drawSpeedLines(cx: Float, cy: Float, scale: Float) {
    val color = Color.White.copy(alpha = 0.7f)
    for (i in -2..2) {
        drawLine(
            color = color,
            start = Offset(cx - 60f * scale, cy + i * 10f * scale),
            end = Offset(cx - 20f * scale, cy + i * 10f * scale),
            strokeWidth = 2f * scale
        )
    }
}

private fun DrawScope.drawCrown(cx: Float, cy: Float, scale: Float) {
    val color = Color(0xFFFFD700)
    val path = Path().apply {
        moveTo(cx - 14f * scale, cy + 8f * scale)
        lineTo(cx - 14f * scale, cy - 6f * scale)
        lineTo(cx - 7f * scale, cy + 2f * scale)
        lineTo(cx, cy - 10f * scale)
        lineTo(cx + 7f * scale, cy + 2f * scale)
        lineTo(cx + 14f * scale, cy - 6f * scale)
        lineTo(cx + 14f * scale, cy + 8f * scale)
        close()
    }
    drawPath(path, color = color)
}

private fun DrawScope.drawCatEars(cx: Float, cy: Float, scale: Float) {
    val color = Color(0xFFF48FB1)
    for (dir in listOf(-1f, 1f)) {
        val path = Path().apply {
            moveTo(cx + dir * 6f * scale, cy - 4f * scale)
            lineTo(cx + dir * 16f * scale, cy - 24f * scale)
            lineTo(cx + dir * 2f * scale, cy - 10f * scale)
            close()
        }
        drawPath(path, color = color)
    }
}

private fun DrawScope.drawExclamationMarks(cx: Float, cy: Float, scale: Float) {
    val color = Color(0xFFE53935)
    for (i in -1..1) {
        val x = cx + i * 14f * scale
        drawLine(color = color, start = Offset(x, cy), end = Offset(x, cy + 10f * scale), strokeWidth = 3f * scale, cap = StrokeCap.Round)
        drawCircle(color = color, radius = 1.8f * scale, center = Offset(x, cy + 15f * scale))
    }
}

private fun DrawScope.drawSharkFinAndSneakers(stemBaseX: Float, potTop: Float, stemTopX: Float, stemTopY: Float, scale: Float) {
    val finColor = Color(0xFF607D8B)
    val fin = Path().apply {
        moveTo(stemTopX, stemTopY)
        lineTo(stemTopX + 16f * scale, stemTopY + 6f * scale)
        lineTo(stemTopX + 2f * scale, stemTopY + 16f * scale)
        close()
    }
    drawPath(fin, color = finColor)
    listOf(-1f, 1f).forEach { dir ->
        drawOval(
            color = Color(0xFFECECEC),
            topLeft = Offset(stemBaseX + dir * 26f * scale - 10f * scale, potTop + 2f * scale),
            size = Size(20f * scale, 9f * scale)
        )
        drawLine(
            color = Color(0xFFE53935),
            start = Offset(stemBaseX + dir * 26f * scale - 8f * scale, potTop + 6f * scale),
            end = Offset(stemBaseX + dir * 26f * scale + 6f * scale, potTop + 4f * scale),
            strokeWidth = 1.5f * scale
        )
    }
}

private fun DrawScope.drawPigFace(cx: Float, cy: Float, scale: Float) {
    drawCircle(color = Color(0xFFFFC1CC), radius = 13f * scale, center = Offset(cx, cy - 8f * scale))
    drawOval(color = Color(0xFFFF8FA3), topLeft = Offset(cx - 7f * scale, cy - 9f * scale), size = Size(14f * scale, 10f * scale))
    drawCircle(color = Color(0xFF6D4C41), radius = 1.6f * scale, center = Offset(cx - 3f * scale, cy - 6f * scale))
    drawCircle(color = Color(0xFF6D4C41), radius = 1.6f * scale, center = Offset(cx + 3f * scale, cy - 6f * scale))
}

private fun DrawScope.drawCrocJawAndWings(cx: Float, cy: Float, scale: Float) {
    val jawColor = Color(0xFF558B2F)
    val jaw = Path().apply {
        moveTo(cx - 18f * scale, cy)
        lineTo(cx + 18f * scale, cy - 2f * scale)
        lineTo(cx + 14f * scale, cy + 6f * scale)
        lineTo(cx - 14f * scale, cy + 8f * scale)
        close()
    }
    drawPath(jaw, color = jawColor)
    for (i in -2..2) {
        val tx = cx + i * 6f * scale
        drawPath(
            Path().apply {
                moveTo(tx - 2f * scale, cy + 3f * scale)
                lineTo(tx, cy + 9f * scale)
                lineTo(tx + 2f * scale, cy + 3f * scale)
            },
            color = Color.White
        )
    }
    val wingColor = Color(0xFF4E6B3A)
    listOf(-1f, 1f).forEach { dir ->
        drawPath(
            Path().apply {
                moveTo(cx, cy - 4f * scale)
                lineTo(cx + dir * 30f * scale, cy - 14f * scale)
                lineTo(cx + dir * 24f * scale, cy + 2f * scale)
                close()
            },
            color = wingColor
        )
    }
}

private fun DrawScope.drawHoodAndCup(cx: Float, cy: Float, scale: Float, miniature: Boolean = false) {
    val m = if (miniature) 0.6f else 1f
    val cupColor = Color(0xFF6D4C41)
    val cupPath = Path().apply {
        moveTo(cx - 10f * scale * m, cy + 4f * scale * m)
        lineTo(cx + 10f * scale * m, cy + 4f * scale * m)
        lineTo(cx + 7f * scale * m, cy + 16f * scale * m)
        lineTo(cx - 7f * scale * m, cy + 16f * scale * m)
        close()
    }
    drawPath(cupPath, color = cupColor)
    drawLine(color = Color.White.copy(alpha = 0.6f), start = Offset(cx - 4f * scale * m, cy - 4f * scale * m), end = Offset(cx - 2f * scale * m, cy - 12f * scale * m), strokeWidth = 1.5f * scale)
    val hoodColor = Color(0xFF263238).copy(alpha = 0.85f)
    drawPath(
        Path().apply {
            moveTo(cx - 16f * scale * m, cy - 10f * scale * m)
            lineTo(cx + 16f * scale * m, cy - 10f * scale * m)
            lineTo(cx, cy - 34f * scale * m)
            close()
        },
        color = hoodColor
    )
    drawCircle(color = Color(0xFFFFEE58), radius = 1.6f * scale * m, center = Offset(cx - 5f * scale * m, cy - 16f * scale * m))
    drawCircle(color = Color(0xFFFFEE58), radius = 1.6f * scale * m, center = Offset(cx + 5f * scale * m, cy - 16f * scale * m))
}

private fun DrawScope.drawMonkeyAndBanana(cx: Float, cy: Float, scale: Float) {
    val furColor = Color(0xFF6D4C41)
    drawCircle(color = furColor, radius = 14f * scale, center = Offset(cx, cy - 8f * scale))
    drawCircle(color = furColor, radius = 6f * scale, center = Offset(cx - 13f * scale, cy - 14f * scale))
    drawCircle(color = furColor, radius = 6f * scale, center = Offset(cx + 13f * scale, cy - 14f * scale))
    drawCircle(color = Color(0xFFFFF3E0), radius = 8f * scale, center = Offset(cx, cy - 6f * scale))
    val bananaColor = Color(0xFFFFD54F)
    drawArc(
        color = bananaColor,
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(cx + 6f * scale, cy - 10f * scale),
        size = Size(26f * scale, 26f * scale),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f * scale, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawJinseokCameo(x: Float, baseY: Float, scale: Float) {
    val color = Color(0xFF9E9E9E)
    drawCircle(color = color, radius = 9f * scale, center = Offset(x, baseY - 26f * scale))
    drawRect(color = color, topLeft = Offset(x - 8f * scale, baseY - 18f * scale), size = Size(16f * scale, 22f * scale))
    drawLine(color = color, start = Offset(x - 8f * scale, baseY - 10f * scale), end = Offset(x + 8f * scale, baseY - 14f * scale), strokeWidth = 3f * scale, cap = StrokeCap.Round)
}

private fun DrawScope.drawGiantShadowAndSkyCrack(w: Float, h: Float, scale: Float) {
    val shadowColor = Color(0xFF1A1A2E).copy(alpha = 0.55f)
    drawPath(
        Path().apply {
            moveTo(w * 0.1f, 0f)
            lineTo(w * 0.9f, 0f)
            lineTo(w * 0.65f, h * 0.5f)
            lineTo(w * 0.35f, h * 0.5f)
            close()
        },
        color = shadowColor
    )
    val crackColor = Color.White.copy(alpha = 0.8f)
    drawLine(color = crackColor, start = Offset(w * 0.3f, 0f), end = Offset(w * 0.48f, h * 0.32f), strokeWidth = 2f * scale)
    drawLine(color = crackColor, start = Offset(w * 0.48f, h * 0.32f), end = Offset(w * 0.4f, h * 0.4f), strokeWidth = 2f * scale)
    drawLine(color = crackColor, start = Offset(w * 0.7f, 0f), end = Offset(w * 0.55f, h * 0.28f), strokeWidth = 2f * scale)
}
