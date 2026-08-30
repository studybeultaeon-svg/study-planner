package com.phonelock.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

/**
 * 트레이/창 아이콘용 일출 그림. 안드로이드 `ic_launcher_background.xml`/`ic_launcher_foreground.xml`과
 * 같은 108x108 좌표·같은 디자인(하늘 그라데이션 + 원형 태양 + 곡선 언덕)을 공유한다 — 예전엔(`PixelSunriseIcon`)
 * 각진 픽셀아트였으나 모바일 아이콘 리메이크(2026-08-26)에 맞춰 부드러운 곡선으로 함께 교체했다.
 */
object SunriseIcon : Painter() {
    override val intrinsicSize = Size(108f, 108f)

    override fun DrawScope.onDraw() {
        val scale = size.width / 108f
        fun pt(x: Float, y: Float) = Offset(x * scale, y * scale)

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF4FC3F7), Color(0xFFFFCC80), Color(0xFFFB8C00))
            ),
            size = size
        )

        val sunCenter = pt(54f, 39f)
        drawCircle(color = Color(0xFFFFF3E0), radius = 16f * scale, center = sunCenter)
        drawCircle(color = Color(0xFFFFB300), radius = 12f * scale, center = sunCenter)

        val hills = Path().apply {
            moveTo(pt(21f, 87f).x, pt(21f, 87f).y)
            lineTo(pt(21f, 74f).x, pt(21f, 74f).y)
            val c1 = pt(28f, 60f); val c2 = pt(40f, 58f); val e1 = pt(50f, 68f)
            cubicTo(c1.x, c1.y, c2.x, c2.y, e1.x, e1.y)
            val c3 = pt(58f, 76f); val c4 = pt(68f, 56f); val e2 = pt(78f, 64f)
            cubicTo(c3.x, c3.y, c4.x, c4.y, e2.x, e2.y)
            val c5 = pt(82f, 67f); val c6 = pt(85f, 70f); val e3 = pt(87f, 73f)
            cubicTo(c5.x, c5.y, c6.x, c6.y, e3.x, e3.y)
            lineTo(pt(87f, 87f).x, pt(87f, 87f).y)
            close()
        }
        drawPath(hills, color = Color(0xFF1E3A5F))
    }
}
