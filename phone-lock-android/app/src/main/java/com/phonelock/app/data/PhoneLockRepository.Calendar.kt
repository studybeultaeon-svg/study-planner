package com.phonelock.app.data

import androidx.room.withTransaction
import com.phonelock.shared.calc.PassSchedule
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

// 83차(다회독 상세화): 회독 진행은 이제 CalendarTask.passIndex/passTotal/passIntervalsCsv가
// 원천이다(업무마다 3~8회독 + 회독별 간격을 자유 설정). 과거 3단계(빨/노/초) 고정 스케줄은
// passTotal==3인 기본 케이스와 동치라 자연히 하위호환된다 — color 문자열은 레거시 코드가 여전히
// "red"(=passIndex 0) 비교에 쓰므로 표시용 라벨로만 남겨둔다(legacyColorLabel 참고).
private val koreanCollator = java.text.Collator.getInstance(java.util.Locale.KOREAN)

/** 신규/자동생성 CalendarTask.color에 쓸 하위호환 라벨. passIndex==0은 항상 "red"(기존 코드가 이 값으로
 *  "1회독 최초 생성"을 판정하므로 반드시 유지), 그 외엔 3단계 기존 이름을 최대한 재사용하고 4단계 이상은
 *  "pass{N}"으로 표시용 문자열만 채운다(실제 판정은 passIndex/passTotal 기준). */
private fun legacyColorLabel(passIndex: Int, passTotal: Int): String = when {
    passIndex <= 0 -> "red"
    // 85차: 최소 회독 수가 2로 내려가면서 passTotal==2일 때의 index1은 "중간"이 아니라 마지막(초록)이다
    // — 3단계(정확히 total==3)일 때만 index1을 "yellow"로 본다(83차 이전엔 최소가 3이라 <=3으로 충분했음).
    passTotal == 3 && passIndex == 1 -> "yellow"
    passIndex >= passTotal - 1 -> "green"
    else -> "pass$passIndex"
}

/** Firebase 등 외부에서 온 레거시 데이터(passIndex/passTotal 없음)의 color 문자열로부터 회독 위치를 추론. */
private fun inferPassIndexFromColor(color: String): Int = when (color) {
    "yellow" -> 1
    "green" -> 2
    else -> 0
}

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
        compareBy<CalendarTask> { it.passTotal - 1 - it.passIndex }
            .thenComparator { a, b -> koreanCollator.compare(a.name, b.name) }
    )
    sorted.forEachIndexed { i, t -> if (t.sortOrder != i) calendarTaskDao.update(t.copy(sortOrder = i)) }
}

suspend fun PhoneLockRepository.addCalendarTask(dateKey: String, name: String) {
    if (name.isBlank()) return
    val nextOrder = (calendarTaskDao.getByDate(dateKey).maxOfOrNull { it.sortOrder } ?: -1) + 1
    val passTotal = preferences.defaultPassCount
    calendarTaskDao.insert(
        CalendarTask(
            dateKey = dateKey,
            name = name.trim(),
            color = "red",
            status = null,
            sortOrder = nextOrder,
            multiPassEnabled = preferences.defaultMultiPassEnabled,
            passIndex = 0,
            passTotal = passTotal,
            passIntervalsCsv = preferences.defaultPassIntervalsCsv
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

/** 회독 수동 선택(83차) — 이 시리즈의 회독 수(passTotal)는 그대로 두고 현재 회독 위치(passIndex)만 직접 지정. */
suspend fun PhoneLockRepository.setCalendarTaskPassIndex(task: CalendarTask, newIndex: Int) {
    val clamped = newIndex.coerceIn(0, (task.passTotal - 1).coerceAtLeast(0))
    calendarTaskDao.update(task.copy(passIndex = clamped, color = legacyColorLabel(clamped, task.passTotal)))
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
    val nextIndex = task.passIndex + 1
    if (nextIndex >= task.passTotal) return
    val intervals = PassSchedule.parsePassIntervals(task.passIntervalsCsv, task.passTotal)
    val defaultDays = intervals.getOrElse(task.passIndex) { PassSchedule.DEFAULT_INTERVAL_DAYS }
    val days = task.nextDays?.takeIf { it >= 0 } ?: defaultDays
    val nKey = nextScheduleDateKey(dateKey, days)
    val existing = calendarTaskDao.getByDate(nKey)
    val exists = existing.any { it.name == task.name && it.passIndex == nextIndex }
    if (!exists) {
        val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        calendarTaskDao.insert(
            CalendarTask(
                dateKey = nKey, name = task.name, color = legacyColorLabel(nextIndex, task.passTotal), status = null,
                nextDays = task.nextDays, sortOrder = nextOrder,
                passIndex = nextIndex, passTotal = task.passTotal, passIntervalsCsv = task.passIntervalsCsv
            )
        )
    }
}

suspend fun PhoneLockRepository.revertCalendarAutoSchedule(dateKey: String, task: CalendarTask) {
    if (!task.multiPassEnabled) return
    val nextIndex = task.passIndex + 1
    if (nextIndex >= task.passTotal) return
    val intervals = PassSchedule.parsePassIntervals(task.passIntervalsCsv, task.passTotal)
    val defaultDays = intervals.getOrElse(task.passIndex) { PassSchedule.DEFAULT_INTERVAL_DAYS }
    val days = task.nextDays?.takeIf { it >= 0 } ?: defaultDays
    val nKey = nextScheduleDateKey(dateKey, days)
    val target = calendarTaskDao.getByDate(nKey).firstOrNull { it.name == task.name && it.passIndex == nextIndex && it.status == null }
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
    // 85차: 업무별 "다회독 사용" 토글이 OFF면 캘린더 연동 시 passCount를 무시하고 단회독(1회독)으로만
    // 만든다 — 자동 다음 회독 생성(multiPassEnabled)도 회독이 1개뿐이면 의미가 없으므로 함께 끈다.
    calendarTaskDao.insert(
        CalendarTask(
            dateKey = dateKey, name = taskName, color = "red", status = null,
            linkedCalc = calcTaskName, progressStep = (to - from + 1).toString(), sortOrder = nextOrder,
            multiPassEnabled = calcTask.multiPassUsageEnabled && preferences.defaultMultiPassEnabled,
            passIndex = 0,
            passTotal = if (calcTask.multiPassUsageEnabled) calcTask.passCount else 1,
            passIntervalsCsv = calcTask.passIntervalsCsv
        )
    )
    resortCalendarDay(dateKey)
    pushCalendarToFirebase()
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
                put("passIndex", t.passIndex)
                put("passTotal", t.passTotal)
                put("passIntervalsCsv", t.passIntervalsCsv)
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
            val color = t.optString("color", "white")
            list.add(
                CalendarTask(
                    dateKey = dateKey,
                    name = t.optString("name", ""),
                    color = color,
                    status = if (t.isNull("status")) null else t.optString("status", null),
                    nextDays = if (t.has("nextDays") && !t.isNull("nextDays")) t.getInt("nextDays") else null,
                    linkedCalc = if (t.isNull("linkedCalc")) null else t.optString("linkedCalc", null),
                    progressStep = if (t.isNull("progressStep")) null else t.optString("progressStep", null),
                    sortOrder = i,
                    multiPassEnabled = t.optBoolean("multiPassEnabled", false),
                    passIndex = if (t.has("passIndex")) t.optInt("passIndex", 0) else inferPassIndexFromColor(color),
                    passTotal = t.optInt("passTotal", 3),
                    passIntervalsCsv = t.optString("passIntervalsCsv", PassSchedule.DEFAULT_INTERVALS_CSV)
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
