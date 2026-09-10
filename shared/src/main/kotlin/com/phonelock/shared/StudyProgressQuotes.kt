package com.phonelock.shared

/**
 * 공부 타이머 진행률(목표 시간/사이클 대비)에 따라 뜨는 응원 문구(99차+ 세션, 사용자 요청) —
 * MotivationalQuotes.kt(재확인/차단 화면, 조롱조)/RoutineQuotes.kt(스트릭, 응원+조롱 혼합)와 달리
 * 이건 항상 긍정적인 톤만 쓴다 — 공부를 "지금 하고 있는 중"에 뜨는 문구라 채찍질보다 지속 동기부여가
 * 목적이기 때문. 안드로이드/데스크탑 대칭 유지를 위해 처음부터 :shared에 작성.
 */
object StudyProgressQuotes {
    private val STARTING = listOf(
        "시작이 반이다 — 이미 반은 온 거예요.",
        "일단 시작한 것만으로도 오늘의 나는 이겼어요.",
        "가장 어려운 건 이미 끝났어요, 바로 지금 시작하는 거.",
        "지금 이 순간이 가장 큰 고비였어요.",
        "첫 발을 뗐다는 게 중요해요, 나머지는 관성이 해줄 거예요."
    )
    private val EARLY = listOf(
        "이제 몸이 풀리기 시작했어요, 이 흐름 그대로.",
        "슬슬 집중이 붙는 구간이에요.",
        "페이스 좋아요, 이대로만 가면 됩니다.",
        "워밍업 끝, 이제부터가 진짜예요.",
        "여기서 멈추기엔 아깝죠, 계속 가봅시다."
    )
    private val MIDDLE = listOf(
        "딱 절반이에요 — 여기까지 온 나를 믿어요.",
        "반환점을 돌았어요, 남은 절반도 가능해요.",
        "지금까지 해온 만큼만 더 하면 끝나요.",
        "여기서 흔들리면 아까워요, 페이스 유지.",
        "중간 지점 통과 — 숨 한 번 고르고 계속."
    )
    private val LATE = listOf(
        "이제 얼마 안 남았어요, 속도 내볼까요.",
        "여기서 포기하기엔 너무 많이 왔어요.",
        "결승선이 보이기 시작했어요.",
        "고비는 지나갔어요, 남은 건 마무리뿐.",
        "이 페이스면 목표까지 금방이에요."
    )
    private val FINAL = listOf(
        "거의 다 왔어요, 마지막까지 힘내요.",
        "몇 분만 더 버티면 오늘의 목표 달성이에요.",
        "여기서 멈추면 너무 아까워요 — 조금만 더.",
        "마지막 스퍼트, 지금이 제일 중요해요.",
        "결승선 코앞이에요."
    )
    private val OVERTIME = listOf(
        "목표 달성! 지금부터는 완전히 보너스 타임이에요.",
        "이미 목표는 넘었어요 — 오늘 제대로 해냈네요.",
        "여기서부터는 그냥 덤이에요, 하고 싶은 만큼만.",
        "목표 초과 달성 — 오늘의 나, 진짜 잘했다.",
        "이 정도면 오늘 하루는 확실히 남는 장사예요."
    )

    /** progress: 0.0(시작)~1.0(목표 도달), 1.0 이상도 허용(목표 초과 시 보너스 문구). */
    fun forProgress(progress: Double): String = when {
        progress >= 1.0 -> OVERTIME.random()
        progress >= 0.85 -> FINAL.random()
        progress >= 0.6 -> LATE.random()
        progress >= 0.4 -> MIDDLE.random()
        progress >= 0.1 -> EARLY.random()
        else -> STARTING.random()
    }

    /** [forProgress]와 같은 구간 경계를 공유하는 정수 tier(0~5) — remember(tier) 키로 써서 매초
     *  재계산되는 progress 값이 바뀌어도 구간을 넘기 전까지는 같은 문구가 유지되게 한다. */
    fun tierFor(progress: Double): Int = when {
        progress >= 1.0 -> 5
        progress >= 0.85 -> 4
        progress >= 0.6 -> 3
        progress >= 0.4 -> 2
        progress >= 0.1 -> 1
        else -> 0
    }
}
