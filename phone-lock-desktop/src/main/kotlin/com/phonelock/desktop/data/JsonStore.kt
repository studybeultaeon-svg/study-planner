package com.phonelock.desktop.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

object JsonStore {
    private val dataDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PhoneLockDesktop")
    private val dataFile = File(dataDir, "data.json")
    private val backupsDir = File(dataDir, "backups")
    private val lastBackupMarkerFile = File(dataDir, "last_backup_date.txt")
    private const val BACKUP_RETENTION_DAYS = 7L

    /**
     * 하루 한 번(앱 시작 시), 그날 아직 백업을 안 만들었으면 현재 data.json을 통째로 복사해둔다
     * (전문가 종합분석 보고서 #19 — "어제 상태로 되돌리기"). 7일 초과분은 자동 정리한다.
     * PreMigrationBackup(스키마 변경 시 1회성)과는 목적이 다른, 매일 회전하는 다세대 백업이다.
     */
    fun rotateDailyBackupIfNeeded() {
        if (!dataFile.exists()) return
        val today = java.time.LocalDate.now().toString()
        val lastBackupDate = runCatching { lastBackupMarkerFile.readText().trim() }.getOrNull()
        if (lastBackupDate == today) return
        runCatching {
            backupsDir.mkdirs()
            dataFile.copyTo(File(backupsDir, "backup_$today.json"), overwrite = true)
            lastBackupMarkerFile.writeText(today)
            val cutoff = java.time.LocalDate.now().minusDays(BACKUP_RETENTION_DAYS)
            backupsDir.listFiles { f -> f.name.startsWith("backup_") && f.name.endsWith(".json") }?.forEach { f ->
                val dateStr = f.name.removePrefix("backup_").removeSuffix(".json")
                val d = runCatching { java.time.LocalDate.parse(dateStr) }.getOrNull()
                if (d != null && d.isBefore(cutoff)) f.delete()
            }
        }
    }

    /** 최신 날짜순으로 정렬된 백업 파일 목록. */
    fun listBackups(): List<File> =
        (backupsDir.listFiles { f -> f.name.startsWith("backup_") && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.name }

    /** 백업 파일을 파싱만 해서 돌려준다(디스크 대체는 호출부 책임) — 파싱 실패 시 null. */
    fun parseBackupFile(file: File): AppData? =
        runCatching { parse(JSONObject(file.readText())) }.getOrNull()

    /**
     * 파일이 손상됐거나(쓰는 도중 강제종료 등) 파싱 실패하면 예외를 던지는 대신, 손상된 파일을
     * 타임스탬프를 붙여 백업해두고 빈 상태로 시작한다. 그렇지 않으면 앱이 영구적으로 시작 불가 상태가
     * 되어 사용자가 직접 data.json을 지워야 하는 상황이 생긴다.
     */
    fun load(): AppData {
        if (!dataFile.exists()) return AppData()
        return runCatching { parse(JSONObject(dataFile.readText())) }.getOrElse {
            runCatching {
                val backup = File(dataDir, "data.json.corrupted-${System.currentTimeMillis()}")
                dataFile.copyTo(backup, overwrite = true)
            }
            AppData()
        }
    }

    private fun parse(json: JSONObject): AppData {
        val data = AppData(
            nextGroupId = json.optLong("nextGroupId", 1),
            dailyResetHour = json.optInt("dailyResetHour", 0),
            routinesTs = json.optLong("routinesTs", 0L),
            themeMode = json.optString("themeMode", "LIGHT_GREEN"),
            blockReels = json.optBoolean("blockReels", false),
            blockShorts = json.optBoolean("blockShorts", false),
            routineStreakNotifyEnabled = json.optBoolean("routineStreakNotifyEnabled", false),
            lastRoutineStreak = json.optInt("lastRoutineStreak", -1),
            zeroStreakDays = json.optInt("zeroStreakDays", 0),
            fbDatabaseUrl = (if (json.isNull("fbDatabaseUrl")) null else json.optString("fbDatabaseUrl", null))
                ?.ifBlank { null } ?: DEFAULT_FB_DATABASE_URL,
            fbApiKey = (if (json.isNull("fbApiKey")) null else json.optString("fbApiKey", null))
                ?.ifBlank { null } ?: DEFAULT_FB_API_KEY,
            timerRun = json.optJSONObject("timerRun")?.let { t ->
                TimerRunState(
                    taskName = t.optString("taskName", ""),
                    mode = t.optString("mode", "plain"),
                    phase = t.optString("phase", "study"),
                    phaseStartedAt = t.optLong("phaseStartedAt", 0L),
                    phaseEndAt = t.optLong("phaseEndAt", 0L),
                    cycleCount = t.optInt("cycleCount", 0),
                    breakExtraUsed = t.optBoolean("breakExtraUsed", false)
                ).takeIf { it.phaseStartedAt > 0L }
            },
            pomodoroStudyMinutes = json.optInt("pomodoroStudyMinutes", 25),
            pomodoroBreakMinutes = json.optInt("pomodoroBreakMinutes", 5),
            pomodoroModeEnabled = json.optBoolean("pomodoroModeEnabled", false),
            studyLockAllowedApps = run {
                val arr = json.optJSONArray("studyLockAllowedApps") ?: JSONArray()
                (0 until arr.length()).map { arr.getString(it) }.toMutableList()
            },
            studyLockAllowedSites = run {
                val arr = json.optJSONArray("studyLockAllowedSites") ?: JSONArray()
                (0 until arr.length()).map { arr.getString(it) }.toMutableList()
            },
            calendarTs = json.optLong("calendarTs", 0L),
            calcTasksTs = json.optLong("calcTasksTs", 0L),
            calcSavedTs = json.optLong("calcSavedTs", 0L),
            calcFolderTs = json.optLong("calcFolderTs", 0L),
            calcFolderOrderTs = json.optLong("calcFolderOrderTs", 0L),
            lastGroupAutoResetDate = if (json.isNull("lastGroupAutoResetDate")) null else json.optString("lastGroupAutoResetDate", null),
            nextRoutineId = json.optLong("nextRoutineId", 1),
            cachedApprovalStatus = if (json.isNull("cachedApprovalStatus")) null else json.optString("cachedApprovalStatus", null),
            permRoutine = json.optBoolean("permRoutine", true),
            permStudy = json.optBoolean("permStudy", true),
            permManage = json.optBoolean("permManage", true),
            permSocial = json.optBoolean("permSocial", true),
            lastUpdateCheckDate = if (json.isNull("lastUpdateCheckDate")) null else json.optString("lastUpdateCheckDate", null),
            updateAvailableBuildTimestamp = json.optLong("updateAvailableBuildTimestamp", 0L),
            updateAvailableInstallerUrl = if (json.isNull("updateAvailableInstallerUrl")) null else json.optString("updateAvailableInstallerUrl", null)
        )

        val nudgeLastSeenJson = json.optJSONObject("nudgeLastSeenByGroup") ?: JSONObject()
        nudgeLastSeenJson.keys().forEach { key -> data.nudgeLastSeenByGroup[key] = nudgeLastSeenJson.optLong(key, 0L) }

        val groupShareJson = json.optJSONObject("groupShareSettings") ?: JSONObject()
        groupShareJson.keys().forEach { groupId ->
            val g = groupShareJson.getJSONObject(groupId)
            data.groupShareSettings[groupId] = GroupShareSettings(
                shareRoutines = g.optBoolean("shareRoutines", true),
                shareStudy = g.optBoolean("shareStudy", true),
                shareStreak = g.optBoolean("shareStreak", true),
                shareSchedule = g.optBoolean("shareSchedule", true),
                shareStudyingNow = g.optBoolean("shareStudyingNow", true),
                shareActiveGroup = g.optBoolean("shareActiveGroup", true)
            )
        }
        val hiddenFromJson = json.optJSONObject("hiddenFromUidsByGroup") ?: JSONObject()
        hiddenFromJson.keys().forEach { groupId ->
            val arr = hiddenFromJson.optJSONArray(groupId) ?: JSONArray()
            data.hiddenFromUidsByGroup[groupId] = (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
        }
        val hiddenPeerJson = json.optJSONObject("hiddenPeerUidsByGroup") ?: JSONObject()
        hiddenPeerJson.keys().forEach { groupId ->
            val arr = hiddenPeerJson.optJSONArray(groupId) ?: JSONArray()
            data.hiddenPeerUidsByGroup[groupId] = (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
        }
        val randomNudgeJson = json.optJSONObject("groupRandomNudgeEnabled") ?: JSONObject()
        randomNudgeJson.keys().forEach { groupId -> data.groupRandomNudgeEnabled[groupId] = randomNudgeJson.optBoolean(groupId, true) }

        val studyLogJson = json.optJSONArray("studyLog") ?: JSONArray()
        for (i in 0 until studyLogJson.length()) {
            val s = studyLogJson.getJSONObject(i)
            data.studyLog.add(
                StudyLogEntry(
                    dateKey = s.getString("dateKey"),
                    taskName = s.optString("taskName", ""),
                    seconds = s.optInt("seconds", 0),
                    startedAt = s.optLong("startedAt", 0L),
                    note = s.optString("note", "")
                )
            )
        }

        val calendarTasksJson = json.optJSONArray("calendarTasks") ?: JSONArray()
        for (i in 0 until calendarTasksJson.length()) {
            val c = calendarTasksJson.getJSONObject(i)
            data.calendarTasks.add(
                CalendarTask(
                    dateKey = c.getString("dateKey"),
                    name = c.optString("name", ""),
                    color = c.optString("color", "white"),
                    status = if (c.isNull("status")) null else c.optString("status", null),
                    nextDays = if (c.has("nextDays") && !c.isNull("nextDays")) c.getInt("nextDays") else null,
                    linkedCalc = if (c.isNull("linkedCalc")) null else c.optString("linkedCalc", null),
                    progressStep = if (c.isNull("progressStep")) null else c.optString("progressStep", null),
                    multiPassEnabled = c.optBoolean("multiPassEnabled", false)
                )
            )
        }

        val calcTasksJson = json.optJSONArray("calcTasks") ?: JSONArray()
        for (i in 0 until calcTasksJson.length()) {
            val c = calcTasksJson.getJSONObject(i)
            val holidaysArr = c.optJSONArray("holidays") ?: JSONArray()
            data.calcTasks.add(
                CalcTask(
                    name = c.optString("name", ""), qty = c.optString("qty", ""), unit = c.optString("unit", ""),
                    progress = c.optString("progress", ""), start = c.optString("start", ""), dday = c.optString("dday", ""),
                    mon = c.optString("mon", ""), tue = c.optString("tue", ""), wed = c.optString("wed", ""),
                    thu = c.optString("thu", ""), fri = c.optString("fri", ""), sat = c.optString("sat", ""), sun = c.optString("sun", ""),
                    holidays = (0 until holidaysArr.length()).map { holidaysArr.getString(it) },
                    modifiedAt = c.optString("modifiedAt", ""), modifiedAtTs = c.optLong("modifiedAtTs", 0L)
                )
            )
        }

        val calcSavedJson = json.optJSONArray("calcSaved") ?: JSONArray()
        for (i in 0 until calcSavedJson.length()) {
            val c = calcSavedJson.getJSONObject(i)
            val holidaysArr = c.optJSONArray("holidays") ?: JSONArray()
            val folderPathArr = c.optJSONArray("folderPath")
            data.calcSaved.add(
                CalcSavedItem(
                    name = c.optString("name", ""), qty = c.optDouble("qty", 0.0), unit = c.optString("unit", ""),
                    progress = c.optDouble("progress", 0.0), start = c.optString("start", ""), dday = c.optString("dday", ""),
                    mon = c.optString("mon", ""), tue = c.optString("tue", ""), wed = c.optString("wed", ""),
                    thu = c.optString("thu", ""), fri = c.optString("fri", ""), sat = c.optString("sat", ""), sun = c.optString("sun", ""),
                    holidays = (0 until holidaysArr.length()).map { holidaysArr.getString(it) },
                    savedAt = c.optString("savedAt", ""), modifiedAt = c.optString("modifiedAt", ""),
                    folderPath = folderPathArr?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                )
            )
        }

        val calcFolderPathsJson = json.optJSONArray("calcFolderPaths") ?: JSONArray()
        for (i in 0 until calcFolderPathsJson.length()) {
            val p = calcFolderPathsJson.getJSONArray(i)
            data.calcFolderPaths.add((0 until p.length()).map { p.getString(it) })
        }

        val calcFolderOrderJson = json.optJSONObject("calcFolderOrder") ?: JSONObject()
        calcFolderOrderJson.keys().forEach { key ->
            val arr = calcFolderOrderJson.optJSONArray(key) ?: JSONArray()
            data.calcFolderOrder[key] = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        }

        val calcFolderCollapsedJson = json.optJSONArray("calcFolderCollapsed") ?: JSONArray()
        for (i in 0 until calcFolderCollapsedJson.length()) data.calcFolderCollapsed.add(calcFolderCollapsedJson.getString(i))

        val escalationsJson = json.optJSONArray("confirmEscalations") ?: JSONArray()
        for (i in 0 until escalationsJson.length()) {
            val e = escalationsJson.getJSONObject(i)
            data.confirmEscalations.add(
                ConfirmEscalation(
                    groupId = e.getLong("groupId"),
                    level = e.optInt("level", 0),
                    lastConfirmedAtEpochMillis = e.optLong("lastConfirmedAtEpochMillis", 0)
                )
            )
        }

        val groupsJson = json.optJSONArray("groups") ?: JSONArray()
        for (i in 0 until groupsJson.length()) {
            val g = groupsJson.getJSONObject(i)
            val processNames = mutableListOf<String>()
            val pn = g.optJSONArray("processNames") ?: JSONArray()
            for (j in 0 until pn.length()) processNames.add(pn.getString(j))
            val domains = mutableListOf<String>()
            val dm = g.optJSONArray("domains") ?: JSONArray()
            for (j in 0 until dm.length()) domains.add(dm.getString(j))
            data.groups.add(
                Group(
                    id = g.getLong("id"),
                    name = g.getString("name"),
                    dailyLimitSeconds = when {
                        g.has("dailyLimitSeconds") && !g.isNull("dailyLimitSeconds") -> g.getInt("dailyLimitSeconds")
                        g.has("dailyLimitMinutes") && !g.isNull("dailyLimitMinutes") -> g.getInt("dailyLimitMinutes") * 60
                        else -> null
                    },
                    dailyLimitApplyStartMinute = if (g.isNull("dailyLimitApplyStartMinute")) null else g.getInt("dailyLimitApplyStartMinute"),
                    dailyLimitApplyEndMinute = if (g.isNull("dailyLimitApplyEndMinute")) null else g.getInt("dailyLimitApplyEndMinute"),
                    dailyLimitDaysMask = g.optInt("dailyLimitDaysMask", 127),
                    scheduleStartMinute = if (g.isNull("scheduleStartMinute")) null else g.getInt("scheduleStartMinute"),
                    scheduleEndMinute = if (g.isNull("scheduleEndMinute")) null else g.getInt("scheduleEndMinute"),
                    scheduleDaysMask = g.optInt("scheduleDaysMask", 127),
                    enabled = g.optBoolean("enabled", true),
                    confirmEnabled = g.optBoolean("confirmEnabled", false),
                    confirmApplyStartMinute = if (g.isNull("confirmApplyStartMinute")) null else g.getInt("confirmApplyStartMinute"),
                    confirmApplyEndMinute = if (g.isNull("confirmApplyEndMinute")) null else g.getInt("confirmApplyEndMinute"),
                    confirmDaysMask = g.optInt("confirmDaysMask", 127),
                    initialWaitSeconds = g.optInt("initialWaitSeconds", 5),
                    waitIncrementSeconds = g.optInt("waitIncrementSeconds", 5),
                    confirmCooldownSeconds = when {
                        g.has("confirmCooldownSeconds") -> g.optInt("confirmCooldownSeconds", 300)
                        g.has("confirmCooldownMinutes") -> g.optInt("confirmCooldownMinutes", 5) * 60
                        else -> 300
                    },
                    levelDecayEnabled = g.optBoolean("levelDecayEnabled", true),
                    usageOverlayEnabled = g.optBoolean("usageOverlayEnabled", true),
                    overlayLevelStepsToMax = g.optInt("overlayLevelStepsToMax", 5),
                    pomodoroUnlockEnabled = g.optBoolean("pomodoroUnlockEnabled", false),
                    levelDecayIntervalSeconds = when {
                        g.has("levelDecayIntervalSeconds") -> g.optInt("levelDecayIntervalSeconds", 3600)
                        g.has("levelResetHours") -> g.optInt("levelResetHours", 1) * 3600
                        else -> 3600
                    },
                    scheduleEnabled = g.optBoolean("scheduleEnabled", true),
                    // groupEnabled/groupOffPending/groupOffMessageIndex는 이번에 새로 분리된 필드라
                    // 옛 데이터 파일에는 없다 — 없으면 예전에 "그룹 전체 on/off" 역할을 하던
                    // scheduleEnabled/scheduleOffPending/scheduleOffMessageIndex 값을 그대로 이어받아
                    // 업그레이드 직후에도 이전 상태(꺼둔 그룹은 계속 꺼진 채)가 유지되게 한다.
                    groupEnabled = g.optBoolean("groupEnabled", g.optBoolean("scheduleEnabled", true)),
                    groupOffPending = g.optBoolean("groupOffPending", g.optBoolean("scheduleOffPending", false)),
                    groupOffMessageIndex = g.optInt("groupOffMessageIndex", g.optInt("scheduleOffMessageIndex", 0)),
                    snoozeMinutes = g.optInt("snoozeMinutes", 30),
                    snoozedUntilEpochMillis = if (g.isNull("snoozedUntilEpochMillis")) null else g.optLong("snoozedUntilEpochMillis", 0L).let { if (it == 0L) null else it },
                    snoozeUsedDate = g.optString("snoozeUsedDate", ""),
                    snoozeUsedCount = g.optInt("snoozeUsedCount", 0),
                    forceEnabledFrom = if (g.isNull("forceEnabledFrom")) null else g.optString("forceEnabledFrom", null),
                    forceEnabledUntil = if (g.isNull("forceEnabledUntil")) null else g.optString("forceEnabledUntil", null),
                    blockAttemptDate = g.optString("blockAttemptDate", ""),
                    blockAttemptCount = g.optInt("blockAttemptCount", 0),
                    processNames = processNames,
                    domains = domains
                )
            )
        }

        val usageJson = json.optJSONArray("usageRecords") ?: JSONArray()
        for (i in 0 until usageJson.length()) {
            val u = usageJson.getJSONObject(i)
            data.usageRecords.add(UsageRecord(u.getLong("groupId"), u.getString("date"), u.getInt("usedSeconds")))
        }

        val confirmCountersJson = json.optJSONArray("confirmCounters") ?: JSONArray()
        for (i in 0 until confirmCountersJson.length()) {
            val c = confirmCountersJson.getJSONObject(i)
            data.confirmCounters.add(ConfirmCounter(c.getLong("groupId"), c.getString("date"), c.optInt("count", 0)))
        }

        val routinesJson = json.optJSONArray("routines") ?: JSONArray()
        for (i in 0 until routinesJson.length()) {
            val r = routinesJson.getJSONObject(i)
            data.routines.add(
                Routine(
                    id = r.getLong("id"),
                    title = r.optString("title", ""),
                    icon = r.optString("icon", ""),
                    timeSlot = if (r.isNull("timeSlot")) null else r.optString("timeSlot", null),
                    daysMask = r.optInt("daysMask", 127),
                    trackStreak = r.optBoolean("trackStreak", false),
                    defenseType = r.optString("defenseType", "NONE"),
                    defenseCount = r.optInt("defenseCount", 0),
                    sortOrder = r.optInt("sortOrder", 0),
                    archived = r.optBoolean("archived", false),
                    notifyEnabled = r.optBoolean("notifyEnabled", false),
                    startDate = if (r.isNull("startDate")) null else r.optString("startDate", null),
                    endDate = if (r.isNull("endDate")) null else r.optString("endDate", null)
                )
            )
        }

        val routineLogsJson = json.optJSONArray("routineLogs") ?: JSONArray()
        for (i in 0 until routineLogsJson.length()) {
            val l = routineLogsJson.getJSONObject(i)
            data.routineLogs.add(RoutineLog(l.getLong("routineId"), l.getString("dateKey")))
        }

        return data
    }

    fun save(data: AppData) {
        dataDir.mkdirs()
        val json = toJsonObject(data)
        val tempFile = File(dataDir, "data.json.tmp")
        tempFile.writeText(json.toString(2))
        Files.move(
            tempFile.toPath(),
            dataFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    /** 설정/그룹 내보내기(#6) — 사용자가 고른 임의 파일에 현재 데이터 전체를 저장한다. save()와 달리 원자적 교체가 필요 없다(대상이 앱 소유 파일이 아님). */
    fun exportToFile(data: AppData, file: File) {
        file.writeText(toJsonObject(data).toString(2))
    }

    private fun toJsonObject(data: AppData): JSONObject {
        val json = JSONObject()
        json.put("nextGroupId", data.nextGroupId)
        json.put("dailyResetHour", data.dailyResetHour)
        json.put("themeMode", data.themeMode)
        json.put("routinesTs", data.routinesTs)
        json.put("lastGroupAutoResetDate", data.lastGroupAutoResetDate ?: JSONObject.NULL)
        json.put("nextRoutineId", data.nextRoutineId)
        json.put("blockReels", data.blockReels)
        json.put("blockShorts", data.blockShorts)
        json.put("routineStreakNotifyEnabled", data.routineStreakNotifyEnabled)
        json.put("lastRoutineStreak", data.lastRoutineStreak)
        json.put("zeroStreakDays", data.zeroStreakDays)
        json.put("fbDatabaseUrl", data.fbDatabaseUrl ?: JSONObject.NULL)
        json.put("fbApiKey", data.fbApiKey ?: JSONObject.NULL)
        json.put("pomodoroStudyMinutes", data.pomodoroStudyMinutes)
        json.put("pomodoroBreakMinutes", data.pomodoroBreakMinutes)
        json.put("pomodoroModeEnabled", data.pomodoroModeEnabled)
        json.put("studyLockAllowedApps", JSONArray(data.studyLockAllowedApps))
        json.put("studyLockAllowedSites", JSONArray(data.studyLockAllowedSites))
        json.put("cachedApprovalStatus", data.cachedApprovalStatus ?: JSONObject.NULL)
        json.put("permRoutine", data.permRoutine)
        json.put("permStudy", data.permStudy)
        json.put("permManage", data.permManage)
        json.put("permSocial", data.permSocial)
        json.put("lastUpdateCheckDate", data.lastUpdateCheckDate ?: JSONObject.NULL)
        json.put("updateAvailableBuildTimestamp", data.updateAvailableBuildTimestamp)
        json.put("updateAvailableInstallerUrl", data.updateAvailableInstallerUrl ?: JSONObject.NULL)
        val nudgeLastSeenJson = JSONObject()
        data.nudgeLastSeenByGroup.forEach { (key, millis) -> nudgeLastSeenJson.put(key, millis) }
        json.put("nudgeLastSeenByGroup", nudgeLastSeenJson)
        val groupShareJson = JSONObject()
        data.groupShareSettings.forEach { (groupId, s) ->
            groupShareJson.put(groupId, JSONObject().apply {
                put("shareRoutines", s.shareRoutines)
                put("shareStudy", s.shareStudy)
                put("shareStreak", s.shareStreak)
                put("shareSchedule", s.shareSchedule)
                put("shareStudyingNow", s.shareStudyingNow)
                put("shareActiveGroup", s.shareActiveGroup)
            })
        }
        json.put("groupShareSettings", groupShareJson)
        val hiddenFromJson = JSONObject()
        data.hiddenFromUidsByGroup.forEach { (groupId, uids) -> hiddenFromJson.put(groupId, JSONArray(uids.toList())) }
        json.put("hiddenFromUidsByGroup", hiddenFromJson)
        val hiddenPeerJson = JSONObject()
        data.hiddenPeerUidsByGroup.forEach { (groupId, uids) -> hiddenPeerJson.put(groupId, JSONArray(uids.toList())) }
        json.put("hiddenPeerUidsByGroup", hiddenPeerJson)
        val randomNudgeJson = JSONObject()
        data.groupRandomNudgeEnabled.forEach { (groupId, enabled) -> randomNudgeJson.put(groupId, enabled) }
        json.put("groupRandomNudgeEnabled", randomNudgeJson)
        val timerRun = data.timerRun
        json.put("timerRun", if (timerRun == null) JSONObject.NULL else JSONObject().apply {
            put("taskName", timerRun.taskName)
            put("mode", timerRun.mode)
            put("phase", timerRun.phase)
            put("phaseStartedAt", timerRun.phaseStartedAt)
            put("phaseEndAt", timerRun.phaseEndAt)
            put("cycleCount", timerRun.cycleCount)
            put("breakExtraUsed", timerRun.breakExtraUsed)
        })
        val studyLogJson = JSONArray()
        data.studyLog.forEach { s ->
            studyLogJson.put(JSONObject().apply {
                put("dateKey", s.dateKey)
                put("taskName", s.taskName)
                put("seconds", s.seconds)
                put("startedAt", s.startedAt)
                put("note", s.note)
            })
        }
        json.put("studyLog", studyLogJson)

        json.put("calendarTs", data.calendarTs)
        val calendarTasksJson = JSONArray()
        data.calendarTasks.forEach { c ->
            calendarTasksJson.put(JSONObject().apply {
                put("dateKey", c.dateKey)
                put("name", c.name)
                put("color", c.color)
                put("status", c.status ?: JSONObject.NULL)
                put("nextDays", c.nextDays ?: JSONObject.NULL)
                put("linkedCalc", c.linkedCalc ?: JSONObject.NULL)
                put("progressStep", c.progressStep ?: JSONObject.NULL)
                put("multiPassEnabled", c.multiPassEnabled)
            })
        }
        json.put("calendarTasks", calendarTasksJson)

        json.put("calcTasksTs", data.calcTasksTs)
        val calcTasksJson = JSONArray()
        data.calcTasks.forEach { c ->
            calcTasksJson.put(JSONObject().apply {
                put("name", c.name); put("qty", c.qty); put("unit", c.unit); put("progress", c.progress)
                put("start", c.start); put("dday", c.dday)
                put("mon", c.mon); put("tue", c.tue); put("wed", c.wed); put("thu", c.thu)
                put("fri", c.fri); put("sat", c.sat); put("sun", c.sun)
                put("holidays", JSONArray(c.holidays))
                put("modifiedAt", c.modifiedAt); put("modifiedAtTs", c.modifiedAtTs)
            })
        }
        json.put("calcTasks", calcTasksJson)

        json.put("calcSavedTs", data.calcSavedTs)
        val calcSavedJson = JSONArray()
        data.calcSaved.forEach { c ->
            calcSavedJson.put(JSONObject().apply {
                put("name", c.name); put("qty", c.qty); put("unit", c.unit); put("progress", c.progress)
                put("start", c.start); put("dday", c.dday)
                put("mon", c.mon); put("tue", c.tue); put("wed", c.wed); put("thu", c.thu)
                put("fri", c.fri); put("sat", c.sat); put("sun", c.sun)
                put("holidays", JSONArray(c.holidays))
                put("savedAt", c.savedAt); put("modifiedAt", c.modifiedAt)
                put("folderPath", c.folderPath?.let { JSONArray(it) } ?: JSONObject.NULL)
            })
        }
        json.put("calcSaved", calcSavedJson)

        json.put("calcFolderTs", data.calcFolderTs)
        val calcFolderPathsJson = JSONArray()
        data.calcFolderPaths.forEach { p -> calcFolderPathsJson.put(JSONArray(p)) }
        json.put("calcFolderPaths", calcFolderPathsJson)

        json.put("calcFolderOrderTs", data.calcFolderOrderTs)
        val calcFolderOrderJson = JSONObject()
        data.calcFolderOrder.forEach { (key, order) -> calcFolderOrderJson.put(key, JSONArray(order)) }
        json.put("calcFolderOrder", calcFolderOrderJson)

        json.put("calcFolderCollapsed", JSONArray(data.calcFolderCollapsed.toList()))

        val groupsJson = JSONArray()
        data.groups.forEach { g ->
            val gj = JSONObject()
            gj.put("id", g.id)
            gj.put("name", g.name)
            gj.put("dailyLimitSeconds", g.dailyLimitSeconds ?: JSONObject.NULL)
            gj.put("dailyLimitApplyStartMinute", g.dailyLimitApplyStartMinute ?: JSONObject.NULL)
            gj.put("dailyLimitApplyEndMinute", g.dailyLimitApplyEndMinute ?: JSONObject.NULL)
            gj.put("dailyLimitDaysMask", g.dailyLimitDaysMask)
            gj.put("scheduleStartMinute", g.scheduleStartMinute ?: JSONObject.NULL)
            gj.put("scheduleEndMinute", g.scheduleEndMinute ?: JSONObject.NULL)
            gj.put("scheduleDaysMask", g.scheduleDaysMask)
            gj.put("enabled", g.enabled)
            gj.put("confirmEnabled", g.confirmEnabled)
            gj.put("confirmApplyStartMinute", g.confirmApplyStartMinute ?: JSONObject.NULL)
            gj.put("confirmApplyEndMinute", g.confirmApplyEndMinute ?: JSONObject.NULL)
            gj.put("confirmDaysMask", g.confirmDaysMask)
            gj.put("initialWaitSeconds", g.initialWaitSeconds)
            gj.put("waitIncrementSeconds", g.waitIncrementSeconds)
            gj.put("confirmCooldownSeconds", g.confirmCooldownSeconds)
            gj.put("levelDecayEnabled", g.levelDecayEnabled)
            gj.put("usageOverlayEnabled", g.usageOverlayEnabled)
            gj.put("overlayLevelStepsToMax", g.overlayLevelStepsToMax)
            gj.put("pomodoroUnlockEnabled", g.pomodoroUnlockEnabled)
            gj.put("levelDecayIntervalSeconds", g.levelDecayIntervalSeconds)
            gj.put("scheduleEnabled", g.scheduleEnabled)
            gj.put("groupEnabled", g.groupEnabled)
            gj.put("groupOffPending", g.groupOffPending)
            gj.put("groupOffMessageIndex", g.groupOffMessageIndex)
            gj.put("snoozeMinutes", g.snoozeMinutes)
            gj.put("snoozedUntilEpochMillis", g.snoozedUntilEpochMillis ?: JSONObject.NULL)
            gj.put("snoozeUsedDate", g.snoozeUsedDate)
            gj.put("snoozeUsedCount", g.snoozeUsedCount)
            gj.put("forceEnabledFrom", g.forceEnabledFrom ?: JSONObject.NULL)
            gj.put("forceEnabledUntil", g.forceEnabledUntil ?: JSONObject.NULL)
            gj.put("blockAttemptDate", g.blockAttemptDate)
            gj.put("blockAttemptCount", g.blockAttemptCount)
            gj.put("processNames", JSONArray(g.processNames))
            gj.put("domains", JSONArray(g.domains))
            groupsJson.put(gj)
        }
        json.put("groups", groupsJson)

        val usageJson = JSONArray()
        data.usageRecords.forEach { u ->
            val uj = JSONObject()
            uj.put("groupId", u.groupId)
            uj.put("date", u.date)
            uj.put("usedSeconds", u.usedSeconds)
            usageJson.put(uj)
        }
        json.put("usageRecords", usageJson)

        val confirmCountersJson = JSONArray()
        data.confirmCounters.forEach { c ->
            confirmCountersJson.put(JSONObject().apply {
                put("groupId", c.groupId); put("date", c.date); put("count", c.count)
            })
        }
        json.put("confirmCounters", confirmCountersJson)

        val routinesJson = JSONArray()
        data.routines.forEach { r ->
            routinesJson.put(JSONObject().apply {
                put("id", r.id)
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
        json.put("routines", routinesJson)

        val routineLogsJson = JSONArray()
        data.routineLogs.forEach { l ->
            routineLogsJson.put(JSONObject().apply {
                put("routineId", l.routineId)
                put("dateKey", l.dateKey)
            })
        }
        json.put("routineLogs", routineLogsJson)

        val escalationsJson = JSONArray()
        data.confirmEscalations.forEach { e ->
            val ej = JSONObject()
            ej.put("groupId", e.groupId)
            ej.put("level", e.level)
            ej.put("lastConfirmedAtEpochMillis", e.lastConfirmedAtEpochMillis)
            escalationsJson.put(ej)
        }
        json.put("confirmEscalations", escalationsJson)

        return json
    }
}
