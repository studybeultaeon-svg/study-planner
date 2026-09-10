package com.phonelock.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.addReward
import com.phonelock.desktop.data.deleteReward
import com.phonelock.desktop.data.getEarnedPointsTotal
import com.phonelock.desktop.data.getPointsBalance
import com.phonelock.desktop.data.getRewards
import com.phonelock.desktop.data.getTotalStudyMinutes
import com.phonelock.desktop.data.redeemReward
import com.phonelock.desktop.ui.theme.Spacing
import com.phonelock.shared.CharacterGrowth
import com.phonelock.shared.StudyLevel
import kotlin.math.cos
import kotlin.math.sin

/**
 * "식물" 탭(104차 후속, 사용자 요청으로 "소셜"에서 분리 신설) — 레벨/경험치와 식물 키우기를 전용
 * 화면으로 독립시킴. "소셜"에 있던 SocialPointsSection(102~104차, 포인트/레벨/캐릭터/보상)을
 * 그대로 옮겨왔고, 캐릭터 단계 표시를 이모지 한 글자 대신 Canvas로 그린 화분+하늘+땅 장면
 * (PlantScene)으로 바꿨다 — StudyTimerScreen의 TimerIllustration과 같은 패턴(순수 Canvas 드로잉,
 * :shared 로직은 그대로 재사용). 보상 교환은 여전히 "보유 포인트"(balance)를 소비하고, 식물을
 * 키우는 축은 "경험치"(화면 표기만 바뀜, 데이터는 기존 earnedTotal과 동일)로 표기를 바꿨다.
 */
@Composable
fun PlantScreen(repository: Repository) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val balance = remember(refreshTick) { repository.getPointsBalance() }
    val earnedTotal = remember(refreshTick) { repository.getEarnedPointsTotal() }
    val totalStudyMinutes = remember(refreshTick) { repository.getTotalStudyMinutes() }
    val rewards = remember(refreshTick) { repository.getRewards() }
    var showAddDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    fun refresh() { refreshTick++ }

    Column(Modifier.fillMaxSize().padding(Spacing.lg).verticalScroll(rememberScrollState())) {
        Text("🌱 내 식물", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Spacing.sm))

        val studyLevel = StudyLevel.levelFor(totalStudyMinutes)
        val studyLevelProgress = StudyLevel.progressToNext(totalStudyMinutes)
        val studyLevelMinutesLeft = StudyLevel.minutesToNextLevel(totalStudyMinutes)
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.sm)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Lv.$studyLevel ${StudyLevel.tierLabel(studyLevel)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("누적 공부 ${totalStudyMinutes / 60}시간 ${totalStudyMinutes % 60}분", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(Spacing.xs))
                LinearProgressIndicator(
                    progress = { studyLevelProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(Spacing.xs))
                Text("다음 레벨까지 공부 ${studyLevelMinutesLeft}분 남음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Spacing.md))

        val stage = CharacterGrowth.stageFor(earnedTotal)
        val progress = CharacterGrowth.progressToNext(earnedTotal)
        val toNext = CharacterGrowth.pointsToNextStage(earnedTotal)
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
                PlantScene(stage = stage, progress = progress)
                Spacer(Modifier.height(Spacing.sm))
                Text("${stage.label} (${stage.index + 1}/${CharacterGrowth.STAGES.size}단계)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Spacing.sm))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    if (toNext != null) "다음 단계까지 경험치 ${toNext} 남음" else "최종 단계 도달!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.xs))
                Text("누적 경험치 ${earnedTotal} EXP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "경험치 획득: 공부 10분당 1EXP · 루틴 완료 5EXP · 일정 완료 5EXP · 오늘 루틴 전부 완료 시 +10EXP",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xs))
        Text("보유 ${balance}P · 아래 보상을 포인트로 교환할 수 있어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.lg))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("오늘의 보상", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { showAddDialog = true }) { Text("+ 보상 추가") }
        }
        Spacer(Modifier.height(Spacing.xs))

        toastMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.xs))
        }

        if (rewards.isEmpty()) {
            Text(
                "등록된 보상이 없습니다\n\"+ 보상 추가\"로 원하는 보상을 만들어보세요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            rewards.forEach { reward ->
                Surface(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
                ) {
                    Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(reward.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text("${reward.cost}P", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            enabled = balance >= reward.cost,
                            onClick = {
                                val ok = repository.redeemReward(reward.id)
                                toastMessage = if (ok) "\"${reward.name}\" 언락했습니다! 🎉" else "포인트가 부족합니다"
                                refresh()
                            }
                        ) { Text("언락") }
                        Spacer(Modifier.width(Spacing.xs))
                        TextButton(onClick = { repository.deleteReward(reward.id); refresh() }) { Text("삭제") }
                    }
                }
            }
        }

        if (showAddDialog) {
            var name by remember { mutableStateOf("") }
            var costText by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("보상 추가") },
                text = {
                    Column {
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("보상 이름") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedTextField(
                            value = costText,
                            onValueChange = { costText = it.filter { c -> c.isDigit() } },
                            label = { Text("필요 포인트") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank() && (costText.toIntOrNull() ?: 0) > 0,
                        onClick = {
                            val cost = costText.toIntOrNull() ?: 0
                            repository.addReward(name.trim(), cost)
                            showAddDialog = false
                            refresh()
                        }
                    ) { Text("추가") }
                },
                dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("취소") } }
            )
        }
    }
}

/**
 * 식물 장면 — 하늘/땅 배경 위에 화분+줄기+잎+꽃을 Canvas로 직접 그린다(이모지 한 글자 대신).
 * 단계(stage.index)로 줄기 높이·잎 개수·꽃 유무를, 단계 안 진행률(progress)로 다음 단계까지
 * 얼마나 자랐는지를 매끄럽게 보여준다. 순수 표시용이라 :shared의 성장 판정 로직은 건드리지 않는다.
 */
@Composable
private fun PlantScene(stage: CharacterGrowth.Stage, progress: Float, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "plantSway")
    val sway by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(animation = tween(2400, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "sway"
    )
    val totalStages = (CharacterGrowth.STAGES.size - 1).coerceAtLeast(1)
    val targetGrowth = (stage.index + progress) / totalStages
    val growthFrac by animateFloatAsState(targetValue = targetGrowth, animationSpec = tween(600), label = "growth")

    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        val w = size.width
        val h = size.height
        val horizonY = h * 0.72f

        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFBEE3F8), Color(0xFFEAF6FF)), endY = horizonY),
            size = Size(w, horizonY)
        )
        drawCircle(color = Color(0xFFFFE17D), radius = 20f, center = Offset(w * 0.82f, h * 0.16f))
        drawCloudPuff(Offset(w * 0.18f, h * 0.14f))
        drawCloudPuff(Offset(w * 0.52f, h * 0.26f))
        drawRect(
            color = Color(0xFF8BC34A).copy(alpha = 0.35f),
            topLeft = Offset(0f, horizonY),
            size = Size(w, h - horizonY)
        )

        val potW = 74f
        val potH = 46f
        val potLeft = w / 2f - potW / 2f
        val potTop = horizonY - 4f
        val potPath = Path().apply {
            moveTo(potLeft, potTop)
            lineTo(potLeft + potW, potTop)
            lineTo(potLeft + potW - 10f, potTop + potH)
            lineTo(potLeft + 10f, potTop + potH)
            close()
        }
        drawPath(potPath, color = Color(0xFFD08B5B))
        drawRect(color = Color(0xFFB5723F), topLeft = Offset(potLeft - 4f, potTop - 6f), size = Size(potW + 8f, 8f))

        val maxStemHeight = horizonY * 0.55f
        val stemHeight = maxStemHeight * growthFrac
        if (stemHeight > 6f) {
            val stemBaseX = w / 2f
            val stemTopX = stemBaseX + sway
            val stemTopY = potTop - stemHeight
            drawLine(
                color = Color(0xFF4CAF50),
                start = Offset(stemBaseX, potTop),
                end = Offset(stemTopX, stemTopY),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )

            val leafPairs = stage.index.coerceIn(0, 5)
            for (i in 1..leafPairs) {
                val t = i / (leafPairs + 1f)
                val lx = stemBaseX + sway * t
                val ly = potTop - stemHeight * t
                drawOval(color = Color(0xFF66BB6A), topLeft = Offset(lx - 20f, ly - 6f), size = Size(18f, 11f))
                drawOval(color = Color(0xFF66BB6A), topLeft = Offset(lx + 2f, ly - 6f), size = Size(18f, 11f))
            }

            if (stage.index >= 5) {
                when {
                    stage.index == 5 -> drawCircle(color = Color(0xFF9CCC65), radius = 9f, center = Offset(stemTopX, stemTopY))
                    else -> {
                        val petalColor = if (stage.index >= 7) Color(0xFFFFA726) else Color(0xFFE91E63)
                        for (angleDeg in 0 until 360 step 60) {
                            val rad = Math.toRadians(angleDeg.toDouble())
                            drawCircle(
                                color = petalColor,
                                radius = 9f,
                                center = Offset(stemTopX + (14 * cos(rad)).toFloat(), stemTopY + (14 * sin(rad)).toFloat())
                            )
                        }
                        drawCircle(color = Color(0xFFFFF176), radius = 7f, center = Offset(stemTopX, stemTopY))
                    }
                }
            }
        } else {
            drawCircle(color = Color(0xFF6D4C41), radius = 6f, center = Offset(w / 2f, potTop - 4f))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloudPuff(center: Offset) {
    val color = Color.White.copy(alpha = 0.8f)
    drawCircle(color = color, radius = 12f, center = center)
    drawCircle(color = color, radius = 9f, center = center + Offset(14f, 2f))
    drawCircle(color = color, radius = 9f, center = center + Offset(-14f, 2f))
}
