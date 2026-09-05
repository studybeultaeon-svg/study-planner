package com.phonelock.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.ui.theme.parseHexColor
import kotlin.math.roundToInt

/**
 * 커스텀 테마의 배경색/포인트색을 헥스 직접 입력 대신 눌러서 고를 수 있는 프리셋 팔레트(86차, 사용자
 * 요청 — 안드로이드판 ColorPaletteDialog.kt와 동일 팔레트/동작). 무채색 8개 + 주요 색상 계열별 밝음~
 * 어두움 몇 단계씩으로 구성 — 선택하면 [PhoneLockPalette] 자동 계산(buildCustomPalette)에 바로 쓸 수
 * 있는 "#RRGGBB" 문자열을 콜백으로 돌려준다. 헥스 텍스트필드는 그대로 남아있어 팔레트에 없는 색도
 * 여전히 직접 입력 가능.
 *
 * 사용자가 제공한 Windows "색 편집" 다이얼로그 이미지를 참고해(2026-09-05) 프리셋 그리드 위에 채도/명도
 * 사각형 + 색상(hue) 슬라이더를 추가했다 — 드래그하는 동안 onSelect를 계속 호출해 배경/포인트색이 실시간
 * 미리보기되도록(SettingsScreen의 onSelect가 이미 즉시 테마에 반영하는 구조를 그대로 활용). 프리셋 스와치
 * 클릭은 기존처럼 고르자마자 닫히는 동작을 유지한다.
 */
private val COLOR_PALETTE: List<Color> = listOf(
    // 무채색
    Color(0xFFFFFFFF), Color(0xFFF5F5F5), Color(0xFFE0E0E0), Color(0xFFBDBDBD),
    Color(0xFF9E9E9E), Color(0xFF616161), Color(0xFF333333), Color(0xFF000000),
    // 레드~핑크
    Color(0xFFFFCDD2), Color(0xFFEF5350), Color(0xFFE53935), Color(0xFFB71C1C),
    Color(0xFFF8BBD0), Color(0xFFEC407A), Color(0xFFC2185B),
    // 오렌지~옐로
    Color(0xFFFFE0B2), Color(0xFFFF9800), Color(0xFFEF6C00),
    Color(0xFFFFF9C4), Color(0xFFFDD835), Color(0xFFF9A825),
    // 그린~틸
    Color(0xFFDCEDC1), Color(0xFF8BC34A), Color(0xFF43A047), Color(0xFF1B5E20),
    Color(0xFFB2DFDB), Color(0xFF26A69A), Color(0xFF00695C),
    // 블루~시안
    Color(0xFFBBDEFB), Color(0xFF42A5F5), Color(0xFF1565C0),
    Color(0xFFB3E5FC), Color(0xFF29B6F6), Color(0xFF01579B),
    // 인디고~퍼플
    Color(0xFFD1C4E9), Color(0xFF7E57C2), Color(0xFF4527A0),
    Color(0xFFE1BEE7), Color(0xFFAB47BC), Color(0xFF6A1B9A),
    // 다크 배경용 톤(28차 다크+블루 팔레트에서 가져온 값)
    Color(0xFF0F1117), Color(0xFF1E2333), Color(0xFF283654)
)

private fun Color.toHexString(): String = String.format("#%06X", 0xFFFFFF and this.toArgb())

/** HSV -> Compose Color. android.graphics.Color를 안 쓰고 순수 계산으로 만들어서 안드로이드판과
 *  동일 코드를 그대로 쓸 수 있게 했다. */
private fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val c = v * s
    val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
    val m = v - c
    val (r1, g1, b1) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        (r1 + m).coerceIn(0f, 1f),
        (g1 + m).coerceIn(0f, 1f),
        (b1 + m).coerceIn(0f, 1f)
    )
}

/** Compose Color -> HSV(FloatArray[hue 0..360, sat 0..1, value 0..1]). */
private fun colorToHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val sat = if (max == 0f) 0f else delta / max
    return floatArrayOf(hue, sat, max)
}

@Composable
private fun SaturationValueBox(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (saturation: Float, value: Float) -> Unit
) {
    val hueColor = hsvToColor(hue, 1f, 1f)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(MaterialTheme.shapes.small)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        fun updateFromOffset(offset: Offset) {
            if (widthPx <= 0f || heightPx <= 0f) return
            val s = (offset.x / widthPx).coerceIn(0f, 1f)
            val v = 1f - (offset.y / heightPx).coerceIn(0f, 1f)
            onChange(s, v)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInputDragAndTap(::updateFromOffset)
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        }
        val thumbX = saturation * widthPx
        val thumbY = (1f - value) * heightPx
        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToInt() - 10, thumbY.roundToInt() - 10) }
                .size(20.dp)
                .border(2.dp, Color.White, CircleShape)
                .border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
        )
    }
}

@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    val hueGradient = remember {
        Brush.horizontalGradient((0..6).map { hsvToColor(it * 60f, 1f, 1f) })
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(MaterialTheme.shapes.small)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        fun updateFromX(x: Float) {
            if (widthPx <= 0f) return
            onChange((x / widthPx).coerceIn(0f, 1f) * 360f)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInputDragAndTap { updateFromX(it.x) }
        ) {
            drawRect(hueGradient)
        }
        val thumbX = (hue / 360f) * widthPx
        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToInt() - 3, 0) }
                .width(6.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.9f))
                .border(1.dp, Color.Black.copy(alpha = 0.35f))
        )
    }
}

private fun Modifier.pointerInputDragAndTap(onOffset: (Offset) -> Unit): Modifier = this
    .pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { onOffset(it) },
            onDrag = { change, _ -> onOffset(change.position); change.consume() }
        )
    }
    .pointerInput(Unit) {
        detectTapGestures { onOffset(it) }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPaletteDialog(
    title: String,
    currentHex: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val current = parseHexColor(currentHex)
    val initialHsv = remember { colorToHsv(current ?: Color(0xFFFFFFFF)) }
    var hue by remember { mutableStateOf(initialHsv[0]) }
    var saturation by remember { mutableStateOf(initialHsv[1]) }
    var brightness by remember { mutableStateOf(initialHsv[2]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                SaturationValueBox(
                    hue = hue,
                    saturation = saturation,
                    value = brightness,
                    onChange = { s, v ->
                        saturation = s
                        brightness = v
                        onSelect(hsvToColor(hue, s, v).toHexString())
                    }
                )
                Spacer(Modifier.height(8.dp))
                HueSlider(
                    hue = hue,
                    onChange = { h ->
                        hue = h
                        onSelect(hsvToColor(h, saturation, brightness).toHexString())
                    }
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    COLOR_PALETTE.forEach { c ->
                        val isSelected = current != null && c.toArgb() == current.toArgb()
                        Box(
                            Modifier
                                .size(36.dp)
                                .background(c, MaterialTheme.shapes.small)
                                .border(
                                    if (isSelected) 3.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    MaterialTheme.shapes.small
                                )
                                .clickable { onSelect(c.toHexString()); onDismiss() }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}
