package com.phonelock.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 기본 Material3 타이포그래피에 따뜻한 느낌을 주기 위해 헤더 계열에 자간/굵기만 살짝 조정. */
val PhoneLockTypography = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.Medium)
)
