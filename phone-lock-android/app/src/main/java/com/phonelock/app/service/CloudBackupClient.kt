package com.phonelock.app.service

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 클라우드 자동 백업(82차, 감사보고서 §9) — [PhoneLockRepository.exportBackupJson]이 만든 전체 백업
 * JSON을 Firebase Storage REST API로 `backups/{uid}/{timestamp}.json`에 업로드한다. Firebase SDK 중
 * Storage는 이 프로젝트에 없어(Auth만 있음, DECISIONS.md 참고) REST(`firebasestorage.googleapis.com`)로
 * 직접 호출 — 다른 SyncClient들이 RTDB를 REST로 두드리는 것과 같은 패턴.
 *
 * **사전 조건**: Firebase 콘솔에서 Storage 제품을 먼저 활성화하고 [[phone-lock-android/firebase-storage.rules]]를
 * 배포해야 실제로 동작한다(활성화 전에는 404로 실패 — 아래 [uploadBackup] 결과가 실패로 돌아오므로 UI에서
 * 사용자에게 사유를 그대로 보여줄 것). 버킷 이름은 `<projectId>.appspot.com`으로 추정한다 — 프로젝트가
 * 생성 시점에 따라 `.firebasestorage.app`일 수도 있어, 실패 시 콘솔의 Storage 탭에서 실제 버킷 이름을
 * 확인해 [BUCKET_SUFFIX]를 바꿔야 할 수 있다.
 */
object CloudBackupClient {
    private const val TIMEOUT_MS = 15_000
    private const val BUCKET_SUFFIX = ".appspot.com"

    private fun projectIdFrom(databaseUrl: String?): String? {
        // "https://<projectId>-default-rtdb.firebaseio.com" 또는 "https://<projectId>.firebaseio.com" 형태.
        val host = runCatching { URL(databaseUrl ?: return null).host }.getOrNull() ?: return null
        return host.removeSuffix(".firebaseio.com").removeSuffix("-default-rtdb")
    }

    /** 성공하면 업로드된 오브젝트 경로, 실패하면 사유 메시지. */
    suspend fun uploadBackup(databaseUrl: String?, json: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val projectId = projectIdFrom(databaseUrl) ?: error("Firebase 프로젝트 정보 없음")
            val user = AuthManager.currentUser ?: error("로그인이 필요합니다")
            val idToken = user.getIdToken(false).await().token ?: error("인증 토큰을 가져오지 못했습니다")
            val objectPath = "backups/${user.uid}/${System.currentTimeMillis()}.json"
            val encodedPath = URLEncoder.encode(objectPath, "UTF-8")
            val url = URL("https://firebasestorage.googleapis.com/v0/b/$projectId$BUCKET_SUFFIX/o?uploadType=media&name=$encodedPath")

            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Firebase $idToken")
                connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val err = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                    error("업로드 실패(HTTP $code): ${err ?: "응답 없음"}")
                }
                objectPath
            } finally {
                connection.disconnect()
            }
        }
    }
}
