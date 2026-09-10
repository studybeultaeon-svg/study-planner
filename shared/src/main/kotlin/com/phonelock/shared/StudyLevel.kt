package com.phonelock.shared

/**
 * 레벨업 시스템(104차 세션, IDEAS.md "최우선 후보" 게이미피케이션 3번째 항목) — 누적 "공부" 포인트
 * (STUDY 원장 항목만 합산 × 10분, 즉 실제 공부한 총 분)을 기준으로 레벨이 오른다. CharacterGrowth(전체
 * 적립 포인트 기준 8단계 식물)와는 별개 축 — 캐릭터는 루틴/캘린더까지 포함한 "전반적 꾸준함", 레벨은
 * "순수 공부량"만 반영한다. 유한 단계 목록이 아니라 레벨마다 요구량이 늘어나는 수식 기반(끝이 없음).
 * 순수 계산 로직만 담아 안드로이드/데스크탑 대칭 유지를 위해 :shared에 작성(CharacterGrowth와 동일 패턴).
 */
object StudyLevel {
    /** 레벨 N에서 N+1로 오르는 데 필요한 분 증가폭의 단위. 레벨 L→L+1 요구량은 UNIT_MINUTES * L. */
    private const val UNIT_MINUTES = 30

    private val TIERS = listOf(
        1 to "공부 새내기",
        5 to "집중력 UP",
        10 to "성실한 학습자",
        20 to "몰입 마스터",
        30 to "공부의 신",
        50 to "전설의 갓생러"
    )

    /** 레벨 level에 도달하기 위해 필요한 누적 공부 분(레벨 1은 0분). */
    fun cumulativeMinutesForLevel(level: Int): Int {
        val l = (level - 1).coerceAtLeast(0)
        return 15 * l * (l + 1) // 15 = UNIT_MINUTES / 2, 등차수열 합
    }

    /** 총 누적 공부 분 기준 현재 레벨(1부터 시작, 끝없이 증가). */
    fun levelFor(totalStudyMinutes: Int): Int {
        var level = 1
        while (cumulativeMinutesForLevel(level + 1) <= totalStudyMinutes) level++
        return level
    }

    /** 현재 레벨 구간 안에서의 진행률(0.0~1.0). */
    fun progressToNext(totalStudyMinutes: Int): Float {
        val level = levelFor(totalStudyMinutes)
        val base = cumulativeMinutesForLevel(level)
        val next = cumulativeMinutesForLevel(level + 1)
        val span = next - base
        if (span <= 0) return 0f
        return ((totalStudyMinutes - base).toFloat() / span).coerceIn(0f, 1f)
    }

    /** 다음 레벨까지 남은 공부 분. */
    fun minutesToNextLevel(totalStudyMinutes: Int): Int {
        val level = levelFor(totalStudyMinutes)
        return (cumulativeMinutesForLevel(level + 1) - totalStudyMinutes).coerceAtLeast(0)
    }

    /** 현재 레벨의 칭호(레벨 구간별로 바뀜). */
    fun tierLabel(level: Int): String = TIERS.lastOrNull { level >= it.first }?.second ?: TIERS.first().second
}
