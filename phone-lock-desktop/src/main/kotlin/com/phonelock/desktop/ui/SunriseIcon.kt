package com.phonelock.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter

/**
 * 트레이/창 아이콘 — 148차(사용자 선택): "하나의 별이 자라고, 그 별들이 모여 별자리가 된다". 가운데 가장 밝은 별(은은한 푸른 빛) +
 * 별 넷을 잇는 W자 별자리 + 작은 점별. 안드로이드 `ic_launcher_background.xml`/`ic_launcher_foreground.xml`과 같은 108x108 좌표·같은 색을
 * 그린다. 설치 파일 아이콘은 `packaging/app-icon.ico`(같은 그림). 객체 이름(`SunriseIcon`)은 변경 범위를 줄이려고 그대로 유지.
 */
object SunriseIcon : Painter() {
    override val intrinsicSize = Size(108f, 108f)

    private val STARS = listOf(27f to 44f, 40f to 62f, 54f to 49f, 68f to 64f, 81f to 42f)
    private const val MAIN = 2
    private val DOTS = listOf(Triple(33f, 30f, 1.2f), Triple(76f, 28f, 1.0f), Triple(72f, 80f, 1.4f), Triple(34f, 78f, 1.0f), Triple(54f, 82f, 0.9f))

    override fun DrawScope.onDraw() {
        val s = size.width / 108f
        fun pt(x: Float, y: Float) = Offset(x * s, y * s)

        drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF10182E), Color(0xFF0B0E14))), size = size)
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0x996AA4FF), Color(0x006AA4FF)), center = pt(54f, 49f), radius = 17f * s),
            radius = 17f * s,
            center = pt(54f, 49f)
        )
        DOTS.forEach { (x, y, r) -> drawCircle(Color(0xFFC9D6F2), radius = r * s, center = pt(x, y)) }

        val line = Path().apply {
            STARS.forEachIndexed { i, (x, y) -> val p = pt(x, y); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        }
        drawPath(line, color = Color(0xFF5B7FC4), style = Stroke(width = 2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        STARS.forEachIndexed { i, (x, y) -> drawCircle(Color(0xFFF4F7FF), radius = (if (i == MAIN) 7.2f else 4.2f) * s, center = pt(x, y)) }
    }
}
