package com.phonelock.app.ui.components

/**
 * 프로필 사진(119차) — 갤러리 업로드 대신 앱이 제공하는 동물 이모지 프리셋 중 하나를 고른다.
 * Firebase Storage 등 별도 저장 인프라 없이 `users/{uid}/profile.profileImage`에 이 id 문자열 하나만
 * 저장하면 되므로([AccountSyncClient.updateProfileImage]), 현재 프로젝트 규모에 맞는 가장 단순한 방식이다.
 */
object AvatarCatalog {
    val PRESETS: List<Pair<String, String>> = listOf(
        "cat" to "🐱",
        "dog" to "🐶",
        "rabbit" to "🐰",
        "bear" to "🐻",
        "fox" to "🦊",
        "panda" to "🐼",
        "koala" to "🐨",
        "lion" to "🦁",
        "tiger" to "🐯",
        "penguin" to "🐧"
    )

    fun emojiFor(id: String?): String? = if (id.isNullOrBlank()) null else PRESETS.firstOrNull { it.first == id }?.second
}
