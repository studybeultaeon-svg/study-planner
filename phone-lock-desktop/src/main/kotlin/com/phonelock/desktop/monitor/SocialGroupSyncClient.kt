package com.phonelock.desktop.monitor

import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.routine.RoutineEngine
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate

/**
 * "모임"(소셜 그룹) 기능의 Firebase REST 클라이언트 — [PomodoroSyncClient]의 resolveIdentity()/
 * HttpClient 패턴을 그대로 재사용한다. 기존 `users/{uid}/...` 동기화와 달리 여러 사용자가 함께 쓰는
 * 데이터라 최상위 `groups/{groupId}/...`, `inviteCodes/{code}`, `users/{uid}/socialGroupIds/{groupId}`
 * 경로를 쓴다(설계 이유는 DECISIONS.md 참고). 모든 함수는 로그인이 안 돼 있으면 조용히
 * 실패(null/빈 목록/Result.failure)한다 — 다른 동기화 클라이언트와 동일한 fail-safe 원칙.
 */
object SocialGroupSyncClient {
    private const val TIMEOUT_SECONDS = 5L
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    private val INVITE_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 0/O, 1/I처럼 헷갈리는 문자 제외

    data class GroupInfo(val name: String, val ownerUid: String, val inviteCode: String, val createdAt: Long)
    data class MemberInfo(val uid: String, val displayName: String, val joinedAt: Long)
    data class RoutineStat(val title: String, val doneToday: Boolean, val icon: String = "", val timeSlot: String? = null)
    /** [dateKey]/[color]가 있어야 모임 멤버 상세에서 실제 캘린더 미니 그리드로 그릴 수 있다(76차 확장 —
     *  예전엔 오늘 하루치만 이름/상태로 보여줬다). */
    data class ScheduleStat(val dateKey: String, val name: String, val status: String?, val color: String)
    /** 할당량 계산기 업무 하나 — 라이브 [com.phonelock.desktop.ui.TimetableScreen]과 같은 요일별 목표량 표를
     *  모임 멤버 상세에도 그대로 그리기 위해(78차) draft CalcTask에서 표시에 필요한 필드만 옮긴다. */
    data class CalcTaskStat(
        val name: String, val unit: String, val start: String, val dday: String,
        val mon: String, val tue: String, val wed: String, val thu: String,
        val fri: String, val sat: String, val sun: String
    )
    /** "작동 중인 관리 그룹" 클릭 시 상세 다이얼로그로 보여줄 전체 설정 — Group의 관련 필드를 그대로 옮긴다. */
    data class ActiveGroupStat(
        val name: String,
        val description: String,
        val scheduleEnabled: Boolean,
        val scheduleStartMinute: Int?,
        val scheduleEndMinute: Int?,
        val scheduleDaysMask: Int,
        val dailyLimitSeconds: Int?,
        val dailyLimitApplyStartMinute: Int?,
        val dailyLimitApplyEndMinute: Int?,
        val dailyLimitDaysMask: Int,
        val confirmEnabled: Boolean,
        val confirmApplyStartMinute: Int?,
        val confirmApplyEndMinute: Int?,
        val confirmDaysMask: Int,
        val processNames: List<String>,
        val domains: List<String>,
        /** "관리 - 통계" 탭용 — StatsScreen.kt와 같은 4개 지표를 그룹별로 함께 옮긴다. */
        val todayUsageSeconds: Int,
        val confirmCountToday: Int,
        val confirmCountYesterday: Int,
        val recentAverageSeconds: Int
    )
    data class MemberStats(
        val uid: String,
        val displayName: String,
        val updatedAt: Long,
        val shareRoutines: Boolean,
        val shareStudy: Boolean,
        val shareStreak: Boolean,
        val shareSchedule: Boolean,
        val shareStudyingNow: Boolean,
        val shareActiveGroup: Boolean,
        val routines: List<RoutineStat>,
        val studyTodaySeconds: Int,
        val studyProgressPercent: Int,
        val streak: Int,
        val schedule: List<ScheduleStat>,
        /** "공부 - 일정표" 탭용 할당량 계산기 업무 목록(78차) — shareSchedule과 같이 묶인다. */
        val calcTasks: List<CalcTaskStat>,
        /** 캘린더 날짜 상세에서 그 날 총 공부시간을 보여주기 위한 dateKey -> 초 — shareStudy가 꺼져있으면 빈 맵. */
        val studySecondsByDate: Map<String, Int>,
        val studyingNow: Boolean,
        val studyingTaskName: String,
        val activeGroups: List<ActiveGroupStat>,
        /** "루틴 - 통계" 탭의 최고 스트릭 타일용. */
        val routineBestStreak: Int,
        /** 이 사람이 "내 정보 숨기기"로 지정한 상대 uid 목록 — 이 목록에 내 uid가 있으면 위 항목을 전부 "비공개"로 취급한다. */
        val hiddenFromUids: Set<String>
    )
    data class NudgeInfo(val fromUid: String, val fromName: String, val sentAtMillis: Long)
    /** [textMessage]가 비어있지 않으면 TTS로 읽어줄 텍스트 메시지, 비어있으면 [audioBase64]를 재생하는
     *  녹음 음성 메시지 — 두 종류를 같은 저장 구조(voiceMessages)에 함께 담는다. */
    data class VoiceMessageInfo(
        val groupId: String, val msgId: String, val fromUid: String, val fromName: String,
        val sentAtMillis: Long, val audioBase64: String, val durationMs: Long,
        val listenedAtMillis: Long = 0L, val textMessage: String = ""
    )

    /** 재생(확인) 후에도 즉시 지우지 않고 이 시간만큼은 인박스에 남겨둔다(다시 듣기 대비) — 이후엔 다음 조회 때 자동 정리. */
    private const val VOICE_MESSAGE_LISTENED_EXPIRY_MS = 24 * 60 * 60 * 1000L

    /** 모임 하나 안에서 무전기를 어떻게 받을지 — 요일×시간대 일정을 여러 개 둘 수 있다(빈 리스트면 항상 허용).
     *  안드로이드판과 동일한 구조(`groups/{groupId}/walkieSettings/{myUid}`)를 공유한다. */
    data class WalkieSchedule(val daysMask: Int = 127, val startMinute: Int = 0, val endMinute: Int = 1440)
    data class GroupWalkieSettings(
        val enabled: Boolean = false,
        val mode: String = "MESSAGE_ONLY",
        val volume: Int = 70,
        val schedules: List<WalkieSchedule> = emptyList(),
        /** TTS 텍스트 메시지를 읽어줄 목소리("FEMALE"/"MALE", 78차) — 받는 사람(이 기기) 기준 설정이라
         *  음성 녹음(실제 오디오) 재생과는 무관하고, [TtsPlayer]로만 전달된다. */
        val voiceGender: String = "FEMALE"
    )

    private fun resolveIdentity(apiKey: String): Pair<String, String>? {
        val uid = AuthManager.currentUid ?: return null
        val token = AuthManager.ensureIdToken(apiKey)
        if (token.isNullOrBlank()) return null
        return token to uid
    }

    private fun get(base: String, path: String, token: String): String? = runCatching {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/$path.json?auth=$token"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        val body = response.body()
        if (body.isNullOrBlank() || body == "null") null else body
    }.getOrNull()

    private fun put(base: String, path: String, token: String, bodyJson: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/$path.json?auth=$token"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .PUT(HttpRequest.BodyPublishers.ofString(bodyJson))
            .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun delete(base: String, path: String, token: String): Boolean = runCatching {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/$path.json?auth=$token"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .DELETE()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() in 200..299
    }.getOrDefault(false)

    /** RTDB push id 발급(POST) — 새 groupId를 만들 때 씀. */
    private fun push(base: String, path: String, token: String, bodyJson: String = "{}"): String? = runCatching {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/$path.json?auth=$token"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        JSONObject(response.body()).optString("name", null)
    }.getOrNull()

    /** [push]와 같지만 실패 시 상태코드/응답 본문(대개 RTDB가 규칙 위반을 설명하는 "Permission denied" 메시지)을
     *  그대로 예외 메시지에 담아 던진다 — 실패 원인을 추측하지 않고 화면에서 바로 볼 수 있게 하려는 용도. 호출부
     *  ([sendVoiceMessage])가 이미 바깥쪽 `runCatching`으로 감싸고 있으므로 여기서는 그대로 던지기만 한다. */
    private fun pushWithDiagnostics(base: String, path: String, token: String, bodyJson: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/$path.json?auth=$token"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("무전 전송에 실패했습니다. (${response.statusCode()}: ${response.body()})")
        }
    }

    private const val DISPLAY_NAME_CACHE_TTL_MS = 60_000L
    private data class DisplayNameCache(val value: String, val fetchedAtMillis: Long)
    private var displayNameCache: DisplayNameCache? = null

    /**
     * 표시 이름 우선순위: 닉네임 -> 커스텀 아이디 -> 이메일 -> uid("익명"은 그 무엇도 없을 때만).
     * 가입 신청 플로우(AccountSyncClient) 도입 이후 프로필(닉네임/커스텀아이디)을 우선하되, 매번 REST
     * 호출을 만들지 않도록 짧게(1분) 캐시한다 — 이 클라이언트의 다른 함수들과 같은 fail-safe 원칙으로
     * 프로필 조회가 실패해도 예전처럼 이메일/uid로 조용히 대체된다.
     */
    private fun myDisplayName(databaseUrl: String?, apiKey: String?): String {
        val fallback = AuthManager.currentEmail ?: AuthManager.currentUid ?: "익명"
        val now = System.currentTimeMillis()
        val cached = displayNameCache
        if (cached != null && now - cached.fetchedAtMillis < DISPLAY_NAME_CACHE_TTL_MS) return cached.value

        val resolved = runCatching {
            val profile = AccountSyncClient.fetchMyProfile(databaseUrl, apiKey).getOrNull() ?: return@runCatching fallback
            profile.optString("nickname", "").ifBlank { null }
                ?: profile.optString("customId", "").ifBlank { null }
                ?: fallback
        }.getOrDefault(fallback)

        displayNameCache = DisplayNameCache(resolved, now)
        return resolved
    }

    /** 모임 만들기: info 작성 + 6자리 코드 생성(충돌 시 재시도) + inviteCodes/members/socialGroupIds 등록. */
    fun createGroup(databaseUrl: String?, apiKey: String?, name: String): Result<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        if (name.isBlank()) return Result.failure(IllegalStateException("모임 이름을 입력하세요."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')

            var code = ""
            repeat(5) {
                if (code.isNotEmpty()) return@repeat
                val candidate = (1..6).map { INVITE_CODE_CHARS.random() }.joinToString("")
                val exists = get(base, "inviteCodes/$candidate", token) != null
                if (!exists) code = candidate
            }
            if (code.isEmpty()) error("초대 코드 생성에 실패했습니다. 다시 시도해주세요.")

            val now = System.currentTimeMillis()
            val displayName = myDisplayName(databaseUrl, apiKey)
            val info = JSONObject().apply {
                put("name", name.trim()); put("ownerUid", uid); put("inviteCode", code); put("createdAt", now)
            }
            // 보안 규칙이 groups/$groupId 생성 시 newData.info.ownerUid를 요구하므로, 빈 body로 push id만
            // 먼저 받아온 뒤 별도로 info를 쓰면 규칙 위반으로 이 첫 push 자체가 거부된다 — info를 담은
            // body로 한 번에 push해야 한다(안드로이드 구현과 동일한 패턴).
            val groupId = push(base, "groups", token, JSONObject().apply { put("info", info) }.toString())
                ?: error("모임 생성에 실패했습니다.")
            put(base, "inviteCodes/$code", token, JSONObject.quote(groupId))
            val member = JSONObject().apply { put("displayName", displayName); put("joinedAt", now) }
            put(base, "groups/$groupId/members/$uid", token, member.toString())
            put(base, "users/$uid/socialGroupIds/$groupId", token, "true")
            groupId
        }
    }

    /** 초대 코드로 참여: inviteCodes/{code} 조회로 groupId를 얻어 members/socialGroupIds에 등록. */
    fun joinGroupByCode(databaseUrl: String?, apiKey: String?, code: String): Result<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        if (code.isBlank()) return Result.failure(IllegalStateException("초대 코드를 입력하세요."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val raw = get(base, "inviteCodes/${code.trim().uppercase()}", token) ?: error("존재하지 않는 초대 코드입니다.")
            val groupId = raw.trim('"')
            val now = System.currentTimeMillis()
            val member = JSONObject().apply { put("displayName", myDisplayName(databaseUrl, apiKey)); put("joinedAt", now) }
            put(base, "groups/$groupId/members/$uid", token, member.toString())
            put(base, "users/$uid/socialGroupIds/$groupId", token, "true")
            groupId
        }
    }

    /** 모임 나가기: 내 members/socialGroupIds/stats 항목만 삭제(모임 자체는 유지). */
    fun leaveGroup(databaseUrl: String?, apiKey: String?, groupId: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            delete(base, "groups/$groupId/members/$uid", token)
            delete(base, "groups/$groupId/stats/$uid", token)
            delete(base, "users/$uid/socialGroupIds/$groupId", token)
        }
    }

    /** 모임 삭제(owner만): 멤버 전원의 socialGroupIds + inviteCodes/{code} + groups/{groupId} 전체 삭제. */
    fun deleteGroup(databaseUrl: String?, apiKey: String?, groupId: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val infoBody = get(base, "groups/$groupId/info", token) ?: error("모임 정보를 찾을 수 없습니다.")
            val info = JSONObject(infoBody)
            if (info.optString("ownerUid", "") != uid) error("모임장만 삭제할 수 있습니다.")
            val membersBody = get(base, "groups/$groupId/members", token)
            if (membersBody != null) {
                val members = JSONObject(membersBody)
                members.keys().forEach { memberUid -> delete(base, "users/$memberUid/socialGroupIds/$groupId", token) }
            }
            val code = info.optString("inviteCode", "")
            if (code.isNotBlank()) delete(base, "inviteCodes/$code", token)
            delete(base, "groups/$groupId", token)
        }
    }

    /** 77차: 관리자 목록(모임장 제외, 모임장은 항상 최상위 관리자) — groups/{id}/admins/{uid}=true. */
    fun readGroupAdmins(databaseUrl: String?, apiKey: String?, groupId: String): Set<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptySet()
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: return@runCatching emptySet()
            val base = databaseUrl.trimEnd('/')
            val body = get(base, "groups/$groupId/admins", token) ?: return@runCatching emptySet()
            val json = JSONObject(body)
            json.keySet().filter { json.optBoolean(it, false) }.toSet()
        }.getOrDefault(emptySet())
    }

    /** 관리자 승격/해제 — 모임장(방을 처음 만든 사람)만 할 수 있다(보안 규칙이 서버에서도 강제). */
    fun setGroupAdmin(databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String, isAdmin: Boolean): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            if (isAdmin) put(base, "groups/$groupId/admins/$targetUid", token, "true")
            else if (!delete(base, "groups/$groupId/admins/$targetUid", token)) error("관리자 해제에 실패했습니다.")
        }
    }

    /** 멤버 내쫓기(모임장/관리자만) — 대상의 members/stats/socialGroupIds/admins 항목을 모두 지운다. */
    fun kickMember(databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            delete(base, "groups/$groupId/members/$targetUid", token)
            delete(base, "groups/$groupId/stats/$targetUid", token)
            delete(base, "groups/$groupId/admins/$targetUid", token)
            delete(base, "users/$targetUid/socialGroupIds/$groupId", token)
            Unit
        }
    }

    /** 모임 이름 수정(모임장/관리자만). */
    fun updateGroupName(databaseUrl: String?, apiKey: String?, groupId: String, newName: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        if (newName.isBlank()) return Result.failure(IllegalStateException("모임 이름을 입력하세요."))
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            put(base, "groups/$groupId/info/name", token, JSONObject.quote(newName.trim()))
        }
    }

    /** 초대 코드 재발급(모임장/관리자만) — 새 코드 생성 + inviteCodes 등록 + info.inviteCode 갱신 + 옛 코드 삭제. */
    fun regenerateInviteCode(databaseUrl: String?, apiKey: String?, groupId: String): Result<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val oldCode = get(base, "groups/$groupId/info/inviteCode", token)?.trim('"') ?: ""
            var code = ""
            repeat(5) {
                if (code.isNotEmpty()) return@repeat
                val candidate = (1..6).map { INVITE_CODE_CHARS.random() }.joinToString("")
                if (get(base, "inviteCodes/$candidate", token) == null) code = candidate
            }
            if (code.isEmpty()) error("코드 생성에 실패했습니다. 다시 시도해주세요.")
            put(base, "inviteCodes/$code", token, JSONObject.quote(groupId))
            put(base, "groups/$groupId/info/inviteCode", token, JSONObject.quote(code))
            if (oldCode.isNotBlank()) delete(base, "inviteCodes/$oldCode", token)
            code
        }
    }

    /** 내가 속한 모임 id 목록. */
    fun readMyGroupIds(databaseUrl: String?, apiKey: String?): List<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptyList()
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching emptyList()
            val base = databaseUrl.trimEnd('/')
            val body = get(base, "users/$uid/socialGroupIds", token) ?: return@runCatching emptyList()
            JSONObject(body).keySet().toList()
        }.getOrDefault(emptyList())
    }

    fun readGroupInfo(databaseUrl: String?, apiKey: String?, groupId: String): GroupInfo? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: return@runCatching null
            val base = databaseUrl.trimEnd('/')
            val body = get(base, "groups/$groupId/info", token) ?: return@runCatching null
            val json = JSONObject(body)
            GroupInfo(
                name = json.optString("name", ""),
                ownerUid = json.optString("ownerUid", ""),
                inviteCode = json.optString("inviteCode", ""),
                createdAt = json.optLong("createdAt", 0L)
            )
        }.getOrNull()
    }

    fun readGroupMembers(databaseUrl: String?, apiKey: String?, groupId: String): List<MemberInfo> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptyList()
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: return@runCatching emptyList()
            val base = databaseUrl.trimEnd('/')
            val body = get(base, "groups/$groupId/members", token) ?: return@runCatching emptyList()
            val json = JSONObject(body)
            json.keySet().map { uid ->
                val m = json.getJSONObject(uid)
                MemberInfo(uid, m.optString("displayName", uid), m.optLong("joinedAt", 0L))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 내 오늘 통계를 groups/{groupId}/stats/{내uid}에 통째로 덮어쓴다 — 각자 자기 것만 쓰는 노드라 병합이
     * 필요 없다(다른 기기와도 경쟁 없음, DECISIONS.md 참고). 공유 토글이 꺼진 항목은 생략한다.
     * "진행률"은 오늘 캘린더 일정 완료율로 계산한다(스터디 시간 자체는 studyTodaySeconds로 별도 표시).
     */
    fun pushMyStats(databaseUrl: String?, apiKey: String?, groupId: String, repository: Repository) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val today = LocalDate.now()
            val todayKey = today.toString()

            val share = repository.groupShareSettings(groupId)
            val hiddenFromUids = repository.hiddenFromUidsFor(groupId)

            val stats = JSONObject().apply {
                put("displayName", myDisplayName(databaseUrl, apiKey))
                put("updatedAt", System.currentTimeMillis())
                put("shareRoutines", share.shareRoutines)
                put("shareStudy", share.shareStudy)
                put("shareStreak", share.shareStreak)
                put("shareSchedule", share.shareSchedule)
                put("shareStudyingNow", share.shareStudyingNow)
                put("shareActiveGroup", share.shareActiveGroup)
                put("hiddenFromUids", JSONArray(hiddenFromUids.toList()))
            }

            if (share.shareRoutines) {
                val routines = repository.getRoutines().filter { RoutineEngine.isScheduledOn(it, today) }
                val completed = routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
                val routinesArr = org.json.JSONArray()
                routines.forEach { r ->
                    routinesArr.put(JSONObject().apply {
                        put("title", r.title)
                        put("doneToday", todayKey in (completed[r.id] ?: emptySet()))
                        put("icon", r.icon)
                        put("timeSlot", r.timeSlot ?: JSONObject.NULL)
                    })
                }
                stats.put("routines", routinesArr)
            }
            val calendarTasks = repository.getCalendarTasks(todayKey)
            // 캘린더 미니 그리드가 이 달 전체를 그리고(오늘 하루치만 보여주던 76차 이전과 다름), 날짜 상세의
            // 총 공부시간도 같은 달 범위가 필요해서 firstOfMonth/lastOfMonth를 두 블록이 함께 쓴다
            // (달력 그리드가 앞뒤로 걸치는 주까지 포함해 ±7일 버퍼 — CalendarScreen.refresh()와 동일 범위).
            val firstOfMonth = today.withDayOfMonth(1)
            val lastOfMonth = firstOfMonth.plusMonths(1).minusDays(1)
            val monthFromKey = firstOfMonth.minusDays(7).toString()
            val monthToKey = lastOfMonth.plusDays(7).toString()
            if (share.shareStudy) {
                val seconds = repository.getStudyLogForDate(todayKey).sumOf { it.seconds }
                val progress = if (calendarTasks.isNotEmpty()) {
                    Math.round(calendarTasks.count { it.status == "O" } * 100.0 / calendarTasks.size).toInt()
                } else 0
                stats.put("studyTodaySeconds", seconds)
                stats.put("studyProgressPercent", progress)
                val byDate = repository.getStudyLogInRange(monthFromKey, monthToKey)
                    .groupBy { it.dateKey }
                    .mapValues { (_, entries) -> entries.sumOf { it.seconds } }
                stats.put("studySecondsByDate", JSONObject().apply {
                    byDate.forEach { (dateKey, seconds2) -> put(dateKey, seconds2) }
                })
            }
            if (share.shareStreak) {
                val routines = repository.getRoutines()
                val completed = routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
                stats.put("streak", RoutineEngine.currentStreak(routines, completed, today))
                stats.put("routineBestStreak", RoutineEngine.bestStreak(routines, completed, today))
            }
            if (share.shareSchedule) {
                val monthTasks = repository.getCalendarTasksInRange(monthFromKey, monthToKey)
                stats.put("schedule", JSONArray().apply {
                    monthTasks.forEach { t ->
                        put(JSONObject().apply {
                            put("dateKey", t.dateKey)
                            put("name", t.name)
                            put("status", t.status ?: JSONObject.NULL)
                            put("color", t.color)
                        })
                    }
                })
                // "일정표" 탭에 캘린더 오늘 할 일이 아니라 진짜 TimetableScreen과 같은 요일별 목표량 표를
                // 보여달라는 요청(78차) — TimetableScreen.kt와 동일 필터(이름/디데이 필수)로 draft 업무를 옮긴다.
                val calcTasks = repository.getCalcTasks().filter { it.name.isNotBlank() && it.dday.isNotBlank() }
                stats.put("calcTasks", JSONArray().apply {
                    calcTasks.forEach { t ->
                        put(JSONObject().apply {
                            put("name", t.name); put("unit", t.unit); put("start", t.start); put("dday", t.dday)
                            put("mon", t.mon); put("tue", t.tue); put("wed", t.wed); put("thu", t.thu)
                            put("fri", t.fri); put("sat", t.sat); put("sun", t.sun)
                        })
                    }
                })
            }
            if (share.shareStudyingNow) {
                val timerRun = repository.getTimerRun()
                val localStudying = timerRun != null && timerRun.phase == "study" && timerRun.phaseStartedAt > 0L
                val remoteStudying = runCatching { PomodoroSyncClient.isStudyTimerActive(databaseUrl, apiKey) }.getOrDefault(false)
                stats.put("studyingNow", localStudying || remoteStudying)
                stats.put(
                    "studyingTaskName",
                    if (localStudying) timerRun?.taskName ?: "" else runCatching { PomodoroSyncClient.remoteTaskName(databaseUrl, apiKey) }.getOrDefault("")
                )
            }
            if (share.shareActiveGroup) {
                stats.put("activeGroups", JSONArray().apply {
                    repository.sharedActiveGroups().forEach { g ->
                        put(JSONObject().apply {
                            put("name", g.name)
                            put("description", g.description)
                            put("scheduleEnabled", g.scheduleEnabled)
                            put("scheduleStartMinute", g.scheduleStartMinute ?: JSONObject.NULL)
                            put("scheduleEndMinute", g.scheduleEndMinute ?: JSONObject.NULL)
                            put("scheduleDaysMask", g.scheduleDaysMask)
                            put("dailyLimitSeconds", g.dailyLimitSeconds ?: JSONObject.NULL)
                            put("dailyLimitApplyStartMinute", g.dailyLimitApplyStartMinute ?: JSONObject.NULL)
                            put("dailyLimitApplyEndMinute", g.dailyLimitApplyEndMinute ?: JSONObject.NULL)
                            put("dailyLimitDaysMask", g.dailyLimitDaysMask)
                            put("confirmEnabled", g.confirmEnabled)
                            put("confirmApplyStartMinute", g.confirmApplyStartMinute ?: JSONObject.NULL)
                            put("confirmApplyEndMinute", g.confirmApplyEndMinute ?: JSONObject.NULL)
                            put("confirmDaysMask", g.confirmDaysMask)
                            put("processNames", JSONArray(g.processNames))
                            put("domains", JSONArray(g.domains))
                            put("todayUsageSeconds", repository.getTodayUsageSeconds(g.id))
                            put("confirmCountToday", repository.getConfirmCountToday(g.id))
                            put("confirmCountYesterday", repository.getConfirmCountYesterday(g.id))
                            put("recentAverageSeconds", repository.getRecentAverageUsageSeconds(g.id))
                        })
                    }
                })
            }

            put(base, "groups/$groupId/stats/$uid", token, stats.toString())
        }
    }

    fun readGroupStats(databaseUrl: String?, apiKey: String?, groupId: String): List<MemberStats> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptyList()
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: return@runCatching emptyList()
            val base = databaseUrl.trimEnd('/')
            val body = get(base, "groups/$groupId/stats", token) ?: return@runCatching emptyList()
            val json = JSONObject(body)
            json.keySet().map { uid ->
                val s = json.getJSONObject(uid)
                val routinesArr = s.optJSONArray("routines")
                val routines = if (routinesArr != null) {
                    (0 until routinesArr.length()).map { i ->
                        val r = routinesArr.getJSONObject(i)
                        RoutineStat(
                            r.optString("title", ""),
                            r.optBoolean("doneToday", false),
                            r.optString("icon", ""),
                            if (r.isNull("timeSlot")) null else r.optString("timeSlot", null)
                        )
                    }
                } else emptyList()
                val scheduleArr = s.optJSONArray("schedule")
                val schedule = if (scheduleArr != null) {
                    (0 until scheduleArr.length()).map { i ->
                        val sc = scheduleArr.getJSONObject(i)
                        ScheduleStat(
                            sc.optString("dateKey", ""),
                            sc.optString("name", ""),
                            if (sc.isNull("status")) null else sc.optString("status", null),
                            sc.optString("color", "white")
                        )
                    }
                } else emptyList()
                val activeGroupArr = s.optJSONArray("activeGroups")
                val activeGroups = if (activeGroupArr != null) {
                    (0 until activeGroupArr.length()).map { i ->
                        val g = activeGroupArr.getJSONObject(i)
                        ActiveGroupStat(
                            name = g.optString("name", ""),
                            description = g.optString("description", ""),
                            scheduleEnabled = g.optBoolean("scheduleEnabled", false),
                            scheduleStartMinute = if (g.isNull("scheduleStartMinute")) null else g.optInt("scheduleStartMinute"),
                            scheduleEndMinute = if (g.isNull("scheduleEndMinute")) null else g.optInt("scheduleEndMinute"),
                            scheduleDaysMask = g.optInt("scheduleDaysMask", 127),
                            dailyLimitSeconds = if (g.isNull("dailyLimitSeconds")) null else g.optInt("dailyLimitSeconds"),
                            dailyLimitApplyStartMinute = if (g.isNull("dailyLimitApplyStartMinute")) null else g.optInt("dailyLimitApplyStartMinute"),
                            dailyLimitApplyEndMinute = if (g.isNull("dailyLimitApplyEndMinute")) null else g.optInt("dailyLimitApplyEndMinute"),
                            dailyLimitDaysMask = g.optInt("dailyLimitDaysMask", 127),
                            confirmEnabled = g.optBoolean("confirmEnabled", false),
                            confirmApplyStartMinute = if (g.isNull("confirmApplyStartMinute")) null else g.optInt("confirmApplyStartMinute"),
                            confirmApplyEndMinute = if (g.isNull("confirmApplyEndMinute")) null else g.optInt("confirmApplyEndMinute"),
                            confirmDaysMask = g.optInt("confirmDaysMask", 127),
                            processNames = g.optJSONArray("processNames")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                            domains = g.optJSONArray("domains")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                            todayUsageSeconds = g.optInt("todayUsageSeconds", 0),
                            confirmCountToday = g.optInt("confirmCountToday", 0),
                            confirmCountYesterday = g.optInt("confirmCountYesterday", 0),
                            recentAverageSeconds = g.optInt("recentAverageSeconds", 0)
                        )
                    }
                } else emptyList()
                val calcTasksArr = s.optJSONArray("calcTasks")
                val calcTasks = if (calcTasksArr != null) {
                    (0 until calcTasksArr.length()).map { i ->
                        val t = calcTasksArr.getJSONObject(i)
                        CalcTaskStat(
                            t.optString("name", ""), t.optString("unit", ""),
                            t.optString("start", ""), t.optString("dday", ""),
                            t.optString("mon", ""), t.optString("tue", ""), t.optString("wed", ""), t.optString("thu", ""),
                            t.optString("fri", ""), t.optString("sat", ""), t.optString("sun", "")
                        )
                    }
                } else emptyList()
                val studySecondsByDateObj = s.optJSONObject("studySecondsByDate")
                val studySecondsByDate = if (studySecondsByDateObj != null) {
                    studySecondsByDateObj.keySet().associateWith { studySecondsByDateObj.optInt(it, 0) }
                } else emptyMap()
                val hiddenArr = s.optJSONArray("hiddenFromUids") ?: JSONArray()
                MemberStats(
                    uid = uid,
                    displayName = s.optString("displayName", uid),
                    updatedAt = s.optLong("updatedAt", 0L),
                    shareRoutines = s.optBoolean("shareRoutines", false),
                    shareStudy = s.optBoolean("shareStudy", false),
                    shareStreak = s.optBoolean("shareStreak", false),
                    shareSchedule = s.optBoolean("shareSchedule", false),
                    shareStudyingNow = s.optBoolean("shareStudyingNow", false),
                    shareActiveGroup = s.optBoolean("shareActiveGroup", false),
                    routines = routines,
                    studyTodaySeconds = s.optInt("studyTodaySeconds", 0),
                    studyProgressPercent = s.optInt("studyProgressPercent", 0),
                    streak = s.optInt("streak", 0),
                    schedule = schedule,
                    calcTasks = calcTasks,
                    studySecondsByDate = studySecondsByDate,
                    studyingNow = s.optBoolean("studyingNow", false),
                    studyingTaskName = s.optString("studyingTaskName", ""),
                    activeGroups = activeGroups,
                    routineBestStreak = s.optInt("routineBestStreak", 0),
                    hiddenFromUids = (0 until hiddenArr.length()).map { hiddenArr.getString(it) }.toSet()
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 넛지("깨우기") 보내기 — 과거 넛지 유무 무관하게 항상 최신 1건으로 덮어쓴다. */
    fun sendNudge(databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("fromUid", uid); put("fromName", myDisplayName(databaseUrl, apiKey)); put("sentAtMillis", System.currentTimeMillis())
            }
            put(base, "groups/$groupId/nudges/$targetUid", token, body.toString())
        }
    }

    /** 내가 속한 모든 모임에서 나(myUid) 앞으로 온 넛지를 읽는다. groupId -> NudgeInfo. */
    fun readIncomingNudges(databaseUrl: String?, apiKey: String?, groupIds: List<String>, myUid: String): Map<String, NudgeInfo> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank() || groupIds.isEmpty()) return emptyMap()
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: return@runCatching emptyMap()
            val base = databaseUrl.trimEnd('/')
            groupIds.mapNotNull { groupId ->
                val body = get(base, "groups/$groupId/nudges/$myUid", token) ?: return@mapNotNull null
                val json = JSONObject(body)
                groupId to NudgeInfo(
                    fromUid = json.optString("fromUid", ""),
                    fromName = json.optString("fromName", ""),
                    sentAtMillis = json.optLong("sentAtMillis", 0L)
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** 무전 음성 메시지 전송 — nudge와 달리 여러 건이 쌓일 수 있어 push-id 리스트(`voiceMessages/{targetUid}/{msgId}`)로 저장한다. */
    fun sendVoiceMessage(
        databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String,
        audioBase64: String, durationMs: Long
    ): Result<Unit> = sendWakeMessage(databaseUrl, apiKey, groupId, targetUid, audioBase64 = audioBase64, durationMs = durationMs)

    /** 텍스트를 TTS로 읽어주는 무전 — 오디오 대신 [textMessage]만 채워서 같은 저장 구조를 재사용한다. */
    fun sendTextMessage(
        databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String, textMessage: String
    ): Result<Unit> = sendWakeMessage(databaseUrl, apiKey, groupId, targetUid, textMessage = textMessage)

    private fun sendWakeMessage(
        databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String,
        audioBase64: String = "", durationMs: Long = 0L, textMessage: String = ""
    ): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("fromUid", uid)
                put("fromName", myDisplayName(databaseUrl, apiKey))
                put("sentAtMillis", System.currentTimeMillis())
                put("audioBase64", audioBase64)
                put("durationMs", durationMs)
                put("textMessage", textMessage)
            }
            pushWithDiagnostics(base, "groups/$groupId/voiceMessages/$targetUid", token, body.toString())
            Unit
        }
    }

    /** 이 모임에서 무전기를 어떻게 받을지(나 자신의 설정) 읽는다 — 값이 없으면 기본값(꺼짐). */
    fun readGroupWalkieSettings(databaseUrl: String?, apiKey: String?, groupId: String): GroupWalkieSettings {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return GroupWalkieSettings()
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching GroupWalkieSettings()
            val base = databaseUrl.trimEnd('/')
            val text = get(base, "groups/$groupId/walkieSettings/$uid", token) ?: return@runCatching GroupWalkieSettings()
            val json = JSONObject(text)
            val schedules = json.optJSONArray("schedules")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    WalkieSchedule(
                        daysMask = s.optInt("daysMask", 127),
                        startMinute = s.optInt("startMinute", 0),
                        endMinute = s.optInt("endMinute", 1440)
                    )
                }
            } ?: emptyList()
            GroupWalkieSettings(
                enabled = json.optBoolean("enabled", false),
                mode = json.optString("mode", "MESSAGE_ONLY"),
                volume = json.optInt("volume", 70),
                schedules = schedules,
                voiceGender = json.optString("voiceGender", "FEMALE")
            )
        }.getOrDefault(GroupWalkieSettings())
    }

    /** 이 모임에서 무전기를 어떻게 받을지(나 자신의 설정) 저장한다. */
    fun writeGroupWalkieSettings(databaseUrl: String?, apiKey: String?, groupId: String, settings: GroupWalkieSettings): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("enabled", settings.enabled)
                put("mode", settings.mode)
                put("volume", settings.volume)
                put("voiceGender", settings.voiceGender)
                put("schedules", JSONArray().apply {
                    settings.schedules.forEach { s ->
                        put(JSONObject().apply {
                            put("daysMask", s.daysMask)
                            put("startMinute", s.startMinute)
                            put("endMinute", s.endMinute)
                        })
                    }
                })
            }
            put(base, "groups/$groupId/walkieSettings/$uid", token, body.toString())
        }
    }

    /** 내가 속한 모든 모임에서 나(myUid) 앞으로 온 무전 메시지 전부(재생/확인 후엔 [markVoiceMessageListened], 완전히
     *  지우려면 [deleteVoiceMessage]) — 이미 들었지만 유예시간([VOICE_MESSAGE_LISTENED_EXPIRY_MS])이 지난 메시지는
     *  여기서 자동으로 지우고 결과에서도 뺀다. */
    fun readIncomingVoiceMessages(databaseUrl: String?, apiKey: String?, groupIds: List<String>, myUid: String): List<VoiceMessageInfo> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank() || groupIds.isEmpty()) return emptyList()
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: return@runCatching emptyList()
            val base = databaseUrl.trimEnd('/')
            val now = System.currentTimeMillis()
            groupIds.flatMap { groupId ->
                val body = get(base, "groups/$groupId/voiceMessages/$myUid", token) ?: return@flatMap emptyList()
                val json = JSONObject(body)
                json.keys().asSequence().mapNotNull { msgId ->
                    val m = json.getJSONObject(msgId)
                    val listenedAt = m.optLong("listenedAtMillis", 0L)
                    if (listenedAt > 0 && now - listenedAt > VOICE_MESSAGE_LISTENED_EXPIRY_MS) {
                        delete(base, "groups/$groupId/voiceMessages/$myUid/$msgId", token)
                        return@mapNotNull null
                    }
                    VoiceMessageInfo(
                        groupId = groupId,
                        msgId = msgId,
                        fromUid = m.optString("fromUid", ""),
                        fromName = m.optString("fromName", "누군가"),
                        sentAtMillis = m.optLong("sentAtMillis", 0L),
                        audioBase64 = m.optString("audioBase64", ""),
                        durationMs = m.optLong("durationMs", 0L),
                        listenedAtMillis = listenedAt,
                        textMessage = m.optString("textMessage", "")
                    )
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    /** 재생 시작 시 호출 — 즉시 지우지 않고 "들었음" 표시만 남겨서 유예시간 동안은 인박스에서 다시 재생할 수 있게 한다. */
    fun markVoiceMessageListened(databaseUrl: String?, apiKey: String?, groupId: String, msg: VoiceMessageInfo) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("fromUid", msg.fromUid)
                put("fromName", msg.fromName)
                put("sentAtMillis", msg.sentAtMillis)
                put("audioBase64", msg.audioBase64)
                put("durationMs", msg.durationMs)
                put("textMessage", msg.textMessage)
                put("listenedAtMillis", System.currentTimeMillis())
            }
            put(base, "groups/$groupId/voiceMessages/$uid/${msg.msgId}", token, body.toString())
        }
    }

    /** 무전 메시지를 완전히 지운다(재생 여부 무관) — 실패 시 상태코드/응답 본문(대개 RTDB 규칙 위반의
     *  "Permission denied")을 그대로 담아 던진다. "네트워크 확인"처럼 얼버무린 문구 대신 실제 원인이 바로
     *  보이게 하려는 용도([sendVoiceMessage]와 동일 원칙). */
    fun deleteVoiceMessage(databaseUrl: String?, apiKey: String?, groupId: String, msgId: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/groups/$groupId/voiceMessages/$uid/$msgId.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .DELETE()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("삭제에 실패했습니다. (${response.statusCode()}: ${response.body()})")
            }
        }
    }
}
