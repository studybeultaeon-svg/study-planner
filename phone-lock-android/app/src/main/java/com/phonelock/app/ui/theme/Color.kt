package com.phonelock.app.ui.theme

import androidx.compose.ui.graphics.Color

/** 테마 선택지(설정 화면에서 고름) — 데스크탑판과 동일, AppData.themeMode에 이 문자열로 저장된다.
 *  53차에 사용자 요청으로 5종 추가(LAVENDER/MINT/ROSE/MIDNIGHT/FOREST). */
object ThemeMode {
    const val LIGHT_GREEN = "LIGHT_GREEN"
    const val DARK_BLUE = "DARK_BLUE"
    const val LIGHT_ORANGE = "LIGHT_ORANGE"
    const val LAVENDER = "LAVENDER"
    const val MINT = "MINT"
    const val ROSE = "ROSE"
    const val MIDNIGHT = "MIDNIGHT"
    const val FOREST = "FOREST"
    /** 고대비(82차, 감사보고서 §6/§9 접근성 신규) — 순검정 배경+순백 텍스트, 포인트색도 최대 채도로. */
    const val HIGH_CONTRAST = "HIGH_CONTRAST"
    /** 커스텀(79차, 사용자 요청) — 배경/포인트 두 색만 사용자가 고르면 나머지 팔레트 값은
     *  [buildCustomPalette]가 자동 계산한다. 실제 두 색은 AppPreferences.customThemeBackground/customThemeAccent. */
    const val CUSTOM = "CUSTOM"
}

/** 팔레트 하나가 채워야 하는 색상 집합 — PhoneLockColorScheme(Theme.kt)이 그대로 매핑한다. */
data class PhoneLockPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val primary: Color,
    val primaryContainer: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val success: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val onBackground: Color,
    val muted: Color,
    val outline: Color
)

// 라이트+그린 팔레트(47차 방향 확정, 48차 착수 후 실제 톤 확정, 49차부터 기본값) — 다크+파랑(28차/35차)을 뒤집는다.
// 데스크탑판과 동일 값 유지. 배경은 순백 대신 아주 옅은 그린 틴트를 준 오프화이트, 포인트 색은
// 밝은 연두(Primary)를 쓰되 그 색 위 텍스트(onPrimary)는 대비를 위해 흰색 대신 짙은 그린빛
// 다크 톤을 쓴다.
val LightGreenPalette = PhoneLockPalette(
    isDark = false,
    background = Color(0xFFFAFBF6),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF1F5E9),
    primary = Color(0xFF8BC34A),
    primaryContainer = Color(0xFFDCEDC1),
    onPrimary = Color(0xFF20261A),
    secondary = Color(0xFF558B2F),
    onSecondary = Color(0xFFFAFBF6),
    success = Color(0xFF43A047),
    warning = Color(0xFFF59E0B),
    warningContainer = Color(0xFFFEF3C7),
    error = Color(0xFFE53935),
    errorContainer = Color(0xFFFEE2E2),
    onBackground = Color(0xFF20261A),
    muted = Color(0xFF6B7566),
    outline = Color(0xFFD9E2CB)
)

// 다크+파랑 팔레트(28차 세션 적용분, 35차에 하드코딩 accent만 주황→파랑 통일) — 공부앱(index.html) 다크
// 팔레트를 그대로 이식한 원본 값: 배경 #0f1117 / 카드 #1e2333 / 포인트 파랑 #4f8ef7 / 보조 보라 #a78bfa /
// 성공 #34d399 / 경고 #fbbf24 / 에러 #f87171.
val DarkBluePalette = PhoneLockPalette(
    isDark = true,
    background = Color(0xFF0F1117),
    surface = Color(0xFF1E2333),
    surfaceAlt = Color(0xFF1E2333),
    primary = Color(0xFF4F8EF7),
    primaryContainer = Color(0xFF283654),
    onPrimary = Color(0xFF0F1117),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF0F1117),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF3A331A),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF3A2020),
    onBackground = Color(0xFFE5E7EB),
    muted = Color(0xFF9CA3AF),
    outline = Color(0xFF2A3142)
)

// 화이트+오렌지 팔레트(28차 개편 이전, 앱 최초 테마) — 정확한 원본 hex는 커밋 이력이 남아있지 않아
// 그대로 복원할 수 없다(2026-08-13 확인). 유일하게 문서에 정확히 남아있던 값은 강조색 #FF9800(35차
// DECISIONS.md 기록)뿐이라, 이를 기준으로 따뜻한 라이트 톤을 재구성했다 — 배경/카드 톤은 근사치다.
val LightOrangePalette = PhoneLockPalette(
    isDark = false,
    background = Color(0xFFFFF8F0),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFFBEBD9),
    primary = Color(0xFFFF9800),
    primaryContainer = Color(0xFFFFE0B2),
    onPrimary = Color(0xFF2E1E00),
    secondary = Color(0xFFE65100),
    onSecondary = Color(0xFFFFF8F0),
    success = Color(0xFF43A047),
    warning = Color(0xFFF59E0B),
    warningContainer = Color(0xFFFEF3C7),
    error = Color(0xFFE53935),
    errorContainer = Color(0xFFFEE2E2),
    onBackground = Color(0xFF2E2114),
    muted = Color(0xFF8A7360),
    outline = Color(0xFFE8D5BE)
)

// 라벤더(라이트+퍼플, 53차 신규): 은은한 라일락 배경에 진보라 포인트.
val LavenderPalette = PhoneLockPalette(
    isDark = false,
    background = Color(0xFFF8F6FC),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF0EBFA),
    primary = Color(0xFF9575CD),
    primaryContainer = Color(0xFFE1D5F5),
    onPrimary = Color(0xFF2A1B47),
    secondary = Color(0xFF7E57C2),
    onSecondary = Color(0xFFF8F6FC),
    success = Color(0xFF43A047),
    warning = Color(0xFFF59E0B),
    warningContainer = Color(0xFFFEF3C7),
    error = Color(0xFFE53935),
    errorContainer = Color(0xFFFEE2E2),
    onBackground = Color(0xFF2A1B47),
    muted = Color(0xFF7C6E94),
    outline = Color(0xFFDCD0EF)
)

// 민트(라이트+틸, 53차 신규): 청량한 민트 배경에 딥틸 포인트.
val MintPalette = PhoneLockPalette(
    isDark = false,
    background = Color(0xFFF0FBF9),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFDFF5F1),
    primary = Color(0xFF26A69A),
    primaryContainer = Color(0xFFB2EAE2),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF00897B),
    onSecondary = Color(0xFFF0FBF9),
    success = Color(0xFF43A047),
    warning = Color(0xFFF59E0B),
    warningContainer = Color(0xFFFEF3C7),
    error = Color(0xFFE53935),
    errorContainer = Color(0xFFFEE2E2),
    onBackground = Color(0xFF143330),
    muted = Color(0xFF5C7A76),
    outline = Color(0xFFC5E6E0)
)

// 로즈(라이트+핑크, 53차 신규): 부드러운 로즈 배경에 진분홍 포인트.
val RosePalette = PhoneLockPalette(
    isDark = false,
    background = Color(0xFFFFF5F8),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFFCE4EC),
    primary = Color(0xFFEC407A),
    primaryContainer = Color(0xFFFBD3E1),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFD81B60),
    onSecondary = Color(0xFFFFF5F8),
    success = Color(0xFF43A047),
    warning = Color(0xFFF59E0B),
    warningContainer = Color(0xFFFEF3C7),
    error = Color(0xFFE53935),
    errorContainer = Color(0xFFFEE2E2),
    onBackground = Color(0xFF3D0F20),
    muted = Color(0xFF8C6270),
    outline = Color(0xFFF5CEDD)
)

// 미드나잇(다크+퍼플, 53차 신규): 짙은 남보라 배경에 라이트퍼플 포인트.
val MidnightPalette = PhoneLockPalette(
    isDark = true,
    background = Color(0xFF14101F),
    surface = Color(0xFF201A32),
    surfaceAlt = Color(0xFF201A32),
    primary = Color(0xFFB388FF),
    primaryContainer = Color(0xFF3B2E5C),
    onPrimary = Color(0xFF14101F),
    secondary = Color(0xFF7C4DFF),
    onSecondary = Color(0xFF14101F),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF3A331A),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF3A2020),
    onBackground = Color(0xFFE8E1F5),
    muted = Color(0xFFA399BD),
    outline = Color(0xFF332A4D)
)

// 포레스트(다크+그린, 53차 신규): 짙은 숲 배경에 산뜻한 연두 포인트.
val ForestPalette = PhoneLockPalette(
    isDark = true,
    background = Color(0xFF0F1712),
    surface = Color(0xFF1B2620),
    surfaceAlt = Color(0xFF1B2620),
    primary = Color(0xFF66BB6A),
    primaryContainer = Color(0xFF26402B),
    onPrimary = Color(0xFF0F1712),
    secondary = Color(0xFF81C784),
    onSecondary = Color(0xFF0F1712),
    success = Color(0xFF66BB6A),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF3A331A),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF3A2020),
    onBackground = Color(0xFFE3EFE5),
    muted = Color(0xFF90A896),
    outline = Color(0xFF2A3B2F)
)

// 고대비(82차 신규): WCAG 대비를 최우선으로 — 순검정 배경/순백 텍스트, 포인트는 시인성 높은 노랑.
// warning/error도 배경과 최대한 멀리 떨어뜨려 색약/저시력 환경에서도 구분되게 했다.
val HighContrastPalette = PhoneLockPalette(
    isDark = true,
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceAlt = Color(0xFF1A1A1A),
    primary = Color(0xFFFFD600),
    primaryContainer = Color(0xFF4D4000),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF00E5FF),
    onSecondary = Color(0xFF000000),
    success = Color(0xFF00E676),
    warning = Color(0xFFFFD600),
    warningContainer = Color(0xFF4D4000),
    error = Color(0xFFFF5252),
    errorContainer = Color(0xFF4D0000),
    onBackground = Color(0xFFFFFFFF),
    muted = Color(0xFFCCCCCC),
    outline = Color(0xFFFFFFFF)
)

/** "#RRGGBB"(또는 "RRGGBB") 문자열을 [Color]로 파싱, 실패하면 null. */
fun parseHexColor(hex: String): Color? = runCatching {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6) return null
    Color(0xFF000000.toInt() or clean.toLong(16).toInt())
}.getOrNull()

private fun blend(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f
)

private fun luminance(c: Color): Float = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue

/**
 * 커스텀 테마(79차, 사용자 요청) — 배경색/포인트색 두 개만으로 나머지 팔레트 필드를 자동 계산한다.
 * 데스크탑판과 동일 알고리즘 — DECISIONS.md 참고.
 */
fun buildCustomPalette(backgroundHex: String, accentHex: String): PhoneLockPalette {
    val background = parseHexColor(backgroundHex) ?: Color(0xFFFAFBF6)
    val primary = parseHexColor(accentHex) ?: Color(0xFF8BC34A)
    val isDark = luminance(background) < 0.5f

    val onBackground = if (isDark) blend(background, Color.White, 0.85f) else blend(background, Color.Black, 0.85f)
    val onPrimary = if (luminance(primary) < 0.5f) Color.White else Color.Black
    val surface = if (isDark) blend(background, Color.White, 0.10f) else Color.White
    val surfaceAlt = if (isDark) surface else blend(background, primary, 0.12f)
    val primaryContainer = if (isDark) blend(background, primary, 0.35f) else blend(primary, Color.White, 0.7f)
    val secondary = if (isDark) blend(primary, Color.White, 0.15f) else blend(primary, Color.Black, 0.2f)
    val onSecondary = background
    val outline = blend(onBackground, background, 0.85f)
    val muted = blend(onBackground, background, 0.5f)

    return PhoneLockPalette(
        isDark = isDark,
        background = background,
        surface = surface,
        surfaceAlt = surfaceAlt,
        primary = primary,
        primaryContainer = primaryContainer,
        onPrimary = onPrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        success = if (isDark) Color(0xFF34D399) else Color(0xFF43A047),
        warning = if (isDark) Color(0xFFFBBF24) else Color(0xFFF59E0B),
        warningContainer = if (isDark) Color(0xFF3A331A) else Color(0xFFFEF3C7),
        error = if (isDark) Color(0xFFF87171) else Color(0xFFE53935),
        errorContainer = if (isDark) Color(0xFF3A2020) else Color(0xFFFEE2E2),
        onBackground = onBackground,
        muted = muted,
        outline = outline
    )
}

fun paletteFor(themeMode: String): PhoneLockPalette = when (themeMode) {
    ThemeMode.DARK_BLUE -> DarkBluePalette
    ThemeMode.LIGHT_ORANGE -> LightOrangePalette
    ThemeMode.LAVENDER -> LavenderPalette
    ThemeMode.MINT -> MintPalette
    ThemeMode.ROSE -> RosePalette
    ThemeMode.MIDNIGHT -> MidnightPalette
    ThemeMode.FOREST -> ForestPalette
    ThemeMode.HIGH_CONTRAST -> HighContrastPalette
    else -> LightGreenPalette
}

/** 설정 화면 테마 선택 UI가 순서대로 나열할 때 쓰는 표시 이름 매핑. */
val THEME_DISPLAY_NAMES: List<Pair<String, String>> = listOf(
    ThemeMode.LIGHT_GREEN to "라이트 · 그린",
    ThemeMode.DARK_BLUE to "다크 · 블루",
    ThemeMode.LIGHT_ORANGE to "화이트 · 오렌지",
    ThemeMode.LAVENDER to "라벤더 · 퍼플",
    ThemeMode.MINT to "민트 · 틸",
    ThemeMode.ROSE to "로즈 · 핑크",
    ThemeMode.MIDNIGHT to "미드나잇 · 퍼플",
    ThemeMode.FOREST to "포레스트 · 그린",
    ThemeMode.HIGH_CONTRAST to "고대비",
    ThemeMode.CUSTOM to "🎨 커스텀"
)
