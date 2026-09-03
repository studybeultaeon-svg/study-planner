package com.phonelock.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.phonelock.shared.calc.PassSchedule

/**
 * scheduleDaysMask: bit 0 = 월요일 ... bit 6 = 일요일. 기본값 127 = 매일.
 */
@Entity(tableName = "app_group")
data class AppGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    /** 실행 확인에서 "예"를 한 뒤, 같은 그룹 내 다른 앱/사이트도 재확인 없이 허용되는 유예시간(초). */
    val confirmCooldownSeconds: Int = 300,
    /** 확인 후 유예시간 동안 화면에 남은 시간을 보여주는 오버레이를 이 그룹에서 표시할지 여부. */
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
    /** 실행 확인 레벨이 시간이 지나면서 자연히 차감(-1)되는 기능 자체의 on/off. */
    val levelDecayEnabled: Boolean = true,
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
    /** "미래의 나에게" 예약 메시지(82차, §11 창의적 기능) — 지금의 내가 남긴 문구를 이 그룹이 잠길 때
     *  회유 멘트 대신/함께 보여준다. 비어있으면 기존처럼 랜덤 문구만 표시. 순수 로컬 텍스트, 동기화 안 함. */
    val selfMessageText: String = ""
)

@Entity(tableName = "group_member", primaryKeys = ["groupId", "packageName"])
data class GroupMember(
    val groupId: Long,
    val packageName: String
)

@Entity(tableName = "usage_record", primaryKeys = ["groupId", "date"])
data class UsageRecord(
    val groupId: Long,
    val date: String,
    val usedSeconds: Int = 0
)

@Entity(tableName = "group_site", primaryKeys = ["groupId", "domain"])
data class GroupSite(
    val groupId: Long,
    val domain: String
)

/**
 * 실행 확인 대기시간 증가/감소 상태. 그룹마다 따로 관리된다 (한 그룹에서 많이 확인해도 다른 그룹의
 * 대기시간에는 영향이 없다). level이 1 증가할 때마다 대기시간이 늘고, lastConfirmedAtEpochMillis로부터
 * 1시간이 지날 때마다 level이 1씩 자연 감소한다 (고정 초기화 시각 없음).
 */
@Entity(tableName = "confirm_escalation")
data class ConfirmEscalation(
    @PrimaryKey val groupId: Long,
    val level: Int = 0,
    val lastConfirmedAtEpochMillis: Long = 0
)

/**
 * 하루에 이 그룹의 재확인 화면을 몇 번 통과했는지("위반 시도" 횟수, 전문가 종합분석 보고서 #32) —
 * 순수 로컬 통계용이며 판정 로직(ConfirmationGate.kt)과는 무관. recordConfirm() 호출부에서만 증가시킨다.
 */
@Entity(tableName = "confirm_counter", primaryKeys = ["groupId", "date"])
data class ConfirmCounter(
    val groupId: Long,
    val date: String,
    val count: Int = 0
)

/** 네이티브 공부 타이머(1단계)의 완료된 공부 기록 한 건. 웹앱 index.html의 studyLog를 이식. */
@Entity(tableName = "study_log_entry")
data class StudyLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,
    val taskName: String,
    val seconds: Int,
    val startedAt: Long,
    val note: String = "",
    /** 포모도로 세션 태그(82차, §9 "포모도로 세션 태그") — 과목 등 자유 입력, 통계 탭에서 태그별 집계에 사용. */
    val tag: String = ""
)

/**
 * 네이티브 캘린더(2단계)의 날짜별 일정 한 건. 웹앱 index.html의 calTasks[dateKey][] 항목을 그대로 이식.
 * color는 현재 red/yellow/green 3단계로 축소돼 있음(과거 8단계 무지개 서술은 낡은 기록이었음) — 다회독
 * 상세화(83차)부터는 passIndex/passTotal/passIntervalsCsv가 실제 회독 진행/색상 렌더링의 원천이고,
 * color는 passTotal==3인 기본 케이스의 하위호환 라벨로만 계속 쓰인다(레거시 코드가 "red"/"yellow"/
 * "green" 문자열을 직접 비교하는 곳이 많아 필드 자체는 유지). sortOrder는 Room에 배열 순서 개념이 없어
 * 대신 쓰는 정수 순번(같은 dateKey 안에서만 의미 있음).
 * linkedCalc/progressStep은 계산기 연동용 필드(51차에 UI 추가) — linkedCalc는 연결된 계산기 업무 이름,
 * progressStep은 이 일정을 완료하면 그 업무 progress에 더해질 양(예: "51~60쪽" → "10").
 */
@Entity(tableName = "calendar_task")
data class CalendarTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,
    val name: String,
    val color: String,
    val status: String? = null,
    val nextDays: Int? = null,
    val linkedCalc: String? = null,
    val progressStep: String? = null,
    val sortOrder: Int = 0,
    /** 완료(O) 시 다음 회독을 자동 생성할지(79차, 사용자 요청) — 기본 off. */
    val multiPassEnabled: Boolean = false,
    /** 이 시리즈에서 0-based 현재 회독 번호(83차, 다회독 상세화). 레거시 데이터는 마이그레이션에서 color 기준으로 채움. */
    val passIndex: Int = 0,
    /** 이 시리즈의 총 회독 수(3~8). */
    val passTotal: Int = 3,
    /** 회독 간 간격(일수) CSV, 길이 = passTotal-1. 생성 시점 CalcTask/설정 기본값에서 복사되어 다음 회독까지 그대로 이어짐. */
    val passIntervalsCsv: String = PassSchedule.DEFAULT_INTERVALS_CSV
)

/**
 * 네이티브 계산기(3단계)의 입력 카드 한 장(웹앱 index.html의 task-card/`autoSaveDraftNow`가 다루는
 * draft 업무 하나에 대응). 요일별 목표(mon~sun)와 qty/progress를 전부 String으로 두는 이유는 웹앱
 * 쪽도 입력 필드 값을 그대로 저장하기 때문 — 계산(calculate) 시점에만 숫자로 파싱한다. holidays는
 * Room이 List를 직접 못 담아 쉼표구분 문자열로 저장(데스크탑 JsonStore는 배열, 이 차이는 저장 방식
 * 차이일 뿐 의미는 동일 — CalendarTask의 id+sortOrder처럼 플랫폼별 자연스러운 차이, DECISIONS.md 참고).
 */
@Entity(tableName = "calc_task")
data class CalcTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val qty: String = "",
    val unit: String = "",
    val progress: String = "",
    val start: String = "",
    val dday: String = "",
    val mon: String = "", val tue: String = "", val wed: String = "", val thu: String = "",
    val fri: String = "", val sat: String = "", val sun: String = "",
    val holidaysCsv: String = "",
    val modifiedAt: String = "",
    val modifiedAtTs: Long = 0L,
    val sortOrder: Int = 0,
    /** 캘린더 일정 자동 생성 on/off(82차, 사용자 지정 스펙) — 켜면 연동 일정을 완료할 때마다 다음 배치를 자동으로 만든다. */
    val autoGenEnabled: Boolean = false,
    /** 자동 생성 배치 크기(예: 10을 넣으면 "51~60쪽"처럼 10단위씩 다음 일정을 만든다). */
    val autoGenBatchSize: Int = 0,
    /** 다회독 상세화(83차) — 이 업무를 캘린더에 연동할 때 몇 회독으로 만들지(3~8). */
    val passCount: Int = PassSchedule.DEFAULT_PASS_COUNT,
    /** 회독 간 간격(일수) CSV, 길이 = passCount-1. */
    val passIntervalsCsv: String = PassSchedule.DEFAULT_INTERVALS_CSV,
    /** 다회독 사용 여부(85차, 사용자 요청) — OFF면 캘린더 연동 시 passCount를 무시하고 단회독(1회독)만
     *  생성한다. 기존 데이터는 true가 기본값이라 이전처럼 passCount 그대로 다회독으로 연동된다. */
    val multiPassUsageEnabled: Boolean = true
)

/**
 * "저장됨" 목록의 계산 결과 한 건(웹앱 saveOneResult()가 만드는 saved[] 항목에 대응). 웹앱은 이
 * 시점의 qty/progress를 이미 숫자로 파싱해서 저장하므로(반면 draft 쪽은 문자열) Double로 둔다.
 * folderPathCsv는 폴더 경로를 "|"로 이어붙인 것("" = 미분류) — 데스크탑의 List<String>?와 동일 정보.
 */
@Entity(tableName = "calc_saved_item")
data class CalcSavedItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val qty: Double = 0.0,
    val unit: String = "",
    val progress: Double = 0.0,
    val start: String = "",
    val dday: String = "",
    val mon: String = "", val tue: String = "", val wed: String = "", val thu: String = "",
    val fri: String = "", val sat: String = "", val sun: String = "",
    val holidaysCsv: String = "",
    val savedAt: String = "",
    val modifiedAt: String = "",
    val folderPathCsv: String = "",
    val sortOrder: Int = 0
)

/**
 * 루틴앱 v1(47차 설계, DECISIONS.md 참고)의 핵심 모델 — "반복 체크리스트"/"습관 트래커"/"시간대별
 * 일과표" 세 요구를 하나로 통합한다. timeSlot이 있으면 일과표 뷰에 시간순으로 나타나고(파생 뷰, 별도
 * 저장 없음 — TimetableScreen이 CalcTask를 파생시키는 패턴과 동일), trackStreak이 true면 통계에서
 * 스트릭이 계산된다("체크리스트"와 "습관"은 이 플래그 하나 차이).
 */
@Entity(tableName = "routine")
data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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

/** Routine의 날짜별 완료 기록 — 존재 자체가 "그날 완료"를 의미한다(웹앱 studyLog 등과 동일하게 완료된 것만 insert). */
@Entity(tableName = "routine_log", primaryKeys = ["routineId", "dateKey"])
data class RoutineLog(
    val routineId: Long,
    val dateKey: String
)

/**
 * 회유 멘트 성공률 통계(82차, §9/§11) — 재확인/차단 화면에서 어떤 문구가 뜬 상태에서 사용자가
 * "진행"(자기통제 실패, 앱을 열기로 함)/"중단"(자기통제 성공, 포기)을 골랐는지 순수 기록. 판정
 * 로직(ConfirmationGate)과 무관 — 이미 결정된 선택을 로깅만 한다.
 */
@Entity(tableName = "quote_outcome")
data class QuoteOutcome(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tier: Int,
    val quoteText: String,
    /** "PROCEED"(진행, 굴복) | "STOP"(중단, 저항). */
    val choice: String,
    val timestampMillis: Long
)
