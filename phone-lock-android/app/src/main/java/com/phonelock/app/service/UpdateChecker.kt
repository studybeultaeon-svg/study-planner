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
 * 에셋의 다운로드 URL을 함께 반환한다. 공개(public) 저장소라 토큰 없이 조회 가능하며, PomodoroSyncClient와
 * 같은 fail-safe 원칙으로 네트워크/파싱 오류가 나도 예외를 던지지 않고 조용히 null을 반환한다.
 */
object UpdateChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/studybeultaeon-svg/study-planner/releases"
    private const val TAG_PREFIX = "android-"
    private const val TIMEOUT_MS = 6_000

    data class LatestRelease(val versionCode: Long, val apkUrl: String)

    /** 저장소의 모든 릴리스를 훑어 "android-" 태그 중 versionCode가 가장 큰 것을 찾는다(latest release가
     *  항상 안드로이드 릴리스라는 보장이 없어 단일 /releases/latest가 아니라 목록 전체를 본다). */
    suspend fun checkLatestAndroidRelease(): LatestRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val body = fetch(RELEASES_URL) ?: return@runCatching null
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
        }.getOrNull()
    }

    private fun fetch(urlString: String): String? {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != 200) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
