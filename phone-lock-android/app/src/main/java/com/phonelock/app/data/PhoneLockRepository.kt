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
internal fun effectiveDate(resetHour: Int, now: LocalDateTime = LocalDateTime.now()): LocalDate =
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
    internal val appContext = context.applicationContext
    internal val db = AppDatabase.getInstance(context)
    private val groupDao = db.appGroupDao()
    private val memberDao = db.groupMemberDao()
    internal val usageDao = db.usageRecordDao()
    private val groupSiteDao = db.groupSiteDao()
    private val confirmEscalationDao = db.confirmEscalationDao()
    internal val confirmCounterDao = db.confirmCounterDao()
    internal val studyLogEntryDao = db.studyLogEntryDao()
    internal val calendarTaskDao = db.calendarTaskDao()
    internal val calcTaskDao = db.calcTaskDao()
    internal val calcSavedItemDao = db.calcSavedItemDao()
    internal val routineDao = db.routineDao()
    internal val routineLogDao = db.routineLogDao()
    private val quoteOutcomeDao = db.quoteOutcomeDao()
    internal val preferences = AppPreferences(context)

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
    internal val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            preferences.updateAvailableReleaseNotes = latest.releaseNotes
        } else if (result.isSuccess) {
            // 확인엔 성공했고 정말 최신 버전일 때만 지워야 한다 — 확인 자체가 실패했으면(네트워크/요청
            // 한도 등) 이전에 남아있던 "업데이트 있음" 상태를 함부로 지우지 않는다.
            preferences.updateAvailableVersionCode = 0L
            preferences.updateAvailableApkUrl = null
        }
    }

    /**
     * 자동 백업/정리(82차, §9) — 하루 1회(dailyResetHour 기준 "오늘"이 바뀔 때) 실행되는 유지보수 묶음.
     * `checkForUpdateIfNeeded`/`applyDailyGroupResetIfNeeded`와 동일한 lastXxxDate 가드 패턴.
     * 1) 12개월 이상 지난 통계 자동 정리(이미 있는 [pruneOldStats] 재사용, 월 1회만).
     * 2) 클라우드 자동 백업 켜져 있으면 [exportBackupJson] 결과를 Firebase Storage에 업로드.
     * 두 작업 모두 실패해도 예외를 던지지 않는다(호출부가 화면 진입 경로라 여기서 죽으면 안 됨).
     */
    suspend fun runDailyMaintenanceIfNeeded() {
        val today = effectiveDate(dailyResetHour).toString()

        val lastPrune = preferences.lastAutoStatsPruneDate
        val prevPruneRunLongAgo = lastPrune.isBlank() ||
            runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(lastPrune), LocalDate.parse(today)) >= 30 }.getOrDefault(true)
        if (prevPruneRunLongAgo) {
            runCatching { pruneOldStats(12) }
            preferences.lastAutoStatsPruneDate = today
        }

        if (preferences.cloudBackupEnabled && preferences.lastCloudBackupDate != today) {
            val result = runCatching {
                val json = exportBackupJson()
                com.phonelock.app.service.CloudBackupClient.uploadBackup(fbDatabaseUrl, json).getOrThrow()
            }
            preferences.lastCloudBackupDate = today
            preferences.lastCloudBackupResult = if (result.isSuccess) "성공 (${result.getOrNull()})" else "실패: ${result.exceptionOrNull()?.message}"
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
            preferences.updateAvailableReleaseNotes = latest.releaseNotes
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

    /**
     * 회유 멘트 성공률 통계(82차, §9/§11) — 재확인/차단 화면에서 어떤 문구·단계에서 "진행"(굴복)/"중단"(저항)을
     * 골랐는지 순수 로깅. 판정 로직과 무관, Activity의 onPrimary/onSecondary에서 fire-and-forget으로 호출.
     */
    fun recordQuoteOutcomeFireAndForget(tier: Int, quoteText: String, proceeded: Boolean) {
        ioScope.launch {
            quoteOutcomeDao.insert(
                QuoteOutcome(tier = tier, quoteText = quoteText, choice = if (proceeded) "PROCEED" else "STOP", timestampMillis = System.currentTimeMillis())
            )
        }
    }

    /** 통계 화면용 — 전체 회유 멘트 선택 기록. */
    suspend fun getAllQuoteOutcomesOnce(): List<QuoteOutcome> = quoteOutcomeDao.getAllOnce()

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

    /** 통계 탭 태그별 집계용(82차, §9) — 전체 공부 기록. */
    suspend fun getAllStudyLogOnce(): List<StudyLogEntry> = studyLogEntryDao.getAllOnce()

    private fun addStudyLogEntry(taskName: String, seconds: Int, startedAt: Long, note: String = "", tag: String = "") {
        if (seconds <= 0) return
        val today = effectiveDate(preferences.dailyResetHour).toString()
        ioScope.launch {
            studyLogEntryDao.insert(StudyLogEntry(dateKey = today, taskName = taskName.ifBlank { "이름 없는 공부" }, seconds = seconds, startedAt = startedAt, note = note, tag = tag))
            pushStudyLogToFirebase(today)
        }
    }

    /** 이 기기가 그날 기록한 전체를 fire-and-forget으로 Firebase에 덮어쓴다. */
    private suspend fun pushStudyLogToFirebase(dateKey: String) {
        val entries = studyLogEntryDao.getByDate(dateKey)
        val json = JSONArray()
        entries.forEach { e ->
            json.put(JSONObject().apply {
                put("taskName", e.taskName); put("seconds", e.seconds); put("startedAt", e.startedAt); put("note", e.note); put("tag", e.tag)
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
                others.add(StudyLogEntry(dateKey = dateKey, taskName = obj.optString("taskName", ""), seconds = obj.optInt("seconds", 0), startedAt = obj.optLong("startedAt", 0L), note = obj.optString("note", ""), tag = obj.optString("tag", "")))
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

    /** 타이머를 정지하고, 진행 중이던 공부 페이즈의 경과시간을 기록에 적립한다. note는 사용자가 남긴 짧은 회고(선택),
     *  tag는 과목 등 자유 입력 태그(82차, §9 "포모도로 세션 태그", 선택). */
    fun timerStop(note: String = "", tag: String = "") {
        val run = getTimerRun() ?: return
        if (run.phase == "study") {
            val elapsed = ((System.currentTimeMillis() - run.phaseStartedAt) / 1000L).toInt()
            addStudyLogEntry(run.taskName, elapsed, run.phaseStartedAt, note, tag)
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

        // 82차(§9 "전체 데이터 내보내기"): 원래는 그룹/멤버/사이트만 담던 백업에 캘린더/계산기/루틴/
        // 공부기록까지 포함시킨다. 각 테이블은 이미 있는 Firebase 동기화용 직렬화 함수를 그대로 재사용 —
        // 새 포맷을 만들지 않고 기존 스키마와 통일한다.
        root.put("calendar", calendarTasksToJson(calendarTaskDao.getAllOnce()))
        root.put("calcTasks", JSONArray().also { arr -> calcTaskDao.getAll().forEach { arr.put(calcTaskToJson(it)) } })
        root.put("calcSaved", JSONArray().also { arr -> calcSavedItemDao.getAll().forEach { arr.put(calcSavedToJson(it)) } })
        val (routinesJson, routineLogsJson) = routinesToJson(routineDao.getAll(), routineLogDao.getAllOnce())
        root.put("routines", routinesJson)
        root.put("routineLogs", routineLogsJson)
        root.put("studyLog", JSONArray().also { arr ->
            studyLogEntryDao.getAllOnce().forEach { e ->
                arr.put(JSONObject().apply {
                    put("dateKey", e.dateKey); put("taskName", e.taskName); put("seconds", e.seconds)
                    put("startedAt", e.startedAt); put("note", e.note); put("tag", e.tag)
                })
            }
        })
        root.put("exportedAt", System.currentTimeMillis())

        return root.toString(2)
    }

    suspend fun importBackupJson(json: String) {
        // 파싱을 먼저 전부 끝내서(실패하면 여기서 예외가 던져짐), 잘못된 백업 파일을 선택했을 때
        // 삭제가 아예 실행되지 않도록 보장한다. 그 다음 삭제+재삽입 전체를 하나의 DB 트랜잭션으로
        // 묶어서, 도중에(그룹별 파싱 등) 예외가 나더라도 이미 지운 데이터가 롤백되게 한다.
        val root = JSONObject(json)
        val groupsJson = root.optJSONArray("groups") ?: JSONArray()

        // 82차(§9 "전체 데이터 내보내기"): 캘린더/계산기/루틴/공부기록은 옛 백업 파일엔 없을 수 있으므로
        // 키가 있을 때만 되돌린다(구버전 백업과 하위 호환).
        val hasCalendar = root.has("calendar")
        val hasCalc = root.has("calcTasks") || root.has("calcSaved")
        val hasRoutines = root.has("routines")
        val hasStudyLog = root.has("studyLog")
        val calendarTasks = if (hasCalendar) calendarTasksFromJson(root.getJSONObject("calendar")) else emptyList()
        val calcTasksList = if (root.has("calcTasks")) {
            val arr = root.getJSONArray("calcTasks")
            (0 until arr.length()).map { i -> calcTaskFromJson(arr.getJSONObject(i), i) }
        } else emptyList()
        val calcSavedList = if (root.has("calcSaved")) {
            val arr = root.getJSONArray("calcSaved")
            (0 until arr.length()).map { i -> calcSavedFromJson(arr.getJSONObject(i), i) }
        } else emptyList()
        val (newRoutines, logRefs) = if (hasRoutines) {
            routinesFromJson(root.getJSONArray("routines"), root.optJSONArray("routineLogs") ?: JSONArray())
        } else emptyList<Routine>() to emptyList()
        val studyLogEntries = if (hasStudyLog) {
            val arr = root.getJSONArray("studyLog")
            (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                StudyLogEntry(
                    dateKey = e.optString("dateKey", ""), taskName = e.optString("taskName", ""),
                    seconds = e.optInt("seconds", 0), startedAt = e.optLong("startedAt", 0L), note = e.optString("note", ""),
                    tag = e.optString("tag", "")
                )
            }
        } else emptyList()

        val oldRoutines = if (hasRoutines) routineDao.getAll() else emptyList()

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

            if (hasCalendar) {
                calendarTaskDao.deleteAll()
                calendarTasks.forEach { calendarTaskDao.insert(it) }
            }
            if (hasCalc) {
                calcTaskDao.deleteAll()
                calcTasksList.forEach { calcTaskDao.insert(it) }
                calcSavedItemDao.deleteAll()
                calcSavedList.forEach { calcSavedItemDao.insert(it) }
            }
            if (hasRoutines) {
                routineLogDao.deleteAll()
                routineDao.deleteAll()
                val newIds = newRoutines.map { routineDao.insert(it) }
                logRefs.forEach { (idx, dateKey) -> routineLogDao.insert(RoutineLog(newIds[idx], dateKey)) }
            }
            if (hasStudyLog) {
                studyLogEntryDao.deleteAll()
                studyLogEntries.forEach { studyLogEntryDao.insert(it) }
            }
        }

        if (hasRoutines) {
            oldRoutines.forEach { com.phonelock.app.routine.RoutineAlarmScheduler.cancel(appContext, it.id) }
            refreshRoutineWidget()
            com.phonelock.app.routine.RoutineAlarmScheduler.rescheduleAll(appContext, this)
        }
        if (hasCalendar) pushCalendarToFirebase()
        if (hasCalc) pushCalcTasksAndSaved()
        if (hasRoutines) pushRoutinesToFirebase()
    }

    // "모임"(소셜 그룹)/캘린더/계산기/루틴 관련 함수는 각각 PhoneLockRepository.Social.kt/
    // PhoneLockRepository.Calendar.kt/PhoneLockRepository.Calc.kt/PhoneLockRepository.Routine.kt로
    // 분리됨(82차, DECISIONS.md 참고). 이 파일엔 그룹/멤버/사이트/실행확인/공부타이머/전체 백업처럼
    // 여러 섹션에 걸친(cross-cutting) 함수만 남아있다.
}
