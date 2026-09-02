package com.phonelock.desktop.monitor

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 클라우드 자동 백업(82차, 감사보고서 §9, 안드로이드판과 대칭) — 전체 데이터 내보내기 JSON을 Firebase
 * Storage REST API로 `backups/{uid}/{timestamp}.json`에 업로드한다. 데스크탑엔 Firebase SDK가 없어(REST만
 * 사용) [AuthManager.ensureIdToken]으로 얻은 ID 토큰을 그대로 씀.
 *
 * **사전 조건**: Firebase 콘솔에서 Storage 제품을 먼저 활성화하고 `firebase-storage.rules`를 배포해야
 * 실제로 동작한다(안드로이드판 CloudBackupClient.kt 주석 참고).
 */
object CloudBackupClient {
    private const val TIMEOUT_MS = 15_000
    private const val BUCKET_SUFFIX = ".appspot.com"

    private fun projectIdFrom(databaseUrl: String?): String? {
        val host = runCatching { URL(databaseUrl ?: return null).host }.getOrNull() ?: return null
        return host.removeSuffix(".firebaseio.com").removeSuffix("-default-rtdb")
    }

    /** 성공하면 업로드된 오브젝트 경로, 실패하면 사유 메시지. */
    fun uploadBackup(databaseUrl: String?, apiKey: String?, json: String): Result<String> = runCatching {
        val projectId = projectIdFrom(databaseUrl) ?: error("Firebase 프로젝트 정보 없음")
        val key = apiKey ?: error("Firebase API 키 없음")
        val uid = AuthManager.currentUid ?: error("로그인이 필요합니다")
        val idToken = AuthManager.ensureIdToken(key) ?: error("인증 토큰을 가져오지 못했습니다")
        val objectPath = "backups/$uid/${System.currentTimeMillis()}.json"
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
