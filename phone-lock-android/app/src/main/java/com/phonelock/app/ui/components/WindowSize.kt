package com.phonelock.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * sw600dp 이상이면 태블릿으로 취급(83차, 기존 InterstitialScreen.kt/RoutineScreen.kt가 각자 갖고 있던
 * `LocalConfiguration.current.screenWidthDp >= 600` 체크를 하나로 추출 — 태블릿 UI를 데스크탑처럼
 * 만드는 작업에서 MainActivity/CalculatorScreen/CalendarScreen이 공통으로 쓴다).
 */
@Composable
fun isTabletWidth(): Boolean = LocalConfiguration.current.screenWidthDp >= 600
