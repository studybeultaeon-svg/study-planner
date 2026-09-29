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
    /**
     * 135차: `per_page` 없이 부르면 GitHub이 **30개만** 돌려주는데, 그 정렬 기준이 발행 시각이 아니라
     * "태그가 가리키는 커밋의 날짜"다(실측: `android-1789917685`는 2026-09-20 발행인데 created_at은
     * 2026-09-19). 릴리스가 이미 40개를 넘겨서, 새로 올린 릴리스가 옛 커밋을 가리키면 1페이지 밖으로
     * 밀려 조회 결과에서 통째로 사라지고 앱은 조용히 "최신 버전입니다"라고 표시한다. 100개로 늘려
     * 여유를 둔다(데스크탑판 DesktopUpdateChecker도 동일).
     */
    private const val RELEASES_URL = "https://api.github.com/repos/studybeultaeon-svg/study-planner/releases?per_page=100"
    private const val TAG_PREFIX = "android-"
    private const val TIMEOUT_MS = 6_000

    data class LatestRelease(val versionCode: Long, val apkUrl: String)

    /**
     * 136차: 조건부 요청(ETag)용 캐시. 이 응답은 실측 390KB(gzip 약 35KB)인데 15분마다 받아오면서도
     * 릴리스를 새로 올리기 전까진 내용이 한 글자도 안 바뀐다. 직전 응답의 ETag를 `If-None-Match`로 같이
     * 보내면 바뀐 게 없을 때 GitHub이 본문 없는 304를 돌려주므로 받아올 바이트도, 파싱할 JSON도 없다.
     *
     * 단, 시간당 요청 한도(비인증 IP당 60회)는 304도 똑같이 차감한다(2026-09-24 실측: 304에도
     * `X-RateLimit-Used`가 1씩 오른다) — 한도 문제는 이 캐시가 아니라 실패 시 백오프
     * ([com.phonelock.app.data.PhoneLockRepository.nextUpdateRetryDelayMs]) 쪽에서 다룬다.
     *
     * 프로세스 메모리에만 둔다 — 앱을 껐다 켜면 한 번은 200을 받지만, 확인을 실제로 반복하는 건
     * 오래 떠 있는 접근성 서비스(같은 프로세스)라서 그 한 번 외에는 전부 304로 끝난다.
     */
    @Volatile private var cachedEtag: String? = null
    @Volatile private var cachedResult: LatestRelease? = null

    /** 이전엔 네트워크 실패(요청 한도 초과 등)와 "확인해보니 진짜 최신 버전"을 구분 못 하고 둘 다 null로
     *  뭉뚱그렸다 — 그래서 API가 실패해도 화면엔 "최신 버전입니다"라고 잘못 표시됐다(2026-08-30 발견).
     *  이제 실패는 Result.failure로 던져 호출부가 "확인 실패"와 "최신 버전"을 구분할 수 있게 한다. */
    suspend fun checkLatestAndroidRelease(): Result<LatestRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val fetched = fetch(RELEASES_URL) ?: return@runCatching cachedResult
            val releases = JSONArray(fetched.body)
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
            // 파싱까지 다 끝난 뒤에 함께 저장한다 — ETag를 먼저 저장하면 파싱이 실패했을 때 다음 확인이
            // 304로 끝나면서 그 실패한 결과가 계속 재사용된다.
            cachedResult = best
            cachedEtag = fetched.etag
            best
        }
    }

    private class Fetched(val body: String, val etag: String?)

    /**
     * 실패 시 응답 코드/메시지를 그대로 던진다 — 예전엔 여기서 null로 삼켜서 원인을 알 수 없었다.
     * 직전 응답과 같으면(304) null을 돌려주고, 호출부는 [cachedResult]를 그대로 재사용한다.
     */
    private fun fetch(urlString: String): Fetched? {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            cachedEtag?.let { connection.setRequestProperty("If-None-Match", it) }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return null
            if (code != 200) error("GitHub 응답 코드 $code")
            return Fetched(
                connection.inputStream.bufferedReader().use { it.readText() },
                connection.getHeaderField("ETag")
            )
        } finally {
            connection.disconnect()
        }
    }
}
