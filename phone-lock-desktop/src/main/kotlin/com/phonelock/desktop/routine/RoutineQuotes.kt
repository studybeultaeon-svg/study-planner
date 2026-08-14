package com.phonelock.desktop.routine

/**
 * 스트릭 기반 응원/비판/조롱 알림(52차, IDEAS.md 요청) 문구 — 안드로이드판과 내용 동일하게 유지할 것.
 */
object RoutineQuotes {
    private val BROKEN = listOf(
        "오늘 루틴을 놓쳤어요. 스트릭이 0으로 돌아갔습니다.",
        "어제까지 쌓은 스트릭이 오늘 끊겼어요.",
        "스트릭 초기화. 오늘부터 다시 시작하세요."
    )
    private val STARTING = listOf(
        "오늘 하루도 루틴을 채워봐요.",
        "이제 막 시작이에요 — 하루씩 쌓아봅시다."
    )
    private val BUILDING = listOf(
        "🔥 스트릭이 쌓이고 있어요, 계속 가봐요.",
        "좋은 흐름이에요 — 오늘도 이어가 볼까요."
    )
    private val STRONG = listOf(
        "🔥 스트릭이 꽤 길어졌어요! 오늘도 지켜봐요.",
        "여기까지 온 게 대단해요 — 오늘 하루만 더."
    )

    /** streak: 어제까지의 연속일수(오늘 아직 체크 전 기준), broken: 어제 스트릭이 0으로 끊겼는지. */
    fun forStreak(streak: Int, broken: Boolean): String = when {
        broken -> BROKEN.random()
        streak >= 14 -> STRONG.random()
        streak >= 3 -> BUILDING.random()
        else -> STARTING.random()
    }
}
