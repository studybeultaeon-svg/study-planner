package com.phonelock.app.ui.launcher

import com.phonelock.app.data.CalendarTask
import com.phonelock.app.data.Routine
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.phonelock.app.ui.AppInfo
import com.phonelock.app.ui.MainActivity

/** 런처 홈에 고정할 수 있는 앱 개수 상한(130차 결정) — 홈 화면이 한눈에 끝나야 자극이 줄기 때문에 일부러 작게 둔다. */
const val LAUNCHER_FAVORITE_MAX = 6

/**
 * 런처 목록에 "무엇을, 어떤 이름으로, 어떤 순서로" 보여줄지만 계산하는 순수 로직.
 * PackageManager 같은 기기 API에 의존하지 않아 유닛테스트로 검증한다([LauncherAppFilterTest]).
 *
 * 설치된 앱 조회 자체는 이미 있는 [com.phonelock.app.ui.getLaunchableApps]를 그대로 쓴다(중복 금지).
 * 2단계에서 "지금 차단 중인 앱 숨김"도 이 파일에 들어온다.
 */

/** 런처에서 보여줄 이름 — 사용자가 바꿔둔 이름이 있으면 그걸, 없으면 원래 앱 이름. */
fun launcherLabel(app: AppInfo, renames: Map<String, String>): String =
    renames[app.packageName]?.trim()?.takeIf { it.isNotEmpty() } ?: app.label

/**
 * 숨긴 앱과 지금 잠긴 앱([isLockedForLauncher])을 빼고, 바꾼 이름을 입힌 뒤, 그 이름 기준으로 정렬한 목록.
 *
 * [hidden]은 사용자가 직접 감춘 것이라 설정에서 언제든 되돌릴 수 있고, [locked]/[allowOnly]는 시간이 지나
 * 차단이 끝나면 저절로 다시 나타난다 — 둘을 한 덩어리로 합치지 않고 따로 받는 이유다.
 */
fun visibleLauncherApps(
    all: List<AppInfo>,
    hidden: Set<String>,
    renames: Map<String, String>,
    locked: Set<String> = emptySet(),
    allowOnly: Set<String>? = null
): List<AppInfo> = all.asSequence()
    .filter { it.packageName !in hidden && !isLockedForLauncher(it.packageName, locked, allowOnly) }
    .map { it.copy(label = launcherLabel(it, renames)) }
    .sortedBy { it.label.lowercase() }
    .toList()

/**
 * 홈/앱 서랍에 "지금 잠긴 앱 N개"로 보여줄 수. 사용자가 직접 숨긴 앱은 애초에 목록에 없던 앱이라
 * 세지 않는다 — 이 숫자는 "차단이 실제로 돌고 있다"는 신호라서, 아무것도 안 보이는 화면과
 * 차단이 고장난 화면을 구분해주는 게 목적이다.
 */
fun lockedAppCount(all: List<AppInfo>, hidden: Set<String>, locked: Set<String>, allowOnly: Set<String>? = null): Int =
    all.count { it.packageName !in hidden && isLockedForLauncher(it.packageName, locked, allowOnly) }

/**
 * 런처가 지금 감춰야 하는 앱인지. [locked]는 "고른 앱만 차단"하는 규칙·타이머에 걸린 앱이고, [allowOnly]는
 * 전체 잠금(142차)이 걸려 있을 때만 값이 있다 — 그때는 허용한 앱(과 전화·시계 같은 필수 앱)만 남고 나머지는
 * 전부 감춘다. 홈 화면 모양은 그대로 두고 앱 목록만 줄어드는 것이 전체 잠금 중의 런처 동작이다.
 */
fun isLockedForLauncher(packageName: String, locked: Set<String>, allowOnly: Set<String>?): Boolean =
    packageName in locked || (allowOnly != null && packageName !in allowOnly)

/** 검색어 필터 — 바꾼 이름이 이미 입혀진 목록([visibleLauncherApps]의 결과)에 적용한다. */
fun searchLauncherApps(apps: List<AppInfo>, query: String): List<AppInfo> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return apps
    return apps.filter { it.label.contains(trimmed, ignoreCase = true) }
}

/**
 * 즐겨찾기 패키지명 목록을 실제 앱 목록과 맞춰 홈에 그릴 순서대로 돌려준다.
 * 지운 앱/숨긴 앱은 [apps]에 없으므로 자연히 빠진다(설정에서 따로 정리할 필요 없음).
 */
fun favoriteApps(apps: List<AppInfo>, favoritePackages: List<String>): List<AppInfo> {
    val byPackage = apps.associateBy { it.packageName }
    return favoritePackages.mapNotNull { byPackage[it] }.take(LAUNCHER_FAVORITE_MAX)
}

/**
 * 즐겨찾기 토글. 이미 [LAUNCHER_FAVORITE_MAX]개가 찬 상태에서 새로 추가하려 하면 **그대로 돌려준다** —
 * 호출부는 크기가 안 변한 걸로 "꽉 찼다" 안내를 띄운다(조용히 오래된 항목을 밀어내면 사용자가
 * 즐겨찾기가 사라진 이유를 알 수 없다).
 */
fun toggleFavorite(current: List<String>, packageName: String): List<String> = when {
    packageName in current -> current - packageName
    current.size >= LAUNCHER_FAVORITE_MAX -> current
    else -> current + packageName
}

/**
 * 런처 홈에서 한 번에 들어갈 수 있는 갓생살기종합세트 화면. [route]는 [com.phonelock.app.ui.MainActivity]가
 * 인텐트로 받는 값 그대로이고, 라우트 문자열은 `MainActivity.ROUTE_*` 상수를 써서 탭 정의와 어긋날 수 없게 한다.
 * [emoji]는 143차까지 앱 하단 탭과 맞추던 글자다 — 144차부터 앱 탭이 벡터 아이콘으로 바뀌어 런처 화면도 [route]로
 * 같은 아이콘을 골라 그린다(LauncherScreen `ShortcutTile`). 런처와 앱에서 같은 탭이 다른 얼굴로 보이면 안 되기 때문이다.
 */
data class GodsaengShortcut(val label: String, val emoji: String, val route: String)

/**
 * 승인 범위(루틴/공부/관리/소셜)에 맞는 바로가기만 남긴다 — 판단 기준과 순서는 `MainActivity.visibleTabs()`와
 * 같고, 인자는 `AppPreferences.perm*` 값을 그대로 받는다(런처를 Room/로그인에 묶지 않기 위해 여기선
 * SharedPreferences 값만 쓴다).
 *
 * **132차(사용자 요청): 앱 하단 탭 5개(홈/루틴/공부/관리/모임)만 둔다.** 131차엔 서브탭까지 펼쳐
 * 10종(타이머·캘린더·계산기…)을 늘어놓았는데, 런처 홈이 앱 목차가 되어버려 요청대로 되돌렸다.
 * 설정은 앱에서도 탭이 아니라 홈 우상단 버튼으로만 들어가므로(118차) 여기서도 탭과 나란히 두지 않는다.
 */
fun godsaengShortcuts(
    routine: Boolean,
    study: Boolean,
    manage: Boolean,
    social: Boolean
): List<GodsaengShortcut> = listOfNotNull(
    GodsaengShortcut("홈", "🌱", MainActivity.ROUTE_HOME),
    GodsaengShortcut("루틴", "📋", MainActivity.ROUTE_ROUTINE).takeIf { routine },
    GodsaengShortcut("집중", "🎯", MainActivity.ROUTE_STUDY).takeIf { study },
    GodsaengShortcut("관리", "🗂️", MainActivity.ROUTE_MANAGE).takeIf { manage },
    GodsaengShortcut("모임", "👥", MainActivity.ROUTE_GROUP).takeIf { social }
)

/**
 * 오늘 예정된 루틴 중 **아직 안 한 것 가운데 가장 먼저 해야 할 하나**(133차, 사용자 요청 — 런처 홈에
 * "오늘 루틴"이라는 말 대신 밀린 루틴의 이름을 띄운다). 순서는 앱 루틴 "오늘" 탭(`RoutineTodayTab`)과
 * 같게 시간대 지정 루틴이 시간순으로 먼저고, 시간대 없는 루틴은 사용자가 정한 순서(sortOrder, 목록에
 * 들어온 순서)대로 뒤에 붙는다. 오늘 예정된 걸 전부 마쳤으면 null.
 */
fun nextPendingRoutine(scheduledToday: List<Routine>, completedIds: Set<Long>): Routine? =
    scheduledToday.sortedWith(compareBy(nullsLast()) { it.timeSlot })
        .firstOrNull { it.id !in completedIds }

/**
 * 오늘 날짜의 캘린더 일정만 앱 캘린더와 같은 순서(sortOrder)로 추린다(133차). 런처는 일정 전체를 한 번에
 * 읽어오므로(`getAllCalendarTasksOnce`) 정렬이 없고, `CalendarTaskDao.getByDate`와 같은 기준을 여기서 맞춘다.
 */
fun todayCalendarTasks(all: List<CalendarTask>, dateKey: String): List<CalendarTask> =
    all.filter { it.dateKey == dateKey }.sortedBy { it.sortOrder }

/**
 * 런처 홈에 띄우는 디데이 후보 하나(133차, 사용자 요청 "후보는 여러 개 만들어두고 홈에는 딱 하나만").
 * [date]는 "yyyy-MM-dd", [id]는 고정된 하나를 가리키는 열쇠([AppPreferences.launcherPinnedDdayId]).
 *
 * Room이 아니라 SharedPreferences에 두는 이유: 이 값은 런처 홈 표시 전용이라 동기화·통계·마이그레이션이
 * 필요 없고, **런처가 Room을 더 무겁게 읽으면 안 되기 때문**이다(홈 버튼이 안 먹는 폰을 만들지 않는 게
 * 이 화면의 첫 번째 규칙 — [[HANDOFF.md]] "다음 세션에서 반드시 알아야 하는 내용" 23번).
 *
 * 저장 형식은 한 줄에 하나씩 `id<TAB>이름<TAB>날짜`다. 앱의 다른 JSON 설정(`launcher_renames_json`)과
 * 달리 이 형식을 쓴 건 파싱까지 유닛테스트로 덮기 위해서다 — 안드로이드 `org.json`은 JVM 유닛테스트에서
 * "not mocked"로 죽는다.
 */
data class LauncherDday(val id: String, val name: String, val date: String)

/** 저장된 문자열 → 후보 목록. 형식이 깨진 줄은 조용히 건너뛴다(홈이 못 뜨는 것보다 한 줄을 잃는 게 낫다). */
fun parseLauncherDdays(raw: String): List<LauncherDday> = raw.lineSequence().mapNotNull { line ->
    val parts = line.split('\t')
    if (parts.size != 3) return@mapNotNull null
    val (id, name, date) = parts.map { it.trim() }
    if (id.isEmpty() || name.isEmpty() || date.isEmpty()) null else LauncherDday(id, name, date)
}.toList()

/** 후보 목록 → 저장할 문자열. 구분자가 값 안에 들어가면 줄이 깨지므로 저장 시점에 공백으로 바꾼다. */
fun launcherDdaysToText(ddays: List<LauncherDday>): String = ddays.joinToString("\n") {
    "${ddayField(it.id)}\t${ddayField(it.name)}\t${ddayField(it.date)}"
}

private fun ddayField(value: String): String = value.replace('\t', ' ').replace('\n', ' ').trim()

/** 홈에 고정된 후보. 고른 적이 없거나 그 후보를 지웠으면 null — 이때 홈은 디데이 줄을 아예 그리지 않는다. */
fun pinnedLauncherDday(ddays: List<LauncherDday>, pinnedId: String?): LauncherDday? =
    ddays.firstOrNull { it.id == pinnedId }

/**
 * 홈에 찍을 문구("수능 D-54" / "수능 D-day" / 지난 날짜면 "수능 D+3"). 날짜가 깨져 있으면 null을
 * 돌려주고 호출부는 줄을 그리지 않는다.
 */
fun launcherDdayText(dday: LauncherDday, today: LocalDate): String? = runCatching {
    val days = ChronoUnit.DAYS.between(today, LocalDate.parse(dday.date))
    val label = when {
        days == 0L -> "D-day"
        days > 0 -> "D-$days"
        else -> "D+${-days}"
    }
    "${dday.name} $label"
}.getOrNull()
