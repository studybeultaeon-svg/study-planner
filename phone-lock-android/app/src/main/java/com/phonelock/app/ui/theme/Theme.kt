package com.phonelock.app.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.phonelock.app.data.AppPreferences

private fun mix(a: Color, b: Color, t: Float): Color = lerp(a, b, t)

/**
 * 팔레트 → Material 색 역할 전체(144차). 예전엔 일부 역할만 넘겨서 나머지(surfaceContainer*·secondaryContainer·
 * outlineVariant 등)가 Material 기본값(보랏빛 회색)으로 새어 다이얼로그·메뉴·구분선·선택 칩 색이 테마와 따로 놀았다.
 * - 바탕 계열: 라이트는 종이(background) 위에 흰 표면, 다크는 바탕 → 표면 → 옅은 바탕 순으로 한 단계씩 밝게.
 * - outline(입력칸·외곽선 버튼 테두리)은 보이는 굵기로, outlineVariant(구분선)는 팔레트의 가는 선(hairline)으로 나눈다.
 * - surfaceTint를 표면색과 같게 둬서 높이(elevation)에 따라 표면이 강조색으로 물들지 않게 한다(평평한 바탕 유지).
 */
internal fun colorSchemeFor(palette: PhoneLockPalette): ColorScheme {
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
 * themeMode를 생략하면 기본값(화이트+오렌지, 104차 후속)을 쓴다.
 */
@Composable
fun PhoneLockTheme(
    themeMode: String = ThemeMode.LIGHT_ORANGE,
    customBackground: String = "#FAFBF6",
    customAccent: String = "#8BC34A",
    fontScale: Float = 1.0f,
    /** 성능 모드(144차) — 기본은 미니멀 모드(흑백 팔레트)일 때. 흑백을 쓰지 않는 잠금 화면 액티비티는 직접 넘긴다. */
    performanceMode: Boolean = themeMode == ThemeMode.MINIMAL,
    content: @Composable () -> Unit
) {
    val palette = paletteFor(themeMode, customBackground, customAccent)
    val systemReducedMotion = rememberSystemReducedMotion()
    val motion = remember(performanceMode, systemReducedMotion) { AppMotion(reduced = performanceMode || systemReducedMotion) }
    MaterialTheme(
        colorScheme = remember(palette) { colorSchemeFor(palette) },
        shapes = PhoneLockShapes,
        typography = PhoneLockTypography
    ) {
        // 글자 크기 배율(82차, §6/§9) — 판정 로직과 무관한 순수 표시 설정. 기존 밀도는 유지하고
        // fontScale만 사용자가 고른 값으로 덮어쓴다.
        val baseDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
            LocalPerformanceMode provides performanceMode,
            LocalPhoneLockPalette provides palette,
            LocalAppMotion provides motion,
            content = content
        )
    }
}

/**
 * 지금 테마의 팔레트 전체(144차) — Material 색 역할에 없는 경고(warning/warningContainer)·성공(success)처럼 화면이
 * 직접 써야 하는 값을 하드코딩(#34D399 같은 고정색은 라이트 테마에서 글씨가 2:1 수준으로 흐려진다) 대신 여기서 꺼낸다.
 */
val LocalPhoneLockPalette = staticCompositionLocalOf { LightGreenPalette }

/** 시스템 설정의 "애니메이션 제거"(애니메이터 배율 0)가 켜져 있으면 true — 접근성 설정을 모션 시스템이 존중한다. */
@Composable
private fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
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
