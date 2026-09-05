package com.phonelock.desktop.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * 앱 전체 폰트(84차, 사용자 요청). 카페24 써라운드 → **에이투지체(A2z) SemiBold**로 교체
 * (noonnu.cc/font_page/1778, OFL, 상업적 사용/재배포 가능). 9단계 굵기 패밀리 중 SemiBold(OS/2
 * usWeightClass=600) 한 파일만 받아 등록했으므로 [FontWeight.W600]으로 정확히 맞춘다(굵기 불일치 시
 * Skia가 합성 굵기를 만들어 글자가 깨져 보이는 문제는 카페24 써라운드 적용 때 이미 겪었다 —
 * [PhoneLockTypography] 참고). `.otf`(CFF 외곽선)로 통일해 이전 폰트와 동일한 렌더링 안정성을 유지한다.
 */
val AppFontFamily: FontFamily = FontFamily(
    Font("font/A2zSemiBold.otf", FontWeight.W600)
)
