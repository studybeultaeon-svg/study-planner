package com.phonelock.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter

/**
 * 트레이/창 아이콘 — "우주 속의 별". 147차: 146차의 네 갈래 별 + 푸른 빛 + 궤도 고리가 Gemini 로고와 닮았다는 사용자 지적으로
 * 다섯 갈래 금빛 별로 바꿨다. 안드로이드 `ic_launcher_background.xml`/`ic_launcher_foreground.xml`과 같은 108x108 좌표·같은 색
 * (남색 바탕 + 은은한 금빛 + 둥근 모서리 별 + 작은 점별)을 그린다. 설치 파일 아이콘은 `packaging/app-icon.ico`(같은 그림).
 * 객체 이름(`SunriseIcon`)은 변경 범위를 줄이려고 그대로 유지 — 실제 그림만 교체.
 */
object SunriseIcon : Painter() {
    override val intrinsicSize = Size(108f, 108f)

    private val STAR_COLOR = Color(0xFFFFE39A)
    // 중심 (54, 56), 바깥 반지름 24, 안쪽 반지름 10.5 — 안드로이드 벡터와 같은 꼭짓점.
    private val STAR_POINTS = listOf(
        54f to 32f, 60.17f to 47.51f, 76.83f to 48.58f, 63.99f to 59.24f, 68.11f to 75.42f,
        54f to 66.5f, 39.89f to 75.42f, 44.01f to 59.24f, 31.17f to 48.58f, 47.83f to 47.51f
    )
    private val DOTS = listOf(
        Triple(31f, 34f, 1.4f), Triple(77f, 30f, 1.0f), Triple(74f, 79f, 1.5f),
        Triple(30f, 75f, 1.0f), Triple(84f, 62f, 0.8f), Triple(46f, 85f, 0.8f)
    )

    override fun DrawScope.onDraw() {
        val s = size.width / 108f
        fun pt(x: Float, y: Float) = Offset(x * s, y * s)

        drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF10182E), Color(0xFF0B0E14))), size = size)
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0x4DFFD97A), Color(0x00FFD97A)), center = pt(54f, 55f), radius = 26f * s),
            radius = 26f * s,
            center = pt(54f, 55f)
        )
        DOTS.forEach { (x, y, r) -> drawCircle(Color(0xFFC9D6F2), radius = r * s, center = pt(x, y)) }

        val star = Path().apply {
            STAR_POINTS.forEachIndexed { i, (x, y) -> val p = pt(x, y); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        drawPath(star, color = STAR_COLOR)
        // 같은 색 둥근 이음 선으로 모서리를 부드럽게(안드로이드 strokeLineJoin="round"와 같다).
        drawPath(star, color = STAR_COLOR, style = Stroke(width = 3.5f * s, join = StrokeJoin.Round))
    }
}
