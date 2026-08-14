package com.phonelock.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 인터스티셜(전체화면 확인/차단) 패널에 쓰는 24dp 반경. Shapes 스케일에는 없어 별도로 둔다. */
val InterstitialPanelShape = RoundedCornerShape(24.dp)

val PhoneLockShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp)
)
