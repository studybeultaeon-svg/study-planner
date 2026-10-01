package com.phonelock.desktop.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

private fun mix(a: Color, b: Color, t: Float): Color = lerp(a, b, t)

/**
 * 팔레트 → Material 색 역할 전체(144차). 예전엔 일부 역할만 넘겨서 나머지(surfaceContainer*·secondaryContainer·
 * outlineVariant 등)가 Material 기본값(보랏빛 회색)으로 새어 다이얼로그·메뉴·구분선·선택 칩 색이 테마와 따로 놀았다.
 * - 바탕 계열: 라이트는 종이(background) 위에 흰 표면, 다크는 바탕 → 표면 → 옅은 바탕 순으로 한 단계씩 밝게.
 * - outline(입력칸·외곽선 버튼 테두리)은 보이는 굵기로, outlineVariant(구분선)는 팔레트의 가는 선(hairline)으로 나눈다.
 * - surfaceTint를 표면색과 같게 둬서 높이(elevation)에 따라 표면이 강조색으로 물들지 않게 한다(평평한 바탕 유지).
 */
private fun colorSchemeFor(palette: PhoneLockPalette): ColorScheme {
    val strongOutline = mix(palette.muted, palette.background, 0.42f)
    val containerHigh = if (palette.isDark) palette.surfaceAlt else palette.surface
    val containerHighest = if (palette.isDark) mix(palette.surfaceAlt, palette.onBackground, 0.06f) else palette.surfaceAlt
    val base = if (palette.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = palette.primary,
        onPrimary = palette.onPrimary,
        primaryContainer = palette.primaryContainer,
        onPrimaryContainer = palette.onBackground,
        inversePrimary = palette.primaryContainer,
        secondary = palette.secondary,
        onSecondary = palette.onSecondary,
        secondaryContainer = palette.primaryContainer,
        onSecondaryContainer = palette.onBackground,
        tertiary = palette.success,
        onTertiary = palette.background,
        tertiaryContainer = mix(palette.background, palette.success, 0.16f),
        onTertiaryContainer = palette.onBackground,
        background = palette.background,
        onBackground = palette.onBackground,
        surface = palette.surface,
        onSurface = palette.onBackground,
        surfaceVariant = palette.surfaceAlt,
        onSurfaceVariant = palette.muted,
        surfaceTint = palette.surface,
        inverseSurface = palette.onBackground,
        inverseOnSurface = palette.background,
        error = palette.error,
        onError = if (palette.isDark) palette.background else Color.White,
        errorContainer = palette.errorContainer,
        onErrorContainer = palette.onBackground,
        outline = strongOutline,
        outlineVariant = palette.outline,
        scrim = Color.Black,
        surfaceBright = palette.surface,
        surfaceDim = palette.surfaceAlt,
        surfaceContainerLowest = if (palette.isDark) palette.background else palette.surface,
        surfaceContainerLow = if (palette.isDark) mix(palette.background, palette.surface, 0.5f) else palette.surface,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = containerHigh,
        surfaceContainerHighest = containerHighest
    )
}

/**
 * 앱 테마(설정 화면에서 고름, ThemeMode 3종). 모든 화면은 MaterialTheme 대신 이걸로 감싼다.
 * themeMode를 생략하면 기본값(라이트+그린, 112차부터)을 쓴다.
 *
 * **주의**: 이 오버로드는 CUSTOM 테마를 표현하지 못한다([paletteFor]가 CUSTOM을 기본 팔레트로 흘려보낸다).
 * 실제 화면은 전부 아래 팔레트 오버로드에 [com.phonelock.desktop.data.Repository.currentPalette]를 넘겨 쓴다 —
 * 새 화면을 추가할 때도 그렇게 할 것. 안드로이드판에서 위젯/오버레이가 이 함정에 걸려 커스텀 테마인데도
 * 기본 초록색이 남아 있던 버그가 121차에 있었다.
 */
@Composable
fun PhoneLockTheme(themeMode: String = ThemeMode.DARK_BLUE, content: @Composable () -> Unit) {
    PhoneLockTheme(paletteFor(themeMode), content = content)
}

/** CUSTOM 테마처럼 미리 계산된 팔레트를 직접 넘길 때 쓰는 오버로드(79차, [Repository.currentPalette] 참고). */
@Composable
fun PhoneLockTheme(
    palette: PhoneLockPalette,
    /** 성능 모드(144차) — 미니멀 모드면 [Repository.currentPalette]가 흑백 팔레트를 주므로 그걸로 알아본다. */
    performanceMode: Boolean = palette == MonoPalette,
    content: @Composable () -> Unit
) {
    val motion = remember(performanceMode) { AppMotion(reduced = performanceMode) }
    MaterialTheme(
        colorScheme = remember(palette) { colorSchemeFor(palette) },
        shapes = PhoneLockShapes,
        typography = PhoneLockTypography
    ) {
        CompositionLocalProvider(
            LocalPerformanceMode provides performanceMode,
            LocalPhoneLockPalette provides palette,
            LocalAppMotion provides motion,
            content = content
        )
    }
}

/** 지금 테마의 팔레트 전체(144차, 안드로이드판과 같은 역할) — 경고·성공 색처럼 Material 색 역할에 없는 값을 꺼낼 때. */
val LocalPhoneLockPalette = staticCompositionLocalOf { LightGreenPalette }
