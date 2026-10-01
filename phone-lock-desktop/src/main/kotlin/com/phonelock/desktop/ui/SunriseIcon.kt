package com.phonelock.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter

/**
 * 트레이/창 아이콘 — 146차: 홈 컨셉이 식물에서 우주로 바뀌면서 "우주 속의 별"(사용자 요청). 안드로이드
 * `ic_launcher_background.xml`/`ic_launcher_foreground.xml`과 같은 108x108 좌표·같은 색(남색 바탕 + 은은한 빛 +
 * 네 갈래 별 + 기울어진 궤도 고리 + 작은 점별)을 그린다. 설치 파일 아이콘은 `packaging/app-icon.ico`(같은 그림).
 * 객체 이름(`SunriseIcon`)은 변경 범위를 줄이려고 그대로 유지 — 실제 그림만 교체.
 */
object SunriseIcon : Painter() {
    override val intrinsicSize = Size(108f, 108f)

    private const val C = 54f
    private const val STAR_R = 25f
    private const val STAR_K = 4f
    private const val ORBIT_RX = 32f
    private const val ORBIT_RY = 10.5f
    private const val ORBIT_ROTATION = -24f
    private val DOTS = listOf(
        Triple(33f, 34f, 1.9f), Triple(77f, 31f, 1.3f), Triple(74f, 76f, 2.1f),
        Triple(31f, 74f, 1.2f), Triple(84f, 52f, 1.0f), Triple(46f, 84f, 0.9f)
    )

    override fun DrawScope.onDraw() {
        val s = size.width / 108f
        fun pt(x: Float, y: Float) = Offset(x * s, y * s)

        drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF10182E), Color(0xFF0B0E14))), size = size)
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0x556AA4FF), Color(0x006AA4FF)), center = pt(C, C), radius = 30f * s),
            radius = 30f * s,
            center = pt(C, C)
        )

        // 궤도 고리는 별 뒤로 지나가는 윗호(어둡게)와 별 앞으로 지나가는 아랫호(강조색)로 나눠 그린다.
        val orbitTopLeft = pt(C - ORBIT_RX, C - ORBIT_RY)
        val orbitSize = Size(ORBIT_RX * 2 * s, ORBIT_RY * 2 * s)
        val orbitStroke = Stroke(width = 2.2f * s, cap = StrokeCap.Round)
        rotate(ORBIT_ROTATION, pivot = pt(C, C)) {
            drawArc(Color(0xFF3A5C95), 180f, 180f, useCenter = false, topLeft = orbitTopLeft, size = orbitSize, style = orbitStroke)
        }

        DOTS.forEach { (x, y, r) -> drawCircle(Color(0xFFC9D6F2), radius = r * s, center = pt(x, y)) }

        val star = Path().apply {
            val top = pt(C, C - STAR_R); val right = pt(C + STAR_R, C); val bottom = pt(C, C + STAR_R); val left = pt(C - STAR_R, C)
            moveTo(top.x, top.y)
            pt(C + STAR_K, C - STAR_K).let { quadraticBezierTo(it.x, it.y, right.x, right.y) }
            pt(C + STAR_K, C + STAR_K).let { quadraticBezierTo(it.x, it.y, bottom.x, bottom.y) }
            pt(C - STAR_K, C + STAR_K).let { quadraticBezierTo(it.x, it.y, left.x, left.y) }
            pt(C - STAR_K, C - STAR_K).let { quadraticBezierTo(it.x, it.y, top.x, top.y) }
            close()
        }
        drawPath(star, color = Color(0xFFF4F7FF))

        rotate(ORBIT_ROTATION, pivot = pt(C, C)) {
            drawArc(Color(0xFF6AA4FF), 0f, 180f, useCenter = false, topLeft = orbitTopLeft, size = orbitSize, style = orbitStroke)
        }
    }
}
