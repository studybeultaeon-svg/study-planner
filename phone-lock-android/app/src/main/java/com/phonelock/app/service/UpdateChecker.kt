package com.phonelock.app.service

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * GitHub Releases(공부앱 웹앱과 같이 쓰는 공개 저장소 studybeultaeon-svg/study-planner)에 올려둔
 * 안드로이드 APK 릴리스를 확인한다. 태그명은 "android-<versionCode>" 규칙(데스크탑은 "desktop-<빌드타임스탬프>"
 * 규칙을 따로 씀 — [phone-lock-desktop] DesktopUpdateChecker 참고)을 쓰고, 그 릴리스에 첨부된 .apk
 * 에셋의 다운로드 URL을 함께 반환한다. 공개(public) 저장소라 토큰 없이 조회 가능하다. 2026-08-30 이전엔
 * 네트워크/파싱 오류를 조용히 삼켜 null로 반환했으나, 그러면 "확인 실패"와 "정말 최신 버전"을 구분할 수
 * 없어(요청 한도 초과 시에도 "최신 버전"으로 잘못 표시되던 버그) 지금은 Result로 실패를 그대로 알린다.
 */
object UpdateChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/studybeultaeon-svg/study-planner/releases"
    private const val TAG_PREFIX = "android-"
    private const val TIMEOUT_MS = 6_000

    data class LatestRelease(val versionCode: Long, val apkUrl: String)

    /** 이전엔 네트워크 실패(요청 한도 초과 등)와 "확인해보니 진짜 최신 버전"을 구분 못 하고 둘 다 null로
     *  뭉뚱그렸다 — 그래서 API가 실패해도 화면엔 "최신 버전입니다"라고 잘못 표시됐다(2026-08-30 발견).
     *  이제 실패는 Result.failure로 던져 호출부가 "확인 실패"와 "최신 버전"을 구분할 수 있게 한다. */
    suspend fun checkLatestAndroidRelease(): Result<LatestRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val body = fetch(RELEASES_URL)
            val releases = JSONArray(body)
            var best: LatestRelease? = null
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                val tag = release.optString("tag_name", "")
                if (!tag.startsWith(TAG_PREFIX)) continue
                val versionCode = tag.removePrefix(TAG_PREFIX).toLongOrNull() ?: continue
                val assets = release.optJSONArray("assets") ?: continue
                var apkUrl: String? = null
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url").ifBlank { null }
                        break
                    }
                }
                val current = best
                if (apkUrl != null && (current == null || versionCode > current.versionCode)) {
                    best = LatestRelease(versionCode, apkUrl)
                }
            }
            best
        }
    }

    /** 실패 시 응답 코드/메시지를 그대로 던진다 — 예전엔 여기서 null로 삼켜서 원인을 알 수 없었다. */
    private fun fetch(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code != 200) error("GitHub 응답 코드 $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
