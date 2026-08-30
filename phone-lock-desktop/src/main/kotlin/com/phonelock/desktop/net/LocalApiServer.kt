package com.phonelock.desktop.net

import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.CheckResult
import com.phonelock.desktop.monitor.SiteEnforcement
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * 브라우저 확장프로그램이 사이트 차단 여부를 물어보는 로컬 전용 HTTP API.
 * 127.0.0.1(루프백)에만 바인딩되어 외부 네트워크에서는 접근할 수 없다.
 *
 * 다만 루프백 바인딩만으로는 이 PC의 다른 로컬 프로세스나, 브라우저에서 열어둔 임의의 웹페이지의
 * JavaScript로부터 오는 요청을 막지 못한다. `/confirm`, `/tick`처럼 상태를 바꾸는 엔드포인트는
 * (1) POST만 허용해 주소창에 URL을 직접 입력하는 방식의 우회를 막고, (2) ApiToken 헤더를 요구해
 * curl/스푸핑 요청을 막고, (3) CORS를 chrome-extension:// 출처로만 제한해 일반 웹페이지의 fetch가
 * 프리플라이트 단계에서 거부되도록 한다.
 */
class LocalApiServer(private val repository: Repository) {
    private val enforcement = SiteEnforcement(repository)
    private var server: HttpServer? = null

    fun start(port: Int = 47882) {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        route(s, "/pair") { handlePair(it) }
        route(s, "/heartbeat") { handleHeartbeat(it) }
        route(s, "/check") { handleCheck(it) }
        route(s, "/confirm") { handleConfirm(it) }
        route(s, "/tick") { handleTick(it) }
        route(s, "/reels-shorts-config") { handleReelsShortsConfig(it) }
        route(s, "/overlay-status") { handleOverlayStatus(it) }
        route(s, "/theme") { handleTheme(it) }
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
    }

    /** OPTIONS 프리플라이트를 공통 처리하고, 그 외 메서드만 실제 핸들러로 넘긴다. */
    private fun route(server: HttpServer, path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                addCorsHeaders(exchange)
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                return@createContext
            }
            handler(exchange)
        }
    }

    private fun queryParams(exchange: HttpExchange): Map<String, String> {
        val query = exchange.requestURI.rawQuery ?: return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null
            else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
    }

    /** 확장프로그램 출처(chrome-extension://<id>)만 응답을 읽을 수 있도록 반사해준다. 와일드카드(*)는
     *  임의의 웹페이지도 응답을 읽을 수 있게 해버려 사용하지 않는다. */
    private fun addCorsHeaders(exchange: HttpExchange) {
        val origin = exchange.requestHeaders.getFirst("Origin")
        if (origin != null && origin.startsWith("chrome-extension://")) {
            exchange.responseHeaders.add("Access-Control-Allow-Origin", origin)
        }
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        exchange.responseHeaders.add("Access-Control-Allow-Headers", "X-PhoneLock-Token, Content-Type")
    }

    private fun respondJson(exchange: HttpExchange, json: String, status: Int = 200, openCors: Boolean = false) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        if (openCors) {
            // overlay-status처럼 상태를 바꾸지 않는 단순 조회는 콘텐츠 스크립트(overlay.js)가 페이지
            // 출처(예: https://www.instagram.com)로 요청을 보내므로, chrome-extension:// 출처만
            // 허용하면 응답을 읽지 못해 막혀버린다(실제로 발생한 회귀버그). 상태를 바꾸지 않으므로
            // 어떤 출처든 읽을 수 있게 열어준다.
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        } else {
            addCorsHeaders(exchange)
        }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** /confirm, /tick처럼 상태를 바꾸는 요청에 대해서만 검사한다: POST 메서드 + 올바른 토큰 헤더. */
    private fun isAuthorizedStateChange(exchange: HttpExchange): Boolean {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) return false
        val token = exchange.requestHeaders.getFirst("X-PhoneLock-Token")
        return token != null && token == ApiToken.value
    }

    private fun respondCheckResult(exchange: HttpExchange, result: CheckResult) {
        when (result) {
            is CheckResult.Allow -> respondJson(exchange, "{\"action\":\"allow\"}")
            is CheckResult.Confirm -> respondJson(
                exchange,
                "{\"action\":\"confirm\",\"groupId\":${result.groupId},\"waitSeconds\":${result.waitSeconds},\"level\":${result.level}}"
            )
            is CheckResult.Block -> respondJson(
                exchange,
                "{\"action\":\"block\",\"reason\":\"${result.reason.name}\",\"attempts\":${result.attempts}}"
            )
        }
    }

    /** 확장프로그램이 설치 후 최초 1회(또는 토큰을 잃어버렸을 때) 토큰을 발급받는 엔드포인트. */
    private fun handlePair(exchange: HttpExchange) {
        respondJson(exchange, "{\"token\":\"${ApiToken.value}\"}")
    }

    /** 확장프로그램이 살아있음을 주기적으로 알려주는 신호. 데스크탑 UI가 이 신호가 끊기면 배너로 경고한다. */
    private fun handleHeartbeat(exchange: HttpExchange) {
        if (!isAuthorizedStateChange(exchange)) {
            respondJson(exchange, "{\"error\":\"forbidden\"}", status = 403)
            return
        }
        repository.recordExtensionHeartbeat()
        respondJson(exchange, "{\"ok\":true}")
    }

    private fun handleCheck(exchange: HttpExchange) {
        val domain = queryParams(exchange)["domain"]
        if (domain.isNullOrBlank()) {
            respondJson(exchange, "{\"action\":\"allow\"}")
            return
        }
        respondCheckResult(exchange, enforcement.check(domain))
    }

    private fun handleConfirm(exchange: HttpExchange) {
        if (!isAuthorizedStateChange(exchange)) {
            respondJson(exchange, "{\"error\":\"forbidden\"}", status = 403)
            return
        }
        val params = queryParams(exchange)
        val domain = params["domain"]
        val groupId = params["groupId"]?.toLongOrNull()
        if (domain != null && groupId != null) {
            enforcement.confirmYes(domain, groupId)
        }
        respondJson(exchange, "{\"ok\":true}")
    }

    private fun handleTick(exchange: HttpExchange) {
        if (!isAuthorizedStateChange(exchange)) {
            respondJson(exchange, "{\"error\":\"forbidden\"}", status = 403)
            return
        }
        val params = queryParams(exchange)
        val domain = params["domain"]
        // 확장프로그램이 실제 경과시간을 재서 보내지만, 비정상적인 값(음수, 비정상적으로 큰 값)이
        // 오면 일일 사용량이 조작될 수 있으므로 합리적인 범위로 clamp한다.
        val elapsed = (params["elapsedSeconds"]?.toIntOrNull() ?: 10).coerceIn(0, 300)
        if (domain.isNullOrBlank()) {
            respondJson(exchange, "{\"action\":\"allow\"}")
            return
        }
        respondCheckResult(exchange, enforcement.tick(domain, elapsed))
    }

    private fun handleReelsShortsConfig(exchange: HttpExchange) {
        respondJson(
            exchange,
            "{\"blockReels\":${repository.blockReels},\"blockShorts\":${repository.blockShorts}}"
        )
    }

    /** 사용자 요청(2026-08-14)으로 브라우저 확장(confirm.html/blocked.html/onboarding.html/overlay.js)도
     *  데스크탑 앱의 테마 3종(설정 화면에서 선택)을 그대로 따라가도록 추가. overlay-status와 같은 이유로
     *  콘텐츠 스크립트가 임의 페이지 출처에서 호출하므로 openCors로 연다. */
    private fun handleTheme(exchange: HttpExchange) {
        respondJson(exchange, "{\"themeMode\":\"${repository.themeMode}\"}", openCors = true)
    }

    /** 실행확인에서 "진행"을 고르고 통과한 뒤 남은 시간/레벨을 브라우저 오버레이가 물어보는 API. */
    private fun handleOverlayStatus(exchange: HttpExchange) {
        val domain = queryParams(exchange)["domain"]
        val status = if (domain.isNullOrBlank()) null else enforcement.overlayStatus(domain)
        respondJson(
            exchange,
            "{\"remainingSeconds\":${status?.remainingSeconds ?: 0},\"level\":${status?.level ?: 0},\"isPomodoro\":${status?.isPomodoro ?: false},\"levelStepsToMax\":${status?.levelStepsToMax ?: 5}}",
            openCors = true
        )
    }
}
