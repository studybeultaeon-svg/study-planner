package com.phonelock.app.service

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await

/**
 * Firebase Authentication은 이메일 형식만 지원하므로, 사용자가 입력하는 아이디를 내부적으로
 * 가짜 이메일(`{아이디}@$SYNTHETIC_EMAIL_DOMAIN`)로 변환해서 인증에 쓴다. 화면에는 절대 노출되지 않는다.
 */
private const val SYNTHETIC_EMAIL_DOMAIN = "phonelockapp.local"

private fun idToSyntheticEmail(id: String) = "$id@$SYNTHETIC_EMAIL_DOMAIN"

object AuthManager {

    val currentUser: FirebaseUser? get() = FirebaseAuth.getInstance().currentUser

    /** 로그인 아이디(가짜 이메일에서 복원). Google 계정 등 다른 방식으로 로그인된 경우 null. */
    val currentLoginId: String?
        get() = currentUser?.email?.takeIf { it.endsWith("@$SYNTHETIC_EMAIL_DOMAIN") }
            ?.removeSuffix("@$SYNTHETIC_EMAIL_DOMAIN")

    suspend fun signUp(id: String, password: String): Result<FirebaseUser> {
        return try {
            val authResult = FirebaseAuth.getInstance()
                .createUserWithEmailAndPassword(idToSyntheticEmail(id), password)
                .await()
            val user = authResult.user ?: return Result.failure(IllegalStateException("회원가입은 성공했지만 사용자 정보를 가져오지 못했습니다."))
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signIn(id: String, password: String): Result<FirebaseUser> {
        return try {
            val authResult = FirebaseAuth.getInstance()
                .signInWithEmailAndPassword(idToSyntheticEmail(id), password)
                .await()
            val user = authResult.user ?: return Result.failure(IllegalStateException("로그인은 성공했지만 사용자 정보를 가져오지 못했습니다."))
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 게스트(익명) 로그인 — 계정 없이도 가입 신청/승인 흐름을 탈 수 있게 한다. `currentUser?.isAnonymous`로
     *  게스트 여부를 판별한다(Firebase SDK 내장 프로퍼티, 별도 상태 불필요). */
    suspend fun signInGuest(): Result<FirebaseUser> {
        return try {
            val authResult = FirebaseAuth.getInstance().signInAnonymously().await()
            val user = authResult.user ?: return Result.failure(IllegalStateException("게스트 로그인은 성공했지만 사용자 정보를 가져오지 못했습니다."))
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        FirebaseAuth.getInstance().signOut()
    }

    /** 비밀번호 변경 — 로그인 아이디(이메일)는 가입 신청 아이디와 통합돼있어 영구 고정이라 바꿀 수 없다. */
    suspend fun changePassword(newPassword: String): Result<Unit> {
        val user = currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        return try {
            user.updatePassword(newPassword).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Firebase Authentication 계정 자체를 삭제한다 — 호출 전 RTDB 데이터부터 지울 것(삭제 후엔 이
     *  uid로 더 이상 인증을 못 하므로 데이터 정리 요청이 통과하지 않는다). */
    suspend fun deleteAccount(): Result<Unit> {
        val user = currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        return try {
            user.delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
