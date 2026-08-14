package com.phonelock.desktop.net

import java.io.File
import java.util.UUID

/**
 * LocalApiServer는 127.0.0.1에만 바인딩되어 있지만, 인증이 전혀 없으면 이 PC의 다른 로컬
 * 프로세스나 브라우저에서 열어둔 임의의 웹페이지가 `/confirm`, `/tick` 같은 상태 변경 엔드포인트를
 * 직접 호출해 타이머·체크포인트·회유 멘트를 몽땅 건너뛸 수 있다. 설치마다 랜덤 토큰을 하나 만들어
 * 디스크에 저장해두고, 브라우저 확장은 `/pair`로 이 토큰을 한 번 받아 이후 모든 상태 변경 요청에
 * 헤더로 실어 보내도록 한다.
 */
object ApiToken {
    private val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PhoneLockDesktop")
    private val file = File(dir, "api_token.txt")

    val value: String by lazy { loadOrCreate() }

    private fun loadOrCreate(): String {
        dir.mkdirs()
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        runCatching { file.writeText(generated) }
        return generated
    }
}
