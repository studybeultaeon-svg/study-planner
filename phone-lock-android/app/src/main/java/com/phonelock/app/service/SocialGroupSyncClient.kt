package com.phonelock.app.service

import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * "모임"(소셜 그룹) 기능의 Firebase REST 클라이언트 — 기존 차단 대상 그룹(`AppGroup`)과는 완전히 다른
 * 개념이라 이름을 분리했다(계획 문서 dynamic-shimmying-map.md 참고). [PomodoroSyncClient]의
 * `resolveIdentity()`/HttpURLConnection GET·PUT 보일러플레이트를 그대로 재사용하되, 이 기능은
 * `users/{uid}/...` 밑이 아니라 최상위 `groups/{groupId}/...`를 쓰므로 별도 클라이언트로 뒀다
 * (여러 사용자가 공유하는 데이터라 uid 루트 밑에 두면 다른 사람이 내 데이터 밑에서 자기 그룹을 못 찾음).
 *
 * 모든 함수는 로그인이 필수다 — [AuthManager]로 로그인 안 돼 있으면 조용히 실패로 처리한다.
 */
object SocialGroupSyncClient {
    private const val TIMEOUT_MS = 5_000

    data class GroupInfo(
        val id: String, val name: String, val ownerUid: String, val inviteCode: String, val createdAt: Long
    )

    data class GroupMemberInfo(val uid: String, val displayName: String, val joinedAt: Long)

    data class RoutineStat(val title: String, val doneToday: Boolean, val icon: String = "", val timeSlot: String? = null)

    /** [dateKey]/[color]가 있어야 모임 멤버 상세에서 실제 캘린더 미니 그리드로 그릴 수 있다(76차 확장 —
     *  예전엔 오늘 하루치만 이름/상태로 보여줬다). */
    data class ScheduleStat(
        val dateKey: String, val name: String, val status: String?, val color: String,
        val linkedCalc: String? = null, val progressStep: String? = null
    )

    /** 할당량 계산기 업무 하나 — 라이브 [com.phonelock.app.ui.TimetableScreen]과 같은 요일별 목표량 표를
     *  모임 멤버 상세에도 그대로 그리기 위해(78차, "오늘 할 일"이 아니라 진짜 일정표를 보고 싶다는 요청)
     *  draft CalcTask에서 표시에 필요한 필드만 옮긴다. */
    data class CalcTaskStat(
        val name: String, val unit: String, val start: String, val dday: String,
        val mon: String, val tue: String, val wed: String, val thu: String,
        val fri: String, val sat: String, val sun: String
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
        val routines: List<RoutineStat>?,
        val studyTodaySeconds: Int?,
        val studyProgressPercent: Int?,
        val streak: Int?,
        val schedule: List<ScheduleStat>?,
        /** "공부 - 일정표" 탭용 할당량 계산기 업무 목록(78차) — shareSchedule과 같이 묶인다. */
        val calcTasks: List<CalcTaskStat>?,
        /** 캘린더 날짜 상세에서 그 날 총 공부시간을 보여주기 위한 dateKey -> 초 — shareStudy가 꺼져있으면 null/빈 맵. */
        val studySecondsByDate: Map<String, Int>?,
        val studyingNow: Boolean?,
        val studyingTaskName: String?,
        /** "루틴 - 통계" 탭의 최고 스트릭 타일용. */
        val routineBestStreak: Int?,
        /** 이 사람이 "내 정보 숨기기"로 지정한 상대 uid 목록 — 이 목록에 내 uid가 있으면 위 항목을 전부 "비공개"로 취급한다. */
        val hiddenFromUids: Set<String>
    )

    data class NudgeInfo(val groupId: String, val fromUid: String, val fromName: String, val sentAtMillis: Long)

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
     *  기존엔 앱 전체 공통(AppPreferences) 설정이었지만, 모임마다 다르게 받고 싶다는 요청으로 모임별/멤버별
     *  RTDB 값(`groups/{groupId}/walkieSettings/{myUid}`)으로 옮겼다. */
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

    /** 모임 생성 — 이름 입력 → info 작성 + 6자리 코드 생성(충돌 시 재시도) + 나·소속목록 등록. 성공 시 새 groupId. */
    suspend fun createGroup(databaseUrl: String?, apiKey: String?, name: String): Result<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val displayName = AccountSyncClient.myDisplayName(databaseUrl, apiKey)
                val now = System.currentTimeMillis()

                val code = generateUniqueInviteCode(base, token)
                val infoBody = JSONObject().apply {
                    put("info", JSONObject().apply {
                        put("name", name)
                        put("ownerUid", uid)
                        put("inviteCode", code)
                        put("createdAt", now)
                    })
                }
                val postConn = (URL("$base/groups.json?auth=$token").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                postConn.outputStream.use { it.write(infoBody.toString().toByteArray()) }
                if (postConn.responseCode !in 200..299) { postConn.disconnect(); error("모임 생성에 실패했습니다.") }
                val postBody = postConn.inputStream.bufferedReader().use { it.readText() }
                postConn.disconnect()
                val groupId = JSONObject(postBody).getString("name")

                putJson(URL("$base/inviteCodes/$code.json?auth=$token"), JSONObject.quote(groupId), raw = true)
                putJson(URL("$base/groups/$groupId/members/$uid.json?auth=$token"), JSONObject().apply {
                    put("displayName", displayName)
                    put("joinedAt", now)
                })
                putJson(URL("$base/users/$uid/socialGroupIds/$groupId.json?auth=$token"), "true", raw = true)

                groupId
            }
        }
    }

    /** 초대 코드로 참여 — 코드→groupId 역인덱스 조회 후 members/socialGroupIds에 나를 추가. */
    suspend fun joinGroupByCode(databaseUrl: String?, apiKey: String?, code: String): Result<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val groupId = getRaw(URL("$base/inviteCodes/$code.json?auth=$token"))
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?: error("초대 코드를 찾을 수 없습니다.")
                val displayName = AccountSyncClient.myDisplayName(databaseUrl, apiKey)
                val now = System.currentTimeMillis()
                putJson(URL("$base/groups/$groupId/members/$uid.json?auth=$token"), JSONObject().apply {
                    put("displayName", displayName)
                    put("joinedAt", now)
                })
                putJson(URL("$base/users/$uid/socialGroupIds/$groupId.json?auth=$token"), "true", raw = true)
                groupId
            }
        }
    }

    /** 나가기 — members/stats/socialGroupIds에서 나를 지운다. */
    suspend fun leaveGroup(databaseUrl: String?, apiKey: String?, groupId: String) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching
                val base = databaseUrl.trimEnd('/')
                sendDelete(URL("$base/groups/$groupId/members/$uid.json?auth=$token"))
                sendDelete(URL("$base/groups/$groupId/stats/$uid.json?auth=$token"))
                sendDelete(URL("$base/users/$uid/socialGroupIds/$groupId.json?auth=$token"))
                Unit
            }
        }
    }

    /** 삭제(owner만) — 멤버 전원의 socialGroupIds + inviteCode + groups 전체를 지운다. */
    suspend fun deleteGroup(databaseUrl: String?, apiKey: String?, groupId: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val infoText = getRaw(URL("$base/groups/$groupId/info.json?auth=$token"))
                    ?.takeIf { it.isNotBlank() && it != "null" } ?: error("모임을 찾을 수 없습니다.")
                val info = JSONObject(infoText)
                if (info.optString("ownerUid") != uid) error("모임장만 삭제할 수 있습니다.")
                val code = info.optString("inviteCode")

                val membersText = getRaw(URL("$base/groups/$groupId/members.json?auth=$token"))
                if (!membersText.isNullOrBlank() && membersText != "null") {
                    val members = JSONObject(membersText)
                    members.keys().forEach { memberUid ->
                        sendDelete(URL("$base/users/$memberUid/socialGroupIds/$groupId.json?auth=$token"))
                    }
                }
                if (code.isNotBlank()) sendDelete(URL("$base/inviteCodes/$code.json?auth=$token"))
                sendDelete(URL("$base/groups/$groupId.json?auth=$token"))
                Unit
            }
        }
    }

    /** 77차: 관리자 목록(모임장 제외, 모임장은 항상 최상위 관리자) — groups/{id}/admins/{uid}=true. */
    suspend fun readGroupAdmins(databaseUrl: String?, apiKey: String?, groupId: String): Set<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptySet()
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: return@runCatching emptySet()
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/groups/$groupId/admins.json?auth=$token"))
                    ?.takeIf { it.isNotBlank() && it != "null" } ?: return@runCatching emptySet()
                val json = JSONObject(text)
                json.keys().asSequence().filter { json.optBoolean(it, false) }.toSet()
            }.getOrDefault(emptySet())
        }
    }

    /** 관리자 승격/해제 — 모임장(방을 처음 만든 사람)만 할 수 있다(보안 규칙이 서버에서도 강제). */
    suspend fun setGroupAdmin(databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String, isAdmin: Boolean): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                if (isAdmin) {
                    putJson(URL("$base/groups/$groupId/admins/$targetUid.json?auth=$token"), "true", raw = true)
                } else {
                    if (!sendDelete(URL("$base/groups/$groupId/admins/$targetUid.json?auth=$token"))) error("관리자 해제에 실패했습니다.")
                }
            }
        }
    }

    /** 멤버 내쫓기(모임장/관리자만) — 대상의 members/stats/socialGroupIds/admins 항목을 모두 지운다. */
    suspend fun kickMember(databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                sendDelete(URL("$base/groups/$groupId/members/$targetUid.json?auth=$token"))
                sendDelete(URL("$base/groups/$groupId/stats/$targetUid.json?auth=$token"))
                sendDelete(URL("$base/groups/$groupId/admins/$targetUid.json?auth=$token"))
                sendDelete(URL("$base/users/$targetUid/socialGroupIds/$groupId.json?auth=$token"))
                Unit
            }
        }
    }

    /** 모임 이름 수정(모임장/관리자만). */
    suspend fun updateGroupName(databaseUrl: String?, apiKey: String?, groupId: String, newName: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        if (newName.isBlank()) return Result.failure(IllegalStateException("모임 이름을 입력하세요."))
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                putJson(URL("$base/groups/$groupId/info/name.json?auth=$token"), JSONObject.quote(newName.trim()), raw = true)
            }
        }
    }

    /** "모임 랭킹"(82차, §11 창의적 기능) — 회유 멘트에 "중단"(저항)한 비율을 모임원끼리 비교. */
    data class QuoteStat(val uid: String, val displayName: String, val stopRatePercent: Int, val totalCount: Int)

    suspend fun writeMyQuoteStat(databaseUrl: String?, apiKey: String?, groupId: String, displayName: String, stopRatePercent: Int, totalCount: Int) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching
                val base = databaseUrl.trimEnd('/')
                putJson(
                    URL("$base/groups/$groupId/quoteStats/$uid.json?auth=$token"),
                    JSONObject().apply {
                        put("displayName", displayName); put("stopRatePercent", stopRatePercent)
                        put("totalCount", totalCount); put("updatedAt", System.currentTimeMillis())
                    }
                )
            }
        }
    }

    suspend fun readQuoteStats(databaseUrl: String?, apiKey: String?, groupId: String): List<QuoteStat> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: return@runCatching emptyList()
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/groups/$groupId/quoteStats.json?auth=$token"))
                if (text.isNullOrBlank() || text == "null") return@runCatching emptyList()
                val json = JSONObject(text)
                json.keys().asSequence().mapNotNull { uid ->
                    val s = json.optJSONObject(uid) ?: return@mapNotNull null
                    QuoteStat(uid, s.optString("displayName", uid), s.optInt("stopRatePercent", 0), s.optInt("totalCount", 0))
                }.toList()
            }.getOrDefault(emptyList())
        }
    }

    /** 모임장 공지사항(82차, §9). */
    data class Announcement(val text: String, val updatedAt: Long, val updatedByName: String)

    suspend fun readAnnouncement(databaseUrl: String?, apiKey: String?, groupId: String): Announcement? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: return@runCatching null
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/groups/$groupId/announcement.json?auth=$token"))
                if (text.isNullOrBlank() || text == "null") return@runCatching null
                val json = JSONObject(text)
                val body = json.optString("text", "")
                if (body.isBlank()) return@runCatching null
                Announcement(body, json.optLong("updatedAt", 0L), json.optString("updatedByName", ""))
            }.getOrNull()
        }
    }

    /** 공지 작성/수정(모임장·관리자만 — RTDB 규칙이 실제 권한을 강제한다). */
    suspend fun writeAnnouncement(databaseUrl: String?, apiKey: String?, groupId: String, text: String, updatedByName: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                putJson(
                    URL("$base/groups/$groupId/announcement.json?auth=$token"),
                    JSONObject().apply { put("text", text.trim()); put("updatedAt", System.currentTimeMillis()); put("updatedByName", updatedByName) }
                )
            }
        }
    }

    /** 모임 공동 목표(82차, §9) — 관리자가 이번 기간(주간 고정) 목표 공부시간(분)을 정하면, 멤버 전원의
     *  studyTodaySeconds 합산으로 진행바를 보여준다(전원이 공유 켠 값만 합산 가능, 서버 집계 없음). */
    data class GroupGoal(val targetMinutes: Int, val updatedAt: Long)

    suspend fun readGoal(databaseUrl: String?, apiKey: String?, groupId: String): GroupGoal? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: return@runCatching null
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/groups/$groupId/goal.json?auth=$token"))
                if (text.isNullOrBlank() || text == "null") return@runCatching null
                val json = JSONObject(text)
                val target = json.optInt("targetMinutes", 0)
                if (target <= 0) return@runCatching null
                GroupGoal(target, json.optLong("updatedAt", 0L))
            }.getOrNull()
        }
    }

    suspend fun writeGoal(databaseUrl: String?, apiKey: String?, groupId: String, targetMinutes: Int): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                putJson(
                    URL("$base/groups/$groupId/goal.json?auth=$token"),
                    JSONObject().apply { put("targetMinutes", targetMinutes); put("updatedAt", System.currentTimeMillis()) }
                )
            }
        }
    }

    /** 초대 코드 재발급(모임장/관리자만) — 새 코드 생성 + inviteCodes 등록 + info.inviteCode 갱신 + 옛 코드 삭제. */
    suspend fun regenerateInviteCode(databaseUrl: String?, apiKey: String?, groupId: String): Result<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val oldCode = getRaw(URL("$base/groups/$groupId/info/inviteCode.json?auth=$token"))?.trim('"') ?: ""
                val code = generateUniqueInviteCode(base, token)
                putJson(URL("$base/inviteCodes/$code.json?auth=$token"), JSONObject.quote(groupId), raw = true)
                putJson(URL("$base/groups/$groupId/info/inviteCode.json?auth=$token"), JSONObject.quote(code), raw = true)
                if (oldCode.isNotBlank()) sendDelete(URL("$base/inviteCodes/$oldCode.json?auth=$token"))
                code
            }
        }
    }

    /** 내가 속한 모임 id 목록. */
    suspend fun readMyGroupIds(databaseUrl: String?, apiKey: String?): List<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching emptyList()
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/users/$uid/socialGroupIds.json?auth=$token"))
                if (text.isNullOrBlank() || text == "null") return@runCatching emptyList()
                val json = JSONObject(text)
                json.keys().asSequence().toList()
            }.getOrDefault(emptyList())
        }
    }

    suspend fun readGroupInfo(databaseUrl: String?, apiKey: String?, groupId: String): GroupInfo? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: return@runCatching null
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/groups/$groupId/info.json?auth=$token"))
                if (text.isNullOrBlank() || text == "null") return@runCatching null
                val json = JSONObject(text)
                GroupInfo(
                    id = groupId,
                    name = json.optString("name", ""),
                    ownerUid = json.optString("ownerUid", ""),
                    inviteCode = json.optString("inviteCode", ""),
                    createdAt = json.optLong("createdAt", 0L)
                )
            }.getOrNull()
        }
    }

    suspend fun readGroupMembers(databaseUrl: String?, apiKey: String?, groupId: String): List<GroupMemberInfo> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: return@runCatching emptyList()
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/groups/$groupId/members.json?auth=$token"))
                if (text.isNullOrBlank() || text == "null") return@runCatching emptyList()
                val json = JSONObject(text)
                json.keys().asSequence().map { memberUid ->
                    val m = json.getJSONObject(memberUid)
                    GroupMemberInfo(memberUid, m.optString("displayName", "사용자"), m.optLong("joinedAt", 0L))
                }.toList()
            }.getOrDefault(emptyList())
        }
    }

    /** 내 통계를 이 모임에 올린다 — 공유 토글이 꺼진 항목은 아예 필드를 생략한다(계획 스키마).
     *  [hiddenFromUids]는 항목 공유 여부와 무관하게 항상 실어 보낸다(특정 상대에게만 전체 비공개하는
     *  용도라 그 자체는 "공개할 정보"가 아니라 접근제어 메타데이터이기 때문). */
    suspend fun pushMyStats(
        databaseUrl: String?, apiKey: String?, groupId: String, displayName: String,
        shareRoutines: Boolean, shareStudy: Boolean, shareStreak: Boolean,
        shareSchedule: Boolean, shareStudyingNow: Boolean,
        routines: List<RoutineStat>, studyTodaySeconds: Int, studyProgressPercent: Int, streak: Int, routineBestStreak: Int,
        schedule: List<ScheduleStat>, calcTasks: List<CalcTaskStat>, studySecondsByDate: Map<String, Int>,
        studyingNow: Boolean, studyingTaskName: String,
        hiddenFromUids: Set<String>
    ) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching
                val base = databaseUrl.trimEnd('/')
                val body = JSONObject().apply {
                    put("displayName", displayName)
                    put("updatedAt", System.currentTimeMillis())
                    put("shareRoutines", shareRoutines)
                    put("shareStudy", shareStudy)
                    put("shareStreak", shareStreak)
                    put("shareSchedule", shareSchedule)
                    put("shareStudyingNow", shareStudyingNow)
                    put("hiddenFromUids", org.json.JSONArray(hiddenFromUids.toList()))
                    if (shareRoutines) {
                        put("routines", org.json.JSONArray().apply {
                            routines.forEach { r ->
                                put(JSONObject().apply {
                                    put("title", r.title)
                                    put("doneToday", r.doneToday)
                                    put("icon", r.icon)
                                    put("timeSlot", r.timeSlot ?: JSONObject.NULL)
                                })
                            }
                        })
                    }
                    if (shareStudy) {
                        put("studyTodaySeconds", studyTodaySeconds)
                        put("studyProgressPercent", studyProgressPercent)
                        put("studySecondsByDate", JSONObject().apply {
                            studySecondsByDate.forEach { (dateKey, seconds) -> put(dateKey, seconds) }
                        })
                    }
                    if (shareStreak) {
                        put("streak", streak)
                        put("routineBestStreak", routineBestStreak)
                    }
                    if (shareSchedule) {
                        put("schedule", org.json.JSONArray().apply {
                            schedule.forEach { s ->
                                put(JSONObject().apply {
                                    put("dateKey", s.dateKey)
                                    put("name", s.name)
                                    put("status", s.status ?: JSONObject.NULL)
                                    put("color", s.color)
                                    put("linkedCalc", s.linkedCalc ?: JSONObject.NULL)
                                    put("progressStep", s.progressStep ?: JSONObject.NULL)
                                })
                            }
                        })
                        put("calcTasks", org.json.JSONArray().apply {
                            calcTasks.forEach { t ->
                                put(JSONObject().apply {
                                    put("name", t.name)
                                    put("unit", t.unit)
                                    put("start", t.start)
                                    put("dday", t.dday)
                                    put("mon", t.mon); put("tue", t.tue); put("wed", t.wed); put("thu", t.thu)
                                    put("fri", t.fri); put("sat", t.sat); put("sun", t.sun)
                                })
                            }
                        })
                    }
                    if (shareStudyingNow) {
                        put("studyingNow", studyingNow)
                        put("studyingTaskName", studyingTaskName)
                    }
                }
                putJson(URL("$base/groups/$groupId/stats/$uid.json?auth=$token"), body)
            }
        }
    }

    suspend fun readGroupStats(databaseUrl: String?, apiKey: String?, groupId: String): List<MemberStats> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: return@runCatching emptyList()
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/groups/$groupId/stats.json?auth=$token"))
                if (text.isNullOrBlank() || text == "null") return@runCatching emptyList()
                val json = JSONObject(text)
                json.keys().asSequence().map { memberUid ->
                    val s = json.getJSONObject(memberUid)
                    val shareRoutines = s.optBoolean("shareRoutines", false)
                    val shareStudy = s.optBoolean("shareStudy", false)
                    val shareStreak = s.optBoolean("shareStreak", false)
                    val shareSchedule = s.optBoolean("shareSchedule", false)
                    val shareStudyingNow = s.optBoolean("shareStudyingNow", false)
                    val hiddenArr = s.optJSONArray("hiddenFromUids") ?: org.json.JSONArray()
                    MemberStats(
                        uid = memberUid,
                        displayName = s.optString("displayName", "사용자"),
                        updatedAt = s.optLong("updatedAt", 0L),
                        shareRoutines = shareRoutines,
                        shareStudy = shareStudy,
                        shareStreak = shareStreak,
                        shareSchedule = shareSchedule,
                        shareStudyingNow = shareStudyingNow,
                        routines = if (shareRoutines) {
                            val arr = s.optJSONArray("routines") ?: org.json.JSONArray()
                            (0 until arr.length()).map { i ->
                                val r = arr.getJSONObject(i)
                                RoutineStat(
                                    r.optString("title", ""),
                                    r.optBoolean("doneToday", false),
                                    r.optString("icon", ""),
                                    if (r.isNull("timeSlot")) null else r.optString("timeSlot", null)
                                )
                            }
                        } else null,
                        studyTodaySeconds = if (shareStudy) s.optInt("studyTodaySeconds", 0) else null,
                        studyProgressPercent = if (shareStudy) s.optInt("studyProgressPercent", 0) else null,
                        streak = if (shareStreak) s.optInt("streak", 0) else null,
                        schedule = if (shareSchedule) {
                            val arr = s.optJSONArray("schedule") ?: org.json.JSONArray()
                            (0 until arr.length()).map { i ->
                                val sc = arr.getJSONObject(i)
                                ScheduleStat(
                                    sc.optString("dateKey", ""),
                                    sc.optString("name", ""),
                                    if (sc.isNull("status")) null else sc.optString("status", null),
                                    sc.optString("color", "white"),
                                    if (sc.isNull("linkedCalc")) null else sc.optString("linkedCalc", null),
                                    if (sc.isNull("progressStep")) null else sc.optString("progressStep", null)
                                )
                            }
                        } else null,
                        calcTasks = if (shareSchedule) {
                            val arr = s.optJSONArray("calcTasks") ?: org.json.JSONArray()
                            (0 until arr.length()).map { i ->
                                val t = arr.getJSONObject(i)
                                CalcTaskStat(
                                    t.optString("name", ""), t.optString("unit", ""),
                                    t.optString("start", ""), t.optString("dday", ""),
                                    t.optString("mon", ""), t.optString("tue", ""), t.optString("wed", ""), t.optString("thu", ""),
                                    t.optString("fri", ""), t.optString("sat", ""), t.optString("sun", "")
                                )
                            }
                        } else null,
                        studySecondsByDate = if (shareStudy) {
                            val obj = s.optJSONObject("studySecondsByDate")
                            if (obj != null) obj.keys().asSequence().associateWith { obj.optInt(it, 0) } else emptyMap()
                        } else null,
                        studyingNow = if (shareStudyingNow) s.optBoolean("studyingNow", false) else null,
                        studyingTaskName = if (shareStudyingNow) s.optString("studyingTaskName", "") else null,
                        routineBestStreak = if (shareStreak) s.optInt("routineBestStreak", 0) else null,
                        hiddenFromUids = (0 until hiddenArr.length()).map { hiddenArr.getString(it) }.toSet()
                    )
                }.toList()
            }.getOrDefault(emptyList())
        }
    }

    /** "깨우기" — 과거 넛지 유무 무관, 항상 최신 1건으로 덮어쓴다. */
    suspend fun sendNudge(databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String, fromName: String) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching
                val base = databaseUrl.trimEnd('/')
                val body = JSONObject().apply {
                    put("fromUid", uid)
                    put("fromName", fromName)
                    put("sentAtMillis", System.currentTimeMillis())
                }
                putJson(URL("$base/groups/$groupId/nudges/$targetUid.json?auth=$token"), body)
            }
        }
    }

    /** 내가 속한 모든 모임에서 나에게 온 넛지 중, 마지막으로 확인한 시각([lastSeenByGroup])보다 새 것만 돌려준다. */
    suspend fun readIncomingNudges(
        databaseUrl: String?, apiKey: String?, groupIds: List<String>, lastSeenByGroup: Map<String, Long>
    ): List<NudgeInfo> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank() || groupIds.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching emptyList()
                val base = databaseUrl.trimEnd('/')
                groupIds.mapNotNull { groupId ->
                    val text = getRaw(URL("$base/groups/$groupId/nudges/$uid.json?auth=$token"))
                    if (text.isNullOrBlank() || text == "null") return@mapNotNull null
                    val json = JSONObject(text)
                    val sentAt = json.optLong("sentAtMillis", 0L)
                    val lastSeen = lastSeenByGroup[groupId] ?: 0L
                    if (sentAt <= lastSeen) return@mapNotNull null
                    NudgeInfo(groupId, json.optString("fromUid", ""), json.optString("fromName", "누군가"), sentAt)
                }
            }.getOrDefault(emptyList())
        }
    }

    /** 무전 음성 메시지 전송 — nudge와 달리 여러 건이 쌓일 수 있어 push-id 리스트(`voiceMessages/{targetUid}/{msgId}`)로 저장한다. */
    suspend fun sendVoiceMessage(
        databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String, fromName: String,
        audioBase64: String, durationMs: Long
    ): Result<Unit> = sendWakeMessage(databaseUrl, apiKey, groupId, targetUid, fromName, audioBase64 = audioBase64, durationMs = durationMs)

    /** 텍스트를 TTS로 읽어주는 무전 — 오디오 대신 [textMessage]만 채워서 같은 저장 구조를 재사용한다. */
    suspend fun sendTextMessage(
        databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String, fromName: String, textMessage: String
    ): Result<Unit> = sendWakeMessage(databaseUrl, apiKey, groupId, targetUid, fromName, textMessage = textMessage)

    private suspend fun sendWakeMessage(
        databaseUrl: String?, apiKey: String?, groupId: String, targetUid: String, fromName: String,
        audioBase64: String = "", durationMs: Long = 0L, textMessage: String = ""
    ): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val body = JSONObject().apply {
                    put("fromUid", uid)
                    put("fromName", fromName)
                    put("sentAtMillis", System.currentTimeMillis())
                    put("audioBase64", audioBase64)
                    put("durationMs", durationMs)
                    put("textMessage", textMessage)
                }
                val postConn = (URL("$base/groups/$groupId/voiceMessages/$targetUid.json?auth=$token").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                postConn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = postConn.responseCode
                if (code !in 200..299) {
                    // RTDB가 거부한 원인(대개 규칙 위반 -> "Permission denied")을 그대로 보여줘야 다음에
                    // 또 추측하지 않고 바로 원인을 알 수 있다 — 과거 이런 종류의 실패를 삼켰다가 여러 번
                    // 잘못 짚은 전례가 있어서(CHANGELOG 참고) 응답 본문을 함께 노출한다.
                    val errorBody = runCatching { postConn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    postConn.disconnect()
                    error("무전 전송에 실패했습니다. ($code: ${errorBody ?: "응답 없음"})")
                }
                postConn.disconnect()
            }
        }
    }

    /** 이 모임에서 무전기를 어떻게 받을지(나 자신의 설정) 읽는다 — 값이 없으면 기본값(꺼짐). */
    suspend fun readGroupWalkieSettings(databaseUrl: String?, apiKey: String?, groupId: String): GroupWalkieSettings {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return GroupWalkieSettings()
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching GroupWalkieSettings()
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/groups/$groupId/walkieSettings/$uid.json?auth=$token"))
                    ?: return@runCatching GroupWalkieSettings()
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
    }

    /** 이 모임에서 무전기를 어떻게 받을지(나 자신의 설정) 저장한다. */
    suspend fun writeGroupWalkieSettings(databaseUrl: String?, apiKey: String?, groupId: String, settings: GroupWalkieSettings): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val body = JSONObject().apply {
                    put("enabled", settings.enabled)
                    put("mode", settings.mode)
                    put("volume", settings.volume)
                    put("voiceGender", settings.voiceGender)
                    put("schedules", org.json.JSONArray().apply {
                        settings.schedules.forEach { s ->
                            put(JSONObject().apply {
                                put("daysMask", s.daysMask)
                                put("startMinute", s.startMinute)
                                put("endMinute", s.endMinute)
                            })
                        }
                    })
                }
                putJson(URL("$base/groups/$groupId/walkieSettings/$uid.json?auth=$token"), body)
            }
        }
    }

    /** 내가 속한 모든 모임에서 나에게 온 무전 메시지 전부(재생/확인 후엔 [markVoiceMessageListened], 완전히 지우려면
     *  [deleteVoiceMessage]) — 이미 들었지만 유예시간([VOICE_MESSAGE_LISTENED_EXPIRY_MS])이 지난 메시지는 여기서
     *  자동으로 지우고 결과에서도 뺀다. */
    suspend fun readIncomingVoiceMessages(databaseUrl: String?, apiKey: String?, groupIds: List<String>): List<VoiceMessageInfo> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank() || groupIds.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching emptyList()
                val base = databaseUrl.trimEnd('/')
                val now = System.currentTimeMillis()
                groupIds.flatMap { groupId ->
                    val text = getRaw(URL("$base/groups/$groupId/voiceMessages/$uid.json?auth=$token"))
                    if (text.isNullOrBlank() || text == "null") return@flatMap emptyList()
                    val json = JSONObject(text)
                    json.keys().asSequence().mapNotNull { msgId ->
                        val m = json.getJSONObject(msgId)
                        val listenedAt = m.optLong("listenedAtMillis", 0L)
                        if (listenedAt > 0 && now - listenedAt > VOICE_MESSAGE_LISTENED_EXPIRY_MS) {
                            sendDelete(URL("$base/groups/$groupId/voiceMessages/$uid/$msgId.json?auth=$token"))
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
    }

    /** 재생 시작 시 호출 — 즉시 지우지 않고 "들었음" 표시만 남겨서 유예시간 동안은 인박스에서 다시 재생할 수 있게 한다. */
    suspend fun markVoiceMessageListened(databaseUrl: String?, apiKey: String?, groupId: String, msg: VoiceMessageInfo) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
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
                putJson(URL("$base/groups/$groupId/voiceMessages/$uid/${msg.msgId}.json?auth=$token"), body)
            }
        }
    }

    /** 무전 메시지를 완전히 지운다(재생 여부 무관, 사용자가 명시적으로 삭제하거나 유예시간이 지났을 때) — 실패 시
     *  상태코드/응답 본문(대개 RTDB 규칙 위반의 "Permission denied")을 그대로 담아 던진다. "네트워크 확인"처럼
     *  얼버무린 문구 대신 실제 원인이 바로 보이게 하려는 용도([sendVoiceMessage]와 동일 원칙). */
    suspend fun deleteVoiceMessage(databaseUrl: String?, apiKey: String?, groupId: String, msgId: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val conn = (URL("$base/groups/$groupId/voiceMessages/$uid/$msgId.json?auth=$token").openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errorBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    conn.disconnect()
                    error("삭제에 실패했습니다. ($code: ${errorBody ?: "응답 없음"})")
                }
                conn.disconnect()
            }
        }
    }

    /** 6자리 숫자 코드를 생성하고 이미 쓰이는 코드면 재시도한다(최대 10회, 그래도 겹치면 마지막 값을 그냥 쓴다). */
    private fun generateUniqueInviteCode(base: String, token: String): String {
        repeat(10) {
            val code = (100000..999999).random(Random).toString()
            val existing = getRaw(URL("$base/inviteCodes/$code.json?auth=$token"))
            if (existing.isNullOrBlank() || existing == "null") return code
        }
        return (100000..999999).random(Random).toString()
    }

    private fun getRaw(url: URL): String? = runCatching {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        body
    }.getOrNull()

    private fun putJson(url: URL, body: JSONObject) {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }

    /** 원시 JSON 값(문자열/불리언 리터럴 등, 객체가 아닌 값)을 그대로 쓴다 — 예: `"true"`, `"\"groupId\""`. */
    private fun putJson(url: URL, rawValue: String, raw: Boolean) {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(rawValue.toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }

    private fun sendDelete(url: URL): Boolean = runCatching {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..299
    }.getOrDefault(false)

    /** (idToken, uid) — 로그인 필수, 안 돼 있으면 null. [PomodoroSyncClient.resolveIdentity]와 동일 패턴. */
    private suspend fun resolveIdentity(apiKey: String): Pair<String, String>? {
        val googleUser = AuthManager.currentUser ?: return null
        val token = runCatching { googleUser.getIdToken(false).await().token }.getOrNull()
        if (token.isNullOrBlank()) return null
        return token to googleUser.uid
    }
}
