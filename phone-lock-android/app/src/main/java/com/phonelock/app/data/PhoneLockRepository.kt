package com.phonelock.app.data

import android.content.Context
import androidx.room.withTransaction
import com.phonelock.app.BuildConfig
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** 일일 사용 한도의 "오늘" 날짜를 계산한다. resetHour 이전이면 아직 전날로 취급한다. */
private fun effectiveDate(resetHour: Int, now: LocalDateTime = LocalDateTime.now()): LocalDate =
    if (now.hour < resetHour) now.toLocalDate().minusDays(1) else now.toLocalDate()

/** Firebase 일일 사용시간 동기화(`dailyUsage/{date}/{그룹}/{device}`)에서 이 기기를 가리키는 키. */
private const val DAILY_USAGE_DEVICE = "android"

/** 스누즈(#1)를 하루에 그룹당 최대 몇 번까지 쓸 수 있는지 — 회유 절차 없이 바로 임시 해제되는 예외라 무제한이면 사실상 실행확인을 무력화하게 된다. */
private const val SNOOZE_DAILY_LIMIT = 3

/** 네이티브 공부 타이머(1단계)의 실행 상태. taskName/mode/phase 등은 [AppPreferences]에 낱개 필드로
 *  저장돼 있어(SharedPreferences 친화적), 조회 시 이 값으로 묶어서 돌려준다. */
data class TimerRunState(
    val taskName: String,
    val mode: String,
    val phase: String,
    val phaseStartedAt: Long,
    val phaseEndAt: Long,
    val cycleCount: Int,
    val breakExtraUsed: Boolean
)

class PhoneLockRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(context)
    private val groupDao = db.appGroupDao()
    private val memberDao = db.groupMemberDao()
    private val usageDao = db.usageRecordDao()
    private val groupSiteDao = db.groupSiteDao()
    private val confirmEscalationDao = db.confirmEscalationDao()
    private val confirmCounterDao = db.confirmCounterDao()
    private val studyLogEntryDao = db.studyLogEntryDao()
    private val calendarTaskDao = db.calendarTaskDao()
    private val calcTaskDao = db.calcTaskDao()
    private val calcSavedItemDao = db.calcSavedItemDao()
    private val routineDao = db.routineDao()
    private val routineLogDao = db.routineLogDao()
    private val preferences = AppPreferences(context)

    // 겹치는 그룹 중 "지금 실제로 제한 중인" 그룹을 우선하는 데 쓴다. LockEvaluator는 이 repository의
    // 공개 함수만 호출하므로 지연 초기화로 만들어도 순환 문제가 없다.
    private val evaluator by lazy { com.phonelock.app.service.LockEvaluator(this) }

    // 오버레이 타이머가 매 tick(2초)마다 getCurrentLevel()을 호출하는데, 이게 그대로 mergedEscalation()을
    // 거치면 매번 Firebase 네트워크 호출이 실행되어 배터리/성능에 좋지 않다.
    // 그룹별로 짧게(오버레이 표시용이니 약간의 지연은 무방) 캐싱해서 재조회 빈도를 줄인다.
    private data class CachedEscalation(val value: ConfirmEscalation, val fetchedAtMillis: Long)
    private val escalationCache = mutableMapOf<Long, CachedEscalation>()
    private val ESCALATION_CACHE_TTL_MS = 10_000L

    // tick()(주기 폴링/창전환 이벤트)과 checkSites()(브라우저 콘텐츠 변경 이벤트)가 서로 다른
    // Dispatchers.Default 스레드에서 동시에 오버레이를 갱신하면서 escalationCache/DB에 동시 접근할 수
    // 있다. 데스크탑판(Repository.kt)은 synchronized(lock)으로 이 구간을 직렬화하는데 안드로이드판엔
    // 이게 빠져 있어서, 동시 읽기/쓰기가 겹치면 레벨이 튀어 오버레이 불투명도가 불안정하게 변하는
    // 원인이 됐다. 아래 세 공개 함수(getConfirmWaitSeconds/getCurrentLevel/recordConfirm) 전체를
    // 뮤텍스로 감싸 데스크탑과 동일하게 직렬화한다.
    private val escalationMutex = Mutex()
    /** tick()과 checkSitesInternal()이 같은 그룹의 사용시간을 동시에 read-modify-write할 수 있어 유실을 막기 위한 뮤텍스. */
    private val usageMutex = Mutex()

    /** 전체 사용시간 기록을 CSV로 직렬화한다(날짜,그룹,사용시간(초) — 전문가 종합분석 보고서 #20, 데스크탑판과 대칭). */
    suspend fun exportUsageCsv(): String {
        val groups = groupDao.getAllOnce()
        val records = usageDao.getAllOnce()
        val header = "date,group,usedSeconds"
        val rows = records.map { r ->
            val groupName = groups.find { it.id == r.groupId }?.name ?: "(삭제된 그룹 ${r.groupId})"
            "${r.date},${csvEscape(groupName)},${r.usedSeconds}"
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    private fun csvEscape(s: String): String =
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) "\"${s.replace("\"", "\"\"")}\"" else s

    /**
     * 최근 [days]일(오늘 제외) 평균 사용시간(초) — 이 기기 로컬 기록 기준(전문가 종합분석 보고서 #34,
     * 이상 사용 패턴 감지용 순수 통계, 판정 로직과 무관, 데스크탑판과 대칭). 기록이 없으면 0.
     */
    suspend fun getRecentAverageUsageSeconds(groupId: Long, days: Int = 7): Int {
        val today = effectiveDate(dailyResetHour)
        val cutoff = today.minusDays(days.toLong()).toString()
        val todayStr = today.toString()
        val recent = usageDao.getInRangeExclusiveEnd(groupId, cutoff, todayStr)
        if (recent.isEmpty()) return 0
        return recent.sumOf { it.usedSeconds } / recent.size
    }

    /** 일일 사용 한도/요일 판정에 공통으로 쓰이는 "하루 시작 시각" (0~23시, 기본 자정). */
    val dailyResetHour: Int get() = preferences.dailyResetHour

    /** 공부앱 연동용 Firebase 설정 — LockEvaluator가 PomodoroSyncClient 호출 시 읽기 전용으로 사용한다. */
    val fbDatabaseUrl: String? get() = preferences.fbDatabaseUrl
    val fbApiKey: String? get() = preferences.fbApiKey

    // 그룹 일정 off-패널티 취소/완료 기록처럼, 호출한 화면(Composable)이 사라지는 바로 그 순간에도
    // 반드시 끝까지 실행되어야 하는 쓰기 작업을 위한 스코프. 화면의 rememberCoroutineScope를 쓰면
    // 화면이 사라질 때 그 스코프도 함께 취소되어 쓰기가 유실될 수 있어, repository 자체 수명에 묶는다.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun updateGroupFireAndForget(group: AppGroup) {
        ioScope.launch { groupDao.update(group) }
    }

    fun observeGroups(): Flow<List<AppGroup>> = groupDao.observeAll()

    fun observeMembers(groupId: Long): Flow<List<GroupMember>> = memberDao.observeMembers(groupId)

    suspend fun createGroup(group: AppGroup): Long = groupDao.insert(group)

    suspend fun updateGroup(group: AppGroup) = groupDao.update(group)

    /** 설정한 초기화 시간(dailyResetHour)이 지나면, 사용자가 꺼둔 그룹(groupEnabled=false)도 자동으로
     *  다시 켠다 — 사용자 요청: "초기화 시간이 지나면 그룹들이 꺼져 있더라도 다시 켜지게". 하루에 한 번만
     *  적용되도록 lastGroupAutoResetDate로 날짜가 바뀌었는지 확인한다(데스크탑판 Repository.applyDailyGroupResetIfNeeded와 대칭).
     *  회유 절차가 진행 중인(groupOffPending) 그룹은 아직 groupEnabled가 true라 대상이 아니다. */
    suspend fun applyDailyGroupResetIfNeeded() {
        val today = effectiveDate(dailyResetHour).toString()
        if (preferences.lastGroupAutoResetDate == today) return
        preferences.lastGroupAutoResetDate = today
        groupDao.getAllOnce().forEach { group ->
            if (!group.groupEnabled) {
                groupDao.update(group.copy(groupEnabled = true, groupOffPending = false, groupOffMessageIndex = 0))
            }
        }
    }

    /**
     * GitHub Releases(공부앱과 같은 저장소)에 새 안드로이드 APK가 올라왔는지 하루 1회(dailyResetHour
     * 기준 "오늘"이 바뀔 때) 확인한다 — applyDailyGroupResetIfNeeded와 동일한 lastXxxDate 가드 패턴.
     * 결과는 [AppPreferences]에 남기고 [pendingUpdateApkUrl]로 네트워크 호출 없이 조회한다.
     */
    suspend fun checkForUpdateIfNeeded() {
        val today = effectiveDate(dailyResetHour).toString()
        if (preferences.lastUpdateCheckDate == today) return
        preferences.lastUpdateCheckDate = today
        val result = com.phonelock.app.service.UpdateChecker.checkLatestAndroidRelease()
        val latest = result.getOrNull()
        if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) {
            preferences.updateAvailableVersionCode = latest.versionCode
            preferences.updateAvailableApkUrl = latest.apkUrl
        } else if (result.isSuccess) {
            // 확인엔 성공했고 정말 최신 버전일 때만 지워야 한다 — 확인 자체가 실패했으면(네트워크/요청
            // 한도 등) 이전에 남아있던 "업데이트 있음" 상태를 함부로 지우지 않는다.
            preferences.updateAvailableVersionCode = 0L
            preferences.updateAvailableApkUrl = null
        }
    }

    /** 지금 설치된 버전보다 새 릴리스가 있으면 그 APK 다운로드 URL, 없으면 null. [checkForUpdateIfNeeded]가
     *  남겨둔 값만 읽으므로(네트워크 호출 없음) Compose에서 매 리컴포지션에 불러도 안전하다. */
    fun pendingUpdateApkUrl(): String? =
        if (preferences.updateAvailableVersionCode > BuildConfig.VERSION_CODE) preferences.updateAvailableApkUrl else null

    /** 지금 설치된 versionCode — 설정 화면에 표시용. */
    fun currentVersionCode(): Long = BuildConfig.VERSION_CODE.toLong()

    /**
     * 설정 화면 "지금 확인" 버튼 전용 — [checkForUpdateIfNeeded]의 하루 1회 가드를 무시하고 즉시
     * GitHub Releases를 확인한다. 2026-08-30 발견: 예전엔 확인 실패(네트워크 오류, GitHub 요청 한도
     * 초과 등)와 "정말 최신 버전"을 구분 못 해서 실패해도 화면에 "최신 버전입니다"라고 잘못 표시됐다 —
     * 이제 세 가지 결과(업데이트 있음/최신 버전/확인 실패)를 명확히 구분해 돌려준다.
     */
    sealed class UpdateCheckOutcome {
        data class Available(val apkUrl: String) : UpdateCheckOutcome()
        object UpToDate : UpdateCheckOutcome()
        data class Failed(val reason: String) : UpdateCheckOutcome()
    }

    suspend fun checkForUpdateNow(): UpdateCheckOutcome {
        preferences.lastUpdateCheckDate = effectiveDate(dailyResetHour).toString()
        val result = com.phonelock.app.service.UpdateChecker.checkLatestAndroidRelease()
        val latest = result.getOrNull()
        return if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) {
            preferences.updateAvailableVersionCode = latest.versionCode
            preferences.updateAvailableApkUrl = latest.apkUrl
            UpdateCheckOutcome.Available(latest.apkUrl)
        } else if (result.isSuccess) {
            preferences.updateAvailableVersionCode = 0L
            preferences.updateAvailableApkUrl = null
            UpdateCheckOutcome.UpToDate
        } else {
            UpdateCheckOutcome.Failed(result.exceptionOrNull()?.message ?: "알 수 없는 오류")
        }
    }

    suspend fun deleteGroup(group: AppGroup) {
        memberDao.clearGroup(group.id)
        groupSiteDao.clearGroup(group.id)
        groupDao.delete(group)
    }

    /** 그룹을 복제해서 새 그룹으로 추가한다(멤버/사이트 포함, 스누즈/기간강화/차단시도 등 진행 중 상태는 초기화, 데스크탑판과 대칭). */
    suspend fun copyGroup(groupId: Long): Long? {
        val original = getGroup(groupId) ?: return null
        val members = getMembers(groupId).map { it.packageName }.toSet()
        val sites = getGroupSites(groupId).map { it.domain }.toSet()
        val copy = original.copy(
            id = 0,
            name = "${original.name} (복사본)",
            groupOffPending = false,
            groupOffMessageIndex = 0,
            snoozedUntilEpochMillis = null,
            snoozeUsedDate = "",
            snoozeUsedCount = 0,
            blockAttemptDate = "",
            blockAttemptCount = 0
        )
        val newId = createGroup(copy)
        setMembers(newId, members)
        setGroupSites(newId, sites)
        return newId
    }

    /**
     * PreMigrationBackup이 만든 raw SQLite 백업 JSON(`{table: [row, ...]}`)에서 그룹 관련 테이블을
     * 복구한다(52차, 동기화 타임스탬프 버그로 로컬 그룹이 통째로 지워진 사용자를 위한 1회성 복구 기능).
     * 원본 id를 그대로 유지해 group_member/usage_record/confirm_escalation/confirm_counter의 groupId
     * 참조가 안 깨지게 한다. 원본 백업이 SQLite BOOLEAN을 0/1 정수로 저장해두므로 그 부분만 변환한다.
     * @return 복구된 그룹 개수.
     */
    suspend fun restoreGroupsFromBackup(json: JSONObject): Int {
        fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
        fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
        fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key, null)
        fun JSONObject.bool(key: String, default: Boolean): Boolean =
            if (isNull(key)) default else optInt(key, if (default) 1 else 0) != 0

        var restored = 0
        val groupsJson = json.optJSONArray("app_group") ?: JSONArray()
        for (i in 0 until groupsJson.length()) {
            val row = groupsJson.getJSONObject(i)
            groupDao.insert(
                AppGroup(
                    id = row.optLong("id"),
                    name = row.optString("name", "이름 없는 그룹"),
                    dailyLimitSeconds = row.intOrNull("dailyLimitSeconds"),
                    dailyLimitApplyStartMinute = row.intOrNull("dailyLimitApplyStartMinute"),
                    dailyLimitApplyEndMinute = row.intOrNull("dailyLimitApplyEndMinute"),
                    dailyLimitDaysMask = row.optInt("dailyLimitDaysMask", 127),
                    scheduleStartMinute = row.intOrNull("scheduleStartMinute"),
                    scheduleEndMinute = row.intOrNull("scheduleEndMinute"),
                    scheduleDaysMask = row.optInt("scheduleDaysMask", 127),
                    enabled = row.bool("enabled", true),
                    confirmEnabled = row.bool("confirmEnabled", false),
                    confirmApplyStartMinute = row.intOrNull("confirmApplyStartMinute"),
                    confirmApplyEndMinute = row.intOrNull("confirmApplyEndMinute"),
                    confirmDaysMask = row.optInt("confirmDaysMask", 127),
                    initialWaitSeconds = row.optInt("initialWaitSeconds", 5),
                    waitIncrementSeconds = row.optInt("waitIncrementSeconds", 5),
                    confirmCooldownSeconds = row.optInt("confirmCooldownSeconds", 300),
                    usageOverlayEnabled = row.bool("usageOverlayEnabled", true),
                    overlayLevelStepsToMax = row.optInt("overlayLevelStepsToMax", 5),
                    pomodoroUnlockEnabled = row.bool("pomodoroUnlockEnabled", false),
                    levelDecayEnabled = row.bool("levelDecayEnabled", true),
                    levelDecayIntervalSeconds = row.optInt("levelDecayIntervalSeconds", 3600),
                    scheduleEnabled = row.bool("scheduleEnabled", true),
                    groupEnabled = row.bool("groupEnabled", true),
                    groupOffPending = row.bool("groupOffPending", false),
                    groupOffMessageIndex = row.optInt("groupOffMessageIndex", 0),
                    snoozeMinutes = row.optInt("snoozeMinutes", 30),
                    snoozedUntilEpochMillis = row.longOrNull("snoozedUntilEpochMillis"),
                    snoozeUsedDate = row.optString("snoozeUsedDate", ""),
                    snoozeUsedCount = row.optInt("snoozeUsedCount", 0),
                    forceEnabledFrom = row.stringOrNull("forceEnabledFrom"),
                    forceEnabledUntil = row.stringOrNull("forceEnabledUntil"),
                    blockAttemptDate = row.optString("blockAttemptDate", ""),
                    blockAttemptCount = row.optInt("blockAttemptCount", 0)
                )
            )
            restored++
        }
        json.optJSONArray("group_member")?.let { arr ->
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                memberDao.insert(GroupMember(row.optLong("groupId"), row.optString("packageName")))
            }
        }
        json.optJSONArray("group_site")?.let { arr ->
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                groupSiteDao.insert(GroupSite(row.optLong("groupId"), row.optString("domain")))
            }
        }
        json.optJSONArray("usage_record")?.let { arr ->
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                usageDao.upsert(UsageRecord(row.optLong("groupId"), row.optString("date"), row.optInt("usedSeconds", 0)))
            }
        }
        json.optJSONArray("confirm_escalation")?.let { arr ->
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                confirmEscalationDao.upsert(
                    ConfirmEscalation(row.optLong("groupId"), row.optInt("level", 0), row.optLong("lastConfirmedAtEpochMillis", 0))
                )
            }
        }
        json.optJSONArray("confirm_counter")?.let { arr ->
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                confirmCounterDao.upsert(ConfirmCounter(row.optLong("groupId"), row.optString("date"), row.optInt("count", 0)))
            }
        }
        return restored
    }

    /** 스누즈(#1) — 회유 절차 없이 group.snoozeMinutes만큼 즉시 임시 해제한다. 하루 3회 초과면 false.
     *  다른 기기와 합산한 오늘 사용 횟수([mergedSnooze])를 기준으로 한도를 판정해서, 데스크탑/안드로이드
     *  양쪽에서 나눠 눌러도 총 3회를 넘지 못하게 한다. */
    suspend fun snoozeGroup(id: Long): Boolean = snoozeMutex.withLock {
        val group = groupDao.getById(id) ?: return@withLock false
        val today = effectiveDate(dailyResetHour).toString()
        val merged = mergedSnooze(group)
        val usedToday = if (merged.usedDate == today) merged.usedCount else 0
        if (usedToday >= SNOOZE_DAILY_LIMIT) return@withLock false
        val updated = SnoozeState(
            untilEpochMillis = System.currentTimeMillis() + group.snoozeMinutes * 60_000L,
            usedDate = today,
            usedCount = usedToday + 1
        )
        groupDao.update(
            group.copy(
                snoozedUntilEpochMillis = updated.untilEpochMillis,
                snoozeUsedDate = updated.usedDate,
                snoozeUsedCount = updated.usedCount
            )
        )
        snoozeCache[id] = CachedSnooze(updated, System.currentTimeMillis())
        com.phonelock.app.service.PomodoroSyncClient
            .writeSnoozeSync(fbDatabaseUrl, fbApiKey, group.name, updated.untilEpochMillis, updated.usedDate, updated.usedCount)
        true
    }

    /** 오늘 이 그룹에 남은 스누즈 횟수(0~3) — UI에 "오늘 n/3" 표시용. 그룹 목록 화면이 매 리컴포지션마다
     *  호출하므로 네트워크 호출 없이 로컬 값만 본다(다른 기기의 스누즈는 [mergedSnooze]가 백그라운드
     *  판정 시점에 이미 로컬로 병합해둔 값을 통해 뒤늦게 반영된다). */
    fun snoozeRemainingToday(group: AppGroup): Int {
        val today = effectiveDate(dailyResetHour).toString()
        val usedToday = if (group.snoozeUsedDate == today) group.snoozeUsedCount else 0
        return (SNOOZE_DAILY_LIMIT - usedToday).coerceAtLeast(0)
    }

    private val blockAttemptMutex = Mutex()

    /** 잠김(스케줄/일일한도) 화면 조롱 문구 강도용 — 오늘 이 그룹을 열려고 시도한 횟수를 1 늘리고
     *  늘린 뒤의 값을 반환한다(dailyResetHour 기준 날짜가 바뀌면 1부터 다시 센다). 데스크탑판
     *  Repository.recordBlockAttempt와 같은 패턴. */
    suspend fun recordBlockAttempt(groupId: Long): Int = blockAttemptMutex.withLock {
        val group = groupDao.getById(groupId) ?: return@withLock 0
        val today = effectiveDate(dailyResetHour).toString()
        val count = if (group.blockAttemptDate == today) group.blockAttemptCount + 1 else 1
        groupDao.update(group.copy(blockAttemptDate = today, blockAttemptCount = count))
        count
    }

    data class SnoozeState(val untilEpochMillis: Long, val usedDate: String, val usedCount: Int)

    private data class CachedSnooze(val value: SnoozeState, val fetchedAtMillis: Long)
    private val snoozeCache = mutableMapOf<Long, CachedSnooze>()
    private val SNOOZE_CACHE_TTL_MS = 10_000L
    private val snoozeMutex = Mutex()

    /**
     * 스누즈(#1) 크로스디바이스 동기화 — mergedEscalation과 같은 "최신값(untilEpochMillis) 승리" 규칙.
     * 다른 기기가 더 최근에(더 미래 시각으로) 스누즈했다면 그 상태(종료 시각 + 오늘 사용 횟수)를 그대로
     * 채택해 로컬에도 병합·저장한다. 그룹은 이름으로 매칭한다(기기마다 그룹ID가 다름).
     */
    private suspend fun mergedSnooze(group: AppGroup): SnoozeState {
        val cached = snoozeCache[group.id]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMillis < SNOOZE_CACHE_TTL_MS) {
            return cached.value
        }
        val local = SnoozeState(group.snoozedUntilEpochMillis ?: 0L, group.snoozeUsedDate, group.snoozeUsedCount)
        val synced = com.phonelock.app.service.PomodoroSyncClient
            .readSnoozeSync(fbDatabaseUrl, fbApiKey, group.name)
            ?.let { SnoozeState(it.untilEpochMillis, it.usedDate, it.usedCount) }
        val winner = if (synced != null && synced.untilEpochMillis > local.untilEpochMillis) synced else local
        if (winner != local) {
            groupDao.update(
                group.copy(
                    snoozedUntilEpochMillis = winner.untilEpochMillis,
                    snoozeUsedDate = winner.usedDate,
                    snoozeUsedCount = winner.usedCount
                )
            )
        }
        snoozeCache[group.id] = CachedSnooze(winner, System.currentTimeMillis())
        return winner
    }

    /** 다른 기기의 스누즈까지 반영한 종료 시각(epoch millis, 스누즈 아니면 0) — LockEvaluator가 판정에 쓴다. */
    suspend fun syncedSnoozeUntil(group: AppGroup): Long = snoozeMutex.withLock { mergedSnooze(group).untilEpochMillis }

    suspend fun setMembers(groupId: Long, packageNames: Set<String>) {
        memberDao.clearGroup(groupId)
        packageNames.forEach { memberDao.insert(GroupMember(groupId, it)) }
    }

    suspend fun getMembers(groupId: Long): List<GroupMember> = memberDao.getMembers(groupId)

    suspend fun getAllEnabledGroups(): List<AppGroup> = groupDao.getAllEnabled()

    /**
     * 같은 앱이 여러 그룹에 겹쳐 등록된 경우, 첫 매칭만 보고 끝내면 그 그룹이 오늘 비활성(요일 미포함)이어도
     * 뒤에 있는 활성 그룹을 아예 검사하지 못하는 문제가 있었다. 이제는 겹치는 모든 활성 그룹을 그대로
     * 돌려주고, 호출하는 쪽에서 그 그룹들의 제한을 모두 함께 적용한다(하나라도 잠그면 잠기고, 확인이
     * 필요한 그룹이 있으면 각각 확인받아야 한다).
     */
    suspend fun findGroupsForPackage(packageName: String): List<AppGroup> {
        val candidates = memberDao.findAllByPackage(packageName)
        if (candidates.isEmpty()) return emptyList()
        return candidates.mapNotNull { groupDao.getById(it.groupId) }.filter { evaluator.isGroupActive(it) }
    }

    /** 사이트도 같은 이유로 여러 그룹에 겹쳐 등록됐을 수 있으므로, 매칭되는 도메인과 그 그룹을 모두 돌려준다. */
    suspend fun findGroupSitesForAddress(addressText: String): List<Pair<GroupSite, AppGroup>> {
        val candidates = groupSiteDao.getAllOnce().filter { addressText.contains(it.domain, ignoreCase = true) }
        if (candidates.isEmpty()) return emptyList()
        return candidates.mapNotNull { site -> groupDao.getById(site.groupId)?.let { site to it } }
            .filter { (_, group) -> evaluator.isGroupActive(group) }
    }

    suspend fun getGroup(id: Long): AppGroup? = groupDao.getById(id)

    /**
     * 로컬(이 기기)에서 오늘 쓴 시간 + 데스크탑이 Firebase에 올려둔 오늘 사용시간을 더한 값. 일일 한도는
     * 기기별이 아니라 그룹 전체 기준이므로, 같은 그룹을 데스크탑에서도 쓰고 있다면 합산해서 판정한다.
     */
    suspend fun getTodayUsageSeconds(groupId: Long): Int {
        val today = effectiveDate(preferences.dailyResetHour).toString()
        val local = usageDao.get(groupId, today)?.usedSeconds ?: 0
        val group = groupDao.getById(groupId) ?: return local
        return local + peerUsageSeconds(group, today)
    }

    private data class CachedPeerUsage(val peerSeconds: Int, val fetchedAtMillis: Long)
    private val peerUsageCache = java.util.concurrent.ConcurrentHashMap<Long, CachedPeerUsage>()
    private val PEER_USAGE_CACHE_TTL_MS = 10_000L

    /** 이 그룹의 오늘 사용시간 중 "이 기기가 아닌 다른 기기(데스크탑)"가 Firebase에 올려둔 몫만 합산한다. */
    private suspend fun peerUsageSeconds(group: AppGroup, dateStr: String): Int {
        val now = System.currentTimeMillis()
        val cached = peerUsageCache[group.id]
        if (cached != null && now - cached.fetchedAtMillis < PEER_USAGE_CACHE_TTL_MS) return cached.peerSeconds
        val map = com.phonelock.app.service.PomodoroSyncClient
            .readDailyUsage(fbDatabaseUrl, fbApiKey, dateStr, group.name) ?: emptyMap()
        val peerSeconds = map.filterKeys { it != DAILY_USAGE_DEVICE }.values.sum()
        peerUsageCache[group.id] = CachedPeerUsage(peerSeconds, now)
        return peerSeconds
    }

    private val usagePushCache = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val USAGE_PUSH_INTERVAL_MS = 30_000L

    /**
     * tick()/checkSites()가 2초마다 호출하므로, 매번 Firebase에 쓰면 배터리/네트워크 낭비가 크다.
     * 로컬 DB엔 매번 즉시 반영하되, Firebase 동기화는 그룹별로 USAGE_PUSH_INTERVAL_MS 간격으로만 보낸다
     * (데스크탑의 usagePersistIntervalMs와 동일한 주기).
     */
    suspend fun addUsageSeconds(groupId: Long, seconds: Int) {
        val today = effectiveDate(preferences.dailyResetHour).toString()
        val updated = usageMutex.withLock {
            val current = usageDao.get(groupId, today)?.usedSeconds ?: 0
            val next = current + seconds
            usageDao.upsert(UsageRecord(groupId, today, next))
            next
        }

        val now = System.currentTimeMillis()
        val lastPush = usagePushCache[groupId] ?: 0L
        if (now - lastPush >= USAGE_PUSH_INTERVAL_MS) {
            usagePushCache[groupId] = now
            val group = groupDao.getById(groupId)
            if (group != null) {
                com.phonelock.app.service.PomodoroSyncClient
                    .writeDailyUsage(fbDatabaseUrl, fbApiKey, today, group.name, DAILY_USAGE_DEVICE, updated)
            }
        }
    }

    fun observeGroupSites(groupId: Long): Flow<List<GroupSite>> = groupSiteDao.observeSites(groupId)

    suspend fun getGroupSites(groupId: Long): List<GroupSite> = groupSiteDao.getSites(groupId)

    suspend fun setGroupSites(groupId: Long, domains: Set<String>) {
        groupSiteDao.clearGroup(groupId)
        domains.forEach { groupSiteDao.insert(GroupSite(groupId, normalizeDomain(it))) }
    }

    suspend fun getAllGroupSitesOnce(): List<GroupSite> = groupSiteDao.getAllOnce()

    /**
     * 지금 시점 기준으로 레벨을 계산한다. 정해진 시각(정각 등) 기준이 아니라, 마지막 확인(진행 완료)
     * 시점으로부터 그룹이 설정한 levelDecayIntervalSeconds가 지날 때마다 레벨이 1씩 자연 차감된다
     * (여러 간격이 한꺼번에 지났으면 그만큼 한 번에 차감, 0 밑으로는 안 내려감). 그룹의
     * levelDecayEnabled가 꺼져 있으면 차감되지 않는다.
     */
    private fun decayedLevel(record: ConfirmEscalation, now: Long, group: AppGroup): Int {
        if (record.lastConfirmedAtEpochMillis <= 0) return record.level
        if (!group.levelDecayEnabled || group.levelDecayIntervalSeconds <= 0) return record.level
        val intervalMs = group.levelDecayIntervalSeconds.toLong() * 1000L
        val elapsed = now - record.lastConfirmedAtEpochMillis
        val decays = (elapsed / intervalMs).toInt()
        return (record.level - decays).coerceAtLeast(0)
    }

    /**
     * 로컬 값과 Firebase(데스크탑과 공유) 값 중 lastConfirmedAtEpochMillis가 더 최근인 쪽을 채택한다.
     * 데스크탑에서 더 최근에 확인했다면 그 값을 가져오고, 아니면 로컬 값을 그대로 쓴다. 그룹은 이름으로
     * 매칭하므로(기기마다 그룹ID가 다름), 같은 이름의 그룹이 있는 경우에만 동기화된다.
     */
    private suspend fun mergedEscalation(group: AppGroup): ConfirmEscalation {
        val cached = escalationCache[group.id]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMillis < ESCALATION_CACHE_TTL_MS) {
            return cached.value
        }

        val local = confirmEscalationDao.get(group.id) ?: ConfirmEscalation(groupId = group.id)
        val synced = com.phonelock.app.service.PomodoroSyncClient
            .readConfirmSync(fbDatabaseUrl, fbApiKey, group.name)
            ?.let { ConfirmEscalation(groupId = group.id, level = it.level, lastConfirmedAtEpochMillis = it.lastConfirmedAtEpochMillis) }
        if (synced == null) {
            escalationCache[group.id] = CachedEscalation(local, System.currentTimeMillis())
            return local
        }
        val winner = if (synced.lastConfirmedAtEpochMillis > local.lastConfirmedAtEpochMillis) synced else local
        if (winner.level != local.level || winner.lastConfirmedAtEpochMillis != local.lastConfirmedAtEpochMillis) {
            confirmEscalationDao.upsert(ConfirmEscalation(groupId = group.id, level = winner.level, lastConfirmedAtEpochMillis = winner.lastConfirmedAtEpochMillis))
        }
        escalationCache[group.id] = CachedEscalation(winner, System.currentTimeMillis())
        return winner
    }

    suspend fun getConfirmWaitSeconds(group: AppGroup): Int = escalationMutex.withLock {
        val merged = mergedEscalation(group)
        val level = decayedLevel(merged, System.currentTimeMillis(), group)
        group.initialWaitSeconds + level * group.waitIncrementSeconds
    }

    /** 지금 이 그룹의 실행확인 레벨(재확인할 때마다 오르고, 차감 간격이 지날 때마다 1씩 내려감). */
    suspend fun getCurrentLevel(group: AppGroup): Int = escalationMutex.withLock {
        val merged = mergedEscalation(group)
        decayedLevel(merged, System.currentTimeMillis(), group)
    }

    /**
     * 같은 이름의 그룹을 가진 다른 기기가 방금 확인(예)을 통과했다면 그 시각을, 아니면 이 기기의 마지막
     * 확인 시각을 반환한다(mergedEscalation과 같은 "최신값 승리" 규칙, 같은 캐시 재사용). 재확인 판정
     * 로직 자체(ConfirmationGate)는 건드리지 않는다 — 호출부(AppMonitorAccessibilityService)가 이 값과
     * 로컬 ConfirmationGate 값 중 더 나중(더 최근)인 쪽으로 "지금 유예시간 안인지"를 계산해서, 같은
     * 이름의 그룹이면 다른 기기에서 확인한 것도 재확인 없이 이어서 쓸 수 있게 한다.
     */
    suspend fun syncedLastConfirmedAtEpochMillis(group: AppGroup): Long = escalationMutex.withLock {
        mergedEscalation(group).lastConfirmedAtEpochMillis
    }

    suspend fun recordConfirm(group: AppGroup) = escalationMutex.withLock {
        val now = System.currentTimeMillis()
        val merged = mergedEscalation(group)
        val currentLevel = decayedLevel(merged, now, group)
        val updated = ConfirmEscalation(groupId = group.id, level = currentLevel + 1, lastConfirmedAtEpochMillis = now)
        confirmEscalationDao.upsert(updated)
        escalationCache[group.id] = CachedEscalation(updated, System.currentTimeMillis())
        incrementConfirmCounter(group.id)
        com.phonelock.app.service.PomodoroSyncClient
            .writeConfirmSync(fbDatabaseUrl, fbApiKey, group.name, updated.level, updated.lastConfirmedAtEpochMillis)
    }

    /** 재확인 화면을 통과할 때마다 그날 카운터를 1 증가 — 순수 로컬 통계, 판정 로직과 무관(데스크탑판과 대칭). */
    private suspend fun incrementConfirmCounter(groupId: Long) {
        val today = effectiveDate(dailyResetHour).toString()
        val current = confirmCounterDao.get(groupId, today)?.count ?: 0
        confirmCounterDao.upsert(ConfirmCounter(groupId, today, current + 1))
    }

    /** 이 그룹의 오늘/어제 재확인 통과 횟수("위반 시도" 카운터, 보고서 #32). */
    suspend fun getConfirmCountToday(groupId: Long): Int {
        val today = effectiveDate(dailyResetHour).toString()
        return confirmCounterDao.get(groupId, today)?.count ?: 0
    }

    suspend fun getConfirmCountYesterday(groupId: Long): Int {
        val yesterday = effectiveDate(dailyResetHour).minusDays(1).toString()
        return confirmCounterDao.get(groupId, yesterday)?.count ?: 0
    }

    /**
     * Activity의 lifecycleScope에서 recordConfirm을 호출하면 finish() 직후 화면이 사라지며 스코프가
     * 취소돼 DB/Firebase 기록이 유실될 수 있다(메모리 압박/태스크 스와이프 시). 이 Repository 자체의
     * ioScope(activity 생명주기와 무관하게 앱이 살아있는 한 유지됨)에서 fire-and-forget으로 실행한다.
     */
    fun recordConfirmFireAndForget(groupId: Long) {
        ioScope.launch {
            getGroup(groupId)?.let { recordConfirm(it) }
        }
    }

    // ---- 네이티브 공부 타이머(1단계) ----
    // 로컬(AppPreferences)이 유일한 source of truth. Firebase엔 페이즈 전환 시점에만 크로스디바이스
    // 신호로 write한다 — DECISIONS.md 참고. 이 기기 자신의 공부 잠금 판정은 더 이상 Firebase를
    // 거치지 않고 이 함수들을 직접 읽는다.

    var studyLockAllowedPackages: Set<String>
        get() = preferences.studyLockAllowedPackages
        set(value) { preferences.studyLockAllowedPackages = value }

    var studyLockAllowedSites: Set<String>
        get() = preferences.studyLockAllowedSites
        set(value) { preferences.studyLockAllowedSites = value }

    var pomodoroStudyMinutes: Int
        get() = preferences.pomodoroStudyMinutes
        set(value) { preferences.pomodoroStudyMinutes = value }

    var pomodoroBreakMinutes: Int
        get() = preferences.pomodoroBreakMinutes
        set(value) { preferences.pomodoroBreakMinutes = value }

    var pomodoroModeEnabled: Boolean
        get() = preferences.pomodoroModeEnabled
        set(value) { preferences.pomodoroModeEnabled = value }

    fun getTimerRun(): TimerRunState? {
        val startedAt = preferences.timerPhaseStartedAt
        if (startedAt <= 0L) return null
        return TimerRunState(
            taskName = preferences.timerTaskName,
            mode = preferences.timerMode,
            phase = preferences.timerPhase,
            phaseStartedAt = startedAt,
            phaseEndAt = preferences.timerPhaseEndAt,
            cycleCount = preferences.timerCycleCount,
            breakExtraUsed = preferences.timerBreakExtraUsed
        )
    }

    private fun writeTimerRun(state: TimerRunState?) {
        if (state == null) {
            preferences.timerPhaseStartedAt = 0L
        } else {
            preferences.timerTaskName = state.taskName
            preferences.timerMode = state.mode
            preferences.timerPhase = state.phase
            preferences.timerPhaseStartedAt = state.phaseStartedAt
            preferences.timerPhaseEndAt = state.phaseEndAt
            preferences.timerCycleCount = state.cycleCount
            preferences.timerBreakExtraUsed = state.breakExtraUsed
        }
        ioScope.launch {
            com.phonelock.app.service.PomodoroSyncClient.pushLocalStudyStatus(
                fbDatabaseUrl, fbApiKey,
                timerActive = state?.phase == "study",
                breakActive = state?.phase == "break",
                phaseEndAt = state?.phaseEndAt ?: 0L,
                mode = state?.mode ?: "plain",
                phaseStartedAt = state?.phaseStartedAt ?: 0L,
                taskName = state?.taskName ?: ""
            )
        }
    }

    /** 다른 기기가 올린 그날 공부 기록의 표시용 캐시(날짜 -> 그 날짜의 다른 기기 기록) — 로컬 DB엔
     *  병합해 쓰지 않는다. dailyUsage와 같은 "기기별 키" 패턴(경쟁 없음) 참고. */
    private var remoteStudyLogCache: Map<String, List<StudyLogEntry>> = emptyMap()

    suspend fun getTodayStudyLog(): List<StudyLogEntry> {
        val today = effectiveDate(preferences.dailyResetHour).toString()
        return studyLogEntryDao.getByDate(today) + (remoteStudyLogCache[today] ?: emptyList())
    }

    /** 캘린더 날짜 상세에서 임의 날짜(dateKey, yyyy-MM-dd)의 타이머 기록을 보여줄 때 사용. */
    suspend fun getStudyLogForDate(dateKey: String): List<StudyLogEntry> =
        studyLogEntryDao.getByDate(dateKey) + (remoteStudyLogCache[dateKey] ?: emptyList())

    /** 타이머가 "오늘 캘린더 일정"을 고를 때 쓰는 오늘 날짜 키 — 사용시간 기록과 같은 dailyResetHour 기준. */
    fun todayCalendarDateKey(): String = effectiveDate(preferences.dailyResetHour).toString()

    private fun addStudyLogEntry(taskName: String, seconds: Int, startedAt: Long, note: String = "") {
        if (seconds <= 0) return
        val today = effectiveDate(preferences.dailyResetHour).toString()
        ioScope.launch {
            studyLogEntryDao.insert(StudyLogEntry(dateKey = today, taskName = taskName.ifBlank { "이름 없는 공부" }, seconds = seconds, startedAt = startedAt, note = note))
            pushStudyLogToFirebase(today)
        }
    }

    /** 이 기기가 그날 기록한 전체를 fire-and-forget으로 Firebase에 덮어쓴다. */
    private suspend fun pushStudyLogToFirebase(dateKey: String) {
        val entries = studyLogEntryDao.getByDate(dateKey)
        val json = JSONArray()
        entries.forEach { e ->
            json.put(JSONObject().apply {
                put("taskName", e.taskName); put("seconds", e.seconds); put("startedAt", e.startedAt); put("note", e.note)
            })
        }
        com.phonelock.app.service.PomodoroSyncClient.writeStudyLogForDate(fbDatabaseUrl, fbApiKey, dateKey, DAILY_USAGE_DEVICE, json)
    }

    /**
     * 타이머/캘린더 화면 진입 시 호출 — 다른 기기가 올린 그날 공부 기록을 읽어와 [remoteStudyLogCache]에
     * 채운다. 로컬 DB엔 병합하지 않으므로(재호출해도 중복 안 생김) 부담 없이 반복 호출 가능하다.
     */
    suspend fun syncStudyLogFromFirebase(dateKey: String) {
        val remote = com.phonelock.app.service.PomodoroSyncClient.readStudyLogForDate(fbDatabaseUrl, fbApiKey, dateKey) ?: return
        val others = mutableListOf<StudyLogEntry>()
        remote.keys().forEach { device ->
            if (device == DAILY_USAGE_DEVICE) return@forEach
            val arr = remote.optJSONArray(device) ?: return@forEach
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                others.add(StudyLogEntry(dateKey = dateKey, taskName = obj.optString("taskName", ""), seconds = obj.optInt("seconds", 0), startedAt = obj.optLong("startedAt", 0L), note = obj.optString("note", "")))
            }
        }
        remoteStudyLogCache = remoteStudyLogCache + (dateKey to others)
    }

    /** 스톱워치/뽀모도로를 새로 시작한다. 이미 실행 중이면 아무 일도 하지 않는다. */
    fun timerStart(taskName: String, pomodoro: Boolean) {
        if (getTimerRun() != null) return
        val now = System.currentTimeMillis()
        val mode = if (pomodoro) "pomodoro" else "plain"
        val phaseEndAt = if (pomodoro) now + pomodoroStudyMinutes * 60_000L else 0L
        writeTimerRun(TimerRunState(taskName, mode, "study", now, phaseEndAt, cycleCount = 0, breakExtraUsed = false))
    }

    /** 타이머를 정지하고, 진행 중이던 공부 페이즈의 경과시간을 기록에 적립한다. note는 사용자가 남긴 짧은 회고(선택). */
    fun timerStop(note: String = "") {
        val run = getTimerRun() ?: return
        if (run.phase == "study") {
            val elapsed = ((System.currentTimeMillis() - run.phaseStartedAt) / 1000L).toInt()
            addStudyLogEntry(run.taskName, elapsed, run.phaseStartedAt, note)
        }
        writeTimerRun(null)
    }

    /**
     * 공부→휴식은 남은 시간이 다 됐을 때만(now >= phaseEndAt) 허용, 휴식→공부는 언제든 허용.
     * 웹앱 index.html의 timerSwitchPhase() 규칙을 그대로 이식 — DECISIONS.md/CLAUDE.md 참고.
     */
    fun timerSwitchPhase() {
        val run = getTimerRun() ?: return
        val now = System.currentTimeMillis()
        if (run.phase == "study") {
            if (run.mode != "pomodoro" || now < run.phaseEndAt) return
            val elapsed = ((now - run.phaseStartedAt) / 1000L).toInt()
            addStudyLogEntry(run.taskName, elapsed, run.phaseStartedAt)
            writeTimerRun(run.copy(phase = "break", phaseStartedAt = now, phaseEndAt = now + pomodoroBreakMinutes * 60_000L, breakExtraUsed = false))
        } else {
            writeTimerRun(run.copy(phase = "study", phaseStartedAt = now, phaseEndAt = now + pomodoroStudyMinutes * 60_000L, cycleCount = run.cycleCount + 1))
        }
    }

    /** 휴식이 다 됐을 때 1회 한정으로 5분 더 쉰다. */
    fun timerExtendBreak() {
        val run = getTimerRun() ?: return
        if (run.phase != "break" || run.breakExtraUsed) return
        writeTimerRun(run.copy(phaseEndAt = System.currentTimeMillis() + 5 * 60_000L, breakExtraUsed = true))
    }

    /** 지금 이 기기에서 공부 잠금(전체화면)이 걸려야 하는지 — 공부 페이즈로 진행 중일 때만. */
    fun isStudyLockActive(): Boolean = getTimerRun()?.phase == "study"

    fun isTimerPomodoroMode(): Boolean = getTimerRun()?.mode == "pomodoro"

    // ---- 네이티브 캘린더(2단계) ----
    // 웹앱 index.html의 calTasks[dateKey][]를 그대로 이식. Room엔 배열 순서 개념이 없어 sortOrder
    // 정수 필드로 같은 dateKey 안에서의 표시 순서를 관리한다. Firebase는 데스크탑과 동일하게
    // users/{user}/calendar 경로에 { tasks:{dateKey:[...]}, _ts } 전체문서 단위 LWW로 동기화한다.

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

    private var calendarTs: Long
        get() = preferences.calendarTs
        set(value) { preferences.calendarTs = value }

    suspend fun getCalendarTasks(dateKey: String): List<CalendarTask> = calendarTaskDao.getByDate(dateKey)

    /** 월 그리드 렌더용 — [fromKey, toKey] 범위(양 끝 포함)의 모든 일정. */
    suspend fun getCalendarTasksInRange(fromKey: String, toKey: String): List<CalendarTask> =
        calendarTaskDao.getByDateRange(fromKey, toKey)

    /** 통계(5단계) 화면용 — 날짜 범위 없이 전체 일정. */
    suspend fun getAllCalendarTasksOnce(): List<CalendarTask> = calendarTaskDao.getAllOnce()

    private suspend fun resortCalendarDay(dateKey: String) {
        val sorted = calendarTaskDao.getByDate(dateKey).sortedWith(
            compareBy<CalendarTask> { CALENDAR_COLOR_ORDER[it.color] ?: 99 }
                .thenComparator { a, b -> koreanCollator.compare(a.name, b.name) }
        )
        sorted.forEachIndexed { i, t -> if (t.sortOrder != i) calendarTaskDao.update(t.copy(sortOrder = i)) }
    }

    suspend fun addCalendarTask(dateKey: String, name: String) {
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

    suspend fun renameCalendarTask(task: CalendarTask, newName: String) {
        if (newName.isBlank()) return
        calendarTaskDao.update(task.copy(name = newName.trim()))
        pushCalendarToFirebase()
    }

    suspend fun recolorCalendarTask(task: CalendarTask, newColor: String) {
        calendarTaskDao.update(task.copy(color = newColor))
        resortCalendarDay(task.dateKey)
        pushCalendarToFirebase()
    }

    suspend fun setCalendarTaskNextDays(task: CalendarTask, nextDays: Int?) {
        calendarTaskDao.update(task.copy(nextDays = nextDays))
        pushCalendarToFirebase()
    }

    suspend fun setCalendarTaskMultiPass(task: CalendarTask, enabled: Boolean) {
        calendarTaskDao.update(task.copy(multiPassEnabled = enabled))
        pushCalendarToFirebase()
    }

    /** ▲▼ 순서 변경(드래그 아님 — 웹앱도 배열 스왑 버튼 방식). direction은 -1(위) 또는 +1(아래). */
    suspend fun moveCalendarTaskOrder(task: CalendarTask, direction: Int) {
        val dayTasks = calendarTaskDao.getByDate(task.dateKey)
        val idx = dayTasks.indexOfFirst { it.id == task.id }
        val targetIdx = idx + direction
        if (idx < 0 || targetIdx !in dayTasks.indices) return
        val a = dayTasks[idx]; val b = dayTasks[targetIdx]
        calendarTaskDao.update(a.copy(sortOrder = b.sortOrder))
        calendarTaskDao.update(b.copy(sortOrder = a.sortOrder))
        pushCalendarToFirebase()
    }

    suspend fun deleteCalendarTask(task: CalendarTask) {
        calendarTaskDao.delete(task)
        pushCalendarToFirebase()
    }

    private fun nextScheduleDateKey(dateKey: String, days: Int): String =
        LocalDate.parse(dateKey).plusDays(days.toLong()).toString()

    private suspend fun applyCalendarAutoSchedule(dateKey: String, task: CalendarTask) {
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

    private suspend fun revertCalendarAutoSchedule(dateKey: String, task: CalendarTask) {
        if (!task.multiPassEnabled) return
        val (nextColor, defaultDays) = CALENDAR_SCHEDULE[task.color] ?: return
        val days = task.nextDays?.takeIf { it >= 0 } ?: defaultDays
        val nKey = nextScheduleDateKey(dateKey, days)
        val target = calendarTaskDao.getByDate(nKey).firstOrNull { it.name == task.name && it.color == nextColor && it.status == null }
        if (target != null) calendarTaskDao.delete(target)
    }

    /** 미완료(X) 처리 시 다음날로 같은 업무를 그대로 복사(원본은 유지, "복사" 기능과 동일한 필드 이식). */
    private suspend fun applyIncompleteCarryOver(dateKey: String, task: CalendarTask) {
        val nKey = nextScheduleDateKey(dateKey, 1)
        val existing = calendarTaskDao.getByDate(nKey)
        val exists = existing.any { it.name == task.name && it.color == task.color && it.status == null }
        if (!exists) {
            val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
            calendarTaskDao.insert(task.copy(id = 0, dateKey = nKey, status = null, sortOrder = nextOrder))
        }
    }

    private suspend fun revertIncompleteCarryOver(dateKey: String, task: CalendarTask) {
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
    suspend fun setCalendarTaskStatus(task: CalendarTask, targetStatus: String) {
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

    private fun linkedProgressAmount(task: CalendarTask): Double =
        task.progressStep?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0

    private fun formatCalcNumber(n: Double): String {
        val r = Math.round(n * 100) / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
    }

    private suspend fun adjustLinkedCalcProgress(calcTaskName: String, delta: Double) {
        val t = calcTaskDao.getAll().find { it.name == calcTaskName } ?: return
        val newProgress = ((t.progress.toDoubleOrNull() ?: 0.0) + delta).coerceAtLeast(0.0)
        calcTaskDao.update(t.copy(progress = formatCalcNumber(newProgress), modifiedAt = nowLabel(), modifiedAtTs = System.currentTimeMillis()))
        pushCalcTasksAndSaved()
    }

    /** 계산기 업무의 [from, to] 범위를 이 날짜의 캘린더 일정으로 새로 만든다(예: "국어 51~60쪽"). */
    suspend fun addLinkedCalendarTask(dateKey: String, calcTaskName: String, from: Int, to: Int) {
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

    /** 그 날짜에 calcTaskName과 연동된, 완료(O) 처리된 일정들의 progressStep 합이 dayQuota 이상이면 달성. */
    suspend fun isLinkedGoalAchieved(dateKey: String, calcTaskName: String, dayQuota: Double): Boolean {
        if (dayQuota <= 0) return false
        val doneTotal = calendarTaskDao.getByDate(dateKey)
            .filter { it.linkedCalc == calcTaskName && it.status == "O" }
            .sumOf { it.progressStep?.toDoubleOrNull() ?: 0.0 }
        return doneTotal >= dayQuota
    }

    /** 이동: 대상 날짜로 옮기고 nextDays/linkedCalc/progressStep은 버린다(웹앱 moveCalTask 경로와 동일 동작). */
    suspend fun moveCalendarTaskToDate(task: CalendarTask, targetDateKey: String) {
        calendarTaskDao.delete(task)
        val existing = calendarTaskDao.getByDate(targetDateKey)
        val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        calendarTaskDao.insert(CalendarTask(dateKey = targetDateKey, name = task.name, color = task.color, status = task.status, sortOrder = nextOrder))
        resortCalendarDay(targetDateKey)
        pushCalendarToFirebase()
    }

    /** 복사: 상태는 초기화(null)하고 nextDays 등 부가 필드는 그대로 옮긴다. 원본은 유지. */
    suspend fun copyCalendarTaskToDate(task: CalendarTask, targetDateKey: String) {
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
    suspend fun pruneOldStats(monthsAgo: Int = 12): Int {
        val cutoff = effectiveDate(dailyResetHour).minusMonths(monthsAgo.toLong()).toString()
        return usageDao.deleteBefore(cutoff) + confirmCounterDao.deleteBefore(cutoff) + studyLogEntryDao.deleteBefore(cutoff)
    }

    /** 오늘 기준 6개월 이전 일정을 영구 삭제(되돌리기 없음, 웹앱 confirmArchiveOldCalTasks와 동일). 삭제된 항목 수 반환. */
    suspend fun archiveOldCalendarTasks(): Int {
        val cutoffKey = LocalDate.now().minusMonths(6).toString()
        val removed = calendarTaskDao.deleteBefore(cutoffKey)
        if (removed > 0) pushCalendarToFirebase()
        return removed
    }

    private fun calendarTasksToJson(tasks: List<CalendarTask>): JSONObject {
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

    private fun calendarTasksFromJson(root: JSONObject): List<CalendarTask> {
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
    private fun pushCalendarToFirebase() {
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
    suspend fun syncCalendarFromFirebase() {
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

    // ══════════════════════════════════════════════════════
    // 네이티브 계산기(3단계) — 데스크탑 Repository의 계산기 섹션과 동일 로직/동일 Firebase 스키마.
    // draft(calcTaskDao)/저장됨(calcSavedItemDao)/폴더 트리는 각자 독립 LWW 타임스탬프를 쓴다.
    // Room에는 배열 재정렬 개념이 없어 CalendarTask와 마찬가지로 sortOrder 필드로 순서를 관리한다.
    // ══════════════════════════════════════════════════════

    private fun encodeHolidays(list: List<String>): String = list.joinToString(",")
    private fun decodeHolidays(csv: String): List<String> = if (csv.isBlank()) emptyList() else csv.split(",")
    private fun encodeFolderPath(path: List<String>?): String = path?.joinToString("|") ?: ""
    private fun decodeFolderPath(csv: String): List<String>? = if (csv.isBlank()) null else csv.split("|")

    private var calcTasksTs: Long
        get() = preferences.calcTasksTs
        set(value) { preferences.calcTasksTs = value }
    private var calcSavedTs: Long
        get() = preferences.calcSavedTs
        set(value) { preferences.calcSavedTs = value }
    private var calcFolderTs: Long
        get() = preferences.calcFolderTs
        set(value) { preferences.calcFolderTs = value }
    private var calcFolderOrderTs: Long
        get() = preferences.calcFolderOrderTs
        set(value) { preferences.calcFolderOrderTs = value }

    private fun getCalcFolderPathsLocal(): MutableList<List<String>> {
        val arr = JSONArray(preferences.calcFolderPathsJson)
        return (0 until arr.length()).map { i -> val p = arr.getJSONArray(i); (0 until p.length()).map { p.getString(it) } }.toMutableList()
    }
    private fun saveCalcFolderPathsLocal(paths: List<List<String>>) {
        val arr = JSONArray(); paths.forEach { arr.put(JSONArray(it)) }
        preferences.calcFolderPathsJson = arr.toString()
    }
    private fun getCalcFolderOrderLocal(): MutableMap<String, MutableList<String>> {
        val obj = JSONObject(preferences.calcFolderOrderJson)
        val map = mutableMapOf<String, MutableList<String>>()
        obj.keys().forEach { k -> val arr = obj.getJSONArray(k); map[k] = (0 until arr.length()).map { arr.getString(it) }.toMutableList() }
        return map
    }
    private fun saveCalcFolderOrderLocal(order: Map<String, List<String>>) {
        val obj = JSONObject(); order.forEach { (k, v) -> obj.put(k, JSONArray(v)) }
        preferences.calcFolderOrderJson = obj.toString()
    }
    private fun calcPathToOrderKey(path: List<String>): String = if (path.isEmpty()) "__root__" else path.joinToString("|")

    private fun getCalcFolderCollapsedLocal(): MutableSet<String> {
        val arr = JSONArray(preferences.calcFolderCollapsedJson)
        return (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
    }
    private fun saveCalcFolderCollapsedLocal(set: Set<String>) {
        preferences.calcFolderCollapsedJson = JSONArray(set.toList()).toString()
    }

    /** 폴더 접기 상태(기기 로컬, Firebase 미동기화) — 기본값 false(펼침)로 기존 동작을 유지한다. */
    fun isCalcFolderCollapsed(path: List<String>): Boolean = calcPathToOrderKey(path) in getCalcFolderCollapsedLocal()

    fun toggleCalcFolderCollapsed(path: List<String>) {
        val key = calcPathToOrderKey(path)
        val set = getCalcFolderCollapsedLocal()
        if (!set.add(key)) set.remove(key)
        saveCalcFolderCollapsedLocal(set)
    }

    suspend fun getCalcTasks(): List<CalcTask> = calcTaskDao.getAll()

    suspend fun addCalcTask() {
        val nextOrder = (calcTaskDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        calcTaskDao.insert(CalcTask(sortOrder = nextOrder))
        calcTasksTs = System.currentTimeMillis()
        pushCalcTasksAndSaved()
    }

    suspend fun updateCalcTask(task: CalcTask) {
        calcTaskDao.update(task.copy(modifiedAt = nowLabel(), modifiedAtTs = System.currentTimeMillis()))
        calcTasksTs = System.currentTimeMillis()
        pushCalcTasksAndSaved()
    }

    suspend fun removeCalcTask(task: CalcTask) {
        calcTaskDao.delete(task)
        calcTasksTs = System.currentTimeMillis()
        pushCalcTasksAndSaved()
    }

    suspend fun moveCalcTaskOrder(task: CalcTask, direction: Int) {
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
    suspend fun resetCalcTasks() {
        calcTaskDao.deleteAll()
        calcTaskDao.insert(CalcTask(sortOrder = 0))
        calcTasksTs = System.currentTimeMillis()
        pushCalcTasksAndSaved()
    }

    suspend fun getCalcSaved(): List<CalcSavedItem> = calcSavedItemDao.getAll()

    /** 계산 결과 저장 — 같은 이름이 이미 있으면 덮어쓰고(폴더 위치 유지), 없으면 추가(웹앱 saveOneResult와 동일). */
    suspend fun saveCalcResult(task: CalcTask, result: com.phonelock.app.calc.CalcEngine.CalcResult) {
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

    private fun fmtCalcNumber(n: Double): String = if (n == 0.0) "" else if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()

    /** 저장 항목을 입력(draft) 목록에 새 카드로 추가(웹앱 loadSavedItem). */
    suspend fun loadCalcSavedItemAsDraft(item: CalcSavedItem) {
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

    suspend fun deleteCalcSavedItem(item: CalcSavedItem) {
        calcSavedItemDao.delete(item)
        calcSavedTs = System.currentTimeMillis()
        pushCalcTasksAndSaved()
    }

    suspend fun moveCalcSavedItem(item: CalcSavedItem, direction: Int) {
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

    suspend fun clearAllCalcSaved() {
        calcSavedItemDao.deleteAll()
        calcSavedTs = System.currentTimeMillis()
        pushCalcTasksAndSaved()
    }

    suspend fun moveCalcSavedItemToFolder(item: CalcSavedItem, folderPath: List<String>?) {
        calcSavedItemDao.update(item.copy(folderPathCsv = encodeFolderPath(folderPath?.takeIf { it.isNotEmpty() })))
        calcSavedTs = System.currentTimeMillis()
        pushCalcTasksAndSaved()
    }

    fun getCalcFolderPaths(): List<List<String>> = getCalcFolderPathsLocal()

    /** 부모 경로 밑 하위 폴더 이름을 저장된 순서대로 반환, 순서에 없는 새 폴더는 뒤에 붙인다. */
    fun getCalcSubfolderNames(parentPath: List<String>): List<String> {
        val allNames = getCalcFolderPathsLocal().filter {
            it.size == parentPath.size + 1 && it.subList(0, parentPath.size) == parentPath
        }.map { it.last() }.distinct()
        val order = getCalcFolderOrderLocal()[calcPathToOrderKey(parentPath)] ?: emptyList()
        val existing = order.filter { it in allNames }
        val added = allNames.filter { it !in existing }
        return existing + added
    }

    fun createCalcFolder(parentPath: List<String>, name: String): Boolean {
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

    suspend fun renameCalcFolder(path: List<String>, newName: String): Boolean {
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
    suspend fun deleteCalcFolder(path: List<String>) {
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

    fun moveCalcFolderOrder(parentPath: List<String>, name: String, direction: Int) {
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
    private suspend fun healCalcFolderPaths(): Boolean {
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

    private fun nowLabel(): String {
        val ts = java.time.LocalDateTime.now()
        return "%d/%d %02d:%02d".format(ts.monthValue, ts.dayOfMonth, ts.hour, ts.minute)
    }

    private fun calcTaskToJson(t: CalcTask): JSONObject = JSONObject().apply {
        put("name", t.name); put("qty", t.qty); put("unit", t.unit); put("progress", t.progress)
        put("start", t.start); put("dday", t.dday)
        put("mon", t.mon); put("tue", t.tue); put("wed", t.wed); put("thu", t.thu)
        put("fri", t.fri); put("sat", t.sat); put("sun", t.sun)
        put("holidays", JSONArray(decodeHolidays(t.holidaysCsv)))
        put("modifiedAt", t.modifiedAt); put("modifiedAtTs", t.modifiedAtTs)
    }

    private fun calcTaskFromJson(t: JSONObject, order: Int): CalcTask {
        val holidaysArr = t.optJSONArray("holidays") ?: JSONArray()
        return CalcTask(
            name = t.optString("name", ""), qty = t.optString("qty", ""), unit = t.optString("unit", ""),
            progress = t.optString("progress", ""), start = t.optString("start", ""), dday = t.optString("dday", ""),
            mon = t.optString("mon", ""), tue = t.optString("tue", ""), wed = t.optString("wed", ""),
            thu = t.optString("thu", ""), fri = t.optString("fri", ""), sat = t.optString("sat", ""), sun = t.optString("sun", ""),
            holidaysCsv = encodeHolidays((0 until holidaysArr.length()).map { holidaysArr.getString(it) }),
            modifiedAt = t.optString("modifiedAt", ""), modifiedAtTs = t.optLong("modifiedAtTs", 0L), sortOrder = order
        )
    }

    private fun calcSavedToJson(s: CalcSavedItem): JSONObject = JSONObject().apply {
        put("name", s.name); put("qty", s.qty); put("unit", s.unit); put("progress", s.progress)
        put("start", s.start); put("dday", s.dday)
        put("mon", s.mon); put("tue", s.tue); put("wed", s.wed); put("thu", s.thu)
        put("fri", s.fri); put("sat", s.sat); put("sun", s.sun)
        put("holidays", JSONArray(decodeHolidays(s.holidaysCsv)))
        put("savedAt", s.savedAt); put("modifiedAt", s.modifiedAt)
        put("folderPath", decodeFolderPath(s.folderPathCsv)?.let { JSONArray(it) } ?: JSONObject.NULL)
    }

    private fun calcSavedFromJson(s: JSONObject, order: Int): CalcSavedItem {
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

    private fun pushCalcTasksAndSaved() {
        ioScope.launch {
            val tasksJson = JSONArray().also { arr -> calcTaskDao.getAll().forEach { arr.put(calcTaskToJson(it)) } }
            val savedJson = JSONArray().also { arr -> calcSavedItemDao.getAll().forEach { arr.put(calcSavedToJson(it)) } }
            com.phonelock.app.service.PomodoroSyncClient.writeCalcTasksAndSaved(
                fbDatabaseUrl, fbApiKey, tasksJson, calcTasksTs, savedJson, calcSavedTs
            )
        }
    }

    private fun pushCalcFolders() {
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
    suspend fun syncCalculatorFromFirebase() {
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

    private fun normalizeDomain(domain: String): String = domain.trim().lowercase()

    suspend fun exportBackupJson(): String {
        val groupsJson = JSONArray()
        groupDao.getAllOnce().forEach { group ->
            val groupJson = JSONObject()
            groupJson.put("name", group.name)
            groupJson.put("dailyLimitSeconds", group.dailyLimitSeconds ?: JSONObject.NULL)
            groupJson.put("dailyLimitApplyStartMinute", group.dailyLimitApplyStartMinute ?: JSONObject.NULL)
            groupJson.put("dailyLimitApplyEndMinute", group.dailyLimitApplyEndMinute ?: JSONObject.NULL)
            groupJson.put("dailyLimitDaysMask", group.dailyLimitDaysMask)
            groupJson.put("scheduleStartMinute", group.scheduleStartMinute ?: JSONObject.NULL)
            groupJson.put("scheduleEndMinute", group.scheduleEndMinute ?: JSONObject.NULL)
            groupJson.put("scheduleDaysMask", group.scheduleDaysMask)
            groupJson.put("enabled", group.enabled)
            groupJson.put("confirmEnabled", group.confirmEnabled)
            groupJson.put("confirmApplyStartMinute", group.confirmApplyStartMinute ?: JSONObject.NULL)
            groupJson.put("confirmApplyEndMinute", group.confirmApplyEndMinute ?: JSONObject.NULL)
            groupJson.put("confirmDaysMask", group.confirmDaysMask)
            groupJson.put("initialWaitSeconds", group.initialWaitSeconds)
            groupJson.put("waitIncrementSeconds", group.waitIncrementSeconds)
            groupJson.put("confirmCooldownSeconds", group.confirmCooldownSeconds)
            groupJson.put("levelDecayEnabled", group.levelDecayEnabled)
            groupJson.put("levelDecayIntervalSeconds", group.levelDecayIntervalSeconds)
            groupJson.put("usageOverlayEnabled", group.usageOverlayEnabled)
            groupJson.put("pomodoroUnlockEnabled", group.pomodoroUnlockEnabled)
            groupJson.put("scheduleEnabled", group.scheduleEnabled)
            groupJson.put("groupEnabled", group.groupEnabled)
            groupJson.put("groupOffPending", group.groupOffPending)
            groupJson.put("groupOffMessageIndex", group.groupOffMessageIndex)
            val membersJson = JSONArray()
            memberDao.getMembers(group.id).forEach { membersJson.put(it.packageName) }
            groupJson.put("members", membersJson)
            val sitesJson = JSONArray()
            groupSiteDao.getSites(group.id).forEach { sitesJson.put(it.domain) }
            groupJson.put("sites", sitesJson)
            groupsJson.put(groupJson)
        }

        val root = JSONObject()
        root.put("groups", groupsJson)
        return root.toString(2)
    }

    suspend fun importBackupJson(json: String) {
        // 파싱을 먼저 전부 끝내서(실패하면 여기서 예외가 던져짐), 잘못된 백업 파일을 선택했을 때
        // 삭제가 아예 실행되지 않도록 보장한다. 그 다음 삭제+재삽입 전체를 하나의 DB 트랜잭션으로
        // 묶어서, 도중에(그룹별 파싱 등) 예외가 나더라도 이미 지운 데이터가 롤백되게 한다.
        val root = JSONObject(json)
        val groupsJson = root.optJSONArray("groups") ?: JSONArray()

        db.withTransaction {
            memberDao.deleteAllMembers()
            groupSiteDao.deleteAllSites()
            groupDao.deleteAll()

            for (i in 0 until groupsJson.length()) {
                val groupJson = groupsJson.getJSONObject(i)
                val group = AppGroup(
                    name = groupJson.optString("name", "이름 없는 그룹"),
                    dailyLimitSeconds = if (groupJson.isNull("dailyLimitSeconds")) null else groupJson.getInt("dailyLimitSeconds"),
                    dailyLimitApplyStartMinute = if (groupJson.isNull("dailyLimitApplyStartMinute")) null else groupJson.getInt("dailyLimitApplyStartMinute"),
                    dailyLimitApplyEndMinute = if (groupJson.isNull("dailyLimitApplyEndMinute")) null else groupJson.getInt("dailyLimitApplyEndMinute"),
                    dailyLimitDaysMask = groupJson.optInt("dailyLimitDaysMask", 127),
                    scheduleStartMinute = if (groupJson.isNull("scheduleStartMinute")) null else groupJson.getInt("scheduleStartMinute"),
                    scheduleEndMinute = if (groupJson.isNull("scheduleEndMinute")) null else groupJson.getInt("scheduleEndMinute"),
                    scheduleDaysMask = groupJson.optInt("scheduleDaysMask", 127),
                    enabled = groupJson.optBoolean("enabled", true),
                    confirmEnabled = groupJson.optBoolean("confirmEnabled", false),
                    confirmApplyStartMinute = if (groupJson.isNull("confirmApplyStartMinute")) null else groupJson.getInt("confirmApplyStartMinute"),
                    confirmApplyEndMinute = if (groupJson.isNull("confirmApplyEndMinute")) null else groupJson.getInt("confirmApplyEndMinute"),
                    confirmDaysMask = groupJson.optInt("confirmDaysMask", 127),
                    initialWaitSeconds = groupJson.optInt("initialWaitSeconds", 5),
                    waitIncrementSeconds = groupJson.optInt("waitIncrementSeconds", 5),
                    confirmCooldownSeconds = groupJson.optInt("confirmCooldownSeconds", 300),
                    levelDecayEnabled = groupJson.optBoolean("levelDecayEnabled", true),
                    levelDecayIntervalSeconds = groupJson.optInt("levelDecayIntervalSeconds", 3600),
                    usageOverlayEnabled = groupJson.optBoolean("usageOverlayEnabled", true),
                    pomodoroUnlockEnabled = groupJson.optBoolean("pomodoroUnlockEnabled", false),
                    scheduleEnabled = groupJson.optBoolean("scheduleEnabled", true),
                    groupEnabled = groupJson.optBoolean("groupEnabled", groupJson.optBoolean("scheduleEnabled", true)),
                    groupOffPending = groupJson.optBoolean("groupOffPending", groupJson.optBoolean("scheduleOffPending", false)),
                    groupOffMessageIndex = groupJson.optInt("groupOffMessageIndex", groupJson.optInt("scheduleOffMessageIndex", 0))
                )
                val groupId = groupDao.insert(group)
                val membersJson = groupJson.optJSONArray("members") ?: JSONArray()
                for (j in 0 until membersJson.length()) {
                    memberDao.insert(GroupMember(groupId, membersJson.getString(j)))
                }
                val sitesJson = groupJson.optJSONArray("sites") ?: JSONArray()
                for (j in 0 until sitesJson.length()) {
                    groupSiteDao.insert(GroupSite(groupId, sitesJson.getString(j)))
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // 루틴앱 v1(47차 설계, DECISIONS.md 참고) — Routine 하나로 체크리스트/습관/일과표 통합.
    // 51차: 캘린더와 동일한 "전체 문서 단위 LWW"로 Firebase 동기화 추가(users/{user}/routines,
    // 데스크탑판과 대칭).
    // ══════════════════════════════════════════════════════

    private var routinesTs: Long
        get() = preferences.routinesTs
        set(value) { preferences.routinesTs = value }

    fun observeRoutines(): Flow<List<Routine>> = routineDao.observeAll()

    suspend fun getRoutines(): List<Routine> = routineDao.getAll()

    suspend fun addRoutine(routine: Routine) {
        val nextOrder = (routineDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val toInsert = routine.copy(sortOrder = nextOrder)
        val newId = routineDao.insert(toInsert)
        com.phonelock.app.routine.RoutineAlarmScheduler.scheduleNext(appContext, toInsert.copy(id = newId))
        pushRoutinesToFirebase()
        refreshRoutineWidget()
    }

    suspend fun updateRoutine(routine: Routine) {
        routineDao.update(routine)
        com.phonelock.app.routine.RoutineAlarmScheduler.scheduleNext(appContext, routine)
        pushRoutinesToFirebase()
        refreshRoutineWidget()
    }

    suspend fun deleteRoutine(routine: Routine) {
        routineDao.delete(routine)
        routineLogDao.deleteForRoutine(routine.id)
        com.phonelock.app.routine.RoutineAlarmScheduler.cancel(appContext, routine.id)
        pushRoutinesToFirebase()
        refreshRoutineWidget()
    }

    suspend fun moveRoutineOrder(routine: Routine, direction: Int) {
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
    suspend fun swapRoutineOrder(idA: Long, idB: Long) {
        val a = routineDao.getById(idA) ?: return
        val b = routineDao.getById(idB) ?: return
        routineDao.update(a.copy(sortOrder = b.sortOrder))
        routineDao.update(b.copy(sortOrder = a.sortOrder))
        pushRoutinesToFirebase()
        refreshRoutineWidget()
    }

    suspend fun copyRoutine(routine: Routine) {
        val nextOrder = (routineDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val toInsert = routine.copy(id = 0, title = "${routine.title} (복사본)", sortOrder = nextOrder, archived = false)
        val newId = routineDao.insert(toInsert)
        com.phonelock.app.routine.RoutineAlarmScheduler.scheduleNext(appContext, toInsert.copy(id = newId))
        pushRoutinesToFirebase()
        refreshRoutineWidget()
    }

    suspend fun getRoutineLogsForDate(dateKey: String): List<RoutineLog> = routineLogDao.getByDate(dateKey)

    suspend fun isRoutineCompleted(routineId: Long, dateKey: String): Boolean =
        routineLogDao.getByDate(dateKey).any { it.routineId == routineId }

    /** 날짜 하나에 대한 완료 체크를 토글한다(존재하면 삭제=미완료, 없으면 추가=완료). */
    suspend fun toggleRoutineLog(routineId: Long, dateKey: String) {
        if (routineLogDao.getByDate(dateKey).any { it.routineId == routineId }) {
            routineLogDao.delete(routineId, dateKey)
        } else {
            routineLogDao.insert(RoutineLog(routineId, dateKey))
        }
        pushRoutinesToFirebase()
        refreshRoutineWidget()
    }

    /** RoutineEngine.currentStreak에 넘길 완료 날짜 집합. */
    suspend fun getRoutineCompletedDateKeys(routineId: Long): Set<String> =
        routineLogDao.getByRoutine(routineId).map { it.dateKey }.toSet()

    /**
     * 루틴/로그를 Firebase JSON 배열 2개로 변환한다. 기기별 로컬(Room 자동증가) id를 그대로 실어보내면
     * 다른 기기의 id 체계와 충돌하므로(캘린더가 dateKey+배열순서로 식별하는 것과 같은 이유), routines
     * 배열 안에서의 인덱스를 로그가 참조하는 "routineIndex"로 쓴다 — 실제 id는 반입하는 쪽에서 새로 배정.
     */
    private fun routinesToJson(routines: List<Routine>, logs: List<RoutineLog>): Pair<JSONArray, JSONArray> {
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
    private fun routinesFromJson(routinesJson: JSONArray, logsJson: JSONArray): Pair<List<Routine>, List<Pair<Int, String>>> {
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
    suspend fun exportRoutinesBackupJson(): String {
        val (routinesArr, logsArr) = routinesToJson(routineDao.getAll(), routineLogDao.getAllOnce())
        val root = JSONObject()
        root.put("routines", routinesArr)
        root.put("routineLogs", logsArr)
        return root.toString(2)
    }

    /** 루틴 파일 가져오기 — 현재 루틴/로그를 파일 내용으로 전체 대체한다(syncRoutinesFromFirebase의 반입 로직과 동일). */
    suspend fun importRoutinesBackupJson(json: String) {
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
    private fun refreshRoutineWidget() {
        com.phonelock.app.widget.RoutineWidgetProvider.updateAll(appContext)
    }

    /** 변경 직후 fire-and-forget으로 Firebase에 전체 루틴 문서를 올린다. */
    private fun pushRoutinesToFirebase() {
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
    suspend fun syncRoutinesFromFirebase() {
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

    // ══════════════════════════════════════════════════════
    // "모임"(소셜 그룹, 계획 dynamic-shimmying-map.md) — SocialGroupSyncClient의 얇은 pass-through.
    // groups/{id}/... 데이터는 로컬에 캐싱/영속화하지 않고 화면 진입 시마다 Firebase에서 직접 읽는다
    // (여러 사용자가 실시간으로 공유하는 데이터라 캐싱해도 이득이 적고 구현만 복잡해짐).
    // ══════════════════════════════════════════════════════

    suspend fun createSocialGroup(name: String) =
        com.phonelock.app.service.SocialGroupSyncClient.createGroup(fbDatabaseUrl, fbApiKey, name)

    suspend fun joinSocialGroup(code: String) =
        com.phonelock.app.service.SocialGroupSyncClient.joinGroupByCode(fbDatabaseUrl, fbApiKey, code)

    suspend fun leaveSocialGroup(groupId: String) =
        com.phonelock.app.service.SocialGroupSyncClient.leaveGroup(fbDatabaseUrl, fbApiKey, groupId)

    suspend fun deleteSocialGroup(groupId: String) =
        com.phonelock.app.service.SocialGroupSyncClient.deleteGroup(fbDatabaseUrl, fbApiKey, groupId)

    suspend fun readMySocialGroupIds() =
        com.phonelock.app.service.SocialGroupSyncClient.readMyGroupIds(fbDatabaseUrl, fbApiKey)

    suspend fun readSocialGroupInfo(groupId: String) =
        com.phonelock.app.service.SocialGroupSyncClient.readGroupInfo(fbDatabaseUrl, fbApiKey, groupId)

    suspend fun readSocialGroupMembers(groupId: String) =
        com.phonelock.app.service.SocialGroupSyncClient.readGroupMembers(fbDatabaseUrl, fbApiKey, groupId)

    /** 77차: 관리자/모임장 시스템 — 관리자 목록 조회, 승격/해제(모임장만), 멤버 내쫓기, 이름/코드 수정(모임장·관리자). */
    suspend fun readSocialGroupAdmins(groupId: String) =
        com.phonelock.app.service.SocialGroupSyncClient.readGroupAdmins(fbDatabaseUrl, fbApiKey, groupId)
    suspend fun setSocialGroupAdmin(groupId: String, targetUid: String, isAdmin: Boolean) =
        com.phonelock.app.service.SocialGroupSyncClient.setGroupAdmin(fbDatabaseUrl, fbApiKey, groupId, targetUid, isAdmin)
    suspend fun kickSocialGroupMember(groupId: String, targetUid: String) =
        com.phonelock.app.service.SocialGroupSyncClient.kickMember(fbDatabaseUrl, fbApiKey, groupId, targetUid)
    suspend fun updateSocialGroupName(groupId: String, newName: String) =
        com.phonelock.app.service.SocialGroupSyncClient.updateGroupName(fbDatabaseUrl, fbApiKey, groupId, newName)
    suspend fun regenerateSocialGroupInviteCode(groupId: String) =
        com.phonelock.app.service.SocialGroupSyncClient.regenerateInviteCode(fbDatabaseUrl, fbApiKey, groupId)

    suspend fun readSocialGroupStats(groupId: String) =
        com.phonelock.app.service.SocialGroupSyncClient.readGroupStats(fbDatabaseUrl, fbApiKey, groupId)

    /**
     * 내 루틴(오늘 예정분+완료여부)/공부시간·진행률/스트릭/오늘 일정/공부중 여부/현재 작동 중인 관리 그룹을
     * 계산해 이 모임에 올린다. 설정에서 끈 항목은 SocialGroupSyncClient가 아예 필드 생략하고 쓰므로,
     * 여기선 계산만 해서 넘긴다. 공유 설정은 62차의 앱 전체 공통 토글에서 75차+에 모임별 설정
     * (`preferences.groupShareSettings(groupId)`)으로 바뀌었다.
     */
    suspend fun pushMySocialStats(groupId: String) {
        val displayName = com.phonelock.app.service.AccountSyncClient.myDisplayName(fbDatabaseUrl, fbApiKey)
        val today = LocalDate.now()
        val todayKey = today.toString()
        val routines = routineDao.getAll()
        val completed = routines.associate { it.id to getRoutineCompletedDateKeys(it.id) }
        val scheduledToday = routines.filter { com.phonelock.app.routine.RoutineEngine.isScheduledOn(it, today) }
        val routineStats = scheduledToday.map {
            com.phonelock.app.service.SocialGroupSyncClient.RoutineStat(
                it.title, todayKey in (completed[it.id] ?: emptySet()), it.icon, it.timeSlot
            )
        }
        val studySeconds = getTodayStudyLog().sumOf { it.seconds }
        val calTasksToday = calendarTaskDao.getByDate(todayCalendarDateKey())
        val studyProgress = if (calTasksToday.isNotEmpty()) {
            Math.round(calTasksToday.count { it.status == "O" } * 100.0 / calTasksToday.size).toInt()
        } else 0
        val streak = com.phonelock.app.routine.RoutineEngine.currentStreak(routines, completed, today)
        val routineBestStreak = com.phonelock.app.routine.RoutineEngine.bestStreak(routines, completed, today)
        // 오늘 하루치만 이름/상태로 보여주던 걸 76차에 실제 캘린더 미니 그리드로 바꾸면서, 이 달 전체
        // (달력 그리드가 앞뒤로 걸치는 주까지 포함해 ±7일 버퍼) 일정을 통째로 올린다 — 데스크탑
        // CalendarScreen.refresh()와 동일한 조회 범위 패턴.
        val firstOfMonth = today.withDayOfMonth(1)
        val lastOfMonth = firstOfMonth.plusMonths(1).minusDays(1)
        val monthTasks = getCalendarTasksInRange(firstOfMonth.minusDays(7).toString(), lastOfMonth.plusDays(7).toString())
        val scheduleStats = monthTasks.map {
            com.phonelock.app.service.SocialGroupSyncClient.ScheduleStat(it.dateKey, it.name, it.status, it.color, it.linkedCalc, it.progressStep)
        }
        // "일정표" 탭에 캘린더 오늘 할 일이 아니라 진짜 TimetableScreen과 같은 요일별 목표량 표를
        // 보여달라는 요청(78차) — TimetableScreen.kt와 동일 필터(이름/디데이 필수)로 draft 업무를 옮긴다.
        val calcTaskStats = getCalcTasks().filter { it.name.isNotBlank() && it.dday.isNotBlank() }.map {
            com.phonelock.app.service.SocialGroupSyncClient.CalcTaskStat(
                it.name, it.unit, it.start, it.dday, it.mon, it.tue, it.wed, it.thu, it.fri, it.sat, it.sun
            )
        }
        // 캘린더 날짜 상세에서 "그 날 얼마나 공부했는지" 보여주려고 같은 달 범위의 공부기록을 날짜별로 합산.
        val studySecondsByDate = studyLogEntryDao.getInRange(
            firstOfMonth.minusDays(7).toString(), lastOfMonth.plusDays(7).toString()
        ).groupBy { it.dateKey }.mapValues { (_, entries) -> entries.sumOf { it.seconds } }

        val localStudying = preferences.timerPhase == "study" && preferences.timerPhaseStartedAt > 0L
        val remoteStudying = runCatching {
            com.phonelock.app.service.PomodoroSyncClient.isStudyTimerActive(fbDatabaseUrl, fbApiKey)
        }.getOrDefault(false)
        val studyingNow = localStudying || remoteStudying
        val studyingTaskName = if (localStudying) preferences.timerTaskName else {
            runCatching { com.phonelock.app.service.PomodoroSyncClient.remoteTaskName(fbDatabaseUrl, fbApiKey) }.getOrDefault("")
        }

        // 76차: "지금 실제로 제한 중인" 그룹만 걸러 보여줬으나(isCurrentlyRestricting), 시간대가 안 맞아
        // 당장은 제한 중이 아닌 그룹(예: 주말에만 도는 그룹)도 사용자가 "그냥 다 보이게" 요청해
        // groupEnabled 기준으로 넓혔다(데스크탑 Repository.currentlyActiveGroupNames와 동일 패턴).
        val allGroups = groupDao.getAllOnce()
        val activeGroups = allGroups.filter { it.groupEnabled }.map {
            com.phonelock.app.service.SocialGroupSyncClient.ActiveGroupStat(
                name = it.name,
                description = it.description,
                scheduleEnabled = it.scheduleEnabled,
                scheduleStartMinute = it.scheduleStartMinute,
                scheduleEndMinute = it.scheduleEndMinute,
                scheduleDaysMask = it.scheduleDaysMask,
                dailyLimitSeconds = it.dailyLimitSeconds,
                dailyLimitApplyStartMinute = it.dailyLimitApplyStartMinute,
                dailyLimitApplyEndMinute = it.dailyLimitApplyEndMinute,
                dailyLimitDaysMask = it.dailyLimitDaysMask,
                confirmEnabled = it.confirmEnabled,
                confirmApplyStartMinute = it.confirmApplyStartMinute,
                confirmApplyEndMinute = it.confirmApplyEndMinute,
                confirmDaysMask = it.confirmDaysMask,
                processNames = memberDao.getMembers(it.id).map { m -> m.packageName },
                domains = groupSiteDao.getSites(it.id).map { s -> s.domain },
                todayUsageSeconds = getTodayUsageSeconds(it.id),
                confirmCountToday = getConfirmCountToday(it.id),
                confirmCountYesterday = getConfirmCountYesterday(it.id),
                recentAverageSeconds = getRecentAverageUsageSeconds(it.id)
            )
        }

        val share = preferences.groupShareSettings(groupId)
        val hiddenFromUids = preferences.hiddenFromUidsFor(groupId)

        com.phonelock.app.service.SocialGroupSyncClient.pushMyStats(
            fbDatabaseUrl, fbApiKey, groupId, displayName,
            share.shareRoutines, share.shareStudy, share.shareStreak,
            share.shareSchedule, share.shareStudyingNow, share.shareActiveGroup,
            routineStats, studySeconds, studyProgress, streak, routineBestStreak,
            scheduleStats, calcTaskStats, studySecondsByDate, studyingNow, studyingTaskName, activeGroups,
            hiddenFromUids
        )
    }

    /** "모임" 공유 설정/사용자별 비공개 설정 — 전부 로컬 SharedPreferences, UI는 이 창구로만 접근한다. */
    fun groupShareSettings(groupId: String) = preferences.groupShareSettings(groupId)
    fun setGroupShareSettings(groupId: String, settings: AppPreferences.GroupShareSettings) =
        preferences.setGroupShareSettings(groupId, settings)

    /** 특정 상대에게 내 정보 전체를 숨길지 — 다음 [pushMySocialStats] 때 RTDB에 반영된다. */
    fun hiddenFromUidsFor(groupId: String) = preferences.hiddenFromUidsFor(groupId)
    fun setHiddenFromUid(groupId: String, targetUid: String, hidden: Boolean) =
        preferences.setHiddenFromUid(groupId, targetUid, hidden)

    /** 특정 상대의 정보를 내 화면에서만 안 보이게 할지 — 순수 로컬 표시 설정, 서버엔 안 올라간다. */
    fun hiddenPeerUidsFor(groupId: String) = preferences.hiddenPeerUidsFor(groupId)
    fun setHiddenPeerUid(groupId: String, targetUid: String, hidden: Boolean) =
        preferences.setHiddenPeerUid(groupId, targetUid, hidden)

    /** "무작위 알림"(77차) — 이 모임에서 내 기기가 처지는 멤버를 자동으로 깨울지, 순수 로컬 설정. */
    fun randomNudgeEnabledFor(groupId: String) = preferences.randomNudgeEnabledFor(groupId)
    fun setRandomNudgeEnabled(groupId: String, enabled: Boolean) =
        preferences.setRandomNudgeEnabled(groupId, enabled)

    suspend fun sendSocialGroupNudge(groupId: String, targetUid: String) {
        val fromName = com.phonelock.app.service.AccountSyncClient.myDisplayName(fbDatabaseUrl, fbApiKey)
        com.phonelock.app.service.SocialGroupSyncClient.sendNudge(fbDatabaseUrl, fbApiKey, groupId, targetUid, fromName)
    }

    /** 내가 속한 모든 모임에서 나에게 온 새 넛지를 읽는다(마지막 확인 시각은 [AppPreferences]에 있음). */
    suspend fun readIncomingSocialGroupNudges(): List<com.phonelock.app.service.SocialGroupSyncClient.NudgeInfo> {
        val groupIds = readMySocialGroupIds()
        return com.phonelock.app.service.SocialGroupSyncClient.readIncomingNudges(
            fbDatabaseUrl, fbApiKey, groupIds, preferences.nudgeLastSeenByGroup()
        )
    }

    fun markSocialGroupNudgeSeen(groupId: String, atMillis: Long) {
        preferences.setNudgeLastSeen(groupId, atMillis)
    }

    /** 무전(강제 음성 메시지) 보내기. */
    suspend fun sendVoiceMessage(groupId: String, targetUid: String, audioBase64: String, durationMs: Long): Result<Unit> {
        val fromName = com.phonelock.app.service.AccountSyncClient.myDisplayName(fbDatabaseUrl, fbApiKey)
        return com.phonelock.app.service.SocialGroupSyncClient.sendVoiceMessage(
            fbDatabaseUrl, fbApiKey, groupId, targetUid, fromName, audioBase64, durationMs
        )
    }

    /** 무전(텍스트 메시지, 상대 기기에서 TTS로 읽어줌) 보내기. */
    suspend fun sendTextMessage(groupId: String, targetUid: String, textMessage: String): Result<Unit> {
        val fromName = com.phonelock.app.service.AccountSyncClient.myDisplayName(fbDatabaseUrl, fbApiKey)
        return com.phonelock.app.service.SocialGroupSyncClient.sendTextMessage(
            fbDatabaseUrl, fbApiKey, groupId, targetUid, fromName, textMessage
        )
    }

    /** 이 모임에서 내가 무전기를 어떻게 받을지(모임마다 다르게 설정 가능). */
    suspend fun readGroupWalkieSettings(groupId: String): com.phonelock.app.service.SocialGroupSyncClient.GroupWalkieSettings {
        return com.phonelock.app.service.SocialGroupSyncClient.readGroupWalkieSettings(fbDatabaseUrl, fbApiKey, groupId)
    }

    suspend fun writeGroupWalkieSettings(groupId: String, settings: com.phonelock.app.service.SocialGroupSyncClient.GroupWalkieSettings): Result<Unit> {
        return com.phonelock.app.service.SocialGroupSyncClient.writeGroupWalkieSettings(fbDatabaseUrl, fbApiKey, groupId, settings)
    }

    /** 내가 속한 모든 모임에서 나에게 온 무전 메시지 전부(재생/확인 후 [deleteVoiceMessage]로 지울 것). */
    suspend fun readIncomingVoiceMessages(): List<com.phonelock.app.service.SocialGroupSyncClient.VoiceMessageInfo> {
        val groupIds = readMySocialGroupIds()
        return com.phonelock.app.service.SocialGroupSyncClient.readIncomingVoiceMessages(fbDatabaseUrl, fbApiKey, groupIds)
    }

    /** 실패 시 실제 원인(상태코드/응답 본문)이 담긴 예외를 돌려준다 — 자동재생 후 삭제처럼 "지워진 게
     *  확인돼야 재생해도 된다"는 호출부도 `result.isSuccess`로 판단할 수 있다. */
    suspend fun deleteVoiceMessage(groupId: String, msgId: String): Result<Unit> {
        return com.phonelock.app.service.SocialGroupSyncClient.deleteVoiceMessage(fbDatabaseUrl, fbApiKey, groupId, msgId)
    }

    suspend fun markVoiceMessageListened(groupId: String, msg: com.phonelock.app.service.SocialGroupSyncClient.VoiceMessageInfo) {
        com.phonelock.app.service.SocialGroupSyncClient.markVoiceMessageListened(fbDatabaseUrl, fbApiKey, groupId, msg)
    }
}
