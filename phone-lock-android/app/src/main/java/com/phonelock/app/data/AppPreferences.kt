package com.phonelock.app.data

import android.content.Context

const val DEFAULT_FB_DATABASE_URL = "https://study-fc3bf-default-rtdb.firebaseio.com"
const val DEFAULT_FB_API_KEY = "AIzaSyBd474MozsRb5q4hYgHy2e-Aiz2htMJy14"

class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phone_lock_prefs", Context.MODE_PRIVATE)

    var blockReels: Boolean
        get() = prefs.getBoolean("block_reels", false)
        set(value) = prefs.edit().putBoolean("block_reels", value).apply()

    var blockShorts: Boolean
        get() = prefs.getBoolean("block_shorts", false)
        set(value) = prefs.edit().putBoolean("block_shorts", value).apply()

    /** 공부 잠금(전체화면) 진입 시 방해금지 모드를 자동으로 켤지 — 전문가 종합분석 보고서 #13. 알림 정책
     *  접근 권한(ACCESS_NOTIFICATION_POLICY)이 없으면 이 설정이 켜져 있어도 조용히 무시된다. */
    var autoDndEnabled: Boolean
        get() = prefs.getBoolean("auto_dnd_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_dnd_enabled", value).apply()

    /**
     * 공부앱 타이머가 "공부" 페이즈로 진행 중일 때(휴식 중엔 아님) 예외로 허용할 앱 패키지명 목록. 이 목록과
     * 이 앱 자신, 런처를 제외한 모든 앱은 [AppMonitorAccessibilityService]가 열리는 즉시 감지해서
     * 잠금 화면으로 되돌린다 — 기기 소유자(device owner) 권한 없이는 진짜 실행 차단이 불가능하므로
     * "감지 후 재차단" 방식(베스트 에포트)이다.
     */
    var studyLockAllowedPackages: Set<String>
        get() = prefs.getStringSet("study_lock_allowed_packages", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("study_lock_allowed_packages", value).apply()

    /** 공부 잠금 중 예외로 허용할 사이트(도메인) — 데스크탑과 같은 Firebase 값을 공유하던 걸 로컬로 이전. */
    var studyLockAllowedSites: Set<String>
        get() = prefs.getStringSet("study_lock_allowed_sites", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("study_lock_allowed_sites", value).apply()

    // ---- 네이티브 공부 타이머(1단계) ----
    /** "plain" | "pomodoro" */
    var timerMode: String
        get() = prefs.getString("timer_mode", "plain") ?: "plain"
        set(value) = prefs.edit().putString("timer_mode", value).apply()

    /** "study" | "break" */
    var timerPhase: String
        get() = prefs.getString("timer_phase", "study") ?: "study"
        set(value) = prefs.edit().putString("timer_phase", value).apply()

    var timerTaskName: String
        get() = prefs.getString("timer_task_name", "") ?: ""
        set(value) = prefs.edit().putString("timer_task_name", value).apply()

    /** 타이머 미실행 상태는 0L로 표현한다. */
    var timerPhaseStartedAt: Long
        get() = prefs.getLong("timer_phase_started_at", 0L)
        set(value) = prefs.edit().putLong("timer_phase_started_at", value).apply()

    /** 뽀모도로 모드에서만 의미 있음(0이면 미설정). */
    var timerPhaseEndAt: Long
        get() = prefs.getLong("timer_phase_end_at", 0L)
        set(value) = prefs.edit().putLong("timer_phase_end_at", value).apply()

    var timerCycleCount: Int
        get() = prefs.getInt("timer_cycle_count", 0)
        set(value) = prefs.edit().putInt("timer_cycle_count", value).apply()

    var timerBreakExtraUsed: Boolean
        get() = prefs.getBoolean("timer_break_extra_used", false)
        set(value) = prefs.edit().putBoolean("timer_break_extra_used", value).apply()

    var pomodoroStudyMinutes: Int
        get() = prefs.getInt("pomodoro_study_minutes", 25)
        set(value) = prefs.edit().putInt("pomodoro_study_minutes", value).apply()

    var pomodoroBreakMinutes: Int
        get() = prefs.getInt("pomodoro_break_minutes", 5)
        set(value) = prefs.edit().putInt("pomodoro_break_minutes", value).apply()

    /** 타이머 시작 전 "뽀모도로 모드" 토글의 마지막 선택값(탭을 이동했다 돌아와도 유지). */
    var pomodoroModeEnabled: Boolean
        get() = prefs.getBoolean("pomodoro_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("pomodoro_mode_enabled", value).apply()

    /** 일일 사용 한도(dailyLimitMinutes)의 "하루" 기준이 되는 시각 (0~23시, 기본값 0 = 자정). */
    var dailyResetHour: Int
        get() = prefs.getInt("daily_reset_hour", 0)
        set(value) = prefs.edit().putInt("daily_reset_hour", value).apply()

    /** 앱 전체 테마 선택(설정 화면) — ThemeMode.LIGHT_GREEN/DARK_BLUE/LIGHT_ORANGE. 데스크탑판과 달리
     *  Room이 아니라 다른 설정들처럼 SharedPreferences에 둔다. */
    var themeMode: String
        get() = prefs.getString("theme_mode", "LIGHT_GREEN") ?: "LIGHT_GREEN"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    /** 그룹 자동 재활성화를 마지막으로 적용한 날짜(effectiveDate 기준) — 데스크탑판 lastGroupAutoResetDate와 동일 역할. */
    var lastGroupAutoResetDate: String?
        get() = prefs.getString("last_group_auto_reset_date", null)
        set(value) = prefs.edit().putString("last_group_auto_reset_date", value).apply()

    /** 스트릭 기반 응원/비판/조롱 알림(52차) 전체 on/off — 개별 루틴 단위가 아니라 앱 전역 설정. */
    var routineStreakNotifyEnabled: Boolean
        get() = prefs.getBoolean("routine_streak_notify_enabled", false)
        set(value) = prefs.edit().putBoolean("routine_streak_notify_enabled", value).apply()

    /** 루틴 동기화 알람 누수 버그(2026-08-30)로 이미 쌓인 예약 알람을 한 번 정리했는지 — 앱 실행마다
     *  반복할 필요 없어 [RoutineAlarmScheduler.cleanupLeakedAlarmsIfNeeded]가 이 값으로 1회만 수행한다. */
    var leakedAlarmsCleaned: Boolean
        get() = prefs.getBoolean("leaked_alarms_cleaned_20260830", false)
        set(value) = prefs.edit().putBoolean("leaked_alarms_cleaned_20260830", value).apply()

    /** 직전에 확인했던 스트릭 값 — 다음 체크 때 이 값보다 0으로 떨어졌으면 "끊김"으로 판단. */
    var lastRoutineStreak: Int
        get() = prefs.getInt("last_routine_streak", -1)
        set(value) = prefs.edit().putInt("last_routine_streak", value).apply()

    /** 스트릭 알림을 마지막으로 보낸 날짜 — 하루 중복 알림 방지. */
    var lastRoutineStreakNotifyDate: String?
        get() = prefs.getString("last_routine_streak_notify_date", null)
        set(value) = prefs.edit().putString("last_routine_streak_notify_date", value).apply()

    /** 스트릭이 0으로 끊긴 날 이후 며칠째 0을 유지 중인지(58차, 응원→조롱→팩폭 단계 판단용). */
    var zeroStreakDays: Int
        get() = prefs.getInt("zero_streak_days", 0)
        set(value) = prefs.edit().putInt("zero_streak_days", value).apply()

    /**
     * 앱이 접속할 Firebase 프로젝트(study-fc3bf) 고정값 — google-services.json과 같은 프로젝트.
     * 62차까지는 설정 화면에서 사용자가 직접 입력했지만, 이제 로그인만으로 동기화되도록
     * 하드코딩(데스크탑판 Models.kt의 DEFAULT_FB_DATABASE_URL/DEFAULT_FB_API_KEY와 동일 값).
     */
    var fbDatabaseUrl: String?
        get() = prefs.getString("fb_database_url", null)?.ifBlank { null } ?: DEFAULT_FB_DATABASE_URL
        set(value) = prefs.edit().putString("fb_database_url", value).apply()

    var fbApiKey: String?
        get() = prefs.getString("fb_api_key", null)?.ifBlank { null } ?: DEFAULT_FB_API_KEY
        set(value) = prefs.edit().putString("fb_api_key", value).apply()

    /** 네이티브 캘린더(2단계) 전체 문서 단위 Firebase LWW 타임스탬프 — 웹앱의 studyCalendarTasks_ts에 대응. */
    var calendarTs: Long
        get() = prefs.getLong("calendar_ts", 0L)
        set(value) = prefs.edit().putLong("calendar_ts", value).apply()

    /** 루틴 전체 문서 단위 Firebase LWW 타임스탬프(51차, 데스크탑판 routinesTs와 동일 패턴). */
    var routinesTs: Long
        get() = prefs.getLong("routines_ts", 0L)
        set(value) = prefs.edit().putLong("routines_ts", value).apply()

    // ---- 네이티브 계산기(3단계) ----
    var calcTasksTs: Long
        get() = prefs.getLong("calc_tasks_ts", 0L)
        set(value) = prefs.edit().putLong("calc_tasks_ts", value).apply()

    var calcSavedTs: Long
        get() = prefs.getLong("calc_saved_ts", 0L)
        set(value) = prefs.edit().putLong("calc_saved_ts", value).apply()

    /** 폴더 트리(빈 폴더도 존재해야 하므로 항목의 folderPath와 별개) — JSON 배열의 배열 문자열로 저장. */
    var calcFolderPathsJson: String
        get() = prefs.getString("calc_folder_paths_json", "[]") ?: "[]"
        set(value) = prefs.edit().putString("calc_folder_paths_json", value).apply()

    var calcFolderTs: Long
        get() = prefs.getLong("calc_folder_ts", 0L)
        set(value) = prefs.edit().putLong("calc_folder_ts", value).apply()

    /** 폴더 정렬 순서 — key는 부모 경로(웹앱 pathToOrderKey와 동일 규칙), JSON 객체 문자열로 저장. */
    var calcFolderOrderJson: String
        get() = prefs.getString("calc_folder_order_json", "{}") ?: "{}"
        set(value) = prefs.edit().putString("calc_folder_order_json", value).apply()

    var calcFolderOrderTs: Long
        get() = prefs.getLong("calc_folder_order_ts", 0L)
        set(value) = prefs.edit().putLong("calc_folder_order_ts", value).apply()

    /**
     * 캘린더/루틴/계산기 등 "전체 문서 단위 LWW" 동기화 타임스탬프를 전부 0으로 되돌린다(52차 발견).
     * Room이 fallbackToDestructiveMigration()으로 로컬 DB를 통째로 지워도 이 타임스탬프들은
     * SharedPreferences라 살아남는데, 그러면 다음 동기화 때 "원격 ts <= 살아남은 로컬 ts"로 판정돼
     * (같거나 로컬이 더 큼) 아무것도 안 당겨오거나 심하면 텅 빈 로컬 데이터를 원격에 덮어쓸 위험이 있다.
     * PreMigrationBackup.backupIfVersionChanged()가 백업을 만든 직후(=이번 실행에서 앱 버전이
     * 바뀐 직후) 호출해서, 다음 동기화가 무조건 원격에서 다시 받아오도록 강제한다.
     */
    fun resetSyncTimestamps() {
        calendarTs = 0L
        routinesTs = 0L
        calcTasksTs = 0L
        calcSavedTs = 0L
        calcFolderTs = 0L
        calcFolderOrderTs = 0L
    }

    /** 접힌 폴더 경로 집합(calcPathToOrderKey로 인코딩) — 기기별 UI 상태라 Firebase엔 올리지 않는다. */
    var calcFolderCollapsedJson: String
        get() = prefs.getString("calc_folder_collapsed_json", "[]") ?: "[]"
        set(value) = prefs.edit().putString("calc_folder_collapsed_json", value).apply()

    /**
     * 마지막으로 앱을 연 시점의 versionCode. Room이 fallbackToDestructiveMigration()을 쓰고 있어서
     * 스키마가 바뀌면 DB 전체가 날아가는데, 그 일이 실제로 벌어지는(Room이 처음 열리는) 시점보다
     * 먼저 이 값과 현재 versionCode를 비교해 변경 여부를 감지하기 위해 쓴다. 기본값 -1은 "아직
     * 한 번도 기록된 적 없음"(첫 설치)을 뜻하며, 이 경우 백업 대상이 아니다.
     */
    var lastKnownVersionCode: Long
        get() = prefs.getLong("last_known_version_code", -1L)
        set(value) = prefs.edit().putLong("last_known_version_code", value).apply()

    // ---- "모임"(소셜 그룹)별 공유 설정 — 62차엔 앱 전체 공통 토글 3개였지만, 모임마다 성격이 달라
    // (가족 모임엔 공부시간만, 스터디 모임엔 루틴까지) 모임마다 따로 설정하도록 확장. 가입 자체가 공유
    // 의도이므로 각 항목 기본값은 true, 설정은 각 모임 화면의 "⚙ 공유 설정"에서 개별 모임 단위로 바꾼다
    // (74차 무전기 설정을 전역→모임별로 옮긴 것과 동일한 선례).
    data class GroupShareSettings(
        val shareRoutines: Boolean = true,
        val shareStudy: Boolean = true,
        val shareStreak: Boolean = true,
        /** 오늘 캘린더 일정 목록(이름+완료여부). */
        val shareSchedule: Boolean = true,
        /** 지금 공부 중(뽀모도로 포함)인지 여부 + 업무 이름. */
        val shareStudyingNow: Boolean = true,
        /** 지금 실제로 나를 제한 중인 관리(차단) 그룹 이름 목록. */
        val shareActiveGroup: Boolean = true
    )

    /** 모임ID -> 공유 설정(JSON 객체 문자열) — nudgeLastSeenByGroupJson과 동일한 맵 저장 패턴. */
    var groupShareSettingsJson: String
        get() = prefs.getString("group_share_settings_json", "{}") ?: "{}"
        set(value) = prefs.edit().putString("group_share_settings_json", value).apply()

    fun groupShareSettings(groupId: String): GroupShareSettings {
        val g = org.json.JSONObject(groupShareSettingsJson).optJSONObject(groupId) ?: return GroupShareSettings()
        return GroupShareSettings(
            shareRoutines = g.optBoolean("shareRoutines", true),
            shareStudy = g.optBoolean("shareStudy", true),
            shareStreak = g.optBoolean("shareStreak", true),
            shareSchedule = g.optBoolean("shareSchedule", true),
            shareStudyingNow = g.optBoolean("shareStudyingNow", true),
            shareActiveGroup = g.optBoolean("shareActiveGroup", true)
        )
    }

    fun setGroupShareSettings(groupId: String, settings: GroupShareSettings) {
        val json = org.json.JSONObject(groupShareSettingsJson)
        json.put(groupId, org.json.JSONObject().apply {
            put("shareRoutines", settings.shareRoutines)
            put("shareStudy", settings.shareStudy)
            put("shareStreak", settings.shareStreak)
            put("shareSchedule", settings.shareSchedule)
            put("shareStudyingNow", settings.shareStudyingNow)
            put("shareActiveGroup", settings.shareActiveGroup)
        })
        groupShareSettingsJson = json.toString()
    }

    // ---- 무작위 알림(77차, 사용자 요청) — 하루 중 무작위 시각 한 번, 이 기기가 속한 모임의 멤버들을
    // 확인해서 "오늘 해야 할 루틴/일정이 아직 남은" 사람에게 기존 넛지(😴 깨우기)와 같은 방식으로 자동
    // 알림을 보낸다. 각 기기가 독립적으로 체크해서 보내므로(넛지가 1인 1슬롯 덮어쓰기라 중복 무해,
    // 사용자 확인) 별도 발신자 조율은 없다. 모임마다 켜고 끌 수 있으며(기본 켜짐), 이 값은 순수 로컬
    // 설정 — "받는 쪽" 설정인 무전기(walkieSettings, RTDB)와 달리 "이 기기가 보낼지"를 결정하므로
    // 동기화 대상이 아니다.
    var groupRandomNudgeEnabledJson: String
        get() = prefs.getString("group_random_nudge_enabled_json", "{}") ?: "{}"
        set(value) = prefs.edit().putString("group_random_nudge_enabled_json", value).apply()

    fun randomNudgeEnabledFor(groupId: String): Boolean =
        org.json.JSONObject(groupRandomNudgeEnabledJson).optBoolean(groupId, true)

    fun setRandomNudgeEnabled(groupId: String, enabled: Boolean) {
        val json = org.json.JSONObject(groupRandomNudgeEnabledJson)
        json.put(groupId, enabled)
        groupRandomNudgeEnabledJson = json.toString()
    }

    /** 무작위 알림 체크를 오늘 이미 수행했는지(하루 1회 제한) — 스트릭 알림의 lastRoutineStreakNotifyDate와 동일 패턴. */
    var lastGroupNudgeCheckDate: String
        get() = prefs.getString("last_group_nudge_check_date", "") ?: ""
        set(value) = prefs.edit().putString("last_group_nudge_check_date", value).apply()

    // ---- 모임 내 사용자별(상대방별) 공개 범위 — "모임 내 사용자 상세 설정" ----
    /** 모임ID -> [내 정보를 안 보여줄 상대 uid 목록]. 상대가 나를 조회할 때 전체 항목이 "비공개"로 보이도록
     *  내 stats push에 실려 RTDB에 함께 올라간다(각 상대 클라이언트가 이 목록에 자기 uid가 있는지 확인). */
    var hiddenFromUidsByGroupJson: String
        get() = prefs.getString("hidden_from_uids_by_group_json", "{}") ?: "{}"
        set(value) = prefs.edit().putString("hidden_from_uids_by_group_json", value).apply()

    fun hiddenFromUidsFor(groupId: String): Set<String> {
        val arr = org.json.JSONObject(hiddenFromUidsByGroupJson).optJSONArray(groupId) ?: return emptySet()
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun setHiddenFromUid(groupId: String, targetUid: String, hidden: Boolean) {
        val json = org.json.JSONObject(hiddenFromUidsByGroupJson)
        val current = hiddenFromUidsFor(groupId).toMutableSet()
        if (hidden) current.add(targetUid) else current.remove(targetUid)
        json.put(groupId, org.json.JSONArray(current.toList()))
        hiddenFromUidsByGroupJson = json.toString()
    }

    /** 모임ID -> [내가 보고 싶지 않아 숨긴 상대 uid 목록]. 순수 내 기기 표시 설정이라 RTDB엔 절대 올리지 않는다. */
    var hiddenPeerUidsByGroupJson: String
        get() = prefs.getString("hidden_peer_uids_by_group_json", "{}") ?: "{}"
        set(value) = prefs.edit().putString("hidden_peer_uids_by_group_json", value).apply()

    fun hiddenPeerUidsFor(groupId: String): Set<String> {
        val arr = org.json.JSONObject(hiddenPeerUidsByGroupJson).optJSONArray(groupId) ?: return emptySet()
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun setHiddenPeerUid(groupId: String, targetUid: String, hidden: Boolean) {
        val json = org.json.JSONObject(hiddenPeerUidsByGroupJson)
        val current = hiddenPeerUidsFor(groupId).toMutableSet()
        if (hidden) current.add(targetUid) else current.remove(targetUid)
        json.put(groupId, org.json.JSONArray(current.toList()))
        hiddenPeerUidsByGroupJson = json.toString()
    }

    /** 모임ID -> 마지막으로 확인한 넛지(깨우기) 시각(epoch millis). JSON 객체 문자열로 저장(다른 JSON 캐시 필드들과 동일 패턴). */
    var nudgeLastSeenByGroupJson: String
        get() = prefs.getString("nudge_last_seen_by_group_json", "{}") ?: "{}"
        set(value) = prefs.edit().putString("nudge_last_seen_by_group_json", value).apply()

    fun nudgeLastSeenByGroup(): Map<String, Long> {
        val json = org.json.JSONObject(nudgeLastSeenByGroupJson)
        return json.keys().asSequence().associateWith { json.optLong(it, 0L) }
    }

    fun setNudgeLastSeen(groupId: String, atMillis: Long) {
        val json = org.json.JSONObject(nudgeLastSeenByGroupJson)
        json.put(groupId, atMillis)
        nudgeLastSeenByGroupJson = json.toString()
    }

    // ---- 가입/승인 계정 게이트 ----
    /** 마지막으로 확인된 계정 승인 상태("approved" 등) — 오프라인일 때도 승인된 사용자가 앱을 열 수 있도록
     *  낙관적으로 먼저 content()를 보여주는 데 쓴다([AccountGate] 참고). */
    var cachedApprovalStatus: String?
        get() = prefs.getString("cached_approval_status", null)
        set(value) = prefs.edit().putString("cached_approval_status", value).apply()

    /** 관리자가 승인 시(또는 이후) 지정한 기능별 사용 허가 — 캐시본이며 필드가 아예 없던 옛 승인 사용자와의
     *  하위호환을 위해 기본값은 전부 true(제한 없음). [AccountGateScreen]이 승인 확인 때마다 갱신한다. */
    var permRoutine: Boolean
        get() = prefs.getBoolean("perm_routine", true)
        set(value) = prefs.edit().putBoolean("perm_routine", value).apply()
    var permStudy: Boolean
        get() = prefs.getBoolean("perm_study", true)
        set(value) = prefs.edit().putBoolean("perm_study", value).apply()
    var permManage: Boolean
        get() = prefs.getBoolean("perm_manage", true)
        set(value) = prefs.edit().putBoolean("perm_manage", value).apply()
    var permSocial: Boolean
        get() = prefs.getBoolean("perm_social", true)
        set(value) = prefs.edit().putBoolean("perm_social", value).apply()

    // ---- 자체 업데이트 확인(GitHub Releases, 2026-08-30) ----
    /** 마지막으로 GitHub Releases를 확인한 날짜(effectiveDate 기준) — 하루 1회만 네트워크 호출하기 위한 가드,
     *  lastGroupAutoResetDate와 동일 패턴. */
    var lastUpdateCheckDate: String?
        get() = prefs.getString("last_update_check_date", null)
        set(value) = prefs.edit().putString("last_update_check_date", value).apply()

    /** GitHub Releases에서 발견한 최신 안드로이드 릴리스의 versionCode. 0이면 "새 버전 없음". */
    var updateAvailableVersionCode: Long
        get() = prefs.getLong("update_available_version_code", 0L)
        set(value) = prefs.edit().putLong("update_available_version_code", value).apply()

    /** 위 versionCode에 대응하는 APK 다운로드 URL(GitHub Release 에셋 직접 링크). */
    var updateAvailableApkUrl: String?
        get() = prefs.getString("update_available_apk_url", null)
        set(value) = prefs.edit().putString("update_available_apk_url", value).apply()
}
