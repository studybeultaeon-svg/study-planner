package com.phonelock.app.service

import com.phonelock.shared.lock.LockTimer
import com.phonelock.shared.lock.LockTimerPreset
import com.phonelock.shared.lock.LockTimerSignal
import com.phonelock.shared.lock.LockTimerSync
import com.phonelock.shared.lock.UnlockLevel
import com.phonelock.shared.lock.formatLockRemaining
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 관리 > 타이머("이거까지만 할게요!", 142차) 테스트 — 엔진은 :shared의 순수 로직이라 안드로이드 유닛테스트에서
 * 검증한다(RoutineRepeatTest와 같은 방식).
 */
class LockTimerTest {

    private val start = 1_000_000_000_000L
    private val minute = 60_000L

    private fun timer(
        free: Int = 10,
        lock: Int = 60,
        wholeDevice: Boolean = true,
        apps: Set<String> = setOf("com.allowed"),
        sites: Set<String> = setOf("allowed.com"),
        level: Int = 2
    ) = LockTimer.create(start, free, lock, wholeDevice, apps, sites, level)

    @Test
    fun `free time comes first, then the lock, then it is over`() {
        val t = timer(free = 10, lock = 60)
        assertEquals(LockTimer.Phase.FREE, t.phaseAt(start))
        assertEquals(LockTimer.Phase.FREE, t.phaseAt(start + 10 * minute - 1))
        assertEquals(LockTimer.Phase.LOCKED, t.phaseAt(start + 10 * minute))
        assertEquals(LockTimer.Phase.LOCKED, t.phaseAt(start + 70 * minute - 1))
        assertEquals(LockTimer.Phase.DONE, t.phaseAt(start + 70 * minute))
    }

    @Test
    fun `zero free time locks immediately`() {
        val t = timer(free = 0, lock = 30)
        assertTrue(t.isLockedAt(start))
        assertEquals(30 * minute, t.remainingMillis(start))
    }

    @Test
    fun `remaining time counts down to the end of the current phase`() {
        val t = timer(free = 10, lock = 60)
        assertEquals(4 * minute, t.remainingMillis(start + 6 * minute))
        assertEquals(50 * minute, t.remainingMillis(start + 20 * minute))
        assertEquals(0L, t.remainingMillis(start + 200 * minute))
    }

    @Test
    fun `whole device lock blocks everything that is not on the allowed list`() {
        val t = timer(wholeDevice = true)
        assertFalse(t.blocksApp("com.allowed"))
        assertTrue(t.blocksApp("com.other"))
        assertFalse(t.blocksHost("allowed.com"))
        assertFalse(t.blocksHost("www.allowed.com"))
        assertTrue(t.blocksHost("notallowed.com"))
        assertTrue(t.blocksHost("other.org"))
        assertFalse(t.blocksAddressText("https://m.allowed.com/page"))
        assertTrue(t.blocksAddressText("other.org/page"))
    }

    @Test
    fun `targeted lock blocks only the chosen apps and sites`() {
        val t = timer(wholeDevice = false, apps = setOf("com.blocked"), sites = setOf("blocked.com"))
        assertTrue(t.blocksApp("com.blocked"))
        assertFalse(t.blocksApp("com.other"))
        assertTrue(t.blocksHost("m.blocked.com"))
        assertFalse(t.blocksHost("other.org"))
        assertTrue(t.blocksAddressText("BLOCKED.com/watch"))
        assertFalse(t.blocksAddressText("other.org"))
    }

    @Test
    fun `whole device lock with an empty allowed list blocks every site`() {
        val t = timer(wholeDevice = true, sites = emptySet())
        assertTrue(t.blocksHost("anything.com"))
        assertTrue(t.blocksAddressText("anything.com"))
    }

    @Test
    fun `minutes and level are clamped to safe ranges`() {
        val t = LockTimer.create(start, freeMinutes = -5, lockMinutes = 99_999, wholeDevice = true, apps = emptySet(), sites = emptySet(), level = 9)
        assertEquals(start, t.lockStartAtMillis)
        assertEquals(start + LockTimer.MAX_LOCK_MINUTES * minute, t.lockEndAtMillis)
        assertEquals(UnlockLevel.MAX, t.level)
        assertEquals(start + minute, LockTimer.create(start, 0, 0, true, emptySet(), emptySet(), 1).lockEndAtMillis)
    }

    @Test
    fun `a timer survives being saved and read back`() {
        val t = timer(free = 3, lock = 45, wholeDevice = false, apps = setOf("a.b", "c.d"), sites = setOf("x.com"), level = 3)
        assertEquals(t, LockTimer.decode(t.encode()))
        val empty = timer(apps = emptySet(), sites = emptySet())
        assertEquals(empty, LockTimer.decode(empty.encode()))
    }

    @Test
    fun `broken saved text reads as no timer`() {
        assertNull(LockTimer.decode(null))
        assertNull(LockTimer.decode(""))
        assertNull(LockTimer.decode("garbage"))
        assertNull(LockTimer.decode("2\n1\n2\n3\n1\n2\n\n"))
        // 끝 시각이 시작보다 앞서면 쓸 수 없는 값이다.
        assertNull(LockTimer.decode("1\n100\n300\n200\n1\n2\n\n"))
    }

    @Test
    fun `items containing separators are dropped instead of corrupting the saved text`() {
        val t = LockTimer.create(start, 0, 10, true, setOf("ok.app", "bad\tapp", "  "), setOf("bad\nsite", " site.com "), 1)
        assertEquals(setOf("ok.app"), t.apps)
        assertEquals(setOf("site.com"), t.sites)
    }

    @Test
    fun `preset keeps the allowed list and the target list apart`() {
        val preset = LockTimerPreset(
            freeMinutes = 0, lockMinutes = 120, wholeDevice = false, level = 4,
            allowedApps = setOf("allow.a"), allowedSites = setOf("allow.com"),
            targetApps = setOf("block.a", "block.b"), targetSites = emptySet()
        )
        assertEquals(preset, LockTimerPreset.decode(preset.encode()))
        assertEquals(LockTimerPreset(), LockTimerPreset.decode(null))
        assertEquals(LockTimerPreset(), LockTimerPreset.decode("nonsense"))
    }

    @Test
    fun `unlock levels describe four steps and only the last cannot be unlocked`() {
        assertTrue(UnlockLevel.canUnlock(1))
        assertTrue(UnlockLevel.canUnlock(3))
        assertFalse(UnlockLevel.canUnlock(4))
        assertFalse(UnlockLevel.needsQuestions(1))
        assertTrue(UnlockLevel.needsQuestions(2))
        assertTrue(UnlockLevel.needsQuestions(3))
        assertEquals(UnlockLevel.LEVEL1_WAIT_SECONDS, UnlockLevel.waitSeconds(1))
        assertEquals(0, UnlockLevel.waitSeconds(2))
        assertEquals(UnlockLevel.LEVEL3_WAIT_SECONDS, UnlockLevel.waitSeconds(3))
        assertEquals(UnlockLevel.MIN, UnlockLevel.clamp(0))
    }

    @Test
    fun `remaining time label rounds up to the next second`() {
        assertEquals("1시간 5분", formatLockRemaining(65 * minute))
        assertEquals("12분 30초", formatLockRemaining(12 * minute + 30_000))
        assertEquals("45초", formatLockRemaining(44_001))
        assertEquals("0초", formatLockRemaining(-500))
    }

    // ---- 143차: 뽀모도로 휴식 해제 필드 + 저장 형식 호환 ----

    @Test
    fun `pomodoro break unlock is saved and read back, and defaults to off`() {
        val on = LockTimer.create(start, 5, 60, true, setOf("a.b"), emptySet(), 2, pomodoroBreakUnlock = true)
        assertTrue(LockTimer.decode(on.encode())!!.pomodoroBreakUnlock)
        assertFalse(timer().pomodoroBreakUnlock)
        assertFalse(LockTimer.decode(timer().encode())!!.pomodoroBreakUnlock)
    }

    @Test
    fun `a timer saved in the previous format still reads, with break unlock off`() {
        // 142차 형식: 버전 "1", 8필드(휴식 해제 필드 없음).
        val legacy = listOf("1", "100", "200", "300", "1", "2", "a.b", "x.com").joinToString("\n")
        val decoded = LockTimer.decode(legacy)!!
        assertEquals(200L, decoded.lockStartAtMillis)
        assertEquals(setOf("a.b"), decoded.apps)
        assertFalse(decoded.pomodoroBreakUnlock)
        // 새 형식의 약속은 9필드가 아니면 읽지 않는다.
        assertNull(LockTimer.decode(listOf("2", "100", "200", "300", "1", "2", "a.b", "x.com").joinToString("\n")))
    }

    @Test
    fun `preset remembers the break unlock choice and still reads the previous format`() {
        val preset = LockTimerPreset(pomodoroBreakUnlock = true, allowedApps = setOf("a.b"))
        assertEquals(preset, LockTimerPreset.decode(preset.encode()))
        val legacy = listOf("1", "5", "30", "0", "3", "allow.a", "allow.com", "block.a", "block.com").joinToString("\n")
        val decoded = LockTimerPreset.decode(legacy)
        assertEquals(30, decoded.lockMinutes)
        assertEquals(setOf("block.a"), decoded.targetApps)
        assertFalse(decoded.pomodoroBreakUnlock)
    }

    // ---- 143차: 기기 간 동기화 판정 ----

    private val now = start + 30 * minute

    private fun signal(
        started: Long = start, free: Int = 10, lock: Int = 60,
        cancelled: Boolean = false, level: Int = 2, wholeDevice: Boolean = true
    ) = LockTimerSignal(
        startedAtMillis = started,
        lockStartAtMillis = started + free * minute,
        lockEndAtMillis = started + (free + lock) * minute,
        wholeDevice = wholeDevice, level = level, pomodoroBreakUnlock = false,
        cancelled = cancelled, updatedAtMillis = started
    )

    @Test
    fun `an active remote timer is adopted by a device that has none`() {
        val remote = signal()
        assertEquals(LockTimerSync.Action.Adopt(remote), LockTimerSync.reconcile(null, remote, 0L, now))
    }

    @Test
    fun `adopting uses this device's own list from its last settings`() {
        val preset = LockTimerPreset(
            allowedApps = setOf("mine.allowed"), allowedSites = setOf("mine.com"),
            targetApps = setOf("mine.target"), targetSites = setOf("target.com")
        )
        val whole = signal(wholeDevice = true, level = 3).toLockTimer(preset)
        assertEquals(setOf("mine.allowed"), whole.apps)
        assertEquals(setOf("mine.com"), whole.sites)
        assertEquals(3, whole.level)
        val targeted = signal(wholeDevice = false).toLockTimer(preset)
        assertEquals(setOf("mine.target"), targeted.apps)
        assertEquals(setOf("target.com"), targeted.sites)
    }

    @Test
    fun `an ended or released remote timer is not adopted`() {
        assertEquals(LockTimerSync.Action.None, LockTimerSync.reconcile(null, signal(cancelled = true), 0L, now))
        assertEquals(LockTimerSync.Action.None, LockTimerSync.reconcile(null, signal(), 0L, start + 200 * minute))
        assertEquals(LockTimerSync.Action.None, LockTimerSync.reconcile(null, null, 0L, now))
    }

    @Test
    fun `releasing a timer on another device releases it here too`() {
        val mine = timer()
        assertEquals(LockTimerSync.Action.ClearLocal, LockTimerSync.reconcile(mine, signal(cancelled = true), 0L, now))
        assertEquals(LockTimerSync.Action.None, LockTimerSync.reconcile(mine, signal(), 0L, now))
    }

    @Test
    fun `a running local timer is never replaced by a different remote one`() {
        val mine = timer(level = 4)
        // 지금(시작 30분 뒤)에도 진행 중인 다른 약속이어야 한다 — 이미 끝난 약속은 "자리가 빈 것"으로 본다.
        val otherDevicesEasierTimer = signal(started = start + 5 * minute, free = 0, lock = 60, level = 1)
        assertEquals(LockTimerSync.Action.None, LockTimerSync.reconcile(mine, otherDevicesEasierTimer, 0L, now))
    }

    @Test
    fun `a local timer is pushed when the remote slot is empty, released, or ended`() {
        val mine = timer()
        assertEquals(LockTimerSync.Action.PushLocal, LockTimerSync.reconcile(mine, null, 0L, now))
        assertEquals(LockTimerSync.Action.PushLocal, LockTimerSync.reconcile(mine, signal(started = start - 10 * minute, cancelled = true), 0L, now))
        assertEquals(LockTimerSync.Action.PushLocal, LockTimerSync.reconcile(mine, signal(started = start - 500 * minute), 0L, now))
        // 끝난 로컬 약속은 없는 것으로 본다.
        assertEquals(LockTimerSync.Action.None, LockTimerSync.reconcile(mine, null, 0L, start + 500 * minute))
    }

    @Test
    fun `a timer released here is not adopted back, its release is pushed again`() {
        val remote = signal()
        assertEquals(LockTimerSync.Action.PushCancel, LockTimerSync.reconcile(null, remote, remote.startedAtMillis, now))
        // 다른 약속이 새로 걸렸으면 그건 받아온다.
        val next = signal(started = start + minute)
        assertEquals(LockTimerSync.Action.Adopt(next), LockTimerSync.reconcile(null, next, remote.startedAtMillis, now))
    }
}
