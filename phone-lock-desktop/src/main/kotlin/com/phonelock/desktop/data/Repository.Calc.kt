package com.phonelock.desktop.data

import com.phonelock.shared.calc.CalcEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 네이티브 계산기(3단계) 관련 [Repository] 확장 함수 모음 — 82차 감사 후속 리팩토링으로 Repository.kt에서
 * 분리했다(DECISIONS.md "82차 God Object 파일 분리" 참고, 안드로이드 PhoneLockRepository.Calc.kt와 대칭).
 * 클래스 자체는 그대로이고 파일만 나눴다. 웹앱 index.html의 할당량 계산기를 그대로 이식 — draft
 * 업무(calcTasks)/저장됨(calcSaved)/폴더 트리(calcFolderPaths+calcFolderOrder)는 각자 독립된 LWW
 * 타임스탬프를 갖는다.
 */

fun Repository.getCalcTasks(): List<CalcTask> = synchronized(lock) { data.calcTasks.toList() }

fun Repository.addCalcTask(task: CalcTask = CalcTask()) = synchronized(lock) {
    data.calcTasks.add(task)
    data.calcTasksTs = System.currentTimeMillis()
    persist()
    pushCalcTasksAndSaved()
}

fun Repository.updateCalcTask(index: Int, updated: CalcTask) = synchronized(lock) {
    if (index !in data.calcTasks.indices) return@synchronized
    data.calcTasks[index] = updated.copy(modifiedAt = nowLabel(), modifiedAtTs = System.currentTimeMillis())
    data.calcTasksTs = System.currentTimeMillis()
    persist()
    pushCalcTasksAndSaved()
}

fun Repository.removeCalcTask(index: Int) = synchronized(lock) {
    if (index !in data.calcTasks.indices) return@synchronized
    data.calcTasks.removeAt(index)
    data.calcTasksTs = System.currentTimeMillis()
    persist()
    pushCalcTasksAndSaved()
}

fun Repository.moveCalcTaskOrder(index: Int, direction: Int) = synchronized(lock) {
    val target = index + direction
    if (index !in data.calcTasks.indices || target !in data.calcTasks.indices) return@synchronized
    val tmp = data.calcTasks[index]
    data.calcTasks[index] = data.calcTasks[target]
    data.calcTasks[target] = tmp
    data.calcTasksTs = System.currentTimeMillis()
    persist()
    pushCalcTasksAndSaved()
}

/** 입력 초기화 — 웹앱 confirmReset과 동일하게 draft만 지우고 저장 항목은 유지한다. */
fun Repository.resetCalcTasks() = synchronized(lock) {
    data.calcTasks.clear()
    data.calcTasks.add(CalcTask())
    data.calcTasksTs = System.currentTimeMillis()
    persist()
    pushCalcTasksAndSaved()
}

fun Repository.getCalcSaved(): List<CalcSavedItem> = synchronized(lock) { data.calcSaved.toList() }

/** 계산 결과 저장 — 같은 이름이 이미 있으면 덮어쓰고(폴더 위치는 유지), 없으면 맨 앞에 추가(웹앱 saveOneResult와 동일). */
fun Repository.saveCalcResult(task: CalcTask, result: CalcEngine.CalcResult) = synchronized(lock) {
    val t = nowLabel()
    val existingIdx = data.calcSaved.indexOfFirst { it.name == result.name }
    val existing = existingIdx.takeIf { it >= 0 }?.let { data.calcSaved[it] }
    val item = CalcSavedItem(
        name = result.name, qty = result.qty, unit = result.unit, progress = result.progress,
        start = task.start, dday = task.dday,
        mon = task.mon, tue = task.tue, wed = task.wed, thu = task.thu, fri = task.fri, sat = task.sat, sun = task.sun,
        holidays = task.holidays,
        savedAt = existing?.savedAt ?: t, modifiedAt = t,
        folderPath = existing?.folderPath
    )
    if (existingIdx >= 0) data.calcSaved[existingIdx] = item else data.calcSaved.add(0, item)
    data.calcSavedTs = System.currentTimeMillis()
    persist()
    pushCalcTasksAndSaved()
}

/** 저장 항목을 입력(draft) 목록에 새 카드로 추가(웹앱 loadSavedItem). */
fun Repository.loadCalcSavedItemAsDraft(index: Int) = synchronized(lock) {
    val item = data.calcSaved.getOrNull(index) ?: return@synchronized
    data.calcTasks.add(
        CalcTask(
            name = item.name, qty = fmtCalcNumber(item.qty), unit = item.unit, progress = fmtCalcNumber(item.progress),
            start = item.start, dday = item.dday,
            mon = item.mon, tue = item.tue, wed = item.wed, thu = item.thu, fri = item.fri, sat = item.sat, sun = item.sun,
            holidays = item.holidays
        )
    )
    data.calcTasksTs = System.currentTimeMillis()
    persist()
    pushCalcTasksAndSaved()
}

fun Repository.fmtCalcNumber(n: Double): String = if (n == 0.0) "" else if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()

fun Repository.deleteCalcSavedItem(index: Int) = synchronized(lock) {
    if (index !in data.calcSaved.indices) return@synchronized
    data.calcSaved.removeAt(index)
    data.calcSavedTs = System.currentTimeMillis()
    persist()
    pushCalcTasksAndSaved()
}

fun Repository.moveCalcSavedItem(index: Int, direction: Int) = synchronized(lock) {
    val target = index + direction
    if (index !in data.calcSaved.indices || target !in data.calcSaved.indices) return@synchronized
    val tmp = data.calcSaved[index]
    data.calcSaved[index] = data.calcSaved[target]
    data.calcSaved[target] = tmp
    data.calcSavedTs = System.currentTimeMillis()
    persist()
    pushCalcTasksAndSaved()
}

fun Repository.clearAllCalcSaved() = synchronized(lock) {
    data.calcSaved.clear()
    data.calcSavedTs = System.currentTimeMillis()
    persist()
    pushCalcTasksAndSaved()
}

fun Repository.moveCalcSavedItemToFolder(index: Int, folderPath: List<String>?) = synchronized(lock) {
    if (index !in data.calcSaved.indices) return@synchronized
    data.calcSaved[index] = data.calcSaved[index].copy(folderPath = folderPath?.takeIf { it.isNotEmpty() })
    data.calcSavedTs = System.currentTimeMillis()
    persist()
    pushCalcTasksAndSaved()
}

/** 폴더 접기 상태(기기 로컬, Firebase 미동기화) — 기본값 false(펼침)로 기존 동작을 유지한다. */
fun Repository.isCalcFolderCollapsed(path: List<String>): Boolean = synchronized(lock) {
    calcPathToOrderKey(path) in data.calcFolderCollapsed
}

fun Repository.toggleCalcFolderCollapsed(path: List<String>) = synchronized(lock) {
    val key = calcPathToOrderKey(path)
    if (!data.calcFolderCollapsed.add(key)) data.calcFolderCollapsed.remove(key)
    persist()
}

fun Repository.getCalcFolderPaths(): List<List<String>> = synchronized(lock) { data.calcFolderPaths.toList() }

/** 부모 경로 밑 하위 폴더 이름을 저장된 순서(calcFolderOrder)대로 반환, 순서에 없는 새 폴더는 뒤에 붙인다. */
fun Repository.getCalcSubfolderNames(parentPath: List<String>): List<String> = synchronized(lock) {
    val allNames = data.calcFolderPaths.filter {
        it.size == parentPath.size + 1 && it.subList(0, parentPath.size) == parentPath
    }.map { it.last() }.distinct()
    val orderKey = calcPathToOrderKey(parentPath)
    val order = data.calcFolderOrder[orderKey] ?: emptyList()
    val existing = order.filter { it in allNames }
    val added = allNames.filter { it !in existing }
    existing + added
}

fun Repository.calcPathToOrderKey(path: List<String>): String = if (path.isEmpty()) "__root__" else path.joinToString("|")

fun Repository.createCalcFolder(parentPath: List<String>, name: String): Boolean = synchronized(lock) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return@synchronized false
    val newPath = parentPath + trimmed
    if (data.calcFolderPaths.any { it == newPath }) return@synchronized false
    data.calcFolderPaths.add(newPath)
    data.calcFolderTs = System.currentTimeMillis()
    persist()
    pushCalcFolders()
    true
}

fun Repository.renameCalcFolder(path: List<String>, newName: String): Boolean = synchronized(lock) {
    val trimmed = newName.trim()
    if (trimmed.isEmpty() || path.isEmpty()) return@synchronized false
    val newPath = path.dropLast(1) + trimmed
    if (data.calcFolderPaths.any { it == newPath }) return@synchronized false
    // 이 경로 및 하위 경로 전부, 그리고 그 경로를 쓰는 저장 항목까지 함께 이전한다.
    for (i in data.calcFolderPaths.indices) {
        val p = data.calcFolderPaths[i]
        if (p.size >= path.size && p.subList(0, path.size) == path) {
            data.calcFolderPaths[i] = newPath + p.subList(path.size, p.size)
        }
    }
    for (i in data.calcSaved.indices) {
        val fp = data.calcSaved[i].folderPath ?: continue
        if (fp.size >= path.size && fp.subList(0, path.size) == path) {
            data.calcSaved[i] = data.calcSaved[i].copy(folderPath = newPath + fp.subList(path.size, fp.size))
        }
    }
    // 순서 맵의 키/값도 이전
    val parentOrderKey = calcPathToOrderKey(path.dropLast(1))
    data.calcFolderOrder[parentOrderKey]?.let { order ->
        val idx = order.indexOf(path.last())
        if (idx >= 0) order[idx] = trimmed
    }
    val oldSubKey = calcPathToOrderKey(path); val newSubKey = calcPathToOrderKey(newPath)
    data.calcFolderOrder.remove(oldSubKey)?.let { data.calcFolderOrder[newSubKey] = it }
    data.calcFolderTs = System.currentTimeMillis()
    data.calcFolderOrderTs = System.currentTimeMillis()
    persist()
    pushCalcFolders()
    true
}

/** 폴더와 하위 폴더를 삭제하고, 안에 있던 항목은 상위 폴더(또는 미분류)로 이동시킨다. */
fun Repository.deleteCalcFolder(path: List<String>) = synchronized(lock) {
    if (path.isEmpty()) return@synchronized
    val parentPath = path.dropLast(1)
    for (i in data.calcSaved.indices) {
        val fp = data.calcSaved[i].folderPath ?: continue
        if (fp.size >= path.size && fp.subList(0, path.size) == path) {
            data.calcSaved[i] = data.calcSaved[i].copy(folderPath = parentPath.takeIf { it.isNotEmpty() })
        }
    }
    data.calcFolderPaths.removeAll { it.size >= path.size && it.subList(0, path.size) == path }
    val parentOrderKey = calcPathToOrderKey(parentPath)
    data.calcFolderOrder[parentOrderKey]?.remove(path.last())
    data.calcFolderOrder.remove(calcPathToOrderKey(path))
    data.calcFolderTs = System.currentTimeMillis()
    data.calcFolderOrderTs = System.currentTimeMillis()
    persist()
    pushCalcFolders()
}

fun Repository.moveCalcFolderOrder(parentPath: List<String>, name: String, direction: Int) = synchronized(lock) {
    val orderKey = calcPathToOrderKey(parentPath)
    val allNames = data.calcFolderPaths.filter {
        it.size == parentPath.size + 1 && it.subList(0, parentPath.size) == parentPath
    }.map { it.last() }.distinct()
    val current = (data.calcFolderOrder[orderKey] ?: emptyList()).filter { it in allNames }
    val order = (current + allNames.filter { it !in current }).toMutableList()
    val idx = order.indexOf(name)
    val target = idx + direction
    if (idx < 0 || target !in order.indices) return@synchronized
    val tmp = order[idx]; order[idx] = order[target]; order[target] = tmp
    data.calcFolderOrder[orderKey] = order
    data.calcFolderOrderTs = System.currentTimeMillis()
    persist()
    pushCalcFolders()
}

/**
 * calcSaved 항목이 참조하는 폴더 경로(및 조상 경로)가 calcFolderPaths에 없으면 채워 넣는다.
 * 웹앱에서 만들어진 기존 데이터가 savedFolderTree 없이 항목의 folderPath만 가진 채로 동기화되면
 * (레거시 데이터, 트리 자체가 한 번도 안 올라간 경우) 항목엔 폴더 이름이 표시돼도 좌측 폴더
 * 목록엔 그 폴더가 아예 안 뜨는 문제가 생긴다 — 웹앱의 rebuildFolderTreeFromItems와 동일한 보정.
 */
fun Repository.healCalcFolderPaths(): Boolean {
    var changed = false
    data.calcSaved.forEach { item ->
        val fp = item.folderPath ?: return@forEach
        for (i in 1..fp.size) {
            val prefix = fp.subList(0, i)
            if (data.calcFolderPaths.none { it == prefix }) {
                data.calcFolderPaths.add(prefix)
                changed = true
            }
        }
    }
    return changed
}

fun Repository.nowLabel(): String {
    val ts = java.time.LocalDateTime.now()
    return "%d/%d %02d:%02d".format(ts.monthValue, ts.dayOfMonth, ts.hour, ts.minute)
}

fun Repository.calcTaskToJson(t: CalcTask): JSONObject = JSONObject().apply {
    put("name", t.name); put("qty", t.qty); put("unit", t.unit); put("progress", t.progress)
    put("start", t.start); put("dday", t.dday)
    put("mon", t.mon); put("tue", t.tue); put("wed", t.wed); put("thu", t.thu)
    put("fri", t.fri); put("sat", t.sat); put("sun", t.sun)
    put("holidays", JSONArray(t.holidays))
    put("modifiedAt", t.modifiedAt); put("modifiedAtTs", t.modifiedAtTs)
    put("autoGenEnabled", t.autoGenEnabled); put("autoGenBatchSize", t.autoGenBatchSize)
    put("passCount", t.passCount); put("passIntervalsCsv", t.passIntervalsCsv)
    put("multiPassUsageEnabled", t.multiPassUsageEnabled)
}

fun Repository.calcTaskFromJson(t: JSONObject): CalcTask {
    val holidaysArr = t.optJSONArray("holidays") ?: JSONArray()
    return CalcTask(
        name = t.optString("name", ""), qty = t.optString("qty", ""), unit = t.optString("unit", ""),
        progress = t.optString("progress", ""), start = t.optString("start", ""), dday = t.optString("dday", ""),
        mon = t.optString("mon", ""), tue = t.optString("tue", ""), wed = t.optString("wed", ""),
        thu = t.optString("thu", ""), fri = t.optString("fri", ""), sat = t.optString("sat", ""), sun = t.optString("sun", ""),
        holidays = (0 until holidaysArr.length()).map { holidaysArr.getString(it) },
        modifiedAt = t.optString("modifiedAt", ""), modifiedAtTs = t.optLong("modifiedAtTs", 0L),
        autoGenEnabled = t.optBoolean("autoGenEnabled", false), autoGenBatchSize = t.optInt("autoGenBatchSize", 0),
        passCount = t.optInt("passCount", com.phonelock.shared.calc.PassSchedule.DEFAULT_PASS_COUNT),
        passIntervalsCsv = t.optString("passIntervalsCsv", com.phonelock.shared.calc.PassSchedule.DEFAULT_INTERVALS_CSV),
        multiPassUsageEnabled = t.optBoolean("multiPassUsageEnabled", true)
    )
}

fun Repository.calcSavedToJson(s: CalcSavedItem): JSONObject = JSONObject().apply {
    put("name", s.name); put("qty", s.qty); put("unit", s.unit); put("progress", s.progress)
    put("start", s.start); put("dday", s.dday)
    put("mon", s.mon); put("tue", s.tue); put("wed", s.wed); put("thu", s.thu)
    put("fri", s.fri); put("sat", s.sat); put("sun", s.sun)
    put("holidays", JSONArray(s.holidays))
    put("savedAt", s.savedAt); put("modifiedAt", s.modifiedAt)
    put("folderPath", s.folderPath?.let { JSONArray(it) } ?: JSONObject.NULL)
}

fun Repository.calcSavedFromJson(s: JSONObject): CalcSavedItem {
    val holidaysArr = s.optJSONArray("holidays") ?: JSONArray()
    val folderPathArr = s.optJSONArray("folderPath")
    return CalcSavedItem(
        name = s.optString("name", ""), qty = s.optDouble("qty", 0.0), unit = s.optString("unit", ""),
        progress = s.optDouble("progress", 0.0), start = s.optString("start", ""), dday = s.optString("dday", ""),
        mon = s.optString("mon", ""), tue = s.optString("tue", ""), wed = s.optString("wed", ""),
        thu = s.optString("thu", ""), fri = s.optString("fri", ""), sat = s.optString("sat", ""), sun = s.optString("sun", ""),
        holidays = (0 until holidaysArr.length()).map { holidaysArr.getString(it) },
        savedAt = s.optString("savedAt", ""), modifiedAt = s.optString("modifiedAt", ""),
        folderPath = folderPathArr?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
    )
}

fun Repository.pushCalcTasksAndSaved() {
    val url: String?; val key: String?
    val tasksJson: JSONArray; val tasksTs: Long
    val savedJson: JSONArray; val savedTs: Long
    synchronized(lock) {
        url = data.fbDatabaseUrl; key = data.fbApiKey
        tasksJson = JSONArray().also { arr -> data.calcTasks.forEach { arr.put(calcTaskToJson(it)) } }
        tasksTs = data.calcTasksTs
        savedJson = JSONArray().also { arr -> data.calcSaved.forEach { arr.put(calcSavedToJson(it)) } }
        savedTs = data.calcSavedTs
    }
    Thread {
        com.phonelock.desktop.monitor.PomodoroSyncClient.writeCalcTasksAndSaved(
            url, key, tasksJson, tasksTs, savedJson, savedTs
        )
    }.start()
}

fun Repository.pushCalcFolders() {
    val url: String?; val key: String?
    val paths: List<List<String>>; val folderTs: Long
    val order: Map<String, List<String>>; val orderTs: Long
    synchronized(lock) {
        url = data.fbDatabaseUrl; key = data.fbApiKey
        paths = data.calcFolderPaths.toList(); folderTs = data.calcFolderTs
        order = data.calcFolderOrder.mapValues { it.value.toList() }; orderTs = data.calcFolderOrderTs
    }
    Thread {
        com.phonelock.desktop.monitor.PomodoroSyncClient.writeCalcFolders(url, key, paths, folderTs, order, orderTs)
    }.start()
}

/**
 * 계산기 탭 진입 시 호출 — draft/저장됨/폴더 세 구간을 각자 독립적으로 LWW 비교한다(웹앱
 * subscribeCalcData와 동일한 3단 비교, 다만 실시간 구독이 아니라 진입 시 1회 동기화 — 캘린더와 같은
 * 단순화, DECISIONS.md 참고). 네트워크 호출을 포함하므로 호출부에서 백그라운드 스레드로 실행할 것.
 */
fun Repository.syncCalculatorFromFirebase() {
    val (url, key) = synchronized(lock) { data.fbDatabaseUrl to data.fbApiKey }
    val result = com.phonelock.desktop.monitor.PomodoroSyncClient.readCalculator(url, key) ?: return
    synchronized(lock) {
        var tasksChanged = false; var savedChanged = false; var foldersChanged = false

        if (result.tasksTs > data.calcTasksTs) {
            data.calcTasks.clear()
            for (i in 0 until result.tasksJson.length()) data.calcTasks.add(calcTaskFromJson(result.tasksJson.getJSONObject(i)))
            data.calcTasksTs = result.tasksTs
            tasksChanged = true
        }
        if (result.savedTs > data.calcSavedTs) {
            data.calcSaved.clear()
            for (i in 0 until result.savedJson.length()) data.calcSaved.add(calcSavedFromJson(result.savedJson.getJSONObject(i)))
            data.calcSavedTs = result.savedTs
            savedChanged = true
        }
        if (result.folderTs > data.calcFolderTs) {
            data.calcFolderPaths.clear()
            for (i in 0 until result.folderPathsJson.length()) {
                val p = result.folderPathsJson.getJSONArray(i)
                data.calcFolderPaths.add((0 until p.length()).map { p.getString(it) })
            }
            data.calcFolderTs = result.folderTs
            foldersChanged = true
        }
        if (result.folderOrderTs > data.calcFolderOrderTs) {
            data.calcFolderOrder.clear()
            result.folderOrderJson.keys().forEach { k ->
                val arr = result.folderOrderJson.optJSONArray(k) ?: JSONArray()
                data.calcFolderOrder[k] = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
            }
            data.calcFolderOrderTs = result.folderOrderTs
            foldersChanged = true
        }

        if (tasksChanged || savedChanged || foldersChanged) persist()

        if (!tasksChanged && !savedChanged && (data.calcTasksTs > result.tasksTs || data.calcSavedTs > result.savedTs)) {
            pushCalcTasksAndSaved()
        }
        if (!foldersChanged && (data.calcFolderTs > result.folderTs || data.calcFolderOrderTs > result.folderOrderTs)) {
            pushCalcFolders()
        }

        if (healCalcFolderPaths()) {
            data.calcFolderTs = System.currentTimeMillis()
            persist()
            pushCalcFolders()
        }
    }
}
