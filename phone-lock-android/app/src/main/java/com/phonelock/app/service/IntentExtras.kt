package com.phonelock.app.service

import android.content.Intent

object IntentExtras {
    const val EXTRA_PACKAGE_NAME = "extra_package_name"
    const val EXTRA_REASON = "extra_reason"
    const val EXTRA_IS_SITE = "extra_is_site"
    const val EXTRA_SITE_DOMAIN = "extra_site_domain"
    const val EXTRA_GROUP_ID = "extra_group_id"
    const val EXTRA_WAIT_SECONDS = "extra_wait_seconds"
    /** 실행확인 화면 조롱 문구 강도용 — 재확인 레벨(절대 수치). */
    const val EXTRA_LEVEL = "extra_level"
    /** 잠김(스케줄/일일한도) 화면 조롱 문구 강도용 — 오늘 이 그룹을 열려고 시도한 횟수. */
    const val EXTRA_BLOCK_ATTEMPTS = "extra_block_attempts"
    const val EXTRA_STUDY_LOCK_ALLOWED_PACKAGES = "extra_study_lock_allowed_packages"
    const val EXTRA_STUDY_LOCK_STARTED_AT = "extra_study_lock_started_at"
    const val EXTRA_STUDY_LOCK_IS_POMODORO = "extra_study_lock_is_pomodoro"
    const val EXTRA_STUDY_LOCK_IS_REMOTE = "extra_study_lock_is_remote"

    /**
     * 차단/실행확인 화면(singleInstance)에 새 인텐트가 왔을 때 "같은 앱/사이트·같은 사유·같은 그룹"에 대한
     * 요청인지(125차). 같으면 떠 있는 화면을 그대로 두고, 다르면 새 요청 기준으로 다시 그려야 한다.
     */
    fun isSameLockRequest(a: Intent, b: Intent): Boolean =
        a.getStringExtra(EXTRA_PACKAGE_NAME) == b.getStringExtra(EXTRA_PACKAGE_NAME) &&
            a.getStringExtra(EXTRA_REASON) == b.getStringExtra(EXTRA_REASON) &&
            a.getBooleanExtra(EXTRA_IS_SITE, false) == b.getBooleanExtra(EXTRA_IS_SITE, false) &&
            a.getStringExtra(EXTRA_SITE_DOMAIN) == b.getStringExtra(EXTRA_SITE_DOMAIN) &&
            a.getLongExtra(EXTRA_GROUP_ID, -1L) == b.getLongExtra(EXTRA_GROUP_ID, -1L)
}
