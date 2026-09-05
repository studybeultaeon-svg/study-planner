package com.phonelock.app.service

import com.phonelock.app.data.AppGroup
import com.phonelock.app.data.PhoneLockRepository
import java.time.LocalDateTime

enum class LockReason { SCHEDULE, LIMIT, REELS, SHORTS, STUDY_LOCK }

data class LockResult(val locked: Boolean, val reason: LockReason? = null)

/** 매일 이 시각(23시)부터 다음날 이 시각(11시) 전까지는 그룹 내용 수정/삭제, on/off 전환에 회유 멘트 절차를 요구하지 않는다. */
private const val EDIT_EXEMPTION_START_HOUR = 23
private const val EDIT_EXEMPTION_END_HOUR = 11

/**
 * 그룹의 스케줄/일일 한도 조건을 평가한다. groupId는 순수 데이터 조회이므로
 * repository의 suspend 함수를 호출하는 쪽(서비스)에서 코루틴 컨텍스트로 감싼다.
 */
class LockEvaluator(private val repository: PhoneLockRepository) {

    /** 일일 한도와 같은 기준(dailyResetHour)으로 보정한 "오늘"의 요일 비트 인덱스. */
    fun effectiveTodayBitIndex(now: LocalDateTime = LocalDateTime.now()): Int {
        val effectiveNow = if (now.hour < repository.dailyResetHour) now.minusDays(1) else now
        return effectiveNow.dayOfWeek.value - 1 // MONDAY=1 -> 0
    }

    private fun isTodayInMask(mask: Int, now: LocalDateTime): Boolean {
        // bit 0 = 월요일 ... bit 6 = 일요일. 일일 한도와 같은 기준(dailyResetHour)으로 "오늘"을 판단해서
        // 통계가 따르는 요일과 그룹 일정이 따르는 요일이 서로 어긋나지 않게 한다.
        val bitIndex = effectiveTodayBitIndex(now)
        return (mask shr bitIndex) and 1 == 1
    }

    /**
     * 그룹 전체가 걸려있는 도중에 끄기를 시도해서 회유 멘트를 확인하는 중이면(groupOffPending) 여전히
     * 켜진 것으로 취급한다. 멘트를 끝까지 다 확인해야 실제로 꺼진다 (즉시 회피 방지).
     */
    private fun effectiveGroupEnabled(group: AppGroup): Boolean = group.groupEnabled || group.groupOffPending

    /**
     * 그룹 자체가 켜져 있는지("그룹 목록" 화면의 스위치). 꺼져 있으면(그리고 패널티 대기 중도 아니면)
     * 스케줄/일일 한도/실행 확인 등 이 그룹의 모든 관리가 비활성 취급된다(겹치는 다른 그룹과의 소유권
     * 경쟁에서도 빠짐). 관리 종류별 개별 on/off와 요일 설정은 각자 evaluate()/isConfirmActiveNow() 등에서
     * 별도로 확인한다.
     */
    fun isGroupActive(group: AppGroup, now: LocalDateTime = LocalDateTime.now()): Boolean =
        effectiveGroupEnabled(group) || isForceEnabled(group, now)

    /** 기간 지정 자동 강화(#7, 시험기간 등) — forceEnabledFrom~Until(포함) 사이면 groupEnabled를 껐어도
     *  켜진 것으로 강제 취급한다. 스누즈보다 우선한다(evaluate()/isCurrentlyRestricting() 참고). */
    private fun isForceEnabled(group: AppGroup, now: LocalDateTime): Boolean {
        val from = group.forceEnabledFrom ?: return false
        val until = group.forceEnabledUntil ?: return false
        val today = now.toLocalDate().toString()
        return today >= from && today <= until
    }

    fun isForceEnabledNow(group: AppGroup, now: LocalDateTime = LocalDateTime.now()): Boolean = isForceEnabled(group, now)

    /** 스누즈(#1)가 지금 적용 중인지 — 회유 절차 없이 그룹별 한도까지만 임시 해제할 수 있는 자기 승인
     *  예외. group.snoozeEnabled가 꺼져 있으면(87차) scheduleEnabled와 같은 방식으로 판정 자체를 건너뛰어
     *  남아있는 스누즈 상태를 즉시 무시한다. 다른 기기의 스누즈도 반영하도록
     *  [PhoneLockRepository.syncedSnoozeUntil]을 거친다(네트워크 I/O 포함) — AppMonitorAccessibilityService의
     *  백그라운드 판정 경로에서만 호출할 것. */
    private suspend fun isSnoozed(group: AppGroup): Boolean {
        if (!group.snoozeEnabled) return false
        val until = repository.syncedSnoozeUntil(group)
        return until > 0 && System.currentTimeMillis() < until
    }

    /** UI 표시 전용(그룹 목록 "😴 스누즈 중" 배지) — Compose 리컴포지션마다 직접 호출되므로 네트워크 호출
     *  없이 로컬 값만 본다. 다른 기기의 스누즈는 [isSnoozed]가 판정 시점에 로컬로 병합·저장해둔 뒤에야
     *  이 함수에도 반영된다(즉시 반영 아님, 확인 레벨 동기화와 동일한 지연 특성). snoozeEnabled가 꺼져
     *  있으면 항상 false. */
    fun isSnoozeActive(group: AppGroup): Boolean {
        if (!group.snoozeEnabled) return false
        val until = group.snoozedUntilEpochMillis ?: return false
        return System.currentTimeMillis() < until
    }

    /** 스케줄(시간대 차단) 관리 종류가 켜져 있고, 오늘이 그 요일에 해당하는지. */
    fun isScheduleTypeActiveToday(group: AppGroup, now: LocalDateTime = LocalDateTime.now()): Boolean =
        group.scheduleEnabled && isTodayInMask(group.scheduleDaysMask, now)

    /** 일일 사용한도 관리 종류가 켜져 있고(한도가 설정돼 있고), 오늘이 그 요일에 해당하는지. */
    fun isLimitTypeActiveToday(group: AppGroup, now: LocalDateTime = LocalDateTime.now()): Boolean =
        group.dailyLimitSeconds != null && isTodayInMask(group.dailyLimitDaysMask, now)

    /** 실행 확인 관리 종류가 켜져 있고, 오늘이 그 요일에 해당하는지. */
    fun isConfirmTypeActiveToday(group: AppGroup, now: LocalDateTime = LocalDateTime.now()): Boolean =
        group.confirmEnabled && isTodayInMask(group.confirmDaysMask, now)

    /** UI 표기/그룹 전체 off-패널티 판정용: 스케줄/일일한도/실행확인 중 하나라도 오늘 적용되는 요일인지. */
    fun isAnyManagementActiveToday(group: AppGroup, now: LocalDateTime = LocalDateTime.now()): Boolean =
        isScheduleTypeActiveToday(group, now) || isLimitTypeActiveToday(group, now) || isConfirmTypeActiveToday(group, now)

    private fun isWithinScheduleWindow(group: AppGroup, now: LocalDateTime): Boolean {
        if (!isScheduleTypeActiveToday(group, now)) return false
        val start = group.scheduleStartMinute ?: return false
        val end = group.scheduleEndMinute ?: return false
        val nowMinute = now.hour * 60 + now.minute
        return if (start <= end) {
            nowMinute in start until end
        } else {
            // 자정을 넘는 스케줄 (예: 22:00 ~ 06:00)
            nowMinute >= start || nowMinute < end
        }
    }

    /** 편집 화면에서 "지금 이 시간대 차단에 걸려있는 도중인지" 판정할 때 쓰는 공개 버전. */
    fun isScheduleWindowActiveNow(group: AppGroup, now: LocalDateTime = LocalDateTime.now()): Boolean =
        isWithinScheduleWindow(group, now)

    /** 매일 밤 11시부터 다음날 오전 11시 전까지는 그룹 수정/삭제/on-off가 회유 멘트 없이 바로 적용된다. */
    fun isWithinEditExemptionWindow(now: LocalDateTime = LocalDateTime.now()): Boolean =
        now.hour >= EDIT_EXEMPTION_START_HOUR || now.hour < EDIT_EXEMPTION_END_HOUR

    /** startMinute/endMinute이 둘 다 null이면 "적용 시간대" 미설정으로 보고 하루 종일 적용된 것으로 취급한다. */
    private fun isWithinApplyWindow(startMinute: Int?, endMinute: Int?, now: LocalDateTime): Boolean {
        if (startMinute == null || endMinute == null) return true
        val nowMinute = now.hour * 60 + now.minute
        return if (startMinute <= endMinute) {
            nowMinute in startMinute until endMinute
        } else {
            nowMinute >= startMinute || nowMinute < endMinute
        }
    }

    private suspend fun isLimitExceeded(group: AppGroup, now: LocalDateTime): Boolean {
        val limit = group.dailyLimitSeconds ?: return false
        if (!isTodayInMask(group.dailyLimitDaysMask, now)) return false
        if (!isWithinApplyWindow(group.dailyLimitApplyStartMinute, group.dailyLimitApplyEndMinute, now)) return false
        val usedSeconds = repository.getTodayUsageSeconds(group.id)
        return usedSeconds >= limit
    }

    /** 실행 확인이 지금 이 순간 적용되어야 하는 상태인지 (그룹 전체 켜짐 + 실행확인 사용 on + 오늘 요일 + 적용 시간대 안). */
    suspend fun isConfirmActiveNow(group: AppGroup, now: LocalDateTime = LocalDateTime.now()): Boolean =
        (isForceEnabled(group, now) || !isSnoozed(group)) &&
            isGroupActive(group, now) &&
            group.confirmEnabled &&
            isTodayInMask(group.confirmDaysMask, now) &&
            isWithinApplyWindow(group.confirmApplyStartMinute, group.confirmApplyEndMinute, now)

    /** 공부앱(별도 웹앱)의 뽀모도로 휴식 시간 동안 이 그룹을 임시로 해제할지. group.groupEnabled 등
     *  영구 상태는 전혀 건드리지 않고 판정 시점에만 조회하므로 detectWeakeningEdit와는 접점이 없다. */
    private suspend fun isPomodoroUnlocked(group: AppGroup): Boolean =
        group.pomodoroUnlockEnabled &&
            PomodoroSyncClient.isBreakActive(repository.fbDatabaseUrl, repository.fbApiKey)

    /** isPomodoroUnlocked의 공개 버전 — 오버레이 표시처럼 "지금 뽀모도로 휴식으로 임시 해제된 상태인지"를
     *  판정 로직 밖(서비스)에서도 확인해야 할 때 쓴다. */
    suspend fun isPomodoroUnlockActive(group: AppGroup): Boolean = isPomodoroUnlocked(group)

    /** 그룹이 지금 이 순간 실제로 제한(시간대 차단 또는 일일 한도 초과)에 걸려있는 상태인지. */
    suspend fun isCurrentlyRestricting(group: AppGroup, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (isPomodoroUnlocked(group)) return false
        if (!isForceEnabled(group, now) && isSnoozed(group)) return false
        if (!isGroupActive(group, now)) return false
        val inWindow = isWithinScheduleWindow(group, now)
        val limitExceeded = isLimitExceeded(group, now)
        return inWindow || limitExceeded
    }

    suspend fun evaluate(group: AppGroup, now: LocalDateTime = LocalDateTime.now()): LockResult {
        if (isPomodoroUnlocked(group)) return LockResult(false)
        if (!isForceEnabled(group, now) && isSnoozed(group)) return LockResult(false)
        if (!isGroupActive(group, now)) return LockResult(false)

        val inWindow = isWithinScheduleWindow(group, now)
        val limitExceeded = isLimitExceeded(group, now)

        if (inWindow) return LockResult(true, LockReason.SCHEDULE)
        if (limitExceeded) return LockResult(true, LockReason.LIMIT)
        return LockResult(false)
    }

    /**
     * 지금 실제로 제한이 걸려있는 도중에 그 제한을 약화/회피시키는 "꼼수성" 수정인지 판정한다.
     * 해당되면 그룹 일정 on/off처럼 10분 감시 후 적용 방식이 걸린다.
     */
    suspend fun detectWeakeningEdit(
        original: AppGroup,
        updated: AppGroup,
        originalPackages: Set<String>,
        updatedPackages: Set<String>,
        originalSites: Set<String>,
        updatedSites: Set<String>,
        now: LocalDateTime = LocalDateTime.now()
    ): Boolean {
        // 0. 그룹 전체가 꺼져있으면(패널티 대기 중도 아니면) 아무 관리도 적용되고 있지 않으므로
        // 무엇을 바꾸든 약화가 아니다.
        if (!isGroupActive(original)) return false

        // 0-1. 매일 밤 11시~오전 11시는 회유 절차 없이 자유롭게 수정할 수 있다.
        if (isWithinEditExemptionWindow(now)) return false

        // 0-2. 스누즈(#1) 중이면 이미 회유 절차 없이 자기 승인으로 모든 관리를 임시 해제한 상태이므로,
        // 그 동안의 설정 변경도 다시 회유 절차를 거칠 필요가 없다 (기간 지정 자동 강화가 우선하면 예외).
        if (!isForceEnabled(original, now) && isSnoozed(original)) return false

        // 1. 확인마다 늘어나는 시간을 줄임
        if (updated.waitIncrementSeconds < original.waitIncrementSeconds) return true

        // 1-1. 재확인까지 유예시간을 늘림 (재확인을 덜 하게 되어 사실상 완화)
        if (updated.confirmCooldownSeconds > original.confirmCooldownSeconds) return true

        // 1-2. 초기 대기시간을 줄임
        if (updated.initialWaitSeconds < original.initialWaitSeconds) return true

        // 1-3. 실행 확인 자체를 꺼버림 (쌓인 대기시간 전체를 한 번에 무력화하는 가장 손쉬운 우회)
        if (original.confirmEnabled && !updated.confirmEnabled) return true

        // 1-4. 레벨 차감을 꺼져있다가 새로 켜거나, 차감 간격을 줄임(더 빨리 깎이게) — 둘 다 사실상 대기시간 완화
        if (!original.levelDecayEnabled && updated.levelDecayEnabled) return true
        if (original.levelDecayEnabled && updated.levelDecayIntervalSeconds < original.levelDecayIntervalSeconds) return true

        // 2. 일일 한도가 이미 다 찼는데 늘리거나 없앰
        val originalLimit = original.dailyLimitSeconds
        if (originalLimit != null) {
            val usedSeconds = repository.getTodayUsageSeconds(original.id)
            val limitReached = usedSeconds >= originalLimit
            val updatedLimit = updated.dailyLimitSeconds
            if (limitReached && (updatedLimit == null || updatedLimit > originalLimit)) return true

            // 2-1. 지금 한도 초과로 걸려있는데(적용 시간대 안이라 실제로 잠긴 상태) 적용 시간대를 좁혀서
            // 지금 시각이 범위 밖으로 빠지게 함
            val originalLimitRestricting = limitReached &&
                isWithinApplyWindow(original.dailyLimitApplyStartMinute, original.dailyLimitApplyEndMinute, now)
            if (originalLimitRestricting &&
                !isWithinApplyWindow(updated.dailyLimitApplyStartMinute, updated.dailyLimitApplyEndMinute, now)
            ) return true

            // 2-2. 오늘이 한도 적용 요일인데 그 요일만 뺌
            if (isTodayInMask(original.dailyLimitDaysMask, now) && !isTodayInMask(updated.dailyLimitDaysMask, now)) return true
        }

        // 3. 지금 실행 확인이 적용되어 확인창이 뜨는 상태인데 적용 시간대를 좁혀서 지금 시각이
        // 범위 밖으로 빠지게 함 (실행 확인을 통째로 끄는 것과 같은 효과의 회피 수단)
        if (isConfirmActiveNow(original, now) && !isConfirmActiveNow(updated, now) && updated.confirmEnabled) return true

        // 3-1. 오늘이 실행확인 적용 요일인데 그 요일만 뺌
        if (original.confirmEnabled &&
            isTodayInMask(original.confirmDaysMask, now) && !isTodayInMask(updated.confirmDaysMask, now)
        ) return true

        // 4. 지금 차단 시간대에 걸려있는데 시간대를 바꿈 (단, 스케줄 관리 자체가 꺼져있으면 시간대는
        // 어차피 아무것도 제한하고 있지 않으므로 검사하지 않는다)
        val currentlyInWindow = original.scheduleEnabled && isScheduleWindowActiveNow(original, now)
        if (currentlyInWindow &&
            (updated.scheduleStartMinute != original.scheduleStartMinute || updated.scheduleEndMinute != original.scheduleEndMinute)
        ) return true

        // 5. 오늘이 스케줄 적용 요일에 해당하는데 그 요일만 뺌 (전체 on/off가 아니라 요일 체크박스만 건드리는 경우).
        if (original.scheduleEnabled) {
            val bitIndex = effectiveTodayBitIndex(now)
            val todayBefore = (original.scheduleDaysMask shr bitIndex) and 1 == 1
            val todayAfter = (updated.scheduleDaysMask shr bitIndex) and 1 == 1
            if (todayBefore && !todayAfter) return true
        }

        // 6. 지금 제한이 걸린 상태에서 포함된 앱/사이트를 뺌
        if (isCurrentlyRestricting(original, now)) {
            val removedPackages = originalPackages - updatedPackages
            val removedSites = originalSites - updatedSites
            if (removedPackages.isNotEmpty() || removedSites.isNotEmpty()) return true
        }

        // 7. 오늘 요일 제한이 실제로 걸려있는 도중에 "스케줄" 관리 자체를 이 화면에서 바로 끔
        // (그룹 목록 화면의 스위치는 회유 절차를 거치지만, 이 화면에서 곧장 끄면 그 절차를 우회하게 된다)
        if (original.scheduleEnabled && !updated.scheduleEnabled &&
            isScheduleTypeActiveToday(original, now)
        ) return true

        return false
    }
}
