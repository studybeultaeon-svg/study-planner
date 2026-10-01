package com.phonelock.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 모션 시스템(144차 리디자인 — DECISIONS.md 144차, 데스크탑판과 같은 값).
 *
 * 성격은 **빠르고 정확하게**(생산성 앱): 화면이 사용자의 손보다 늦게 따라오지 않는 160~360ms, 감속형 이징
 * 하나로 통일한다. 튀는 스프링은 "눌림" 피드백에만 쓴다. 애니메이션은 transform/alpha만 움직여 레이아웃을
 * 다시 계산하지 않는 쪽을 우선한다.
 *
 * **성능 모드**(= 미니멀 모드, [LocalPerformanceMode])거나 시스템 "애니메이션 제거"가 켜져 있으면
 * [AppMotion.reduced]가 true가 되어 모든 전환이 90ms 페이드 이하/즉시 전환으로 줄어든다 — 기능과 화면 구조는
 * 그대로이고 움직임과 렌더링 비용만 줄인다.
 */
object MotionTokens {
    const val QUICK_MS = 160
    const val STANDARD_MS = 240
    const val EMPHASIZED_MS = 360
    /** 성능 모드에서 남기는 유일한 전환 길이 — 상태가 바뀌었다는 것만 알릴 만큼 짧게. */
    const val REDUCED_MS = 90

    /** 감속형(빠르게 출발해 부드럽게 멈춤) — 들어오는 요소와 상태 변화 전부. */
    val EaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** 가속형 — 화면에서 나가는 요소. */
    val EaseIn = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

@Immutable
class AppMotion(val reduced: Boolean) {
    /** 선택 표시·토글·작은 상태 변화. */
    fun <T> quick(): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(MotionTokens.QUICK_MS, easing = MotionTokens.EaseOut)

    /** 화면 안의 내용 교체(서브탭 전환, 펼치기/접기). */
    fun <T> standard(): FiniteAnimationSpec<T> =
        if (reduced) tween(MotionTokens.REDUCED_MS) else tween(MotionTokens.STANDARD_MS, easing = MotionTokens.EaseOut)

    /** 화면 전환·큰 요소가 자리를 옮길 때. */
    fun <T> emphasized(): FiniteAnimationSpec<T> =
        if (reduced) tween(MotionTokens.REDUCED_MS) else tween(MotionTokens.EMPHASIZED_MS, easing = MotionTokens.EaseOut)

    /** 나가는 요소(조금 더 짧게 — 들어오는 쪽에 시선을 넘긴다). */
    fun <T> exit(): FiniteAnimationSpec<T> =
        if (reduced) tween(MotionTokens.REDUCED_MS) else tween(MotionTokens.QUICK_MS, easing = MotionTokens.EaseIn)

    /** 눌림 피드백 — 성능 모드에선 크기 변화 자체를 하지 않는다([pressScale]). */
    fun <T> press(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow * 2f)
}

/** 성능 모드(= 미니멀 모드) 여부. 장식(그라디언트·그림자·반투명·움직이는 장면)을 그릴지 여기서 판단한다. */
val LocalPerformanceMode = staticCompositionLocalOf { false }

/** 지금 화면이 써야 하는 모션 규격. [PhoneLockTheme]이 성능 모드·시스템 설정을 보고 채운다. */
val LocalAppMotion = staticCompositionLocalOf { AppMotion(reduced = false) }

/**
 * 눌렀을 때 살짝 작아지는 피드백(0.97배). 같은 [interactionSource]를 쓰는 clickable과 함께 붙인다.
 * 성능 모드에선 아무것도 하지 않는다(리플/색 변화 같은 기본 피드백은 그대로 남는다).
 */
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.97f): Modifier = composed {
    val motion = LocalAppMotion.current
    if (motion.reduced) return@composed this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, motion.press(), label = "pressScale")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** 성능 모드인지 Composable 안에서 짧게 묻는다. */
@Composable
fun isPerformanceMode(): Boolean = LocalPerformanceMode.current
