package com.phonelock.desktop.data

import com.phonelock.desktop.calc.CalcEngine
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** Firebase 일일 사용시간 동기화(`dailyUsage/{date}/{그룹}/{device}`)에서 이 기기를 가리키는 키. */
private const val DAILY_USAGE_DEVICE = "desktop"

/** 스누즈(#1)를 하루에 그룹당 최대 몇 번까지 쓸 수 있는지 — 회유 절차 없이 바로 임시 해제되는 예외라 무제한이면 사실상 실행확인을 무력화하게 된다. */
private const val SNOOZE_DAILY_LIMIT = 3

/** 일일 사용 한도의 "오늘" 날짜를 계산한다. resetHour 이전이면 아직 전날로 취급한다. */
private fun effectiveDate(resetHour: Int, now: LocalDateTime = LocalDateTime.now()): LocalDate =
    if (now.hour < resetHour) now.toLocalDate().minusDays(1) else now.toLocalDate()

class Repository {
    private val lock = Any()
    private var data: AppData = JsonStore.load()

    // 겹치는 그룹 중 "지금 실제로 제한 중인" 그룹을 우선하는 데 쓴다. LockEvaluator는 이 repository의
    // 공개 메서드만 호출하므로 지연 초기화로 만들어도 순환 문제가 없다.
    private val evaluator by lazy { com.phonelock.desktop.monitor.LockEvaluator(this) }

    init {
        // 트레이 메뉴의 "종료"가 아니라 taskkill(강제 종료 없이)/Ctrl+C 등으로 종료되는 경우를 대비한
        // 안전망. addUsageSeconds()가 최대 usagePersistIntervalMs만큼 저장을 미루기 때문에 필요하다.
        Runtime.getRuntime().addShutdownHook(Thread { flushPendingUsage() })
        // 하루 한 번 다세대 백업 회전(보고서 #19) — 앱 시작마다 호출되지만 내부에서 오늘 이미 만들었으면 스킵한다.
        JsonStore.rotateDailyBackupIfNeeded()
    }

    /** 설정 화면의 "백업에서 복원" 목록용 — 최신 날짜순. */
    fun listBackups(): List<java.io.File> = JsonStore.listBackups()

    /** 선택한 백업 파일로 현재 데이터를 완전히 대체한다(되돌리기 없음). 파싱 실패 시 false. */
    fun restoreFromBackup(file: java.io.File): Boolean = synchronized(lock) {
        val restored = JsonStore.parseBackupFile(file) ?: return@synchronized false
        data = restored
        persist()
        true
    }

    /** 설정/그룹 내보내기(#6) — 기기 교체·재설치 시 사용자가 고른 위치에 현재 데이터 전체를 저장한다. 가져오기는 restoreFromBackup을 그대로 재사용(임의 파일도 이미 지원). */
    fun exportDataToFile(file: java.io.File) = synchronized(lock) {
        JsonStore.exportToFile(data, file)
    }

    private fun persist() {
        JsonStore.save(data)
    }

    fun getGroups(): List<Group> = synchronized(lock) { data.groups.toList() }

    fun getGroup(id: Long): Group? = synchronized(lock) { data.groups.find { it.id == id } }

    fun getEnabledGroups(): List<Group> = synchronized(lock) { data.groups.filter { it.enabled } }

    fun hasSiteGroups(): Boolean = synchronized(lock) { data.groups.any { it.domains.isNotEmpty() } }

    private val appStartAtMillis = System.currentTimeMillis()

    @Volatile
    private var lastExtensionHeartbeatAtMillis = 0L

    /** 브라우저 확장프로그램이 살아있다는 신호(heartbeat)를 보낼 때마다 호출된다. */
    fun recordExtensionHeartbeat() {
        lastExtensionHeartbeatAtMillis = System.currentTimeMillis()
    }

    /**
     * 확장프로그램의 heartbeat가 오래 끊겼는지 판단한다. 앱을 막 시작했을 땐 브라우저가 아직 안
     * 열려있을 수 있으므로 앱 시작 시각 기준으로 유예시간을 준다.
     */
    fun isExtensionHeartbeatStale(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val graceMs = 3 * 60_000L
        val lastAt = lastExtensionHeartbeatAtMillis
        return if (lastAt == 0L) nowMillis - appStartAtMillis > graceMs else nowMillis - lastAt > graceMs
    }

    fun createGroup(group: Group): Long = synchronized(lock) {
        val id = data.nextGroupId
        data.nextGroupId += 1
        data.groups.add(group.copy(id = id))
        persist()
        id
    }

    fun updateGroup(group: Group) = synchronized(lock) {
        val index = data.groups.indexOfFirst { it.id == group.id }
        if (index >= 0) {
            data.groups[index] = group
            persist()
        }
    }

    /** 설정한 초기화 시간(dailyResetHour)이 지나면, 사용자가 꺼둔 그룹(groupEnabled=false)도 자동으로
     *  다시 켠다 — 사용자 요청: "초기화 시간이 지나면 그룹들이 꺼져 있더라도 다시 켜지게". 하루에 한 번만
     *  적용되도록 lastGroupAutoResetDate로 날짜가 바뀌었는지 확인한다(그렇지 않으면 사용자가 오늘 중에
     *  다시 끈 그룹도 다음 tick마다 계속 강제로 켜지게 된다). 회유 절차가 진행 중인(groupOffPending) 그룹은
     *  아직 groupEnabled가 true라 대상이 아니다. */
    fun applyDailyGroupResetIfNeeded() = synchronized(lock) {
        val today = effectiveDate(data.dailyResetHour).toString()
        if (data.lastGroupAutoResetDate == today) return@synchronized
        data.lastGroupAutoResetDate = today
        data.groups.forEachIndexed { index, group ->
            if (!group.groupEnabled) {
                data.groups[index] = group.copy(groupEnabled = true, groupOffPending = false, groupOffMessageIndex = 0)
            }
        }
        persist()
    }

    /**
     * GitHub Releases(공부앱과 같은 저장소)에 새 데스크탑 설치파일이 올라왔는지 하루 1회(dailyResetHour
     * 기준 "오늘"이 바뀔 때) 확인한다 — applyDailyGroupResetIfNeeded와 동일한 lastXxxDate 가드 패턴.
     * 실제 GitHub API 호출(최대 수초)은 pushUsageToFirebase 등과 같은 이유로 락 밖 백그라운드 스레드에서
     * 수행한다 — 락 안에서 하면 그동안 다른 그룹 감시 전체가 멈춘다.
     */
    fun checkForUpdateIfNeeded() {
        val shouldCheck = synchronized(lock) {
            val today = effectiveDate(data.dailyResetHour).toString()
            if (data.lastUpdateCheckDate == today) return@synchronized false
            data.lastUpdateCheckDate = today
            persist()
            true
        }
        if (!shouldCheck) return
        Thread {
            val result = com.phonelock.desktop.monitor.DesktopUpdateChecker.checkLatestDesktopRelease()
            val latest = result.getOrNull()
            synchronized(lock) {
                if (latest != null && latest.buildTimestamp > com.phonelock.desktop.BuildInfo.BUILD_TIMESTAMP) {
                    data.updateAvailableBuildTimestamp = latest.buildTimestamp
                    data.updateAvailableInstallerUrl = latest.installerUrl
                    persist()
                } else if (result.isSuccess) {
                    // 확인 자체가 실패했으면(네트워크/요청 한도 등) 이전에 남아있던 "업데이트 있음" 상태를
                    // 함부로 지우지 않는다 — 성공했고 정말 최신 버전일 때만 지운다.
                    data.updateAvailableBuildTimestamp = 0L
                    data.updateAvailableInstallerUrl = null
                    persist()
                }
            }
        }.start()
    }

    /** 지금 실행 중인 빌드보다 새 릴리스가 있으면 그 설치파일 다운로드 URL, 없으면 null. 네트워크 호출
     *  없이 [checkForUpdateIfNeeded]가 남겨둔 값만 읽으므로 UI 폴링 루프에서 바로 써도 안전하다. */
    fun pendingUpdateInstallerUrl(): String? = synchronized(lock) {
        if (data.updateAvailableBuildTimestamp > com.phonelock.desktop.BuildInfo.BUILD_TIMESTAMP) data.updateAvailableInstallerUrl else null
    }

    /** 지금 실행 중인 빌드의 식별자 — 설정 화면에 표시용. */
    fun currentBuildTimestamp(): Long = com.phonelock.desktop.BuildInfo.BUILD_TIMESTAMP

    /**
     * 설정 화면 "지금 확인" 버튼 전용 — [checkForUpdateIfNeeded]의 하루 1회 가드를 무시하고 즉시
     * GitHub Releases를 확인한다. 네트워크 호출은 마찬가지로 락 밖에서 수행하고, 끝나면 [onResult]로
     * 결과를 콜백한다(UI 스레드 전환은 호출부 책임). 2026-08-30 발견: 예전엔 확인 실패와 "정말 최신
     * 버전"을 구분 못 해서 실패해도 "최신 버전"으로 잘못 표시됐다 — 이제 세 결과를 명확히 나눈다.
     */
    sealed class UpdateCheckOutcome {
        data class Available(val installerUrl: String) : UpdateCheckOutcome()
        object UpToDate : UpdateCheckOutcome()
        data class Failed(val reason: String) : UpdateCheckOutcome()
    }

    fun checkForUpdateNow(onResult: (UpdateCheckOutcome) -> Unit) {
        Thread {
            val result = com.phonelock.desktop.monitor.DesktopUpdateChecker.checkLatestDesktopRelease()
            val latest = result.getOrNull()
            val outcome = synchronized(lock) {
                data.lastUpdateCheckDate = effectiveDate(data.dailyResetHour).toString()
                if (latest != null && latest.buildTimestamp > com.phonelock.desktop.BuildInfo.BUILD_TIMESTAMP) {
                    data.updateAvailableBuildTimestamp = latest.buildTimestamp
                    data.updateAvailableInstallerUrl = latest.installerUrl
                    persist()
                    UpdateCheckOutcome.Available(latest.installerUrl)
                } else if (result.isSuccess) {
                    data.updateAvailableBuildTimestamp = 0L
                    data.updateAvailableInstallerUrl = null
                    persist()
                    UpdateCheckOutcome.UpToDate
                } else {
                    UpdateCheckOutcome.Failed(result.exceptionOrNull()?.message ?: "알 수 없는 오류")
                }
            }
            onResult(outcome)
        }.start()
    }

    /** 스누즈(#1) — 회유 절차 없이 group.snoozeMinutes만큼 즉시 임시 해제한다. 하루 3회 초과면 false.
     *  다른 기기와 합산한 오늘 사용 횟수([mergedSnooze])를 기준으로 한도를 판정해서, 데스크탑/안드로이드
     *  양쪽에서 나눠 눌러도 총 3회를 넘지 못하게 한다. */
    fun snoozeGroup(id: Long): Boolean = synchronized(lock) {
        val index = data.groups.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        val today = effectiveDate(data.dailyResetHour).toString()
        val merged = mergedSnooze(data.groups[index])
        val usedToday = if (merged.usedDate == today) merged.usedCount else 0
        if (usedToday >= SNOOZE_DAILY_LIMIT) return@synchronized false
        val group = data.groups[index]
        val updated = SnoozeState(
            untilEpochMillis = System.currentTimeMillis() + group.snoozeMinutes * 60_000L,
            usedDate = today,
            usedCount = usedToday + 1
        )
        data.groups[index] = group.copy(
            snoozedUntilEpochMillis = updated.untilEpochMillis,
            snoozeUsedDate = updated.usedDate,
            snoozeUsedCount = updated.usedCount
        )
        snoozeCache[id] = CachedSnooze(updated, System.currentTimeMillis())
        persist()
        writeSyncedSnooze(group.name, updated)
        true
    }

    /** 오늘 이 그룹에 남은 스누즈 횟수(0~3) — UI에 "오늘 n/3" 표시용. 그룹 목록 화면이 매 리컴포지션마다
     *  호출하므로 네트워크 호출 없이 로컬 값만 본다(다른 기기의 스누즈는 [mergedSnooze]가 백그라운드
     *  판정 시점에 이미 로컬로 병합해둔 값을 통해 뒤늦게 반영된다). */
    fun snoozeRemainingToday(group: Group): Int = synchronized(lock) {
        val today = effectiveDate(data.dailyResetHour).toString()
        val usedToday = if (group.snoozeUsedDate == today) group.snoozeUsedCount else 0
        (SNOOZE_DAILY_LIMIT - usedToday).coerceAtLeast(0)
    }

    /** 잠김(스케줄/일일한도) 화면 조롱 문구 강도용 — 오늘 이 그룹을 열려고 시도한 횟수를 1 늘리고
     *  늘린 뒤의 값을 반환한다(dailyResetHour 기준 날짜가 바뀌면 1부터 다시 센다). */
    fun recordBlockAttempt(groupId: Long): Int = synchronized(lock) {
        val index = data.groups.indexOfFirst { it.id == groupId }
        if (index < 0) return@synchronized 0
        val today = effectiveDate(data.dailyResetHour).toString()
        val group = data.groups[index]
        val count = if (group.blockAttemptDate == today) group.blockAttemptCount + 1 else 1
        data.groups[index] = group.copy(blockAttemptDate = today, blockAttemptCount = count)
        persist()
        count
    }

    data class SnoozeState(val untilEpochMillis: Long, val usedDate: String, val usedCount: Int)

    /** Firebase에서 이 그룹(이름 매칭)의 스누즈 상태를 읽는다. 설정이 비어있거나 오류가 나면 null. */
    private fun readSyncedSnooze(groupName: String): SnoozeState? {
        val entry = com.phonelock.desktop.monitor.PomodoroSyncClient
            .readSnoozeSync(data.fbDatabaseUrl, data.fbApiKey, groupName) ?: return null
        return SnoozeState(entry.untilEpochMillis, entry.usedDate, entry.usedCount)
    }

    /** Firebase에 이 그룹(이름 매칭)의 스누즈 상태를 갱신한다. */
    private fun writeSyncedSnooze(groupName: String, state: SnoozeState) {
        com.phonelock.desktop.monitor.PomodoroSyncClient.writeSnoozeSync(
            data.fbDatabaseUrl, data.fbApiKey,
            groupName, state.untilEpochMillis, state.usedDate, state.usedCount
        )
    }

    private data class CachedSnooze(val value: SnoozeState, val fetchedAtMillis: Long)
    private val snoozeCache = mutableMapOf<Long, CachedSnooze>()
    private val snoozeCacheTtlMs = 10_000L

    /**
     * 스누즈(#1) 크로스디바이스 동기화 — mergedEscalation과 같은 "최신값(untilEpochMillis) 승리" 규칙.
     * 다른 기기가 더 최근에(더 미래 시각으로) 스누즈했다면 그 상태(종료 시각 + 오늘 사용 횟수)를 그대로
     * 채택해 로컬에도 병합·저장한다. 그룹은 이름으로 매칭한다(기기마다 그룹ID가 다름). LockEvaluator의
     * 판정 경로(백그라운드 tick)에서만 호출할 것 — Compose UI 스레드에서 직접 호출하면 안 된다(네트워크
     * I/O 포함).
     */
    private fun mergedSnooze(group: Group): SnoozeState {
        val now = System.currentTimeMillis()
        val cached = snoozeCache[group.id]
        if (cached != null && now - cached.fetchedAtMillis < snoozeCacheTtlMs) return cached.value
        val local = SnoozeState(group.snoozedUntilEpochMillis ?: 0L, group.snoozeUsedDate, group.snoozeUsedCount)
        val synced = readSyncedSnooze(group.name)
        val winner = if (synced != null && synced.untilEpochMillis > local.untilEpochMillis) synced else local
        if (winner != local) {
            val index = data.groups.indexOfFirst { it.id == group.id }
            if (index >= 0) {
                data.groups[index] = data.groups[index].copy(
                    snoozedUntilEpochMillis = winner.untilEpochMillis,
                    snoozeUsedDate = winner.usedDate,
                    snoozeUsedCount = winner.usedCount
                )
                persist()
            }
        }
        snoozeCache[group.id] = CachedSnooze(winner, now)
        return winner
    }

    /** 다른 기기의 스누즈까지 반영한 종료 시각(epoch millis, 스누즈 아니면 0) — LockEvaluator가 판정에 쓴다. */
    fun syncedSnoozeUntil(group: Group): Long = synchronized(lock) { mergedSnooze(group).untilEpochMillis }

    fun deleteGroup(id: Long) = synchronized(lock) {
        data.groups.removeAll { it.id == id }
        data.usageRecords.removeAll { it.groupId == id }
        data.confirmEscalations.removeAll { it.groupId == id }
        data.confirmCounters.removeAll { it.groupId == id }
        persist()
    }

    /** 그룹을 복제해서 새 그룹으로 추가한다(멤버/사이트 포함, 스누즈/기간강화/차단시도 등 진행 중 상태는 초기화). */
    fun copyGroup(id: Long): Long? {
        val original = getGroup(id) ?: return null
        val copy = original.copy(
            name = "${original.name} (복사본)",
            groupOffPending = false,
            groupOffMessageIndex = 0,
            snoozedUntilEpochMillis = null,
            snoozeUsedDate = "",
            snoozeUsedCount = 0,
            blockAttemptDate = "",
            blockAttemptCount = 0
        )
        return createGroup(copy)
    }

    /**
     * 같은 앱/사이트가 여러 그룹에 동시에 속할 수 있고, 이 경우 겹치는 모든 그룹의 제한이 함께 적용된다
     * (그중 하나라도 잠금 조건이면 잠기고, 확인이 필요한 그룹이 있으면 그 그룹에 대해 각각 확인받아야
     * 한다). 그룹 자체가 켜져 있는 그룹만 후보로 돌려준다(관리 종류별 개별 on/off와 요일은
     * evaluate()/isConfirmActiveNow() 등에서 각각 확인한다).
     */
    fun findGroupsForProcess(processName: String): List<Group> = synchronized(lock) {
        data.groups.filter { group ->
            group.processNames.any { it.equals(processName, ignoreCase = true) } && evaluator.isGroupActive(group)
        }
    }

    fun findGroupsForDomain(hostname: String): List<Group> = synchronized(lock) {
        val host = hostname.lowercase()
        data.groups.filter { group ->
            group.domains.any { domain ->
                val d = domain.lowercase()
                host == d || host.endsWith(".$d")
            } && evaluator.isGroupActive(group)
        }
    }

    /**
     * 로컬(이 기기)에서 오늘 쓴 시간 + 모바일이 Firebase에 올려둔 오늘 사용시간을 더한 값. 일일 한도는
     * 기기별이 아니라 그룹 전체 기준이므로, 같은 그룹을 모바일에서도 쓰고 있다면 합산해서 판정한다.
     */
    fun getTodayUsageSeconds(groupId: Long): Int = synchronized(lock) {
        val dateStr = effectiveDate(data.dailyResetHour).toString()
        val local = data.usageRecords.find { it.groupId == groupId && it.date == dateStr }?.usedSeconds ?: 0
        val group = data.groups.find { it.id == groupId } ?: return@synchronized local
        local + peerUsageSeconds(group, dateStr)
    }

    /**
     * 최근 [days]일(오늘 제외) 평균 사용시간(초) — 이 기기 로컬 기록 기준(전문가 종합분석 보고서 #34,
     * 이상 사용 패턴 감지용 순수 통계, 판정 로직과 무관). 기록이 없으면 0.
     */
    fun getRecentAverageUsageSeconds(groupId: Long, days: Int = 7): Int = synchronized(lock) {
        val today = effectiveDate(data.dailyResetHour)
        val cutoff = today.minusDays(days.toLong()).toString()
        val todayStr = today.toString()
        val recent = data.usageRecords.filter { it.groupId == groupId && it.date >= cutoff && it.date < todayStr }
        if (recent.isEmpty()) return@synchronized 0
        recent.sumOf { it.usedSeconds } / recent.size
    }

    private data class CachedPeerUsage(val peerSeconds: Int, val fetchedAtMillis: Long)
    private val peerUsageCache = mutableMapOf<Long, CachedPeerUsage>()
    private val peerUsageCacheTtlMs = 10_000L
    private val peerUsageRefreshInFlight = mutableSetOf<Long>()

    /**
     * 이 그룹의 오늘 사용시간 중 "이 기기가 아닌 다른 기기(모바일)"가 Firebase에 올려둔 몫만 합산한다.
     * 이 함수는 이미 락을 쥔 상태(getTodayUsageSeconds)에서 호출되므로, 캐시가 오래됐어도 여기서
     * 동기 네트워크 호출로 기다리지 않는다 — 대신 마지막으로 알려진 값(캐시 없으면 0)을 즉시 돌려주고
     * 갱신은 락 밖 백그라운드 스레드에 맡긴다(락을 쥔 채 최대 수초 블로킹되던 문제 수정).
     */
    private fun peerUsageSeconds(group: Group, dateStr: String): Int {
        val now = System.currentTimeMillis()
        val cached = peerUsageCache[group.id]
        if ((cached == null || now - cached.fetchedAtMillis >= peerUsageCacheTtlMs) && peerUsageRefreshInFlight.add(group.id)) {
            val url = data.fbDatabaseUrl; val key = data.fbApiKey
            val groupId = group.id; val groupName = group.name
            Thread {
                val map = com.phonelock.desktop.monitor.PomodoroSyncClient
                    .readDailyUsage(url, key, dateStr, groupName) ?: emptyMap()
                val peerSeconds = map.filterKeys { it != DAILY_USAGE_DEVICE }.values.sum()
                synchronized(lock) {
                    peerUsageCache[groupId] = CachedPeerUsage(peerSeconds, System.currentTimeMillis())
                    peerUsageRefreshInFlight.remove(groupId)
                }
            }.start()
        }
        return cached?.peerSeconds ?: 0
    }

    private var lastUsagePersistAtMillis = 0L
    private var usageDirty = false
    private val usagePersistIntervalMs = 30_000L

    /**
     * 포그라운드 감시(2초 tick)마다 호출되므로, 매번 전체 JSON을 디스크에 다시 쓰면 잦은 I/O로
     * 디스크 마모/손상 위험이 커진다. 메모리에는 즉시 반영하되, 실제 저장은 usagePersistIntervalMs
     * 간격으로만 하고 나머지는 usageDirty로 표시해뒀다가 flushPendingUsage()(앱 종료 시)나 다음 주기에
     * 저장한다. 비정상 종료 시 최대 usagePersistIntervalMs만큼의 사용시간 기록이 유실될 수 있지만,
     * 매 tick마다 저장하던 것보다 훨씬 안전하다. Firebase로의 사용시간 동기화도 같은 주기로 묶어서
     * 보낸다(매 tick마다 네트워크 호출하지 않도록).
     */
    fun addUsageSeconds(groupId: Long, seconds: Int) = synchronized(lock) {
        val dateStr = effectiveDate(data.dailyResetHour).toString()
        val index = data.usageRecords.indexOfFirst { it.groupId == groupId && it.date == dateStr }
        val updated = if (index >= 0) {
            val current = data.usageRecords[index]
            val next = current.copy(usedSeconds = current.usedSeconds + seconds)
            data.usageRecords[index] = next
            next
        } else {
            val next = UsageRecord(groupId, dateStr, seconds)
            data.usageRecords.add(next)
            next
        }
        usageDirty = true
        val now = System.currentTimeMillis()
        if (now - lastUsagePersistAtMillis >= usagePersistIntervalMs) {
            persist()
            lastUsagePersistAtMillis = now
            usageDirty = false
            pushUsageToFirebase(groupId, dateStr, updated.usedSeconds)
        }
    }

    /**
     * tick 루프(EnforcementService의 tickMutex + 이 Repository의 lock을 모두 쥔 채)에서 호출되므로,
     * 다른 push 함수들(pushStudyLogToFirebase/pushCalendarToFirebase 등)과 동일하게 실제 네트워크 I/O는
     * 락 밖 백그라운드 스레드로 넘긴다 — 그렇지 않으면 최대 수초의 HTTP 호출 동안 다른 그룹 감시와
     * Repository 전체 호출이 함께 멈춘다(37차 이후 발견, 확인 대기는 뮤텍스 밖 원칙과 같은 유형의 문제).
     */
    private fun pushUsageToFirebase(groupId: Long, dateStr: String, usedSeconds: Int) {
        val group = data.groups.find { it.id == groupId } ?: return
        val url = data.fbDatabaseUrl; val key = data.fbApiKey
        val groupName = group.name
        Thread {
            com.phonelock.desktop.monitor.PomodoroSyncClient.writeDailyUsage(
                url, key, dateStr, groupName, DAILY_USAGE_DEVICE, usedSeconds
            )
        }.start()
    }

    /** flushPendingUsage 전용 — 셧다운훅에서만 호출되므로(다른 그룹 감시를 막을 우려 없음) 동기로 완료까지 기다려서 전송 유실을 막는다. */
    private fun pushUsageToFirebaseBlocking(groupId: Long, dateStr: String, usedSeconds: Int) {
        val group = data.groups.find { it.id == groupId } ?: return
        com.phonelock.desktop.monitor.PomodoroSyncClient.writeDailyUsage(
            data.fbDatabaseUrl, data.fbApiKey, dateStr, group.name, DAILY_USAGE_DEVICE, usedSeconds
        )
    }

    /** 아직 저장되지 않은 사용시간이 있으면 강제로 저장한다. 앱 정상 종료 시 반드시 호출해야 한다. */
    fun flushPendingUsage() = synchronized(lock) {
        if (usageDirty) {
            persist()
            usageDirty = false
            val dateStr = effectiveDate(data.dailyResetHour).toString()
            data.usageRecords.filter { it.date == dateStr }.forEach { pushUsageToFirebaseBlocking(it.groupId, dateStr, it.usedSeconds) }
        }
    }

    /** 전체 사용시간 기록을 CSV로 직렬화한다(날짜,그룹,사용시간(초) — 전문가 종합분석 보고서 #20). */
    fun exportUsageCsv(): String = synchronized(lock) {
        val header = "date,group,usedSeconds"
        val rows = data.usageRecords.sortedByDescending { it.date }.map { r ->
            val groupName = data.groups.find { it.id == r.groupId }?.name ?: "(삭제된 그룹 ${r.groupId})"
            "${r.date},${csvEscape(groupName)},${r.usedSeconds}"
        }
        (listOf(header) + rows).joinToString("\n")
    }

    private fun csvEscape(s: String): String =
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) "\"${s.replace("\"", "\"\"")}\"" else s

    var dailyResetHour: Int
        get() = synchronized(lock) { data.dailyResetHour }
        set(value) = synchronized(lock) {
            data.dailyResetHour = value
            persist()
        }

    var themeMode: String
        get() = synchronized(lock) { data.themeMode }
        set(value) = synchronized(lock) {
            data.themeMode = value
            persist()
        }

    /** 앱 완전 종료 시 회유 멘트 20개 확인 절차를 거칠지(79차, 사용자 요청) — 기본 꺼짐.
     *  이 값을 켬→끔으로 바꾸는 것 자체도 같은 절차로 보호한다(설정 화면에서 처리, [[DECISIONS.md]] 79차 참고). */
    var exitConfirmEnabled: Boolean
        get() = synchronized(lock) { data.exitConfirmEnabled }
        set(value) = synchronized(lock) {
            data.exitConfirmEnabled = value
            persist()
        }

    /** 커스텀 테마(79차, 사용자 요청)용 배경/포인트 색 — "#RRGGBB" 문자열로 저장. themeMode가
     *  [com.phonelock.desktop.ui.theme.ThemeMode.CUSTOM]일 때만 쓰인다. */
    var customThemeBackground: String
        get() = synchronized(lock) { data.customThemeBackground }
        set(value) = synchronized(lock) {
            data.customThemeBackground = value
            persist()
        }

    var customThemeAccent: String
        get() = synchronized(lock) { data.customThemeAccent }
        set(value) = synchronized(lock) {
            data.customThemeAccent = value
            persist()
        }

    /** 현재 선택된 테마의 완성된 팔레트 — CUSTOM이면 두 색으로부터 나머지를 자동 계산한다. */
    fun currentPalette(): com.phonelock.desktop.ui.theme.PhoneLockPalette = synchronized(lock) {
        if (data.themeMode == com.phonelock.desktop.ui.theme.ThemeMode.CUSTOM) {
            com.phonelock.desktop.ui.theme.buildCustomPalette(data.customThemeBackground, data.customThemeAccent)
        } else {
            com.phonelock.desktop.ui.theme.paletteFor(data.themeMode)
        }
    }

    /** 켜져있는(groupEnabled) 관리 그룹 전체 — "모임" 공유용. 76차엔 "지금 실제로 제한 중인" 그룹만
     *  걸러 보여줬으나(isCurrentlyRestricting), 시간대가 안 맞아 당장은 제한 중이 아닌 그룹(예: 주말에만
     *  도는 그룹)도 사용자가 "그냥 다 보이게" 요청해 groupEnabled 기준으로 넓혔다. 이름/설명뿐 아니라
     *  스케줄/일일한도/실행확인 설정과 차단 앱·사이트 목록까지 그대로 넘겨(SocialGroupSyncClient가
     *  필요한 필드만 뽑아 씀) 모임 멤버 상세에서 "이 그룹이 정확히 뭘 하는지" 볼 수 있게 한다.
     */
    fun sharedActiveGroups(): List<Group> = getGroups().filter { it.groupEnabled }

    /** 모임 멤버 상세의 캘린더 날짜 상세에서 "그 날 얼마나 공부했는지"를 보여주기 위한 범위 조회. */
    fun getStudyLogInRange(fromKey: String, toKey: String): List<StudyLogEntry> = synchronized(lock) {
        data.studyLog.filter { it.dateKey in fromKey..toKey }
    }

    /** "모임" 공유 설정/사용자별 비공개 설정 — 전부 로컬(JsonStore), UI는 이 창구로만 접근한다. */
    fun groupShareSettings(groupId: String): GroupShareSettings =
        synchronized(lock) { data.groupShareSettings[groupId] ?: GroupShareSettings() }
    fun setGroupShareSettings(groupId: String, settings: GroupShareSettings) = synchronized(lock) {
        data.groupShareSettings[groupId] = settings
        persist()
    }

    /** 특정 상대에게 내 정보 전체를 숨길지 — 다음 [com.phonelock.desktop.monitor.SocialGroupSyncClient.pushMyStats] 때 RTDB에 반영된다. */
    fun hiddenFromUidsFor(groupId: String): Set<String> = synchronized(lock) { data.hiddenFromUidsByGroup[groupId]?.toSet() ?: emptySet() }
    fun setHiddenFromUid(groupId: String, targetUid: String, hidden: Boolean) = synchronized(lock) {
        val set = data.hiddenFromUidsByGroup.getOrPut(groupId) { mutableSetOf() }
        if (hidden) set.add(targetUid) else set.remove(targetUid)
        persist()
    }

    /** 특정 상대의 정보를 내 화면에서만 안 보이게 할지 — 순수 로컬 표시 설정, 서버엔 안 올라간다. */
    fun hiddenPeerUidsFor(groupId: String): Set<String> = synchronized(lock) { data.hiddenPeerUidsByGroup[groupId]?.toSet() ?: emptySet() }
    fun setHiddenPeerUid(groupId: String, targetUid: String, hidden: Boolean) = synchronized(lock) {
        val set = data.hiddenPeerUidsByGroup.getOrPut(groupId) { mutableSetOf() }
        if (hidden) set.add(targetUid) else set.remove(targetUid)
        persist()
    }

    /** "무작위 알림"(77차) — 이 모임에서 이 기기가 처지는 멤버를 자동으로 깨울지, 순수 로컬 설정. */
    fun randomNudgeEnabledFor(groupId: String): Boolean = synchronized(lock) { data.groupRandomNudgeEnabled[groupId] ?: true }
    fun setRandomNudgeEnabled(groupId: String, enabled: Boolean) = synchronized(lock) {
        data.groupRandomNudgeEnabled[groupId] = enabled
        persist()
    }

    /** 가입 승인 상태 로컬 캐시(AccountGateScreen 낙관적 표시용) — get/set 모두 즉시 반영. */
    var cachedApprovalStatus: String?
        get() = synchronized(lock) { data.cachedApprovalStatus }
        set(value) = synchronized(lock) { data.cachedApprovalStatus = value; persist() }

    /** 관리자가 지정한 기능별 사용 허가 로컬 캐시 — 옛 승인 사용자(필드 없음)는 기본 true. */
    var permRoutine: Boolean
        get() = synchronized(lock) { data.permRoutine }
        set(value) = synchronized(lock) { data.permRoutine = value; persist() }
    var permStudy: Boolean
        get() = synchronized(lock) { data.permStudy }
        set(value) = synchronized(lock) { data.permStudy = value; persist() }
    var permManage: Boolean
        get() = synchronized(lock) { data.permManage }
        set(value) = synchronized(lock) { data.permManage = value; persist() }
    var permSocial: Boolean
        get() = synchronized(lock) { data.permSocial }
        set(value) = synchronized(lock) { data.permSocial = value; persist() }

    /** 이 모임에서 마지막으로 확인한 넛지 시각(epoch millis, 없으면 0) — SocialGroupNotifier가 새 넛지 판정에 쓴다. */
    fun nudgeLastSeenFor(groupId: String): Long = synchronized(lock) { data.nudgeLastSeenByGroup[groupId] ?: 0L }

    fun setNudgeLastSeen(groupId: String, millis: Long) = synchronized(lock) {
        data.nudgeLastSeenByGroup[groupId] = millis
        persist()
    }

    var blockReels: Boolean
        get() = synchronized(lock) { data.blockReels }
        set(value) = synchronized(lock) {
            data.blockReels = value
            persist()
        }

    var blockShorts: Boolean
        get() = synchronized(lock) { data.blockShorts }
        set(value) = synchronized(lock) {
            data.blockShorts = value
            persist()
        }

    var routineStreakNotifyEnabled: Boolean
        get() = synchronized(lock) { data.routineStreakNotifyEnabled }
        set(value) = synchronized(lock) {
            data.routineStreakNotifyEnabled = value
            persist()
        }

    var lastRoutineStreak: Int
        get() = synchronized(lock) { data.lastRoutineStreak }
        set(value) = synchronized(lock) {
            data.lastRoutineStreak = value
            persist()
        }

    var zeroStreakDays: Int
        get() = synchronized(lock) { data.zeroStreakDays }
        set(value) = synchronized(lock) {
            data.zeroStreakDays = value
            persist()
        }

    var fbDatabaseUrl: String?
        get() = synchronized(lock) { data.fbDatabaseUrl }
        set(value) = synchronized(lock) {
            data.fbDatabaseUrl = value
            persist()
        }

    var fbApiKey: String?
        get() = synchronized(lock) { data.fbApiKey }
        set(value) = synchronized(lock) {
            data.fbApiKey = value
            persist()
        }

    /** Firebase에서 이 그룹의 실행확인 레벨을 읽는다. 설정이 비어있거나 오류가 나면 null. */
    private fun readSyncedEscalation(groupName: String, groupId: Long): ConfirmEscalation? {
        val entry = com.phonelock.desktop.monitor.PomodoroSyncClient
            .readConfirmSync(data.fbDatabaseUrl, data.fbApiKey, groupName) ?: return null
        return ConfirmEscalation(
            groupId = groupId,
            level = entry.level,
            lastConfirmedAtEpochMillis = entry.lastConfirmedAtEpochMillis
        )
    }

    /** Firebase에 이 그룹(이름으로 매칭)의 실행확인 레벨만 갱신한다. */
    private fun writeSyncedEscalation(groupName: String, escalation: ConfirmEscalation) {
        com.phonelock.desktop.monitor.PomodoroSyncClient.writeConfirmSync(
            data.fbDatabaseUrl, data.fbApiKey,
            groupName, escalation.level, escalation.lastConfirmedAtEpochMillis
        )
    }

    private data class CachedEscalation(val value: ConfirmEscalation, val fetchedAtMillis: Long)
    private val escalationCache = mutableMapOf<Long, CachedEscalation>()
    private val escalationCacheTtlMs = 10_000L

    /**
     * 로컬 값과 Firebase(모바일과 공유) 값 중 lastConfirmedAtEpochMillis가 더 최근인 쪽을 채택한다.
     * 모바일에서 더 최근에 확인했다면 그 값을 가져오고, 아니면 로컬 값을 그대로 쓴다. 그룹은 이름으로
     * 매칭하므로(기기마다 그룹ID가 다름), 같은 이름의 그룹이 있는 경우에만 동기화된다.
     *
     * 브라우저 오버레이가 남은 시간 표시를 위해 2초마다 이 값을 폴링하는데, 매번 네트워크 호출을 하면
     * 잦은 호출이 발생하므로 짧게(escalationCacheTtlMs) 캐싱한다.
     */
    private fun mergedEscalation(group: Group): ConfirmEscalation {
        val now = System.currentTimeMillis()
        val cached = escalationCache[group.id]
        if (cached != null && now - cached.fetchedAtMillis < escalationCacheTtlMs) {
            return cached.value
        }
        val local = data.confirmEscalations.find { it.groupId == group.id } ?: ConfirmEscalation(groupId = group.id)
        val synced = readSyncedEscalation(group.name, group.id)
        val winner = if (synced != null && synced.lastConfirmedAtEpochMillis > local.lastConfirmedAtEpochMillis) synced else local
        if (winner.level != local.level || winner.lastConfirmedAtEpochMillis != local.lastConfirmedAtEpochMillis) {
            data.confirmEscalations.removeAll { it.groupId == group.id }
            data.confirmEscalations.add(winner)
            persist()
        }
        escalationCache[group.id] = CachedEscalation(winner, now)
        return winner
    }

    /**
     * 지금 시점 기준으로 레벨을 계산한다. 정해진 시각(정각 등) 기준이 아니라, 마지막 확인(진행 완료)
     * 시점으로부터 그룹이 설정한 levelDecayIntervalSeconds가 지날 때마다 레벨이 1씩 자연 차감된다
     * (여러 간격이 한꺼번에 지났으면 그만큼 한 번에 차감, 0 밑으로는 안 내려감). 그룹의
     * levelDecayEnabled가 꺼져 있으면 차감되지 않는다.
     */
    private fun decayedLevel(record: ConfirmEscalation, now: Long, group: Group): Int {
        if (record.lastConfirmedAtEpochMillis <= 0) return record.level
        if (!group.levelDecayEnabled || group.levelDecayIntervalSeconds <= 0) return record.level
        val intervalMs = group.levelDecayIntervalSeconds.toLong() * 1000L
        val elapsed = now - record.lastConfirmedAtEpochMillis
        val decays = (elapsed / intervalMs).toInt()
        return (record.level - decays).coerceAtLeast(0)
    }

    fun getConfirmWaitSeconds(group: Group): Int = synchronized(lock) {
        val merged = mergedEscalation(group)
        val level = decayedLevel(merged, System.currentTimeMillis(), group)
        group.initialWaitSeconds + level * group.waitIncrementSeconds
    }

    /** 지금 이 그룹의 실행확인 레벨(재확인할 때마다 오르고, 차감 간격이 지날 때마다 1씩 내려감). */
    fun getCurrentLevel(group: Group): Int = synchronized(lock) {
        decayedLevel(mergedEscalation(group), System.currentTimeMillis(), group)
    }

    /**
     * 같은 이름의 그룹을 가진 다른 기기가 방금 확인(예)을 통과했다면 그 시각을, 아니면 이 기기의 마지막
     * 확인 시각을 반환한다(mergedEscalation과 같은 "최신값 승리" 규칙, 같은 10초 캐시 재사용). 재확인
     * 판정 로직 자체(ConfirmationGate)는 건드리지 않는다 — 호출부(EnforcementService/SiteEnforcement)가
     * 이 값과 로컬 ConfirmationGate 값 중 더 나중(더 최근)인 쪽으로 "지금 유예시간 안인지"를 계산해서,
     * 같은 이름의 그룹이면 다른 기기에서 확인한 것도 재확인 없이 이어서 쓸 수 있게 한다.
     */
    fun syncedLastConfirmedAtEpochMillis(group: Group): Long = synchronized(lock) {
        mergedEscalation(group).lastConfirmedAtEpochMillis
    }

    fun recordConfirm(group: Group) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val merged = mergedEscalation(group)
        val currentLevel = decayedLevel(merged, now, group)
        val updated = ConfirmEscalation(groupId = group.id, level = currentLevel + 1, lastConfirmedAtEpochMillis = now)
        data.confirmEscalations.removeAll { it.groupId == group.id }
        data.confirmEscalations.add(updated)
        escalationCache[group.id] = CachedEscalation(updated, now)
        incrementConfirmCounter(group.id)
        persist()
        writeSyncedEscalation(group.name, updated)
    }

    /** 재확인 화면을 통과할 때마다 그날 카운터를 1 증가 — 순수 로컬 통계, 판정 로직과 무관(호출부는 이미 lock을 쥐고 있음). */
    private fun incrementConfirmCounter(groupId: Long) {
        val today = effectiveDate(data.dailyResetHour).toString()
        val idx = data.confirmCounters.indexOfFirst { it.groupId == groupId && it.date == today }
        if (idx >= 0) {
            data.confirmCounters[idx] = data.confirmCounters[idx].copy(count = data.confirmCounters[idx].count + 1)
        } else {
            data.confirmCounters.add(ConfirmCounter(groupId, today, 1))
        }
    }

    /** 이 그룹의 오늘/어제 재확인 통과 횟수("위반 시도" 카운터, 보고서 #32). */
    fun getConfirmCountToday(groupId: Long): Int = synchronized(lock) {
        val today = effectiveDate(data.dailyResetHour).toString()
        data.confirmCounters.find { it.groupId == groupId && it.date == today }?.count ?: 0
    }

    fun getConfirmCountYesterday(groupId: Long): Int = synchronized(lock) {
        val yesterday = effectiveDate(data.dailyResetHour).minusDays(1).toString()
        data.confirmCounters.find { it.groupId == groupId && it.date == yesterday }?.count ?: 0
    }

    // ---- 네이티브 공부 타이머(1단계) ----
    // 로컬 상태가 유일한 source of truth. Firebase엔 페이즈 전환 시점에만(EnforcementService/UI 쪽에서
    // PomodoroSyncClient.pushLocalStudyStatus 호출) 크로스디바이스 신호로 write한다 — DECISIONS.md 참고.

    fun getTimerRun(): TimerRunState? = synchronized(lock) { data.timerRun }

    private fun setTimerRun(state: TimerRunState?) {
        synchronized(lock) {
            data.timerRun = state
            persist()
        }
        // 호출부(StudyTimerScreen 버튼 클릭 등)가 Compose UI 스레드일 수 있으므로 네트워크 호출은
        // 백그라운드 스레드에서 fire-and-forget으로 보낸다 — 실패해도 로컬 상태는 이미 저장된 뒤.
        Thread {
            com.phonelock.desktop.monitor.PomodoroSyncClient.pushLocalStudyStatus(
                fbDatabaseUrl, fbApiKey,
                timerActive = state?.phase == "study",
                breakActive = state?.phase == "break",
                phaseEndAt = state?.phaseEndAt ?: 0L,
                mode = state?.mode ?: "plain",
                phaseStartedAt = state?.phaseStartedAt ?: 0L,
                taskName = state?.taskName ?: ""
            )
        }.start()
    }

    var pomodoroStudyMinutes: Int
        get() = synchronized(lock) { data.pomodoroStudyMinutes }
        set(value) = synchronized(lock) { data.pomodoroStudyMinutes = value; persist() }

    var pomodoroBreakMinutes: Int
        get() = synchronized(lock) { data.pomodoroBreakMinutes }
        set(value) = synchronized(lock) { data.pomodoroBreakMinutes = value; persist() }

    var pomodoroModeEnabled: Boolean
        get() = synchronized(lock) { data.pomodoroModeEnabled }
        set(value) = synchronized(lock) { data.pomodoroModeEnabled = value; persist() }

    var studyLockAllowedApps: List<String>
        get() = synchronized(lock) { data.studyLockAllowedApps.toList() }
        set(value) = synchronized(lock) { data.studyLockAllowedApps = value.toMutableList(); persist() }

    var studyLockAllowedSites: List<String>
        get() = synchronized(lock) { data.studyLockAllowedSites.toList() }
        set(value) = synchronized(lock) { data.studyLockAllowedSites = value.toMutableList(); persist() }

    /** 다른 기기가 올린 그날 공부 기록의 표시용 캐시(날짜 -> 그 날짜의 다른 기기 기록) — 로컬 studyLog엔
     *  병합해 쓰지 않는다. dailyUsage와 같은 "기기별 키" 패턴(경쟁 없음) 참고. */
    private var remoteStudyLogCache: Map<String, List<StudyLogEntry>> = emptyMap()

    fun getTodayStudyLog(): List<StudyLogEntry> = synchronized(lock) {
        val today = effectiveDate(data.dailyResetHour).toString()
        data.studyLog.filter { it.dateKey == today } + (remoteStudyLogCache[today] ?: emptyList())
    }

    /** 캘린더 날짜 상세에서 임의 날짜(dateKey, yyyy-MM-dd)의 타이머 기록을 보여줄 때 사용. */
    fun getStudyLogForDate(dateKey: String): List<StudyLogEntry> = synchronized(lock) {
        data.studyLog.filter { it.dateKey == dateKey } + (remoteStudyLogCache[dateKey] ?: emptyList())
    }

    /** 타이머가 "오늘 캘린더 일정"을 고를 때 쓰는 오늘 날짜 키 — 사용시간 기록과 같은 dailyResetHour 기준. */
    fun todayCalendarDateKey(): String = synchronized(lock) { effectiveDate(data.dailyResetHour).toString() }

    private fun addStudyLogEntry(taskName: String, seconds: Int, startedAt: Long, note: String = "") = synchronized(lock) {
        if (seconds <= 0) return@synchronized
        val today = effectiveDate(data.dailyResetHour).toString()
        data.studyLog.add(StudyLogEntry(today, taskName.ifBlank { "이름 없는 공부" }, seconds, startedAt, note))
        persist()
        pushStudyLogToFirebase(today)
    }

    private fun studyLogEntriesToJson(entries: List<StudyLogEntry>): JSONArray {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("taskName", e.taskName); put("seconds", e.seconds); put("startedAt", e.startedAt); put("note", e.note)
            })
        }
        return arr
    }

    /** 이 기기가 그날 기록한 전체를 fire-and-forget으로 Firebase에 덮어쓴다(호출부는 이미 lock을 쥐고 있음). */
    private fun pushStudyLogToFirebase(dateKey: String) {
        val entries = data.studyLog.filter { it.dateKey == dateKey }
        val json = studyLogEntriesToJson(entries)
        val url = data.fbDatabaseUrl; val key = data.fbApiKey
        Thread {
            com.phonelock.desktop.monitor.PomodoroSyncClient.writeStudyLogForDate(url, key, dateKey, DAILY_USAGE_DEVICE, json)
        }.start()
    }

    /**
     * 타이머/캘린더 화면 진입 시 호출 — 다른 기기가 올린 그날 공부 기록을 읽어와 [remoteStudyLogCache]에
     * 채운다. 로컬 studyLog엔 병합하지 않으므로(재호출해도 중복 안 생김) 부담 없이 반복 호출 가능하다.
     * 네트워크 호출을 포함하므로 호출부(UI)가 백그라운드 스레드/코루틴에서 실행해야 한다.
     */
    fun syncStudyLogFromFirebase(dateKey: String) {
        val (url, key) = synchronized(lock) { data.fbDatabaseUrl to data.fbApiKey }
        val remote = com.phonelock.desktop.monitor.PomodoroSyncClient.readStudyLogForDate(url, key, dateKey) ?: return
        val others = mutableListOf<StudyLogEntry>()
        remote.keys().forEach { device ->
            if (device == DAILY_USAGE_DEVICE) return@forEach
            val arr = remote.optJSONArray(device) ?: return@forEach
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                others.add(StudyLogEntry(dateKey, obj.optString("taskName", ""), obj.optInt("seconds", 0), obj.optLong("startedAt", 0L), obj.optString("note", "")))
            }
        }
        synchronized(lock) {
            remoteStudyLogCache = remoteStudyLogCache + (dateKey to others)
        }
    }

    /** 스톱워치/뽀모도로를 새로 시작한다. 이미 실행 중이면 아무 일도 하지 않는다. */
    fun timerStart(taskName: String, pomodoro: Boolean) {
        if (getTimerRun() != null) return
        val now = System.currentTimeMillis()
        val mode = if (pomodoro) "pomodoro" else "plain"
        val phaseEndAt = if (pomodoro) now + pomodoroStudyMinutes * 60_000L else 0L
        setTimerRun(TimerRunState(taskName = taskName, mode = mode, phase = "study", phaseStartedAt = now, phaseEndAt = phaseEndAt))
    }

    /** 타이머를 정지하고, 진행 중이던 공부 페이즈의 경과시간을 기록에 적립한다. note는 사용자가 남긴 짧은 회고(선택). */
    fun timerStop(note: String = "") {
        val run = getTimerRun() ?: return
        if (run.phase == "study") {
            val elapsed = ((System.currentTimeMillis() - run.phaseStartedAt) / 1000L).toInt()
            addStudyLogEntry(run.taskName, elapsed, run.phaseStartedAt, note)
        }
        setTimerRun(null)
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
            setTimerRun(run.copy(phase = "break", phaseStartedAt = now, phaseEndAt = now + pomodoroBreakMinutes * 60_000L, breakExtraUsed = false))
        } else {
            setTimerRun(run.copy(phase = "study", phaseStartedAt = now, phaseEndAt = now + pomodoroStudyMinutes * 60_000L, cycleCount = run.cycleCount + 1))
        }
    }

    /** 휴식이 다 됐을 때 1회 한정으로 5분 더 쉰다. */
    fun timerExtendBreak() {
        val run = getTimerRun() ?: return
        if (run.phase != "break" || run.breakExtraUsed) return
        setTimerRun(run.copy(phaseEndAt = System.currentTimeMillis() + 5 * 60_000L, breakExtraUsed = true))
    }

    /** 지금 이 기기에서 공부 잠금(전체화면)이 걸려야 하는지 — 공부 페이즈로 진행 중일 때만. */
    fun isStudyLockActive(): Boolean = getTimerRun()?.phase == "study"

    fun isTimerPomodoroMode(): Boolean = getTimerRun()?.mode == "pomodoro"

    // ---- 네이티브 캘린더(2단계) ----
    // 웹앱 index.html의 calTasks[dateKey][]를 그대로 이식. 배열 순서=표시 순서이므로 이 기기에선
    // 전체를 한 MutableList<CalendarTask>로 갖고, 같은 dateKey를 가진 항목들의 리스트 내 상대 순서를
    // "그 날짜 안에서의 표시 순서"로 취급한다(순서 변경/정렬은 항상 그 상대 위치끼리만 스왑/재배치).
    // Firebase엔 웹앱과 동일하게 users/{user}/calendar 경로에 { tasks:{dateKey:[...]}, _ts } 전체문서로
    // 동기화(문서 단위 LWW, calendarTs가 로컬 타임스탬프).

    // 77차: 8단계(하양~보라, 51차)에서 다시 3단계(빨/노/초)로 축소(사용자 요청) — 과도하게 길었던
    // 회독 주기를 짧게 되돌렸다. 저장된 기존 color 값(white/orange/blue/indigo/purple)은 그대로 두되
    // (51차와 같은 전례: "라벨 매핑만 바뀌어 과거 일정은 다른 회독 번호로 보인다") 새로 고르거나
    // 자동 생성되는 회독은 이 3색만 쓴다. 가장 진행된 단계가 0(정렬 시 위/먼저 보임).
    private val CALENDAR_COLOR_ORDER = mapOf(
        "green" to 0, "yellow" to 1, "red" to 2
    )

    /**
     * color -> (다음 회독 color, 기본 간격일수). green(3회독)은 종단이라 매핑 없음.
     * 사용자 지정값 — 1회독(만든 날)부터 누적 0/3/7일차: red(1회독, 0일)→yellow(2회독, +3일)→
     * green(3회독, 1회독 기준 +7일 = yellow 기준 +4일).
     */
    private val CALENDAR_SCHEDULE = mapOf(
        "red" to ("yellow" to 3),
        "yellow" to ("green" to 4)
    )

    private val koreanCollator = java.text.Collator.getInstance(java.util.Locale.KOREAN)

    fun getCalendarTasks(dateKey: String): List<CalendarTask> = synchronized(lock) {
        data.calendarTasks.filter { it.dateKey == dateKey }
    }

    /** 월 그리드 렌더용 — [fromKey, toKey] 범위(양 끝 포함, 문자열 비교)의 모든 일정. */
    fun getCalendarTasksInRange(fromKey: String, toKey: String): List<CalendarTask> = synchronized(lock) {
        data.calendarTasks.filter { it.dateKey in fromKey..toKey }
    }

    /** 통계(5단계) 화면용 — 날짜 범위 없이 전체 일정. */
    fun getAllCalendarTasks(): List<CalendarTask> = synchronized(lock) { data.calendarTasks.toList() }

    private fun dayGlobalIndices(dateKey: String): List<Int> =
        data.calendarTasks.indices.filter { data.calendarTasks[it].dateKey == dateKey }

    private fun sortCalendarDay(dateKey: String) {
        val indices = dayGlobalIndices(dateKey)
        if (indices.size < 2) return
        val sorted = indices.map { data.calendarTasks[it] }.sortedWith(
            compareBy<CalendarTask> { CALENDAR_COLOR_ORDER[it.color] ?: 99 }
                .thenComparator { a, b -> koreanCollator.compare(a.name, b.name) }
        )
        indices.forEachIndexed { i, globalIdx -> data.calendarTasks[globalIdx] = sorted[i] }
    }

    fun addCalendarTask(dateKey: String, name: String) = synchronized(lock) {
        if (name.isBlank()) return@synchronized
        data.calendarTasks.add(CalendarTask(dateKey = dateKey, name = name.trim(), color = "red", status = null))
        sortCalendarDay(dateKey)
        persist()
        pushCalendarToFirebase()
    }

    fun renameCalendarTask(dateKey: String, ordinal: Int, newName: String) = synchronized(lock) {
        if (newName.isBlank()) return@synchronized
        val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
        data.calendarTasks[idx] = data.calendarTasks[idx].copy(name = newName.trim())
        persist()
        pushCalendarToFirebase()
    }

    fun recolorCalendarTask(dateKey: String, ordinal: Int, newColor: String) = synchronized(lock) {
        val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
        data.calendarTasks[idx] = data.calendarTasks[idx].copy(color = newColor)
        sortCalendarDay(dateKey)
        persist()
        pushCalendarToFirebase()
    }

    fun setCalendarTaskNextDays(dateKey: String, ordinal: Int, nextDays: Int?) = synchronized(lock) {
        val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
        data.calendarTasks[idx] = data.calendarTasks[idx].copy(nextDays = nextDays)
        persist()
        pushCalendarToFirebase()
    }

    /** ▲▼ 순서 변경(드래그 아님 — 웹앱도 배열 스왑 버튼 방식). direction은 -1(위) 또는 +1(아래). */
    fun moveCalendarTaskOrder(dateKey: String, ordinal: Int, direction: Int) = synchronized(lock) {
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

    fun deleteCalendarTask(dateKey: String, ordinal: Int) = synchronized(lock) {
        val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
        data.calendarTasks.removeAt(idx)
        persist()
        pushCalendarToFirebase()
    }

    private fun nextScheduleDateKey(dateKey: String, days: Int): String =
        LocalDate.parse(dateKey).plusDays(days.toLong()).toString()

    fun setCalendarTaskMultiPass(dateKey: String, ordinal: Int, enabled: Boolean) = synchronized(lock) {
        val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
        data.calendarTasks[idx] = data.calendarTasks[idx].copy(multiPassEnabled = enabled)
        persist()
        pushCalendarToFirebase()
    }

    private fun applyCalendarAutoSchedule(dateKey: String, task: CalendarTask) {
        if (!task.multiPassEnabled) return
        val (nextColor, defaultDays) = CALENDAR_SCHEDULE[task.color] ?: return
        val days = task.nextDays?.takeIf { it >= 0 } ?: defaultDays
        val nKey = nextScheduleDateKey(dateKey, days)
        val exists = data.calendarTasks.any { it.dateKey == nKey && it.name == task.name && it.color == nextColor }
        if (!exists) {
            data.calendarTasks.add(CalendarTask(dateKey = nKey, name = task.name, color = nextColor, status = null, nextDays = task.nextDays))
            sortCalendarDay(nKey)
        }
    }

    private fun revertCalendarAutoSchedule(dateKey: String, task: CalendarTask) {
        if (!task.multiPassEnabled) return
        val (nextColor, defaultDays) = CALENDAR_SCHEDULE[task.color] ?: return
        val days = task.nextDays?.takeIf { it >= 0 } ?: defaultDays
        val nKey = nextScheduleDateKey(dateKey, days)
        val removeIdx = data.calendarTasks.indexOfFirst {
            it.dateKey == nKey && it.name == task.name && it.color == nextColor && it.status == null
        }
        if (removeIdx >= 0) data.calendarTasks.removeAt(removeIdx)
    }

    /** 미완료(X) 처리 시 다음날로 같은 업무를 그대로 복사(원본은 유지, "복사" 기능과 동일한 필드 이식). */
    private fun applyIncompleteCarryOver(dateKey: String, task: CalendarTask) {
        val nKey = nextScheduleDateKey(dateKey, 1)
        val exists = data.calendarTasks.any { it.dateKey == nKey && it.name == task.name && it.color == task.color && it.status == null }
        if (!exists) {
            data.calendarTasks.add(task.copy(dateKey = nKey, status = null))
            sortCalendarDay(nKey)
        }
    }

    private fun revertIncompleteCarryOver(dateKey: String, task: CalendarTask) {
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
    fun setCalendarTaskStatus(dateKey: String, ordinal: Int, targetStatus: String) = synchronized(lock) {
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
    private fun linkedProgressAmount(task: CalendarTask): Double =
        task.progressStep?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0

    private fun formatCalcNumber(n: Double): String {
        val r = Math.round(n * 100) / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
    }

    /** delta만큼 그 계산기 업무의 progress를 더하고(0 밑으로는 안 내려감) Firebase에도 반영한다. */
    private fun adjustLinkedCalcProgress(calcTaskName: String, delta: Double) {
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
    fun addLinkedCalendarTask(dateKey: String, calcTaskName: String, from: Int, to: Int) = synchronized(lock) {
        if (from > to || from < 1) return@synchronized
        val calcTask = data.calcTasks.find { it.name == calcTaskName } ?: return@synchronized
        val unit = calcTask.unit.trim()
        val taskName = "$calcTaskName $from~$to$unit"
        if (data.calendarTasks.any { it.dateKey == dateKey && it.name == taskName }) return@synchronized
        data.calendarTasks.add(
            CalendarTask(
                dateKey = dateKey, name = taskName, color = "red", status = null,
                linkedCalc = calcTaskName, progressStep = (to - from + 1).toString()
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
    fun isLinkedGoalAchieved(dateKey: String, calcTaskName: String, dayQuota: Double): Boolean = synchronized(lock) {
        if (dayQuota <= 0) return@synchronized false
        val doneTotal = data.calendarTasks
            .filter { it.dateKey == dateKey && it.linkedCalc == calcTaskName && it.status == "O" }
            .sumOf { it.progressStep?.toDoubleOrNull() ?: 0.0 }
        doneTotal >= dayQuota
    }

    /** 이동: 대상 날짜로 옮기고 nextDays/linkedCalc/progressStep은 버린다(웹앱 moveCalTask 경로와 동일 동작). */
    fun moveCalendarTaskToDate(dateKey: String, ordinal: Int, targetDateKey: String) = synchronized(lock) {
        val idx = dayGlobalIndices(dateKey).getOrNull(ordinal) ?: return@synchronized
        val task = data.calendarTasks[idx]
        data.calendarTasks.removeAt(idx)
        data.calendarTasks.add(CalendarTask(dateKey = targetDateKey, name = task.name, color = task.color, status = task.status))
        sortCalendarDay(targetDateKey)
        persist()
        pushCalendarToFirebase()
    }

    /** 복사: 상태는 초기화(null)하고 nextDays 등 부가 필드는 그대로 옮긴다. 원본은 유지. */
    fun copyCalendarTaskToDate(dateKey: String, ordinal: Int, targetDateKey: String) = synchronized(lock) {
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
    fun pruneOldStats(monthsAgo: Int = 12): Int = synchronized(lock) {
        val cutoff = effectiveDate(data.dailyResetHour).minusMonths(monthsAgo.toLong()).toString()
        val before = data.usageRecords.size + data.confirmCounters.size + data.studyLog.size
        data.usageRecords.removeAll { it.date < cutoff }
        data.confirmCounters.removeAll { it.date < cutoff }
        data.studyLog.removeAll { it.dateKey < cutoff }
        val removed = before - (data.usageRecords.size + data.confirmCounters.size + data.studyLog.size)
        if (removed > 0) persist()
        removed
    }

    /** 오늘 기준 6개월 이전 일정을 영구 삭제(되돌리기 없음, 웹앱 confirmArchiveOldCalTasks와 동일). 삭제된 날짜 수 반환. */
    fun archiveOldCalendarTasks(): Int = synchronized(lock) {
        val cutoffKey = LocalDate.now().minusMonths(6).toString()
        val staleDates = data.calendarTasks.map { it.dateKey }.filter { it < cutoffKey }.toSet()
        if (staleDates.isEmpty()) return@synchronized 0
        data.calendarTasks.removeAll { it.dateKey in staleDates }
        persist()
        pushCalendarToFirebase()
        staleDates.size
    }

    private fun calendarTasksToJson(): JSONObject {
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
                })
            }
            root.put(dateKey, arr)
        }
        return root
    }

    private fun calendarTasksFromJson(root: JSONObject): MutableList<CalendarTask> {
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
                        multiPassEnabled = t.optBoolean("multiPassEnabled", false)
                    )
                )
            }
        }
        return list
    }

    /** 변경 직후 fire-and-forget으로 Firebase에 전체 캘린더 문서를 올린다(호출부는 이미 lock을 쥐고 있음). */
    private fun pushCalendarToFirebase() {
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
    fun syncCalendarFromFirebase() {
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

    // ══════════════════════════════════════════════════════
    // 네이티브 계산기(3단계) — 웹앱 index.html의 할당량 계산기를 그대로 이식.
    // draft 업무(calcTasks)/저장됨(calcSaved)/폴더 트리(calcFolderPaths+calcFolderOrder)는 각자 독립된
    // LWW 타임스탬프를 갖는다(웹앱의 tasksTs/savedTs/savedFolderTreeTs/savedFolderOrderTs와 동일 이유 —
    // 예를 들어 폴더만 옮겼는데 입력 중이던 draft가 통째로 원격 값에 덮이면 안 된다).
    // ══════════════════════════════════════════════════════

    fun getCalcTasks(): List<CalcTask> = synchronized(lock) { data.calcTasks.toList() }

    fun addCalcTask(task: CalcTask = CalcTask()) = synchronized(lock) {
        data.calcTasks.add(task)
        data.calcTasksTs = System.currentTimeMillis()
        persist()
        pushCalcTasksAndSaved()
    }

    fun updateCalcTask(index: Int, updated: CalcTask) = synchronized(lock) {
        if (index !in data.calcTasks.indices) return@synchronized
        data.calcTasks[index] = updated.copy(modifiedAt = nowLabel(), modifiedAtTs = System.currentTimeMillis())
        data.calcTasksTs = System.currentTimeMillis()
        persist()
        pushCalcTasksAndSaved()
    }

    fun removeCalcTask(index: Int) = synchronized(lock) {
        if (index !in data.calcTasks.indices) return@synchronized
        data.calcTasks.removeAt(index)
        data.calcTasksTs = System.currentTimeMillis()
        persist()
        pushCalcTasksAndSaved()
    }

    fun moveCalcTaskOrder(index: Int, direction: Int) = synchronized(lock) {
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
    fun resetCalcTasks() = synchronized(lock) {
        data.calcTasks.clear()
        data.calcTasks.add(CalcTask())
        data.calcTasksTs = System.currentTimeMillis()
        persist()
        pushCalcTasksAndSaved()
    }

    fun getCalcSaved(): List<CalcSavedItem> = synchronized(lock) { data.calcSaved.toList() }

    /** 계산 결과 저장 — 같은 이름이 이미 있으면 덮어쓰고(폴더 위치는 유지), 없으면 맨 앞에 추가(웹앱 saveOneResult와 동일). */
    fun saveCalcResult(task: CalcTask, result: CalcEngine.CalcResult) = synchronized(lock) {
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
    fun loadCalcSavedItemAsDraft(index: Int) = synchronized(lock) {
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

    private fun fmtCalcNumber(n: Double): String = if (n == 0.0) "" else if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()

    fun deleteCalcSavedItem(index: Int) = synchronized(lock) {
        if (index !in data.calcSaved.indices) return@synchronized
        data.calcSaved.removeAt(index)
        data.calcSavedTs = System.currentTimeMillis()
        persist()
        pushCalcTasksAndSaved()
    }

    fun moveCalcSavedItem(index: Int, direction: Int) = synchronized(lock) {
        val target = index + direction
        if (index !in data.calcSaved.indices || target !in data.calcSaved.indices) return@synchronized
        val tmp = data.calcSaved[index]
        data.calcSaved[index] = data.calcSaved[target]
        data.calcSaved[target] = tmp
        data.calcSavedTs = System.currentTimeMillis()
        persist()
        pushCalcTasksAndSaved()
    }

    fun clearAllCalcSaved() = synchronized(lock) {
        data.calcSaved.clear()
        data.calcSavedTs = System.currentTimeMillis()
        persist()
        pushCalcTasksAndSaved()
    }

    fun moveCalcSavedItemToFolder(index: Int, folderPath: List<String>?) = synchronized(lock) {
        if (index !in data.calcSaved.indices) return@synchronized
        data.calcSaved[index] = data.calcSaved[index].copy(folderPath = folderPath?.takeIf { it.isNotEmpty() })
        data.calcSavedTs = System.currentTimeMillis()
        persist()
        pushCalcTasksAndSaved()
    }

    /** 폴더 접기 상태(기기 로컬, Firebase 미동기화) — 기본값 false(펼침)로 기존 동작을 유지한다. */
    fun isCalcFolderCollapsed(path: List<String>): Boolean = synchronized(lock) {
        calcPathToOrderKey(path) in data.calcFolderCollapsed
    }

    fun toggleCalcFolderCollapsed(path: List<String>) = synchronized(lock) {
        val key = calcPathToOrderKey(path)
        if (!data.calcFolderCollapsed.add(key)) data.calcFolderCollapsed.remove(key)
        persist()
    }

    fun getCalcFolderPaths(): List<List<String>> = synchronized(lock) { data.calcFolderPaths.toList() }

    /** 부모 경로 밑 하위 폴더 이름을 저장된 순서(calcFolderOrder)대로 반환, 순서에 없는 새 폴더는 뒤에 붙인다. */
    fun getCalcSubfolderNames(parentPath: List<String>): List<String> = synchronized(lock) {
        val allNames = data.calcFolderPaths.filter {
            it.size == parentPath.size + 1 && it.subList(0, parentPath.size) == parentPath
        }.map { it.last() }.distinct()
        val orderKey = calcPathToOrderKey(parentPath)
        val order = data.calcFolderOrder[orderKey] ?: emptyList()
        val existing = order.filter { it in allNames }
        val added = allNames.filter { it !in existing }
        existing + added
    }

    private fun calcPathToOrderKey(path: List<String>): String = if (path.isEmpty()) "__root__" else path.joinToString("|")

    fun createCalcFolder(parentPath: List<String>, name: String): Boolean = synchronized(lock) {
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

    fun renameCalcFolder(path: List<String>, newName: String): Boolean = synchronized(lock) {
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
    fun deleteCalcFolder(path: List<String>) = synchronized(lock) {
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

    fun moveCalcFolderOrder(parentPath: List<String>, name: String, direction: Int) = synchronized(lock) {
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
    private fun healCalcFolderPaths(): Boolean {
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

    private fun nowLabel(): String {
        val ts = java.time.LocalDateTime.now()
        return "%d/%d %02d:%02d".format(ts.monthValue, ts.dayOfMonth, ts.hour, ts.minute)
    }

    private fun calcTaskToJson(t: CalcTask): JSONObject = JSONObject().apply {
        put("name", t.name); put("qty", t.qty); put("unit", t.unit); put("progress", t.progress)
        put("start", t.start); put("dday", t.dday)
        put("mon", t.mon); put("tue", t.tue); put("wed", t.wed); put("thu", t.thu)
        put("fri", t.fri); put("sat", t.sat); put("sun", t.sun)
        put("holidays", JSONArray(t.holidays))
        put("modifiedAt", t.modifiedAt); put("modifiedAtTs", t.modifiedAtTs)
    }

    private fun calcTaskFromJson(t: JSONObject): CalcTask {
        val holidaysArr = t.optJSONArray("holidays") ?: JSONArray()
        return CalcTask(
            name = t.optString("name", ""), qty = t.optString("qty", ""), unit = t.optString("unit", ""),
            progress = t.optString("progress", ""), start = t.optString("start", ""), dday = t.optString("dday", ""),
            mon = t.optString("mon", ""), tue = t.optString("tue", ""), wed = t.optString("wed", ""),
            thu = t.optString("thu", ""), fri = t.optString("fri", ""), sat = t.optString("sat", ""), sun = t.optString("sun", ""),
            holidays = (0 until holidaysArr.length()).map { holidaysArr.getString(it) },
            modifiedAt = t.optString("modifiedAt", ""), modifiedAtTs = t.optLong("modifiedAtTs", 0L)
        )
    }

    private fun calcSavedToJson(s: CalcSavedItem): JSONObject = JSONObject().apply {
        put("name", s.name); put("qty", s.qty); put("unit", s.unit); put("progress", s.progress)
        put("start", s.start); put("dday", s.dday)
        put("mon", s.mon); put("tue", s.tue); put("wed", s.wed); put("thu", s.thu)
        put("fri", s.fri); put("sat", s.sat); put("sun", s.sun)
        put("holidays", JSONArray(s.holidays))
        put("savedAt", s.savedAt); put("modifiedAt", s.modifiedAt)
        put("folderPath", s.folderPath?.let { JSONArray(it) } ?: JSONObject.NULL)
    }

    private fun calcSavedFromJson(s: JSONObject): CalcSavedItem {
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

    private fun pushCalcTasksAndSaved() {
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

    private fun pushCalcFolders() {
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
    fun syncCalculatorFromFirebase() {
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

    // ══════════════════════════════════════════════════════
    // 루틴앱 v1(47차 설계, DECISIONS.md 참고) — Routine 하나로 체크리스트/습관/일과표 통합.
    // 51차: 캘린더와 동일한 "전체 문서 단위 LWW"로 Firebase 동기화 추가(users/{user}/routines).
    // ══════════════════════════════════════════════════════

    fun getRoutines(): List<Routine> = synchronized(lock) { data.routines.filter { !it.archived }.sortedBy { it.sortOrder } }

    fun addRoutine(routine: Routine) = synchronized(lock) {
        val nextOrder = (data.routines.maxOfOrNull { it.sortOrder } ?: -1) + 1
        data.routines.add(routine.copy(id = data.nextRoutineId, sortOrder = nextOrder))
        data.nextRoutineId++
        persist()
        pushRoutinesToFirebase()
    }

    fun updateRoutine(updated: Routine) = synchronized(lock) {
        val idx = data.routines.indexOfFirst { it.id == updated.id }
        if (idx !in data.routines.indices) return@synchronized
        data.routines[idx] = updated
        persist()
        pushRoutinesToFirebase()
    }

    fun deleteRoutine(routineId: Long) = synchronized(lock) {
        data.routines.removeAll { it.id == routineId }
        data.routineLogs.removeAll { it.routineId == routineId }
        persist()
        pushRoutinesToFirebase()
    }

    fun moveRoutineOrder(routineId: Long, direction: Int) = synchronized(lock) {
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
    fun swapRoutineOrder(idA: Long, idB: Long) = synchronized(lock) {
        val aIdx = data.routines.indexOfFirst { it.id == idA }
        val bIdx = data.routines.indexOfFirst { it.id == idB }
        if (aIdx < 0 || bIdx < 0) return@synchronized
        val a = data.routines[aIdx]; val b = data.routines[bIdx]
        data.routines[aIdx] = a.copy(sortOrder = b.sortOrder)
        data.routines[bIdx] = b.copy(sortOrder = a.sortOrder)
        persist()
        pushRoutinesToFirebase()
    }

    fun copyRoutine(routine: Routine) = synchronized(lock) {
        val nextOrder = (data.routines.maxOfOrNull { it.sortOrder } ?: -1) + 1
        data.routines.add(routine.copy(id = data.nextRoutineId, title = "${routine.title} (복사본)", sortOrder = nextOrder, archived = false))
        data.nextRoutineId++
        persist()
        pushRoutinesToFirebase()
    }

    fun getRoutineLogsForDate(dateKey: String): List<RoutineLog> = synchronized(lock) {
        data.routineLogs.filter { it.dateKey == dateKey }
    }

    fun isRoutineCompleted(routineId: Long, dateKey: String): Boolean = synchronized(lock) {
        data.routineLogs.any { it.routineId == routineId && it.dateKey == dateKey }
    }

    /** 날짜 하나에 대한 완료 체크를 토글한다(존재하면 삭제=미완료, 없으면 추가=완료). */
    fun toggleRoutineLog(routineId: Long, dateKey: String) = synchronized(lock) {
        if (data.routineLogs.any { it.routineId == routineId && it.dateKey == dateKey }) {
            data.routineLogs.removeAll { it.routineId == routineId && it.dateKey == dateKey }
        } else {
            data.routineLogs.add(RoutineLog(routineId, dateKey))
        }
        persist()
        pushRoutinesToFirebase()
    }

    /** RoutineEngine.currentStreak에 넘길 완료 날짜 집합. */
    fun getRoutineCompletedDateKeys(routineId: Long): Set<String> = synchronized(lock) {
        data.routineLogs.filter { it.routineId == routineId }.map { it.dateKey }.toSet()
    }

    /**
     * 루틴/로그를 Firebase JSON 배열 2개로 변환한다. 기기별 로컬 id를 그대로 실어보내면 다른 기기의
     * id 체계와 충돌하므로(캘린더가 dateKey+배열순서로 식별하는 것과 같은 이유), routines 배열 안에서의
     * 인덱스를 로그가 참조하는 "routineIndex"로 쓴다 — 실제 id는 반입하는 쪽에서 새로 배정한다.
     */
    private fun routinesToJsonArrays(): Pair<JSONArray, JSONArray> {
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
        return routinesArr to logsArr
    }

    /** JSON 배열 2개(routines, routineLogs)를 로컬 Routine/RoutineLog로 되돌린다 — 새 로컬 id를 배열 순서대로 새로 배정. */
    private fun routinesFromJsonArrays(routinesJson: JSONArray, logsJson: JSONArray): Pair<MutableList<Routine>, MutableList<RoutineLog>> {
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
        return newRoutines to newLogs
    }

    /** 루틴 파일 내보내기(사용자 요청, 2026-08-14) — Firebase 동기화 문서와 동일한 스키마를 그대로 재사용. */
    fun exportRoutinesBackupJson(): String = synchronized(lock) {
        val (routinesArr, logsArr) = routinesToJsonArrays()
        val root = JSONObject()
        root.put("routines", routinesArr)
        root.put("routineLogs", logsArr)
        root.toString(2)
    }

    /** 루틴 파일 가져오기 — 현재 루틴/로그를 파일 내용으로 전체 대체한다(syncRoutinesFromFirebase의 반입 로직과 동일). */
    fun importRoutinesBackupJson(json: String) {
        val root = JSONObject(json)
        val routinesJson = root.optJSONArray("routines") ?: JSONArray()
        val logsJson = root.optJSONArray("routineLogs") ?: JSONArray()
        synchronized(lock) {
            val (newRoutines, newLogs) = routinesFromJsonArrays(routinesJson, logsJson)
            data.routines.clear(); data.routines.addAll(newRoutines)
            data.routineLogs.clear(); data.routineLogs.addAll(newLogs)
            data.nextRoutineId = (newRoutines.maxOfOrNull { it.id } ?: 0L) + 1
            persist()
            pushRoutinesToFirebase()
        }
    }

    /** 변경 직후 fire-and-forget으로 Firebase에 전체 루틴 문서를 올린다(호출부는 이미 lock을 쥐고 있음). */
    private fun pushRoutinesToFirebase() {
        val ts = System.currentTimeMillis()
        data.routinesTs = ts
        val (routinesArr, logsArr) = routinesToJsonArrays()
        val url = data.fbDatabaseUrl; val key = data.fbApiKey
        Thread {
            com.phonelock.desktop.monitor.PomodoroSyncClient.writeRoutines(url, key, routinesArr, logsArr, ts)
        }.start()
    }

    /**
     * 루틴 화면 진입 시 호출 — 원격이 로컬보다 최신이면(문서 단위 LWW) 로컬을 덮어쓰고, 로컬이 더 최신이면
     * 반대로 원격에 푸시한다. 네트워크 호출을 포함하므로 호출부(UI)에서 백그라운드 스레드/코루틴에서 실행.
     */
    fun syncRoutinesFromFirebase() {
        val (url, key) = synchronized(lock) { data.fbDatabaseUrl to data.fbApiKey }
        val result = com.phonelock.desktop.monitor.PomodoroSyncClient.readRoutines(url, key) ?: return
        synchronized(lock) {
            if (result.ts > data.routinesTs) {
                val (newRoutines, newLogs) = routinesFromJsonArrays(result.routinesJson, result.logsJson)
                data.routines.clear(); data.routines.addAll(newRoutines)
                data.routineLogs.clear(); data.routineLogs.addAll(newLogs)
                data.nextRoutineId = (newRoutines.maxOfOrNull { it.id } ?: 0L) + 1
                data.routinesTs = result.ts
                persist()
            } else if (data.routinesTs > result.ts) {
                pushRoutinesToFirebase()
            }
        }
    }
}
