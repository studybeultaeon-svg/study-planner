package com.phonelock.app.data

import android.content.Context

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

    /** 직전에 확인했던 스트릭 값 — 다음 체크 때 이 값보다 0으로 떨어졌으면 "끊김"으로 판단. */
    var lastRoutineStreak: Int
        get() = prefs.getInt("last_routine_streak", -1)
        set(value) = prefs.edit().putInt("last_routine_streak", value).apply()

    /** 스트릭 알림을 마지막으로 보낸 날짜 — 하루 중복 알림 방지. */
    var lastRoutineStreakNotifyDate: String?
        get() = prefs.getString("last_routine_streak_notify_date", null)
        set(value) = prefs.edit().putString("last_routine_streak_notify_date", value).apply()

    /**
     * 공부앱(별도 웹앱)의 뽀모도로 휴식 신호를 읽어오고, 데스크탑과 실행 확인 레벨을 주고받기 위한
     * Firebase 설정. 공부앱의 "동기화 설정"에 입력한 것과 동일한 값이어야 한다. databaseUrl/apiKey 중
     * 하나라도 비어있으면 두 연동 기능 모두 쓰지 않는다.
     */
    var fbDatabaseUrl: String?
        get() = prefs.getString("fb_database_url", null)
        set(value) = prefs.edit().putString("fb_database_url", value).apply()

    var fbApiKey: String?
        get() = prefs.getString("fb_api_key", null)
        set(value) = prefs.edit().putString("fb_api_key", value).apply()

    var fbUser: String
        get() = prefs.getString("fb_user", "default") ?: "default"
        set(value) = prefs.edit().putString("fb_user", value).apply()

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
}
