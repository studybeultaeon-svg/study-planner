package com.phonelock.desktop.monitor

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * "소셜" 개편(92차~) Phase 1 — 모임 안의 "💬 대화" 채널. [SocialGroupSyncClient]와 같은 HttpClient
 * 동기 호출 패턴(안드로이드판과 달리 desktop은 suspend가 아니라 호출부에서 `Thread { }.start()`로
 * 감싸는 방식)을 그대로 따르되, 채팅은 `groups/{groupId}/...` 밑이 아니라 최상위
 * `groupChats/{groupId}/messages/{msgId}`에 둔다 — 모임 멤버십(`groups/{groupId}/members`)을 그대로
 * 참여자 판정에 재사용하면(보안 규칙에서 직접 참조) 별도 참여자 목록을 동기화할 필요가 없어진다.
 * 실시간성은 "앱이 켜져있는 동안만"(사용자 확정 사항)이라 SSE/FCM 없이 화면이 보이는 동안 짧은 주기로
 * 폴링한다. 1:1 DM은 Phase 2에서 추가 예정(IDEAS.md/HANDOFF.md 92차 참고).
 */
object ChatSyncClient {
    private const val TIMEOUT_SECONDS = 5L
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    data class ChatMessage(
        val msgId: String,
        val senderUid: String,
        val senderName: String,
        val text: String,
        val sentAtMillis: Long,
        val reactions: Map<String, String> = emptyMap()
    )

    /** 내 1:1 대화 목록 한 줄(안드로이드판과 대칭) — [peerLabel]은 상대의 커스텀 아이디. */
    data class DmChatPreview(val chatId: String, val peerUid: String, val peerLabel: String, val updatedAtMillis: Long)

    private fun dmChatId(uidA: String, uidB: String): String {
        val sorted = listOf(uidA, uidB).sorted()
        return "dm_${sorted[0]}_${sorted[1]}"
    }

    private fun put(base: String, path: String, token: String, bodyJson: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/$path.json?auth=$token"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .PUT(HttpRequest.BodyPublishers.ofString(bodyJson))
            .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
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

    /** 커스텀 아이디로 상대를 찾는다(카카오톡 ID검색과 동일한 개념, 안드로이드판과 대칭) — `usernames/{code}`
     *  공개 인덱스를 재사용. 찾으면 (uid, 정규화된 코드), 없거나 나 자신이면 null. */
    fun searchUserByCode(databaseUrl: String?, apiKey: String?, code: String): Pair<String, String>? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank() || code.isBlank()) return null
        return runCatching {
            val (token, myUid) = resolveIdentity(apiKey) ?: return null
            val base = databaseUrl.trimEnd('/')
            val normalized = code.trim().uppercase()
            val text = get(base, "usernames/$normalized", token)?.trim('"')?.takeIf { it.isNotBlank() && it != "null" } ?: return null
            if (text == myUid) return null
            text to normalized
        }.getOrNull()
    }

    /** DM방을 만들거나(없으면) 이미 있으면 그대로 chatId만 반환 — 양쪽 `users/{uid}/dmChatIds`에도
     *  서로를 등록한다(안드로이드판과 대칭). */
    fun ensureDmChat(databaseUrl: String?, apiKey: String?, otherUid: String, otherLabel: String): Result<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, myUid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val chatId = dmChatId(myUid, otherUid)
            val now = System.currentTimeMillis()
            val existing = get(base, "dmChats/$chatId/info", token)
            if (existing.isNullOrBlank() || existing == "null") {
                val infoBody = JSONObject().apply {
                    put("participants", JSONObject().apply { put(myUid, true); put(otherUid, true) })
                    put("createdAt", now)
                }
                put(base, "dmChats/$chatId/info", token, infoBody.toString())
            }
            val myProfile = runCatching { AccountSyncClient.fetchMyProfile(databaseUrl, apiKey).getOrNull() }.getOrNull()
            val myLabel = myProfile?.optString("customId", "")?.takeIf { it.isNotBlank() } ?: myUid
            put(
                base, "users/$myUid/dmChatIds/$chatId", token,
                JSONObject().apply { put("peerUid", otherUid); put("peerLabel", otherLabel); put("updatedAtMillis", now) }.toString()
            )
            put(
                base, "users/$otherUid/dmChatIds/$chatId", token,
                JSONObject().apply { put("peerUid", myUid); put("peerLabel", myLabel); put("updatedAtMillis", now) }.toString()
            )
            chatId
        }
    }

    /** 내 1:1 대화 목록. */
    fun readMyDmChats(databaseUrl: String?, apiKey: String?): List<DmChatPreview> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptyList()
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: return emptyList()
            val base = databaseUrl.trimEnd('/')
            val text = get(base, "users/$uid/dmChatIds", token) ?: return emptyList()
            val json = JSONObject(text)
            json.keys().asSequence().map { chatId ->
                val c = json.getJSONObject(chatId)
                DmChatPreview(
                    chatId = chatId,
                    peerUid = c.optString("peerUid", ""),
                    peerLabel = c.optString("peerLabel", "상대"),
                    updatedAtMillis = c.optLong("updatedAtMillis", 0L)
                )
            }.sortedByDescending { it.updatedAtMillis }.toList()
        }.getOrDefault(emptyList())
    }

    /** DM 메시지 전송 — 성공 시 양쪽 dmChatIds의 updatedAtMillis도 갱신. */
    fun sendDmMessage(databaseUrl: String?, apiKey: String?, chatId: String, peerUid: String, text: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        if (text.isBlank()) return Result.failure(IllegalStateException("빈 메시지는 보낼 수 없습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val senderName = myDisplayName(databaseUrl, apiKey)
            val now = System.currentTimeMillis()
            val body = JSONObject().apply {
                put("senderUid", uid)
                put("senderName", senderName)
                put("text", text.trim())
                put("sentAtMillis", now)
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/dmChats/$chatId/messages.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("메시지 전송에 실패했습니다. (${response.statusCode()}: ${response.body()})")
            }
            runCatching {
                put(base, "users/$uid/dmChatIds/$chatId/updatedAtMillis", token, now.toString())
                put(base, "users/$peerUid/dmChatIds/$chatId/updatedAtMillis", token, now.toString())
            }
            Unit
        }
    }

    /** 최근 DM 메시지 최대 200개(시각순). */
    fun readDmMessages(databaseUrl: String?, apiKey: String?, chatId: String): List<ChatMessage> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptyList()
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: return emptyList()
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/dmChats/$chatId/messages.json?auth=$token&orderBy=%22sentAtMillis%22&limitToLast=200"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return emptyList()
            val text = response.body()
            if (text.isNullOrBlank() || text == "null") return emptyList()
            val json = JSONObject(text)
            json.keys().asSequence().map { msgId ->
                val m = json.getJSONObject(msgId)
                val reactionsObj = m.optJSONObject("reactions")
                val reactions = if (reactionsObj != null) {
                    reactionsObj.keys().asSequence().associateWith { reactionsObj.optString(it, "") }
                } else emptyMap()
                ChatMessage(
                    msgId = msgId,
                    senderUid = m.optString("senderUid", ""),
                    senderName = m.optString("senderName", "사용자"),
                    text = m.optString("text", ""),
                    sentAtMillis = m.optLong("sentAtMillis", 0L),
                    reactions = reactions
                )
            }.sortedBy { it.sentAtMillis }.toList()
        }.getOrDefault(emptyList())
    }

    /** DM 메시지 이모지 리액션 토글. */
    fun toggleDmMessageReaction(databaseUrl: String?, apiKey: String?, chatId: String, msgId: String, emoji: String, alreadySet: Boolean): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val path = "dmChats/$chatId/messages/$msgId/reactions/$uid"
            if (alreadySet) {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$base/$path.json?auth=$token"))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .DELETE()
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) error("리액션 삭제에 실패했습니다.")
            } else {
                put(base, path, token, JSONObject.quote(emoji))
            }
        }
    }

    private fun resolveIdentity(apiKey: String): Pair<String, String>? {
        val uid = AuthManager.currentUid ?: return null
        val token = AuthManager.ensureIdToken(apiKey)
        if (token.isNullOrBlank()) return null
        return token to uid
    }

    private fun myDisplayName(databaseUrl: String?, apiKey: String?): String {
        val fallback = AuthManager.currentEmail ?: AuthManager.currentUid ?: "익명"
        return runCatching {
            val profile = AccountSyncClient.fetchMyProfile(databaseUrl, apiKey).getOrNull() ?: return@runCatching fallback
            profile.optString("nickname", "").ifBlank { null }
                ?: profile.optString("customId", "").ifBlank { null }
                ?: fallback
        }.getOrDefault(fallback)
    }

    /** 그룹 대화방 메시지 전송. */
    fun sendGroupMessage(databaseUrl: String?, apiKey: String?, groupId: String, text: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        if (text.isBlank()) return Result.failure(IllegalStateException("빈 메시지는 보낼 수 없습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val senderName = myDisplayName(databaseUrl, apiKey)
            val body = JSONObject().apply {
                put("senderUid", uid)
                put("senderName", senderName)
                put("text", text.trim())
                put("sentAtMillis", System.currentTimeMillis())
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/groupChats/$groupId/messages.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("메시지 전송에 실패했습니다. (${response.statusCode()}: ${response.body()})")
            }
        }
    }

    /** 최근 메시지 최대 200개(시각순) — 화면이 열려있는 동안 짧은 주기로 다시 호출해 폴링한다. */
    fun readGroupMessages(databaseUrl: String?, apiKey: String?, groupId: String): List<ChatMessage> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptyList()
        return runCatching {
            val (token, _) = resolveIdentity(apiKey) ?: return emptyList()
            val base = databaseUrl.trimEnd('/')
            val query = "orderBy=%22sentAtMillis%22&limitToLast=200"
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/groupChats/$groupId/messages.json?auth=$token&$query"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return emptyList()
            val text = response.body()
            if (text.isNullOrBlank() || text == "null") return emptyList()
            val json = JSONObject(text)
            json.keys().asSequence().map { msgId ->
                val m = json.getJSONObject(msgId)
                val reactionsObj = m.optJSONObject("reactions")
                val reactions = if (reactionsObj != null) {
                    reactionsObj.keys().asSequence().associateWith { reactionsObj.optString(it, "") }
                } else emptyMap()
                ChatMessage(
                    msgId = msgId,
                    senderUid = m.optString("senderUid", ""),
                    senderName = m.optString("senderName", "사용자"),
                    text = m.optString("text", ""),
                    sentAtMillis = m.optLong("sentAtMillis", 0L),
                    reactions = reactions
                )
            }.sortedBy { it.sentAtMillis }.toList()
        }.getOrDefault(emptyList())
    }

    /** 이모지 리액션 토글 — 이미 같은 이모지를 남겼으면 지우고, 아니면 덮어쓴다(사람당 메시지 하나에 한 개만). */
    fun toggleGroupMessageReaction(databaseUrl: String?, apiKey: String?, groupId: String, msgId: String, emoji: String, alreadySet: Boolean): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
            val base = databaseUrl.trimEnd('/')
            val path = "groupChats/$groupId/messages/$msgId/reactions/$uid"
            if (alreadySet) {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$base/$path.json?auth=$token"))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .DELETE()
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) error("리액션 삭제에 실패했습니다.")
            } else {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$base/$path.json?auth=$token"))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .PUT(HttpRequest.BodyPublishers.ofString(JSONObject.quote(emoji)))
                    .build()
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }
        }
    }
}
