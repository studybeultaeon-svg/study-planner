package com.phonelock.desktop.data

import com.phonelock.shared.lock.LockTimer
import com.phonelock.shared.lock.LockTimerPreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 타이머 동기화(143차)가 추가한 저장 필드가 data.json 저장/읽기를 왕복해도 남는지 — 데스크탑은 새 필드를
 * `JsonStore.save()/parse()` 한쪽에만 넣으면 컴파일러가 못 잡고 재시작 때 조용히 사라진다(137차 `Group.description`
 * 사례, [GrowthBoostPersistenceTest]와 같은 방식). 사라지면 "이 기기에서 푼 약속"을 잊어서 원격에 남은 같은 약속을
 * 다시 받아와 이미 푼 타이머가 되살아난다.
 */
class LockTimerPersistenceTest {

    @Test
    fun `timer break unlock flag and closed marker survive a data json round trip`() {
        val timer = LockTimer.create(1_000L, 5, 30, true, setOf("chrome.exe"), setOf("study.com"), 3, pomodoroBreakUnlock = true)
        val preset = LockTimerPreset(pomodoroBreakUnlock = true, allowedApps = setOf("chrome.exe"))
        val data = AppData().apply {
            lockTimerEncoded = timer.encode()
            lockTimerPresetEncoded = preset.encode()
            lockTimerClosedStartedAt = 123_456L
        }
        val file = File.createTempFile("lock-timer", ".json")
        try {
            file.writeText(JsonStore.exportToJsonString(data))
            val parsed = JsonStore.parseBackupFile(file)!!
            assertEquals(123_456L, parsed.lockTimerClosedStartedAt)
            assertEquals(timer, LockTimer.decode(parsed.lockTimerEncoded))
            assertEquals(preset, LockTimerPreset.decode(parsed.lockTimerPresetEncoded))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `old data json without the fields loads with no marker and no break unlock`() {
        val file = File.createTempFile("lock-timer-old", ".json")
        try {
            file.writeText("{}")
            val parsed = JsonStore.parseBackupFile(file)!!
            assertEquals(0L, parsed.lockTimerClosedStartedAt)
            assertFalse(LockTimerPreset.decode(parsed.lockTimerPresetEncoded).pomodoroBreakUnlock)
        } finally {
            file.delete()
        }
    }
}
