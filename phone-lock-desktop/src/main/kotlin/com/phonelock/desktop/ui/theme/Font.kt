package com.phonelock.desktop.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * 앱 전체 폰트 — **에이투지체(A2Z) v1.001**(github.com/Freesentation/A2Z, OFL, 상업적 사용·재배포 가능, 안드로이드판과 같은 파일).
 *
 * 84차부터 SemiBold 한 벌만 쓰다가 144차 리디자인에서 굵기 4벌로 늘렸다(사용자 승인) — 큰 숫자·제목은 굵게, 본문은
 * 보통으로 굵기 대비를 만든다. 네 파일 모두 한글 11,172자 전체·같은 세로 메트릭·고정폭 숫자(타이머 숫자가 흔들리지 않음).
 * 등록하지 않은 굵기를 요청하면 가장 가까운 얼굴을 쓴다(Medium→Regular, Black→ExtraBold) — 합성 굵기는 생기지 않는다.
 */
val AppFontFamily: FontFamily = FontFamily(
    Font("font/A2zRegular.otf", FontWeight.W400),
    Font("font/A2zSemiBold.otf", FontWeight.W600),
    Font("font/A2zBold.otf", FontWeight.W700),
    Font("font/A2zExtraBold.otf", FontWeight.W800)
)
