package com.phonelock.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 인터스티셜(전체화면 확인/차단) 패널에 쓰는 24dp 반경. Shapes 스케일에는 없어 별도로 둔다. */
val InterstitialPanelShape = RoundedCornerShape(24.dp)

/**
 * 모서리 반경(144차 리디자인) — 정보 덩어리는 카드로 감싸기보다 여백·가는 선으로 나누므로, 남는 표면(입력칸·
 * 묶음 목록·시트)만 이 스케일을 쓴다. 작을수록 정밀하게(10), 클수록 부드럽게(20~28). 버튼·칩은 알약형(Material 기본).
 */
val PhoneLockShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
