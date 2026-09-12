package com.phonelock.app.data

/**
 * "소셜" 개편 Phase 2 — 1:1 DM의 [PhoneLockRepository] 얇은 pass-through.
 * [PhoneLockRepository.Social.kt]와 같은 패턴(로컬 캐싱 없이 화면 진입 시마다 Firebase 직접 조회).
 */

/** 채팅 알림(2026-09-10) — [WalkieTalkieService] 폴링에서 새 메시지 유무만 가볍게 확인하는 용도. */
suspend fun PhoneLockRepository.peekLatestDmChatMessage(chatId: String) =
    com.phonelock.app.service.ChatSyncClient.peekLatestDmMessage(fbDatabaseUrl, fbApiKey, chatId)

/** "소셜" 개편 Phase 2 — 1:1 DM(커스텀 아이디 전역 검색). */
suspend fun PhoneLockRepository.searchDmUserByCode(code: String) =
    com.phonelock.app.service.ChatSyncClient.searchUserByCode(fbDatabaseUrl, fbApiKey, code)

suspend fun PhoneLockRepository.ensureDmChat(otherUid: String, otherLabel: String) =
    com.phonelock.app.service.ChatSyncClient.ensureDmChat(fbDatabaseUrl, fbApiKey, otherUid, otherLabel)

suspend fun PhoneLockRepository.readMyDmChats() =
    com.phonelock.app.service.ChatSyncClient.readMyDmChats(fbDatabaseUrl, fbApiKey)

suspend fun PhoneLockRepository.sendDmChatMessage(chatId: String, peerUid: String, text: String) =
    com.phonelock.app.service.ChatSyncClient.sendDmMessage(fbDatabaseUrl, fbApiKey, chatId, peerUid, text)

suspend fun PhoneLockRepository.readDmChatMessages(chatId: String) =
    com.phonelock.app.service.ChatSyncClient.readDmMessages(fbDatabaseUrl, fbApiKey, chatId)

suspend fun PhoneLockRepository.toggleDmChatReaction(chatId: String, msgId: String, emoji: String, alreadySet: Boolean) =
    com.phonelock.app.service.ChatSyncClient.toggleDmMessageReaction(fbDatabaseUrl, fbApiKey, chatId, msgId, emoji, alreadySet)
