package com.phonelock.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private fun colorSchemeFor(palette: PhoneLockPalette) = if (palette.isDark) {
    darkColorScheme(
        background = palette.background,
        surface = palette.surface,
        surfaceVariant = palette.surfaceAlt,
        primary = palette.primary,
        primaryContainer = palette.primaryContainer,
        onPrimary = palette.onPrimary,
        onPrimaryContainer = palette.onBackground,
        secondary = palette.secondary,
        onSecondary = palette.onSecondary,
        tertiary = palette.success,
        onBackground = palette.onBackground,
        onSurface = palette.onBackground,
        onSurfaceVariant = palette.muted,
        error = palette.error,
        errorContainer = palette.errorContainer,
        onErrorContainer = palette.onBackground,
        outline = palette.outline
    )
} else {
    lightColorScheme(
        background = palette.background,
        surface = palette.surface,
        surfaceVariant = palette.surfaceAlt,
        primary = palette.primary,
        primaryContainer = palette.primaryContainer,
        onPrimary = palette.onPrimary,
        onPrimaryContainer = palette.onBackground,
        secondary = palette.secondary,
        onSecondary = palette.onSecondary,
        tertiary = palette.success,
        onBackground = palette.onBackground,
        onSurface = palette.onBackground,
        onSurfaceVariant = palette.muted,
        error = palette.error,
        errorContainer = palette.errorContainer,
        onErrorContainer = palette.onBackground,
        outline = palette.outline
    )
}

/**
 * 앱 테마(설정 화면에서 고름, ThemeMode 3종). 모든 화면은 MaterialTheme 대신 이걸로 감싼다.
 * themeMode를 생략하면 기본값(라이트+그린, 49차)을 쓴다.
 */
@Composable
fun PhoneLockTheme(
    themeMode: String = ThemeMode.LIGHT_GREEN,
    customBackground: String = "#FAFBF6",
    customAccent: String = "#8BC34A",
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val palette = if (themeMode == ThemeMode.CUSTOM) buildCustomPalette(customBackground, customAccent) else paletteFor(themeMode)
    MaterialTheme(
        colorScheme = colorSchemeFor(palette),
        shapes = PhoneLockShapes,
        typography = PhoneLockTypography
    ) {
        // 글자 크기 배율(82차, §6/§9) — 판정 로직과 무관한 순수 표시 설정. 기존 밀도는 유지하고
        // fontScale만 사용자가 고른 값으로 덮어쓴다.
        val baseDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
            content = content
        )
    }
}
