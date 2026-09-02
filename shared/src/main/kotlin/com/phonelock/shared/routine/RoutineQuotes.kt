package com.phonelock.shared.routine

/**
 * 스트릭 기반 응원/비판/조롱 알림(52차, IDEAS.md 요청) 문구 — 42차 MotivationalQuotes.kt(재확인/차단
 * 화면용, 5단계 149개)와는 별개 용도라 새로 작은 세트로 둔다. 82차부터 안드로이드/데스크탑이 각자
 * 대칭 복제하던 것을 이 :shared 모듈로 통합(감사 §7).
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

    // 58차: 스트릭이 0인 채로 며칠째인지에 따라 응원 → 조롱 → 팩폭으로 강도를 올린다.
    private val ZERO_ENCOURAGE = listOf(
        "스트릭이 비어있어요. 오늘 한 번으로 다시 시작할 수 있어요.",
        "며칠 쉬었다고 끝난 거 아니에요 — 오늘 다시 채워봐요.",
        "0에서 1 만드는 게 제일 어려워요, 오늘 그거 해봐요."
    )
    private val ZERO_MOCK = listOf(
        "벌써 며칠째 0이에요. 이러다 아예 놓아버리는 거 아니에요?",
        "며칠째 손도 안 대고 있네요 — 앱만 켜놓은 거예요?",
        "스트릭이 며칠째 0인데, 그것도 이제 습관이 되겠어요."
    )
    private val ZERO_BRUTAL = listOf(
        "일주일 넘게 아무것도 안 했어요. 계획은 실행 안 하면 그냥 메모예요.",
        "이쯤 되면 다시 시작할 마음이 있긴 한 거예요?",
        "며칠씩 0을 유지하는 것도 재주라면 재주네요.",
        "루틴을 만든 이유, 아직 기억은 하고 있어요?"
    )

    /** streak: 어제까지의 연속일수(오늘 아직 체크 전 기준), broken: 어제 스트릭이 0으로 끊겼는지. */
    fun forStreak(streak: Int, broken: Boolean): String = when {
        broken -> BROKEN.random()
        streak >= 14 -> STRONG.random()
        streak >= 3 -> BUILDING.random()
        else -> STARTING.random()
    }

    /** zeroStreakDays: 스트릭이 0으로 끊긴 날(0)부터 며칠째 0을 유지 중인지. broken: 오늘 막 끊긴 날인지. */
    fun forZeroStreak(zeroStreakDays: Int, broken: Boolean): String = when {
        broken -> BROKEN.random()
        zeroStreakDays >= 7 -> ZERO_BRUTAL.random()
        zeroStreakDays >= 3 -> ZERO_MOCK.random()
        else -> ZERO_ENCOURAGE.random()
    }
}
