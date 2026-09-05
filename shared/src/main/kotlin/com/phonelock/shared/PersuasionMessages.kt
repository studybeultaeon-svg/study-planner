package com.phonelock.shared

import kotlin.random.Random

/**
 * 제한을 약화시키는 시도(설정 약화 저장, 그룹 삭제, 그룹 일정 끄기 등)를 할 때 하나씩 순서대로
 * 보여주는 회유 멘트. 각 멘트마다 사용자가 직접 "예"를 눌러야 하고, 누르면 그때부터 무작위 시간
 * 대기한 뒤 자동으로 다음 멘트로 넘어간다(마지막 멘트는 실제 행동 실행). 눌러야 시작되고, 시작되면
 * 다시 못 누른다 — 미리 잠겨있다가 풀리는 방식이 아니다.
 * 사용자 요청으로 정중한 반성 유도 톤에서 MotivationalQuotes.kt와 같은 계열의 조롱조/놀림조로
 * 전면 교체(2026-08-14) — 앞부분은 가벼운 놀림, 뒤로 갈수록 더 독해지도록 순서를 짰다.
 * 82차부터 안드로이드/데스크탑이 각자 대칭 복제하던 것을 이 :shared 모듈로 통합(감사 §7).
 */
val PERSUASION_MESSAGES = listOf(
    "또 여기 왔네, 대단한 결심이었나 봐",
    "이 설정 만들 때 이유가 있었을 텐데, 벌써 까먹었어?",
    "몇 분을 못 참고 손이 먼저 움직였네",
    "이거 지금 꼭 풀어야 되는 거 맞아?",
    "잠깐이라더니 또 여기 서 있네",
    "\"이번만\"이 벌써 몇 번째야",
    "스스로 잠가놓고 스스로 열려는 거, 웃기지 않아?",
    "결심은 화려했는데 실천은 왜 이렇게 초라해",
    "이 버튼 하나를 못 참아서 여기까지 왔네",
    "내일의 너는 오늘의 이 선택을 뭐라고 부를까",
    "의지력이 이 정도였어? 솔직히 실망이다",
    "규칙 만든 것도 너고 어기려는 것도 너네",
    "이쯤 되면 그냥 못 참는 거 인정하는 게 낫지 않아",
    "매번 여기서 무너지는 거, 이제 패턴이 아니라 습성이야",
    "\"딱 한 번만\"이 벌써 몇 번째 딱 한 번인지 세어봤어",
    "이 정도로 흔들리면서 뭘 바꾸겠다는 거야",
    "스스로한테 지는 게 이렇게 쉬운 사람이었나",
    "결심 유통기한, 이번엔 진짜 짧았네",
    "여기까지 온 거 보면 이미 마음 정한 거 다 알아 — 그래도 하나만 묻는다, 이게 진짜 네가 원하던 모습이야?",
    "좋아, 계속 가 — 그래도 진행하는 거지? 이게 정말, 최종 결정이야?"
)

private const val PERSUASION_MIN_STEP_MS = 2_000L
private const val PERSUASION_MAX_STEP_MS = 45_000L
private const val PERSUASION_TOTAL_BUDGET_MS = 10 * 60 * 1000L

/**
 * "예"를 누른 뒤 자동으로 다음 멘트로 넘어가기까지 각 단계마다 기다리는 시간을 무작위로 정한다.
 * 각 단계는 서로 독립적으로 2~45초 사이에서 뚜렷하게 다른 값을 뽑되(같은 값이 반복되는 느낌이
 * 나지 않도록), 남은 단계들이 최소한은 확보되도록 예산을 관리해서 20단계를 다 합쳐도 10분을
 * 넘지 않는다.
 */
fun randomPersuasionStepDelaysMs(stepCount: Int = PERSUASION_MESSAGES.size): List<Long> {
    var remainingBudgetMs = PERSUASION_TOTAL_BUDGET_MS
    return List(stepCount) { index ->
        val stepsLeftAfterThis = stepCount - index - 1
        val maxForThisStep = (remainingBudgetMs - PERSUASION_MIN_STEP_MS * stepsLeftAfterThis)
            .coerceIn(PERSUASION_MIN_STEP_MS, PERSUASION_MAX_STEP_MS)
        val delayMs = if (maxForThisStep <= PERSUASION_MIN_STEP_MS) {
            PERSUASION_MIN_STEP_MS
        } else {
            Random.nextLong(PERSUASION_MIN_STEP_MS, maxForThisStep + 1)
        }
        remainingBudgetMs -= delayMs
        delayMs
    }
}
