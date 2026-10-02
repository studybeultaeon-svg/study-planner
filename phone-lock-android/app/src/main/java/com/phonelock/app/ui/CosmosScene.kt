package com.phonelock.app.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonelock.app.ui.theme.DarkBluePalette
import com.phonelock.app.ui.theme.LocalAppMotion
import com.phonelock.app.ui.theme.LocalPhoneLockPalette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 홈의 "우주"(145차 계획 B, 식물 장면을 대신한다 — [[DECISIONS.md]] 145·149차, 안드로이드판과 같은 코드).
 *
 * 그림 규칙: 바탕은 검은 우주 + 작은 점별, 천체는 **점·원·고리·호(와 그것들이 모인 선)**만으로 그린다.
 * 색은 둘뿐 — 물질의 모양은 흰 선([CosmosColors.ink]), 그 단계에서 새로 생긴 핵심 하나는 강조색([CosmosColors.accent],
 * 테마 primary). 그라디언트·삽화 배경은 쓰지 않는다.
 * 움직임(149차 사용자 요청 "과하지 않게, 정지보단 낫게"): 천체마다 그 천체다운 느린 움직임 하나 — 궤도를 도는 위성·행성,
 * 천천히 도는 원반·은하, 숨 쉬듯 커졌다 작아지는 빛 고리, 바깥으로 흘러가는 파편 — 와 몇몇 점별의 반짝임뿐이다.
 * 주기는 수 초~수 분, 폭은 몇 단위라 눈을 끌지 않고, [LocalAppMotion]이 줄임(성능 모드·시스템 애니메이션 끔)이면 멈춘다.
 * 그림은 [com.phonelock.shared.GrowthSystem.Stage.illustrationId]로 고른다 — 칭호와 그림이 1:1이다.
 */
class CosmosColors(val ink: Color, val accent: Color, val space: Color) {
    /** 둘 중 어두운 색(블랙홀의 사건의 지평선). */
    val void: Color get() = if (space.luminance() < ink.luminance()) space else ink
    /** 둘 중 밝은 색(강조색 면 위의 구름). */
    val light: Color get() = if (space.luminance() > ink.luminance()) space else ink
}

/**
 * 장면은 테마와 상관없이 늘 검은 우주다(149차 사용자 결정 — 식물 장면이 테마와 상관없이 늘 밝았던 것과 같은 자리이고,
 * 라이트 테마에선 위쪽 레벨 숫자 뒤 스크림이 종이 바탕과 장면을 잇는다). 다크 테마는 그 바탕이 곧 우주라 바탕·글자·
 * 강조색을 그대로 쓰고, 라이트 테마는 다크 · 블루의 바탕·글자에 강조색만 밝혀 쓴다(라이트의 진한 강조색은 검정에 묻힌다).
 */
@Composable
fun cosmosColors(): CosmosColors {
    val palette = LocalPhoneLockPalette.current
    return if (palette.isDark) CosmosColors(palette.onBackground, palette.primary, palette.background)
    else CosmosColors(DarkBluePalette.onBackground, brightenForSpace(palette.primary), DarkBluePalette.background)
}

/** 검은 바탕에서 읽히는 밝기로 — 색상(hue)·채도는 두고 명도만 올린다(흰색 쪽으로 섞으면 탁해진다). */
private fun brightenForSpace(c: Color): Color {
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    val l = (max + min) / 2f
    if (l >= 0.6f) return c
    val d = max - min
    val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
    val h = when {
        d == 0f -> 0f
        max == c.red -> 60f * (((c.green - c.blue) / d) % 6f)
        max == c.green -> 60f * ((c.blue - c.red) / d + 2f)
        else -> 60f * ((c.red - c.green) / d + 4f)
    }
    return Color.hsl((h + 360f) % 360f, s.coerceIn(0f, 1f), 0.64f)
}

/** 움직임을 다시 그리는 간격 — 움직임이 느려 초당 30장이면 충분하고, 화면 주사율만큼 그리는 것보다 배터리를 덜 쓴다. */
private const val COSMOS_FRAME_MS = 33L

/**
 * 우주 장면 한 장 — 점별 바탕 위 가운데에 지금 단계의 천체 하나. [contentTopInset]/[contentBottomInset]은 위·아래를 다른 UI
 * (레벨 숫자·아래 시트)가 덮는 높이로, 천체는 그 사이 영역의 가운데에 그 영역에 맞는 크기로 그린다. 움직임의 시각은
 * 그리기 단계에서만 읽어 컴포지션을 다시 돌리지 않고, [animate]가 꺼지면 처음 모습(시각 0)으로 멈춘다.
 */
@Composable
fun CosmosScene(
    illustrationId: String,
    modifier: Modifier = Modifier,
    contentTopInset: Dp = 0.dp,
    contentBottomInset: Dp = 0.dp,
    colors: CosmosColors = cosmosColors(),
    animate: Boolean = !LocalAppMotion.current.reduced
) {
    val time = remember { mutableFloatStateOf(0f) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now -> time.floatValue = (now - start) / 1_000_000_000f }
                delay(COSMOS_FRAME_MS)
            }
        }
    }
    Spacer(
        modifier.clipToBounds().drawWithCache {
            val top = contentTopInset.toPx()
            val bottom = (size.height - contentBottomInset.toPx()).coerceAtLeast(top + size.height * 0.3f)
            val center = Offset(size.width / 2f, (top + bottom) / 2f)
            val radius = min(size.width, bottom - top) * 0.46f
            onDrawBehind {
                val t = if (animate) time.floatValue else 0f
                drawRect(colors.space)
                drawStarfield(colors.ink, t)
                drawCelestialBody(illustrationId, center, radius, colors, t)
            }
        }
    )
}

/** 바탕의 작은 점별 — 위치는 캔버스 비율로 고정(크기가 바뀌어도 같은 하늘), 개수는 넓이에 비례. 셋 중 하나는 천천히 반짝인다. */
fun DrawScope.drawStarfield(ink: Color, t: Float = 0f) {
    val unit = min(size.width, size.height) / 200f
    val count = (size.width * size.height / (unit * unit * 1100f)).toInt().coerceIn(18, 90)
    for (i in 0 until count) {
        val x = hash01(i, 1) * size.width
        val y = hash01(i, 2) * size.height
        val r = (0.22f + hash01(i, 3) * 0.38f) * unit
        val twinkle = if (i % 3 == 0) 0.55f + 0.45f * sin(TAU * t / (3f + 4f * hash01(i, 5)) + hash01(i, 6) * TAU) else 1f
        drawCircle(ink.copy(alpha = (0.16f + hash01(i, 4) * 0.4f) * twinkle), r, Offset(x, y))
    }
}

/** [illustrationId]의 천체를 [center]를 중심으로 반지름 [radius] 안에 그린다(그림 좌표는 반지름 = 100). [t]는 움직임 시각(초). */
fun DrawScope.drawCelestialBody(illustrationId: String, center: Offset, radius: Float, colors: CosmosColors, t: Float = 0f) {
    (BODIES[illustrationId] ?: Sketch::cosmicDust)(Sketch(this, center, radius / 100f, colors, t))
}

/** 그림 id → 그리는 함수. [com.phonelock.shared.GrowthSystem.STAGES]의 id가 전부 여기 있어야 한다(테스트가 확인). */
private val BODIES: Map<String, Sketch.() -> Unit> = mapOf(
    "cosmic_dust" to Sketch::cosmicDust,
    "planetesimal" to Sketch::planetesimal,
    "protoplanet" to Sketch::protoplanet,
    "rocky_planet" to Sketch::rockyPlanet,
    "atmosphere_planet" to Sketch::atmospherePlanet,
    "ocean_planet" to Sketch::oceanPlanet,
    "moon_system" to Sketch::moonSystem,
    "ringed_planet" to Sketch::ringedPlanet,
    "gas_giant" to Sketch::gasGiant,
    "planetary_system" to Sketch::planetarySystem,
    "brown_dwarf" to Sketch::brownDwarf,
    "protostar" to Sketch::protostar,
    "main_sequence" to Sketch::mainSequence,
    "binary_star" to Sketch::binaryStar,
    "red_giant" to Sketch::redGiant,
    "supernova" to Sketch::supernova,
    "black_hole" to Sketch::blackHole,
    "galaxy" to Sketch::galaxy,
    "galaxy_cluster" to Sketch::galaxyCluster,
    "supercluster" to Sketch::supercluster,
    "observable_universe" to Sketch::observableUniverse,
    "multiverse" to Sketch::multiverse,
    "omega_point" to Sketch::omegaPoint
)

/** 그림이 있는 id 전체(테스트용). */
internal val COSMOS_BODY_IDS: Set<String> get() = BODIES.keys

private const val TAU = (2 * PI).toFloat()

private fun rad(deg: Float): Float = deg / 180f * PI.toFloat()

/** 위치·크기 흩뿌리기용 결정적 난수(0 이상 1 미만) — 같은 그림은 언제나 같은 자리에 그려진다. */
private fun hash01(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0x7fffffff) / 2147483648f
}

/** 소수 부분(0 이상 1 미만) — 흘러가다 처음으로 돌아오는 움직임에. */
private fun frac(x: Float): Float = x - kotlin.math.floor(x)

/** 그림 좌표(중심 0,0 · 반지름 100) 위의 점. */
private data class P(val x: Float, val y: Float)

/** (x, y)를 [deg]만큼 돌린 점 — 기운 궤도·원반 위의 자리를 구할 때. */
private fun tilt(x: Float, y: Float, deg: Float): P {
    val a = rad(deg)
    return P(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
}

/** 기운 타원 위 [angleDeg] 지점(궤도 위 위성·별 자리). */
private fun onEllipse(rx: Float, ry: Float, tiltDeg: Float, angleDeg: Float): P =
    tilt(rx * cos(rad(angleDeg)), ry * sin(rad(angleDeg)), tiltDeg)

/** 기운 궤도 위 [angleDeg] 자리가 보는 쪽(앞, 아래 반)인지 — 앞이면 가운데 천체 뒤에 그려야 가려지지 않는다. */
private fun isFront(angleDeg: Float): Boolean = sin(rad(angleDeg)) > 0f

/** 그림 좌표로 그리는 붓 — 선 굵기는 크기에 비례(작은 썸네일에서도 1.2px 아래로는 안 내려간다). [t]는 움직임 시각(초). */
private class Sketch(val scope: DrawScope, val c: Offset, val u: Float, val col: CosmosColors, val t: Float) {
    val sw = max(1.2f, u * 1.05f)
    val ink get() = col.ink
    val accent get() = col.accent

    /** [period]초에 한 바퀴 도는 각도(도). */
    fun spin(period: Float): Float = 360f * t / period

    /** [period]초 주기로 -1~1을 오가는 물결. */
    fun wave(period: Float, phase: Float = 0f): Float = sin(TAU * t / period + phase)

    fun at(x: Float, y: Float) = Offset(c.x + x * u, c.y + y * u)

    fun dot(x: Float, y: Float, r: Float, color: Color) = scope.drawCircle(color, max(r * u, 0.6f), at(x, y))

    fun ring(x: Float, y: Float, r: Float, color: Color, w: Float = sw) =
        scope.drawCircle(color, r * u, at(x, y), style = Stroke(w))

    /** 뒤를 가리는 천체 — 바탕색으로 채우고 먹 테두리. */
    fun body(x: Float, y: Float, r: Float, line: Color = ink, w: Float = sw) {
        scope.drawCircle(col.space, r * u, at(x, y))
        ring(x, y, r, line, w)
    }

    /** 기운 타원의 호 — 시작각 0은 오른쪽, 시계 방향. 0~180이 앞쪽(아래) 반, 180~360이 뒤쪽(위) 반. */
    fun arc(x: Float, y: Float, rx: Float, ry: Float, tiltDeg: Float, start: Float, sweep: Float, color: Color, w: Float = sw) {
        val o = at(x, y)
        scope.rotate(tiltDeg, o) {
            drawArc(
                color, start, sweep, false,
                topLeft = Offset(o.x - rx * u, o.y - ry * u), size = Size(2 * rx * u, 2 * ry * u),
                style = Stroke(w, cap = StrokeCap.Round)
            )
        }
    }

    fun oval(x: Float, y: Float, rx: Float, ry: Float, tiltDeg: Float, color: Color, stroke: Float? = null) {
        val o = at(x, y)
        scope.rotate(tiltDeg, o) {
            val tl = Offset(o.x - rx * u, o.y - ry * u)
            val sz = Size(2 * rx * u, 2 * ry * u)
            if (stroke == null) drawOval(color, tl, sz) else drawOval(color, tl, sz, style = Stroke(stroke))
        }
    }

    /** 원 안으로만 그린다(행성 표면의 띠·구름). */
    fun insideCircle(x: Float, y: Float, r: Float, block: () -> Unit) {
        val o = at(x, y)
        val path = Path().apply { addOval(androidx.compose.ui.geometry.Rect(o, r * u)) }
        scope.clipPath(path) { block() }
    }

    /** 점들로 이은 기운 타원(먼지 원반) 중 [front](앞쪽 반)이나 뒤쪽 반만 — [offsetDeg]만큼 돌려 놓는다(원반이 도는 움직임). */
    fun dottedEllipse(rx: Float, ry: Float, tiltDeg: Float, front: Boolean, n: Int, r: Float, color: Color, salt: Int, offsetDeg: Float = 0f) {
        for (i in 0 until n) {
            val a = 360f * frac((i + hash01(i, salt)) / n + offsetDeg / 360f)
            if (isFront(a) != front) continue
            val p = onEllipse(rx, ry, tiltDeg, a)
            dot(p.x, p.y, r * (0.6f + 0.6f * hash01(i, salt + 1)), color.copy(alpha = color.alpha * (0.45f + 0.55f * hash01(i, salt + 2))))
        }
    }
}

// ── 행성 등급: 먼지가 뭉쳐 행성이 되고, 행성이 거느리는 것이 늘어난다 ─────────────────────────

/** 우주 먼지 — 중심 없이 흩어진 점 구름. 강조색 점 몇 개가 "아직 아무것도 아닌 시작". 안쪽일수록 조금 빨리 소용돌이친다. */
private fun Sketch.cosmicDust() {
    for (i in 0 until 120) {
        val rr = 62f * hash01(i, 11).pow(0.85f)
        val a = hash01(i, 12) * TAU + TAU * t / 160f * (1.4f - rr / 62f)
        val p = tilt(rr * cos(a), rr * sin(a) * 0.58f, -18f)
        val color = if (i % 15 == 0) accent.copy(alpha = 0.6f + 0.4f * wave(4f + i % 4, i.toFloat())) else ink.copy(alpha = 0.28f + hash01(i, 14) * 0.6f)
        dot(p.x, p.y, 0.45f + hash01(i, 13) * 1.0f, color)
    }
}

/** 미행성 — 먼지 고리 가운데에 처음 뭉친 덩어리(강조색) 하나와, 그 둘레를 도는 작은 덩어리들. */
private fun Sketch.planetesimal() {
    for (i in 0 until 90) {
        val rr = 26f + 36f * hash01(i, 21)
        val a = hash01(i, 22) * TAU + TAU * t / 110f * (1.3f - (rr - 26f) / 72f)
        val p = tilt(rr * cos(a), rr * sin(a) * 0.5f, -18f)
        dot(p.x, p.y, 0.4f + hash01(i, 23) * 0.85f, ink.copy(alpha = 0.22f + hash01(i, 24) * 0.55f))
    }
    scope.drawCircle(accent, 10f * u, at(0f, 0f))
    listOf(Triple(P(18f, -5f), 3.4f, 40f), Triple(P(-15f, 9f), 2.6f, 55f), Triple(P(6f, 15f), 1.7f, 70f)).forEachIndexed { k, (p, r, period) ->
        val q = tilt(p.x, p.y, spin(period))
        scope.drawCircle(if (k == 2) ink.copy(alpha = 0.8f) else ink, r * u, at(q.x, q.y))
    }
}

/** 원시 행성 — 둥글어진 몸체로 작은 천체들이 끌려 들어오고, 막 부딪힌 자리가 달아올랐다 식기를 되풀이한다(강조색). */
private fun Sketch.protoplanet() {
    val r = 25f
    body(0f, 0f, r)
    for (i in 0 until 18) {
        val rr = (r - 5f) * sqrt(hash01(i, 31))
        val a = hash01(i, 32) * TAU
        dot(rr * cos(a), rr * sin(a), 0.6f + 1.3f * hash01(i, 33), ink.copy(alpha = 0.22f))
    }
    listOf(Triple(-38f, 3.4f, 0), Triple(158f, 2.6f, 1), Triple(78f, 2f, 2)).forEach { (deg, size, k) ->
        val a = rad(deg)
        val dist = 40f + k * 5f + 1.6f * wave(7f, k * 2f)
        scope.drawCircle(ink, size * u, at(dist * cos(a), dist * sin(a)))
        for (j in 1..4) {
            val d = dist + j * 6f
            val aj = a + j * 0.07f
            dot(d * cos(aj), d * sin(aj), size * (1f - j * 0.2f), ink.copy(alpha = 0.55f - j * 0.11f))
        }
    }
    val glow = 0.65f + 0.35f * (0.5f + 0.5f * wave(2.6f))
    arc(0f, 0f, r, r, 0f, 198f, 40f, accent.copy(alpha = glow), sw * 2.4f)
    val hit = rad(218f)
    for (j in 0 until 4) {
        val d = r + 3f + j * 3.4f
        val aj = hit + (j - 1.5f) * 0.12f
        dot(d * cos(aj), d * sin(aj), 1.4f - j * 0.24f, accent.copy(alpha = glow))
    }
}

/** 크레이터 자리(반지름 30 행성 기준). */
private val CRATERS = listOf(
    Triple(-10f, -9f, 6f), Triple(9f, -14f, 3.7f), Triple(12f, 8f, 7.1f), Triple(-8f, 14f, 4.1f),
    Triple(-18f, 2f, 2.8f), Triple(1f, -1f, 2.1f), Triple(20f, -5f, 1.8f)
)

/** 암석 행성 — 단단한 표면이 생겼다: 크레이터(강조색). 행성이 아주 천천히 돈다. */
private fun Sketch.rockyPlanet() {
    body(0f, 0f, 30f)
    val turn = spin(120f)
    CRATERS.forEach { (x, y, r) -> val p = tilt(x, y, turn); ring(p.x, p.y, r, accent, sw * 0.95f) }
}

/** 대기 행성 — 크레이터는 흐려지고(구름에 덮임) 행성을 감싸는 대기층(강조색 고리)이 생겼다. 구름이 흐르고 대기가 숨 쉰다. */
private fun Sketch.atmospherePlanet() {
    body(0f, 0f, 30f)
    val turn = spin(120f)
    CRATERS.forEach { (x, y, r) -> val p = tilt(x, y, turn); ring(p.x, p.y, r, ink.copy(alpha = 0.22f), sw * 0.8f) }
    insideCircle(0f, 0f, 30f) {
        arc(-5f + 2.5f * wave(14f), -7f, 18f, 5f, -6f, 200f, 120f, ink.copy(alpha = 0.55f), sw)
        arc(7f - 2.5f * wave(14f), 10f, 15f, 4.2f, -6f, 15f, 130f, ink.copy(alpha = 0.55f), sw)
    }
    ring(0f, 0f, 35f, accent, sw * 1.3f)
    ring(0f, 0f, 40f + 0.8f * wave(6f), accent.copy(alpha = 0.35f * (0.75f + 0.25f * wave(6f))), sw * 0.8f)
}

/** 해양 행성 — 표면이 물로 덮였다: 강조색으로 채운 원 + 밝은 구름 줄기(천천히 흐른다) + 옅은 대기선. */
private fun Sketch.oceanPlanet() {
    scope.drawCircle(accent, 30f * u, at(0f, 0f))
    insideCircle(0f, 0f, 30f) {
        arc(-7f + 3f * wave(16f), -11f, 17f, 5.5f, -8f, 200f, 120f, col.light.copy(alpha = 0.9f), sw * 1.4f)
        arc(9f + 3f * wave(16f, 2f), 5f, 14f, 4.4f, -8f, 20f, 130f, col.light.copy(alpha = 0.9f), sw * 1.4f)
        arc(-11f + 3f * wave(16f, 4f), 14f, 10f, 3.4f, -8f, 190f, 110f, col.light.copy(alpha = 0.75f), sw * 1.2f)
    }
    ring(0f, 0f, 35f, ink.copy(alpha = 0.35f), sw * 0.8f)
}

/** 위성계 행성 — 궤도를 도는 위성(강조색)이 생겼다. 궤도 뒤쪽 반과 그쪽을 지나는 위성은 행성에 가린다. */
private fun Sketch.moonSystem() {
    val orbitColor = ink.copy(alpha = 0.35f)
    val tiltDeg = -14f
    // (궤도 반지름 x, y, 반지름, 각도)
    val moons = listOf(
        listOf(38f, 12.5f, 3.8f, 35f + spin(24f)),
        listOf(56f, 19f, 2.6f, 215f + spin(44f)),
        listOf(56f, 19f, 2f, 340f + spin(44f))
    )
    fun drawMoons(front: Boolean) = moons.filter { isFront(it[3]) == front }.forEach { (rx, ry, r, a) ->
        val p = onEllipse(rx, ry, tiltDeg, a)
        scope.drawCircle(accent, r * u, at(p.x, p.y))
    }
    arc(0f, 0f, 38f, 12.5f, tiltDeg, 180f, 180f, orbitColor, sw * 0.8f)
    arc(0f, 0f, 56f, 19f, tiltDeg, 180f, 180f, orbitColor, sw * 0.8f)
    drawMoons(front = false)
    body(0f, 0f, 23f)
    insideCircle(0f, 0f, 23f) {
        arc(-3f, -7f, 14f, 4.2f, -8f, 200f, 120f, ink.copy(alpha = 0.4f), sw)
        arc(6f, 7f, 11.5f, 3.4f, -8f, 20f, 130f, ink.copy(alpha = 0.4f), sw)
    }
    arc(0f, 0f, 38f, 12.5f, tiltDeg, 0f, 180f, orbitColor, sw * 0.8f)
    arc(0f, 0f, 56f, 19f, tiltDeg, 0f, 180f, orbitColor, sw * 0.8f)
    drawMoons(front = true)
}

/** 고리 행성 — 기운 고리(강조색)가 생겼다. 고리 뒤쪽 반은 행성 뒤로 돌고, 작은 알갱이 하나가 고리를 따라 돈다. */
private fun Sketch.ringedPlanet() {
    val tiltDeg = -16f
    val moonlet = 60f + spin(30f)
    fun drawMoonlet(front: Boolean) {
        if (isFront(moonlet) != front) return
        val p = onEllipse(49f, 13.2f, tiltDeg, moonlet)
        dot(p.x, p.y, 1.3f, ink)
    }
    arc(0f, 0f, 52f, 14f, tiltDeg, 180f, 180f, accent, sw * 1.6f)
    arc(0f, 0f, 42f, 11.3f, tiltDeg, 180f, 180f, accent.copy(alpha = 0.5f), sw)
    drawMoonlet(front = false)
    body(0f, 0f, 25f)
    insideCircle(0f, 0f, 25f) {
        arc(0f, -10f, 27f, 3.4f, tiltDeg, 0f, 180f, ink.copy(alpha = 0.3f), sw)
        arc(0f, 4.5f, 27f, 3.4f, tiltDeg, 0f, 180f, ink.copy(alpha = 0.3f), sw)
    }
    arc(0f, 0f, 52f, 14f, tiltDeg, 0f, 180f, accent, sw * 1.6f)
    arc(0f, 0f, 42f, 11.3f, tiltDeg, 0f, 180f, accent.copy(alpha = 0.5f), sw)
    drawMoonlet(front = true)
}

/** 가스 거인 — 더 크고, 표면이 줄무늬 띠(굵은 띠 둘이 강조색)와 큰 소용돌이 점으로 바뀌었다. 행성이 돌며 소용돌이가 지나간다. */
private fun Sketch.gasGiant() {
    val r = 38f
    body(0f, 0f, r)
    insideCircle(0f, 0f, r) {
        listOf(-26f to false, -13.5f to true, -3.5f to false, 8f to true, 19f to false, 29f to false).forEach { (y, belt) ->
            val half = sqrt((r * r - y * y).coerceAtLeast(0f)) + 2f
            arc(0f, y - 3.4f, half, 3.4f, 0f, 0f, 180f, if (belt) accent else ink.copy(alpha = 0.45f), if (belt) sw * 2.2f else sw * 0.9f)
        }
        // 소용돌이 점은 경도를 따라 돈다 — 가장자리로 갈수록 납작해지고, 뒤쪽으로 넘어가면 보이지 않는다.
        val lon = 0.41f + TAU * t / 50f
        val facing = cos(lon)
        if (facing > 0.12f) {
            val x = 35f * sin(lon)
            oval(x, 14.5f, 6.8f * facing, 3.6f, 0f, col.space)
            oval(x, 14.5f, 6.8f * facing, 3.6f, 0f, ink, sw)
        }
    }
}

/** 행성계 — 한 걸음 물러나면: 가운데 별(강조색) 둘레를 도는 여러 행성(안쪽일수록 빨리 돈다). */
private fun Sketch.planetarySystem() {
    val tiltDeg = -12f
    val orbits = listOf(16f, 25f, 35f, 47f, 62f, 80f)
    val periods = listOf(12f, 18f, 27f, 42f, 62f, 90f)
    orbits.forEach { rx -> oval(0f, 0f, rx, rx * 0.36f, tiltDeg, ink.copy(alpha = 0.25f), sw * 0.75f) }
    scope.drawCircle(accent, 5.5f * u, at(0f, 0f))
    ring(0f, 0f, 8.5f, accent.copy(alpha = 0.35f), sw * 0.8f)
    val planets = listOf(40f to 1.3f, 160f to 1.7f, 290f to 1.8f, 105f to 1.5f, 230f to 4.2f, 335f to 3f)
    planets.forEachIndexed { i, (deg, size) ->
        val p = onEllipse(orbits[i], orbits[i] * 0.36f, tiltDeg, deg + spin(periods[i]))
        if (i == 5) oval(p.x, p.y, 6f, 1.8f, tiltDeg - 8f, accent, sw * 0.9f)
        scope.drawCircle(ink, size * u, at(p.x, p.y))
    }
}

// ── 항성 등급: 스스로 빛나기 시작한다 ─────────────────────────────────────────

/** 갈색 왜성 — 별이 되다 만 천체: 가스 거인 같은 띠에 희미하게 달아오른 면(옅은 강조색). 희미한 빛이 잦아들었다 살아난다. */
private fun Sketch.brownDwarf() {
    val r = 33f
    val ember = 0.5f + 0.5f * wave(5f)
    scope.drawCircle(col.space, r * u, at(0f, 0f))
    scope.drawCircle(accent.copy(alpha = 0.18f + 0.08f * ember), r * u, at(0f, 0f))
    insideCircle(0f, 0f, r) {
        listOf(-18f, -6f, 6.5f, 19f).forEach { y ->
            val half = sqrt(r * r - y * y) + 2f
            arc(0f, y - 3.4f, half, 3.4f, 0f, 0f, 180f, ink.copy(alpha = 0.28f), sw)
        }
    }
    ring(0f, 0f, r, accent.copy(alpha = 0.75f), sw)
    ring(0f, 0f, 38.5f, accent.copy(alpha = 0.1f + 0.12f * ember), sw * 0.8f)
}

/** 원시성 — 먼지 원반 가운데서 막 불이 붙은 핵(강조색)과 위아래로 뿜는 제트. 원반은 돌고 제트는 바깥으로 흐른다. */
private fun Sketch.protostar() {
    val tiltDeg = -10f
    fun disc(front: Boolean) {
        for (k in 0..3) {
            val rx = 24f + k * 12f
            dottedEllipse(rx, rx * 0.24f, tiltDeg, front, 2 * (22 + k * 6), 1f, ink.copy(alpha = 0.8f - k * 0.15f), 40 + k * 3, spin(26f + k * 12f))
        }
    }
    disc(front = false)
    scope.drawCircle(accent, 6.5f * u, at(0f, 0f))
    ring(0f, 0f, 10.5f, accent.copy(alpha = 0.4f), sw)
    disc(front = true)
    val start = 14f
    val span = 56f
    val spacing = 4.5f
    val flow = (t * 5f) % spacing
    listOf(tiltDeg - 90f, tiltDeg + 90f).forEach { dir ->
        var d = start + flow
        while (d <= start + span) {
            val fadeIn = ((d - start) / 6f).coerceIn(0f, 1f)
            dot(d * cos(rad(dir)), d * sin(rad(dir)), 1.5f - d / 68f, accent.copy(alpha = (1f - d / 80f).coerceAtLeast(0.15f) * fadeIn))
            d += spacing
        }
    }
}

/** 주계열성 — 안정적으로 타오르는 별: 강조색으로 꽉 찬 원과 바깥으로 옅어지는 빛 고리(천천히 숨 쉰다). */
private fun Sketch.mainSequence() {
    scope.drawCircle(accent, 27f * u, at(0f, 0f))
    listOf(Triple(33f, 0.5f, 1.2f), Triple(40.5f, 0.28f, 1f), Triple(49f, 0.14f, 0.8f)).forEachIndexed { k, (r, a, w) ->
        val breath = wave(5f, -k * 0.9f)
        ring(0f, 0f, r + 1.2f * breath, accent.copy(alpha = a * (0.8f + 0.2f * breath)), sw * w)
    }
}

/** 쌍성계 — 하나의 궤도를 함께 도는 두 별: 큰 별(먹)과 짝별(강조색), 가운데 무게중심. */
private fun Sketch.binaryStar() {
    val tiltDeg = -14f
    val turn = spin(40f)
    oval(0f, 0f, 48f, 16.5f, tiltDeg, ink.copy(alpha = 0.3f), sw * 0.8f)
    dot(0f, 0f, 1.2f, ink.copy(alpha = 0.6f))
    val big = 205f + turn
    val small = 25f + turn
    fun drawBig() {
        val a = onEllipse(48f, 16.5f, tiltDeg, big)
        scope.drawCircle(ink, 14f * u, at(a.x, a.y))
        ring(a.x, a.y, 19f, ink.copy(alpha = 0.3f), sw * 0.9f)
    }
    fun drawSmall() {
        val b = onEllipse(48f, 16.5f, tiltDeg, small)
        scope.drawCircle(accent, 9f * u, at(b.x, b.y))
        ring(b.x, b.y, 12.8f, accent.copy(alpha = 0.35f), sw * 0.9f)
    }
    // 뒤쪽에 있는 별을 먼저 그려야 둘이 겹칠 때 앞 별이 가린다.
    if (isFront(big)) { drawSmall(); drawBig() } else { drawBig(); drawSmall() }
}

// ── 별의 최후 등급 ─────────────────────────────────────────────────────────

/** 적색 거성 — 화면을 채울 만큼 부푼 옅은 바깥층(강조색 테두리, 느리게 맥동), 흩어져 나가는 가장자리, 그 안의 작은 핵. */
private fun Sketch.redGiant() {
    val r = 52f + 1f * wave(9f)
    scope.drawCircle(col.space, r * u, at(0f, 0f))
    scope.drawCircle(accent.copy(alpha = 0.12f), r * u, at(0f, 0f))
    ring(0f, 0f, r * 0.6f, accent.copy(alpha = 0.22f), sw * 0.8f)
    ring(0f, 0f, r, accent, sw * 1.5f)
    val drift = spin(80f)
    for (i in 0 until 16) {
        arc(0f, 0f, r + 7f, r + 7f, 0f, i * (360f / 16) + hash01(i, 71) * 8f + drift, 11f, ink.copy(alpha = 0.22f), sw * 0.8f)
    }
    scope.drawCircle(ink, 2.6f * u, at(0f, 0f))
    ring(0f, 0f, 5.5f, ink.copy(alpha = 0.4f), sw * 0.8f)
}

/** 초신성 — 터져 나가는 껍질(끊어진 고리, 안쪽이 강조색)과 바깥으로 계속 흘러가는 파편, 가운데 남은 핵. */
private fun Sketch.supernova() {
    for (i in 0 until 48) {
        val a = hash01(i, 81) * 360f
        // 파편은 안에서 밖으로 흐르고, 끝에 닿으면 사라졌다가 안쪽에서 다시 나온다(나올 때 서서히 밝아진다).
        val f = frac(hash01(i, 82) + t / 18f)
        val d = 24f + 50f * f
        val fadeIn = (f / 0.08f).coerceIn(0f, 1f)
        dot(d * cos(rad(a)), d * sin(rad(a)), 1.3f - 0.9f * f, ink.copy(alpha = (0.8f - 0.5f * f) * fadeIn))
    }
    fun shell(r: Float, n: Int, minSweep: Float, maxSweep: Float, color: Color, w: Float, salt: Int, turn: Float) {
        for (i in 0 until n) {
            val start = i * (360f / n) + hash01(i, salt) * 10f + turn
            arc(0f, 0f, r, r, 0f, start, minSweep + (maxSweep - minSweep) * hash01(i, salt + 1), color, w)
        }
    }
    shell(20f, 7, 28f, 40f, accent, sw * 1.8f, 83, spin(70f))
    shell(34f, 9, 18f, 28f, ink.copy(alpha = 0.55f), sw * 1.1f, 85, -spin(90f))
    shell(50f, 12, 10f, 18f, accent.copy(alpha = 0.45f), sw, 87, spin(120f))
    scope.drawCircle(ink, 2.6f * u, at(0f, 0f))
    ring(0f, 0f, 5.5f, ink.copy(alpha = 0.4f), sw * 0.8f)
}

/**
 * 블랙홀 — 빛조차 못 빠져나오는 원(가장 어두운 색)과 그 앞을 가로지르는 납작한 강착 원반(강조색). 원반의 뒤쪽이
 * 중력에 휘어 구멍 위·아래로 감겨 보인다(고리 행성과 달리 먹 테두리가 없고, 빛은 전부 강조색). 원반 위 밝은 덩어리 셋이
 * 안쪽일수록 빨리 돈다.
 */
private fun Sketch.blackHole() {
    val tiltDeg = -6f
    // 안쪽(구멍에 가까울수록 뜨겁다)이 가장 밝다 — 바깥 고리가 가장 진한 고리 행성과 반대.
    val disc = listOf(Triple(72f, 0.25f, 0.8f), Triple(60f, 0.5f, 1f), Triple(48f, 1f, 1.6f), Triple(38f, 0.8f, 1.2f))
    val clumps = listOf(Triple(40f, 9f, 0f), Triple(52f, 14f, 140f), Triple(64f, 20f, 260f))
    fun drawClumps(front: Boolean) = clumps.forEach { (rx, period, phase) ->
        val a = phase + spin(period)
        if (isFront(a) != front) return@forEach
        val p = onEllipse(rx, rx * 0.1f, tiltDeg, a)
        dot(p.x, p.y, 1.3f, col.light.copy(alpha = 0.85f))
    }
    disc.forEach { (rx, a, w) -> arc(0f, 0f, rx, rx * 0.1f, tiltDeg, 180f, 180f, accent.copy(alpha = a), sw * w) }
    drawClumps(front = false)
    arc(0f, 0f, 34f, 32f, tiltDeg, 192f, 156f, accent.copy(alpha = 0.3f), sw * 0.8f)
    arc(0f, 0f, 29f, 27f, tiltDeg, 188f, 164f, accent, sw * 2.2f)
    arc(0f, 0f, 25f, 23f, tiltDeg, 14f, 152f, accent.copy(alpha = 0.6f), sw * 1.2f)
    scope.drawCircle(col.void, 20f * u, at(0f, 0f))
    ring(0f, 0f, 21.5f, accent.copy(alpha = 0.8f + 0.1f * wave(3f)), sw * 0.7f)
    disc.forEach { (rx, a, w) -> arc(0f, 0f, rx, rx * 0.1f, tiltDeg, 0f, 180f, accent.copy(alpha = a), sw * w) }
    drawClumps(front = true)
}

// ── 은하 등급: 별들이 모인다 ──────────────────────────────────────────────────

/** 은하 — 점(별)으로 이어진 나선팔 두 개와 밝은 중심부(강조색). 은하 전체가 아주 천천히 돈다. */
private fun Sketch.galaxy() {
    val tiltDeg = -22f
    val turn = TAU * t / 140f
    for (i in 0 until 60) {
        val rr = 70f * sqrt(hash01(i, 91))
        val a = hash01(i, 92) * TAU + turn
        val p = tilt(rr * cos(a), rr * sin(a) * 0.6f, tiltDeg)
        dot(p.x, p.y, 0.45f, ink.copy(alpha = 0.2f))
    }
    for (arm in 0..1) {
        for (k in 0 until 100) {
            val s = k / 100f
            val th = arm * PI.toFloat() + s * 3.3f * PI.toFloat() + (hash01(k + arm * 100, 93) - 0.5f) * 0.25f + turn
            val rr = 6f + 72f * s + (hash01(k + arm * 100, 94) - 0.5f) * 8f * s
            val p = tilt(rr * cos(th), rr * sin(th) * 0.6f, tiltDeg)
            dot(p.x, p.y, (1.6f - 0.95f * s) * (0.6f + 0.4f * hash01(k + arm * 100, 95)), ink.copy(alpha = 0.95f - 0.55f * s))
        }
    }
    oval(0f, 0f, 12f, 7.2f, tiltDeg, accent.copy(alpha = 0.35f))
    scope.drawCircle(accent, 5.2f * u, at(0f, 0f))
}

/** 은하단 — 크고 작은 은하 수십 개(기운 타원·점, 제자리에서 조금씩 떠다닌다)와 가운데 거대 타원 은하(강조색). */
private fun Sketch.galaxyCluster() {
    ring(0f, 0f, 76f, ink.copy(alpha = 0.12f), sw * 0.8f)
    for (i in 0 until 28) {
        val rr = 16f + 56f * hash01(i, 101).pow(0.8f)
        val a = hash01(i, 102) * TAU
        val drift = TAU * t / (20f + 20f * hash01(i, 106)) + hash01(i, 107) * TAU
        val x = rr * cos(a) + 1.2f * cos(drift)
        val y = rr * sin(a) + 1.2f * sin(drift)
        val tiltDeg = hash01(i, 103) * 180f
        when (i % 3) {
            0 -> {
                val rx = 2.6f + 2.8f * hash01(i, 104)
                oval(x, y, rx, rx * (0.35f + 0.45f * hash01(i, 105)), tiltDeg, ink.copy(alpha = 0.8f), sw * 0.8f)
                dot(x, y, 0.7f, ink)
            }
            1 -> oval(x, y, 1.6f + 1.4f * hash01(i, 104), 1.1f + 0.6f * hash01(i, 105), tiltDeg, ink.copy(alpha = 0.75f))
            else -> dot(x, y, 0.8f + 0.6f * hash01(i, 104), ink.copy(alpha = 0.7f))
        }
    }
    oval(0f, 0f, 13f, 9.2f, 20f, accent.copy(alpha = 0.3f * (0.75f + 0.25f * wave(6f))))
    oval(0f, 0f, 8f, 5.6f, 20f, accent)
}

/** 해바라기 배치(골고루 퍼지되 격자처럼 보이지 않게 흔든 점 [n]개, 반지름 [radius] 안). */
private fun sunflower(n: Int, radius: Float, salt: Int): List<P> = (0 until n).map { i ->
    val rr = radius * sqrt((i + 0.5f) / n) * (0.86f + 0.28f * hash01(i, salt))
    val th = i * 2.3999632f + (hash01(i, salt + 1) - 0.5f) * 0.7f
    P(rr * cos(th), rr * sin(th))
}

private fun dist2(a: P, b: P): Float = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)

/** 우주 거대 구조 — [nodes]마다 가까운 이웃 [links]개와 살짝 휜 점선 필라멘트로 잇는다. 점들이 제각각 천천히 반짝인다. */
private fun Sketch.cosmicWeb(nodes: List<P>, links: Int, dotsPerUnit: Float, dotR: Float, alpha: Float, salt: Int) {
    val edges = LinkedHashSet<Pair<Int, Int>>()
    nodes.forEachIndexed { i, p ->
        nodes.indices.filter { it != i }.sortedBy { dist2(nodes[it], p) }.take(links).forEach { j -> edges += minOf(i, j) to maxOf(i, j) }
    }
    edges.forEachIndexed { e, (a, b) ->
        val pa = nodes[a]
        val pb = nodes[b]
        val len = sqrt(dist2(pa, pb)).coerceAtLeast(1f)
        val bend = (hash01(e, salt) - 0.5f) * len * 0.3f
        val cx = (pa.x + pb.x) / 2f - (pb.y - pa.y) / len * bend
        val cy = (pa.y + pb.y) / 2f + (pb.x - pa.x) / len * bend
        val n = (len * dotsPerUnit).toInt().coerceAtLeast(3)
        for (k in 1 until n) {
            val s = k / n.toFloat()
            val h = e * 97 + k
            val x = (1 - s) * (1 - s) * pa.x + 2 * (1 - s) * s * cx + s * s * pb.x + (hash01(h, salt + 1) - 0.5f) * 2.2f
            val y = (1 - s) * (1 - s) * pa.y + 2 * (1 - s) * s * cy + s * s * pb.y + (hash01(h, salt + 2) - 0.5f) * 2.2f
            val twinkle = 0.75f + 0.25f * wave(4f + 3f * hash01(h, salt + 5), hash01(h, salt + 6) * TAU)
            dot(x, y, dotR * (0.6f + 0.6f * hash01(h, salt + 3)), ink.copy(alpha = alpha * (0.5f + 0.5f * hash01(h, salt + 4)) * twinkle))
        }
    }
}

/** 초은하단 — 은하단(점 덩어리)들이 필라멘트로 이어진 그물, 가장 큰 마디가 강조색(빛 고리가 숨 쉰다). */
private fun Sketch.supercluster() {
    val nodes = sunflower(12, 68f, 111)
    cosmicWeb(nodes, 2, 1.0f, 0.75f, 0.6f, 113)
    nodes.forEachIndexed { n, p ->
        val big = n == 0
        for (k in 0 until (if (big) 20 else 12)) {
            val rr = (if (big) 6.5f else 4.5f) * sqrt(hash01(n * 30 + k, 115))
            val a = hash01(n * 30 + k, 116) * TAU
            dot(p.x + rr * cos(a), p.y + rr * sin(a), 0.5f + 0.5f * hash01(n * 30 + k, 117), ink.copy(alpha = 0.85f))
        }
        if (big) {
            scope.drawCircle(accent, 2.6f * u, at(p.x, p.y))
            ring(p.x, p.y, 10f + 0.8f * wave(5f), accent.copy(alpha = 0.45f), sw * 0.9f)
        }
    }
}

// ── 우주 등급 ────────────────────────────────────────────────────────────

/** 관측 가능한 우주 — 빛이 닿는 끝(강조색 경계 원, 바깥 띠가 옅게 숨 쉰다) 안을 촘촘한 우주 거대 구조가 채우고, 가운데가 "여기". */
private fun Sketch.observableUniverse() {
    val nodes = sunflower(40, 74f, 121)
    cosmicWeb(nodes, 2, 0.8f, 0.45f, 0.6f, 123)
    nodes.forEachIndexed { n, p ->
        for (k in 0 until 5) {
            val rr = 2.4f * sqrt(hash01(n * 10 + k, 125))
            val a = hash01(n * 10 + k, 126) * TAU
            dot(p.x + rr * cos(a), p.y + rr * sin(a), 0.4f, ink.copy(alpha = 0.7f))
        }
    }
    ring(0f, 0f, 89f, accent.copy(alpha = 0.3f * (0.6f + 0.4f * wave(7f))), sw * 0.8f)
    ring(0f, 0f, 84f, accent, sw * 1.4f)
    scope.drawCircle(col.space, 4.6f * u, at(0f, 0f))
    ring(0f, 0f, 3.4f, ink, sw)
    dot(0f, 0f, 1f, ink)
}

/** 다중우주 — 크고 작은 우주 거품들(제각각 천천히 떠다닌다), 그중 우리 우주 하나가 강조색. */
private fun Sketch.multiverse() {
    val bubbles = listOf(
        Triple(-48f, -30f, 20f), Triple(44f, -36f, 22f), Triple(52f, 32f, 17f), Triple(-42f, 40f, 24f),
        Triple(4f, -66f, 12f), Triple(-74f, 4f, 11f), Triple(12f, 62f, 14f), Triple(78f, -2f, 9f)
    )
    fun bubble(x: Float, y: Float, r: Float, line: Color, w: Float, salt: Int) {
        body(x, y, r, line, w)
        for (k in 0 until (r * 1.4f).toInt()) {
            val rr = r * 0.78f * sqrt(hash01(k, salt))
            val a = hash01(k, salt + 1) * TAU
            dot(x + rr * cos(a), y + rr * sin(a), 0.35f + 0.25f * hash01(k, salt + 2), ink.copy(alpha = 0.4f))
        }
    }
    bubbles.forEachIndexed { i, (x, y, r) ->
        val period = 14f + 3f * i
        bubble(x + 1.5f * wave(period, i.toFloat()), y + 1.5f * wave(period * 1.3f, i + 1.5f), r, ink.copy(alpha = 0.7f), sw, 131 + i * 3)
    }
    val ox = 0.8f * wave(18f)
    val oy = 0.8f * wave(23f, 1f)
    bubble(ox, oy, 30f, accent, sw * 1.5f, 161)
    dot(ox, oy, 1.4f, accent)
}

/** 오메가 포인트 — 모든 선이 휘감기며 점 하나(강조색)로 수렴한다. 소용돌이가 천천히 감겨 들어가고 가운데 점이 맥동한다. */
private fun Sketch.omegaPoint() {
    val lines = 28
    val turn = TAU * t / 100f
    for (i in 0 until lines) {
        val th0 = i * TAU / lines + hash01(i, 141) * 0.1f + turn
        val path = Path()
        for (k in 0..48) {
            val s = k / 48f
            val rr = 92f * (1f - s).pow(1.15f)
            val th = th0 + 1.6f * s.pow(1.2f)
            val o = at(rr * cos(th), rr * sin(th))
            if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        scope.drawPath(path, ink.copy(alpha = 0.12f + 0.35f * hash01(i, 142)), style = Stroke(sw * 0.8f, cap = StrokeCap.Round))
    }
    val pulse = wave(3f)
    ring(0f, 0f, 12f + 1.2f * pulse, accent.copy(alpha = 0.2f * (0.7f + 0.3f * pulse)), sw * 0.8f)
    ring(0f, 0f, 7f + 0.6f * pulse, accent.copy(alpha = 0.45f), sw)
    scope.drawCircle(accent, 3.2f * u, at(0f, 0f))
}
