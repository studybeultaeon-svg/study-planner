package com.phonelock.app.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.phonelock.app.data.AppPreferences

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
 * themeMode를 생략하면 기본값(화이트+오렌지, 104차 후속)을 쓴다.
 */
@Composable
fun PhoneLockTheme(
    themeMode: String = ThemeMode.LIGHT_ORANGE,
    customBackground: String = "#FAFBF6",
    customAccent: String = "#8BC34A",
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val palette = paletteFor(themeMode, customBackground, customAccent)
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

/**
 * 액티비티 창(window) 배경을 현재 테마의 배경색으로 칠한다(121차, 사용자 지적 "테마가 일부 화면에만
 * 적용된다") — `Theme.PhoneLock`/`Theme.PhoneLock.Overlay`가 `android:Theme.Material.Light`를 상속해서
 * 창 바탕이 항상 흰색이었고, 다크/커스텀(어두운 배경) 테마에서는 Compose가 첫 프레임을 그리기 전과
 * 화면 전환 애니메이션 중에 그 흰색이 그대로 비쳤다. `setContent` 직전에 부른다.
 */
fun Activity.applyThemeWindowBackground(preferences: AppPreferences) {
    window.setBackgroundDrawable(ColorDrawable(preferences.currentPalette().background.toArgb()))
}
