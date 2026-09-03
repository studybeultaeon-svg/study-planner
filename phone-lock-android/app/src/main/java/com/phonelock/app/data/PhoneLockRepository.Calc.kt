package com.phonelock.app.data

import androidx.room.withTransaction
import com.phonelock.shared.calc.CalcEngine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 네이티브 계산기(3단계) 관련 [PhoneLockRepository] 확장 함수 모음 — 82차 감사 후속 리팩토링으로
 * PhoneLockRepository.kt에서 분리했다(DECISIONS.md "82차 God Object 파일 분리" 참고). 클래스 자체는
 * 그대로이고 파일만 나눴다. 데스크탑 Repository의 계산기 섹션과 동일 로직/동일 Firebase 스키마.
 * draft(calcTaskDao)/저장됨(calcSavedItemDao)/폴더 트리는 각자 독립 LWW 타임스탬프를 쓴다. Room에는
 * 배열 재정렬 개념이 없어 CalendarTask와 마찬가지로 sortOrder 필드로 순서를 관리한다.
 */

fun PhoneLockRepository.encodeHolidays(list: List<String>): String = list.joinToString(",")
fun PhoneLockRepository.decodeHolidays(csv: String): List<String> = if (csv.isBlank()) emptyList() else csv.split(",")
fun PhoneLockRepository.encodeFolderPath(path: List<String>?): String = path?.joinToString("|") ?: ""
fun PhoneLockRepository.decodeFolderPath(csv: String): List<String>? = if (csv.isBlank()) null else csv.split("|")

var PhoneLockRepository.calcTasksTs: Long
    get() = preferences.calcTasksTs
    set(value) { preferences.calcTasksTs = value }
var PhoneLockRepository.calcSavedTs: Long
    get() = preferences.calcSavedTs
    set(value) { preferences.calcSavedTs = value }
var PhoneLockRepository.calcFolderTs: Long
    get() = preferences.calcFolderTs
    set(value) { preferences.calcFolderTs = value }
var PhoneLockRepository.calcFolderOrderTs: Long
    get() = preferences.calcFolderOrderTs
    set(value) { preferences.calcFolderOrderTs = value }

fun PhoneLockRepository.getCalcFolderPathsLocal(): MutableList<List<String>> {
    val arr = JSONArray(preferences.calcFolderPathsJson)
    return (0 until arr.length()).map { i -> val p = arr.getJSONArray(i); (0 until p.length()).map { p.getString(it) } }.toMutableList()
}
fun PhoneLockRepository.saveCalcFolderPathsLocal(paths: List<List<String>>) {
    val arr = JSONArray(); paths.forEach { arr.put(JSONArray(it)) }
    preferences.calcFolderPathsJson = arr.toString()
}
fun PhoneLockRepository.getCalcFolderOrderLocal(): MutableMap<String, MutableList<String>> {
    val obj = JSONObject(preferences.calcFolderOrderJson)
    val map = mutableMapOf<String, MutableList<String>>()
    obj.keys().forEach { k -> val arr = obj.getJSONArray(k); map[k] = (0 until arr.length()).map { arr.getString(it) }.toMutableList() }
    return map
}
fun PhoneLockRepository.saveCalcFolderOrderLocal(order: Map<String, List<String>>) {
    val obj = JSONObject(); order.forEach { (k, v) -> obj.put(k, JSONArray(v)) }
    preferences.calcFolderOrderJson = obj.toString()
}
fun PhoneLockRepository.calcPathToOrderKey(path: List<String>): String = if (path.isEmpty()) "__root__" else path.joinToString("|")

fun PhoneLockRepository.getCalcFolderCollapsedLocal(): MutableSet<String> {
    val arr = JSONArray(preferences.calcFolderCollapsedJson)
    return (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
}
fun PhoneLockRepository.saveCalcFolderCollapsedLocal(set: Set<String>) {
    preferences.calcFolderCollapsedJson = JSONArray(set.toList()).toString()
}

/** 폴더 접기 상태(기기 로컬, Firebase 미동기화) — 기본값 false(펼침)로 기존 동작을 유지한다. */
fun PhoneLockRepository.isCalcFolderCollapsed(path: List<String>): Boolean = calcPathToOrderKey(path) in getCalcFolderCollapsedLocal()

fun PhoneLockRepository.toggleCalcFolderCollapsed(path: List<String>) {
    val key = calcPathToOrderKey(path)
    val set = getCalcFolderCollapsedLocal()
    if (!set.add(key)) set.remove(key)
    saveCalcFolderCollapsedLocal(set)
}

suspend fun PhoneLockRepository.getCalcTasks(): List<CalcTask> = calcTaskDao.getAll()

suspend fun PhoneLockRepository.addCalcTask() {
    val nextOrder = (calcTaskDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
    calcTaskDao.insert(CalcTask(sortOrder = nextOrder))
    calcTasksTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

suspend fun PhoneLockRepository.updateCalcTask(task: CalcTask) {
    calcTaskDao.update(task.copy(modifiedAt = nowLabel(), modifiedAtTs = System.currentTimeMillis()))
    calcTasksTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

suspend fun PhoneLockRepository.removeCalcTask(task: CalcTask) {
    calcTaskDao.delete(task)
    calcTasksTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

suspend fun PhoneLockRepository.moveCalcTaskOrder(task: CalcTask, direction: Int) {
    val all = calcTaskDao.getAll()
    val idx = all.indexOfFirst { it.id == task.id }
    val target = idx + direction
    if (idx < 0 || target !in all.indices) return
    val a = all[idx]; val b = all[target]
    calcTaskDao.update(a.copy(sortOrder = b.sortOrder))
    calcTaskDao.update(b.copy(sortOrder = a.sortOrder))
    calcTasksTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

/** 입력 초기화 — 웹앱 confirmReset과 동일하게 draft만 지우고 저장 항목은 유지한다. */
suspend fun PhoneLockRepository.resetCalcTasks() {
    calcTaskDao.deleteAll()
    calcTaskDao.insert(CalcTask(sortOrder = 0))
    calcTasksTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

suspend fun PhoneLockRepository.getCalcSaved(): List<CalcSavedItem> = calcSavedItemDao.getAll()

/** 계산 결과 저장 — 같은 이름이 이미 있으면 덮어쓰고(폴더 위치 유지), 없으면 추가(웹앱 saveOneResult와 동일). */
suspend fun PhoneLockRepository.saveCalcResult(task: CalcTask, result: CalcEngine.CalcResult) {
    val t = nowLabel()
    val all = calcSavedItemDao.getAll()
    val existing = all.find { it.name == result.name }
    val nextOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1
    val item = CalcSavedItem(
        id = existing?.id ?: 0,
        name = result.name, qty = result.qty, unit = result.unit, progress = result.progress,
        start = task.start, dday = task.dday,
        mon = task.mon, tue = task.tue, wed = task.wed, thu = task.thu, fri = task.fri, sat = task.sat, sun = task.sun,
        holidaysCsv = task.holidaysCsv,
        savedAt = existing?.savedAt ?: t, modifiedAt = t,
        folderPathCsv = existing?.folderPathCsv ?: "",
        sortOrder = existing?.sortOrder ?: nextOrder
    )
    if (existing != null) calcSavedItemDao.update(item) else calcSavedItemDao.insert(item)
    calcSavedTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

fun PhoneLockRepository.fmtCalcNumber(n: Double): String = if (n == 0.0) "" else if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()

/** 저장 항목을 입력(draft) 목록에 새 카드로 추가(웹앱 loadSavedItem). */
suspend fun PhoneLockRepository.loadCalcSavedItemAsDraft(item: CalcSavedItem) {
    val nextOrder = (calcTaskDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
    calcTaskDao.insert(
        CalcTask(
            name = item.name, qty = fmtCalcNumber(item.qty), unit = item.unit, progress = fmtCalcNumber(item.progress),
            start = item.start, dday = item.dday,
            mon = item.mon, tue = item.tue, wed = item.wed, thu = item.thu, fri = item.fri, sat = item.sat, sun = item.sun,
            holidaysCsv = item.holidaysCsv, sortOrder = nextOrder
        )
    )
    calcTasksTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

suspend fun PhoneLockRepository.deleteCalcSavedItem(item: CalcSavedItem) {
    calcSavedItemDao.delete(item)
    calcSavedTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

suspend fun PhoneLockRepository.moveCalcSavedItem(item: CalcSavedItem, direction: Int) {
    val all = calcSavedItemDao.getAll()
    val idx = all.indexOfFirst { it.id == item.id }
    val target = idx + direction
    if (idx < 0 || target !in all.indices) return
    val a = all[idx]; val b = all[target]
    calcSavedItemDao.update(a.copy(sortOrder = b.sortOrder))
    calcSavedItemDao.update(b.copy(sortOrder = a.sortOrder))
    calcSavedTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

suspend fun PhoneLockRepository.clearAllCalcSaved() {
    calcSavedItemDao.deleteAll()
    calcSavedTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

suspend fun PhoneLockRepository.moveCalcSavedItemToFolder(item: CalcSavedItem, folderPath: List<String>?) {
    calcSavedItemDao.update(item.copy(folderPathCsv = encodeFolderPath(folderPath?.takeIf { it.isNotEmpty() })))
    calcSavedTs = System.currentTimeMillis()
    pushCalcTasksAndSaved()
}

fun PhoneLockRepository.getCalcFolderPaths(): List<List<String>> = getCalcFolderPathsLocal()

/** 부모 경로 밑 하위 폴더 이름을 저장된 순서대로 반환, 순서에 없는 새 폴더는 뒤에 붙인다. */
fun PhoneLockRepository.getCalcSubfolderNames(parentPath: List<String>): List<String> {
    val allNames = getCalcFolderPathsLocal().filter {
        it.size == parentPath.size + 1 && it.subList(0, parentPath.size) == parentPath
    }.map { it.last() }.distinct()
    val order = getCalcFolderOrderLocal()[calcPathToOrderKey(parentPath)] ?: emptyList()
    val existing = order.filter { it in allNames }
    val added = allNames.filter { it !in existing }
    return existing + added
}

fun PhoneLockRepository.createCalcFolder(parentPath: List<String>, name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false
    val newPath = parentPath + trimmed
    val paths = getCalcFolderPathsLocal()
    if (paths.any { it == newPath }) return false
    paths.add(newPath)
    saveCalcFolderPathsLocal(paths)
    calcFolderTs = System.currentTimeMillis()
    pushCalcFolders()
    return true
}

suspend fun PhoneLockRepository.renameCalcFolder(path: List<String>, newName: String): Boolean {
    val trimmed = newName.trim()
    if (trimmed.isEmpty() || path.isEmpty()) return false
    val newPath = path.dropLast(1) + trimmed
    val paths = getCalcFolderPathsLocal()
    if (paths.any { it == newPath }) return false
    for (i in paths.indices) {
        val p = paths[i]
        if (p.size >= path.size && p.subList(0, path.size) == path) paths[i] = newPath + p.subList(path.size, p.size)
    }
    saveCalcFolderPathsLocal(paths)
    calcSavedItemDao.getAll().forEach { item ->
        val fp = decodeFolderPath(item.folderPathCsv) ?: return@forEach
        if (fp.size >= path.size && fp.subList(0, path.size) == path) {
            calcSavedItemDao.update(item.copy(folderPathCsv = encodeFolderPath(newPath + fp.subList(path.size, fp.size))))
        }
    }
    val order = getCalcFolderOrderLocal()
    val parentOrderKey = calcPathToOrderKey(path.dropLast(1))
    order[parentOrderKey]?.let { list -> val idx = list.indexOf(path.last()); if (idx >= 0) list[idx] = trimmed }
    val oldSubKey = calcPathToOrderKey(path); val newSubKey = calcPathToOrderKey(newPath)
    order.remove(oldSubKey)?.let { order[newSubKey] = it }
    saveCalcFolderOrderLocal(order)
    calcFolderTs = System.currentTimeMillis()
    calcFolderOrderTs = System.currentTimeMillis()
    pushCalcFolders()
    return true
}

/** 폴더와 하위 폴더를 삭제하고, 안에 있던 항목은 상위 폴더(또는 미분류)로 이동시킨다. */
suspend fun PhoneLockRepository.deleteCalcFolder(path: List<String>) {
    if (path.isEmpty()) return
    val parentPath = path.dropLast(1)
    calcSavedItemDao.getAll().forEach { item ->
        val fp = decodeFolderPath(item.folderPathCsv) ?: return@forEach
        if (fp.size >= path.size && fp.subList(0, path.size) == path) {
            calcSavedItemDao.update(item.copy(folderPathCsv = encodeFolderPath(parentPath.takeIf { it.isNotEmpty() })))
        }
    }
    val paths = getCalcFolderPathsLocal()
    paths.removeAll { it.size >= path.size && it.subList(0, path.size) == path }
    saveCalcFolderPathsLocal(paths)
    val order = getCalcFolderOrderLocal()
    order[calcPathToOrderKey(parentPath)]?.remove(path.last())
    order.remove(calcPathToOrderKey(path))
    saveCalcFolderOrderLocal(order)
    calcFolderTs = System.currentTimeMillis()
    calcFolderOrderTs = System.currentTimeMillis()
    pushCalcFolders()
}

fun PhoneLockRepository.moveCalcFolderOrder(parentPath: List<String>, name: String, direction: Int) {
    val orderKey = calcPathToOrderKey(parentPath)
    val allNames = getCalcFolderPathsLocal().filter {
        it.size == parentPath.size + 1 && it.subList(0, parentPath.size) == parentPath
    }.map { it.last() }.distinct()
    val order = getCalcFolderOrderLocal()
    val current = (order[orderKey] ?: emptyList()).filter { it in allNames }
    val list = (current + allNames.filter { it !in current }).toMutableList()
    val idx = list.indexOf(name); val target = idx + direction
    if (idx < 0 || target !in list.indices) return
    val tmp = list[idx]; list[idx] = list[target]; list[target] = tmp
    order[orderKey] = list
    saveCalcFolderOrderLocal(order)
    calcFolderOrderTs = System.currentTimeMillis()
    pushCalcFolders()
}

/**
 * calcSaved 항목이 참조하는 폴더 경로(및 조상 경로)가 폴더 목록에 없으면 채워 넣는다. 데스크탑판과
 * 동일한 보정(healCalcFolderPaths) — 웹앱에서 만들어진 레거시 데이터가 savedFolderTree 없이
 * 항목의 folderPath만 가진 채로 동기화되면 항목엔 폴더 이름이 표시돼도 폴더 트리엔 그 폴더가
 * 아예 안 뜨는 문제가 생긴다.
 */
suspend fun PhoneLockRepository.healCalcFolderPaths(): Boolean {
    val paths = getCalcFolderPathsLocal()
    var changed = false
    calcSavedItemDao.getAll().forEach { item ->
        val fp = decodeFolderPath(item.folderPathCsv) ?: return@forEach
        for (i in 1..fp.size) {
            val prefix = fp.subList(0, i)
            if (paths.none { it == prefix }) {
                paths.add(prefix)
                changed = true
            }
        }
    }
    if (changed) saveCalcFolderPathsLocal(paths)
    return changed
}

fun PhoneLockRepository.nowLabel(): String {
    val ts = java.time.LocalDateTime.now()
    return "%d/%d %02d:%02d".format(ts.monthValue, ts.dayOfMonth, ts.hour, ts.minute)
}

fun PhoneLockRepository.calcTaskToJson(t: CalcTask): JSONObject = JSONObject().apply {
    put("name", t.name); put("qty", t.qty); put("unit", t.unit); put("progress", t.progress)
    put("start", t.start); put("dday", t.dday)
    put("mon", t.mon); put("tue", t.tue); put("wed", t.wed); put("thu", t.thu)
    put("fri", t.fri); put("sat", t.sat); put("sun", t.sun)
    put("holidays", JSONArray(decodeHolidays(t.holidaysCsv)))
    put("modifiedAt", t.modifiedAt); put("modifiedAtTs", t.modifiedAtTs)
    put("autoGenEnabled", t.autoGenEnabled); put("autoGenBatchSize", t.autoGenBatchSize)
    put("passCount", t.passCount); put("passIntervalsCsv", t.passIntervalsCsv)
}

fun PhoneLockRepository.calcTaskFromJson(t: JSONObject, order: Int): CalcTask {
    val holidaysArr = t.optJSONArray("holidays") ?: JSONArray()
    return CalcTask(
        name = t.optString("name", ""), qty = t.optString("qty", ""), unit = t.optString("unit", ""),
        progress = t.optString("progress", ""), start = t.optString("start", ""), dday = t.optString("dday", ""),
        mon = t.optString("mon", ""), tue = t.optString("tue", ""), wed = t.optString("wed", ""),
        thu = t.optString("thu", ""), fri = t.optString("fri", ""), sat = t.optString("sat", ""), sun = t.optString("sun", ""),
        holidaysCsv = encodeHolidays((0 until holidaysArr.length()).map { holidaysArr.getString(it) }),
        modifiedAt = t.optString("modifiedAt", ""), modifiedAtTs = t.optLong("modifiedAtTs", 0L), sortOrder = order,
        autoGenEnabled = t.optBoolean("autoGenEnabled", false), autoGenBatchSize = t.optInt("autoGenBatchSize", 0),
        passCount = t.optInt("passCount", com.phonelock.shared.calc.PassSchedule.DEFAULT_PASS_COUNT),
        passIntervalsCsv = t.optString("passIntervalsCsv", com.phonelock.shared.calc.PassSchedule.DEFAULT_INTERVALS_CSV)
    )
}

fun PhoneLockRepository.calcSavedToJson(s: CalcSavedItem): JSONObject = JSONObject().apply {
    put("name", s.name); put("qty", s.qty); put("unit", s.unit); put("progress", s.progress)
    put("start", s.start); put("dday", s.dday)
    put("mon", s.mon); put("tue", s.tue); put("wed", s.wed); put("thu", s.thu)
    put("fri", s.fri); put("sat", s.sat); put("sun", s.sun)
    put("holidays", JSONArray(decodeHolidays(s.holidaysCsv)))
    put("savedAt", s.savedAt); put("modifiedAt", s.modifiedAt)
    put("folderPath", decodeFolderPath(s.folderPathCsv)?.let { JSONArray(it) } ?: JSONObject.NULL)
}

fun PhoneLockRepository.calcSavedFromJson(s: JSONObject, order: Int): CalcSavedItem {
    val holidaysArr = s.optJSONArray("holidays") ?: JSONArray()
    val folderPathArr = s.optJSONArray("folderPath")
    val folderPath = folderPathArr?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
    return CalcSavedItem(
        name = s.optString("name", ""), qty = s.optDouble("qty", 0.0), unit = s.optString("unit", ""),
        progress = s.optDouble("progress", 0.0), start = s.optString("start", ""), dday = s.optString("dday", ""),
        mon = s.optString("mon", ""), tue = s.optString("tue", ""), wed = s.optString("wed", ""),
        thu = s.optString("thu", ""), fri = s.optString("fri", ""), sat = s.optString("sat", ""), sun = s.optString("sun", ""),
        holidaysCsv = encodeHolidays((0 until holidaysArr.length()).map { holidaysArr.getString(it) }),
        savedAt = s.optString("savedAt", ""), modifiedAt = s.optString("modifiedAt", ""),
        folderPathCsv = encodeFolderPath(folderPath), sortOrder = order
    )
}

fun PhoneLockRepository.pushCalcTasksAndSaved() {
    ioScope.launch {
        val tasksJson = JSONArray().also { arr -> calcTaskDao.getAll().forEach { arr.put(calcTaskToJson(it)) } }
        val savedJson = JSONArray().also { arr -> calcSavedItemDao.getAll().forEach { arr.put(calcSavedToJson(it)) } }
        com.phonelock.app.service.PomodoroSyncClient.writeCalcTasksAndSaved(
            fbDatabaseUrl, fbApiKey, tasksJson, calcTasksTs, savedJson, calcSavedTs
        )
    }
}

fun PhoneLockRepository.pushCalcFolders() {
    val paths = getCalcFolderPathsLocal()
    val order = getCalcFolderOrderLocal()
    val folderTs = calcFolderTs; val folderOrderTs = calcFolderOrderTs
    ioScope.launch {
        com.phonelock.app.service.PomodoroSyncClient.writeCalcFolders(fbDatabaseUrl, fbApiKey, paths, folderTs, order, folderOrderTs)
    }
}

/**
 * 계산기 탭 진입 시 호출 — draft/저장됨/폴더 세 구간을 각자 독립적으로 LWW 비교한다(웹앱
 * subscribeCalcData와 동일한 3단 비교, 다만 실시간 구독이 아니라 진입 시 1회 동기화 — 데스크탑/
 * 캘린더와 같은 단순화, DECISIONS.md 참고).
 */
suspend fun PhoneLockRepository.syncCalculatorFromFirebase() {
    val result = com.phonelock.app.service.PomodoroSyncClient.readCalculator(fbDatabaseUrl, fbApiKey) ?: return

    // delete+insert를 트랜잭션으로 묶는다 — 그 사이 프로세스가 죽으면 로컬이 빈 상태로 남을 수 있음(캘린더와 동일 수정).
    if (result.tasksTs > calcTasksTs) {
        db.withTransaction {
            calcTaskDao.deleteAll()
            for (i in 0 until result.tasksJson.length()) calcTaskDao.insert(calcTaskFromJson(result.tasksJson.getJSONObject(i), i))
        }
        calcTasksTs = result.tasksTs
    } else if (calcTasksTs > result.tasksTs) {
        pushCalcTasksAndSaved()
    }

    if (result.savedTs > calcSavedTs) {
        db.withTransaction {
            calcSavedItemDao.deleteAll()
            for (i in 0 until result.savedJson.length()) calcSavedItemDao.insert(calcSavedFromJson(result.savedJson.getJSONObject(i), i))
        }
        calcSavedTs = result.savedTs
    } else if (calcSavedTs > result.savedTs && result.tasksTs <= calcTasksTs) {
        pushCalcTasksAndSaved()
    }

    var foldersChanged = false
    if (result.folderTs > calcFolderTs) {
        val paths = mutableListOf<List<String>>()
        for (i in 0 until result.folderPathsJson.length()) {
            val p = result.folderPathsJson.getJSONArray(i)
            paths.add((0 until p.length()).map { p.getString(it) })
        }
        saveCalcFolderPathsLocal(paths)
        calcFolderTs = result.folderTs
        foldersChanged = true
    }
    if (result.folderOrderTs > calcFolderOrderTs) {
        val order = mutableMapOf<String, MutableList<String>>()
        result.folderOrderJson.keys().forEach { k ->
            val arr = result.folderOrderJson.optJSONArray(k) ?: JSONArray()
            order[k] = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        }
        saveCalcFolderOrderLocal(order)
        calcFolderOrderTs = result.folderOrderTs
        foldersChanged = true
    }
    if (!foldersChanged && (calcFolderTs > result.folderTs || calcFolderOrderTs > result.folderOrderTs)) {
        pushCalcFolders()
    }

    if (healCalcFolderPaths()) {
        calcFolderTs = System.currentTimeMillis()
        pushCalcFolders()
    }
}
