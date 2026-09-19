package com.phonelock.desktop.monitor

import com.phonelock.desktop.data.Group
import com.phonelock.desktop.data.Repository
import java.time.LocalDateTime

enum class LockReason { SCHEDULE, LIMIT, STUDY_LOCK }

data class LockResult(val locked: Boolean, val reason: LockReason? = null)

/**
 * "차단 규칙 수정·삭제 방지"(129차)가 [hour]시에 적용되는지 — 시작 시각 포함, 끝 시각 미포함이며
 * 자정을 넘는 범위(예: 22~6시)도 지원한다. 설정 화면이 *아직 저장하지 않은* 값으로 "이 변경이 방지를
 * 약화시키는가"를 미리 판정해야 해서, repository를 보는 [LockEvaluator.isWithinEditProtectionWindow]와
 * 로직이 갈리지 않도록 순수 함수로 빼두고 양쪽이 같이 쓴다.
 *
 * [startHour]==[endHour]는 "빈 범위"가 아니라 하루 종일 적용으로 본다 — 빈 범위로 해석하면 켜둔 채로
 * 방지가 통째로 사라져 설정이 조용히 무력화되기 때문.
 */
fun isEditProtectionHour(enabled: Boolean, startHour: Int, endHour: Int, hour: Int): Boolean {
    if (!enabled) return false
    if (startHour == endHour) return true
    return if (startHour < endHour) hour in startHour until endHour else hour >= startHour || hour < endHour
}

class LockEvaluator(private val repository: Repository) {

    /** 일일 한도와 같은 기준(dailyResetHour)으로 보정한 "오늘"의 요일 비트 인덱스. */
    fun effectiveTodayBitIndex(now: LocalDateTime = LocalDateTime.now()): Int {
        val effectiveNow = if (now.hour < repository.dailyResetHour) now.minusDays(1) else now
        return effectiveNow.dayOfWeek.value - 1
    }

    private fun isTodayInMask(mask: Int, now: LocalDateTime): Boolean {
        // 일일 한도와 같은 기준(dailyResetHour)으로 "오늘"을 판단해서 통계와 그룹 일정(요일)이 어긋나지 않게 한다.
        val bitIndex = effectiveTodayBitIndex(now)
        return (mask shr bitIndex) and 1 == 1
    }

    /**
     * 그룹 전체가 걸려있는 도중에 끄기를 시도해서 회유 멘트를 확인하는 중이면(groupOffPending) 여전히
     * 켜진 것으로 취급한다. 멘트를 끝까지 다 확인해야 실제로 꺼진다 (즉시 회피 방지).
     */
    private fun effectiveGroupEnabled(group: Group): Boolean = group.groupEnabled || group.groupOffPending

    /**
     * 그룹 자체가 켜져 있는지("그룹 목록" 화면의 스위치). 꺼져 있으면(그리고 패널티 대기 중도 아니면)
     * 스케줄/일일 한도/실행 확인 등 이 그룹의 모든 관리가 비활성 취급된다(겹치는 다른 그룹과의 소유권
     * 경쟁에서도 빠짐). 관리 종류별 개별 on/off와 요일 설정은 각자 evaluate()/isConfirmActiveNow() 등에서
     * 별도로 확인한다.
     */
    fun isGroupActive(group: Group, now: LocalDateTime = LocalDateTime.now()): Boolean =
        effectiveGroupEnabled(group) || isForceEnabled(group, now)

    /** 기간 지정 자동 강화(#7, 시험기간 등) — forceEnabledFrom~Until(포함) 사이면 groupEnabled를 껐어도
     *  켜진 것으로 강제 취급한다. 스누즈보다 우선한다(evaluate()/isCurrentlyRestricting() 참고). */
    private fun isForceEnabled(group: Group, now: LocalDateTime): Boolean {
        val from = group.forceEnabledFrom ?: return false
        val until = group.forceEnabledUntil ?: return false
        val today = now.toLocalDate().toString()
        return today >= from && today <= until
    }

    fun isForceEnabledNow(group: Group, now: LocalDateTime = LocalDateTime.now()): Boolean = isForceEnabled(group, now)

    /** 스누즈(#1)가 지금 적용 중인지 — 회유 절차 없이 그룹별 한도까지만 임시 해제할 수 있는 자기 승인
     *  예외. group.snoozeEnabled가 꺼져 있으면(87차, 안드로이드판과 대칭) scheduleEnabled와 같은 방식으로
     *  판정 자체를 건너뛰어 남아있는 스누즈 상태를 즉시 무시한다. 다른 기기의 스누즈도 반영하도록
     *  [Repository.syncedSnoozeUntil]을 거친다(네트워크 I/O 포함) — EnforcementService/SiteEnforcement의
     *  백그라운드 판정 경로에서만 호출할 것. */
    private fun isSnoozed(group: Group): Boolean {
        if (!group.snoozeEnabled) return false
        val until = repository.syncedSnoozeUntil(group)
        return until > 0 && System.currentTimeMillis() < until
    }

    /** UI 표시 전용(그룹 목록 "😴 스누즈 중" 배지) — Compose 리컴포지션마다 직접 호출되므로 네트워크 호출
     *  없이 로컬 값만 본다. 다른 기기의 스누즈는 [isSnoozed]가 판정 시점에 로컬로 병합·저장해둔 뒤에야
     *  이 함수에도 반영된다(즉시 반영 아님, 확인 레벨 동기화와 동일한 지연 특성). snoozeEnabled가 꺼져
     *  있으면 항상 false. */
    fun isSnoozeActive(group: Group): Boolean {
        if (!group.snoozeEnabled) return false
        val until = group.snoozedUntilEpochMillis ?: return false
        return System.currentTimeMillis() < until
    }

    /** 스케줄(시간대 차단) 관리 종류가 켜져 있고, 오늘이 그 요일에 해당하는지. */
    fun isScheduleTypeActiveToday(group: Group, now: LocalDateTime = LocalDateTime.now()): Boolean =
        group.scheduleEnabled && isTodayInMask(group.scheduleDaysMask, now)

    /** 일일 사용한도 관리 종류가 켜져 있고(한도가 설정돼 있고), 오늘이 그 요일에 해당하는지. */
    fun isLimitTypeActiveToday(group: Group, now: LocalDateTime = LocalDateTime.now()): Boolean =
        group.dailyLimitSeconds != null && isTodayInMask(group.dailyLimitDaysMask, now)

    /** 실행 확인 관리 종류가 켜져 있고, 오늘이 그 요일에 해당하는지. */
    fun isConfirmTypeActiveToday(group: Group, now: LocalDateTime = LocalDateTime.now()): Boolean =
        group.confirmEnabled && isTodayInMask(group.confirmDaysMask, now)

    /** UI 표기/그룹 전체 off-패널티 판정용: 스케줄/일일한도/실행확인 중 하나라도 오늘 적용되는 요일인지. */
    fun isAnyManagementActiveToday(group: Group, now: LocalDateTime = LocalDateTime.now()): Boolean =
        isScheduleTypeActiveToday(group, now) || isLimitTypeActiveToday(group, now) || isConfirmTypeActiveToday(group, now)

    private fun isWithinScheduleWindow(group: Group, now: LocalDateTime): Boolean {
        if (!isScheduleTypeActiveToday(group, now)) return false
        val start = group.scheduleStartMinute ?: return false
        val end = group.scheduleEndMinute ?: return false
        val nowMinute = now.hour * 60 + now.minute
        return if (start <= end) {
            nowMinute in start until end
        } else {
            nowMinute >= start || nowMinute < end
        }
    }

    /** 편집 화면에서 "지금 이 시간대 차단에 걸려있는 도중인지" 판정할 때 쓰는 공개 버전. */
    fun isScheduleWindowActiveNow(group: Group, now: LocalDateTime = LocalDateTime.now()): Boolean =
        isWithinScheduleWindow(group, now)

    /**
     * "차단 규칙 수정·삭제 방지"가 지금 적용되는 시간대 안인지. 128차까지는 11시~23시로 하드코딩
     * 이었고, 129차부터 설정(규칙 카테고리)에서 on/off와 시작/끝 시각을 정한다. 판정은 [isEditProtectionHour].
     */
    fun isWithinEditProtectionWindow(now: LocalDateTime = LocalDateTime.now()): Boolean =
        isEditProtectionHour(
            repository.editProtectionEnabled,
            repository.editProtectionStartHour,
            repository.editProtectionEndHour,
            now.hour
        )

    /** 방지 시간대 밖이면 그룹 수정/삭제/on-off가 회유 멘트 없이 바로 적용된다. */
    fun isWithinEditExemptionWindow(now: LocalDateTime = LocalDateTime.now()): Boolean =
        !isWithinEditProtectionWindow(now)

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

    private fun isLimitExceeded(group: Group, now: LocalDateTime): Boolean {
        val limit = group.dailyLimitSeconds ?: return false
        if (!isTodayInMask(group.dailyLimitDaysMask, now)) return false
        if (!isWithinApplyWindow(group.dailyLimitApplyStartMinute, group.dailyLimitApplyEndMinute, now)) return false
        val usedSeconds = repository.getTodayUsageSeconds(group.id)
        return usedSeconds >= limit
    }

    /**
     * 실행 확인이 지금 이 순간 적용되어야 하는 상태인지 (그룹 전체 켜짐 + 실행확인 사용 on + 오늘 요일 + 적용 시간대 안).
     *
     * [ignoreTemporaryUnlock]=true면 잠깐 풀기(스누즈)/뽀모도로 휴식 같은 "임시 해제"를 없는 것으로 보고
     * 판정한다(129차) — 임시 해제 중에 영구 설정을 약화시키거나 규칙을 지우는 꼼수를 막는
     * [detectWeakeningEdit]/[requiresDeleteGate] 전용이다. 실제 차단 판정 경로는 기본값(false) 그대로
     * 임시 해제를 존중한다.
     */
    fun isConfirmActiveNow(
        group: Group,
        now: LocalDateTime = LocalDateTime.now(),
        ignoreTemporaryUnlock: Boolean = false
    ): Boolean =
        (ignoreTemporaryUnlock || isForceEnabled(group, now) || !isSnoozed(group)) &&
            isGroupActive(group, now) &&
            group.confirmEnabled &&
            isTodayInMask(group.confirmDaysMask, now) &&
            isWithinApplyWindow(group.confirmApplyStartMinute, group.confirmApplyEndMinute, now)

    /** 공부앱(별도 웹앱)의 뽀모도로 휴식 시간 동안 이 그룹을 임시로 해제할지. group.groupEnabled 등
     *  영구 상태는 전혀 건드리지 않고 판정 시점에만 조회하므로 detectWeakeningEdit와는 접점이 없다. */
    private fun isPomodoroUnlocked(group: Group): Boolean =
        group.pomodoroUnlockEnabled &&
            PomodoroSyncClient.isBreakActive(repository.fbDatabaseUrl, repository.fbApiKey)

    /** isPomodoroUnlocked의 공개 버전 — 오버레이 표시처럼 판정 로직 밖(EnforcementService 등)에서도
     *  "지금 뽀모도로 휴식으로 임시 해제된 상태인지" 확인해야 할 때 쓴다. */
    fun isPomodoroUnlockActive(group: Group): Boolean = isPomodoroUnlocked(group)

    /** 그룹이 지금 이 순간 실제로 제한(시간대 차단 또는 일일 한도 초과)에 걸려있는 상태인지.
     *  [ignoreTemporaryUnlock]의 의미는 [isConfirmActiveNow] 참고. */
    fun isCurrentlyRestricting(
        group: Group,
        now: LocalDateTime = LocalDateTime.now(),
        ignoreTemporaryUnlock: Boolean = false
    ): Boolean {
        if (!ignoreTemporaryUnlock && isPomodoroUnlocked(group)) return false
        if (!ignoreTemporaryUnlock && !isForceEnabled(group, now) && isSnoozed(group)) return false
        if (!isGroupActive(group, now)) return false
        val inWindow = isWithinScheduleWindow(group, now)
        val limitExceeded = isLimitExceeded(group, now)
        return inWindow || limitExceeded
    }

    fun evaluate(group: Group, now: LocalDateTime = LocalDateTime.now()): LockResult {
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
    fun detectWeakeningEdit(
        original: Group,
        updated: Group,
        originalProcessNames: Set<String>,
        updatedProcessNames: Set<String>,
        originalDomains: Set<String>,
        updatedDomains: Set<String>,
        now: LocalDateTime = LocalDateTime.now()
    ): Boolean {
        // 0. 그룹 전체가 꺼져있으면(패널티 대기 중도 아니면) 아무 관리도 적용되고 있지 않으므로
        // 무엇을 바꾸든 약화가 아니다.
        if (!isGroupActive(original)) return false

        // 0-1. "차단 규칙 수정·삭제 방지" 시간대 밖이면 회유 절차 없이 자유롭게 수정할 수 있다.
        if (isWithinEditExemptionWindow(now)) return false

        // 0-2(129차에 삭제된 예외): 55차엔 "스누즈 중이면 이미 자기 승인으로 해제한 상태"라는 이유로
        // 스누즈 중 모든 약화 수정을 통과시켰다. 이게 "잠깐 풀기 → 그 사이에 잠깐 풀기 시간/횟수를
        // 늘림 → 다시 잠깐 풀기"로 무한히 잠금을 해제하는 꼼수의 통로였다. 잠깐 풀기는 *임시* 해제일
        // 뿐이고 여기서 막는 건 *영구* 설정 약화라 성격이 다르므로 예외를 없앴다 — 아래 판정들은
        // ignoreTemporaryUnlock=true로 "임시 해제가 없었다면 지금 걸려있었을 상태"를 기준으로 본다.

        // 1. 확인마다 늘어나는 시간을 줄임
        if (updated.waitIncrementSeconds < original.waitIncrementSeconds) return true

        // 1-1. 재확인까지 유예시간을 늘림 (재확인을 덜 하게 되어 사실상 완화)
        if (updated.confirmCooldownSeconds > original.confirmCooldownSeconds) return true

        // 1-2. 초기 대기시간을 줄임
        if (updated.initialWaitSeconds < original.initialWaitSeconds) return true

        // 1-3. 실행 확인 자체를 꺼버림 (쌓인 대기시간 전체를 한 번에 무력화하는 가장 손쉬운 우회)
        if (original.confirmEnabled && !updated.confirmEnabled) return true

        // 1-4. 레벨 차감을 새로 켬 (켜지면 레벨이 줄어들어 대기시간 완화)
        if (!original.levelDecayEnabled && updated.levelDecayEnabled) return true

        // 1-5. 레벨 차감 간격을 줄임 (더 빨리 줄어들어 대기시간 완화)
        if (original.levelDecayEnabled && updated.levelDecayEnabled &&
            updated.levelDecayIntervalSeconds < original.levelDecayIntervalSeconds) return true

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
        if (isConfirmActiveNow(original, now, ignoreTemporaryUnlock = true) &&
            !isConfirmActiveNow(updated, now, ignoreTemporaryUnlock = true) && updated.confirmEnabled
        ) return true

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

        // 6. 지금 제한이 걸린 상태에서 포함된 프로그램/사이트를 뺌
        if (isCurrentlyRestricting(original, now, ignoreTemporaryUnlock = true)) {
            val removedProcessNames = originalProcessNames - updatedProcessNames
            val removedDomains = originalDomains - updatedDomains
            if (removedProcessNames.isNotEmpty() || removedDomains.isNotEmpty()) return true
        }

        // 7. 오늘 요일 제한이 실제로 걸려있는 도중에 "스케줄" 관리 자체를 이 화면에서 바로 끔
        // (그룹 목록 화면의 스위치는 회유 절차를 거치지만, 이 화면에서 곧장 끄면 그 절차를 우회하게 된다)
        if (original.scheduleEnabled && !updated.scheduleEnabled &&
            isScheduleTypeActiveToday(original, now)
        ) return true

        // 8. 잠깐 풀기(스누즈)를 더 헐겁게 만듦 — 이 규칙 하나하나가 "회유 절차를 생략하는 합법적
        // 탈출구"를 넓히는 것이라, 지금 뭔가 걸려있는지와 무관하게 약화로 본다(1-x 대기시간 항목들과
        // 같은 취급). 꺼져 있는 동안 숫자만 바꿔두는 건 탈출구가 없으니 막지 않고, 실제로 켜는
        // 순간(off→on)에 걸린다.
        if (!original.snoozeEnabled && updated.snoozeEnabled) return true
        if (original.snoozeEnabled && updated.snoozeMinutes > original.snoozeMinutes) return true
        if (original.snoozeEnabled && updated.snoozeDailyLimit > original.snoozeDailyLimit) return true

        // 9. 기간 지정 자동 강화(시험기간 등)가 지금 걸려있는데 그 기간을 지우거나 오늘이 빠지게 좁힘.
        // 이 기능은 "나중에 후회할 즉흥적 판단을 미리 막아두는" 안전장치라(39차 DECISIONS), 그 즉흥적
        // 판단으로 안전장치 자체를 걷어낼 수 있으면 존재 의미가 없다.
        if (isForceEnabled(original, now) && !isForceEnabled(updated, now)) return true

        // 10. 뽀모도로 휴식 임시 해제를 새로 켬 — 공부앱에서 휴식 버튼만 누르면 이 규칙이 통째로
        // 풀리는 탈출구가 새로 생기는 것이므로 8번(스누즈 새로 켜기)과 같은 취급.
        if (!original.pomodoroUnlockEnabled && updated.pomodoroUnlockEnabled) return true

        return false
    }

    /**
     * 이 규칙을 지금 삭제하는 게 "걸려있는 제한을 통째로 없애는" 행위라 회유 멘트 절차를 거쳐야 하는지.
     *
     * 128차까지는 삭제 버튼이 [isCurrentlyRestricting]만 봤는데, 이 함수는 시간대 차단/일일한도만
     * "제한"으로 치기 때문에 ① 실행 확인만 설정한 규칙은 대낮에도 버튼 한 번에 지워졌고(편집으로
     * 실행 확인을 끄려면 절차를 거쳐야 하는데도) ② 잠깐 풀기 중에는 아무 규칙이나 무방비로 지워졌다.
     * 둘 다 "편집보다 삭제가 더 쉬운" 역전이라 129차에 조건을 맞췄다.
     */
    fun requiresDeleteGate(group: Group, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (isWithinEditExemptionWindow(now)) return false
        return isCurrentlyRestricting(group, now, ignoreTemporaryUnlock = true) ||
            isConfirmActiveNow(group, now, ignoreTemporaryUnlock = true)
    }
}
