package com.phonelock.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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
    content: @Composable () -> Unit
) {
    val palette = if (themeMode == ThemeMode.CUSTOM) buildCustomPalette(customBackground, customAccent) else paletteFor(themeMode)
    MaterialTheme(
        colorScheme = colorSchemeFor(palette),
        shapes = PhoneLockShapes,
        typography = PhoneLockTypography,
        content = content
    )
}
