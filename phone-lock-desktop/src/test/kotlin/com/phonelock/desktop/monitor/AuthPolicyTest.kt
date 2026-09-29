package com.phonelock.desktop.monitor

import com.phonelock.shared.auth.AuthPolicy
import com.phonelock.shared.auth.AuthPolicy.LoginIdRecord
import com.phonelock.shared.auth.AuthPolicy.LoginIdentifier
import com.phonelock.shared.auth.AuthPolicy.LoginMethods
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** shared/auth/AuthPolicy — 다중 로그인 판정(입력 해석, 마지막 수단 보호, 세션 무효화, loginIds 자가 복구). */
class AuthPolicyTest {

    @Test
    fun `로그인 입력은 @ 유무로 아이디와 이메일을 가른다`() {
        assertEquals(LoginIdentifier.Id("Abc123"), AuthPolicy.parseIdentifier("  Abc123 "))
        assertEquals("abc123", (AuthPolicy.parseIdentifier("Abc123") as LoginIdentifier.Id).key)
        assertEquals(LoginIdentifier.Email("user@example.com"), AuthPolicy.parseIdentifier(" User@Example.COM "))
        assertEquals(LoginIdentifier.Invalid, AuthPolicy.parseIdentifier("ab"))
        assertEquals(LoginIdentifier.Invalid, AuthPolicy.parseIdentifier("bad@"))
        assertEquals(LoginIdentifier.Invalid, AuthPolicy.parseIdentifier("한글아이디"))
    }

    @Test
    fun `가짜 도메인 이메일은 실제 이메일로 받지 않는다`() {
        assertFalse(AuthPolicy.isValidEmail("abc@phonelockapp.local"))
        assertEquals(LoginIdentifier.Invalid, AuthPolicy.parseIdentifier("abc@PhoneLockApp.local"))
        assertTrue(AuthPolicy.isSyntheticEmail("ABC@phonelockapp.local"))
        assertEquals("abc", AuthPolicy.idFromSyntheticEmail("ABC@phonelockapp.local"))
        assertNull(AuthPolicy.idFromSyntheticEmail("abc@gmail.com"))
        assertEquals("abc@phonelockapp.local", AuthPolicy.syntheticEmail(" ABC "))
    }

    @Test
    fun `이메일 형식 검사`() {
        assertTrue(AuthPolicy.isValidEmail("a.b+c@sub.example.co.kr"))
        assertFalse(AuthPolicy.isValidEmail("a b@example.com"))
        assertFalse(AuthPolicy.isValidEmail("ab@example"))
        assertFalse(AuthPolicy.isValidEmail("a".repeat(65) + "@example.com"))
    }

    @Test
    fun `새 비밀번호 규칙`() {
        assertNotNull(AuthPolicy.newPasswordProblem("abc12"))            // 짧음
        assertNotNull(AuthPolicy.newPasswordProblem("abcdefgh"))         // 숫자 없음
        assertNotNull(AuthPolicy.newPasswordProblem("12345678"))         // 영문 없음
        assertNotNull(AuthPolicy.newPasswordProblem("abcd 1234"))        // 공백
        assertNotNull(AuthPolicy.newPasswordProblem("xMyId99x", id = "myid99"))  // 아이디 포함
        assertNull(AuthPolicy.newPasswordProblem("study2026!", id = "myid99"))
    }

    @Test
    fun `이메일 가리기`() {
        assertEquals("ab****@gmail.com", AuthPolicy.maskEmail("abcdef@gmail.com"))
        assertEquals("a**@x.io", AuthPolicy.maskEmail("ab@x.io"))
        assertEquals("ab******@naver.com", AuthPolicy.maskEmail("abcdefghijklmn@naver.com"))
    }

    @Test
    fun `마지막 로그인 수단은 떼어낼 수 없다`() {
        val googleOnly = LoginMethods(hasPassword = false, hasGoogle = true, isAnonymous = false, loginEmail = "g@gmail.com")
        assertFalse(googleOnly.canUnlinkGoogle)
        assertTrue(googleOnly.canSetPassword)
        assertEquals(1, googleOnly.usableCount)

        val both = LoginMethods(hasPassword = true, hasGoogle = true, isAnonymous = false, loginEmail = "abc@phonelockapp.local")
        assertTrue(both.canUnlinkGoogle)
        assertFalse(both.hasRealEmail)
        assertFalse(both.canSetPassword)

        val guest = LoginMethods(hasPassword = false, hasGoogle = false, isAnonymous = true, loginEmail = null)
        assertEquals(0, guest.usableCount)
        assertFalse(guest.canReauthenticate)
        assertFalse(guest.canSetPassword)
    }

    @Test
    fun `연속 실패 대기 시간은 5번째부터 30초에서 두 배씩 5분까지`() {
        assertEquals(0L, AuthPolicy.loginCooldownMs(4))
        assertEquals(30_000L, AuthPolicy.loginCooldownMs(5))
        assertEquals(60_000L, AuthPolicy.loginCooldownMs(6))
        assertEquals(240_000L, AuthPolicy.loginCooldownMs(8))
        assertEquals(300_000L, AuthPolicy.loginCooldownMs(9))
        assertEquals(300_000L, AuthPolicy.loginCooldownMs(50))
    }

    @Test
    fun `세션 무효화 판정`() {
        assertTrue(AuthPolicy.isSessionRevoked(authTimeSec = 100, revokedBeforeSec = 200))
        assertFalse(AuthPolicy.isSessionRevoked(authTimeSec = 200, revokedBeforeSec = 200))
        assertFalse(AuthPolicy.isSessionRevoked(authTimeSec = 100, revokedBeforeSec = null))
        assertFalse(AuthPolicy.isSessionRevoked(authTimeSec = null, revokedBeforeSec = 200))
    }

    @Test
    fun `loginIds 자가 복구 - 없으면 만들고 인증된 새 이메일은 올린다`() {
        val created = AuthPolicy.healedRecord(null, uid = "u1", id = "ABC", tokenEmail = "abc@phonelockapp.local")
        assertEquals(LoginIdRecord("u1", "ABC", "abc@phonelockapp.local"), created)

        val pending = LoginIdRecord("u1", "ABC", "abc@phonelockapp.local", pendingEmail = "me@example.com")
        assertEquals(
            LoginIdRecord("u1", "ABC", "me@example.com", pendingEmail = null),
            AuthPolicy.healedRecord(pending, "u1", "ABC", "Me@Example.com")
        )
        // 인증 전이라 로그인 이메일이 그대로면 고칠 것이 없다.
        assertNull(AuthPolicy.healedRecord(pending, "u1", "ABC", "abc@phonelockapp.local"))
        // 다른 사람 항목이나 은퇴한 아이디는 건드리지 않는다.
        assertNull(AuthPolicy.healedRecord(pending, "u2", "ABC", "x@example.com"))
        assertNull(AuthPolicy.healedRecord(pending.copy(retired = true), "u1", "ABC", "me@example.com"))
    }

    @Test
    fun `아이디 로그인 이메일 후보 순서`() {
        val r = LoginIdRecord("u1", "ABC", "old@example.com", pendingEmail = "new@example.com")
        assertEquals(listOf("old@example.com", "new@example.com"), r.emailCandidates())
        assertEquals(emptyList(), r.copy(retired = true).emailCandidates())
        assertEquals(listOf("old@example.com"), r.copy(pendingEmail = "old@example.com").emailCandidates())
    }

    @Test
    fun `보안 기록 코드 왕복`() {
        AuthPolicy.SecurityEvent.values().forEach { assertEquals(it, AuthPolicy.SecurityEvent.fromCode(it.code)) }
        assertNull(AuthPolicy.SecurityEvent.fromCode("nope"))
    }
}
