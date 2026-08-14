# BUGS

현재 알려진 버그 관리. 해결되면 Fixed로 유지하거나 제거.

---

## Fixed (2026-08-14, 56차 세션)

### 안드로이드: 루틴 알림이 정시보다 약 2분 늦게 옴
- **원인**: `RoutineAlarmScheduler.kt`가 52차부터 배터리 절약 목적으로 부정확 알람(`setAndAllowWhileIdle`)을 썼는데, 이게 Doze 모드에서 시스템이 알람 전달을 지연시키는 정상 동작이었다.
- **해결**: `setExactAndAllowWhileIdle`로 전환, `canScheduleExactAlarms()` 확인 후 권한 없으면 기존 부정확 알람으로 자동 폴백. `SCHEDULE_EXACT_ALARM` 매니페스트 권한 추가, 설정 화면에 허용 버튼 신규. **실기기 미검증**(권한 허용 후 정시 도착 확인 필요).

### 안드로이드: 다른 기기에서 만들거나 수정한 루틴은 알림이 아예 예약 안 됨
- **원인**: `MainActivity.onCreate`의 앱 실행 시 재예약(`RoutineAlarmScheduler.rescheduleAll()`)이 로컬 Room DB만 봄 — Firebase 최신 데이터를 끌어오는 `syncRoutinesFromFirebase()`는 "루틴" 탭 화면에 직접 들어갈 때만 호출됐다. 다른 기기에서 루틴을 추가/수정한 뒤 이 폰에서 "루틴" 탭을 한 번도 안 열면 로컬 DB가 옛 상태 그대로라 알림 자체가 예약 안 됨(지연이 아니라 누락).
- **해결**: `MainActivity.onCreate`에서 `rescheduleAll()` 전에 `syncRoutinesFromFirebase()`를 먼저 호출하도록 수정. 앱을 아예 안 켜는 경우까지 커버하는 백그라운드 주기적 동기화는 이번 범위에서 제외(사용자 확인, 배터리/복잡도 트레이드오프). **실기기 미검증**.

---

## Fixed (2026-08-14, 54차 세션)

### 데스크탑: 앱을 껐다 켜면 루틴 아이콘(및 알림설정/기간설정)이 전부 사라짐
- **원인**: 52차에 `Routine`에 추가된 `icon`/`notifyEnabled`/`startDate`/`endDate` 4개 필드가 Firebase 동기화 경로(`Repository.kt`)엔 정상 반영됐지만, 로컬 영속화 파일 `JsonStore.kt`의 루틴 save()/parse()엔 빠져 있었다(36차 `pomodoroModeEnabled`와 동일 유형). 게다가 `pushRoutinesToFirebase()`가 push 시점에 로컬 `routinesTs`도 원격과 같은 값으로 맞춰버려서, 재시작 후 LWW 비교(`result.ts > data.routinesTs`)가 "동일값이라 어느 쪽도 최신 아님"으로 판정 — 코드만 고쳐도 이미 망가진 로컬 데이터는 스스로 복구되지 않는 함정이었다. Firebase REST API로 원격 26개 루틴의 icon이 전부 정상임(`_ts`도 로컬과 정확히 동일)을 확인해 원인 확정.
- **해결**: `JsonStore.kt` save()/parse() 양쪽에 4개 필드 추가. 이미 망가진 이번 기기 로컬 데이터는 앱 종료 후 `data.json`의 `routinesTs`를 0으로 강제 리셋해 다음 "루틴" 탭 진입 시 원격에서 재수신하도록 유도 — 사용자가 아이콘 정상 표시를 직접 확인 완료.
- **재발 방지 관점**: `Routine`처럼 로컬(JsonStore)과 원격(Firebase) 양쪽에 동시에 필드를 저장하는 모델에 새 필드를 추가할 땐 두 저장 경로 모두 빠짐없이 반영했는지 항상 같이 확인할 것(36차에 이미 한 번 겪은 유형). [[DECISIONS.md]] 54차 참고.

---

## Fixed (2026-08-14, 53차 세션)

### 안드로이드/데스크탑: 계산기 "저장됨" 항목 34개 + 폴더 트리가 Firebase에서 사라짐
- **원인**: 52차의 Room 버전업 재앙 때, `resetSyncTimestamps()` 수정이 배포되기 전 시점에 안드로이드가 빈 로컬 계산기 데이터(마이그레이션으로 테이블이 지워진 직후)를 `saved=[]`로 그대로 Firebase에 푸시해 원격의 진짜 저장 데이터를 덮어썼다. Firebase RTDB는 빈 배열을 저장하면 해당 키 자체를 지워버려서, REST로 조회하면 `saved` 키가 아예 없는 상태로 보였다.
- **해결**: 데스크탑 로컬 `data.json`엔 이 사고 이전 원본이 그대로 남아있었음(우연히 동기화 타임스탬프가 원격과 일치해서 그 이후 push/pull이 한 번도 안 일어났음) — 데스크탑의 `calcSavedTs`/`calcFolderTs`를 강제로 최신화해 앱의 정상 push 경로가 다시 실행되게 했다. `pushCalcTasksAndSaved()`가 `saved` 내용은 올바르게 올렸지만 `savedTs` 필드 자체는 갱신하지 않는 걸 뒤늦게 발견해, 이 필드만 Firebase REST PATCH로 직접 최신화 — 이후 안드로이드가 계산기 탭 재진입 시 정상적으로 34개 항목을 받아옴을 확인. [[DECISIONS.md]] 53차 참고.

### 안드로이드: 설정 화면 테마 선택 칩이 8종으로 늘며 화면 밖으로 잘림
- **원인**: 3종 전용으로 짠 고정폭 `Row(horizontalArrangement=...)`에 `FilterChip` 8개를 넣어 화면 폭을 넘어섬.
- **해결**: `FlowRow`(자동 줄바꿈)로 교체, `THEME_DISPLAY_NAMES` 목록을 순회하는 방식으로 리팩터링(항목 추가 시 코드 중복 없이 자동 반영). 데스크탑도 동일하게 통일.

### 안드로이드: 태블릿에서 공부앱/루틴앱 통계 탭 "최근 30일 완료 추이" 그래프가 좁게 뭉쳐 보임
- **원인**: 50차에 폰 화면 대응으로 막대 폭을 20dp 고정+가로스크롤로 바꿨는데, 이 처리가 태블릿 화면에도 조건 없이 그대로 적용됨 — 태블릿의 넓은 폭을 못 쓰고 그래프가 왼쪽에 작게 뭉친 채 스크롤바만 보임.
- **해결**: `LocalConfiguration.current.screenWidthDp >= 600`(태블릿 기준, `InterstitialScreen.kt`와 동일)으로 분기해 태블릿에선 `weight(1f)` 균등분할로 폭 전체를 채우도록 수정(`StudyStatsScreen.kt`/`RoutineScreen.kt`).

### 안드로이드/데스크탑: 테마를 바꿔도 실행확인/차단 화면 "중단" 버튼과 "사용 중" 오버레이 타이머 색이 초록 고정
- **원인**: `InterstitialScreen`/`WatchAndWaitScreen`을 호출하는 각 화면(`ConfirmOpenActivity.kt`/`BlockActivity.kt`/`ConfirmScreen.kt`/`BlockScreen.kt`)이 `secondaryContainerColor` 파라미터에 `Color(0xFF8BC34A)`(라이트+그린 리터럴)를 직접 넘기고 있었다 — 49차 테마 전환 때 이런 개별 파라미터 리터럴은 못 찾았던 것으로 보임. 안드로이드 접근성 오버레이(`AppMonitorAccessibilityService.kt`)와 데스크탑 코너 위젯(`UsageOverlayContent.kt`, 애초에 `PhoneLockTheme`로 감싸지지도 않았음)의 타이머 색도 같은 문제.
- **해결**: 4곳 전부 `MaterialTheme.colorScheme.primary`로 교체, 데스크탑 오버레이는 `Main.kt`에서 `PhoneLockTheme(themeMode) { UsageOverlayContent(...) }`로 감싸도록 수정, 안드로이드 접근성 서비스는 Compose 문맥이 아니라 `AppPreferences.themeMode`→`paletteFor()`로 직접 팔레트를 조회하는 헬퍼 추가.

---

## Fixed (2026-08-14, 52차 세션)

### 안드로이드: 릴스/쇼츠 감지 차단이 작동 안 함
- **원인**: `AppMonitorAccessibilityService.kt`의 `REELS_KEYWORDS`/`SHORTS_KEYWORDS` 문자열 리터럴이 파일 바이트 단위로 깨져 있었다(정상 "릴스"/"쇼츠"가 아니라 mojibake로 저장돼 있었음 — 화면 렌더링 문제가 아니라 실제 파일 바이트가 잘못됨, `[System.IO.File]::ReadAllBytes`+UTF-8 디코딩으로 직접 확인). 컴파일은 정상적으로 되지만 실제 화면 텍스트("릴스", "쇼츠")와 절대 매칭될 수 없어 감지 자체가 항상 실패했다. 언제/어떻게 깨졌는지는 특정 못함(과거 세션의 어떤 편집 도구가 인코딩을 잘못 처리했을 가능성).
- **해결**: 올바른 UTF-8 한글 리터럴로 교체, 컴파일된 클래스 파일에 정상 반영된 것까지 `grep -a` 확인.

### 안드로이드: Room DB 버전을 올리면(fallbackToDestructiveMigration) 캘린더/루틴/계산기 동기화가 조용히 멈춤 — 그룹 데이터도 통째로 유실
- **원인**: 이번 세션에 루틴 아이콘/알림/기간 필드 추가로 Room DB 버전을 26→27로 올렸는데, `fallbackToDestructiveMigration()`이 로컬 SQLite 전체(그룹/사용기록/루틴/캘린더/계산기 등 전부)를 지운다. 그런데 캘린더/루틴/계산기의 "마지막 동기화 시각"(`calendarTs`/`routinesTs`/`calcTasksTs`/`calcSavedTs`/`calcFolderTs`/`calcFolderOrderTs`)은 Room이 아니라 `AppPreferences`(SharedPreferences)에 저장돼 있어 DB 초기화의 영향을 안 받고 그대로 살아남는다. 그 결과 다음 동기화 때 "로컬 데이터는 텅 비었지만 타임스탬프는 예전 그대로"인 상태가 되어, LWW 비교(`원격 ts > 로컬 ts`)가 "이미 최신"으로 오판하고 아무것도 다시 받아오지 않았다(실사용자 보고로 발견 — 캘린더/루틴/계산기 탭이 전부 빈 목록으로 보임). 그룹(차단 대상 앱/사이트 목록)은 애초에 Firebase에 동기화되지 않는 데이터라 이 초기화로 실제로 유실됨.
- **해결**: `PreMigrationBackup.backupIfVersionChanged()`가 백업을 만든 직후(=이번 실행에서 앱 버전이 바뀐 게 확인된 직후) `AppPreferences.resetSyncTimestamps()`로 위 6개 타임스탬프를 전부 0으로 리셋해, 다음 동기화가 무조건 원격에서 다시 받아오도록 강제(`MainActivity.onCreate`). Firebase에 있던 원격 데이터 자체는 REST API로 직접 조회해 멀쩡함을 확인함(루틴 22개/캘린더 43일치/계산기 데이터 전부 존재) — 유실된 건 그룹뿐.
- **그룹 복구**: Firebase에 안 올라가는 그룹 데이터를 위해 `PhoneLockRepository.restoreGroupsFromBackup()`(raw SQLite 백업 JSON에서 `app_group`/`group_member`/`group_site`/`usage_record`/`confirm_escalation`/`confirm_counter` 파싱해 원본 id 그대로 복원) + 설정 화면 "⚠ 그룹 데이터 복구" 카드(`PreMigrationBackup.listBackups()`로 찾은 최신 백업에서 1클릭 복구) 신규 추가. **사용자가 실제로 복구 버튼을 눌러본 결과는 이 세션 종료 시점에 미확인** — 다음 세션 최우선 확인 대상.
- [[DECISIONS.md]] 52차 "Room 버전을 올릴 땐 SharedPreferences 타임스탬프도 같이 고려" 참고.

---

## Open (미해결, 52차 세션 종료 시점)

### 안드로이드: 그룹 데이터 복구 버튼 실사용 미확인
- **상황**: 위 "동기화 버그" 항목의 그룹 유실 사용자에게 새 APK(복구 기능 포함)를 전달했으나, 실제로 "⚠ 그룹 데이터 복구" 카드가 뜨는지·복구 버튼을 눌렀을 때 그룹이 정상 복원되는지·복원된 그룹으로 차단이 실제로 다시 작동하는지 세션 종료 시점까지 확인 못함. **다음 세션 최우선**.

---

## Fixed (2026-08-13, 50차 세션)

### 데스크탑: 루틴 오늘 탭 체크박스가 바로 반영 안 되고 다른 탭 왔다갔다 해야 체크된 걸로 보임
- **원인**: `RoutineLog` 토글 후 `mutableStateOf<List<Routine>>`을 구조적으로 동일한 새 리스트로 재할당해 Compose가 리컴포지션을 스킵 — 그룹 탭에서 이미 겪었던 것과 같은 유형의 버그.
- **해결**: 항상 증가하는 `refreshTick: Int`를 `key(refreshTick){...}`으로 감싸 강제 리컴포지션. [[DECISIONS.md]] 50차 참고.

### 안드로이드: 루틴 오늘 탭 요일 피커에서 금/토 칩이 화면 밖으로 밀려 안 보임
- **원인(1차 시도 실패)**: `horizontalScroll`+`FilterChip`으로 구현했으나 스크롤이 있어도 여전히 잘려 보이는 문제 재현 — 스크롤 인디케이터가 명확하지 않은 좁은 화면에서 사용자가 스크롤을 인지 못함.
- **해결(2차, 확정)**: 스크롤 자체를 없애고 7개 칩을 `Modifier.weight(1f)` 균등 분할 `Column`으로 재작성해 화면 폭과 무관하게 항상 7개가 다 보이도록 함. 계산기 연동 업무 선택 버튼도 동일 유형 문제라 `FlowRow`(줄바꿈)로 해결. [[DECISIONS.md]] 50차 참고.

---

## Fixed (2026-08-13, 46차 세션)

### (작업 중 발견한 필드 혼동, 기존 앱 버그 아님) 데스크탑 그룹을 껐는데도 차단이 안 꺼짐
- **원인**: `Group` 모델에 이름이 비슷한 필드가 둘 있음 — `enabled`(통계 탭 표시 필터 전용, `Models.kt` 주석에 "잠금/차단 판정에는 전혀 관여하지 않는다"고 명시)와 `groupEnabled`(그룹 목록 화면 스위치, 실제 차단 on/off). `data.json`을 직접 편집해 그룹을 끌 때 `enabled`를 껐다가 사용자가 "off 안됐는데?"라고 지적해서 발견.
- **해결**: `enabled`는 원래 값(true)으로 되돌리고 `groupEnabled=false`로 다시 설정, 앱 재시작 후에도 유지되는 것 확인. 메모리 `project_group_enabled_vs_groupEnabled`에 기록해 재발 방지.

---

## Open (기타)

### 데스크탑: Alt-Tab으로 허용된 브라우저에 진입해서 허용 안 된 사이트를 실제로 이용 가능
- **원인 확정(35차 세션, 코드 조사)**: `background.js`의 차단 판정은 `chrome.webNavigation.onBeforeNavigate`(새 네비게이션 발생 시)에만 걸려있다([background.js:155](phone-lock-desktop/browser-extension/background.js:155)). 공부 잠금이 켜지기 전부터 이미 로드돼 있던 차단 대상 사이트 탭으로 Alt-Tab만 해서 돌아오는 경우는 네비게이션 이벤트가 아니라서 이 리스너가 아예 발동하지 않는다. 유일한 백업은 `chrome.alarms.create("tick", { periodInMinutes: 1 })` 1분 주기 폴링뿐([background.js:181](phone-lock-desktop/browser-extension/background.js:181), [background.js:237-259](phone-lock-desktop/browser-extension/background.js:237-259))이라, 최악의 경우 최대 60초간 실제로 그대로 이용 가능하다. 데스크탑 쪽 `SiteEnforcement.kt`의 판정 로직(`isBlockedByStudyLock()` 포함) 자체는 정상 — 확장이 판정을 물어보는 시점 자체가 너무 늦게(최대 60초 뒤) 온다는 게 진짜 원인.
- **해결 방향(미적용, 사용자 확인 후 진행 예정)**: `chrome.tabs.onActivated`/`chrome.windows.onFocusChanged` 리스너를 추가해 탭/창 포커스가 바뀌는 즉시 재검사하도록 하면 지연을 1분에서 사실상 즉시로 줄일 수 있음.

---

## Fixed (2026-08-10, 39차 세션)

### 데스크탑+안드로이드: `StudyTimerScreen.kt`의 "오늘 한눈에" 요약 카드가 컴파일 자체가 안 됨
- **원인**: `"$totalCount개(완료 $doneCount)"` — Kotlin 문자열 템플릿은 한글도 식별자 문자로 인식해서 `$totalCount개`가 `totalCount개`라는 존재하지 않는 변수 참조로 파싱됨(38차에 처음 작성된 코드, 양 플랫폼 대칭 구조라 동일 버그가 둘 다 있었음).
- **해결**: `${totalCount}개`로 중괄호를 추가해 식별자 경계를 명시. 이후 데스크탑 `compileKotlin`, 안드로이드 `compileDebugKotlin`/`assembleDebug` 전부 BUILD SUCCESSFUL 확인.

## Fixed (2026-08-10, 38차 세션) — 코드 수정 완료, **39차 세션에 컴파일 확인 완료**

전문가 종합분석 보고서 작성 중 새로 발견한 버그 6건. 38차엔 gradle이 없어 컴파일조차 못 확인했으나(당시 "미검증" 표기), 39차 세션에 컴파일 툴체인이 있는 환경에서 확인해보니 위 문자열 템플릿 버그 1건을 제외하면 전부 정상 컴파일됨.

### [Critical] 데스크탑: `pushUsageToFirebase`가 뮤텍스 두 개를 쥔 채 동기 Firebase HTTP 호출
- **위치**: `phone-lock-desktop/.../data/Repository.kt` `pushUsageToFirebase()`
- **원인**: 자매 함수(`pushStudyLogToFirebase` 등 4개)는 `Thread{}.start()`로 비동기인데 이 함수만 동기. 호출 경로 `EnforcementService.tick()`(`tickMutex`) → `addUsageSeconds()`(`Repository.lock`) → 이 함수 — 최대 수초 네트워크 I/O 동안 다른 그룹 감시/Repository 전체가 정지될 수 있었음.
- **해결**: 다른 push 함수와 동일한 `Thread{}.start()` 패턴으로 전환. 셧다운훅 경로(`flushPendingUsage()`)는 전송 유실 방지를 위해 별도 동기 버전(`pushUsageToFirebaseBlocking`) 유지.

### [High] 데스크탑: `peerUsageSeconds`(모바일 사용시간 합산 읽기)도 락 안에서 블로킹
- **원인**: 10초 캐시 만료 시 `synchronized(lock)` 안에서 동기 GET — 위와 동일한 유형.
- **해결**: 캐시값(또는 0) 즉시 반환 + 백그라운드 스레드에서 갱신 후 짧게 재잠금해 캐시만 갱신하는 패턴으로 전환.

### [High] 안드로이드: `PreMigrationBackup.TABLES`가 25~27차 신규 테이블 4개 누락
- **원인**: `study_log_entry`/`calendar_task`/`calc_task`/`calc_saved_item`이 백업 대상에서 빠져 있어, 다음 Room 스키마 변경(`fallbackToDestructiveMigration()`) 시 이 데이터가 유일한 안전망 없이 유실될 수 있었음.
- **해결**: `TABLES`에 4개 추가(+ 이번 세션에 추가한 `confirm_counter`까지 총 5개).

### [High] 안드로이드: 캘린더/계산기 Firebase 동기화의 delete→insert 구간에 트랜잭션 부재
- **원인**: `syncCalendarFromFirebase()`/`syncCalculatorFromFirebase()`가 `deleteAll()` 이후 별도로 `insert()`를 반복 호출 — 그 사이 프로세스가 죽으면 로컬이 빈 상태로 남을 위험(`importBackupJson()`은 이미 안전했음).
- **해결**: 세 군데(캘린더, 계산기 draft, 계산기 저장됨) 모두 `db.withTransaction{}`으로 delete+insert를 하나로 묶음.

### [Medium] 안드로이드: `ConfirmOpenActivity.recordConfirm()`이 `lifecycleScope` 사용
- **원인**: `Repository.kt` 자체 주석이 경고하는 "화면 소멸 시 스코프 취소로 쓰기 유실" 패턴을 이 호출부만 어김.
- **해결**: `PhoneLockRepository.recordConfirmFireAndForget(groupId)` 신설(자체 `ioScope` 기반)로 교체.

### [Medium] 안드로이드: `addUsageSeconds`의 read-modify-write가 뮤텍스 없이 동시 실행 가능
- **원인**: `tick()`과 `checkSitesInternal()`이 독립 `AtomicBoolean` 가드로 동시 진행 가능해 같은 그룹 사용시간이 유실될 수 있었음.
- **해결**: `usageMutex` 신설.

### [Medium] 안드로이드: `PomodoroSyncClient`의 `tokenCache`/`statusCache`가 동기화 없는 공유 var
- **원인**: 여러 IO 스레드에서 check-then-act로 접근.
- **해결**: `@Volatile` 추가.

---

## Fixed (2026-08-08, 36차 세션)

### 데스크탑/안드로이드: "🍅 뽀모도로 모드" 토글이 다른 서브탭 갔다 오면 항상 OFF로 초기화
- **원인**: `StudyTimerScreen`의 `pomodoroEnabled`가 `remember { mutableStateOf(false) }`로 하드코딩돼 있어, 서브탭을 벗어났다 돌아올 때 컴포저블이 리마운트되면서 항상 초기값(false)으로 리셋됐다.
- **해결**: `pomodoroStudyMinutes`/`pomodoroBreakMinutes`와 동일한 패턴으로 `pomodoroModeEnabled`를 Repository에 영속화(데스크탑 `Models.kt`+`Repository.kt`+`JsonStore.kt`, 안드로이드 `AppPreferences.kt`+`PhoneLockRepository.kt`), 초기값을 그걸로 읽고 클릭 시 즉시 저장. 데스크탑은 수동 JSON 직렬화라 `JsonStore.kt`의 `parse()`/`save()`에도 필드를 빠짐없이 추가해야 했음(처음 한 번 누락했다가 재시작 시 안 살아남는 걸로 뒤늦게 발견).

### (도구 사용 문제, 앱 버그 아님) robocopy를 Bash 도구로 실행하면 이 프로젝트(한글 경로)에서 조용히 아무것도 복사 안 함
- **증상**: 위 토글 수정을 완료하고 여러 차례 "재빌드/재배포 완료"라고 보고했지만 사용자가 반영 안 된다고 계속 재현 — desktop `gradle compileKotlin`/`jar`가 매번 `UP-TO-DATE`로 스킵되며 "BUILD SUCCESSFUL"만 찍혀서 정상처럼 보였음.
- **원인**: `robocopy "phone-lock-desktop\src" "...\AndroidBuilds\...\src" /MIR`를 Bash 도구로(상대경로, `cd` 이후) 실행하면 exit code 등은 정상처럼 보이지만 실제로 파일이 하나도 안 옮겨짐 — Git Bash가 `OneDrive\바탕 화면\클로드\관리앱`의 한글 경로를 처리하는 과정에서 조용히 실패하는 것으로 추정. 배포된 jar를 직접 압축 해제해 `.class` 파일에서 새 필드/문자열을 grep해보고 나서야 `AndroidBuilds`의 소스가 옛날 그대로임을 발견.
- **해결**: robocopy를 PowerShell 도구 + 절대경로로 재실행하니 정상적으로 "Newer" 표시와 함께 복사됨. 메모리 `feedback_robocopy_use_powershell`에 기록 — 앞으로 이 프로젝트의 robocopy 단계는 항상 PowerShell로 실행할 것.

## Fixed (2026-08-07, 34차 세션)

### 캘린더 날짜 상세: 다른 기기가 방금 남긴 공부 기록이 패널을 계속 열어놔도 안 보임
- **원인**: 날짜 상세 패널의 공부 기록 동기화가 `LaunchedEffect(dateKey)`로 패널 진입 시 딱 1회만 실행됐다 — 그 날짜를 이미 열어둔 채로 다른 기기에서 새로 기록을 남겨도 다시 동기화되지 않았다.
- **해결**: 5초 주기 while 루프로 바꿔 패널이 열려 있는 동안 계속 갱신되게 함(양 플랫폼). 데스크탑 로컬 데이터파일에서 Firebase 설정을 읽어 REST API로 서버 상태를 직접 조회해 원인 범위를 좁힌 뒤 발견.

## Fixed (2026-08-07, 33차 세션)

### 데스크탑: 공부 타이머가 "공부" 페이즈로 작동 중일 때 허용 프로그램이 실행 안 됨
- **원인**: `ProcessBuilder(appName)`은 PATH 환경변수만 검색해서 "chrome.exe"처럼 설치 경로가 PATH에 없는 실행파일명은 `CreateProcess error=2`로 못 찾음(`debug.log`로 확정).
- **해결**: `resolveAppPath()` 신설 — 레지스트리 `App Paths` → 시작 메뉴 바로가기(`.lnk`) → PATH 순으로 이름을 해석. 자세한 배경은 [[DECISIONS.md]] "공부 잠금 허용 프로그램 실행: 이름만으로 찾는 3단계 폴백" 참고.

### 데스크탑/안드로이드: 공부 타이머 측정 결과가 "오늘의 공부 기록"에 기록 안 됨
- **원인**: 저장 로직(`Repository.timerStop()`)은 정상 동작 중이었음(`data.json`으로 확인) — 전체화면 잠금 화면에서 정지/전환했을 때 타이머 탭의 `todayLog` UI 상태가 안 갱신되는 표시 버그였음.
- **해결**: 두 화면 모두 1초 tick 루프에서 `run == null`일 때 `todayLog`도 같이 재조회하도록 추가.

### 안드로이드: "설치 앱 목록에서 골라 허용앱 지정" 창이 안 뜸
- **원인**: `AllowedAppsPickerBody`의 `LazyColumn`이 `weight(1f, fill=false)`를 쓴 `Column` 안에 중첩돼 있어, 특히 타이머 탭의 `verticalScroll(Column)`(무한 높이 부모) 안에서 쓰일 때 중첩 스크롤 충돌로 렌더링이 깨졌던 것으로 보임. 정상 작동하는 `GroupEditScreen`은 앱 목록을 최상위 `LazyColumn`의 `items()`로 바로 펼치는 방식이라 이 문제가 없었음.
- **해결**: `weight()` 제거, `heightIn(max=360dp)`만으로 고정 높이 부여.

### 데스크탑: 계산기 결과 카드 가로세로 비율이 웹앱과 다름
- **원인**: `CardGrid`가 화면 폭과 상관없이 `items.chunked(2)`로 항상 2열 고정.
- **해결**: 웹앱 `minmax(320px,1fr)` 자동 배치와 동일하게 `LazyVerticalGrid(GridCells.Adaptive(minSize=320.dp))`로 교체.

## Fixed (2026-08-07, 31차 세션)

### 데스크탑: 계산기에서 "계산하기"를 누르면 항상 오류가 뜸
- **원인**: `CalculatorScreen.kt`의 `CalcResultTab`이 `results.filterIsInstance<Pair<CalcTask, CalcEngine.CalcOutcome.Error>>()`로 에러만 골라내려 했는데, Kotlin 제네릭은 타입 인자를 런타임에 소거해서 이 필터는 실제로 `is Pair<*, *>`만 확인한다 — Success든 Error든 Pair이기만 하면 전부 통과했다. 이후 `outcome.message`에 접근하는 순간 실제 런타임 타입이 `Success`인 항목에서 `ClassCastException`이 터졌다.
- **해결**: `is CalcEngine.CalcOutcome.Error` 체크로 직접 분기하도록 수정([[DECISIONS.md]] "계산기 데스크탑 레이아웃을..." 참고). 안드로이드판은 원래부터 `when` 분기라 이 함정이 없었음.

### 저장됨 항목이 폴더 소속 표시는 되는데 그 폴더가 좌측 폴더 목록엔 안 뜸
- **원인**: 웹앱에서 만들어진 기존 Firebase 데이터가 항목별 `folderPath`만 갖고 폴더 트리 자체(`savedFolderTree`)는 비어있는 채로 동기화된 경우, 데스크탑/안드로이드 둘 다 폴더 목록을 `savedFolderTree`에서만 읽어와 그런 폴더는 트리에 없어 안 보였다.
- **해결**: 양 플랫폼 Repository에 `healCalcFolderPaths()` 추가 — `syncCalculatorFromFirebase()` 실행 시 저장 항목이 참조하는 폴더 경로(및 조상 경로)가 목록에 없으면 자동으로 채워 넣고 Firebase에도 다시 푸시(웹앱의 `rebuildFolderTreeFromItems`와 동일한 보정).

### 데스크탑: 계산기 결과가 별도 탭 뒤에 숨어 있어 계산 후 못 찾는 것처럼 보임
- **원인**: 28차 UI 개편에서 입력/결과/저장됨을 동등한 3개 탭으로 바꿔, 계산 후 자동으로 결과 탭으로 전환은 됐지만 구조 자체가 "결과를 보려면 탭을 눌러야" 하는 형태였다.
- **해결**: 웹앱과 동일한 좌(2):우(8) 사이드바 구조로 재작성 — 왼쪽은 업무입력/저장됨 서브탭, 오른쪽은 서브탭이 아니라 항상 보이는 결과 영역. 자세한 판단 근거는 [[DECISIONS.md]] 참고.

## Fixed (2026-08-07, 28차 세션)

### 타이머 탭에서 캘린더 일정을 선택해도 계속 첫 번째 항목으로 되돌아감
- **원인**: `StudyTimerScreen.kt`(데스크탑/안드로이드 둘 다)가 1초마다 `todayTasks`를 새로 조회해 재할당하는데, `taskName` 상태가 `remember(todayTasks)`로 그 목록을 키로 삼고 있어 목록이 갱신될 때마다 선택값이 첫 항목으로 재계산됐다(실기기 검증에서 발견).
- **해결**: `remember(todayTasks)` 키를 제거하고, `LaunchedEffect(todayTasks)`에서 현재 선택값이 새 목록에 더 이상 없거나 비어있을 때만 첫 항목으로 되돌리도록 가드 추가.

## Fixed (2026-08-07, 1단계 네이티브 재구현)

### 데스크탑/안드로이드: "타이머 정지"/"휴식으로 전환" 버튼을 눌러도 반응 안 함
- **원인**: 공부앱(웹뷰/브라우저)이 Firebase `pomodoro/remoteCommand`에 쓴 명령을 공부앱이 구독하고 있어야만 실제로 반영되는 비동기 왕복 구조 — 공부앱이 백그라운드로 밀려 JS가 스로틀되거나 탭이 닫혀있으면 명령이 아예 처리되지 않았고, 실패해도 관리앱 쪽엔 신호가 없었다.
- **해결**: 공부 타이머 상태를 관리앱 자체(안드로이드 `AppPreferences`+Room, 데스크탑 `data.json`)에 로컬로 저장하는 네이티브 타이머로 재구현. `StudyLockScreen`/`StudyLockActivity`의 정지/전환 버튼이 이제 로컬 `Repository.timerStop()`/`timerSwitchPhase()`를 동기 직접 호출 — 네트워크나 별도 프로세스를 거치지 않는다. [[DECISIONS.md]] "공부앱을 웹/웹뷰가 아닌 완전 네이티브로 재구현" 참고.

### 안드로이드: "타이머 정지"를 누르면 타이머는 꺼지는데 잠금 화면(오버레이)이 안 사라짐
- **원인**: `StudyLockActivity`가 한 번 뜨면 스스로 상태를 재확인하지 않아, 다른 앱으로 전환하거나 뒤로가기(홈 이동)를 눌러야만 없어졌다.
- **해결**: `StudyLockScreen`(Composable)의 기존 1초 tick 루프에 `repository.isStudyLockActive()` 폴링을 추가해 로컬 상태가 꺼지면 즉시 `finish()`.

### 데스크탑: `pomodoroUnlockEnabled`이 저장 시 누락되어 앱 재시작하면 항상 꺼진 상태로 되돌아감
- **원인**: `JsonStore.parse()`는 이 필드를 읽어오는데 `JsonStore.save()`에는 애초에 `gj.put("pomodoroUnlockEnabled", ...)`가 없었다 — 이번 1단계에서 같은 `save()` 함수를 편집하다 발견.
- **해결**: `save()`에 누락된 `put` 한 줄 추가(2026-08-07).

---

## Fixed

### 그룹 전체 on/off와 스케줄 on/off가 한 필드에 묶여있던 설계 결함
- **원인**: `scheduleEnabled` 필드가 "그룹 전체 on/off"와 "스케줄 관리 종류 on/off" 두 역할을 겸함.
- **해결**: `groupEnabled` 신설로 분리(2026-08-04, 9차 세션). [[groupEnabled/scheduleEnabled 분리 결정, DECISIONS.md 참고]]

### 배포 중 watchdog 예약 작업이 프로세스를 즉시 재실행시켜 robocopy 실패
- **원인**(2026-08-07, 22차 세션에 처음 발견): `PhoneLockDesktopWatchdog` 예약 작업이 앱이 안 떠 있으면 자동으로 재실행한다 — `Stop-Process`로 죽여도 watchdog이 곧바로 다시 띄워서, robocopy가 exe/dll을 "파일 사용 중"으로 반복 재시도하다 실패(`FAILED` 0이 아님).
- **해결**: 배포 전 `Disable-ScheduledTask -TaskName "PhoneLockDesktopWatchdog"` → 프로세스 종료 → robocopy(FAILED 0 확인) → 재실행 → `Enable-ScheduledTask`로 되돌리기. 아래 [[HANDOFF.md]] 배포 절차에도 반영.

### robocopy가 실행 중인 프로세스의 파일을 조용히 스킵
- **원인**: `Stop-Process` 후 바로 비동기 `robocopy /MIR`를 돌려 프로세스가 완전히 죽기 전에 복사가 시작됨 → `.exe`/jar가 "파일 사용 중"으로 실패했는데 재시작 성공만 보고 배포됐다고 오판.
- **해결**(2026-07-30): 프로세스 종료 후 대기 → robocopy 동기 실행 + FAILED 0 확인 → 배포된 jar 해시가 새 빌드와 일치하는지 확인 → 그 다음에만 재실행. 이후 세션들의 표준 배포 절차로 정착.

### PowerShell `-Encoding UTF8`이 BOM을 붙여 JSON 파싱 실패
- **원인**: `Set-Content -Encoding UTF8`이 파일 앞에 BOM 붙임 → `org.json.JSONObject` 파서가 못 걷어냄 → `JsonStore.load()`의 손상파일 방어 로직이 조용히 빈 상태로 시작(그룹이 전부 사라진 것처럼 보임).
- **해결**(2026-07-30): `[System.IO.File]::WriteAllText($path, $content, (New-Object System.Text.UTF8Encoding $false))`처럼 BOM 없는 인코딩 명시. 데이터 파일 직접 편집 후 그룹이 안 보이면 `%APPDATA%\PhoneLockDesktop\data.json.corrupted-*` 생성 여부부터 확인할 것.

### 실행 확인 오버레이 타이머 버벅거림
- **원인**: "남은 유예시간" 오버레이가 2초 주기 백그라운드 감시 루프의 서버 계산값으로 로컬 1초 타이머를 매번 덮어써서, 두 타이밍이 조금만 어긋나도 눈에 띄게 버벅거림.
- **해결**(2026-07-30, 4차 세션): 로컬/서버 값 차이가 1초 이하면 무시, 실제로 크게 달라진 경우(새 재확인 등)에만 갱신. 사용자가 실기기+데스크탑에서 해결 확인 완료.

### 브라우저 확장 오버레이 CORS 차단
- **원인**: content script(`overlay.js`)의 fetch가 확장 출처가 아니라 페이지 출처로 나가는데, 직전 세션의 CORS 강화가 이를 놓쳐 회귀버그 발생.
- **해결**(2026-07-29): `/overlay-status`만 `Access-Control-Allow-Origin: *`로 개방(상태변경 없는 단순 조회라 안전).

### 안드로이드 오버레이 불투명도 불안정 상승
- **원인**: 순수 시간 기반 재확인 쿨다운이 연속 사용 중에도 계속 만료되어 escalation이 과도하게 자주 걸림 + `escalationCache` 레이스 컨디션 + 데스크탑-모바일 `confirm_sync.json` 교차 오염(10초 캐시 주기로 튀어 넘어옴).
- **해결**(2026-07-30, 3차 세션): 오버레이 "표시값"에만 캡을 씌우는 `OverlayLevelRatchet`으로 최종 해결, 재확인/escalation 판정 로직은 원본 유지. [[표시값과 판정 로직 분리 원칙, DECISIONS.md 참고]]

### 안드로이드 APK 이중 위치 갱신 누락
- **원인**: 빌드 결과 APK가 `AndroidBuilds\phone-lock-app.apk`와 OneDrive 원본 두 곳에 따로 존재하는데, 한쪽만 갱신하고 넘어가 최대 3일치 변경사항이 반영 안 된 적 있었음.
- **해결**: 빌드마다 반드시 두 위치 모두 `Copy-Item`으로 갱신하는 절차 확립([[feedback_android_apk_dual_location]] 메모리화됨).
