package com.phonelock.desktop.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 루틴앱 v1(47차 설계, DECISIONS.md 참고) 관련 [Repository] 확장 함수 모음 — 82차 감사 후속
 * 리팩토링으로 Repository.kt에서 분리했다(DECISIONS.md "82차 God Object 파일 분리" 참고, 안드로이드
 * PhoneLockRepository.Routine.kt와 대칭). 클래스 자체는 그대로이고 파일만 나눴다. 51차: 캘린더와
 * 동일한 "전체 문서 단위 LWW"로 Firebase 동기화(users/{user}/routines).
 */

fun Repository.getRoutines(): List<Routine> = synchronized(lock) {
    data.routines.filter { !it.archived }.sortedBy { it.sortOrder }
}

/** 전체 루틴(보관 제외) — 알림/요약 등에서 쓴다(getRoutines와 동일하지만 의도를 드러내는 별칭). */
fun Repository.getAllRoutines(): List<Routine> = synchronized(lock) { data.routines.filter { !it.archived }.sortedBy { it.sortOrder } }

fun Repository.addRoutine(routine: Routine) = synchronized(lock) {
    val nextOrder = (data.routines.maxOfOrNull { it.sortOrder } ?: -1) + 1
    data.routines.add(routine.copy(id = data.nextRoutineId, sortOrder = nextOrder))
    data.nextRoutineId++
    persist()
    pushRoutinesToFirebase()
}

fun Repository.updateRoutine(updated: Routine) = synchronized(lock) {
    val idx = data.routines.indexOfFirst { it.id == updated.id }
    if (idx !in data.routines.indices) return@synchronized
    data.routines[idx] = updated
    persist()
    pushRoutinesToFirebase()
}

fun Repository.deleteRoutine(routineId: Long) = synchronized(lock) {
    data.routines.removeAll { it.id == routineId }
    data.routineLogs.removeAll { it.routineId == routineId }
    persist()
    pushRoutinesToFirebase()
}

fun Repository.moveRoutineOrder(routineId: Long, direction: Int) = synchronized(lock) {
    val all = data.routines.sortedBy { it.sortOrder }
    val idx = all.indexOfFirst { it.id == routineId }
    val target = idx + direction
    if (idx < 0 || target !in all.indices) return@synchronized
    val a = all[idx]; val b = all[target]
    val aIdx = data.routines.indexOfFirst { it.id == a.id }
    val bIdx = data.routines.indexOfFirst { it.id == b.id }
    data.routines[aIdx] = a.copy(sortOrder = b.sortOrder)
    data.routines[bIdx] = b.copy(sortOrder = a.sortOrder)
    persist()
    pushRoutinesToFirebase()
}

/** 특정 두 루틴의 sortOrder를 직접 맞바꾼다 — "오늘" 탭에서 시간대 미지정 루틴끼리의 ▲/▼ 순서 버튼용
 *  (moveRoutineOrder는 전역 sortOrder 인접 항목을 스왑해 시간대 지정 루틴과 뒤섞일 수 있어 UI가 표시
 *  중인 두 루틴 id를 직접 받는 이 함수를 대신 쓴다). */
fun Repository.swapRoutineOrder(idA: Long, idB: Long) = synchronized(lock) {
    val aIdx = data.routines.indexOfFirst { it.id == idA }
    val bIdx = data.routines.indexOfFirst { it.id == idB }
    if (aIdx < 0 || bIdx < 0) return@synchronized
    val a = data.routines[aIdx]; val b = data.routines[bIdx]
    data.routines[aIdx] = a.copy(sortOrder = b.sortOrder)
    data.routines[bIdx] = b.copy(sortOrder = a.sortOrder)
    persist()
    pushRoutinesToFirebase()
}

fun Repository.copyRoutine(routine: Routine) = synchronized(lock) {
    val nextOrder = (data.routines.maxOfOrNull { it.sortOrder } ?: -1) + 1
    data.routines.add(routine.copy(id = data.nextRoutineId, title = "${routine.title} (복사본)", sortOrder = nextOrder, archived = false))
    data.nextRoutineId++
    persist()
    pushRoutinesToFirebase()
}

fun Repository.getRoutineLogsForDate(dateKey: String): List<RoutineLog> = synchronized(lock) {
    data.routineLogs.filter { it.dateKey == dateKey }
}

fun Repository.isRoutineCompleted(routineId: Long, dateKey: String): Boolean = synchronized(lock) {
    data.routineLogs.any { it.routineId == routineId && it.dateKey == dateKey }
}

/** 날짜 하나에 대한 완료 체크를 토글한다(존재하면 삭제=미완료, 없으면 추가=완료). */
fun Repository.toggleRoutineLog(routineId: Long, dateKey: String) = synchronized(lock) {
    val completed: Boolean
    if (data.routineLogs.any { it.routineId == routineId && it.dateKey == dateKey }) {
        data.routineLogs.removeAll { it.routineId == routineId && it.dateKey == dateKey }
        completed = false
    } else {
        data.routineLogs.add(RoutineLog(routineId, dateKey))
        completed = true
    }
    persist()
    pushRoutinesToFirebase()
    onRoutineToggled(routineId, dateKey, completed)
}

/** RoutineEngine.currentStreak에 넘길 완료 날짜 집합. */
fun Repository.getRoutineCompletedDateKeys(routineId: Long): Set<String> = synchronized(lock) {
    data.routineLogs.filter { it.routineId == routineId }.map { it.dateKey }.toSet()
}

/** routines/routineLogs 2종 JSON 배열을 한 번에 담는 결과. */
data class RoutineExportJson(val routinesArr: JSONArray, val logsArr: JSONArray)

/**
 * 루틴/로그를 Firebase JSON 배열 2개로 변환한다. 기기별 로컬 id를 그대로 실어보내면 다른 기기의
 * id 체계와 충돌하므로(캘린더가 dateKey+배열순서로 식별하는 것과 같은 이유), routines 배열 안에서의
 * 인덱스를 log.routineIndex로 쓴다 — 실제 id는 반입하는 쪽에서 새로 배정한다.
 */
fun Repository.routinesToJsonArrays(): RoutineExportJson {
    val sorted = data.routines.sortedBy { it.sortOrder }
    val indexById = sorted.mapIndexed { idx, r -> r.id to idx }.toMap()
    val routinesArr = JSONArray()
    sorted.forEach { r ->
        routinesArr.put(JSONObject().apply {
            put("title", r.title)
            put("icon", r.icon)
            put("timeSlot", r.timeSlot ?: JSONObject.NULL)
            put("daysMask", r.daysMask)
            put("trackStreak", r.trackStreak)
            put("defenseType", r.defenseType)
            put("defenseCount", r.defenseCount)
            put("sortOrder", r.sortOrder)
            put("archived", r.archived)
            put("notifyEnabled", r.notifyEnabled)
            put("startDate", r.startDate ?: JSONObject.NULL)
            put("endDate", r.endDate ?: JSONObject.NULL)
        })
    }
    val logsArr = JSONArray()
    data.routineLogs.forEach { log ->
        val idx = indexById[log.routineId] ?: return@forEach
        logsArr.put(JSONObject().apply {
            put("routineIndex", idx)
            put("dateKey", log.dateKey)
        })
    }
    return RoutineExportJson(routinesArr, logsArr)
}

/** 반입된 루틴/로그를 담는 결과(안드로이드판과 대칭) — routines는 이미 새 로컬 id가 배정된 상태. */
data class RoutineImportResult(val routines: MutableList<Routine>, val logs: MutableList<RoutineLog>)

/** JSON 배열 2개(routines, routineLogs)를 로컬 Routine/RoutineLog로 되돌린다 — 새 로컬 id를 배열 순서대로 새로 배정. */
fun Repository.routinesFromJsonArrays(routinesJson: JSONArray, logsJson: JSONArray): RoutineImportResult {
    val newRoutines = mutableListOf<Routine>()
    for (i in 0 until routinesJson.length()) {
        val r = routinesJson.getJSONObject(i)
        newRoutines.add(
            Routine(
                id = (i + 1).toLong(),
                title = r.optString("title", ""),
                icon = r.optString("icon", ""),
                timeSlot = if (r.isNull("timeSlot")) null else r.optString("timeSlot", null),
                daysMask = r.optInt("daysMask", 127),
                trackStreak = r.optBoolean("trackStreak", false),
                defenseType = r.optString("defenseType", "NONE"),
                defenseCount = r.optInt("defenseCount", 0),
                sortOrder = r.optInt("sortOrder", i),
                archived = r.optBoolean("archived", false),
                notifyEnabled = r.optBoolean("notifyEnabled", false),
                startDate = if (r.isNull("startDate")) null else r.optString("startDate", null),
                endDate = if (r.isNull("endDate")) null else r.optString("endDate", null)
            )
        )
    }
    val newLogs = mutableListOf<RoutineLog>()
    for (i in 0 until logsJson.length()) {
        val l = logsJson.getJSONObject(i)
        val idx = l.optInt("routineIndex", -1)
        if (idx !in newRoutines.indices) continue
        newLogs.add(RoutineLog(newRoutines[idx].id, l.optString("dateKey", "")))
    }
    return RoutineImportResult(newRoutines, newLogs)
}

/** 반입된 루틴을 실제 로컬 상태(data.routines)에 적용한다 — import/sync 양쪽에서 공용. */
private fun Repository.applyRoutineImport(result: RoutineImportResult) {
    data.routines.clear(); data.routines.addAll(result.routines)
    data.routineLogs.clear(); data.routineLogs.addAll(result.logs)
    data.nextRoutineId = (result.routines.maxOfOrNull { it.id } ?: 0L) + 1
}

/** 루틴 파일 내보내기(사용자 요청, 2026-08-14) — Firebase 동기화 문서와 동일한 스키마를 그대로 재사용. */
fun Repository.exportRoutinesBackupJson(): String = synchronized(lock) {
    val export = routinesToJsonArrays()
    val root = JSONObject()
    root.put("routines", export.routinesArr)
    root.put("routineLogs", export.logsArr)
    root.toString(2)
}

/** 루틴 파일 가져오기 — 현재 루틴/로그를 파일 내용으로 전체 대체한다(syncRoutinesFromFirebase의 반입 로직과 동일). */
fun Repository.importRoutinesBackupJson(json: String) {
    val root = JSONObject(json)
    val routinesJson = root.optJSONArray("routines") ?: JSONArray()
    val logsJson = root.optJSONArray("routineLogs") ?: JSONArray()
    synchronized(lock) {
        val result = routinesFromJsonArrays(routinesJson, logsJson)
        applyRoutineImport(result)
        persist()
        pushRoutinesToFirebase()
    }
}

/** 변경 직후 fire-and-forget으로 Firebase에 전체 루틴 문서를 올린다(호출부는 이미 lock을 쥐고 있음). */
fun Repository.pushRoutinesToFirebase() {
    val ts = System.currentTimeMillis()
    data.routinesTs = ts
    val export = routinesToJsonArrays()
    val url = data.fbDatabaseUrl; val key = data.fbApiKey
    Thread {
        com.phonelock.desktop.monitor.PomodoroSyncClient.writeRoutines(url, key, export.routinesArr, export.logsArr, ts)
    }.start()
}

/**
 * 루틴 화면 진입 시 호출 — 원격이 로컬보다 최신이면(문서 단위 LWW) 로컬을 덮어쓰고, 로컬이 더 최신이면
 * 반대로 원격에 푸시한다. 네트워크 호출을 포함하므로 호출부(UI)에서 백그라운드 스레드/코루틴에서 실행.
 */
fun Repository.syncRoutinesFromFirebase() {
    val (url, key) = synchronized(lock) { data.fbDatabaseUrl to data.fbApiKey }
    val result = com.phonelock.desktop.monitor.PomodoroSyncClient.readRoutines(url, key) ?: return
    synchronized(lock) {
        if (result.ts > data.routinesTs) {
            val parsed = routinesFromJsonArrays(result.routinesJson, result.logsJson)
            applyRoutineImport(parsed)
            data.routinesTs = result.ts
            persist()
        } else if (data.routinesTs > result.ts) {
            pushRoutinesToFirebase()
        }
    }
}
