package com.phonelock.app.data

import java.time.LocalDate

/**
 * "모임"(소셜 그룹) 관련 [PhoneLockRepository] 확장 함수 모음 — 82차 감사 후속 리팩토링으로
 * PhoneLockRepository.kt(당시 2053줄)에서 분리했다. 클래스 자체는 그대로이고 파일만 나눴다
 * (DECISIONS.md "82차 God Object 파일 분리" 참고). SocialGroupSyncClient의 얇은 pass-through라
 * groups/{id}/... 데이터는 로컬에 캐싱/영속화하지 않고 화면 진입 시마다 Firebase에서 직접 읽는다.
 */

suspend fun PhoneLockRepository.createSocialGroup(name: String) =
    com.phonelock.app.service.SocialGroupSyncClient.createGroup(fbDatabaseUrl, fbApiKey, name)

suspend fun PhoneLockRepository.joinSocialGroup(code: String) =
    com.phonelock.app.service.SocialGroupSyncClient.joinGroupByCode(fbDatabaseUrl, fbApiKey, code)

suspend fun PhoneLockRepository.leaveSocialGroup(groupId: String) =
    com.phonelock.app.service.SocialGroupSyncClient.leaveGroup(fbDatabaseUrl, fbApiKey, groupId)

suspend fun PhoneLockRepository.deleteSocialGroup(groupId: String) =
    com.phonelock.app.service.SocialGroupSyncClient.deleteGroup(fbDatabaseUrl, fbApiKey, groupId)

suspend fun PhoneLockRepository.readMySocialGroupIds() =
    com.phonelock.app.service.SocialGroupSyncClient.readMyGroupIds(fbDatabaseUrl, fbApiKey)

suspend fun PhoneLockRepository.readSocialGroupInfo(groupId: String) =
    com.phonelock.app.service.SocialGroupSyncClient.readGroupInfo(fbDatabaseUrl, fbApiKey, groupId)

suspend fun PhoneLockRepository.readSocialGroupMembers(groupId: String) =
    com.phonelock.app.service.SocialGroupSyncClient.readGroupMembers(fbDatabaseUrl, fbApiKey, groupId)

/** 77차: 관리자/모임장 시스템 — 관리자 목록 조회, 승격/해제(모임장만), 멤버 내쫓기, 이름/코드 수정(모임장·관리자). */
suspend fun PhoneLockRepository.readSocialGroupAdmins(groupId: String) =
    com.phonelock.app.service.SocialGroupSyncClient.readGroupAdmins(fbDatabaseUrl, fbApiKey, groupId)
suspend fun PhoneLockRepository.setSocialGroupAdmin(groupId: String, targetUid: String, isAdmin: Boolean) =
    com.phonelock.app.service.SocialGroupSyncClient.setGroupAdmin(fbDatabaseUrl, fbApiKey, groupId, targetUid, isAdmin)
suspend fun PhoneLockRepository.kickSocialGroupMember(groupId: String, targetUid: String) =
    com.phonelock.app.service.SocialGroupSyncClient.kickMember(fbDatabaseUrl, fbApiKey, groupId, targetUid)
suspend fun PhoneLockRepository.updateSocialGroupName(groupId: String, newName: String) =
    com.phonelock.app.service.SocialGroupSyncClient.updateGroupName(fbDatabaseUrl, fbApiKey, groupId, newName)
suspend fun PhoneLockRepository.regenerateSocialGroupInviteCode(groupId: String) =
    com.phonelock.app.service.SocialGroupSyncClient.regenerateInviteCode(fbDatabaseUrl, fbApiKey, groupId)

suspend fun PhoneLockRepository.readSocialGroupStats(groupId: String) =
    com.phonelock.app.service.SocialGroupSyncClient.readGroupStats(fbDatabaseUrl, fbApiKey, groupId)

/** "모임 랭킹"(82차, §11) — 내 회유 멘트 저항률을 이 모임에 올린다. */
suspend fun PhoneLockRepository.pushMyQuoteStatToGroup(groupId: String) {
    val outcomes = getAllQuoteOutcomesOnce()
    if (outcomes.isEmpty()) return
    val stopRate = Math.round(outcomes.count { it.choice == "STOP" } * 100.0 / outcomes.size).toInt()
    val displayName = com.phonelock.app.service.AccountSyncClient.myDisplayName(fbDatabaseUrl, fbApiKey)
    com.phonelock.app.service.SocialGroupSyncClient.writeMyQuoteStat(fbDatabaseUrl, fbApiKey, groupId, displayName, stopRate, outcomes.size)
}

suspend fun PhoneLockRepository.readSocialGroupQuoteStats(groupId: String) =
    com.phonelock.app.service.SocialGroupSyncClient.readQuoteStats(fbDatabaseUrl, fbApiKey, groupId)

/** 모임장 공지사항(82차, §9). */
suspend fun PhoneLockRepository.readSocialGroupAnnouncement(groupId: String) =
    com.phonelock.app.service.SocialGroupSyncClient.readAnnouncement(fbDatabaseUrl, fbApiKey, groupId)

suspend fun PhoneLockRepository.writeSocialGroupAnnouncement(groupId: String, text: String): Result<Unit> {
    val myName = com.phonelock.app.service.AccountSyncClient.myDisplayName(fbDatabaseUrl, fbApiKey)
    return com.phonelock.app.service.SocialGroupSyncClient.writeAnnouncement(fbDatabaseUrl, fbApiKey, groupId, text, myName)
}

/** 모임 공동 목표(82차, §9). */
suspend fun PhoneLockRepository.readSocialGroupGoal(groupId: String) =
    com.phonelock.app.service.SocialGroupSyncClient.readGoal(fbDatabaseUrl, fbApiKey, groupId)

suspend fun PhoneLockRepository.writeSocialGroupGoal(groupId: String, targetMinutes: Int) =
    com.phonelock.app.service.SocialGroupSyncClient.writeGoal(fbDatabaseUrl, fbApiKey, groupId, targetMinutes)

/**
 * 내 루틴(오늘 예정분+완료여부)/공부시간·진행률/스트릭/오늘 일정/공부중 여부/현재 작동 중인 관리 그룹을
 * 계산해 이 모임에 올린다. 설정에서 끈 항목은 SocialGroupSyncClient가 아예 필드 생략하고 쓰므로,
 * 여기선 계산만 해서 넘긴다. 공유 설정은 62차의 앱 전체 공통 토글에서 75차+에 모임별 설정
 * (`preferences.groupShareSettings(groupId)`)으로 바뀌었다.
 */
suspend fun PhoneLockRepository.pushMySocialStats(groupId: String) {
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

    val share = preferences.groupShareSettings(groupId)
    val hiddenFromUids = preferences.hiddenFromUidsFor(groupId)

    runCatching {
        com.phonelock.app.service.SocialGroupSyncClient.pushMyStats(
            fbDatabaseUrl, fbApiKey, groupId, displayName,
            share.shareRoutines, share.shareStudy, share.shareStreak,
            share.shareSchedule, share.shareStudyingNow,
            routineStats, studySeconds, studyProgress, streak, routineBestStreak,
            scheduleStats, calcTaskStats, studySecondsByDate, studyingNow, studyingTaskName,
            hiddenFromUids
        )
    }.onSuccess {
        preferences.recordSyncSuccess()
    }.onFailure { e ->
        preferences.recordSyncFailure()
        com.phonelock.app.util.InAppLogger.log(appContext, "SocialGroupSync", "pushMySocialStats failed: ${e.message}")
    }
}

/** "모임" 공유 설정/사용자별 비공개 설정 — 전부 로컬 SharedPreferences, UI는 이 창구로만 접근한다. */
fun PhoneLockRepository.groupShareSettings(groupId: String) = preferences.groupShareSettings(groupId)
fun PhoneLockRepository.setGroupShareSettings(groupId: String, settings: AppPreferences.GroupShareSettings) =
    preferences.setGroupShareSettings(groupId, settings)

/** 특정 상대에게 내 정보 전체를 숨길지 — 다음 [pushMySocialStats] 때 RTDB에 반영된다. */
fun PhoneLockRepository.hiddenFromUidsFor(groupId: String) = preferences.hiddenFromUidsFor(groupId)
fun PhoneLockRepository.setHiddenFromUid(groupId: String, targetUid: String, hidden: Boolean) =
    preferences.setHiddenFromUid(groupId, targetUid, hidden)

/** 특정 상대의 정보를 내 화면에서만 안 보이게 할지 — 순수 로컬 표시 설정, 서버엔 안 올라간다. */
fun PhoneLockRepository.hiddenPeerUidsFor(groupId: String) = preferences.hiddenPeerUidsFor(groupId)
fun PhoneLockRepository.setHiddenPeerUid(groupId: String, targetUid: String, hidden: Boolean) =
    preferences.setHiddenPeerUid(groupId, targetUid, hidden)

/** "무작위 알림"(77차, 81차 정정) — 이 모임에서 처지는 멤버가 있을 때 나에게 알림으로 알려줄지, 순수 로컬 설정. */
fun PhoneLockRepository.randomNudgeEnabledFor(groupId: String) = preferences.randomNudgeEnabledFor(groupId)
fun PhoneLockRepository.setRandomNudgeEnabled(groupId: String, enabled: Boolean) =
    preferences.setRandomNudgeEnabled(groupId, enabled)

suspend fun PhoneLockRepository.sendSocialGroupNudge(groupId: String, targetUid: String) {
    val fromName = com.phonelock.app.service.AccountSyncClient.myDisplayName(fbDatabaseUrl, fbApiKey)
    com.phonelock.app.service.SocialGroupSyncClient.sendNudge(fbDatabaseUrl, fbApiKey, groupId, targetUid, fromName)
}

/** 내가 속한 모든 모임에서 나에게 온 새 넛지를 읽는다(마지막 확인 시각은 [AppPreferences]에 있음). */
suspend fun PhoneLockRepository.readIncomingSocialGroupNudges(): List<com.phonelock.app.service.SocialGroupSyncClient.NudgeInfo> {
    val groupIds = readMySocialGroupIds()
    return com.phonelock.app.service.SocialGroupSyncClient.readIncomingNudges(
        fbDatabaseUrl, fbApiKey, groupIds, preferences.nudgeLastSeenByGroup()
    )
}

fun PhoneLockRepository.markSocialGroupNudgeSeen(groupId: String, atMillis: Long) {
    preferences.setNudgeLastSeen(groupId, atMillis)
}

/** 무전(강제 음성 메시지) 보내기. */
suspend fun PhoneLockRepository.sendVoiceMessage(groupId: String, targetUid: String, audioBase64: String, durationMs: Long): Result<Unit> {
    val fromName = com.phonelock.app.service.AccountSyncClient.myDisplayName(fbDatabaseUrl, fbApiKey)
    return com.phonelock.app.service.SocialGroupSyncClient.sendVoiceMessage(
        fbDatabaseUrl, fbApiKey, groupId, targetUid, fromName, audioBase64, durationMs
    )
}

/** 무전(텍스트 메시지, 상대 기기에서 TTS로 읽어줌) 보내기. */
suspend fun PhoneLockRepository.sendTextMessage(groupId: String, targetUid: String, textMessage: String): Result<Unit> {
    val fromName = com.phonelock.app.service.AccountSyncClient.myDisplayName(fbDatabaseUrl, fbApiKey)
    return com.phonelock.app.service.SocialGroupSyncClient.sendTextMessage(
        fbDatabaseUrl, fbApiKey, groupId, targetUid, fromName, textMessage
    )
}

/** 이 모임에서 내가 무전기를 어떻게 받을지(모임마다 다르게 설정 가능). */
suspend fun PhoneLockRepository.readGroupWalkieSettings(groupId: String): com.phonelock.app.service.SocialGroupSyncClient.GroupWalkieSettings {
    return com.phonelock.app.service.SocialGroupSyncClient.readGroupWalkieSettings(fbDatabaseUrl, fbApiKey, groupId)
}

suspend fun PhoneLockRepository.writeGroupWalkieSettings(groupId: String, settings: com.phonelock.app.service.SocialGroupSyncClient.GroupWalkieSettings): Result<Unit> {
    return com.phonelock.app.service.SocialGroupSyncClient.writeGroupWalkieSettings(fbDatabaseUrl, fbApiKey, groupId, settings)
}

/** 내가 속한 모든 모임에서 나에게 온 무전 메시지 전부(재생/확인 후 [deleteVoiceMessage]로 지울 것). */
suspend fun PhoneLockRepository.readIncomingVoiceMessages(): List<com.phonelock.app.service.SocialGroupSyncClient.VoiceMessageInfo> {
    val groupIds = readMySocialGroupIds()
    return com.phonelock.app.service.SocialGroupSyncClient.readIncomingVoiceMessages(fbDatabaseUrl, fbApiKey, groupIds)
}

/** 실패 시 실제 원인(상태코드/응답 본문)이 담긴 예외를 돌려준다 — 자동재생 후 삭제처럼 "지워진 게
 *  확인돼야 재생해도 된다"는 호출부도 `result.isSuccess`로 판단할 수 있다. */
suspend fun PhoneLockRepository.deleteVoiceMessage(groupId: String, msgId: String): Result<Unit> {
    return com.phonelock.app.service.SocialGroupSyncClient.deleteVoiceMessage(fbDatabaseUrl, fbApiKey, groupId, msgId)
}

suspend fun PhoneLockRepository.markVoiceMessageListened(groupId: String, msg: com.phonelock.app.service.SocialGroupSyncClient.VoiceMessageInfo) {
    com.phonelock.app.service.SocialGroupSyncClient.markVoiceMessageListened(fbDatabaseUrl, fbApiKey, groupId, msg)
}
