package com.phonelock.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 기기 간 동기화 병합 규칙 회귀 테스트(127차). 같은 규칙이 안드로이드판
 * `PhoneLockRepository.mergeRemoteStudyLog`/`peerUsageSecondsOf`에도 동일하게 들어있다.
 *
 * 126차가 옛 칸(플랫폼 이름 그대로인 "android"/"desktop")을 칸 이름만 보고 버리는 바람에, 아직
 * 업데이트 안 된 폰의 기록이 데스크탑에서 통째로 사라졌다 — "칸 이름이 아니라 기록 지문으로 내 것만
 * 뺀다"는 새 규칙을 이 테스트로 고정한다.
 */
class DeviceSyncMergeTest {

    private fun entryJson(taskName: String, seconds: Int, startedAt: Long): JSONObject =
        JSONObject().apply {
            put("taskName", taskName); put("seconds", seconds); put("startedAt", startedAt)
            put("note", ""); put("tag", "")
        }

    @Test
    fun `아직 업데이트 안 된 기기가 쓰는 옛 칸의 기록도 보인다`() {
        val remote = JSONObject().apply {
            put("android", JSONArray().put(entryJson("수학", 1200, 1000L)))
        }

        val merged = mergeRemoteStudyLog(remote, "2026-09-18", ownKey = "desktop-abc123", localFingerprints = emptySet())

        assertEquals(1, merged.size)
        assertEquals("수학", merged[0].taskName)
        assertEquals(1200, merged[0].seconds)
    }

    @Test
    fun `옛 칸에 남아있는 내 기록은 지문이 겹치므로 이중 계산되지 않는다`() {
        val mine = entryJson("영어", 600, 2000L)
        val remote = JSONObject().apply {
            // 업데이트 전 이 기기가 올려둔 그대로 남아있는 옛 칸.
            put("desktop", JSONArray().put(mine))
            // 다른 기기가 올린 새 칸.
            put("android-zzz999", JSONArray().put(entryJson("국어", 300, 3000L)))
        }
        val localFingerprints = setOf(studyLogFingerprint(2000L, 600, "영어"))

        val merged = mergeRemoteStudyLog(remote, "2026-09-18", ownKey = "desktop-abc123", localFingerprints)

        assertEquals(listOf("국어"), merged.map { it.taskName })
    }

    @Test
    fun `내 칸은 지문이 없어도 통째로 건너뛴다`() {
        val remote = JSONObject().apply {
            put("desktop-abc123", JSONArray().put(entryJson("내기록", 100, 4000L)))
        }

        val merged = mergeRemoteStudyLog(remote, "2026-09-18", ownKey = "desktop-abc123", localFingerprints = emptySet())

        assertEquals(emptyList<StudyLogEntry>(), merged)
    }

    @Test
    fun `일일 사용시간은 같은 플랫폼의 다른 기기 몫도 합산한다`() {
        // 폰과 태블릿이 각자 자기 칸에 올린 상태 — 126차까지는 둘 다 "android" 한 칸을 덮어써서
        // 폰에서 태블릿 사용시간이 아예 안 보였고, 그만큼 일일 한도가 느슨해졌다.
        val usage = mapOf(
            "android-phone01" to 600,
            "android-tab002" to 900,
            "desktop-abc123" to 300
        )

        assertEquals(1200, peerUsageSecondsOf(usage, ownKey = "android-phone01"))
    }

    @Test
    fun `일일 사용시간도 옛 칸을 남의 기기 몫으로 합산한다`() {
        val usage = mapOf("android" to 600, "desktop-abc123" to 300)

        assertEquals(600, peerUsageSecondsOf(usage, ownKey = "desktop-abc123"))
    }
}
