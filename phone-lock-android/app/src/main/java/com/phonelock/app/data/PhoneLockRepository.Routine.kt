package com.phonelock.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 루틴앱 v1(47차 설계, DECISIONS.md 참고) 관련 [PhoneLockRepository] 확장 함수 모음 — 82차 감사
 * 후속 리팩토링으로 PhoneLockRepository.kt에서 분리했다(DECISIONS.md "82차 God Object 파일 분리" 참고).
 * 클래스 자체는 그대로이고 파일만 나눴다. Routine 하나로 체크리스트/습관/일과표 통합. 51차: 캘린더와
 * 동일한 "전체 문서 단위 LWW"로 Firebase 동기화(users/{user}/routines, 데스크탑판과 대칭).
 */

private var PhoneLockRepository.routinesTs: Long
    get() = preferences.routinesTs
    set(value) { preferences.routinesTs = value }

fun PhoneLockRepository.observeRoutines(): Flow<List<Routine>> = routineDao.observeAll()

suspend fun PhoneLockRepository.getRoutines(): List<Routine> = routineDao.getAll()

suspend fun PhoneLockRepository.addRoutine(routine: Routine) {
    val nextOrder = (routineDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
    val toInsert = routine.copy(sortOrder = nextOrder)
    val newId = routineDao.insert(toInsert)
    com.phonelock.app.routine.RoutineAlarmScheduler.scheduleNext(appContext, toInsert.copy(id = newId))
    pushRoutinesToFirebase()
    refreshRoutineWidget()
}

suspend fun PhoneLockRepository.updateRoutine(routine: Routine) {
    routineDao.update(routine)
    com.phonelock.app.routine.RoutineAlarmScheduler.scheduleNext(appContext, routine)
    pushRoutinesToFirebase()
    refreshRoutineWidget()
}

suspend fun PhoneLockRepository.deleteRoutine(routine: Routine) {
    routineDao.delete(routine)
    routineLogDao.deleteForRoutine(routine.id)
    com.phonelock.app.routine.RoutineAlarmScheduler.cancel(appContext, routine.id)
    pushRoutinesToFirebase()
    refreshRoutineWidget()
}

suspend fun PhoneLockRepository.moveRoutineOrder(routine: Routine, direction: Int) {
    val all = routineDao.getAll()
    val idx = all.indexOfFirst { it.id == routine.id }
    val target = idx + direction
    if (idx < 0 || target !in all.indices) return
    val a = all[idx]; val b = all[target]
    routineDao.update(a.copy(sortOrder = b.sortOrder))
    routineDao.update(b.copy(sortOrder = a.sortOrder))
    pushRoutinesToFirebase()
    refreshRoutineWidget()
}

/** 특정 두 루틴의 sortOrder를 직접 맞바꾼다 — "오늘" 탭에서 시간대 미지정 루틴끼리의 ▲/▼ 순서
 *  버튼용(데스크탑판과 대칭, moveRoutineOrder는 전역 인접 스왑이라 시간대 지정 루틴과 뒤섞일 수 있음). */
suspend fun PhoneLockRepository.swapRoutineOrder(idA: Long, idB: Long) {
    val a = routineDao.getById(idA) ?: return
    val b = routineDao.getById(idB) ?: return
    routineDao.update(a.copy(sortOrder = b.sortOrder))
    routineDao.update(b.copy(sortOrder = a.sortOrder))
    pushRoutinesToFirebase()
    refreshRoutineWidget()
}

suspend fun PhoneLockRepository.copyRoutine(routine: Routine) {
    val nextOrder = (routineDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
    val toInsert = routine.copy(id = 0, title = "${routine.title} (복사본)", sortOrder = nextOrder, archived = false)
    val newId = routineDao.insert(toInsert)
    com.phonelock.app.routine.RoutineAlarmScheduler.scheduleNext(appContext, toInsert.copy(id = newId))
    pushRoutinesToFirebase()
    refreshRoutineWidget()
}

suspend fun PhoneLockRepository.getRoutineLogsForDate(dateKey: String): List<RoutineLog> = routineLogDao.getByDate(dateKey)

suspend fun PhoneLockRepository.isRoutineCompleted(routineId: Long, dateKey: String): Boolean =
    routineLogDao.getByDate(dateKey).any { it.routineId == routineId }

/** 날짜 하나에 대한 완료 체크를 토글한다(존재하면 삭제=미완료, 없으면 추가=완료). */
suspend fun PhoneLockRepository.toggleRoutineLog(routineId: Long, dateKey: String) {
    if (routineLogDao.getByDate(dateKey).any { it.routineId == routineId }) {
        routineLogDao.delete(routineId, dateKey)
    } else {
        routineLogDao.insert(RoutineLog(routineId, dateKey))
    }
    pushRoutinesToFirebase()
    refreshRoutineWidget()
}

/** RoutineEngine.currentStreak에 넘길 완료 날짜 집합. */
suspend fun PhoneLockRepository.getRoutineCompletedDateKeys(routineId: Long): Set<String> =
    routineLogDao.getByRoutine(routineId).map { it.dateKey }.toSet()

/**
 * 루틴/로그를 Firebase JSON 배열 2개로 변환한다. 기기별 로컬(Room 자동증가) id를 그대로 실어보내면
 * 다른 기기의 id 체계와 충돌하므로(캘린더가 dateKey+배열순서로 식별하는 것과 같은 이유), routines
 * 배열 안에서의 인덱스를 로그가 참조하는 "routineIndex"로 쓴다 — 실제 id는 반입하는 쪽에서 새로 배정.
 */
fun PhoneLockRepository.routinesToJson(routines: List<Routine>, logs: List<RoutineLog>): Pair<JSONArray, JSONArray> {
    val sorted = routines.sortedBy { it.sortOrder }
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
    logs.forEach { log ->
        val idx = indexById[log.routineId] ?: return@forEach
        logsArr.put(JSONObject().apply {
            put("routineIndex", idx)
            put("dateKey", log.dateKey)
        })
    }
    return routinesArr to logsArr
}

/** JSON 배열 2개(routines, routineLogs)를 로컬 Routine/RoutineLog로 되돌린다 — 새 로컬 id는 insert 시 Room이 자동 배정. */
fun PhoneLockRepository.routinesFromJson(routinesJson: JSONArray, logsJson: JSONArray): Pair<List<Routine>, List<Pair<Int, String>>> {
    val newRoutines = mutableListOf<Routine>()
    for (i in 0 until routinesJson.length()) {
        val r = routinesJson.getJSONObject(i)
        newRoutines.add(
            Routine(
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
    val logRefs = mutableListOf<Pair<Int, String>>()
    for (i in 0 until logsJson.length()) {
        val l = logsJson.getJSONObject(i)
        val idx = l.optInt("routineIndex", -1)
        if (idx !in newRoutines.indices) continue
        logRefs.add(idx to l.optString("dateKey", ""))
    }
    return newRoutines to logRefs
}

/** 루틴 파일 내보내기(사용자 요청, 2026-08-14) — Firebase 동기화 문서와 동일한 스키마를 그대로 재사용. */
suspend fun PhoneLockRepository.exportRoutinesBackupJson(): String {
    val (routinesArr, logsArr) = routinesToJson(routineDao.getAll(), routineLogDao.getAllOnce())
    val root = JSONObject()
    root.put("routines", routinesArr)
    root.put("routineLogs", logsArr)
    return root.toString(2)
}

/** 루틴 파일 가져오기 — 현재 루틴/로그를 파일 내용으로 전체 대체한다(syncRoutinesFromFirebase의 반입 로직과 동일). */
suspend fun PhoneLockRepository.importRoutinesBackupJson(json: String) {
    val root = JSONObject(json)
    val (newRoutines, logRefs) = routinesFromJson(
        root.optJSONArray("routines") ?: JSONArray(),
        root.optJSONArray("routineLogs") ?: JSONArray()
    )
    db.withTransaction {
        routineLogDao.deleteAll()
        routineDao.deleteAll()
        val newIds = newRoutines.map { routineDao.insert(it) }
        logRefs.forEach { (idx, dateKey) -> routineLogDao.insert(RoutineLog(newIds[idx], dateKey)) }
    }
    pushRoutinesToFirebase()
    refreshRoutineWidget()
    com.phonelock.app.routine.RoutineAlarmScheduler.rescheduleAll(appContext, this)
}

/** 홈 화면 위젯(51차, 사용자 요청)이 있으면 최신 상태로 다시 그리게 한다 — 없으면 조용히 아무 일도 안 함. */
fun PhoneLockRepository.refreshRoutineWidget() {
    com.phonelock.app.widget.RoutineWidgetProvider.updateAll(appContext)
}

/** 변경 직후 fire-and-forget으로 Firebase에 전체 루틴 문서를 올린다. */
fun PhoneLockRepository.pushRoutinesToFirebase() {
    val ts = System.currentTimeMillis()
    routinesTs = ts
    ioScope.launch {
        val (routinesArr, logsArr) = routinesToJson(routineDao.getAll(), routineLogDao.getAllOnce())
        com.phonelock.app.service.PomodoroSyncClient.writeRoutines(fbDatabaseUrl, fbApiKey, routinesArr, logsArr, ts)
    }
}

/**
 * 루틴 화면 진입 시 호출 — 원격이 로컬보다 최신이면(문서 단위 LWW) 로컬을 덮어쓰고, 로컬이 더
 * 최신이면 반대로 원격에 푸시한다.
 */
suspend fun PhoneLockRepository.syncRoutinesFromFirebase() {
    val result = com.phonelock.app.service.PomodoroSyncClient.readRoutines(fbDatabaseUrl, fbApiKey) ?: return
    if (result.ts > routinesTs) {
        val (newRoutines, logRefs) = routinesFromJson(result.routinesJson, result.logsJson)
        // Room auto-increment ID라 delete+insert하면 새 루틴들이 전부 새 ID를 받는다 — 이 ID를
        // requestCode로 쓰는 예약 알람(RoutineAlarmScheduler)이 그대로 두면 옛 ID의 알람은 절대
        // 취소될 길이 없어 동기화 때마다 계속 쌓인다(안드로이드 앱당 예약 알람 500개 한도에 걸려
        // 실제로 크래시 루프가 났던 원인, 2026-08-30). 지우기 전에 지금 있는 루틴들의 알람부터 먼저
        // 취소해서 이 누수를 막는다.
        val oldRoutines = routineDao.getAll()
        // delete+insert를 하나의 트랜잭션으로 묶는다 — 캘린더 동기화와 동일한 이유(도중에 죽어도 빈 상태로 안 남게).
        db.withTransaction {
            routineLogDao.deleteAll()
            routineDao.deleteAll()
            val newIds = newRoutines.map { routineDao.insert(it) }
            logRefs.forEach { (idx, dateKey) ->
                routineLogDao.insert(RoutineLog(newIds[idx], dateKey))
            }
        }
        oldRoutines.forEach { com.phonelock.app.routine.RoutineAlarmScheduler.cancel(appContext, it.id) }
        routinesTs = result.ts
        refreshRoutineWidget()
        com.phonelock.app.routine.RoutineAlarmScheduler.rescheduleAll(appContext, this)
    } else if (routinesTs > result.ts) {
        pushRoutinesToFirebase()
    }
}
