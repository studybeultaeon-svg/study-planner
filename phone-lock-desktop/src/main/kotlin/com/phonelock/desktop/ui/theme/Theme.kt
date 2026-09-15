package com.phonelock.desktop.ui.theme

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
 * themeMode를 생략하면 기본값(라이트+그린, 112차부터)을 쓴다.
 *
 * **주의**: 이 오버로드는 CUSTOM 테마를 표현하지 못한다([paletteFor]가 CUSTOM을 기본 팔레트로 흘려보낸다).
 * 실제 화면은 전부 아래 팔레트 오버로드에 [com.phonelock.desktop.data.Repository.currentPalette]를 넘겨 쓴다 —
 * 새 화면을 추가할 때도 그렇게 할 것. 안드로이드판에서 위젯/오버레이가 이 함정에 걸려 커스텀 테마인데도
 * 기본 초록색이 남아 있던 버그가 121차에 있었다.
 */
@Composable
fun PhoneLockTheme(themeMode: String = ThemeMode.LIGHT_GREEN, content: @Composable () -> Unit) {
    PhoneLockTheme(paletteFor(themeMode), content)
}

/** CUSTOM 테마처럼 미리 계산된 팔레트를 직접 넘길 때 쓰는 오버로드(79차, [Repository.currentPalette] 참고). */
@Composable
fun PhoneLockTheme(palette: PhoneLockPalette, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorSchemeFor(palette),
        shapes = PhoneLockShapes,
        typography = PhoneLockTypography,
        content = content
    )
}
