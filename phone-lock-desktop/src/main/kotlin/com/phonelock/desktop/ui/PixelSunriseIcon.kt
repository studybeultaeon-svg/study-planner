package com.phonelock.desktop.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 트레이/창 아이콘용 픽셀아트 일출 그림.
 * Android ic_launcher_background.xml / ic_launcher_foreground.xml과 동일한 108x108 좌표 그리드를 공유한다.
 */
object PixelSunriseIcon : Painter() {
    private data class PixelRect(val x: Float, val y: Float, val w: Float, val h: Float, val color: Color)

    private val rects = listOf(
        PixelRect(0f, 0f, 108f, 9f, Color(0xFF29B6F6)),
        PixelRect(0f, 9f, 108f, 9f, Color(0xFF4FC3F7)),
        PixelRect(0f, 18f, 108f, 9f, Color(0xFF81D4FA)),
        PixelRect(0f, 27f, 108f, 9f, Color(0xFFB3E5FC)),
        PixelRect(0f, 36f, 108f, 9f, Color(0xFFFFE0B2)),
        PixelRect(0f, 45f, 108f, 9f, Color(0xFFFFCC80)),
        PixelRect(0f, 54f, 108f, 9f, Color(0xFFFFB74D)),
        PixelRect(0f, 63f, 108f, 9f, Color(0xFFFFA726)),
        PixelRect(0f, 72f, 108f, 9f, Color(0xFFFF9800)),
        PixelRect(0f, 81f, 108f, 9f, Color(0xFFFB8C00)),
        PixelRect(0f, 90f, 108f, 9f, Color(0xFFF57C00)),
        PixelRect(0f, 99f, 108f, 9f, Color(0xFFEF6C00)),
        PixelRect(36f, 18f, 27f, 9f, Color(0xFFFFF176)),
        PixelRect(27f, 27f, 45f, 9f, Color(0xFFFFEE58)),
        PixelRect(27f, 36f, 45f, 9f, Color(0xFFFFCA28)),
        PixelRect(27f, 45f, 45f, 9f, Color(0xFFFFA726)),
        PixelRect(36f, 54f, 27f, 9f, Color(0xFFFF8F00)),
        PixelRect(18f, 9f, 9f, 9f, Color(0xFFFFF9C4)),
        PixelRect(72f, 9f, 9f, 9f, Color(0xFFFFF9C4)),
        PixelRect(18f, 63f, 9f, 9f, Color(0xFFFFF9C4)),
        PixelRect(72f, 63f, 9f, 9f, Color(0xFFFFF9C4)),
        PixelRect(9f, 72f, 18f, 9f, Color(0xFF1E293B)),
        PixelRect(81f, 72f, 18f, 9f, Color(0xFF1E293B)),
        PixelRect(0f, 81f, 108f, 27f, Color(0xFF1E293B)),
    )

    override val intrinsicSize = Size(108f, 108f)

    override fun DrawScope.onDraw() {
        val scale = size.width / 108f
        rects.forEach { r ->
            drawRect(
                color = r.color,
                topLeft = androidx.compose.ui.geometry.Offset(r.x * scale, r.y * scale),
                size = Size(r.w * scale, r.h * scale)
            )
        }
    }
}
