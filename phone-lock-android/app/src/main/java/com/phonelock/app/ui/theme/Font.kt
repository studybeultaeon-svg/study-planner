package com.phonelock.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.phonelock.app.R

/**
 * 앱 전체 폰트(83차, 사용자 요청, 데스크탑판과 대칭). Gothic A1 → Pretendard → 여러 후보 비교 후
 * **카페24 써라운드**로 정착. "글자가 깨져 보인다"는 지적을 받아 원인을 찾아보니 두 가지였다 —
 * (1) 이 폰트의 OS/2 usWeightClass가 실제로는 700(Bold)인데 [FontWeight.Normal]로 등록해서 요청
 * 굵기와 실제 얼굴이 안 맞았고("볼드 빼봐" 요청을 문자 그대로 처리한 게 오히려 문제였음), (2) `.ttf`
 * (TrueType glyf 힌팅)가 Skia/안드로이드 렌더러와 잘 안 맞아 작은 크기에서 획이 뭉개졌다 — CFF
 * 외곽선을 쓰는 `.otf`로 바꿔서 해결. 이 폰트는 애초에 Bold 한 벌짜리 얼굴이므로 [FontWeight.Bold]로
 * 정확히 등록한다(굵기 자체를 없애는 게 아니라 이 폰트의 정체를 있는 그대로 알려주는 것).
 */
val AppFontFamily = FontFamily(
    Font(R.font.cafe24_ssurround, FontWeight.Bold)
)
