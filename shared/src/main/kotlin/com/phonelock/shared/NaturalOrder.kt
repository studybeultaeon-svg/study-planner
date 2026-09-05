package com.phonelock.shared

import java.text.Collator
import java.util.Locale

/**
 * 캘린더 기본 정렬(85차, 사용자 요청) — 업무 이름에 "문제2"/"문제10"처럼 숫자가 섞여 있으면 순수
 * 사전식(Collator) 비교는 문자 단위로만 비교해 "문제10"이 "문제2"보다 앞에 온다(1<2이므로). 이름을
 * 숫자/비숫자 구간으로 쪼개 숫자 구간은 값으로, 나머지는 Collator로 비교하는 "자연 정렬"로 이 문제를
 * 해결한다.
 */
object NaturalOrder {
    private val koreanCollator = Collator.getInstance(Locale.KOREAN)

    private fun splitChunks(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val start = i
            val isDigit = s[i].isDigit()
            while (i < s.length && s[i].isDigit() == isDigit) i++
            chunks.add(s.substring(start, i))
        }
        return chunks
    }

    /** 이름을 숫자/문자 구간별로 비교하는 자연 정렬 comparator. */
    val comparator: Comparator<String> = Comparator { a, b ->
        val ca = splitChunks(a)
        val cb = splitChunks(b)
        val n = minOf(ca.size, cb.size)
        for (i in 0 until n) {
            val x = ca[i]; val y = cb[i]
            val bothDigits = x.isNotEmpty() && y.isNotEmpty() && x[0].isDigit() && y[0].isDigit()
            val cmp = if (bothDigits) {
                val nx = x.toLongOrNull(); val ny = y.toLongOrNull()
                if (nx != null && ny != null) nx.compareTo(ny) else koreanCollator.compare(x, y)
            } else {
                koreanCollator.compare(x, y)
            }
            if (cmp != 0) return@Comparator cmp
        }
        ca.size - cb.size
    }
}
