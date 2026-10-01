package com.phonelock.desktop.ui.theme

import androidx.compose.ui.graphics.Color

/** 테마 선택지(설정 화면에서 고름) — 안드로이드판과 동일, AppData.themeMode에 이 문자열로 저장된다.
 *  85차(사용자 요청): 53차/82차에 늘렸던 LAVENDER/MINT/ROSE/MIDNIGHT/FOREST/HIGH_CONTRAST 6종을
 *  제거하고 기본 3종(LIGHT_GREEN/DARK_BLUE/LIGHT_ORANGE)+CUSTOM만 남겼다. 기존에 이 값들로 저장돼
 *  있던 사용자는 [paletteFor]의 `else -> LightGreenPalette` 폴백으로 자동 복구된다. */
object ThemeMode {
    const val LIGHT_GREEN = "LIGHT_GREEN"
    const val DARK_BLUE = "DARK_BLUE"
    const val LIGHT_ORANGE = "LIGHT_ORANGE"
    /** 커스텀(79차, 사용자 요청) — 배경/포인트 두 색만 사용자가 고르면 나머지 팔레트 값은
     *  [buildCustomPalette]가 자동 계산한다. 실제 두 색은 AppData.customThemeBackground/customThemeAccent. */
    const val CUSTOM = "CUSTOM"

    /**
     * 미니멀 모드(130차) 전용 흑백 팔레트. **설정의 테마 목록에는 넣지 않는다** — 테마를 하나 더 고르는
     * 게 아니라 "미니멀 모드" 스위치를 켜면 골라둔 테마와 무관하게 이 팔레트가 강제되는 구조이기 때문이다
     * (85차에 테마 종류를 3개로 줄인 결정을 되돌리지 않으려는 의도). 스위치를 끄면 원래 고른 테마로 그대로 돌아온다.
     */
    const val MINIMAL = "MINIMAL"
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
    val outline: Color,
    /**
     * 그래프 막대·점·진행 막대처럼 "채우는" 상태 색(144차 후속, 사용자 지적 "라이트도 다크와 같은 색으로"). [success]/[warning]/[error]는
     * 라이트 테마에서 글자 대비(4.5:1)를 맞추느라 어둡게 잡혀 있어 막대에 쓰면 탁하고 다크와 딴판이었다. 채움은 테마와 무관하게 다크 테마의
     * 밝은 색을 그대로 쓰고(흑백 팔레트만 회색 농도), 글자에는 계속 success/warning/error를 쓴다.
     */
    val fillGood: Color = Color(0xFF3DD68C),
    val fillPartial: Color = Color(0xFFF5B83D),
    val fillBad: Color = Color(0xFFFF7A7A),
    /** 항목을 색으로 구분하는 그래프(분야별 도넛 등)의 색 — 앞에서부터 쓰고, 테마와 무관하게 같은 밝은 색이다. */
    val categorical: List<Color> = listOf(
        Color(0xFF6AA4FF), Color(0xFF3DD68C), Color(0xFFF5B83D), Color(0xFFA78BFA), Color(0xFFFF7A7A), Color(0xFF2DD4BF)
    )
)

// ── 144차 리디자인 "Ledger" 팔레트(DECISIONS.md 144차, 안드로이드판과 같은 값) ─────────────────────────
// 구조: 중립 바탕(종이/먹) + 테마마다 강조색 하나. 강조색은 "지금 여기"(선택 상태·진행·주요 행동)에만 쓰고
// 장식으로 칠하지 않는다. 테마 이름과 색 계열(그린/블루/오렌지)은 그대로 두고, 강조색 글씨가 바탕·옅은 바탕·
// 강조 바탕 위에서 모두 4.5:1 이상 읽히도록 값을 다시 잡았다(이전 그린·오렌지는 강조색 글씨가 약 2:1이었다).
// 성공/경고/오류도 라이트 테마에선 글씨로 읽히는 진한 값(4.5:1 이상)을 쓴다.

// 라이트 · 그린 — 옅은 풀빛 종이 + 짙은 풀색 강조.
val LightGreenPalette = PhoneLockPalette(
    isDark = false,
    background = Color(0xFFF4F5EF),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEAEDE3),
    primary = Color(0xFF36701A),
    primaryContainer = Color(0xFFDDEFC6),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2B5A12),
    onSecondary = Color(0xFFFFFFFF),
    success = Color(0xFF2E7D32),
    warning = Color(0xFFB45309),
    warningContainer = Color(0xFFFDF0D2),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFDE4E1),
    onBackground = Color(0xFF141A10),
    muted = Color(0xFF5C6656),
    outline = Color(0xFFDCE0D4)
)

// 다크 · 블루 — 먹색에 가까운 남색 바탕 + 밝은 파랑 강조. 보조색은 예전의 보라 대신 같은 계열의 옅은 파랑으로
// 둬서 "파랑→보라" 조합을 없앴다.
val DarkBluePalette = PhoneLockPalette(
    isDark = true,
    background = Color(0xFF0B0E14),
    surface = Color(0xFF141922),
    surfaceAlt = Color(0xFF1B212C),
    primary = Color(0xFF6AA4FF),
    primaryContainer = Color(0xFF1C2C47),
    onPrimary = Color(0xFF07101F),
    secondary = Color(0xFF9CC3FF),
    onSecondary = Color(0xFF07101F),
    success = Color(0xFF3DD68C),
    warning = Color(0xFFF5B83D),
    warningContainer = Color(0xFF3A2F14),
    error = Color(0xFFFF7A7A),
    errorContainer = Color(0xFF3D1C1F),
    onBackground = Color(0xFFEEF1F6),
    muted = Color(0xFF98A2B3),
    outline = Color(0xFF252C38)
)

// 화이트 · 오렌지 — 따뜻한 미색 종이 + 짙은 주황 강조.
val LightOrangePalette = PhoneLockPalette(
    isDark = false,
    background = Color(0xFFFAF6F0),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF2EADF),
    primary = Color(0xFFA84F00),
    primaryContainer = Color(0xFFFCE3C8),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF8A3F00),
    onSecondary = Color(0xFFFFFFFF),
    success = Color(0xFF2E7D32),
    warning = Color(0xFFB45309),
    warningContainer = Color(0xFFFDF0D2),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFDE4E1),
    onBackground = Color(0xFF1F160C),
    muted = Color(0xFF6E5E4E),
    outline = Color(0xFFE6DCCD)
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
 * 안드로이드판과 동일 알고리즘 — DECISIONS.md 참고.
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
    val outline = blend(onBackground, background, 0.86f)
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
        warning = if (isDark) Color(0xFFF5B83D) else Color(0xFFB45309),
        warningContainer = if (isDark) Color(0xFF3A331A) else Color(0xFFFEF3C7),
        error = if (isDark) Color(0xFFF87171) else Color(0xFFE53935),
        errorContainer = if (isDark) Color(0xFF3A2020) else Color(0xFFFEE2E2),
        onBackground = onBackground,
        muted = muted,
        outline = outline
    )
}

/**
 * 미니멀 모드(130차) 흑백 팔레트 — 색으로 시선을 끄는 자리를 전부 없앤다. 경고/에러만 회색 농도로
 * 구분해서(빨강 대신 가장 진한 먹색) "위험한 동작"이라는 신호 자체는 남긴다.
 */
val MonoPalette = PhoneLockPalette(
    isDark = false,
    // 144차: 바탕을 아주 옅은 회색으로 — 흰 표면(묶음·시트)이 바탕과 구분돼야 테두리 없이도 묶음이 보인다.
    background = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEDEDED),
    primary = Color(0xFF111111),
    primaryContainer = Color(0xFFE6E6E6),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3D3D3D),
    onSecondary = Color(0xFFFFFFFF),
    success = Color(0xFF333333),
    warning = Color(0xFF555555),
    warningContainer = Color(0xFFEDEDED),
    error = Color(0xFF000000),
    errorContainer = Color(0xFFE0E0E0),
    onBackground = Color(0xFF111111),
    muted = Color(0xFF666666),
    outline = Color(0xFFE2E2E2),
    // 흑백 모드의 채움은 농도로만 구분한다 — 다 함(먹) > 일부(중간 회색) > 안 함(옅은 회색).
    fillGood = Color(0xFF111111),
    fillPartial = Color(0xFF8A8A8A),
    fillBad = Color(0xFFC8C8C8),
    categorical = listOf(Color(0xFF111111), Color(0xFF555555), Color(0xFF8A8A8A), Color(0xFFB0B0B0), Color(0xFF333333), Color(0xFFD0D0D0))
)

fun paletteFor(themeMode: String): PhoneLockPalette = when (themeMode) {
    ThemeMode.DARK_BLUE -> DarkBluePalette
    ThemeMode.LIGHT_ORANGE -> LightOrangePalette
    ThemeMode.MINIMAL -> MonoPalette
    else -> LightGreenPalette
}

/**
 * 커스텀 테마까지 포함해 "지금 선택된 테마의 완성된 팔레트"를 돌려주는 유일한 창구(121차) — Compose
 * 밖(위젯 RemoteViews, 접근성 오버레이, 액티비티 창 배경)에서도 테마 색을 써야 하는데, 그동안
 * [paletteFor] 한 인자 버전만 부르는 자리들이 CUSTOM을 `else ->` 로 흘려 라이트+그린 기본 팔레트를
 * 보여주고 있었다(사용자 지적: "위젯·오버레이에만 초록 기본 테마가 남아있다"). 앞으로 테마 색이 필요한
 * 코드는 반드시 이 함수(또는 이걸 부르는 `Repository.currentPalette()`)를 쓴다.
 */
fun paletteFor(themeMode: String, customBackgroundHex: String, customAccentHex: String): PhoneLockPalette =
    if (themeMode == ThemeMode.CUSTOM) buildCustomPalette(customBackgroundHex, customAccentHex) else paletteFor(themeMode)

/** 설정 화면 테마 선택 UI가 순서대로 나열할 때 쓰는 표시 이름 매핑. */
// 146차(사용자 요청): 기본 테마가 다크 · 블루가 되면서 목록 맨 앞으로. 버튼 글자의 이모지는 뺐다(Ledger 규칙).
val THEME_DISPLAY_NAMES: List<Pair<String, String>> = listOf(
    ThemeMode.DARK_BLUE to "다크 · 블루",
    ThemeMode.LIGHT_GREEN to "라이트 · 그린",
    ThemeMode.LIGHT_ORANGE to "화이트 · 오렌지",
    ThemeMode.CUSTOM to "커스텀"
)
