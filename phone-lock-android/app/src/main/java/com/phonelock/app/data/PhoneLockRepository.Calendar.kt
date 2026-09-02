package com.phonelock.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.launch
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 네이티브 캘린더(2단계) + 계산기 연동(캘린더↔계산기) 관련 [PhoneLockRepository] 확장 함수 모음 —
 * 82차 감사 후속 리팩토링으로 PhoneLockRepository.kt에서 분리했다(DECISIONS.md "82차 God Object 파일
 * 분리" 참고). 클래스 자체는 그대로이고 파일만 나눴다. 웹앱 index.html의 calTasks[dateKey][]를 그대로
 * 이식 — Room엔 배열 순서 개념이 없어 sortOrder 정수 필드로 같은 dateKey 안에서의 표시 순서를 관리한다.
 * Firebase는 데스크탑과 동일하게 users/{user}/calendar 경로에 { tasks:{dateKey:[...]}, _ts } 전체문서
 * 단위 LWW로 동기화한다.
 */

// 77차: 8단계(51차, 데스크탑판과 대칭)에서 다시 3단계(빨/노/초)로 축소(사용자 요청). 저장된 기존
// color 값(white/orange/blue/indigo/purple)은 그대로 두되(51차와 같은 전례: "라벨만 바뀐다")
// 새로 고르거나 자동 생성되는 회독은 이 3색만 쓴다.
private val CALENDAR_COLOR_ORDER = mapOf(
    "green" to 0, "yellow" to 1, "red" to 2
)

/**
 * color -> (다음 회독 color, 기본 간격일수). green(3회독)은 종단이라 매핑 없음. 데스크탑판과 대칭.
 * 사용자 지정값 — 1회독(만든 날)부터 누적 0/3/7일차: red(1회독, 0일)→yellow(2회독, +3일)→
 * green(3회독, 1회독 기준 +7일 = yellow 기준 +4일).
 */
private val CALENDAR_SCHEDULE = mapOf(
    "red" to ("yellow" to 3),
    "yellow" to ("green" to 4)
)

private val koreanCollator = java.text.Collator.getInstance(java.util.Locale.KOREAN)

var PhoneLockRepository.calendarTs: Long
    get() = preferences.calendarTs
    set(value) { preferences.calendarTs = value }

suspend fun PhoneLockRepository.getCalendarTasks(dateKey: String): List<CalendarTask> = calendarTaskDao.getByDate(dateKey)

/** 월 그리드 렌더용 — [fromKey, toKey] 범위(양 끝 포함)의 모든 일정. */
suspend fun PhoneLockRepository.getCalendarTasksInRange(fromKey: String, toKey: String): List<CalendarTask> =
    calendarTaskDao.getByDateRange(fromKey, toKey)

/** 통계(5단계) 화면용 — 날짜 범위 없이 전체 일정. */
suspend fun PhoneLockRepository.getAllCalendarTasksOnce(): List<CalendarTask> = calendarTaskDao.getAllOnce()

suspend fun PhoneLockRepository.resortCalendarDay(dateKey: String) {
    val sorted = calendarTaskDao.getByDate(dateKey).sortedWith(
        compareBy<CalendarTask> { CALENDAR_COLOR_ORDER[it.color] ?: 99 }
            .thenComparator { a, b -> koreanCollator.compare(a.name, b.name) }
    )
    sorted.forEachIndexed { i, t -> if (t.sortOrder != i) calendarTaskDao.update(t.copy(sortOrder = i)) }
}

suspend fun PhoneLockRepository.addCalendarTask(dateKey: String, name: String) {
    if (name.isBlank()) return
    val nextOrder = (calendarTaskDao.getByDate(dateKey).maxOfOrNull { it.sortOrder } ?: -1) + 1
    calendarTaskDao.insert(
        CalendarTask(
            dateKey = dateKey,
            name = name.trim(),
            color = "red",
            status = null,
            sortOrder = nextOrder,
            multiPassEnabled = preferences.defaultMultiPassEnabled
        )
    )
    resortCalendarDay(dateKey)
    pushCalendarToFirebase()
}

suspend fun PhoneLockRepository.renameCalendarTask(task: CalendarTask, newName: String) {
    if (newName.isBlank()) return
    calendarTaskDao.update(task.copy(name = newName.trim()))
    pushCalendarToFirebase()
}

suspend fun PhoneLockRepository.recolorCalendarTask(task: CalendarTask, newColor: String) {
    calendarTaskDao.update(task.copy(color = newColor))
    resortCalendarDay(task.dateKey)
    pushCalendarToFirebase()
}

suspend fun PhoneLockRepository.setCalendarTaskNextDays(task: CalendarTask, nextDays: Int?) {
    calendarTaskDao.update(task.copy(nextDays = nextDays))
    pushCalendarToFirebase()
}

suspend fun PhoneLockRepository.setCalendarTaskMultiPass(task: CalendarTask, enabled: Boolean) {
    calendarTaskDao.update(task.copy(multiPassEnabled = enabled))
    pushCalendarToFirebase()
}

/** ▲▼ 순서 변경(드래그 아님 — 웹앱도 배열 스왑 버튼 방식). direction은 -1(위) 또는 +1(아래). */
suspend fun PhoneLockRepository.moveCalendarTaskOrder(task: CalendarTask, direction: Int) {
    val dayTasks = calendarTaskDao.getByDate(task.dateKey)
    val idx = dayTasks.indexOfFirst { it.id == task.id }
    val targetIdx = idx + direction
    if (idx < 0 || targetIdx !in dayTasks.indices) return
    val a = dayTasks[idx]; val b = dayTasks[targetIdx]
    calendarTaskDao.update(a.copy(sortOrder = b.sortOrder))
    calendarTaskDao.update(b.copy(sortOrder = a.sortOrder))
    pushCalendarToFirebase()
}

suspend fun PhoneLockRepository.deleteCalendarTask(task: CalendarTask) {
    calendarTaskDao.delete(task)
    pushCalendarToFirebase()
}

fun PhoneLockRepository.nextScheduleDateKey(dateKey: String, days: Int): String =
    LocalDate.parse(dateKey).plusDays(days.toLong()).toString()

suspend fun PhoneLockRepository.applyCalendarAutoSchedule(dateKey: String, task: CalendarTask) {
    if (!task.multiPassEnabled) return
    val (nextColor, defaultDays) = CALENDAR_SCHEDULE[task.color] ?: return
    val days = task.nextDays?.takeIf { it >= 0 } ?: defaultDays
    val nKey = nextScheduleDateKey(dateKey, days)
    val existing = calendarTaskDao.getByDate(nKey)
    val exists = existing.any { it.name == task.name && it.color == nextColor }
    if (!exists) {
        val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        calendarTaskDao.insert(
            CalendarTask(dateKey = nKey, name = task.name, color = nextColor, status = null, nextDays = task.nextDays, sortOrder = nextOrder)
        )
    }
}

suspend fun PhoneLockRepository.revertCalendarAutoSchedule(dateKey: String, task: CalendarTask) {
    if (!task.multiPassEnabled) return
    val (nextColor, defaultDays) = CALENDAR_SCHEDULE[task.color] ?: return
    val days = task.nextDays?.takeIf { it >= 0 } ?: defaultDays
    val nKey = nextScheduleDateKey(dateKey, days)
    val target = calendarTaskDao.getByDate(nKey).firstOrNull { it.name == task.name && it.color == nextColor && it.status == null }
    if (target != null) calendarTaskDao.delete(target)
}

/** 미완료(X) 처리 시 다음날로 같은 업무를 그대로 복사(원본은 유지, "복사" 기능과 동일한 필드 이식). */
suspend fun PhoneLockRepository.applyIncompleteCarryOver(dateKey: String, task: CalendarTask) {
    val nKey = nextScheduleDateKey(dateKey, 1)
    val existing = calendarTaskDao.getByDate(nKey)
    val exists = existing.any { it.name == task.name && it.color == task.color && it.status == null }
    if (!exists) {
        val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        calendarTaskDao.insert(task.copy(id = 0, dateKey = nKey, status = null, sortOrder = nextOrder))
    }
}

suspend fun PhoneLockRepository.revertIncompleteCarryOver(dateKey: String, task: CalendarTask) {
    val nKey = nextScheduleDateKey(dateKey, 1)
    val target = calendarTaskDao.getByDate(nKey).firstOrNull { it.name == task.name && it.color == task.color && it.status == null }
    if (target != null) calendarTaskDao.delete(target)
}

/**
 * 완료(O)/미완료(X) 토글 — 웹앱 renderModalActions의 완료/미완료 버튼과 동일한 규칙: 이미 같은
 * 상태면 취소(null로 되돌림), 아니면 기존 O/X의 부작용(자동생성된 다음 회독/다음날 복사)을 먼저
 * 되돌린 뒤 새 상태를 적용한다. targetStatus에 O를 주면 완료 처리(자동으로 다음 회독 생성), X를
 * 주면 미완료 처리(자동으로 다음날에 같은 업무 복사, 원본은 그대로 남김 — 35차 세션 신규).
 */
suspend fun PhoneLockRepository.setCalendarTaskStatus(task: CalendarTask, targetStatus: String) {
    if (task.status == "O") revertCalendarAutoSchedule(task.dateKey, task)
    if (task.status == "X") revertIncompleteCarryOver(task.dateKey, task)
    if (task.status == targetStatus) {
        // 완료 취소 — 계산기 연동 항목이었다면(1회독=red일 때만 최초 반영했으므로 그때만) 진행량을 되돌린다.
        if (task.status == "O" && task.linkedCalc != null && task.color == "red") {
            adjustLinkedCalcProgress(task.linkedCalc, -linkedProgressAmount(task))
        }
        calendarTaskDao.update(task.copy(status = null))
    } else {
        val updated = task.copy(status = targetStatus)
        calendarTaskDao.update(updated)
        if (targetStatus == "O") {
            applyCalendarAutoSchedule(task.dateKey, updated)
            if (updated.linkedCalc != null && updated.color == "red") {
                adjustLinkedCalcProgress(updated.linkedCalc, linkedProgressAmount(updated))
                maybeAutoGenerateNextLinkedTask(updated.linkedCalc, updated.dateKey)
            }
        }
        if (targetStatus == "X") applyIncompleteCarryOver(task.dateKey, updated)
    }
    pushCalendarToFirebase()
}

// ══════════════════════════════════════════════════════
// 계산기 연동(캘린더↔계산기, 웹앱 addLinkedTasksFromModal/deductCalcQty/isCalTaskLinkedDone 이식,
// 51차 신규, 데스크탑판과 대칭) — 계산기 업무의 특정 범위를 캘린더 일정으로 만들어두면, 완료 체크할
// 때 그 업무의 progress에 자동으로 더해진다(체크 해제하면 되돌림).
// ══════════════════════════════════════════════════════

fun PhoneLockRepository.linkedProgressAmount(task: CalendarTask): Double =
    task.progressStep?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0

private fun formatCalcNumber(n: Double): String {
    val r = Math.round(n * 100) / 100.0
    return if (r == Math.floor(r)) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
}

suspend fun PhoneLockRepository.adjustLinkedCalcProgress(calcTaskName: String, delta: Double) {
    val t = calcTaskDao.getAll().find { it.name == calcTaskName } ?: return
    val newProgress = ((t.progress.toDoubleOrNull() ?: 0.0) + delta).coerceAtLeast(0.0)
    calcTaskDao.update(t.copy(progress = formatCalcNumber(newProgress), modifiedAt = nowLabel(), modifiedAtTs = System.currentTimeMillis()))
    pushCalcTasksAndSaved()
}

/** 계산기 업무의 [from, to] 범위를 이 날짜의 캘린더 일정으로 새로 만든다(예: "국어 51~60쪽"). */
suspend fun PhoneLockRepository.addLinkedCalendarTask(dateKey: String, calcTaskName: String, from: Int, to: Int) {
    if (from > to || from < 1) return
    val calcTask = calcTaskDao.getAll().find { it.name == calcTaskName } ?: return
    val unit = calcTask.unit.trim()
    val taskName = "$calcTaskName $from~$to$unit"
    val existing = calendarTaskDao.getByDate(dateKey)
    if (existing.any { it.name == taskName }) return
    val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
    calendarTaskDao.insert(
        CalendarTask(
            dateKey = dateKey, name = taskName, color = "red", status = null,
            linkedCalc = calcTaskName, progressStep = (to - from + 1).toString(), sortOrder = nextOrder,
            multiPassEnabled = preferences.defaultMultiPassEnabled
        )
    )
    resortCalendarDay(dateKey)
    pushCalendarToFirebase()
}

/**
 * 캘린더 일정 자동 생성(82차, 사용자 지정 스펙) — 계산기 업무의 `autoGenEnabled`가 켜져 있으면,
 * 연동 일정을 완료(O)할 때마다 다음날에 다음 배치("이름 N~M단위" 형식, [addLinkedCalendarTask]를
 * 그대로 재사용해 할당량 연동도 자동으로 유지됨)를 자동으로 만든다. 진행량(progress)이 이미
 * [adjustLinkedCalcProgress]로 갱신된 뒤 호출되므로, progress를 그대로 "다음 시작점"으로 쓴다.
 */
suspend fun PhoneLockRepository.maybeAutoGenerateNextLinkedTask(calcTaskName: String, dateKey: String) {
    val calcTask = calcTaskDao.getAll().find { it.name == calcTaskName } ?: return
    if (!calcTask.autoGenEnabled || calcTask.autoGenBatchSize <= 0) return
    val total = calcTask.qty.toDoubleOrNull() ?: return
    val done = calcTask.progress.toDoubleOrNull() ?: 0.0
    if (done >= total) return // 이미 목표 전체를 달성했으면 더 만들 게 없음
    val from = done.toInt() + 1
    val to = (from + calcTask.autoGenBatchSize - 1).coerceAtMost(total.toInt())
    if (from > to) return
    addLinkedCalendarTask(nextScheduleDateKey(dateKey, 1), calcTaskName, from, to)
}

/** 그 날짜에 calcTaskName과 연동된, 완료(O) 처리된 일정들의 progressStep 합이 dayQuota 이상이면 달성. */
suspend fun PhoneLockRepository.isLinkedGoalAchieved(dateKey: String, calcTaskName: String, dayQuota: Double): Boolean {
    if (dayQuota <= 0) return false
    val doneTotal = calendarTaskDao.getByDate(dateKey)
        .filter { it.linkedCalc == calcTaskName && it.status == "O" }
        .sumOf { it.progressStep?.toDoubleOrNull() ?: 0.0 }
    return doneTotal >= dayQuota
}

/** 이동: 대상 날짜로 옮기고 nextDays/linkedCalc/progressStep은 버린다(웹앱 moveCalTask 경로와 동일 동작). */
suspend fun PhoneLockRepository.moveCalendarTaskToDate(task: CalendarTask, targetDateKey: String) {
    calendarTaskDao.delete(task)
    val existing = calendarTaskDao.getByDate(targetDateKey)
    val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
    calendarTaskDao.insert(CalendarTask(dateKey = targetDateKey, name = task.name, color = task.color, status = task.status, sortOrder = nextOrder))
    resortCalendarDay(targetDateKey)
    pushCalendarToFirebase()
}

/** 복사: 상태는 초기화(null)하고 nextDays 등 부가 필드는 그대로 옮긴다. 원본은 유지. */
suspend fun PhoneLockRepository.copyCalendarTaskToDate(task: CalendarTask, targetDateKey: String) {
    val existing = calendarTaskDao.getByDate(targetDateKey)
    val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
    calendarTaskDao.insert(task.copy(id = 0, dateKey = targetDateKey, status = null, sortOrder = nextOrder))
    resortCalendarDay(targetDateKey)
    pushCalendarToFirebase()
}

/**
 * 오늘 기준 monthsAgo개월 이전의 사용시간/재확인 카운터/공부기록을 영구 삭제한다(되돌리기 없음,
 * 데스크탑판과 대칭 — 전문가 종합분석 보고서 #21). 삭제된 레코드 수 반환.
 */
suspend fun PhoneLockRepository.pruneOldStats(monthsAgo: Int = 12): Int {
    val cutoff = effectiveDate(dailyResetHour).minusMonths(monthsAgo.toLong()).toString()
    return usageDao.deleteBefore(cutoff) + confirmCounterDao.deleteBefore(cutoff) + studyLogEntryDao.deleteBefore(cutoff)
}

/** 오늘 기준 6개월 이전 일정을 영구 삭제(되돌리기 없음, 웹앱 confirmArchiveOldCalTasks와 동일). 삭제된 항목 수 반환. */
suspend fun PhoneLockRepository.archiveOldCalendarTasks(): Int {
    val cutoffKey = LocalDate.now().minusMonths(6).toString()
    val removed = calendarTaskDao.deleteBefore(cutoffKey)
    if (removed > 0) pushCalendarToFirebase()
    return removed
}

fun PhoneLockRepository.calendarTasksToJson(tasks: List<CalendarTask>): JSONObject {
    val root = JSONObject()
    tasks.groupBy { it.dateKey }.forEach { (dateKey, dayTasks) ->
        val arr = JSONArray()
        dayTasks.sortedBy { it.sortOrder }.forEach { t ->
            arr.put(JSONObject().apply {
                put("name", t.name)
                put("color", t.color)
                put("status", t.status ?: JSONObject.NULL)
                put("nextDays", t.nextDays ?: JSONObject.NULL)
                put("linkedCalc", t.linkedCalc ?: JSONObject.NULL)
                put("progressStep", t.progressStep ?: JSONObject.NULL)
                put("multiPassEnabled", t.multiPassEnabled)
            })
        }
        root.put(dateKey, arr)
    }
    return root
}

fun PhoneLockRepository.calendarTasksFromJson(root: JSONObject): List<CalendarTask> {
    val list = mutableListOf<CalendarTask>()
    root.keys().forEach { dateKey ->
        val arr = root.optJSONArray(dateKey) ?: JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            list.add(
                CalendarTask(
                    dateKey = dateKey,
                    name = t.optString("name", ""),
                    color = t.optString("color", "white"),
                    status = if (t.isNull("status")) null else t.optString("status", null),
                    nextDays = if (t.has("nextDays") && !t.isNull("nextDays")) t.getInt("nextDays") else null,
                    linkedCalc = if (t.isNull("linkedCalc")) null else t.optString("linkedCalc", null),
                    progressStep = if (t.isNull("progressStep")) null else t.optString("progressStep", null),
                    sortOrder = i,
                    multiPassEnabled = t.optBoolean("multiPassEnabled", false)
                )
            )
        }
    }
    return list
}

/** 변경 직후 fire-and-forget으로 Firebase에 전체 캘린더 문서를 올린다. */
fun PhoneLockRepository.pushCalendarToFirebase() {
    val ts = System.currentTimeMillis()
    calendarTs = ts
    ioScope.launch {
        val tasksJson = calendarTasksToJson(calendarTaskDao.getAllOnce())
        com.phonelock.app.service.PomodoroSyncClient.writeCalendarTasks(fbDatabaseUrl, fbApiKey, tasksJson, ts)
    }
}

/**
 * 캘린더 화면 진입 시 호출 — 원격이 로컬보다 최신이면(문서 단위 LWW) 로컬을 덮어쓰고, 로컬이 더
 * 최신이면 반대로 원격에 푸시한다.
 */
suspend fun PhoneLockRepository.syncCalendarFromFirebase() {
    val result = com.phonelock.app.service.PomodoroSyncClient.readCalendarTasks(fbDatabaseUrl, fbApiKey) ?: return
    if (result.ts > calendarTs) {
        val tasks = calendarTasksFromJson(result.tasksJson)
        // delete+insert를 하나의 트랜잭션으로 묶는다 — 따로 실행하면 그 사이 프로세스가 죽었을 때
        // 로컬 캘린더가 빈 상태로 남을 수 있다(importBackupJson()은 이미 트랜잭션으로 처리 중).
        db.withTransaction {
            calendarTaskDao.deleteAll()
            tasks.forEach { calendarTaskDao.insert(it) }
        }
        calendarTs = result.ts
    } else if (calendarTs > result.ts) {
        pushCalendarToFirebase()
    }
}
