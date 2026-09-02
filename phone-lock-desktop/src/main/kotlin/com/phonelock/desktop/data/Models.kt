package com.phonelock.desktop.data

/**
 * scheduleDaysMask: bit 0 = 월요일 ... bit 6 = 일요일. 기본값 127 = 매일.
 */
data class Group(
    val id: Long,
    val name: String,
    /** 이 그룹이 뭘 하는 그룹인지 짧은 설명 — "모임" 공유의 "작동 중인 관리 그룹"에서 이름과 함께 보여준다. */
    val description: String = "",
    /** 일일 사용 한도(초). null이면 미적용. */
    val dailyLimitSeconds: Int? = null,
    /** 일일 사용 한도가 적용되는 시간대(분). 둘 다 null이면 하루 종일 적용된다. */
    val dailyLimitApplyStartMinute: Int? = null,
    val dailyLimitApplyEndMinute: Int? = null,
    /** 일일 사용한도가 적용되는 요일. bit 0 = 월요일 ... bit 6 = 일요일. 기본값 127 = 매일. */
    val dailyLimitDaysMask: Int = 127,
    val scheduleStartMinute: Int? = null,
    val scheduleEndMinute: Int? = null,
    val scheduleDaysMask: Int = 127,
    /** 통계 탭 표시 필터 전용. 잠금/차단 판정에는 전혀 관여하지 않는다(그룹 전체 켜짐/꺼짐은 groupEnabled 참고). */
    val enabled: Boolean = true,
    val confirmEnabled: Boolean = false,
    /** 실행 확인이 적용되는 시간대(분). 둘 다 null이면 하루 종일 적용된다. */
    val confirmApplyStartMinute: Int? = null,
    val confirmApplyEndMinute: Int? = null,
    /** 실행 확인이 적용되는 요일. bit 0 = 월요일 ... bit 6 = 일요일. 기본값 127 = 매일. */
    val confirmDaysMask: Int = 127,
    val initialWaitSeconds: Int = 5,
    val waitIncrementSeconds: Int = 5,
    /** 실행 확인에서 "예"를 한 뒤, 같은 그룹 내 다른 프로그램/사이트도 재확인 없이 허용되는 유예시간(초). */
    val confirmCooldownSeconds: Int = 300,
    /** 실행 확인 레벨이 시간이 지나면서 자연히 차감(-1)되는 기능 자체의 on/off. */
    val levelDecayEnabled: Boolean = true,
    /** 실행 확인을 통과한 뒤 유예시간 동안 프로그램을 쓰는 중에 남은 시간을 알려주는 오버레이 표시 on/off. */
    val usageOverlayEnabled: Boolean = true,
    /**
     * 실행확인 레벨이 몇 번째 재확인 만에 오버레이 최고 밝기(OVERLAY_MAX_ALPHA)에 도달하는지.
     * 한 번 재확인할 때마다 오르는 밝기 폭(오버레이 알파 증가분)은 이 값으로부터
     * (OVERLAY_MAX_ALPHA - OVERLAY_BASE_ALPHA) / overlayLevelStepsToMax로 자동 계산되며,
     * 재확인/escalation 판정 로직(레벨 자체가 오르내리는 규칙)과는 무관한 표시값 설정이다.
     */
    val overlayLevelStepsToMax: Int = 5,
    /** 공부앱(별도 웹앱)의 뽀모도로 휴식 시간 동안 이 그룹의 잠금을 자동으로 임시 해제할지 여부. */
    val pomodoroUnlockEnabled: Boolean = false,
    /**
     * 마지막 확인(진행 완료) 시점으로부터 이 시간(초)이 지날 때마다 실행 확인 레벨이 1씩 자연 차감된다.
     * 정해진 시각(정각 등) 기준이 아니라 경과 시간 기준이며, 여러 간격이 한꺼번에 지났으면 그만큼
     * 한 번에 차감된다(0 밑으로는 내려가지 않음). levelDecayEnabled가 꺼져 있으면 차감되지 않는다.
     */
    val levelDecayIntervalSeconds: Int = 3600,
    /** 시간대 차단(스케줄) 관리 종류 자체의 on/off. false면 시간대 차단만 비활성화되고
     *  일일 한도/실행 확인은 각자의 on/off와 요일 설정에 따라 별개로 계속 작동한다. */
    val scheduleEnabled: Boolean = true,
    /** 그룹 전체 사용 on/off("그룹 목록" 화면의 스위치). false면 스케줄/일일한도/실행 확인 등
     *  이 그룹의 모든 관리가 비활성화된다. 관리 종류별 on/off(scheduleEnabled 등)와는 별개다. */
    val groupEnabled: Boolean = true,
    /** 그룹 전체가 걸려있는 도중 끄기를 시도해서 회유 멘트를 확인하는 중인지. */
    val groupOffPending: Boolean = false,
    /** 지금까지 확인(예를 누름)한 회유 멘트 개수 (다음에 보여줄 멘트의 인덱스이기도 함). */
    val groupOffMessageIndex: Int = 0,
    /** 스누즈(전문가 종합분석 보고서 #1) 한 번에 몇 분간 임시 해제할지. 회유 절차 없이 즉시 적용되므로
     *  하루 3회로 제한된다(snoozeUsedDate/snoozeUsedCount, LockEvaluator.isSnoozeActive 참고). */
    val snoozeMinutes: Int = 30,
    /** 지금 스누즈가 적용 중이면 그 종료 시각(epoch millis). 지났으면 무시. */
    val snoozedUntilEpochMillis: Long? = null,
    /** 스누즈 하루 횟수 제한(3회)을 세는 날짜/카운트 — dailyResetHour 기준 "오늘"이 바뀌면 0으로 리셋. */
    val snoozeUsedDate: String = "",
    val snoozeUsedCount: Int = 0,
    /** 기간 지정 자동 강화(#7, 시험기간 등) — 이 날짜 범위(yyyy-MM-dd, 포함) 안에서는 groupEnabled를
     *  껐어도 LockEvaluator가 켜진 것으로 강제 취급한다(스케줄/한도 판정 자체는 그대로 따른다). */
    val forceEnabledFrom: String? = null,
    val forceEnabledUntil: String? = null,
    /** 잠김(스케줄/일일한도) 화면 조롱 문구 강도용 — 오늘 이 그룹을 열려고 시도한 횟수/날짜.
     *  dailyResetHour 기준 "오늘"이 바뀌면 0으로 리셋(snoozeUsedDate/snoozeUsedCount와 같은 패턴). */
    val blockAttemptDate: String = "",
    val blockAttemptCount: Int = 0,
    val processNames: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    /** "미래의 나에게" 예약 메시지(82차, §11, 안드로이드판과 대칭) — 순수 로컬 텍스트, 동기화 안 함. */
    val selfMessageText: String = ""
)

/** 회유 멘트 성공률 통계(82차, §9/§11, 안드로이드판과 대칭) — 판정 로직과 무관한 순수 로컬 기록. */
data class QuoteOutcome(val tier: Int, val quoteText: String, val choice: String, val timestampMillis: Long)

data class UsageRecord(val groupId: Long, val date: String, val usedSeconds: Int)

/**
 * 하루에 이 그룹의 재확인 화면을 몇 번 통과했는지("위반 시도" 횟수, 전문가 종합분석 보고서 #32) —
 * 순수 로컬 통계용이며 판정 로직(ConfirmationGate.kt)과는 무관하다. recordConfirm() 호출부에서만
 * 증가시킨다(37차 "원본은 그대로, 호출부에서 병합/부가 기능" 패턴과 동일).
 */
data class ConfirmCounter(val groupId: Long, val date: String, val count: Int)

/**
 * 실행 확인 대기시간 증가/감소 상태. 그룹마다 따로 관리된다 (한 그룹에서 많이 확인해도 다른 그룹의
 * 대기시간에는 영향이 없다). level이 1 증가할 때마다 대기시간이 늘고, lastConfirmedAtEpochMillis로부터
 * 1시간이 지날 때마다 level이 1씩 자연 감소한다 (고정 초기화 시각 없음).
 */
data class ConfirmEscalation(val groupId: Long, val level: Int = 0, val lastConfirmedAtEpochMillis: Long = 0)

/**
 * 네이티브 공부 타이머의 실행 상태(1단계, 웹앱 index.html의 timerRun을 그대로 이식). 항상 wall-clock
 * 기준(phaseStartedAt/phaseEndAt)으로 경과·남은시간을 계산하고, tick은 화면 갱신 트리거로만 쓴다 —
 * DECISIONS.md 참고. 타이머가 꺼져 있으면 phaseStartedAt=0L.
 */
data class TimerRunState(
    val taskName: String = "",
    /** "plain" | "pomodoro" */
    val mode: String = "plain",
    /** "study" | "break" */
    val phase: String = "study",
    val phaseStartedAt: Long = 0L,
    /** 뽀모도로 모드에서만 의미 있음(0이면 미설정). */
    val phaseEndAt: Long = 0L,
    val cycleCount: Int = 0,
    val breakExtraUsed: Boolean = false
)

data class StudyLogEntry(val dateKey: String, val taskName: String, val seconds: Int, val startedAt: Long, val note: String = "", val tag: String = "")

/** 모임(소셜 그룹) 하나에 무엇을 공유할지 — 62차엔 앱 전체 공통 토글 3개였지만 75차+에 모임마다 다르게
 *  설정하도록 확장, 항목도 루틴/공부/스트릭 3종에서 오늘 일정/공부중 여부까지 5종으로 확대(안드로이드
 *  AppPreferences.GroupShareSettings와 대칭). "현재 작동 중인 관리 그룹"은 77차에 추가됐다가 81차에
 *  완전히 제외됨(사용자 요청). */
data class GroupShareSettings(
    val shareRoutines: Boolean = true,
    val shareStudy: Boolean = true,
    val shareStreak: Boolean = true,
    val shareSchedule: Boolean = true,
    val shareStudyingNow: Boolean = true
)

/**
 * 네이티브 캘린더(2단계)의 날짜별 일정 한 건. 웹앱 index.html의 calTasks[dateKey][] 항목을 그대로 이식.
 * color는 51차에 8단계 무지개로 확장됨(white=1회독~purple=8회독, DECISIONS.md 참고).
 * linkedCalc/progressStep은 계산기 연동용 필드(51차에 UI 추가) — linkedCalc는 연결된 계산기 업무 이름,
 * progressStep은 이 일정을 완료하면 그 업무 progress에 더해질 양(예: "51~60쪽" → "10").
 */
data class CalendarTask(
    val dateKey: String,
    val name: String,
    val color: String,
    val status: String? = null,
    val nextDays: Int? = null,
    val linkedCalc: String? = null,
    val progressStep: String? = null,
    /** 완료(O) 시 다음 회독을 자동 생성할지(79차, 사용자 요청) — 기본 off, 켜야만 [Repository.applyCalendarAutoSchedule]이 동작한다. */
    val multiPassEnabled: Boolean = false
)

/**
 * 네이티브 계산기(3단계)의 입력 카드 한 장(웹앱 index.html의 task-card/`autoSaveDraftNow`가 다루는
 * draft 업무 하나에 대응). 요일별 목표(mon~sun)와 qty/progress를 전부 String으로 두는 이유는
 * 웹앱 쪽도 입력 필드 값(gv())을 그대로 저장하기 때문 — 입력칸 바인딩과 Firebase 스키마 모두 문자열
 * 그대로가 원본과 가장 가깝다. 계산(calculate) 시점에만 숫자로 파싱한다.
 */
data class CalcTask(
    val name: String = "",
    val qty: String = "",
    val unit: String = "",
    val progress: String = "",
    val start: String = "",
    val dday: String = "",
    val mon: String = "", val tue: String = "", val wed: String = "", val thu: String = "",
    val fri: String = "", val sat: String = "", val sun: String = "",
    val holidays: List<String> = emptyList(),
    val modifiedAt: String = "",
    val modifiedAtTs: Long = 0L,
    /** 캘린더 일정 자동 생성 on/off(82차, 사용자 지정 스펙, 안드로이드판과 대칭) — 켜면 연동 일정을 완료할 때마다 다음 배치를 자동으로 만든다. */
    val autoGenEnabled: Boolean = false,
    /** 자동 생성 배치 크기. */
    val autoGenBatchSize: Int = 0
)

/**
 * "저장됨" 목록의 계산 결과 한 건(웹앱 `saveOneResult()`가 만드는 saved[] 항목에 대응). 웹앱은 이
 * 시점의 qty/progress를 이미 숫자로 파싱해서 저장하므로(반면 draft 쪽은 문자열) Double로 둔다 —
 * 원본과의 이런 타입 차이는 의도적으로 유지한다(DECISIONS.md 참고).
 */
data class CalcSavedItem(
    val name: String = "",
    val qty: Double = 0.0,
    val unit: String = "",
    val progress: Double = 0.0,
    val start: String = "",
    val dday: String = "",
    val mon: String = "", val tue: String = "", val wed: String = "", val thu: String = "",
    val fri: String = "", val sat: String = "", val sun: String = "",
    val holidays: List<String> = emptyList(),
    val savedAt: String = "",
    val modifiedAt: String = "",
    /** null/빈 리스트 = 미분류. 폴더 트리 자체(calcFolderPaths)와 별개로 각 항목이 자기 경로를 갖는다. */
    val folderPath: List<String>? = null
)

/**
 * 루틴앱 v1(47차 설계, DECISIONS.md 참고)의 핵심 모델 — "반복 체크리스트"/"습관 트래커"/"시간대별
 * 일과표" 세 요구를 하나로 통합한다. timeSlot이 있으면 일과표 뷰에 시간순으로 나타나고(파생 뷰, 별도
 * 저장 없음 — TimetableScreen이 CalcTask를 파생시키는 패턴과 동일), trackStreak이 true면 통계에서
 * 스트릭이 계산된다("체크리스트"와 "습관"은 이 플래그 하나 차이).
 */
data class Routine(
    val id: Long,
    val title: String = "",
    /** 목록에서 표시할 이모지 1개(선택). 빈 문자열이면 아이콘 없이 제목만 표시. */
    val icon: String = "",
    /** "HH:mm" 24시간제. null이면 시간 미지정(순수 체크리스트/습관 항목). */
    val timeSlot: String? = null,
    /** bit 0 = 월요일 ... bit 6 = 일요일. 기본값 127 = 매일. */
    val daysMask: Int = 127,
    val trackStreak: Boolean = false,
    /** "NONE" | "WEEKLY" | "MONTHLY" — 스트릭 방어권(RoutineEngine 참고) 주기. */
    val defenseType: String = "NONE",
    /** defenseType 주기당 허용되는 미체크 봐주기 횟수. */
    val defenseCount: Int = 0,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    /** timeSlot 기준 리마인드 알림 on/off(52차) — timeSlot이 없으면 무의미(알림 자체가 안 걸림). */
    val notifyEnabled: Boolean = false,
    /** 루틴 기간 설정(52차, yyyy-MM-dd, 포함) — 둘 다 null이면 기간 제한 없음(상시). daysMask와
     *  별개로 이 범위 밖이면 "오늘" 탭 예정 목록/스트릭 집계에서 제외된다. */
    val startDate: String? = null,
    val endDate: String? = null
)

/** Routine의 날짜별 완료 기록 — 존재 자체가 "그날 완료"를 의미한다. */
data class RoutineLog(val routineId: Long, val dateKey: String)

/**
 * 앱이 접속할 Firebase 프로젝트(study-fc3bf) 고정값 — 62차까지는 설정 화면에서 사용자가 직접 입력했지만,
 * 이제 로그인만으로 동기화되도록 하드코딩(안드로이드 google-services.json과 같은 프로젝트).
 */
const val DEFAULT_FB_DATABASE_URL = "https://study-fc3bf-default-rtdb.firebaseio.com"
const val DEFAULT_FB_API_KEY = "AIzaSyASJv4Fox3b00uIrTvBom5fsoq7UmFTDW8"

data class AppData(
    val groups: MutableList<Group> = mutableListOf(),
    val usageRecords: MutableList<UsageRecord> = mutableListOf(),
    val confirmEscalations: MutableList<ConfirmEscalation> = mutableListOf(),
    val confirmCounters: MutableList<ConfirmCounter> = mutableListOf(),
    var nextGroupId: Long = 1,
    /** 일일 사용 한도(dailyLimitMinutes)의 "하루" 기준이 되는 시각 (0~23시, 기본값 0 = 자정). */
    var dailyResetHour: Int = 0,
    /** 브라우저 확장프로그램이 URL 패턴(youtube.com/shorts, instagram.com/reels)으로 감지해서 차단할지 여부. */
    var blockReels: Boolean = false,
    var blockShorts: Boolean = false,
    /** 스트릭 기반 응원/비판/조롱 알림(52차) 전체 on/off. */
    var routineStreakNotifyEnabled: Boolean = false,
    /** 직전에 확인했던 루틴 스트릭 값 — 다음 체크 때 이 값보다 0으로 떨어졌으면 "끊김"으로 판단(안드로이드판과 대칭). */
    var lastRoutineStreak: Int = -1,
    /** 스트릭이 0으로 끊긴 날 이후 며칠째 0을 유지 중인지(58차, 응원→조롱→팩폭 단계 판단용, 안드로이드판과 대칭). */
    var zeroStreakDays: Int = 0,
    /**
     * 공부앱(별도 웹앱)의 뽀모도로 휴식 신호를 읽어오고, 모바일과 실행 확인 레벨을 주고받기 위한 Firebase
     * 프로젝트 설정 — 앱이 접속할 프로젝트 고정값(DEFAULT_FB_DATABASE_URL/DEFAULT_FB_API_KEY)이며,
     * 실제 계정 식별은 로그인(uid)만으로 이뤄진다.
     */
    var fbDatabaseUrl: String? = DEFAULT_FB_DATABASE_URL,
    var fbApiKey: String? = DEFAULT_FB_API_KEY,
    /** 네이티브 공부 타이머 상태(1단계). null이면 타이머 미실행. */
    var timerRun: TimerRunState? = null,
    var pomodoroStudyMinutes: Int = 25,
    var pomodoroBreakMinutes: Int = 5,
    /** 타이머 시작 전 "뽀모도로 모드" 토글의 마지막 선택값(탭을 이동했다 돌아와도 유지). */
    var pomodoroModeEnabled: Boolean = false,
    val studyLog: MutableList<StudyLogEntry> = mutableListOf(),
    /** 회유 멘트 성공률 통계(82차, §9/§11). */
    val quoteOutcomes: MutableList<QuoteOutcome> = mutableListOf(),
    /** 공부 잠금 중 예외로 허용할 프로그램 실행파일명(이전엔 웹앱 타이머 탭 → Firebase에서만 관리했음). */
    var studyLockAllowedApps: MutableList<String> = mutableListOf(),
    /** 공부 잠금 중 예외로 허용할 사이트(도메인) — 안드로이드와 Firebase로 공유. */
    var studyLockAllowedSites: MutableList<String> = mutableListOf(),
    /** 네이티브 캘린더(2단계) 일정 전체. 날짜 키 순서는 무관하고, 같은 날짜 안에서는 리스트 등장 순서가
     *  표시 순서다(웹앱의 calTasks[key] 배열 순서와 동일 개념). */
    val calendarTasks: MutableList<CalendarTask> = mutableListOf(),
    /** 캘린더 전체 문서 단위 LWW 타임스탬프 — 웹앱의 studyCalendarTasks_ts에 대응. */
    var calendarTs: Long = 0L,
    /** 네이티브 계산기(3단계) — 입력 중인 draft 업무 카드들과 그 LWW 타임스탬프(웹앱 tasks/tasksTs). */
    val calcTasks: MutableList<CalcTask> = mutableListOf(),
    var calcTasksTs: Long = 0L,
    /** "저장됨" 목록(웹앱 saved/savedTs). */
    val calcSaved: MutableList<CalcSavedItem> = mutableListOf(),
    var calcSavedTs: Long = 0L,
    /** 폴더 트리(빈 폴더도 존재해야 하므로 아이템의 folderPath와 별개로 전체 경로 목록을 따로 든다).
     *  웹앱 savedFolderTree(중첩 객체)와 동일한 정보를 평평한 경로 리스트로 표현한 것. */
    val calcFolderPaths: MutableList<List<String>> = mutableListOf(),
    var calcFolderTs: Long = 0L,
    /** 폴더 정렬 순서 — key는 부모 경로(웹앱 pathToOrderKey와 동일 규칙: 빈 경로="__root__", 그 외 "a|b"),
     *  value는 그 밑 하위 폴더 이름의 순서. */
    val calcFolderOrder: MutableMap<String, MutableList<String>> = mutableMapOf(),
    var calcFolderOrderTs: Long = 0L,
    /** 접힌 폴더 경로 집합(calcPathToOrderKey로 인코딩) — 기기별 UI 상태라 Firebase엔 올리지 않는다. */
    val calcFolderCollapsed: MutableSet<String> = mutableSetOf(),
    /** 그룹 자동 재활성화(초기화 시간마다 꺼진 그룹을 다시 켬)를 마지막으로 적용한 날짜(effectiveDate 기준).
     *  이 값과 오늘 날짜가 다르면 다음 tick에서 한 번만 재적용한다. */
    var lastGroupAutoResetDate: String? = null,
    /** 루틴앱 v1(47차) — Routine 목록과 다음 id 발급용 카운터. */
    val routines: MutableList<Routine> = mutableListOf(),
    var nextRoutineId: Long = 1,
    /** 루틴 날짜별 완료 기록. */
    val routineLogs: MutableList<RoutineLog> = mutableListOf(),
    /** 루틴 전체 문서 단위 LWW 타임스탬프(51차, 캘린더의 calendarTs와 동일 패턴) — users/{user}/routines. */
    var routinesTs: Long = 0L,
    /** 앱 전체 테마 선택(설정 화면) — ThemeMode.LIGHT_GREEN/DARK_BLUE/LIGHT_ORANGE 등, CUSTOM이면 아래 두 값을 씀. */
    var themeMode: String = "LIGHT_GREEN",
    /** 커스텀 테마(79차)의 배경/포인트 색 — "#RRGGBB". */
    var customThemeBackground: String = "#FAFBF6",
    var customThemeAccent: String = "#8BC34A",
    /** 앱 완전 종료 시 회유 멘트 20개 확인 절차를 거칠지(79차) — 기본 꺼짐(사용자 요청). */
    var exitConfirmEnabled: Boolean = false,
    /** 캘린더 새 일정을 추가할 때 "다회독"(완료 시 다음 회독 자동 생성) 기본값 — 기본 꺼짐, 공부 설정에서 사용자가 변경. */
    var defaultMultiPassEnabled: Boolean = false,
    // ---- 자동 백업/정리(82차, §9, 안드로이드판과 대칭) ----
    var cloudBackupEnabled: Boolean = false,
    var lastCloudBackupDate: String = "",
    var lastCloudBackupResult: String = "",
    var lastAutoStatsPruneDate: String = "",
    /** "모임"(소셜 그룹)별 공유 설정 — groupId -> GroupShareSettings. 모임 가입 자체가 공유 의도이므로
     *  각 항목 기본값은 true, 설정은 각 모임 화면의 "🔒 공유 설정"에서 모임 단위로 바꾼다(74차 무전기
     *  설정을 전역→모임별로 옮긴 것과 동일한 선례). */
    val groupShareSettings: MutableMap<String, GroupShareSettings> = mutableMapOf(),
    /** 모임ID -> [내 정보를 안 보여줄 상대 uid 목록] — "모임 내 사용자 상세 설정", RTDB에도 함께 올라간다. */
    val hiddenFromUidsByGroup: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    /** 모임ID -> [내가 보고 싶지 않아 숨긴 상대 uid 목록] — 순수 로컬 표시 설정, RTDB엔 올리지 않는다. */
    val hiddenPeerUidsByGroup: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    /** 모임ID -> "무작위 알림"(77차) 켜짐 여부 — 이 모임에서 이 기기가 처지는 멤버를 자동으로 깨울지,
     *  순수 로컬 설정(발신 여부만 결정하므로 RTDB엔 안 올림). 기본값 true(모임 가입 자체가 참여 의도). */
    val groupRandomNudgeEnabled: MutableMap<String, Boolean> = mutableMapOf(),
    /** 모임별 마지막으로 확인한 넛지 시각(epoch millis) — groupId -> millis. 새 넛지 도착 판정용. */
    val nudgeLastSeenByGroup: MutableMap<String, Long> = mutableMapOf(),
    /** 가입 신청/승인 게이트(AccountGateScreen) — 마지막으로 서버에서 확인한 내 승인 상태
     *  ("pending"/"approved"/"rejected", 아직 한 번도 확인 못했으면 null). "approved"였다면 앱 시작 시
     *  네트워크 응답이 오기 전에도 낙관적으로 메인 화면을 먼저 보여주고 백그라운드에서 재확인한다. */
    var cachedApprovalStatus: String? = null,
    /** 관리자가 승인 시(또는 이후) 지정한 기능별 사용 허가 캐시본 — 필드가 아예 없던 옛 승인 사용자와의
     *  하위호환을 위해 기본값은 전부 true(제한 없음). [AccountGateScreen]이 승인 확인 때마다 갱신한다. */
    var permRoutine: Boolean = true,
    var permStudy: Boolean = true,
    var permManage: Boolean = true,
    var permSocial: Boolean = true,
    // ---- 자체 업데이트 확인(GitHub Releases, 2026-08-30) ----
    /** 마지막으로 GitHub Releases를 확인한 날짜(effectiveDate 기준) — 안드로이드판 lastUpdateCheckDate와 동일 패턴. */
    var lastUpdateCheckDate: String? = null,
    /** GitHub Releases에서 발견한 최신 데스크탑 릴리스의 빌드 타임스탬프(BuildInfo.BUILD_TIMESTAMP와 비교). 0이면 "새 버전 없음". */
    var updateAvailableBuildTimestamp: Long = 0L,
    /** 위 빌드 타임스탬프에 대응하는 설치파일(exe/msi) 다운로드 URL. */
    var updateAvailableInstallerUrl: String? = null
)
