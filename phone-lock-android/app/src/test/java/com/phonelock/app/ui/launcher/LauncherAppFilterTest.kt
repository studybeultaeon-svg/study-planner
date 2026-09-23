package com.phonelock.app.ui.launcher

import com.phonelock.app.data.CalendarTask
import com.phonelock.app.data.Routine
import com.phonelock.app.ui.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 미니멀 런처가 "무엇을 어떤 이름으로 보여줄지" 계산하는 순수 로직 검증(130차). */
class LauncherAppFilterTest {

    private val apps = listOf(
        AppInfo(label = "YouTube", packageName = "com.google.android.youtube"),
        AppInfo(label = "전화", packageName = "com.android.dialer"),
        AppInfo(label = "Instagram", packageName = "com.instagram.android")
    )

    @Test
    fun `숨긴 앱은 목록에서 빠진다`() {
        val visible = visibleLauncherApps(apps, hidden = setOf("com.instagram.android"), renames = emptyMap())
        assertEquals(listOf("com.google.android.youtube", "com.android.dialer"), visible.map { it.packageName })
    }

    @Test
    fun `바꾼 이름이 적용되고 그 이름 기준으로 정렬된다`() {
        val visible = visibleLauncherApps(
            apps,
            hidden = emptySet(),
            renames = mapOf("com.google.android.youtube" to "영상")
        )
        // 정렬 기준이 원래 앱 이름("YouTube")이 아니라 바꾼 이름("영상")이라 한글 두 개 사이로 들어간다.
        assertEquals(listOf("Instagram", "영상", "전화"), visible.map { it.label })
    }

    @Test
    fun `빈 이름으로 바꾸면 원래 앱 이름을 쓴다`() {
        val renamed = launcherLabel(apps[0], mapOf("com.google.android.youtube" to "   "))
        assertEquals("YouTube", renamed)
    }

    @Test
    fun `검색은 대소문자를 무시하고 바꾼 이름에도 걸린다`() {
        val visible = visibleLauncherApps(apps, emptySet(), mapOf("com.instagram.android" to "사진"))
        assertEquals(listOf("YouTube"), searchLauncherApps(visible, "youtu").map { it.label })
        assertEquals(listOf("사진"), searchLauncherApps(visible, "사진").map { it.label })
        assertEquals(3, searchLauncherApps(visible, "   ").size)
    }

    @Test
    fun `즐겨찾기는 저장된 순서를 지키고 없는 앱은 건너뛴다`() {
        val favorites = favoriteApps(apps, listOf("com.android.dialer", "com.example.deleted", "com.instagram.android"))
        assertEquals(listOf("전화", "Instagram"), favorites.map { it.label })
    }

    @Test
    fun `차단 중인 앱은 목록에서 빠지고 개수로만 알려준다`() {
        val locked = setOf("com.instagram.android")
        val visible = visibleLauncherApps(apps, hidden = emptySet(), renames = emptyMap(), locked = locked)
        assertEquals(listOf("com.google.android.youtube", "com.android.dialer"), visible.map { it.packageName })
        assertEquals(1, lockedAppCount(apps, hidden = emptySet(), locked = locked))
    }

    @Test
    fun `사용자가 직접 숨긴 앱은 잠김 개수에 세지 않는다`() {
        val locked = setOf("com.instagram.android", "com.google.android.youtube")
        assertEquals(1, lockedAppCount(apps, hidden = setOf("com.instagram.android"), locked = locked))
    }

    @Test
    fun `차단 중인 앱은 즐겨찾기에서도 사라진다`() {
        val visible = visibleLauncherApps(apps, emptySet(), emptyMap(), locked = setOf("com.android.dialer"))
        val favorites = favoriteApps(visible, listOf("com.android.dialer", "com.google.android.youtube"))
        assertEquals(listOf("YouTube"), favorites.map { it.label })
    }

    @Test
    fun `즐겨찾기 토글은 추가와 제거를 오간다`() {
        val added = toggleFavorite(emptyList(), "a")
        assertEquals(listOf("a"), added)
        assertEquals(emptyList<String>(), toggleFavorite(added, "a"))
    }

    @Test
    fun `즐겨찾기가 상한까지 차면 새 앱을 추가하지 않는다`() {
        val full = (1..LAUNCHER_FAVORITE_MAX).map { "app$it" }
        assertEquals(full, toggleFavorite(full, "new"))
        // 이미 들어있는 항목을 빼는 건 꽉 찬 상태에서도 된다.
        assertEquals(LAUNCHER_FAVORITE_MAX - 1, toggleFavorite(full, "app1").size)
    }

    @Test
    fun `승인 범위 밖 기능은 바로가기에 나오지 않는다`() {
        val onlyRoutine = godsaengShortcuts(routine = true, study = false, manage = false, social = false)
        assertEquals(listOf("홈", "루틴"), onlyRoutine.map { it.label })
    }

    @Test
    fun `홈 바로가기는 승인 범위와 무관하게 항상 남는다`() {
        val none = godsaengShortcuts(routine = false, study = false, manage = false, social = false)
        assertEquals(listOf("홈"), none.map { it.label })
    }

    @Test
    fun `바로가기는 앱 하단 탭 5개와 순서까지 같다`() {
        val all = godsaengShortcuts(routine = true, study = true, manage = true, social = true)
        assertEquals(listOf("홈", "루틴", "공부", "규칙", "모임"), all.map { it.label })
        assertEquals(
            listOf(
                com.phonelock.app.ui.MainActivity.ROUTE_HOME,
                com.phonelock.app.ui.MainActivity.ROUTE_ROUTINE,
                com.phonelock.app.ui.MainActivity.ROUTE_STUDY,
                com.phonelock.app.ui.MainActivity.ROUTE_MANAGE,
                com.phonelock.app.ui.MainActivity.ROUTE_GROUP
            ),
            all.map { it.route }
        )
    }

    @Test
    fun `바로가기마다 이모지가 있다`() {
        val all = godsaengShortcuts(routine = true, study = true, manage = true, social = true)
        assertEquals(emptyList<String>(), all.filter { it.emoji.isBlank() }.map { it.label })
    }

    // --- 133차: 갓생 카드가 "지금 먼저 해야 할 것"을 고르는 규칙 ---

    private fun routine(id: Long, title: String, timeSlot: String? = null) =
        Routine(id = id, title = title, timeSlot = timeSlot)

    private fun task(name: String, sortOrder: Int, status: String? = null, dateKey: String = "2026-09-21") =
        CalendarTask(id = sortOrder.toLong(), dateKey = dateKey, name = name, color = "", status = status, sortOrder = sortOrder)

    @Test
    fun `밀린 루틴은 시간대 순으로 고르고 시간 없는 루틴은 뒤로 간다`() {
        val scheduled = listOf(
            routine(1, "설거지"),
            routine(2, "저녁 운동", "19:00"),
            routine(3, "아침 스트레칭", "07:00")
        )
        assertEquals("아침 스트레칭", nextPendingRoutine(scheduled, emptySet())?.title)
        assertEquals("저녁 운동", nextPendingRoutine(scheduled, setOf(3L))?.title)
        assertEquals("설거지", nextPendingRoutine(scheduled, setOf(2L, 3L))?.title)
    }

    @Test
    fun `오늘 루틴을 다 하면 먼저 할 루틴이 없다`() {
        val scheduled = listOf(routine(1, "설거지"), routine(2, "아침 스트레칭", "07:00"))
        assertNull(nextPendingRoutine(scheduled, setOf(1L, 2L)))
    }

    @Test
    fun `오늘 일정만 화면과 같은 순서로 추려진다`() {
        val all = listOf(
            task("수학 3단원", sortOrder = 1),
            task("영어 단어", sortOrder = 0),
            task("내일 모의고사", sortOrder = 0, dateKey = "2026-09-22")
        )
        val todays = todayCalendarTasks(all, "2026-09-21")
        assertEquals(listOf("영어 단어", "수학 3단원"), todays.map { it.name })
    }

    @Test
    fun `오늘 일정 중 완료한 것은 먼저 할 일정에서 빠진다`() {
        val all = listOf(task("영어 단어", sortOrder = 0, status = "O"), task("수학 3단원", sortOrder = 1))
        val todays = todayCalendarTasks(all, "2026-09-21")
        assertEquals("수학 3단원", todays.firstOrNull { it.status != "O" }?.name)
        assertEquals(1, todays.count { it.status == "O" })
    }

    // --- 133차: 런처 홈 디데이(후보 여러 개 + 홈에는 고정한 하나만) ---

    private val ddayA = LauncherDday(id = "dday_1", name = "수능", date = "2026-11-13")
    private val ddayB = LauncherDday(id = "dday_2", name = "기말고사", date = "2026-09-21")

    @Test
    fun `디데이 후보는 저장했다 읽어도 그대로다`() {
        val restored = parseLauncherDdays(launcherDdaysToText(listOf(ddayA, ddayB)))
        assertEquals(listOf(ddayA, ddayB), restored)
    }

    @Test
    fun `구분자가 섞인 이름도 줄을 깨뜨리지 않는다`() {
        val messy = LauncherDday(id = "dday_3", name = "수능\t대비\n합숙", date = "2026-11-13")
        val restored = parseLauncherDdays(launcherDdaysToText(listOf(messy)))
        assertEquals(listOf(LauncherDday("dday_3", "수능 대비 합숙", "2026-11-13")), restored)
    }

    @Test
    fun `형식이 깨진 줄은 건너뛰고 나머지는 살린다`() {
        val raw = "망가진줄\n" + launcherDdaysToText(listOf(ddayA)) + "\n\t\t"
        assertEquals(listOf(ddayA), parseLauncherDdays(raw))
    }

    @Test
    fun `홈에는 고정한 후보 하나만 뜨고 고른 게 없으면 아무것도 안 뜬다`() {
        val ddays = listOf(ddayA, ddayB)
        assertEquals(ddayB, pinnedLauncherDday(ddays, "dday_2"))
        assertNull(pinnedLauncherDday(ddays, null))
        assertNull(pinnedLauncherDday(ddays, "dday_지워짐"))
    }

    @Test
    fun `디데이 문구는 남은 날짜와 당일과 지난 날짜를 구분한다`() {
        val today = java.time.LocalDate.parse("2026-09-21")
        assertEquals("수능 D-53", launcherDdayText(ddayA, today))
        assertEquals("기말고사 D-day", launcherDdayText(ddayB, today))
        assertEquals("개학 D+3", launcherDdayText(LauncherDday("x", "개학", "2026-09-18"), today))
    }

    @Test
    fun `날짜가 깨진 디데이는 문구를 만들지 않는다`() {
        assertNull(launcherDdayText(LauncherDday("x", "언젠가", "날짜없음"), java.time.LocalDate.parse("2026-09-21")))
    }
}
