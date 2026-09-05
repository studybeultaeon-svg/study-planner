package com.phonelock.shared.calc

/**
 * 다회독(여러 번 반복 학습) 스케줄 계산 — 업무마다 회독 수(2~8, 85차: 최소 3→2로 완화)와 회독별
 * 간격(일수)을 다르게 설정할 수 있게 하면서, 색상은 빨강(회독1)→초록(마지막 회독)으로 자연스럽게
 * 그라데이션되도록 계산한다(2회독이면 중간 앵커 없이 빨강→초록만). CalcEngine.jsDow(0=일~6=토)를
 * 그대로 재사용.
 */
object PassSchedule {
    const val MIN_PASS_COUNT = 2
    const val MAX_PASS_COUNT = 8
    const val DEFAULT_PASS_COUNT = 3
    const val DEFAULT_INTERVAL_DAYS = 3
    const val DEFAULT_INTERVALS_CSV = "3,4"

    // 83차: 순수 HSV 색상환 보간은 두 번이나 "너무 쨍하다"는 지적을 받았다(채도/명도를 낮춰도 원색
    // 특유의 형광 느낌이 남음) — 대신 실제 무지개(빨강→주황→노랑→초록)의 정석적인 색상 4개를 앵커로
    // 두고 그 사이를 RGB 선형보간한다. 각 앵커 자체가 "너무 연하지도 너무 어둡지도 않은" 균형점.
    private val RAINBOW_STOPS = intArrayOf(
        0xFFE64A4A.toInt(), // 빨강 (회독 1)
        0xFFF2994A.toInt(), // 주황
        0xFFE8C547.toInt(), // 노랑
        0xFF4CAF6D.toInt()  // 초록 (마지막 회독)
    )

    /** index(0-based)/total 위치에 따라 무지개 앵커 4색을 선형보간한 ARGB Int 반환. Compose Color(Int) 생성자에 그대로 넘기면 됨. */
    fun passColor(index: Int, total: Int): Int {
        val safeTotal = total.coerceAtLeast(2)
        val t = (index.toFloat() / (safeTotal - 1).toFloat()).coerceIn(0f, 1f)
        val segments = RAINBOW_STOPS.size - 1
        val scaled = (t * segments).coerceIn(0f, segments.toFloat())
        val segIndex = scaled.toInt().coerceIn(0, segments - 1)
        val localT = scaled - segIndex
        return lerpArgb(RAINBOW_STOPS[segIndex], RAINBOW_STOPS[segIndex + 1], localT)
    }

    private fun lerpArgb(from: Int, to: Int, t: Float): Int {
        val fr = (from shr 16) and 0xFF; val fg = (from shr 8) and 0xFF; val fb = from and 0xFF
        val tr = (to shr 16) and 0xFF; val tg = (to shr 8) and 0xFF; val tb = to and 0xFF
        val r = (fr + (tr - fr) * t).toInt().coerceIn(0, 255)
        val g = (fg + (tg - fg) * t).toInt().coerceIn(0, 255)
        val b = (fb + (tb - fb) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun defaultPassIntervals(count: Int): List<Int> {
        val n = count.coerceIn(MIN_PASS_COUNT, MAX_PASS_COUNT)
        return List(n - 1) { DEFAULT_INTERVAL_DAYS }
    }

    /** csv 파싱 + 길이가 (count-1)과 다르면 방어적으로 자르거나 마지막 값을 반복해 채움. */
    fun parsePassIntervals(csv: String, count: Int): List<Int> {
        val n = count.coerceIn(MIN_PASS_COUNT, MAX_PASS_COUNT)
        val needed = n - 1
        val parsed = csv.split(",").mapNotNull { it.trim().toIntOrNull()?.coerceAtLeast(0) }
        return when {
            parsed.isEmpty() -> defaultPassIntervals(n)
            parsed.size == needed -> parsed
            parsed.size > needed -> parsed.take(needed)
            else -> parsed + List(needed - parsed.size) { parsed.last() }
        }
    }
}
