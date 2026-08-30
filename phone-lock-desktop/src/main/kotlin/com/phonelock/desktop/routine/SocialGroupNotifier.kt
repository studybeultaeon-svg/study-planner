package com.phonelock.desktop.routine

import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.SocialGroupSyncClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * "모임"(소셜 그룹) 넛지("깨우기") 폴링 → 로컬 알림. [RoutineNotifier]와 같은 tick() 구조로
 * Main.kt의 30초 주기 루프에 얹는다. 다만 이 함수는 네트워크 I/O(Firebase 조회)를 포함하므로,
 * RoutineNotifier와 달리 코루틴 tick 스레드를 막지 않도록 실제 조회는 백그라운드 스레드로 넘긴다
 * (Repository의 다른 push 함수들과 동일한 fire-and-forget 패턴). 이미 진행 중인 조회가 있으면
 * 겹쳐 실행하지 않는다.
 *
 * 77차: "무작위 알림"(수신 발신 겸용 이 오브젝트에 발신 쪽도 추가) — 하루 중 완전히 랜덤한 시각 한 번
 * (RoutineNotifier의 스트릭 알림과 같은 randomTimeAfter 패턴), 내가 속한 모임(모임별로 켜져있으면)의
 * 멤버 중 오늘 할 일이 남은 사람에게 이 기기가 자동으로 넛지를 보낸다.
 */
object SocialGroupNotifier {
    @Volatile
    private var running = false
    private var lastGroupNudgeCheckDate: String? = null
    private var nextGroupNudgeCheckAt: LocalDateTime? = null

    private fun randomTimeAfter(now: LocalDateTime): LocalDateTime {
        var candidate = LocalDateTime.of(now.toLocalDate(), LocalTime.of((0..23).random(), (0..59).random()))
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate
    }

    fun tick(repository: Repository) {
        if (running) return
        if (!AuthManager.isSignedIn) return
        val url = repository.fbDatabaseUrl
        val key = repository.fbApiKey
        if (url.isNullOrBlank() || key.isNullOrBlank()) return

        running = true
        Thread {
            try {
                val myUid = AuthManager.currentUid ?: return@Thread
                val groupIds = SocialGroupSyncClient.readMyGroupIds(url, key)
                if (groupIds.isEmpty()) return@Thread
                val nudges = SocialGroupSyncClient.readIncomingNudges(url, key, groupIds, myUid)
                nudges.forEach { (groupId, nudge) ->
                    if (nudge.fromUid == myUid) return@forEach
                    val lastSeen = repository.nudgeLastSeenFor(groupId)
                    if (nudge.sentAtMillis > lastSeen) {
                        DesktopNotifier.notify("😴 ${nudge.fromName}님이 깨웠어요", "모임에서 넛지가 도착했습니다 — 지금 확인해보세요")
                        repository.setNudgeLastSeen(groupId, nudge.sentAtMillis)
                    }
                }
                checkRandomGroupNudge(repository, url, key, myUid, groupIds)
            } finally {
                running = false
            }
        }.start()
    }

    private fun checkRandomGroupNudge(repository: Repository, url: String, key: String, myUid: String, groupIds: List<String>) {
        val now = LocalDateTime.now()
        val todayKey = now.toLocalDate().toString()
        val target = nextGroupNudgeCheckAt ?: randomTimeAfter(now).also { nextGroupNudgeCheckAt = it }
        if (now.isBefore(target) || lastGroupNudgeCheckDate == todayKey) return
        lastGroupNudgeCheckDate = todayKey
        nextGroupNudgeCheckAt = randomTimeAfter(now)

        groupIds.forEach groupLoop@{ groupId ->
            if (!repository.randomNudgeEnabledFor(groupId)) return@groupLoop
            val stats = SocialGroupSyncClient.readGroupStats(url, key, groupId)
            stats.forEach memberLoop@{ member ->
                if (member.uid == myUid) return@memberLoop
                val routineIncomplete = member.routines.any { !it.doneToday }
                val scheduleIncomplete = member.schedule.any { it.dateKey == todayKey && it.status != "O" }
                if (routineIncomplete || scheduleIncomplete) {
                    SocialGroupSyncClient.sendNudge(url, key, groupId, member.uid)
                }
            }
        }
    }
}
