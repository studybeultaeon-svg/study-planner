package com.phonelock.desktop.monitor

import com.phonelock.desktop.BuildInfo
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 데스크탑 구글 로그인(140차) — 설치형 앱 표준 흐름(OAuth 2.0 + OpenID Connect, RFC 8252 루프백 리디렉션 + PKCE).
 *
 * 1. `127.0.0.1`의 임시 포트에 한 번만 응답하는 로컬 서버를 연다(외부에서 접근 불가).
 * 2. 기본 브라우저로 구글 로그인 페이지를 연다 — 요청마다 새 `state`(CSRF 방지), `nonce`(재전송 방지),
 *    PKCE `code_verifier`를 만든다.
 * 3. 돌아온 요청의 `state`가 다르면 버린다. 인가 코드를 토큰으로 바꿀 때 `code_verifier`를 보낸다.
 * 4. 받은 ID 토큰의 nonce·대상(aud)·발급자·만료를 확인한다. 서명은 Firebase(`accounts:signInWithIdp`)가 서버에서
 *    다시 검증한다. 구글 액세스 토큰은 쓰지 않으므로 저장하지 않고 버린다.
 *
 * 토큰·인가 코드는 로그에 남기지 않는다. 클라이언트 설정은 [BuildInfo.GOOGLE_DESKTOP_CLIENT_ID](git 밖 파일에서 빌드 시 주입).
 */
object GoogleDesktopOAuth {

    /** 브라우저에서 로그인을 취소했거나 창을 닫아 시간이 지났다 — 화면은 오류로 크게 띄우지 않는다. */
    class CancelledException : Exception("구글 로그인을 취소했습니다.")

    /** 구글이 확인해 준 계정 — [sub]는 구글 계정 고유 ID(이메일이 바뀌어도 그대로). */
    data class GoogleIdentity(val idToken: String, val sub: String, val email: String?)

    val isConfigured: Boolean get() = BuildInfo.GOOGLE_DESKTOP_CLIENT_ID.isNotBlank()

    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    private val VALID_ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
    private val TIMEOUT: Duration = Duration.ofMinutes(3)

    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val random = SecureRandom()

    /** 브라우저로 구글 로그인을 진행하고 확인된 ID 토큰을 돌려준다(블로킹 — UI 스레드에서 부르지 말 것). */
    fun authorize(): Result<GoogleIdentity> = runCatching {
        check(isConfigured) { "구글 로그인이 아직 준비되지 않았습니다." }
        val state = randomToken(24)
        val nonce = randomToken(24)
        val verifier = randomToken(48)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        val result = CompletableFuture<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery)
            // 다른 요청(파비콘 등)이나 state가 다른 요청은 무시한다 — 위조된 콜백으로 로그인이 완료되지 않게.
            val accepted = params["state"] == state && (params.containsKey("code") || params.containsKey("error"))
            val body = if (accepted) CALLBACK_PAGE else "잘못된 요청입니다."
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(if (accepted) 200 else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            if (accepted) result.complete(params)
        }
        server.start()
        try {
            val redirectUri = "http://127.0.0.1:${server.address.port}"
            val authUrl = AUTH_ENDPOINT + "?" + formEncode(
                "client_id" to BuildInfo.GOOGLE_DESKTOP_CLIENT_ID,
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "scope" to "openid email profile",
                "state" to state,
                "nonce" to nonce,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "prompt" to "select_account"
            )
            openBrowser(authUrl)
            val params = try {
                result.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                throw CancelledException()
            }
            if (params["error"] != null) throw CancelledException()
            val code = params["code"] ?: error("구글 인증 응답이 올바르지 않습니다.")
            exchangeCode(code, verifier, redirectUri, nonce)
        } finally {
            server.stop(0)
        }
    }

    private fun exchangeCode(code: String, verifier: String, redirectUri: String, nonce: String): GoogleIdentity {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_ENDPOINT))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    formEncode(
                        "code" to code,
                        "client_id" to BuildInfo.GOOGLE_DESKTOP_CLIENT_ID,
                        "client_secret" to BuildInfo.GOOGLE_DESKTOP_CLIENT_SECRET,
                        "redirect_uri" to redirectUri,
                        "grant_type" to "authorization_code",
                        "code_verifier" to verifier
                    )
                )
            )
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) error("구글 인증을 마치지 못했습니다. 잠시 후 다시 시도해 주세요.")
        val idToken = JSONObject(response.body()).optString("id_token").takeIf { it.isNotBlank() }
            ?: error("구글 계정 정보를 받지 못했습니다.")
        val claims = decodeJwtPayload(idToken) ?: error("구글 계정 정보를 확인하지 못했습니다.")
        check(claims.optString("nonce") == nonce) { "구글 인증 응답이 올바르지 않습니다. 다시 시도해 주세요." }
        check(claims.optString("aud") == BuildInfo.GOOGLE_DESKTOP_CLIENT_ID) { "구글 인증 응답이 올바르지 않습니다." }
        check(claims.optString("iss") in VALID_ISSUERS) { "구글 인증 응답이 올바르지 않습니다." }
        check(claims.optLong("exp") * 1000 > System.currentTimeMillis()) { "구글 인증이 만료됐습니다. 다시 시도해 주세요." }
        val sub = claims.optString("sub").takeIf { it.isNotBlank() } ?: error("구글 계정 정보를 확인하지 못했습니다.")
        return GoogleIdentity(idToken, sub, claims.optString("email").takeIf { it.isNotBlank() })
    }

    fun decodeJwtPayload(jwt: String): JSONObject? = runCatching {
        JSONObject(String(Base64.getUrlDecoder().decode(jwt.split('.')[1]), Charsets.UTF_8))
    }.getOrNull()

    private fun openBrowser(url: String) {
        val opened = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url)); true
            } else false
        }.getOrDefault(false)
        if (!opened) Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url))
    }

    private fun randomToken(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

    private fun formEncode(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private const val CALLBACK_PAGE = """<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>갓생키트</title>
<style>body{font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:90vh;color:#333}</style></head>
<body><p>구글 확인이 끝났습니다. 이 창을 닫고 앱으로 돌아가세요.</p></body></html>"""
}
