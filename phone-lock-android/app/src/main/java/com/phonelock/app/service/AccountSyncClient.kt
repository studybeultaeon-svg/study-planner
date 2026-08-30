package com.phonelock.app.service

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 앱 내부 관리자 아이디(대문자, `usernames/BEULTAEON`에 저장된 uid와 내 uid가 같으면 관리자). */
private const val ADMIN_USERNAME = "BEULTAEON"

/**
 * Firebase 콘솔에서 수동으로 whitelist(`allowedUsers`)를 등록하던 방식을 대체하는 "앱 내 가입 → 관리자
 * 승인" 플로우의 REST 클라이언트. [SocialGroupSyncClient]/[PomodoroSyncClient]와 같은 보일러플레이트
 * 패턴(HttpURLConnection GET/PUT/PATCH/DELETE, `resolveIdentity`)을 그대로 따른다.
 *
 * 스키마(`firebase-database.rules.json` 참고):
 * - `usernames/{CUSTOMID}` = 내 uid (대문자, 선점식 — 최초 작성자가 영구 소유)
 * - `allowedUsers/{uid}` = true (기존 화이트리스트 게이트, 다른 기능들이 그대로 소비함)
 * - `users/{uid}/profile` = { customId, nickname, isGuest, status: pending|approved|rejected, requestedAt }
 */
object AccountSyncClient {
    private const val TIMEOUT_MS = 5_000

    /** 관리자(`usernames/BEULTAEON`)만 읽을 수 있는 승인 대기 사용자. */
    data class PendingUser(
        val uid: String, val customId: String, val nickname: String, val isGuest: Boolean, val requestedAt: Long
    )

    /** 관리자만 읽을 수 있는 승인된 사용자. */
    data class ApprovedUser(
        val uid: String, val customId: String, val nickname: String, val isGuest: Boolean, val permissions: Permissions
    )

    /** 관리자가 사용자별로 켜고 끌 수 있는 기능 범위 — 루틴/공부/관리(앱 차단)/모임 4개. */
    data class Permissions(val routine: Boolean, val study: Boolean, val manage: Boolean, val social: Boolean) {
        fun toJson() = JSONObject().apply {
            put("routine", routine); put("study", study); put("manage", manage); put("social", social)
        }
        companion object {
            val ALL = Permissions(routine = true, study = true, manage = true, social = true)
            /** 필드가 아예 없으면(옛 승인 사용자) 전부 허용으로 취급 — 하위호환. */
            fun fromProfile(profile: JSONObject?): Permissions {
                val p = profile?.optJSONObject("permissions") ?: return ALL
                return Permissions(
                    routine = p.optBoolean("routine", true),
                    study = p.optBoolean("study", true),
                    manage = p.optBoolean("manage", true),
                    social = p.optBoolean("social", true)
                )
            }
        }
    }

    /** `usernames/{CUSTOMID}` 노드를 내 uid로 선점한다. 규칙상 `!data.exists()`라 이미 쓰이는 아이디면
     *  비-2xx 응답이 오므로 그대로 실패 처리한다. */
    suspend fun claimUsername(databaseUrl: String?, apiKey: String?, customId: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val id = customId.uppercase()
                val ok = putJson(URL("$base/usernames/$id.json?auth=$token"), JSONObject.quote(uid), raw = true)
                if (!ok) throw IllegalStateException("이미 사용 중인 아이디입니다.")
            }
        }
    }

    /**
     * 가입 신청 — `users/{uid}/profile`을 pending 상태로 작성한다. **호출 순서 주의**: 관리자 아이디
     * (BEULTAEON)로 신청하는 경우 이 함수가 곧바로 자기 자신을 승인 처리하는데, 이는 규칙상
     * `usernames/BEULTAEON`에 이미 내 uid가 적혀 있어야만 통과하므로 반드시 [claimUsername]을
     * 먼저 호출해 그 노드를 선점한 뒤에 이 함수를 불러야 한다.
     */
    suspend fun submitProfile(databaseUrl: String?, apiKey: String?, customId: String, nickname: String, isGuest: Boolean): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val id = customId.uppercase()
                val body = JSONObject().apply {
                    put("customId", id)
                    put("nickname", nickname)
                    put("isGuest", isGuest)
                    put("status", "pending")
                    put("requestedAt", System.currentTimeMillis())
                }
                putJson(URL("$base/users/$uid/profile.json?auth=$token"), body)

                if (id == ADMIN_USERNAME) {
                    selfApproveAdmin(base, token, uid)
                }
            }
        }
    }

    /** 내 프로필을 읽는다. 프로필이 아직 없으면 `success(null)`, 네트워크/로그인 오류만 failure로 취급한다. */
    suspend fun fetchMyProfile(databaseUrl: String?, apiKey: String?): Result<JSONObject?> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val body = getRaw(URL("$base/users/$uid/profile.json?auth=$token"))
                if (body.isNullOrBlank() || body == "null") null else JSONObject(body)
            }
        }
    }

    /** 거절된 신청을 새 아이디/닉네임으로 다시 제출한다 — [claimUsername]으로 새 아이디를 선점한 뒤
     *  기존 isGuest 값(있으면)을 유지해 profile을 다시 pending으로 덮어쓴다. */
    suspend fun resubmit(databaseUrl: String?, apiKey: String?, newCustomId: String, newNickname: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return claimUsername(databaseUrl, apiKey, newCustomId).mapCatching {
            withContext(Dispatchers.IO) {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val isGuest = fetchMyProfile(databaseUrl, apiKey).getOrNull()?.optBoolean("isGuest", false) ?: false
                val id = newCustomId.uppercase()
                val body = JSONObject().apply {
                    put("customId", id)
                    put("nickname", newNickname)
                    put("isGuest", isGuest)
                    put("status", "pending")
                    put("requestedAt", System.currentTimeMillis())
                }
                putJson(URL("$base/users/$uid/profile.json?auth=$token"), body)

                if (id == ADMIN_USERNAME) {
                    selfApproveAdmin(base, token, uid)
                }
            }
        }
    }

    /** 관리자 아이디로 신청한 경우 즉시 자기 자신을 승인 처리한다(claimUsername이 이미 선행되어 규칙을 통과). */
    private fun selfApproveAdmin(base: String, token: String, uid: String) {
        sendPatch(URL("$base/users/$uid/profile.json?auth=$token"), JSONObject().apply { put("status", "approved") })
        putJson(URL("$base/allowedUsers/$uid.json?auth=$token"), "true", raw = true)
    }

    /** 닉네임만 부분 갱신한다. */
    suspend fun updateNickname(databaseUrl: String?, apiKey: String?, nickname: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                sendPatch(URL("$base/users/$uid/profile.json?auth=$token"), JSONObject().apply { put("nickname", nickname.trim()) })
            }
        }
    }

    /** 내가 관리자(`usernames/BEULTAEON`에 적힌 uid === 내 uid)인지. 실패 시 fail-safe로 false. */
    suspend fun isAdmin(databaseUrl: String?, apiKey: String?): Boolean {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching false
                val base = databaseUrl.trimEnd('/')
                val raw = getRaw(URL("$base/usernames/$ADMIN_USERNAME.json?auth=$token"))
                val adminUid = raw?.trim()?.trim('"')
                adminUid != null && adminUid.isNotBlank() && adminUid != "null" && adminUid == uid
            }.getOrDefault(false)
        }
    }

    /** 승인 대기 중인 전체 사용자 목록(관리자 전용, `users.json` 전체를 읽어 status로 거른다). */
    suspend fun listPendingUsers(databaseUrl: String?, apiKey: String?): Result<List<PendingUser>> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val body = getRaw(URL("$base/users.json?auth=$token"))
                if (body.isNullOrBlank() || body == "null") return@runCatching emptyList()
                val json = JSONObject(body)
                json.keys().asSequence().mapNotNull { uid ->
                    val profile = json.optJSONObject(uid)?.optJSONObject("profile") ?: return@mapNotNull null
                    if (profile.optString("status") != "pending") return@mapNotNull null
                    PendingUser(
                        uid = uid,
                        customId = profile.optString("customId", ""),
                        nickname = profile.optString("nickname", ""),
                        isGuest = profile.optBoolean("isGuest", false),
                        requestedAt = profile.optLong("requestedAt", 0L)
                    )
                }.toList()
            }
        }
    }

    /** 승인된 전체 사용자 목록(관리자 전용). */
    suspend fun listApprovedUsers(databaseUrl: String?, apiKey: String?): Result<List<ApprovedUser>> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val body = getRaw(URL("$base/users.json?auth=$token"))
                if (body.isNullOrBlank() || body == "null") return@runCatching emptyList()
                val json = JSONObject(body)
                json.keys().asSequence().mapNotNull { uid ->
                    val profile = json.optJSONObject(uid)?.optJSONObject("profile") ?: return@mapNotNull null
                    if (profile.optString("status") != "approved") return@mapNotNull null
                    ApprovedUser(
                        uid = uid,
                        customId = profile.optString("customId", ""),
                        nickname = profile.optString("nickname", ""),
                        isGuest = profile.optBoolean("isGuest", false),
                        permissions = Permissions.fromProfile(profile)
                    )
                }.toList()
            }
        }
    }

    /** 승인(관리자 전용) — allowedUsers에 등록하고 profile.status를 approved로, permissions를 함께 적는다(순차 호출, 원자성 불필요). */
    suspend fun approveUser(databaseUrl: String?, apiKey: String?, targetUid: String, permissions: Permissions): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                putJson(URL("$base/allowedUsers/$targetUid.json?auth=$token"), "true", raw = true)
                sendPatch(
                    URL("$base/users/$targetUid/profile.json?auth=$token"),
                    JSONObject().apply { put("status", "approved"); put("permissions", permissions.toJson()) }
                )
            }
        }
    }

    /** 이미 승인된 사용자의 기능 범위만 바꾼다(관리자 전용). */
    suspend fun updatePermissions(databaseUrl: String?, apiKey: String?, targetUid: String, permissions: Permissions): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                sendPatch(URL("$base/users/$targetUid/profile.json?auth=$token"), JSONObject().apply { put("permissions", permissions.toJson()) })
            }
        }
    }

    /** 거절(관리자 전용) — profile.status만 rejected로 바꾼다. */
    suspend fun rejectUser(databaseUrl: String?, apiKey: String?, targetUid: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                sendPatch(URL("$base/users/$targetUid/profile.json?auth=$token"), JSONObject().apply { put("status", "rejected") })
            }
        }
    }

    /** 승인 취소(관리자 전용) — allowedUsers에서 지우고 profile.status를 rejected로 되돌린다. */
    suspend fun revokeUser(databaseUrl: String?, apiKey: String?, targetUid: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                sendDelete(URL("$base/allowedUsers/$targetUid.json?auth=$token"))
                sendPatch(URL("$base/users/$targetUid/profile.json?auth=$token"), JSONObject().apply { put("status", "rejected") })
            }
        }
    }

    /**
     * 내 계정 데이터를 스스로 지운다(설정 화면 "계정 삭제") — `users/{uid}` 전체(프로필/루틴/캘린더/
     * 계산기 등)를 삭제한다. 규칙상 `allowedUsers/{uid}`는 관리자만 지울 수 있어 여기선 못 건드리지만,
     * 어차피 이 뒤에 [AuthManager.deleteAccount]로 Firebase 계정 자체를 지우면 그 uid로는 다시는
     * 로그인할 수 없으니 무해한 흔적으로 남는다. `usernames/{customId}` 선점도 규칙상 영구 고정이라
     * 이 아이디는 이후 아무도(본인 포함) 다시 쓸 수 없다 — 삭제 전에 사용자에게 반드시 고지할 것.
     */
    suspend fun deleteMyData(databaseUrl: String?, apiKey: String?): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                sendDelete(URL("$base/users/$uid.json?auth=$token"))
            }
        }
    }

    private var cachedProfileForDisplayName: JSONObject? = null
    private var cachedProfileFetchedAtMillis: Long = 0L
    private const val DISPLAY_NAME_CACHE_TTL_MS = 60_000L

    /** 캐시된 프로필(닉네임/아이디) → Google displayName/email → uid 순으로 표시 이름을 고른다.
     *  같은 세션 안에서 반복 REST 호출을 피하려고 프로필을 짧게 캐시한다. */
    suspend fun myDisplayName(databaseUrl: String?, apiKey: String?): String {
        val now = System.currentTimeMillis()
        val cached = cachedProfileForDisplayName
        val profile = if (cached != null && now - cachedProfileFetchedAtMillis < DISPLAY_NAME_CACHE_TTL_MS) {
            cached
        } else {
            val fetched = fetchMyProfile(databaseUrl, apiKey).getOrNull()
            cachedProfileForDisplayName = fetched
            cachedProfileFetchedAtMillis = now
            fetched
        }
        val nickname = profile?.optString("nickname", "")?.takeIf { it.isNotBlank() }
        val customId = profile?.optString("customId", "")?.takeIf { it.isNotBlank() }
        val user = AuthManager.currentUser
        return nickname ?: customId ?: user?.displayName?.takeIf { it.isNotBlank() }
            ?: user?.email?.takeIf { it.isNotBlank() } ?: user?.uid ?: "사용자"
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

    /** PUT — 성공(2xx) 여부를 돌려준다(claimUsername에서 규칙 위반을 감지하는 데 필요). */
    private fun putJson(url: URL, body: JSONObject): Boolean {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        return ok
    }

    /** 원시 JSON 값(문자열/불리언 리터럴 등, 객체가 아닌 값)을 그대로 쓴다 — 예: `"true"`, `"\"uid\""`. */
    private fun putJson(url: URL, rawValue: String, raw: Boolean): Boolean {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(rawValue.toByteArray()) }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        return ok
    }

    /** 부분 갱신(PATCH) — Android HttpURLConnection은 PATCH를 직접 지원하지 않아 POST +
     *  `X-HTTP-Method-Override: PATCH`로 우회한다([PomodoroSyncClient.sendPatch]와 동일 패턴). */
    private fun sendPatch(url: URL, body: JSONObject) {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }

    private fun sendDelete(url: URL) {
        runCatching {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            conn.responseCode
            conn.disconnect()
        }
    }

    /** (idToken, uid) — 로그인 필수(게스트 포함), 안 돼 있으면 null. [SocialGroupSyncClient.resolveIdentity]와 동일 패턴. */
    private suspend fun resolveIdentity(apiKey: String): Pair<String, String>? {
        val user = AuthManager.currentUser ?: return null
        val token = runCatching { user.getIdToken(false).await().token }.getOrNull()
        if (token.isNullOrBlank()) return null
        return token to user.uid
    }
}
