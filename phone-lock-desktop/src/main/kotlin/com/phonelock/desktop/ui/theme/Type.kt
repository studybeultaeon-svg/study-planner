package com.phonelock.desktop.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 본문 줄바꿈 — 데스크탑(Skia)은 안드로이드의 어절(Phrase) 줄바꿈 설정을 받지 않아 문단 기본값을 쓴다(144차).
 * 한국어 어절이 갈라지는 자리는 화면 폭·글자 크기로 피한다(안드로이드판은 [LineBreak.WordBreak.Phrase]).
 */
private val KoreanParagraph = LineBreak.Paragraph

private fun style(
    size: TextUnit,
    lineHeight: TextUnit,
    weight: FontWeight,
    tracking: TextUnit,
    heading: Boolean
) = TextStyle(
    fontFamily = AppFontFamily,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = tracking,
    lineBreak = if (heading) LineBreak.Heading else KoreanParagraph,
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)
)

/**
 * 앱 전체 타이포그래피(144차 리디자인 "Ledger" — DECISIONS.md 144차, 안드로이드판과 같은 값).
 *
 * 위계는 크기만이 아니라 **굵기 대비**로 만든다: 큰 숫자·화면 제목은 ExtraBold, 항목 제목·버튼은 Bold,
 * 메타 정보는 SemiBold, 본문은 Regular. 큰 글자일수록 자간을 조금 좁혀(한글 헤드라인은 -2~-3%) 덩어리로
 * 읽히게 하고, 작은 글자는 0~+0.4로 둔다.
 *
 * 줄바꿈: 제목류는 [LineBreak.Heading](줄 길이를 고르게 — 마지막 줄에 한두 글자만 남는 현상을 줄인다),
 * 본문류는 어절 단위([KoreanParagraph]). 줄 높이는 위아래 여백을 고르게 나누고 첫/끝 줄을 자르지 않는다
 * ([LineHeightStyle.Trim.None]) — 한글과 영문·숫자가 섞여도 기준선이 한 줄 안에서 흔들리지 않는다.
 *
 * Material 슬롯 대응(화면 코드는 슬롯 이름을 그대로 쓴다):
 * - display* = 큰 숫자/히어로(타이머·레벨·통계 숫자), headline* = 화면 제목, title* = 섹션·항목 제목,
 *   body* = 본문, label* = 버튼·태그·메타 정보.
 */
val PhoneLockTypography = Typography(
    displayLarge = style(60.sp, 64.sp, FontWeight.W800, (-2.0).sp, heading = true),
    displayMedium = style(46.sp, 50.sp, FontWeight.W800, (-1.4).sp, heading = true),
    displaySmall = style(36.sp, 42.sp, FontWeight.W800, (-1.0).sp, heading = true),
    headlineLarge = style(32.sp, 38.sp, FontWeight.W800, (-0.8).sp, heading = true),
    headlineMedium = style(28.sp, 34.sp, FontWeight.W800, (-0.6).sp, heading = true),
    headlineSmall = style(23.sp, 30.sp, FontWeight.W700, (-0.4).sp, heading = true),
    titleLarge = style(20.sp, 27.sp, FontWeight.W700, (-0.3).sp, heading = true),
    titleMedium = style(16.sp, 22.sp, FontWeight.W700, (-0.1).sp, heading = true),
    titleSmall = style(14.sp, 20.sp, FontWeight.W600, 0.sp, heading = true),
    bodyLarge = style(16.sp, 24.sp, FontWeight.W400, 0.sp, heading = false),
    bodyMedium = style(14.sp, 21.sp, FontWeight.W400, 0.sp, heading = false),
    bodySmall = style(12.sp, 17.sp, FontWeight.W400, 0.1.sp, heading = false),
    labelLarge = style(14.sp, 20.sp, FontWeight.W700, 0.1.sp, heading = true),
    labelMedium = style(12.sp, 16.sp, FontWeight.W600, 0.2.sp, heading = true),
    labelSmall = style(11.sp, 14.sp, FontWeight.W600, 0.4.sp, heading = true)
)
