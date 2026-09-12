package com.phonelock.app.service

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * "소셜" 개편(92차~) Phase 1 — 모임(그룹) 안의 "💬 대화" 채널. [SocialGroupSyncClient]와 같은
 * HttpURLConnection REST 패턴을 그대로 따르되, 채팅은 `groups/{groupId}/...` 밑이 아니라 최상위
 * `groupChats/{groupId}/messages/{msgId}`에 둔다 — 모임 멤버십(`groups/{groupId}/members`)을 그대로
 * 참여자 판정에 재사용하면(보안 규칙에서 직접 참조) 별도 참여자 목록을 매번 동기화할 필요가 없어진다.
 * 실시간성은 "앱이 켜져있는 동안만"(사용자 확정 사항)이라 SSE/FCM 없이 화면이 보이는 동안 짧은 주기로
 * 폴링한다(이 프로젝트의 다른 실시간에 가까운 기능들 — 무전기 7초 폴링 등 — 과 동일한 스타일).
 * 1:1 DM은 Phase 2에서 추가 예정(IDEAS.md/HANDOFF.md 92차 참고).
 */
object ChatSyncClient {
    private const val TIMEOUT_MS = 5_000

    data class ChatMessage(
        val msgId: String,
        val senderUid: String,
        val senderName: String,
        val text: String,
        val sentAtMillis: Long,
        val reactions: Map<String, String> = emptyMap()
    )

    /** 내 1:1 대화 목록 한 줄 — [peerLabel]은 상대의 커스텀 아이디(닉네임 아님, 검색 당시 알 수 있는
     *  값이라 그대로 씀). */
    data class DmChatPreview(val chatId: String, val peerUid: String, val peerLabel: String, val updatedAtMillis: Long)

    /** 두 uid로 결정적 chatId를 만든다 — 정렬 순서가 고정이라 누가 먼저 시작했든 항상 같은 방으로 모인다. */
    private fun dmChatId(uidA: String, uidB: String): String {
        val sorted = listOf(uidA, uidB).sorted()
        return "dm_${sorted[0]}_${sorted[1]}"
    }

    /** DM방을 만들거나(없으면) 이미 있으면 그대로 chatId만 반환 — 양쪽 `users/{uid}/dmChatIds`에도
     *  서로를 등록해야 검색 없이도 대화 목록에서 다시 찾을 수 있다. */
    suspend fun ensureDmChat(databaseUrl: String?, apiKey: String?, otherUid: String, otherLabel: String): Result<String> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, myUid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val chatId = dmChatId(myUid, otherUid)
                val now = System.currentTimeMillis()
                val existing = getRaw(URL("$base/dmChats/$chatId/info.json?auth=$token"))
                if (existing.isNullOrBlank() || existing == "null") {
                    putJson(
                        URL("$base/dmChats/$chatId/info.json?auth=$token"),
                        JSONObject().apply {
                            put("participants", JSONObject().apply { put(myUid, true); put(otherUid, true) })
                            put("createdAt", now)
                        }
                    )
                }
                val myProfile = runCatching { AccountSyncClient.fetchMyProfile(databaseUrl, apiKey).getOrNull() }.getOrNull()
                val myLabel = myProfile?.optString("customId", "")?.takeIf { it.isNotBlank() } ?: myUid
                putJson(
                    URL("$base/users/$myUid/dmChatIds/$chatId.json?auth=$token"),
                    JSONObject().apply { put("peerUid", otherUid); put("peerLabel", otherLabel); put("updatedAtMillis", now) }
                )
                putJson(
                    URL("$base/users/$otherUid/dmChatIds/$chatId.json?auth=$token"),
                    JSONObject().apply { put("peerUid", myUid); put("peerLabel", myLabel); put("updatedAtMillis", now) }
                )
                chatId
            }
        }
    }

    /** 내 1:1 대화 목록. */
    suspend fun readMyDmChats(databaseUrl: String?, apiKey: String?): List<DmChatPreview> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: return@runCatching emptyList()
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/users/$uid/dmChatIds.json?auth=$token"))
                if (text.isNullOrBlank() || text == "null") return@runCatching emptyList()
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
    }

    /** DM 메시지 전송 — 성공 시 양쪽 dmChatIds의 updatedAtMillis도 갱신해 대화 목록이 최신순으로 유지되게 한다. */
    suspend fun sendDmMessage(databaseUrl: String?, apiKey: String?, chatId: String, peerUid: String, text: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        if (text.isBlank()) return Result.failure(IllegalStateException("빈 메시지는 보낼 수 없습니다."))
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val senderName = AccountSyncClient.myDisplayName(databaseUrl, apiKey)
                val now = System.currentTimeMillis()
                val body = JSONObject().apply {
                    put("senderUid", uid)
                    put("senderName", senderName)
                    put("text", text.trim())
                    put("sentAtMillis", now)
                }
                val postConn = (URL("$base/dmChats/$chatId/messages.json?auth=$token").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                postConn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = postConn.responseCode
                if (code !in 200..299) {
                    val errorBody = runCatching { postConn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    postConn.disconnect()
                    error("메시지 전송에 실패했습니다. ($code: ${errorBody ?: "응답 없음"})")
                }
                postConn.disconnect()
                runCatching {
                    putJson(URL("$base/users/$uid/dmChatIds/$chatId/updatedAtMillis.json?auth=$token"), now.toString(), raw = true)
                    putJson(URL("$base/users/$peerUid/dmChatIds/$chatId/updatedAtMillis.json?auth=$token"), now.toString(), raw = true)
                }
                Unit
            }
        }
    }

    /** 최근 DM 메시지 최대 200개(시각순). 98차엔 실패를 전부 삼켜 빈 목록으로 돌려주던 걸,
     *  전송은 되는데 목록엔 안 뜨는 제보의 진짜 원인(쓰기 vs 읽기 중 어느 쪽인지)을 구분하려고
     *  [Result]로 바꿔 실패 사유를 그대로 드러낸다(sendMessage와 동일 패턴, 데스크탑판과 대칭). */
    suspend fun readDmMessages(databaseUrl: String?, apiKey: String?, chatId: String): Result<List<ChatMessage>> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val query = "orderBy=%22sentAtMillis%22&limitToLast=200"
                val (code, text, errorBody) = getRawWithStatus(URL("$base/dmChats/$chatId/messages.json?auth=$token&$query"))
                if (code !in 200..299) {
                    error("메시지 목록을 불러오지 못했습니다. ($code: ${errorBody ?: "응답 없음"})")
                }
                if (text.isNullOrBlank() || text == "null") return@runCatching emptyList()
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
            }
        }
    }

    /** DM 메시지 이모지 리액션 토글. */
    suspend fun toggleDmMessageReaction(databaseUrl: String?, apiKey: String?, chatId: String, msgId: String, emoji: String, alreadySet: Boolean): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val url = URL("$base/dmChats/$chatId/messages/$msgId/reactions/$uid.json?auth=$token")
                if (alreadySet) {
                    if (!sendDelete(url)) error("리액션 삭제에 실패했습니다.")
                } else {
                    putJson(url, JSONObject.quote(emoji), raw = true)
                }
            }
        }
    }

    /** 그룹 대화방 메시지 전송. */
    suspend fun sendGroupMessage(databaseUrl: String?, apiKey: String?, groupId: String, text: String): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        if (text.isBlank()) return Result.failure(IllegalStateException("빈 메시지는 보낼 수 없습니다."))
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val senderName = AccountSyncClient.myDisplayName(databaseUrl, apiKey)
                val body = JSONObject().apply {
                    put("senderUid", uid)
                    put("senderName", senderName)
                    put("text", text.trim())
                    put("sentAtMillis", System.currentTimeMillis())
                }
                val postConn = (URL("$base/groupChats/$groupId/messages.json?auth=$token").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                postConn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = postConn.responseCode
                if (code !in 200..299) {
                    val errorBody = runCatching { postConn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    postConn.disconnect()
                    error("메시지 전송에 실패했습니다. ($code: ${errorBody ?: "응답 없음"})")
                }
                postConn.disconnect()
            }
        }
    }

    /** 최근 메시지 최대 200개(시각순) — 화면이 열려있는 동안 짧은 주기로 다시 호출해 폴링한다.
     *  98차: 실패를 삼켜 빈 목록으로 돌려주던 걸 [Result]로 바꿔 실패 사유를 드러낸다(위 참고). */
    suspend fun readGroupMessages(databaseUrl: String?, apiKey: String?, groupId: String): Result<List<ChatMessage>> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val query = "orderBy=%22sentAtMillis%22&limitToLast=200"
                val (code, text, errorBody) = getRawWithStatus(URL("$base/groupChats/$groupId/messages.json?auth=$token&$query"))
                if (code !in 200..299) {
                    error("메시지 목록을 불러오지 못했습니다. ($code: ${errorBody ?: "응답 없음"})")
                }
                if (text.isNullOrBlank() || text == "null") return@runCatching emptyList()
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
            }
        }
    }

    /** 이모지 리액션 토글 — 이미 같은 이모지를 남겼으면 지우고, 아니면 덮어쓴다(사람당 메시지 하나에 한 개만). */
    suspend fun toggleGroupMessageReaction(databaseUrl: String?, apiKey: String?, groupId: String, msgId: String, emoji: String, alreadySet: Boolean): Result<Unit> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = resolveIdentity(apiKey) ?: error("먼저 로그인을 해야 합니다.")
                val base = databaseUrl.trimEnd('/')
                val url = URL("$base/groupChats/$groupId/messages/$msgId/reactions/$uid.json?auth=$token")
                if (alreadySet) {
                    if (!sendDelete(url)) error("리액션 삭제에 실패했습니다.")
                } else {
                    putJson(url, JSONObject.quote(emoji), raw = true)
                }
            }
        }
    }

    /** 최신 메시지 1개만 가볍게 조회 — [WalkieTalkieService]의 새 메시지 알림 폴링 전용(전체 200개를
     *  매번 받아오면 낭비라 별도로 둠). */
    suspend fun peekLatestGroupMessage(databaseUrl: String?, apiKey: String?, groupId: String): ChatMessage? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: return@runCatching null
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/groupChats/$groupId/messages.json?auth=$token&orderBy=%22sentAtMillis%22&limitToLast=1"))
                parseLatestMessage(text)
            }.getOrNull()
        }
    }

    /** [peekLatestGroupMessage]의 DM판. */
    suspend fun peekLatestDmMessage(databaseUrl: String?, apiKey: String?, chatId: String): ChatMessage? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, _) = resolveIdentity(apiKey) ?: return@runCatching null
                val base = databaseUrl.trimEnd('/')
                val text = getRaw(URL("$base/dmChats/$chatId/messages.json?auth=$token&orderBy=%22sentAtMillis%22&limitToLast=1"))
                parseLatestMessage(text)
            }.getOrNull()
        }
    }

    private fun parseLatestMessage(text: String?): ChatMessage? {
        if (text.isNullOrBlank() || text == "null") return null
        val json = JSONObject(text)
        val msgId = json.keys().asSequence().firstOrNull() ?: return null
        val m = json.getJSONObject(msgId)
        return ChatMessage(
            msgId = msgId,
            senderUid = m.optString("senderUid", ""),
            senderName = m.optString("senderName", "사용자"),
            text = m.optString("text", ""),
            sentAtMillis = m.optLong("sentAtMillis", 0L)
        )
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

    /** [getRaw]와 달리 실패해도 상태코드/에러 본문을 그대로 반환 — 메시지 읽기 실패 사유를
     *  화면에 보여줘야 하는 채팅 목록 조회 전용(98차). */
    private data class RawResponse(val code: Int, val body: String?, val errorBody: String?)
    private fun getRawWithStatus(url: URL): RawResponse {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val errorBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            conn.disconnect()
            return RawResponse(code, null, errorBody)
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return RawResponse(code, body, null)
    }

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

    private suspend fun resolveIdentity(apiKey: String): Pair<String, String>? {
        val googleUser = AuthManager.currentUser ?: return null
        val token = runCatching { googleUser.getIdToken(false).await().token }.getOrNull()
        if (token.isNullOrBlank()) return null
        return token to googleUser.uid
    }
}
