package com.phonelock.desktop.data

import com.phonelock.shared.GrowthBoost
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 상점 물약 효과 구간(138차)이 data.json 저장/읽기와 Firebase 문서 변환을 왕복해도 그대로 남는지 — 데스크탑은
 * 새 필드를 `JsonStore.save()/parse()` 한쪽에만 넣으면 컴파일러가 못 잡고 재시작 때 조용히 사라진다(137차
 * `Group.description` 사례). 실제 APPDATA를 건드리지 않도록 내보내기 문자열 → 임시 파일 → 백업 파싱 경로를 쓴다.
 */
class GrowthBoostPersistenceTest {

    private val windows = listOf(
        GrowthBoost.Window("boost_small", 1_000L, 3_601_000L, 1.5),
        GrowthBoost.Window("boost_strong", 5_000_000L, 8_600_000L, 2.0)
    )

    @Test
    fun `data json round trip keeps boost windows`() {
        val data = AppData().apply { growthBoosts.addAll(windows) }
        val file = File.createTempFile("growth-boost", ".json")
        try {
            file.writeText(JsonStore.exportToJsonString(data))
            val parsed = JsonStore.parseBackupFile(file)!!
            assertEquals(windows, parsed.growthBoosts.toList())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `firebase json round trip keeps boost windows and drops broken ones`() {
        val arr = growthBoostWindowsToJson(windows)
        arr.put(org.json.JSONObject().apply { put("potionId", "x"); put("start", 10L); put("end", 5L); put("multiplier", 2.0) })
        assertEquals(windows, growthBoostWindowsFromJson(JSONArray(arr.toString())))
    }

    @Test
    fun `old data json without the field loads as empty`() {
        val file = File.createTempFile("growth-boost-old", ".json")
        try {
            file.writeText("{}")
            assertEquals(emptyList<GrowthBoost.Window>(), JsonStore.parseBackupFile(file)!!.growthBoosts.toList())
        } finally {
            file.delete()
        }
    }
}
