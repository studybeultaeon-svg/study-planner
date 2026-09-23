package com.phonelock.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** 알림 필터·묶음 요약(130차)의 순수 로직 검증 — 기기 API를 타지 않는 부분만. */
class NotificationFilterTest {

    private val protectedPackages = setOf("com.phonelock.app", "com.android.dialer", "com.android.mms")

    @Test
    fun `통화와 알람과 진행 중 알림은 고른 앱이어도 거르지 않는다`() {
        assertTrue(isProtectedNotification("com.kakao.talk", "call", false, protectedPackages))
        assertTrue(isProtectedNotification("com.kakao.talk", "alarm", false, protectedPackages))
        assertTrue(isProtectedNotification("com.spotify.music", null, true, protectedPackages))
    }

    @Test
    fun `기본 전화-문자 앱과 이 앱 자신은 항상 보호된다`() {
        assertTrue(isProtectedNotification("com.android.dialer", null, false, protectedPackages))
        assertTrue(isProtectedNotification("com.phonelock.app", null, false, protectedPackages))
    }

    @Test
    fun `평범한 앱 알림은 보호 대상이 아니다`() {
        assertFalse(isProtectedNotification("com.kakao.talk", "msg", false, protectedPackages))
    }

    @Test
    fun `요약 시각은 형식이 틀린 항목을 버리고 정렬한다`() {
        val times = parseDigestTimes("18:30, 9:05 , 없음, 25:00, 12:70, 18:30")
        assertEquals(listOf(LocalTime.of(9, 5), LocalTime.of(18, 30)), times)
    }

    @Test
    fun `다음 요약 시각은 오늘 남은 것 중 가장 이른 것`() {
        val now = LocalDateTime.of(2026, 9, 20, 13, 0)
        val next = nextDigestTrigger(now, listOf(LocalTime.of(12, 30), LocalTime.of(18, 30)))
        assertEquals(LocalDateTime.of(2026, 9, 20, 18, 30), next)
    }

    @Test
    fun `오늘 시각이 다 지났으면 내일 첫 시각으로 넘어간다`() {
        val now = LocalDateTime.of(2026, 9, 20, 23, 0)
        val next = nextDigestTrigger(now, listOf(LocalTime.of(12, 30), LocalTime.of(18, 30)))
        assertEquals(LocalDateTime.of(2026, 9, 21, 12, 30), next)
    }

    @Test
    fun `시각이 하나도 없으면 예약하지 않는다`() {
        assertNull(nextDigestTrigger(LocalDateTime.now(), emptyList()))
    }

    @Test
    fun `요약 문구는 앱별 건수를 많은 순으로 줄여 쓴다`() {
        val labels = listOf("카톡", "카톡", "인스타", "카톡", "X", "메일")
        assertEquals("카톡 3건 · 인스타 1건 · X 1건 · 외 1개 앱", digestSummaryText(labels))
    }
}
