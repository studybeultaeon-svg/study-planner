package com.phonelock.desktop.data

import com.phonelock.shared.calc.PassSchedule
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 네이티브 캘린더(2단계) + 계산기 연동(캘린더↔계산기) 관련 [Repository] 확장 함수 모음 — 82차 감사
 * 후속 리팩토링으로 Repository.kt에서 분리했다(DECISIONS.md "82차 God Object 파일 분리" 참고, 안드로이드
 * PhoneLockRepository.Calendar.kt와 대칭). 클래스 자체는 그대로이고 파일만 나눴다. 웹앱 index.html의
 * calTasks[dateKey][]를 그대로 이식 — 배열 순서=표시 순서이므로 전체를 한 MutableList<CalendarTask>로
 * 갖고, 같은 dateKey를 가진 항목들의 리스트 내 상대 순서를 "그 날짜 안에서의 표시 순서"로 취급한다.
 */

// 83차(다회독 상세화): 회독 진행은 이제 CalendarTask.passIndex/passTotal/passIntervalsCsv가
// 원천이다(업무마다 3~8회독 + 회독별 간격을 자유 설정, 안드로이드판과 대칭). 과거 3단계(빨/노/초) 고정
// 스케줄은 passTotal==3인 기본 케이스와 동치라 자연히 하위호환된다.
private val koreanCollator = java.text.Collator.getInstance(java.util.Locale.KOREAN)

/** 신규/자동생성 CalendarTask.color에 쓸 하위호환 라벨(안드로이드판과 동일 규칙). */
private fun legacyColorLabel(passIndex: Int, passTotal: Int): String = when {
    passIndex <= 0 -> "red"
    passTotal <= 3 && passIndex == 1 -> "yellow"
    passIndex >= passTotal - 1 -> "green"
    else -> "pass$passIndex"
}

/** Firebase 등 레거시 데이터(passIndex/passTotal 없음)의 color 문자열로부터 회독 위치를 추론. */
internal fun inferPassIndexFromColor(color: String): Int = when (color) {
    "yellow" -> 1
    "green" -> 2
    else -> 0
}

fun Repository.getCalendarTasks(dateKey: String): List<CalendarTask> = synchronized(lock) {
    data.calendarTasks.filter { it.dateKey == dateKey }
}

/** 월 그리드 렌더용 — [fromKey, toKey] 범위(양 끝 포함, 문자열 비교)의 모든 일정. */
fun Repository.getCalendarTasksInRange(fromKey: String, toKey: String): List<CalendarTask> = synchronized(lock) {
    data.calendarTasks.filter { it.dateKey in fromKey..toKey }
}

/** 통계(5단계) 화면용 — 날짜 범위 없이 전체 일정. */
fun Repository.getAllCalendarTasks(): List<CalendarTask> = synchronized(lock) { data.calendarTasks.toList() }

fun Repository.dayGlobalIndices(dateKey: String): List<Int> =
    data.calendarTasks.indices.filter { data.calendarTasks[it].dateKey == dateKey }

fun Repository.sortCalendarDay(dateKey: String) {
    val indices = dayGlobalIndices(dateKey)
    if (indices.size < 2) return
    val sorted = indices.map { data.calendarTasks[it] }.sortedWith(
        compareBy<CalendarTask> { it.passTotal - 1 - it.passIndex }
            .thenComparator { a, b -> koreanCollator.compare(a.name, b.name) }
    )
    indices.forEachIndexed { i, globalIdx -> data.calendarTasks[globalIdx] = sorted[i] }
}

fun Repository.addCalendarTask(dateKey: String, name: String) = synchronized(lock) {
    if (name.isBlank()) return@synchronized
    data.calendarTasks.add(
        CalendarTask(
            dateKey = dateKey, name = name.trim(), color = "red", status = null,
            multiPassEnabled = data.defaultMultiPassEnabled,
            passIndex = 0, passTotal = data.defaultPassCount, passIntervalsCsv = data.defaultPassIntervalsCsv
        )
    )
    sortCalendarDay(dateKey)
    persist()
    pushCalendarToFirebase()
}

fun Repository.renameCalendarTask(dateKey: String, ordinal: Int, newName: String) = synchronized(lock) {
    if (newName.isBlank()) return@synchronized
    val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
    data.calendarTasks[idx] = data.calendarTasks[idx].copy(name = newName.trim())
    persist()
    pushCalendarToFirebase()
}

fun Repository.recolorCalendarTask(dateKey: String, ordinal: Int, newColor: String) = synchronized(lock) {
    val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
    data.calendarTasks[idx] = data.calendarTasks[idx].copy(color = newColor)
    sortCalendarDay(dateKey)
    persist()
    pushCalendarToFirebase()
}

/** 회독 수동 선택(83차) — 이 시리즈의 회독 수(passTotal)는 그대로 두고 현재 회독 위치(passIndex)만 직접 지정. */
fun Repository.setCalendarTaskPassIndex(dateKey: String, ordinal: Int, newIndex: Int) = synchronized(lock) {
    val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
    val task = data.calendarTasks[idx]
    val clamped = newIndex.coerceIn(0, (task.passTotal - 1).coerceAtLeast(0))
    data.calendarTasks[idx] = task.copy(passIndex = clamped, color = legacyColorLabel(clamped, task.passTotal))
    sortCalendarDay(dateKey)
    persist()
    pushCalendarToFirebase()
}

fun Repository.setCalendarTaskNextDays(dateKey: String, ordinal: Int, nextDays: Int?) = synchronized(lock) {
    val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
    data.calendarTasks[idx] = data.calendarTasks[idx].copy(nextDays = nextDays)
    persist()
    pushCalendarToFirebase()
}

/** ▲▼ 순서 변경(드래그 아님 — 웹앱도 배열 스왑 버튼 방식). direction은 -1(위) 또는 +1(아래). */
fun Repository.moveCalendarTaskOrder(dateKey: String, ordinal: Int, direction: Int) = synchronized(lock) {
    val indices = dayGlobalIndices(dateKey)
    val target = ordinal + direction
    if (ordinal !in indices.indices || target !in indices.indices) return@synchronized
    val a = indices[ordinal]; val b = indices[target]
    val tmp = data.calendarTasks[a]
    data.calendarTasks[a] = data.calendarTasks[b]
    data.calendarTasks[b] = tmp
    persist()
    pushCalendarToFirebase()
}

fun Repository.deleteCalendarTask(dateKey: String, ordinal: Int) = synchronized(lock) {
    val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
    data.calendarTasks.removeAt(idx)
    persist()
    pushCalendarToFirebase()
}

fun Repository.nextScheduleDateKey(dateKey: String, days: Int): String =
    LocalDate.parse(dateKey).plusDays(days.toLong()).toString()

fun Repository.setCalendarTaskMultiPass(dateKey: String, ordinal: Int, enabled: Boolean) = synchronized(lock) {
    val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
    data.calendarTasks[idx] = data.calendarTasks[idx].copy(multiPassEnabled = enabled)
    persist()
    pushCalendarToFirebase()
}

fun Repository.applyCalendarAutoSchedule(dateKey: String, task: CalendarTask) {
    if (!task.multiPassEnabled) return
    val nextIndex = task.passIndex + 1
    if (nextIndex >= task.passTotal) return
    val intervals = PassSchedule.parsePassIntervals(task.passIntervalsCsv, task.passTotal)
    val defaultDays = intervals.getOrElse(task.passIndex) { PassSchedule.DEFAULT_INTERVAL_DAYS }
    val days = task.nextDays?.takeIf { it >= 0 } ?: defaultDays
    val nKey = nextScheduleDateKey(dateKey, days)
    val exists = data.calendarTasks.any { it.dateKey == nKey && it.name == task.name && it.passIndex == nextIndex }
    if (!exists) {
        data.calendarTasks.add(
            CalendarTask(
                dateKey = nKey, name = task.name, color = legacyColorLabel(nextIndex, task.passTotal), status = null,
                nextDays = task.nextDays, passIndex = nextIndex, passTotal = task.passTotal, passIntervalsCsv = task.passIntervalsCsv
            )
        )
        sortCalendarDay(nKey)
    }
}

fun Repository.revertCalendarAutoSchedule(dateKey: String, task: CalendarTask) {
    if (!task.multiPassEnabled) return
    val nextIndex = task.passIndex + 1
    if (nextIndex >= task.passTotal) return
    val intervals = PassSchedule.parsePassIntervals(task.passIntervalsCsv, task.passTotal)
    val defaultDays = intervals.getOrElse(task.passIndex) { PassSchedule.DEFAULT_INTERVAL_DAYS }
    val days = task.nextDays?.takeIf { it >= 0 } ?: defaultDays
    val nKey = nextScheduleDateKey(dateKey, days)
    val removeIdx = data.calendarTasks.indexOfFirst {
        it.dateKey == nKey && it.name == task.name && it.passIndex == nextIndex && it.status == null
    }
    if (removeIdx >= 0) data.calendarTasks.removeAt(removeIdx)
}

/** 미완료(X) 처리 시 다음날로 같은 업무를 그대로 복사(원본은 유지, "복사" 기능과 동일한 필드 이식). */
fun Repository.applyIncompleteCarryOver(dateKey: String, task: CalendarTask) {
    val nKey = nextScheduleDateKey(dateKey, 1)
    val exists = data.calendarTasks.any { it.dateKey == nKey && it.name == task.name && it.color == task.color && it.status == null }
    if (!exists) {
        data.calendarTasks.add(task.copy(dateKey = nKey, status = null))
        sortCalendarDay(nKey)
    }
}

fun Repository.revertIncompleteCarryOver(dateKey: String, task: CalendarTask) {
    val nKey = nextScheduleDateKey(dateKey, 1)
    val removeIdx = data.calendarTasks.indexOfFirst {
        it.dateKey == nKey && it.name == task.name && it.color == task.color && it.status == null
    }
    if (removeIdx >= 0) data.calendarTasks.removeAt(removeIdx)
}

/**
 * 완료(O)/미완료(X) 토글 — 웹앱 renderModalActions의 완료/미완료 버튼과 동일한 규칙: 이미 같은
 * 상태면 취소(null로 되돌림), 아니면 기존 O/X의 부작용(자동생성된 다음 회독/다음날 복사)을 먼저
 * 되돌린 뒤 새 상태를 적용한다. targetStatus에 O를 주면 완료 처리(자동으로 다음 회독 생성), X를
 * 주면 미완료 처리(자동으로 다음날에 같은 업무 복사, 원본은 그대로 남김 — 35차 세션 신규).
 */
fun Repository.setCalendarTaskStatus(dateKey: String, ordinal: Int, targetStatus: String) = synchronized(lock) {
    val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
    val current = data.calendarTasks[idx]
    if (current.status == "O") revertCalendarAutoSchedule(dateKey, current)
    if (current.status == "X") revertIncompleteCarryOver(dateKey, current)
    if (current.status == targetStatus) {
        // 완료 취소 — 계산기 연동 항목이었다면(1회독=red일 때만 최초 반영했으므로 그때만) 진행량을 되돌린다.
        if (current.status == "O" && current.linkedCalc != null && current.color == "red") {
            adjustLinkedCalcProgress(current.linkedCalc, -linkedProgressAmount(current))
        }
        data.calendarTasks[idx] = data.calendarTasks[idx].copy(status = null)
    } else {
        val updated = data.calendarTasks[idx].copy(status = targetStatus)
        data.calendarTasks[idx] = updated
        if (targetStatus == "O") {
            applyCalendarAutoSchedule(dateKey, updated)
            if (updated.linkedCalc != null && updated.color == "red") {
                adjustLinkedCalcProgress(updated.linkedCalc, linkedProgressAmount(updated))
            }
        }
        if (targetStatus == "X") applyIncompleteCarryOver(dateKey, updated)
    }
    persist()
    pushCalendarToFirebase()
}

// ══════════════════════════════════════════════════════
// 계산기 연동(캘린더↔계산기, 웹앱 index.html addLinkedTasksFromModal/deductCalcQty/isCalTaskLinkedDone
// 이식) — 계산기 업무의 특정 범위(예: "51~60쪽")를 캘린더 일정으로 만들어두면, 완료 체크할 때 그
// 계산기 업무의 진행량(progress)에 자동으로 더해진다(체크 해제하면 되돌림). 웹앱은 옛 4색 체계의
// "red"(당시 1회독)에서만 반영했는데, 지금 8색 체계에선 1회독이 white라서 그 자리를 대신한다.
// ══════════════════════════════════════════════════════

/** progressStep이 비어있거나 0 이하면 1(웹앱 getLinkedProgressAmount와 동일 fallback). */
fun Repository.linkedProgressAmount(task: CalendarTask): Double =
    task.progressStep?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0

private fun formatCalcNumber(n: Double): String {
    val r = Math.round(n * 100) / 100.0
    return if (r == Math.floor(r)) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
}

/** delta만큼 그 계산기 업무의 progress를 더하고(0 밑으로는 안 내려감) Firebase에도 반영한다. */
fun Repository.adjustLinkedCalcProgress(calcTaskName: String, delta: Double) {
    val idx = data.calcTasks.indexOfFirst { it.name == calcTaskName }
    if (idx !in data.calcTasks.indices) return
    val t = data.calcTasks[idx]
    val newProgress = ((t.progress.toDoubleOrNull() ?: 0.0) + delta).coerceAtLeast(0.0)
    data.calcTasks[idx] = t.copy(progress = formatCalcNumber(newProgress), modifiedAt = nowLabel(), modifiedAtTs = System.currentTimeMillis())
    data.calcTasksTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

/**
 * 계산기 업무의 [from, to] 범위를 이 날짜의 캘린더 일정으로 새로 만든다(예: "국어 51~60쪽"). 1회독
 * 색(white)으로 시작해서 다른 일정과 동일하게 다음 회독 자동 생성/스트릭 등 기존 로직을 그대로 탄다.
 */
fun Repository.addLinkedCalendarTask(dateKey: String, calcTaskName: String, from: Int, to: Int) = synchronized(lock) {
    if (from > to || from < 1) return@synchronized
    val calcTask = data.calcTasks.find { it.name == calcTaskName } ?: return@synchronized
    val unit = calcTask.unit.trim()
    val taskName = "$calcTaskName $from~$to$unit"
    if (data.calendarTasks.any { it.dateKey == dateKey && it.name == taskName }) return@synchronized
    data.calendarTasks.add(
        CalendarTask(
            dateKey = dateKey, name = taskName, color = "red", status = null,
            linkedCalc = calcTaskName, progressStep = (to - from + 1).toString(),
            multiPassEnabled = data.defaultMultiPassEnabled,
            passIndex = 0, passTotal = calcTask.passCount, passIntervalsCsv = calcTask.passIntervalsCsv
        )
    )
    sortCalendarDay(dateKey)
    persist()
    pushCalendarToFirebase()
}

/**
 * 그 날짜에 calcTaskName과 연동된, 완료(O) 처리된 일정들의 progressStep 합이 dayQuota 이상이면
 * "그날 목표 달성"으로 본다(웹앱 isCalTaskLinkedDone과 동일 판정, 일정표 화면에서 ✅ 표시용).
 */
fun Repository.isLinkedGoalAchieved(dateKey: String, calcTaskName: String, dayQuota: Double): Boolean = synchronized(lock) {
    if (dayQuota <= 0) return@synchronized false
    val doneTotal = data.calendarTasks
        .filter { it.dateKey == dateKey && it.linkedCalc == calcTaskName && it.status == "O" }
        .sumOf { it.progressStep?.toDoubleOrNull() ?: 0.0 }
    doneTotal >= dayQuota
}

/** 이동: 대상 날짜로 옮기고 nextDays/linkedCalc/progressStep은 버린다(웹앱 moveCalTask 경로와 동일 동작). */
fun Repository.moveCalendarTaskToDate(dateKey: String, ordinal: Int, targetDateKey: String) = synchronized(lock) {
    val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
    val task = data.calendarTasks[idx]
    data.calendarTasks.removeAt(idx)
    data.calendarTasks.add(CalendarTask(dateKey = targetDateKey, name = task.name, color = task.color, status = task.status))
    sortCalendarDay(targetDateKey)
    persist()
    pushCalendarToFirebase()
}

/** 복사: 상태는 초기화(null)하고 nextDays 등 부가 필드는 그대로 옮긴다. 원본은 유지. */
fun Repository.copyCalendarTaskToDate(dateKey: String, ordinal: Int, targetDateKey: String) = synchronized(lock) {
    val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
    val task = data.calendarTasks[idx]
    data.calendarTasks.add(task.copy(dateKey = targetDateKey, status = null))
    sortCalendarDay(targetDateKey)
    persist()
    pushCalendarToFirebase()
}

/**
 * 오늘 기준 monthsAgo개월 이전의 사용시간/재확인 카운터/공부기록을 영구 삭제한다(되돌리기 없음,
 * 캘린더의 archiveOldCalendarTasks()와 같은 정리 패턴 — 전문가 종합분석 보고서 #21). 통계 화면의
 * 스트릭 계산은 캘린더 일정 기준이라 이 정리 대상과 무관하다. 삭제된 레코드 수 반환.
 */
fun Repository.pruneOldStats(monthsAgo: Int = 12): Int = synchronized(lock) {
    val cutoff = effectiveDate(data.dailyResetHour).minusMonths(monthsAgo.toLong()).toString()
    val before = data.usageRecords.size + data.confirmCounters.size + data.studyLog.size
    data.usageRecords.removeAll { it.date < cutoff }
    data.confirmCounters.removeAll { it.date < cutoff }
    data.studyLog.removeAll { it.dateKey < cutoff }
    val removed = before - (data.usageRecords.size + data.confirmCounters.size + data.studyLog.size)
    if (removed > 0) persist()
    removed
}

/**
 * 자동 백업/정리(82차, §9, 안드로이드판과 대칭) — 하루 1회(dailyResetHour 기준 "오늘"이 바뀔 때)
 * 실행되는 유지보수 묶음. 12개월 통계 자동 정리(월 1회) + 클라우드 자동 백업(켜져 있으면 매일).
 * 두 작업 모두 실패해도 예외를 던지지 않는다(호출부가 화면 진입 경로라 여기서 죽으면 안 됨).
 */
fun Repository.runDailyMaintenanceIfNeeded() {
    val today = effectiveDate(data.dailyResetHour).toString()

    val lastPrune = data.lastAutoStatsPruneDate
    val pruneDue = lastPrune.isBlank() ||
        runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(lastPrune), LocalDate.parse(today)) >= 30 }.getOrDefault(true)
    if (pruneDue) {
        runCatching { pruneOldStats(12) }
        synchronized(lock) { data.lastAutoStatsPruneDate = today; persist() }
    }

    val backupEnabled = synchronized(lock) { data.cloudBackupEnabled }
    val lastBackupDate = synchronized(lock) { data.lastCloudBackupDate }
    if (backupEnabled && lastBackupDate != today) {
        runCatching { uploadCloudBackupNow() }
    }
}

/** 오늘 기준 6개월 이전 일정을 영구 삭제(되돌리기 없음, 웹앱 confirmArchiveOldCalTasks와 동일). 삭제된 날짜 수 반환. */
fun Repository.archiveOldCalendarTasks(): Int = synchronized(lock) {
    val cutoffKey = LocalDate.now().minusMonths(6).toString()
    val staleDates = data.calendarTasks.map { it.dateKey }.filter { it < cutoffKey }.toSet()
    if (staleDates.isEmpty()) return@synchronized 0
    data.calendarTasks.removeAll { it.dateKey in staleDates }
    persist()
    pushCalendarToFirebase()
    staleDates.size
}

fun Repository.calendarTasksToJson(): JSONObject {
    val root = JSONObject()
    data.calendarTasks.groupBy { it.dateKey }.forEach { (dateKey, tasks) ->
        val arr = JSONArray()
        tasks.forEach { t ->
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

fun Repository.calendarTasksFromJson(root: JSONObject): MutableList<CalendarTask> {
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

/** 변경 직후 fire-and-forget으로 Firebase에 전체 캘린더 문서를 올린다(호출부는 이미 lock을 쥐고 있음). */
fun Repository.pushCalendarToFirebase() {
    val ts = System.currentTimeMillis()
    data.calendarTs = ts
    val tasksJson = calendarTasksToJson()
    val url = data.fbDatabaseUrl; val key = data.fbApiKey
    Thread {
        com.phonelock.desktop.monitor.PomodoroSyncClient.writeCalendarTasks(url, key, tasksJson, ts)
    }.start()
}

/**
 * 캘린더 화면 진입/앱 시작 시 호출 — 원격이 로컬보다 최신이면(문서 단위 LWW) 로컬을 덮어쓰고,
 * 로컬이 더 최신이면 반대로 원격에 푸시한다. 네트워크 호출을 포함하므로 호출부(UI)에서 백그라운드
 * 스레드/코루틴에서 실행해야 한다.
 */
fun Repository.syncCalendarFromFirebase() {
    val (url, key) = synchronized(lock) { data.fbDatabaseUrl to data.fbApiKey }
    val result = com.phonelock.desktop.monitor.PomodoroSyncClient.readCalendarTasks(url, key) ?: return
    synchronized(lock) {
        if (result.ts > data.calendarTs) {
            data.calendarTasks.clear()
            data.calendarTasks.addAll(calendarTasksFromJson(result.tasksJson))
            data.calendarTs = result.ts
            persist()
        } else if (data.calendarTs > result.ts) {
            pushCalendarToFirebase()
        }
    }
}
