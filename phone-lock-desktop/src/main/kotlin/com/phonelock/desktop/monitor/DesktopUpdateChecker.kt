package com.phonelock.desktop.monitor

import org.json.JSONArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * GitHub Releases(공부앱 웹앱과 같이 쓰는 공개 저장소 studybeultaeon-svg/study-planner)에 올려둔
 * 데스크탑 설치파일(exe/msi) 릴리스를 확인한다. 태그명은 "desktop-<BuildInfo.BUILD_TIMESTAMP>"
 * 규칙(안드로이드는 "android-<versionCode>" — [com.phonelock.app.service.UpdateChecker] 참고)을
 * 쓰고, 그 릴리스에 첨부된 exe/msi 에셋의 다운로드 URL을 함께 반환한다. 공개 저장소라 토큰 없이 조회
 * 가능하며, PomodoroSyncClient와 같은 fail-safe 원칙으로 네트워크/파싱 오류가 나도 예외를 던지지 않고
 * 조용히 null을 반환한다.
 */
object DesktopUpdateChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/studybeultaeon-svg/study-planner/releases"
    private const val TAG_PREFIX = "desktop-"
    private const val TIMEOUT_SECONDS = 6L

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    data class LatestRelease(val buildTimestamp: Long, val installerUrl: String)

    /** 저장소의 모든 릴리스를 훑어 "desktop-" 태그 중 빌드 타임스탬프가 가장 큰 것을 찾는다. */
    fun checkLatestDesktopRelease(): LatestRelease? = runCatching {
        val body = fetch(RELEASES_URL) ?: return@runCatching null
        val releases = JSONArray(body)
        var best: LatestRelease? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val tag = release.optString("tag_name", "")
            if (!tag.startsWith(TAG_PREFIX)) continue
            val buildTimestamp = tag.removePrefix(TAG_PREFIX).toLongOrNull() ?: continue
            val assets = release.optJSONArray("assets") ?: continue
            var installerUrl: String? = null
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".exe", ignoreCase = true) || name.endsWith(".msi", ignoreCase = true)) {
                    installerUrl = asset.optString("browser_download_url").ifBlank { null }
                    break
                }
            }
            val current = best
            if (installerUrl != null && (current == null || buildTimestamp > current.buildTimestamp)) {
                best = LatestRelease(buildTimestamp, installerUrl)
            }
        }
        best
    }.getOrNull()

    private fun fetch(urlString: String): String? = runCatching {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(urlString))
            .header("Accept", "application/vnd.github+json")
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) null else response.body()
    }.getOrNull()
}
