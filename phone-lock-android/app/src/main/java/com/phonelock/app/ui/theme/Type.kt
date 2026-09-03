package com.phonelock.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

/**
 * 앱 전체 타이포그래피 — 83차(사용자 요청)부터 [AppFontFamily](카페24 써라운드)를 전 스타일에 적용
 * (데스크탑판과 대칭). 이 폰트는 OS/2 메타데이터상 실제로 Bold(700) 한 벌짜리 얼굴이라(디스플레이/로고용
 * 으로 설계된 폰트라 그렇다) 전 스타일을 [FontWeight.Bold]로 맞춰 등록된 얼굴과 요청 굵기가 항상
 * 일치하게 했다 — 처음엔 "볼드 빼봐" 요청으로 Normal을 요청했는데, 그러면 오히려 없는 얼굴(진짜
 * Normal)을 합성하려다 글자가 깨져 보였다(자세한 경위는 [AppFontFamily] 주석 참고). 텍스트 크기
 * 차이로 위계는 여전히 유지된다.
 */
val PhoneLockTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    displayMedium = base.displayMedium.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    displaySmall = base.displaySmall.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    titleSmall = base.titleSmall.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    bodyLarge = base.bodyLarge.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    bodyMedium = base.bodyMedium.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    bodySmall = base.bodySmall.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    labelLarge = base.labelLarge.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    labelMedium = base.labelMedium.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    labelSmall = base.labelSmall.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold)
)
