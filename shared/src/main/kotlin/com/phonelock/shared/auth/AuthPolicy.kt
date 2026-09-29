package com.phonelock.shared.auth

/**
 * 다중 로그인(아이디 · 이메일 · 구글) 공용 규칙 — 안드로이드/데스크탑이 같은 판정을 쓰도록 여기 한 곳에 둔다.
 *
 * 계정 모델(DECISIONS.md "다중 로그인 · 계정 보안" 참고):
 * - **User = Firebase uid 하나.** 로그인 수단은 그 uid에 붙은 Firebase 공급자다(`password`, `google.com`,
 *   개인용 게스트는 `anonymous`). 구글 계정 하나가 두 User에 붙는 것은 Firebase 서버가 거부한다.
 * - **로그인 이메일** = Firebase 계정의 이메일. 실제 이메일을 등록하기 전에는 아이디로 만든 가짜 이메일
 *   (`{아이디}@phonelockapp.local`)이고, 등록하면 인증을 마친 실제 이메일로 바뀐다(`verifyBeforeUpdateEmail`만
 *   거치므로 실제 이메일은 항상 인증된 상태다). 비밀번호 재설정·이메일 인증 메일은 이 주소로만 간다.
 * - **아이디 로그인**은 `loginIds/{소문자 아이디}`에서 로그인 이메일을 찾아 로그인한다. 항목이 없으면 옛 방식대로
 *   가짜 이메일로 시도한다(이번 버전 이전에 만든 계정 — 로그인하면 항목이 자동으로 채워진다).
 */
object AuthPolicy {
    const val SYNTHETIC_EMAIL_DOMAIN = "phonelockapp.local"

    /** 아이디 형식(영문/숫자 3~20자) — 가입·아이디 변경·로그인 입력이 모두 이것을 쓴다. */
    val ID_PATTERN = Regex("^[A-Za-z0-9]{3,20}$")

    /** 로그인 입력칸에서 받는 비밀번호 길이(예전 계정의 6자 비밀번호도 로그인은 돼야 한다). */
    val LOGIN_PASSWORD_LENGTH = 6..128

    /** 새로 정하는 비밀번호의 최소 길이. 예전 기준(6자)보다 올렸다 — 기존 비밀번호는 그대로 쓸 수 있다. */
    const val NEW_PASSWORD_MIN = 8
    const val NEW_PASSWORD_MAX = 64

    /** 민감한 작업 전 재인증이 유효한 시간(앱 쪽). DB 규칙의 신선도 검사([RULES_FRESH_AUTH_SEC])보다 짧아야 한다. */
    const val REAUTH_VALID_MS = 4 * 60 * 1000L

    /** DB 규칙이 "방금 본인 확인을 했다"로 인정하는 시간 — `firebase-database.rules.json`의 300과 같아야 한다. */
    const val RULES_FRESH_AUTH_SEC = 300L

    /** 인증 메일 다시 보내기·재설정 메일 요청 사이 최소 간격(앱 쪽 남용 방지, Firebase도 자체 한도가 있다). */
    const val EMAIL_RESEND_COOLDOWN_MS = 60_000L

    /** 보안 기록 보관 기간 — 이보다 오래된 기록은 본인이 정리할 수 있다(규칙도 같은 기준). */
    const val EVENT_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

    fun syntheticEmail(id: String): String = "${id.trim().lowercase()}@$SYNTHETIC_EMAIL_DOMAIN"

    fun isSyntheticEmail(email: String?): Boolean =
        email != null && email.trim().lowercase().endsWith("@$SYNTHETIC_EMAIL_DOMAIN")

    /** 가짜 이메일에서 아이디(소문자)를 되돌린다. 실제 이메일이면 null. */
    fun idFromSyntheticEmail(email: String?): String? =
        email?.trim()?.lowercase()?.takeIf { isSyntheticEmail(it) }?.removeSuffix("@$SYNTHETIC_EMAIL_DOMAIN")

    /** `loginIds`의 키 — 아이디는 대소문자를 가리지 않는다(Firebase 이메일도 소문자로 저장된다). */
    fun loginIdKey(id: String): String = id.trim().lowercase()

    fun normalizeEmail(email: String): String = email.trim().lowercase()

    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    /** 사용자가 등록·로그인에 쓸 수 있는 실제 이메일인가(가짜 도메인은 거절). */
    fun isValidEmail(email: String): Boolean {
        val e = normalizeEmail(email)
        if (e.length > 254 || !EMAIL_PATTERN.matches(e)) return false
        if (e.substringBefore('@').length > 64) return false
        return !isSyntheticEmail(e)
    }

    /** 로그인 입력칸 한 곳에서 아이디와 이메일을 함께 받는다 — `@`가 있으면 이메일로 본다. */
    sealed class LoginIdentifier {
        data class Email(val email: String) : LoginIdentifier()
        data class Id(val id: String) : LoginIdentifier() {
            val key: String get() = loginIdKey(id)
        }
        object Invalid : LoginIdentifier()
    }

    fun parseIdentifier(raw: String): LoginIdentifier {
        val input = raw.trim()
        return when {
            input.contains('@') -> if (isValidEmail(input)) LoginIdentifier.Email(normalizeEmail(input)) else LoginIdentifier.Invalid
            ID_PATTERN.matches(input) -> LoginIdentifier.Id(input)
            else -> LoginIdentifier.Invalid
        }
    }

    /**
     * 새 비밀번호 검사 — 통과하면 null, 아니면 화면에 보여줄 문장. 영문과 숫자를 모두 넣게 하고, 아이디를
     * 그대로 담은 비밀번호는 막는다(크리덴셜 스터핑·추측 공격에 가장 먼저 뚫리는 유형).
     */
    fun newPasswordProblem(password: String, id: String? = null): String? = when {
        password.length < NEW_PASSWORD_MIN -> "비밀번호는 ${NEW_PASSWORD_MIN}자 이상이어야 합니다."
        password.length > NEW_PASSWORD_MAX -> "비밀번호는 ${NEW_PASSWORD_MAX}자 이하여야 합니다."
        password.any { it.isWhitespace() } -> "비밀번호에 공백을 넣을 수 없습니다."
        password.none { it.isLetter() } || password.none { it.isDigit() } -> "비밀번호에 영문과 숫자를 모두 넣어 주세요."
        !id.isNullOrBlank() && id.length >= 3 && password.lowercase().contains(id.trim().lowercase()) ->
            "비밀번호에 아이디를 넣을 수 없습니다."
        else -> null
    }

    /** "ab****@gmail.com" — 본인 확인 전 화면(구글 선택·재설정 안내)에 이메일을 보여줄 때 쓴다. */
    fun maskEmail(email: String): String {
        val e = email.trim()
        val at = e.indexOf('@')
        if (at <= 0) return "***"
        val local = e.substring(0, at)
        val keep = if (local.length <= 2) 1 else 2
        return local.take(keep) + "*".repeat((local.length - keep).coerceIn(2, 6)) + e.substring(at)
    }

    /**
     * 이 계정에 붙어 있는 로그인 수단. [loginEmail]은 Firebase 계정의 이메일(가짜 또는 실제), [hasPassword]는
     * 비밀번호 공급자가 붙어 있는지.
     */
    data class LoginMethods(
        val hasPassword: Boolean,
        val hasGoogle: Boolean,
        val isAnonymous: Boolean,
        val loginEmail: String?
    ) {
        val hasRealEmail: Boolean get() = loginEmail != null && !isSyntheticEmail(loginEmail)

        /** 로그인에 실제로 쓸 수 있는 수단 개수(게스트는 0 — 로그아웃하면 다시 못 들어온다). */
        val usableCount: Int get() = (if (hasPassword) 1 else 0) + (if (hasGoogle) 1 else 0)

        /** 구글을 떼어내도 들어올 방법(비밀번호)이 남는가. 아니면 먼저 비밀번호를 정하게 한다. */
        val canUnlinkGoogle: Boolean get() = hasGoogle && hasPassword

        /** 구글로만 가입한 계정이 비밀번호를 새로 정할 수 있는가(비밀번호는 로그인 이메일에 붙는다). */
        val canSetPassword: Boolean get() = !hasPassword && !isAnonymous && loginEmail != null

        /** 민감한 작업 전에 본인 확인을 할 방법이 있는가(게스트는 없다). */
        val canReauthenticate: Boolean get() = hasPassword || hasGoogle
    }

    /**
     * 이 기기에서 로그인 실패가 이어질 때 다음 시도까지 기다리게 하는 시간. 5번까지는 바로 다시 할 수 있고,
     * 그 뒤로 30초에서 두 배씩 늘려 5분에서 멈춘다. 진짜 방어선은 Firebase의 서버 측 시도 제한이고, 이것은
     * 같은 기기에서의 연속 추측을 늦추고 사용자가 스스로 잠기지 않게 안내하는 용도다.
     */
    fun loginCooldownMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures < 5) return 0L
        val step = (consecutiveFailures - 5).coerceAtMost(4)
        return (30_000L shl step).coerceAtMost(300_000L)
    }

    /** 이 세션(로그인 시각 [authTimeSec])이 "다른 기기 모두 로그아웃"([revokedBeforeSec]) 이전에 시작됐는가. */
    fun isSessionRevoked(authTimeSec: Long?, revokedBeforeSec: Long?): Boolean =
        authTimeSec != null && revokedBeforeSec != null && authTimeSec < revokedBeforeSec

    /**
     * `loginIds/{키}` 한 항목. [email]은 그 아이디로 로그인할 때 쓰는 Firebase 로그인 이메일, [pendingEmail]은
     * 인증을 기다리는 새 이메일(인증 링크를 누르는 순간 로그인 이메일이 바뀌므로 그 사이에도 아이디 로그인이 되게),
     * [retired]는 아이디를 바꾼 뒤 남은 옛 아이디(로그인에 쓰지 않고, 다른 사람이 가져가지도 못하게 막아 둔다).
     */
    data class LoginIdRecord(
        val uid: String,
        val id: String,
        val email: String,
        val pendingEmail: String? = null,
        val retired: Boolean = false
    ) {
        /** 이 아이디로 로그인할 때 시도할 이메일 순서. */
        fun emailCandidates(): List<String> =
            if (retired) emptyList() else listOfNotNull(email, pendingEmail?.takeIf { it != email })
    }

    /**
     * 로그인에 성공한 뒤 `loginIds` 항목을 실제 로그인 이메일에 맞춰야 하는지 판단한다 — 바꿔 쓸 레코드를
     * 돌려주고, 이미 맞거나 고칠 수 없으면(다른 사람 항목) null. 이메일 변경 링크를 누른 직후에는 로그인 이메일이
     * pendingEmail로 바뀌어 있으므로 그 값을 email로 올리고 pendingEmail을 비운다.
     */
    fun healedRecord(existing: LoginIdRecord?, uid: String, id: String, tokenEmail: String): LoginIdRecord? {
        val email = normalizeEmail(tokenEmail)
        if (existing == null) return LoginIdRecord(uid = uid, id = id, email = email)
        if (existing.uid != uid || existing.retired) return null
        val pending = existing.pendingEmail?.takeIf { normalizeEmail(it) != email }
        if (existing.email == email && pending == existing.pendingEmail) return null
        return existing.copy(email = email, pendingEmail = pending)
    }

    /** 보안 기록 종류 — 코드는 DB에 저장되는 짧은 값, 라벨은 화면 표시용. */
    enum class SecurityEvent(val code: String, val label: String) {
        SIGN_IN("signIn", "로그인"),
        SIGN_UP("signUp", "계정 만들기"),
        PASSWORD_CHANGED("pwChanged", "비밀번호 변경"),
        PASSWORD_SET("pwSet", "비밀번호 설정"),
        PASSWORD_RESET_REQUESTED("pwResetReq", "비밀번호 재설정 메일 요청"),
        EMAIL_CHANGE_REQUESTED("emailReq", "이메일 인증 메일 발송"),
        EMAIL_CHANGED("emailChanged", "이메일 변경 완료"),
        GOOGLE_LINKED("googleLinked", "구글 계정 연결"),
        GOOGLE_UNLINKED("googleUnlinked", "구글 계정 연결 해제"),
        ID_CHANGED("idChanged", "아이디 변경"),
        SESSIONS_REVOKED("revoked", "다른 기기 모두 로그아웃"),
        AUTO_LINK_BLOCKED("autoLinkBlocked", "구글 자동 연결 차단");

        companion object {
            fun fromCode(code: String?): SecurityEvent? = values().firstOrNull { it.code == code }
        }
    }

    /** 보안 기록·기기 목록에 남기는 로그인 방법 코드 → 화면 문구. */
    fun methodLabel(code: String?): String = when (code) {
        "id" -> "아이디"
        "email" -> "이메일"
        "google" -> "구글"
        "guest" -> "게스트"
        else -> ""
    }
}
