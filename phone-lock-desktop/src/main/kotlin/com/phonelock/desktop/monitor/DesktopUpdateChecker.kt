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
 * 가능하다. 2026-08-30 이전엔 네트워크/파싱 오류를 조용히 삼켜 null로 반환했으나, 그러면 "확인 실패"와
 * "정말 최신 버전"을 구분할 수 없어(요청 한도 초과 시에도 "최신 버전"으로 잘못 표시되던 버그) 지금은
 * Result로 실패를 그대로 알린다.
 */
object DesktopUpdateChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/studybeultaeon-svg/study-planner/releases"
    private const val TAG_PREFIX = "desktop-"
    private const val TIMEOUT_SECONDS = 6L

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    data class LatestRelease(val buildTimestamp: Long, val installerUrl: String)

    /** 이전엔 네트워크 실패(요청 한도 초과 등)와 "확인해보니 진짜 최신 버전"을 구분 못 하고 둘 다 null로
     *  뭉뚱그렸다 — 그래서 API가 실패해도 화면엔 "최신 버전입니다"라고 잘못 표시됐다(2026-08-30 발견,
     *  안드로이드판 UpdateChecker와 동일한 문제). 이제 실패는 Result.failure로 던져 호출부가 구분한다. */
    fun checkLatestDesktopRelease(): Result<LatestRelease?> = runCatching {
        val body = fetch(RELEASES_URL)
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
    }

    private fun fetch(urlString: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(urlString))
            .header("Accept", "application/vnd.github+json")
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) error("GitHub 응답 코드 ${response.statusCode()}")
        return response.body()
    }
}
