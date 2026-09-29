package com.phonelock.desktop.monitor

import org.json.JSONArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * GitHub Releases(공부앱 웹앱과 같이 쓰는 공개 저장소 studybeultaeon-svg/study-planner)에 올려둔
 * 데스크탑 배포물(135차부터 app-image zip 우선, 없으면 옛 릴리스의 exe/msi) 릴리스를 확인한다. 태그명은 "desktop-<BuildInfo.BUILD_TIMESTAMP>"
 * 규칙(안드로이드는 "android-<versionCode>" — [com.phonelock.app.service.UpdateChecker] 참고)을
 * 쓰고, 그 릴리스에 첨부된 exe/msi 에셋의 다운로드 URL을 함께 반환한다. 공개 저장소라 토큰 없이 조회
 * 가능하다. 2026-08-30 이전엔 네트워크/파싱 오류를 조용히 삼켜 null로 반환했으나, 그러면 "확인 실패"와
 * "정말 최신 버전"을 구분할 수 없어(요청 한도 초과 시에도 "최신 버전"으로 잘못 표시되던 버그) 지금은
 * Result로 실패를 그대로 알린다.
 */
object DesktopUpdateChecker {
    /** 135차: per_page 없이 부르면 30개만 오는데, 그 정렬 기준이 발행 시각이 아니라 "태그가 가리키는
     *  커밋의 날짜"라서 새 릴리스가 1페이지 밖으로 밀려 조용히 "최신 버전"이 될 수 있다(안드로이드판
     *  UpdateChecker에 자세한 실측 근거). 100개로 늘려 여유를 둔다. */
    private const val RELEASES_URL = "https://api.github.com/repos/studybeultaeon-svg/study-planner/releases?per_page=100"
    private const val TAG_PREFIX = "desktop-"
    private const val TIMEOUT_SECONDS = 6L

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    data class LatestRelease(val buildTimestamp: Long, val installerUrl: String)

    /**
     * 136차: 조건부 요청(ETag)용 캐시 — 안드로이드판 [com.phonelock.app.service.UpdateChecker]와 같은 이유이고,
     * 데스크탑 쪽이 낭비가 더 크다. 자바 `HttpClient`는 안드로이드의 `HttpURLConnection`과 달리
     * `Accept-Encoding: gzip`을 자동으로 붙이지도, 응답을 자동으로 풀지도 않아서 이 응답을 **압축 없이
     * 통째로**(실측 390KB) 15분마다 받아왔다. 직전 응답의 ETag를 `If-None-Match`로 보내면 릴리스를 새로
     * 올리기 전까진 본문 없는 304로 끝난다(하루 약 37MB → 0에 가깝게).
     *
     * 단, 시간당 요청 한도(비인증 IP당 60회)는 304도 똑같이 차감한다(2026-09-24 실측) — 한도 문제는
     * 이 캐시가 아니라 실패 시 백오프([com.phonelock.desktop.data.Repository])가 다룬다.
     */
    @Volatile private var cachedEtag: String? = null
    @Volatile private var cachedResult: LatestRelease? = null

    /** 이전엔 네트워크 실패(요청 한도 초과 등)와 "확인해보니 진짜 최신 버전"을 구분 못 하고 둘 다 null로
     *  뭉뚱그렸다 — 그래서 API가 실패해도 화면엔 "최신 버전입니다"라고 잘못 표시됐다(2026-08-30 발견,
     *  안드로이드판 UpdateChecker와 동일한 문제). 이제 실패는 Result.failure로 던져 호출부가 구분한다. */
    fun checkLatestDesktopRelease(): Result<LatestRelease?> = runCatching {
        val fetched = fetch(RELEASES_URL) ?: return@runCatching cachedResult
        val releases = JSONArray(fetched.body)
        var best: LatestRelease? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val tag = release.optString("tag_name", "")
            if (!tag.startsWith(TAG_PREFIX)) continue
            val buildTimestamp = tag.removePrefix(TAG_PREFIX).toLongOrNull() ?: continue
            val assets = release.optJSONArray("assets") ?: continue
            // 135차: app-image zip이 있으면 그걸 우선한다 — 실제로 실행 중인 app-image 폴더를 바꿔치기할 수
            // 있는 유일한 형식이다. exe/msi는 zip이 없는 옛 릴리스를 위한 폴백으로만 남긴다.
            var installerUrl: String? = null
            var fallbackInstallerUrl: String? = null
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url").ifBlank { null } ?: continue
                if (name.endsWith(".zip", ignoreCase = true)) {
                    installerUrl = url
                    break
                }
                if (fallbackInstallerUrl == null &&
                    (name.endsWith(".exe", ignoreCase = true) || name.endsWith(".msi", ignoreCase = true))
                ) {
                    fallbackInstallerUrl = url
                }
            }
            if (installerUrl == null) installerUrl = fallbackInstallerUrl
            val current = best
            if (installerUrl != null && (current == null || buildTimestamp > current.buildTimestamp)) {
                best = LatestRelease(buildTimestamp, installerUrl)
            }
        }
        // 파싱까지 다 끝난 뒤에 함께 저장한다 — ETag를 먼저 저장하면 파싱이 실패했을 때 다음 확인이
        // 304로 끝나면서 그 실패한 결과가 계속 재사용된다.
        cachedResult = best
        cachedEtag = fetched.etag
        best
    }

    private class Fetched(val body: String, val etag: String?)

    /** 직전 응답과 같으면(304) null을 돌려주고, 호출부는 [cachedResult]를 그대로 재사용한다. */
    private fun fetch(urlString: String): Fetched? {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(urlString))
            .header("Accept", "application/vnd.github+json")
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .GET()
        cachedEtag?.let { builder.header("If-None-Match", it) }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 304) return null
        if (response.statusCode() != 200) error("GitHub 응답 코드 ${response.statusCode()}")
        return Fetched(response.body(), response.headers().firstValue("ETag").orElse(null))
    }
}
