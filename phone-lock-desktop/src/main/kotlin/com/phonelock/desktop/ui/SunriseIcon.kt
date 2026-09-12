package com.phonelock.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

/**
 * 트레이/창 아이콘용 새싹 그림(112차, 옛 일출 컨셉 대체). 안드로이드 `ic_launcher_background.xml`/
 * `ic_launcher_foreground.xml`과 같은 108x108 좌표·같은 디자인(하늘→연두 그라데이션 + 흙 언덕 + 줄기 +
 * 양쪽 잎)을 공유한다. 객체 이름(`SunriseIcon`)은 변경 범위를 줄이려고 그대로 유지 — 실제 그림만 교체.
 */
object SunriseIcon : Painter() {
    override val intrinsicSize = Size(108f, 108f)

    override fun DrawScope.onDraw() {
        val scale = size.width / 108f
        fun pt(x: Float, y: Float) = Offset(x * scale, y * scale)

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFA5D6E8), Color(0xFFDCEDC8), Color(0xFFC5E1A5))
            ),
            size = size
        )

        val hills = Path().apply {
            moveTo(pt(21f, 87f).x, pt(21f, 87f).y)
            lineTo(pt(21f, 76f).x, pt(21f, 76f).y)
            val c1 = pt(34f, 66f); val c2 = pt(74f, 66f); val e1 = pt(87f, 76f)
            cubicTo(c1.x, c1.y, c2.x, c2.y, e1.x, e1.y)
            lineTo(pt(87f, 87f).x, pt(87f, 87f).y)
            close()
        }
        drawPath(hills, color = Color(0xFF6D4C36))

        val stem = Path().apply {
            moveTo(pt(52f, 76f).x, pt(52f, 76f).y)
            val sc1 = pt(52f, 64f); val sc2 = pt(54f, 58f); val se = pt(54f, 50f)
            cubicTo(sc1.x, sc1.y, sc2.x, sc2.y, se.x, se.y)
            lineTo(pt(58f, 50f).x, pt(58f, 50f).y)
            val sc3 = pt(58f, 58f); val sc4 = pt(60f, 64f); val se2 = pt(60f, 76f)
            cubicTo(sc3.x, sc3.y, sc4.x, sc4.y, se2.x, se2.y)
            close()
        }
        drawPath(stem, color = Color(0xFF2E7D32))

        val leftLeaf = Path().apply {
            moveTo(pt(55f, 54f).x, pt(55f, 54f).y)
            val lc1 = pt(40f, 54f); val lc2 = pt(30f, 44f); val le1 = pt(30f, 32f)
            cubicTo(lc1.x, lc1.y, lc2.x, lc2.y, le1.x, le1.y)
            val lc3 = pt(46f, 32f); val lc4 = pt(56f, 40f); val le2 = pt(56f, 54f)
            cubicTo(lc3.x, lc3.y, lc4.x, lc4.y, le2.x, le2.y)
            close()
        }
        drawPath(leftLeaf, color = Color(0xFF66BB6A))

        val rightLeaf = Path().apply {
            moveTo(pt(57f, 50f).x, pt(57f, 50f).y)
            val rc1 = pt(72f, 50f); val rc2 = pt(82f, 40f); val re1 = pt(82f, 28f)
            cubicTo(rc1.x, rc1.y, rc2.x, rc2.y, re1.x, re1.y)
            val rc3 = pt(66f, 28f); val rc4 = pt(56f, 36f); val re2 = pt(56f, 50f)
            cubicTo(rc3.x, rc3.y, rc4.x, rc4.y, re2.x, re2.y)
            close()
        }
        drawPath(rightLeaf, color = Color(0xFF43A047))
    }
}
