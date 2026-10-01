package com.phonelock.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.phonelock.app.R

/**
 * 앱 전체 폰트 — **에이투지체(A2Z) v1.001**(github.com/Freesentation/A2Z, OFL, 상업적 사용·재배포 가능).
 *
 * 84차부터 SemiBold 한 벌만 쓰다가 144차 리디자인에서 굵기 4벌로 늘렸다(사용자 승인) — 큰 숫자·제목은 굵게,
 * 본문은 보통으로 굵기 대비를 만들어야 정보 위계가 크기에만 기대지 않는다. 네 파일 모두 한글 11,172자 전체,
 * 같은 세로 메트릭(UPM 900, ascent 880/descent 200), **고정폭 숫자**(굵기마다 0~9 너비가 같다 — 타이머
 * 숫자가 바뀔 때 글자가 흔들리지 않는다)를 확인했다.
 *
 * 등록하지 않은 굵기를 요청하면 가장 가까운 얼굴을 쓴다(Medium→Regular, Black→ExtraBold). 합성 굵기는
 * "요청 ≥ 600인데 얼굴 < 600"일 때만 생기므로 이 네 벌로는 생기지 않는다(카페24 써라운드 때 겪은 깨짐 방지).
 */
val AppFontFamily = FontFamily(
    Font(R.font.a2z_regular, FontWeight.W400),
    Font(R.font.a2z_semibold, FontWeight.W600),
    Font(R.font.a2z_bold, FontWeight.W700),
    Font(R.font.a2z_extrabold, FontWeight.W800)
)
