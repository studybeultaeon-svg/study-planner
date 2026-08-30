package com.phonelock.desktop.monitor

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * "가입 신청 → 관리자 승인" 화이트리스트 플로우의 Firebase REST 클라이언트 — [SocialGroupSyncClient]/
 * [PomodoroSyncClient]의 HttpClient/Result 패턴을 그대로 재사용한다. 안드로이드 쪽과 같은 Firebase
 * 프로젝트/스키마(`usernames/{customId}`, `allowedUsers/{uid}`, `users/{uid}/profile`)를 공유하며,
 * 스키마 자체는 `phone-lock-android/firebase-database.rules.json`에 이미 확정돼 있다(그 파일은 두 앱이
 * 공유하는 하나의 Firebase 프로젝트 규칙이라 android 폴더 아래 있어도 데스크탑에도 그대로 적용됨).
 * 관리자 판별은 `usernames/BEULTAEON`에 저장된 uid와 요청자 uid가 같은지로 한다(ADMIN_USERNAME).
 * 모든 함수는 동기 함수이며 호출부(SettingsScreen/AccountGateScreen)에서 Thread로 감싸 호출한다.
 */
object AccountSyncClient {
    const val ADMIN_USERNAME = "BEULTAEON"

    private const val TIMEOUT_SECONDS = 5L
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    data class PendingUser(val uid: String, val customId: String, val nickname: String, val isGuest: Boolean, val requestedAt: Long)
    data class ApprovedUser(
        val uid: String, val customId: String, val nickname: String, val isGuest: Boolean, val permissions: Permissions
    )

    /** 관리자가 사용자별로 켜고 끌 수 있는 기능 범위 — 루틴/공부/관리(앱 차단)/모임 4개(안드로이드판과 동일 스키마). */
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

    /** PUT 실패 시 응답 본문(에러 메시지)을 담아 예외로 던진다 — 권한거부(permission denied) 판정용. */
    private fun putOrThrow(base: String, path: String, token: String, bodyJson: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/$path.json?auth=$token"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .PUT(HttpRequest.BodyPublishers.ofString(bodyJson))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) error(response.body() ?: "요청 실패 (${response.statusCode()})")
    }

    private fun patchOrThrow(base: String, path: String, token: String, bodyJson: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/$path.json?auth=$token"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(bodyJson))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) error(response.body() ?: "요청 실패 (${response.statusCode()})")
    }

    private fun deleteOrThrow(base: String, path: String, token: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/$path.json?auth=$token"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .DELETE()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) error(response.body() ?: "요청 실패 (${response.statusCode()})")
    }

    /** 커스텀 아이디를 usernames/{customId}에 선점한다(중복 시 규칙 위반으로 실패). */
    fun claimUsername(databaseUrl: String?, apiKey: String?, customId: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val normalized = customId.trim().uppercase()
            runCatching {
                putOrThrow(base, "usernames/$normalized", token, JSONObject.quote(uid))
            }.onFailure { error("이미 사용 중인 아이디입니다.") }
        }
    }

    /**
     * 프로필을 pending 상태로 등록한다. customId가 정확히 [ADMIN_USERNAME]이면(관리자 본인) 곧바로
     * 자가승인까지 처리한다 — claimUsername이 먼저 usernames/BEULTAEON을 내 uid로 등록해둔 뒤이므로,
     * 이 시점부터는 규칙상 내가 관리자로 인정되어 status="approved" 기록과 allowedUsers 등록이 허용된다.
     */
    fun submitProfile(databaseUrl: String?, apiKey: String?, customId: String, nickname: String, isGuest: Boolean): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val normalized = customId.trim().uppercase()
            val isAdminSelf = normalized == ADMIN_USERNAME

            val profile = JSONObject().apply {
                put("customId", normalized)
                put("nickname", nickname.trim())
                put("isGuest", isGuest)
                put("status", if (isAdminSelf) "approved" else "pending")
                put("requestedAt", System.currentTimeMillis())
            }
            putOrThrow(base, "users/$uid/profile", token, profile.toString())

            if (isAdminSelf) {
                putOrThrow(base, "allowedUsers/$uid", token, "true")
            }
        }
    }

    /** 폴링용 — 내 프로필 상태를 읽는다. 없거나 오류가 나면 null. */
    fun fetchMyProfile(databaseUrl: String?, apiKey: String?): Result<JSONObject?> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            get(base, "users/$uid/profile", token)?.let { JSONObject(it) }
        }
    }

    /** 거절된 사용자가 다른 아이디/닉네임으로 재신청한다 — claimUsername + submitProfile 순서를 그대로 반복. */
    fun resubmit(databaseUrl: String?, apiKey: String?, newCustomId: String, newNickname: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            claimUsername(databaseUrl, apiKey, newCustomId).getOrThrow()
            val isGuest = AuthManager.isAnonymous
            submitProfile(databaseUrl, apiKey, newCustomId, newNickname, isGuest).getOrThrow()
        }
    }

    /** 닉네임만 언제든 변경(PATCH). */
    fun updateNickname(databaseUrl: String?, apiKey: String?, nickname: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject().apply { put("nickname", nickname.trim()) }
            patchOrThrow(base, "users/$uid/profile", token, body.toString())
        }
    }

    /** 내가 관리자(usernames/BEULTAEON == 내 uid)인지. */
    fun isAdmin(databaseUrl: String?, apiKey: String?): Boolean {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return false
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching false
            val base = databaseUrl.trimEnd('/')
            val raw = get(base, "usernames/$ADMIN_USERNAME", token) ?: return@runCatching false
            raw.trim('"') == uid
        }.getOrDefault(false)
    }

    private fun readAllProfiles(base: String, token: String): JSONObject? = get(base, "users", token)?.let { JSONObject(it) }

    /** 승인 대기 중인 사용자 목록(관리자 전용 — users 전체를 읽어 status로 필터링). */
    fun listPendingUsers(databaseUrl: String?, apiKey: String?): Result<List<PendingUser>> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val users = readAllProfiles(base, token) ?: return@runCatching emptyList()
            users.keySet().mapNotNull { uid ->
                val profile = users.getJSONObject(uid).optJSONObject("profile") ?: return@mapNotNull null
                if (profile.optString("status", "") != "pending") return@mapNotNull null
                PendingUser(
                    uid = uid,
                    customId = profile.optString("customId", ""),
                    nickname = profile.optString("nickname", ""),
                    isGuest = profile.optBoolean("isGuest", false),
                    requestedAt = profile.optLong("requestedAt", 0L)
                )
            }
        }
    }

    /** 승인된 사용자 목록(관리자 전용). */
    fun listApprovedUsers(databaseUrl: String?, apiKey: String?): Result<List<ApprovedUser>> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val users = readAllProfiles(base, token) ?: return@runCatching emptyList()
            users.keySet().mapNotNull { uid ->
                val profile = users.getJSONObject(uid).optJSONObject("profile") ?: return@mapNotNull null
                if (profile.optString("status", "") != "approved") return@mapNotNull null
                ApprovedUser(
                    uid = uid,
                    customId = profile.optString("customId", ""),
                    nickname = profile.optString("nickname", ""),
                    isGuest = profile.optBoolean("isGuest", false),
                    permissions = Permissions.fromProfile(profile)
                )
            }
        }
    }

    /** 가입 승인(관리자 전용): allowedUsers 등록 + status=approved + 선택한 기능 범위 기록. */
    fun approveUser(databaseUrl: String?, apiKey: String?, targetUid: String, permissions: Permissions): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            putOrThrow(base, "allowedUsers/$targetUid", token, "true")
            val body = JSONObject().apply { put("status", "approved"); put("permissions", permissions.toJson()) }
            patchOrThrow(base, "users/$targetUid/profile", token, body.toString())
        }
    }

    /** 이미 승인된 사용자의 기능 범위만 바꾼다(관리자 전용). */
    fun updatePermissions(databaseUrl: String?, apiKey: String?, targetUid: String, permissions: Permissions): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            patchOrThrow(base, "users/$targetUid/profile", token, JSONObject().apply { put("permissions", permissions.toJson()) }.toString())
        }
    }

    /** 가입 거절(관리자 전용): status=rejected만 기록(allowedUsers엔 애초에 등록 안 됨). */
    fun rejectUser(databaseUrl: String?, apiKey: String?, targetUid: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            patchOrThrow(base, "users/$targetUid/profile", token, JSONObject().apply { put("status", "rejected") }.toString())
        }
    }

    /** 승인 취소(관리자 전용): allowedUsers에서 제거 + status=rejected. */
    fun revokeUser(databaseUrl: String?, apiKey: String?, targetUid: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            deleteOrThrow(base, "allowedUsers/$targetUid", token)
            patchOrThrow(base, "users/$targetUid/profile", token, JSONObject().apply { put("status", "rejected") }.toString())
        }
    }

    /**
     * 내 계정 데이터를 스스로 지운다(설정 화면 "계정 삭제") — `users/{uid}` 전체(프로필/루틴/캘린더/
     * 계산기 등)를 삭제한다. 규칙상 `allowedUsers/{uid}`는 관리자만 지울 수 있어 여기선 못 건드리지만,
     * 어차피 이 뒤에 [AuthManager.deleteAccount]로 Firebase 계정 자체를 지우면 그 uid로는 다시는
     * 로그인할 수 없으니 무해한 흔적으로 남는다. `usernames/{customId}` 선점도 규칙상 영구 고정이라
     * 이 아이디는 이후 아무도(본인 포함) 다시 쓸 수 없다 — 삭제 전에 사용자에게 반드시 고지할 것.
     */
    fun deleteMyData(databaseUrl: String?, apiKey: String?): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            deleteOrThrow(base, "users/$uid", token)
        }
    }
}
