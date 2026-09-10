# CHANGELOG

갓생살기종합세트(구 관리앱, phone-lock-android/desktop/browser-extension) 변경 이력. 날짜순 누적 기록, 삭제하지 않음.

---

## 2026-09-10 (102차 세션) — 포인트/보상(게이미피케이션) 시스템 1차 구현

### 포인트 적립 + "오늘의 보상" 언락 신규
- [[IDEAS.md]] "최우선 후보"(게이미피케이션/보상) 백로그 중 사용자가 확정한 1차 범위만 구현 — 포인트/코인 적립 + 보상 언락. 배지/레벨업/캐릭터 키우기/친구 초대 보상은 이번 범위 밖(IDEAS.md에 남겨둠).
- 적립 기준(사용자 확정): 공부 시간 10분당 1포인트, 루틴 완료 시 5포인트(고정), 캘린더 일정 완료 시 5포인트(고정), 그날 예정된 루틴을 전부 완료하면 스트릭 보너스 10포인트(날짜당 1회) — 하나라도 미완료로 되돌리면 그 보너스도 회수.
- 잔액은 별도 저장 없이 `PointsLedgerEntry` 원장 전체를 매번 합산해서 계산(`RoutineEngine.currentStreak`과 같은 "매번 다시 훑는 순수 파생값" 패턴). reason("STUDY"/"ROUTINE"/"CALENDAR"/"STREAK"/"REDEEM")+refId+dateKey 조합으로 중복 적립/롤백을 판정 — 루틴 체크 해제·캘린더 완료 취소 시 그때 적립됐던 항목을 그대로 지운다.
- 신규 Room 엔티티 `PointsLedgerEntry`/`Reward`(안드로이드), 대칭 데이터클래스(데스크탑). Room v38→v39(`MIGRATION_38_39`, `points_ledger`/`reward` 테이블 신규 생성만 — 기존 테이블 스키마 변경 없음).
- 훅 지점: `addStudyLogEntry`(공부 세션 종료 시), `toggleRoutineLog`(루틴 체크 토글 시), `setCalendarTaskStatus`(캘린더 완료 전환 시) — 3곳 모두 기존 함수 끝에 호출 한 줄만 추가하는 방식으로 삽입, 기존 로직은 안 건드림. 데스크탑 `CalendarTask`는 안드로이드와 달리 안정적인 id가 없어(dateKey+배열 순서로만 식별) refId를 `calendar:{dateKey}:{ordinal}`로 근사(기존 미완료 이월/자동생성 로직도 같은 ordinal 주소 방식을 쓰고 있어 위험 수준이 기존 코드와 동일).
- 신규 보상: 사용자가 이름+필요 포인트만 직접 등록/삭제(고정 프리셋 없음). 교환(언락) 시 잔액이 모자라면 아무 일도 안 하고 실패만 알림.
- UI: "루틴" 섹션에 3번째 서브탭 "🎁 포인트" 신규 추가(양 플랫폼, 기존 오늘/연속기록 옆) — 최상위 탭을 새로 만들지 않고 기존 3-top-tab(관리/공부/설정 + 루틴/소셜) 구조에 얹음. 잔액 카드(RoutineStatsTab과 같은 강조 카드 스타일)+보상 목록(언락/삭제 버튼)+보상 추가 다이얼로그.
- Firebase `users/{user}/points`에 캘린더/루틴과 동일한 "전체 문서 단위 LWW" 동기화(`PomodoroSyncClient.writePoints`/`readPoints`, 데스크탑 대칭) — 루틴 문서와 별개 노드로 분리(합쳐서 재사용하면 루틴 동기화의 delete+insert 트랜잭션에 포인트 원장까지 얽혀 들어가는 걸 피하기 위함).
- 양 플랫폼 컴파일 확인 후 릴리스 빌드(`assembleRelease`/`packageMsi createDistributable`) 완료, 안드로이드 APK 3위치+데스크탑 호스트/`vm-build-output` 양쪽 배포, GitHub 릴리스 게시(안드로이드 `android-1789030574`, 데스크탑 `desktop-1789030702`), `sync-public-repo.ps1`로 공개 저장소 push까지 완료. 실사용 검증은 안 됨.

---

## 2026-09-10 (101차 세션) — 모임(소셜 그룹) 이름/설명 수정 기능

### 모임 설명(description) 필드 신규 + 기존 이름 수정 기능에 통합
- [[IDEAS.md]] "괜찮음 티어" 백로그 항목을 사용자 승인 하에 구현. 착수 전 기존 코드를 검색해보니 "모임 이름 수정" 자체는 77차에 이미 구현돼 있었다(`updateGroupName`, `SocialGroupMembersScreen.kt`의 "✏️ 모임 이름/코드 수정" 다이얼로그, 모임장/관리자 권한 게이트) — 처음에 별도 함수(`updateGroupInfo`, 모임장 전용)로 새로 만들었다가, 이 기존 기능을 발견하고 되돌린 뒤 기존 `updateGroupName`을 확장하는 쪽으로 재작업했다(CLAUDE.md "동일 기능이 이미 있는지 먼저 검색" 원칙).
- `GroupInfo` 데이터클래스(양 플랫폼 `SocialGroupSyncClient.kt`)에 `description: String = ""` 추가, `readGroupInfo`가 `groups/{id}/info/description` 파싱.
- `updateGroupName(databaseUrl, apiKey, groupId, newName, description)` — name/description 하위 경로를 각각 개별 PUT(REST). info 문서 전체를 덮어쓰지 않아 그 사이 다른 클라이언트가 바꾼 `inviteCode`/`createdAt` 등을 보존한다 — 기존 `transferOwnership`(ownerUid만 PUT)과 동일한 패턴.
- 기존 "✏️ 모임 이름/코드 수정" `AlertDialog`(양 플랫폼 `SocialGroupMembersScreen.kt`)에 이름 입력 필드 아래 설명(선택, 2~4줄 `OutlinedTextField`) 필드 추가.
- 설명이 있으면 모임 상세 화면 이름 아래에 작은 회색 텍스트로 표시(안드로이드는 탭바 위, 데스크탑은 헤드라인 바로 아래) — 빈 설명이면 아무것도 안 그림.
- `PhoneLockRepository.Social.kt`의 `updateSocialGroupName`도 `description` 파라미터 추가.
- 권한은 기존 UI 게이트(모임장 또는 관리자, `isAdmin`)를 그대로 유지 — 최초 계획은 "모임장 전용"이었지만, 이미 배포돼 있던 이름 수정 기능이 관리자까지 허용하고 있어 그 기존 관례를 따랐다(새 기능이 기존 기능을 확장하면서 권한 모델만 더 엄격하게 좁히면 사용자가 "왜 이름은 관리자가 되는데 설명은 안 되지" 하는 혼란을 만들 수 있다고 판단).
- 양 플랫폼 컴파일(`compileDebugKotlin`/`compileKotlin`) 확인 후 릴리스 빌드(`assembleRelease`/`packageMsi createDistributable`)까지 완료, 안드로이드 APK 3위치(AndroidBuilds/OneDrive 원본/vm-build-output) + 데스크탑 호스트(`PhoneLockDesktopApp`)/`vm-build-output` 양쪽 배포, GitHub 릴리스 게시(안드로이드 `android-1789028782`, 데스크탑 `desktop-1789028710`), `sync-public-repo.ps1`로 공개 저장소 push까지 완료. 실사용 검증은 안 됨.

---

## 2026-09-10 (100차 세션) — 캘린더 기본 정렬 버그 수정 + 공부 타이머 진행률 응원 문구 신규 + 신규 기능 브레인스토밍 백로그 정리 + 세션 마무리 정책 변경

### 캘린더 기본 정렬 버그 수정
- 사용자가 "1회독부터 오름차순 정렬"을 여러 번 요청했는데도 적용 안 되고 있던 버그 — `sortCalendarDay`/`resortCalendarDay`(양 플랫폼)의 정렬 키가 `passTotal - 1 - passIndex`(사실상 회독 역순, 3회독→2회독→1회독 순으로 표시됨)로 잘못 돼있었다. `passIndex` 오름차순으로 수정.

### 공부 타이머 "목표 대비 진행률" 응원 문구 신규
- 타이머 시작 전 선택 입력으로 목표 설정 가능 — 일반(스톱워치) 모드는 "목표 시간(분)", 뽀모도로 모드는 "목표 사이클 수"(`Repository.studyGoalMinutes`/`pomodoroTargetCycles`, 데스크탑 `Models.kt`+`JsonStore.kt`, 안드로이드 `AppPreferences.kt` 신규 필드, 둘 다 0=미설정으로 기존 동작 보존).
- 실행 화면에 진행률 0~10%/10~40%/40~60%/60~85%/85~100%/100%+ 6단계에 따라 다른 톤의 응원 문구 표시 — `shared/StudyProgressQuotes.kt` 신규(양 플랫폼 공용, `MotivationalQuotes.kt`/`RoutineQuotes.kt`와 달리 항상 긍정 톤만 사용). `StudyTimerScreen.kt`(양 플랫폼)에 진행률 계산(뽀모도로는 `cycleCount`+현재 페이즈 진행률 합산, 일반은 `phaseStartedAt` 경과시간 기준)과 `remember(tier)`로 구간 넘기 전까지 문구가 안 바뀌게 하는 표시 로직 추가.

### 신규 기능 브레인스토밍 100개 + 우선순위 정리
- 사용자 요청으로 카테고리별 신규 기능 아이디어 100개 제시 후, 사용자가 반응한 항목을 [[IDEAS.md]] "신규 기능 브레인스토밍 백로그" 섹션에 최우선(게이미피케이션/보상)/괜찮음(18개)/고려해볼만함(7개) 3단계로 정리.

### 세션 마무리 정책 변경 — 문서/빌드/배포/GitHub 게시를 요청 없이 판단해서 진행
- 기존엔 "문서 갱신/빌드/배포/GitHub 게시는 내가 하라고 할 때만" 원칙이었으나, 사용자가 "이제부터 니가 알아서 적당한 때가 된거 같다싶으면 알아서 해"로 명시적으로 뒤집음 — 앞으로는 작업이 완결된 시점을 스스로 판단해 이 4가지를 자동으로 진행한다(단, 잦은/사소한 변경마다 매번 하지 않고 의미 있는 완결 단위마다 묶어서 하는 절제는 유지).

양 플랫폼 컴파일+빌드+호스트 배포+GitHub 릴리스 게시까지 완료(안드로이드 versionCode `1789026774`, 데스크탑 BuildInfo `1789026709`).

---

## 2026-09-10 (99차 세션) — 소셜 채팅 버그 해결 + 사진/GIF 첨부 구현 후 삭제 + 채팅 알림 신규 + 새로고침 버그 수정 + "회독"→"복습" 용어 변경

### 소셜 채팅 "전송은 되는데 목록에 안 뜨는" 버그 — 근본 원인 확정·해결(96차부터 이월)
- **원인**: Firebase RTDB가 `orderBy=%22sentAtMillis%22` 조회에 `.indexOn` 색인이 없다는 이유로 읽기 요청 자체를 HTTP 400으로 거부하고 있었다. 메시지 전송(쓰기)은 색인과 무관해 항상 성공했고, 읽기 실패는 98차까지 조용히 삼켜지고(빈 목록으로 덮어씀) 있어 "쓰는 건 되는데 안 보인다"는 증상만 남았다.
- **해결**: `phone-lock-android/firebase-database.rules.json`의 `groupChats/{groupId}/messages`와 `dmChats/{chatId}/messages`에 `.indexOn: ["sentAtMillis"]` 추가. 사용자가 Firebase 콘솔에 재게시 후 실사용으로 확인 완료.
- **후속 계측**: `ChatSyncClient.kt`(양 플랫폼)의 `readGroupMessages`/`readDmMessages`가 `List<ChatMessage>` 대신 `Result<List<ChatMessage>>`를 반환하도록 변경 — 읽기 실패 사유도 "목록 불러오기 실패: ..."로 화면에 표시(기존 전송 실패 표시와 대칭). `ChatThreadScreen.kt`(양 플랫폼)에 `loadError` 상태 추가.

### 채팅 사진/GIF 첨부 — 구현 후 사용자 요청으로 전면 삭제
- 최초 구현: `ChatSyncClient.uploadChatMedia()`(Firebase Storage REST 업로드, `CloudBackupClient`와 동일 패턴), `ChatMessage.mediaUrl` 필드, `sendGroupMessage`/`sendDmMessage`에 `mediaUrl` 파라미터, 안드로이드 `ActivityResultContracts.PickVisualMedia`+Coil(`coil-compose`/`coil-gif`) 이미지 표시, 데스크탑 `java.awt.FileDialog`+Skia 디코드 표시, `firebase-storage.rules`에 `chatMedia/` 경로 규칙 추가.
- 사용자가 "사진, gif 보내기 기능 삭제해" 요청 → 위 전부 원복. Coil 의존성(`app/build.gradle.kts`)/`PhoneLockApplication`의 `ImageLoaderFactory` 구현도 제거. 현재 코드에 흔적 없음.

### 채팅 알림(사용자 요청 — 진동 없이)
- 안드로이드: `WalkieTalkieService.kt`에 `pollChatMessages()` 신규 — 기존 7초 폴링 루프에 편승해 내가 속한 모임 대화방(`readMySocialGroupIds()`)+DM(`readMyDmChats()`)을 순회, `ChatSyncClient.peekLatestGroupMessage`/`peekLatestDmMessage`(신규, `limitToLast=1` 경량 조회)로 새 메시지 유무만 확인. 새 알림 채널 `chat_message`(`enableVibration(false)` — 이 앱에서 처음부터 진동을 끈 유일한 채널, 다른 채널들은 전부 `enableVibration(true)`가 기본이라 명시적으로 켜야 했던 것과 반대). `AppPreferences.chatLastSeenByChat`(신규, Map<String,Long>)으로 마지막 확인 시각 저장.
- `ui/ChatThreadScreen.kt`(양 플랫폼)에 `ActiveChatTracker`(전역 `@Volatile var openChatId`) 신규 — 화면이 열려있는 동안 자신의 chatId를 채워두고, 백그라운드 폴러가 지금 보고 있는 방이면 알림을 건너뛴다.
- 데스크탑: `routine/ChatNotifier.kt`(신규, `SocialGroupNotifier`와 동일한 tick() 구조) — `Main.kt`의 기존 7초 루프에 호출 추가. 트레이 풍선 알림이라 진동 개념 자체가 없어 "진동 없이" 요구사항이 자동 충족됨. `Repository.chatLastSeenFor`/`setChatLastSeen`(신규, `Models.kt`/`JsonStore.kt`에 `chatLastSeenByChat` 필드+저장 로직 추가) — `nudgeLastSeenByGroup`과 동일 패턴.

### 새로고침 버그 수정 + 범위 확장
- **스피너 고정 버그**(사용자 제보, 캘린더에서 재현): `ui/components/PullToRefreshBox.kt`(안드로이드)의 `LaunchedEffect(Unit) { onRefresh(); state.endRefresh() }`가 `onRefresh()` 실패 시 `endRefresh()`를 건너뛰어 `state.isRefreshing`이 영원히 true로 남는 버그였다 — try/finally로 감싸 실패해도 항상 인디케이터가 닫히도록 수정. 새로고침이 달린 모든 화면(루틴/캘린더/계산기/차단규칙목록/소셜/모임멤버 + 이번에 추가된 3개)에 공통 적용.
- **누락 화면 3개 추가**(사용자 요청): `StudyStatsScreen.kt`/`TimetableScreen.kt`/`StudyTimerScreen.kt`(양 플랫폼) — 안드로이드는 `PullToRefreshBox`로 감싸기 위해 `StudyStatsScreen`/`TimetableScreen`의 early `return`이 있는 본문을 `StudyStatsContent`/`TimetableContent`(신규 private 컴포저블)로 추출(일반 함수 `return`은 유효, 람다 안 non-local return은 불가하므로). `StudyTimerScreen`은 early return이 없어 구조 변경 없이 루트 레이아웃만 감쌈. 데스크탑은 기존 6개 화면과 동일하게 헤더에 "🔄" `IconButton` 추가.
- **미해결로 남긴 것**: 안드로이드에서 당겨서 새로고침이 스크롤과 겹쳐 잘 안 되는 문제는 원인을 못 좁혔다 — 현재 쓰는 Material3 `rememberPullToRefreshState`/`PullToRefreshContainer`가 실험적(`@ExperimentalMaterial3Api`) 버전이라 제스처 인식이 상대적으로 불안정한 것으로 추정, 근본 해결은 더 안정적인 최신 pull-to-refresh API로 이전(Compose BOM/Material3 버전 업그레이드 필요, 다른 화면 영향 범위가 커서 이번엔 보류).

### "회독"→"복습" 용어 변경(사용자 요청)
- 숫자가 붙는 경우 "N회독"→"N회 복습"(예: `passLabel()`, 색상 선택 라벨, 간격 라벨), 숫자 없이 기능을 가리키는 경우(설정 섹션 제목, 토글 이름 등) "복습"으로 통일.
- 대상: `CalendarScreen.kt`(`passLabel`/`multiPassEnabled` 토글/색상 선택 라벨, 데스크탑은 미사용 `COLOR_LABEL` 맵도 포함), `CalculatorScreen.kt`(복습 설정 섹션 전체), `SettingsScreen.kt`(캘린더 복습 기본값 섹션), `SocialGroupMemberDetailScreen.kt`, `StudyTimerScreen.kt`, `StudyStatsScreen.kt` — 양 플랫폼 전부.
- 내부 변수명(`passIndex`/`passTotal`/`passCount`/`multiPassEnabled` 등)과 코드 주석, 이 문서 체계(BUGS/DECISIONS/HANDOFF 등)의 기존 서술은 그대로 유지 — 화면에 실제로 보이는 문자열만 교체.

### Firebase 요금제 안내(코드 변경 없음)
- 사용자 질문에 답변: Spark(무료) 요금제는 저장 1GB/월 다운로드 10GB 한도. 이 앱은 저장 용량보다 무전기 서비스의 상시 7초 폴링(사용자 수 × 모임/DM 개수에 비례)이 트래픽 병목이라는 점을 설명 — 대략적 추정치로 무료 요금제는 10~20명 선까지 여유로울 것으로 안내(실측 아님, Firebase 콘솔 사용량 탭에서 재확인 권장).

### 빌드/배포
- 양 플랫폼 컴파일 확인(단계별로 여러 차례) 후 릴리스 빌드 — 안드로이드 versionCode `1789016348`, 데스크탑 BuildInfo `1789016291`. 호스트 배포(해시 검증 포함) 완료. **GitHub 릴리스는 이번 세션에 게시하지 않음.**

---

## 2026-09-10 (98차 세션) — 루틴 모드 신규 + 온라인/오프라인 모드 신규 + 당겨서 새로고침 신규 + 버그 5건

### 루틴 모드(96차 설계 확정, 이번에 구현) — Room DB v37→v38
- `Entities.kt`(안드로이드)/`Models.kt`(데스크탑)에 `RoutineMode(id, name, sortOrder)` 신규, `Routine.modeId: Long?` 추가.
- `AppDatabase.kt`: `version = 38`, `MIGRATION_37_38`(`CREATE TABLE routine_mode`+기본 모드 삽입+`ALTER TABLE routine ADD COLUMN modeId`+기존 루틴 전부 기본 모드로 UPDATE) 추가. 명시적 마이그레이션이라 기존 데이터 보존, destructive fallback 안 탐.
- `Daos.kt`: `RoutineModeDao` 신규, `RoutineDao`에 `observeByMode`/`getByMode`/`reassignMode` 추가.
- `PhoneLockRepository.Routine.kt`/`Repository.Routine.kt`: `ensureDefaultRoutineMode()`(신규 설치 안전장치), 모드 CRUD(`addRoutineMode`/`renameRoutineMode`/`deleteRoutineMode`(마지막 모드 삭제 방지+루틴 자동 재배정)/`swapRoutineModeOrder`), `getRoutines(modeId)`(모드 필터), `getAllRoutines()`(모드 무관 전체 — 알림/리마인더용).
- Firebase 동기화 스키마 확장: `routinesToJson`/`routinesFromJson`에 `modes` 배열+각 루틴의 `modeIndex`(기존 로그의 `routineIndex`와 동일한 "배열 인덱스로 참조" 패턴) 추가. `PomodoroSyncClient.readRoutines`/`writeRoutines` 시그니처에 `modesJson` 추가.
- 백업 내보내기/가져오기(`exportRoutinesBackupJson`/`importRoutinesBackupJson`)에 모드 포함.
- `RoutineScreen.kt`(양 플랫폼): 기존 "오늘"/"연속 기록" 서브탭 위에 모드 칩 Row 신규(선택 표시+"+"+"⚙"), 모드 추가/관리(이름변경/삭제/▲▼순서) 다이얼로그 신규. `RoutineEditScreen.kt`: 모드가 2개 이상이면 편집 다이얼로그에 모드 드롭다운 추가.
- 안드로이드 위젯(`RoutineWidgetFactory.kt`): `AppPreferences.activeRoutineModeId`(신규)로 마지막 본 모드를 기억해 위젯도 그 모드의 루틴만 표시.
- 안드로이드 `RoutineReminderReceiver.kt`/`RoutineAlarmScheduler.kt`, 데스크탑 `RoutineNotifier.kt`/`WeeklySummaryNotifier.kt`/`SocialGroupSyncClient.kt`: 모드 무관 `getAllRoutines()`로 전환(알림/요약은 숨겨진 모드의 루틴도 대상).

### 온라인/오프라인 모드(사용자 요청, 신규)
- 안드로이드 `NetworkMonitor.kt`(신규, `ConnectivityManager.NetworkCallback` 실시간 감시) — `PhoneLockApplication.onCreate()`에서 등록, `AndroidManifest.xml`에 `ACCESS_NETWORK_STATE` 권한 추가.
- 데스크탑 `NetworkMonitor.kt`(신규) — 호출 시점에 8.8.8.8:53 TCP 연결을 짧은 타임아웃(1초)으로 시도해 판정.
- `AppPreferences.offlineModeOverride`(안드로이드 SharedPreferences)/`AppData.offlineModeOverride`(데스크탑, JsonStore 영속)로 수동 강제 오프라인 토글 신규 — 설정 화면에 "온라인 / 오프라인 모드" 섹션 신규.
- `PhoneLockRepository.isEffectivelyOffline()`/`Repository.isEffectivelyOffline()`(신규) — 수동 토글 OR 실제 연결 끊김 OR 게스트(익명) 계정 중 하나라도 해당하면 true. 루틴/캘린더/계산기/그룹설정 4개 화면의 진입 시 동기화 호출(`sync*FromFirebase`)을 이 값으로 게이트.
- 게스트(익명) 계정은 온/오프라인 무관하게 소셜 탭을 완전히 숨김(`MainActivity.kt`/`MainScreen.kt`의 탭 가시성 조건에 `!isAnonymous` 추가) — 기존엔 서버 프로필에 `permissions.social` 필드가 없으면(하위호환 기본값) 게스트도 소셜 탭이 보이던 문제.

### 당겨서 새로고침(사용자 요청, 신규)
- 안드로이드 `ui/components/PullToRefreshBox.kt`(신규, Material3 1.2.1 `rememberPullToRefreshState`/`PullToRefreshContainer` 기반 공용 래퍼) — 루틴/캘린더/계산기/차단규칙목록/소셜모임/모임멤버 6개 화면에 적용(스와이프 제스처).
- 데스크탑: 위 6개 화면 각각의 헤더에 "🔄" `IconButton` 추가(제스처 대신 버튼).

### 버그 수정
- **다회독 계산기 연동 끊김**: `PhoneLockRepository.Calendar.kt`/`Repository.Calendar.kt`의 `applyCalendarAutoSchedule()`이 다음 회독 `CalendarTask`를 만들 때 `linkedCalc`/`progressStep`을 안 이어받아서, 완료할 때마다 계산기 연동이 끊기던 버그(양 플랫폼).
- **계산기 "저장됨"→"입력" 탭 미반영**: `CalculatorScreen.kt`(양 플랫폼) "저장됨" 탭의 불러오기가 자기 탭 목록만 새로고침하고 "입력" 탭의 draft 목록(`tasks`)은 안 갱신 — 다른 탭 갔다 오거나 앱을 재시작해야 반영되던 버그. 사용자 제보로 발견 후 전체 화면 감사 진행, 아래 2건 추가 발견.
- **소셜 공유 설정 미반영**: `SocialGroupMembersScreen.kt`(양 플랫폼) 공유 설정 다이얼로그가 통계만 다시 올리고 멤버 목록(`rows`/`stats`)은 안 새로고침하던 버그.
- **차단 규칙 편집이 목록 변경사항을 덮어씀**: `GroupEditScreen.kt`(양 플랫폼)가 화면 진입 시점 스냅샷(`originalGroup`)을 저장 시 그대로 써서, 편집하는 동안 목록에서 켜짐/스누즈/차단시도 등이 바뀌어도 저장하면 그 변경이 통째로 되돌아가던 버그 — 저장 직전 `repository.getGroup(id)`로 최신 상태를 다시 읽어 그 값을 쓰도록 수정. 조사 중 `blockAttemptDate`/`blockAttemptCount`(조롱 문구 강도)가 이 폼에 필드 자체가 없어 저장할 때마다 0으로 리셋되던 별개 버그도 발견해 함께 수정.
- **데스크탑 `generateBuildInfo` 캐싱**: `build.gradle.kts`의 이 태스크가 `inputs` 선언 없이 `outputs.dir(...)`만 있어서, 같은 `build/` 디렉터리에서 재빌드해도 Gradle이 UP-TO-DATE로 캐싱 — 며칠 전(97차) 타임스탬프가 그대로 남아있던 걸 이번에 발견. `outputs.upToDateWhen { false }` 추가로 항상 재실행하도록 수정.
- 소셜 채팅 전송 안 되는 버그(96차 이월)는 **원인 미확정** — `ChatThreadScreen.kt`(양 플랫폼)이 `sendMessage`의 `Result<Unit>` 실패를 그동안 버리고 있던 걸 발견해 화면에 실패 사유를 표시하도록 계측만 추가(`sendGroupChatMessage`/`sendDmChatMessage` 시그니처는 이미 `Result<Unit>`였음).

### 빌드/배포
- 양 플랫폼 컴파일 확인(여러 차례) 후 릴리스 빌드(`assembleRelease`/`createDistributable`+`packageReleaseMsi`) — 안드로이드 versionCode `1789000237`, 데스크탑 BuildInfo `1789000490`(캐싱 버그 수정 후 재빌드로 갱신).
- 호스트 3곳 APK 해시 일치 배포 + 데스크탑 프로세스 교체·재실행 완료.
- `sync-public-repo.ps1`로 공개 저장소(`study-planner`) 소스 동기화 + `gh release create`로 `android-1789000237`/`desktop-1789000490` 태그 게시 완료.

---

## 2026-09-09 (97차 세션 마지막) — 도움말 기능 전체 삭제

세션 내내(워크스루→페이징→실제 화면 재현 이미지+번호 배지, 아래 항목들 참고) 여러 차례 개편했던 도움말(GuideScreen) 기능을 사용자 요청("그냥 가이드 싹다 지워")으로 전부 삭제.

- 삭제: `shared/src/main/kotlin/com/phonelock/shared/GuideContent.kt`, `phone-lock-android/.../ui/GuideScreen.kt`, `phone-lock-desktop/.../ui/GuideScreen.kt`, 안드로이드 `res/drawable/guide_*.png`(27개), 데스크탑 `src/main/resources/guide/`(27개).
- 원복: `MainActivity.kt`/`Main.kt`/`MainScreen.kt`의 워크스루 자동 표시 상태·탭별 도움말 오버레이 상태, `ManageSection`/`StudySection`/`RoutineScreen`/`SocialGroupScreen`(양 플랫폼)의 ❓ 버튼과 관련 파라미터, `SettingsScreen`(양 플랫폼)의 "도움말"/"설정 탭이란" 카드 4~5곳, `AppPreferences.lastSeenGuideVersion`(안드로이드)·`AppData.lastSeenGuideVersion`/`Repository.lastSeenGuideVersion`(데스크탑) 저장 필드.
- 유지: 최초 실행 시 권한 설명 다이얼로그(`OnboardingDialog`)는 도움말과 별개 기능이라 그대로 둠.
- 빌드/배포: 안드로이드(`compileDebugKotlin`+`assembleRelease`, versionCode `1788946190`)·데스크탑(`compileKotlin`+`packageMsi`+`createDistributable`, BuildInfo `1788946046`) 둘 다 컴파일 확인 후 릴리스 빌드, 호스트 3곳/2곳 해시 일치 배포 완료. 데스크탑 앱 실행 확인.

---

## 2026-09-09 (97차 세션) — 도움말(GuideScreen) 시스템 전면 개편

89차에 만든 도움말이 93~96차 디자인/기능 변경(관리 탭 자물쇠 개편, 소셜 메신저 확장 등)을 계속 따라가지 못해 실제 화면과 어긋나던 문제와, 태블릿에서 워크스루 페이지 하나하나가 화면보다 커서 매번 스크롤해야 보이던 문제를 해결하기 위해 전면 개편. 사용자가 세션 중 "업데이트한 사람한텐 도움말 띄우지 말라"는 요청을 취소해, 91차의 버전 비교 기반 재표시 조건은 그대로 유지.

### shared — GuideContent.kt 구조 개편
- 기존 `GuidePage`/`pages`(관리/일시정지/공부/루틴/모임/설정 7페이지)를 "intro"+"guide_hint" 2페이지로 축소 — 최초 실행 워크스루는 이제 "이 앱이 뭐고 도움말은 어디서 다시 찾는지"만 짧게 안내.
- 신규 `GuideSection`(소제목+bullets)/`TabGuide`(id/emoji/title/intro/sections)/`TabGuideContent`(manage/study/routine/social/settings) 추가 — 탭 5개 각각 여러 섹션으로 나눠 작은 기능까지 상세히 설명(예: 관리 탭은 "차단 규칙 만들기/제한 방식/해제 절차/오버레이/일시정지/기간 지정/동기화/편집 면제 시간대" 8개 섹션).

### 안드로이드 — GuideScreen.kt/MainActivity.kt/RoutineScreen.kt/SocialGroupScreen.kt/SettingsScreen.kt
- `GuideScreen`(워크스루)은 `widthIn(max = 480.dp)`로 폭 제한 + 페이지 수 축소로 태블릿 오버플로우 해결. 신규 `TabGuideDialog(guide, onDismiss)` — 탭별 상세 도움말을 섹션별로 스크롤 나열, 기존 모크업 함수(`MockupManage` 등)는 그대로 재사용.
- `MainActivity.kt`: `showGuide` 상태를 `setContent` 최상위(로그인 화면 위에도 겹쳐 뜨던 지점)에서 `PhoneLockApp`(= `AccountGate` content 안)으로 이동 — 로그인 완료 후에만 뜨도록 수정. `openTabGuide` 상태 신규, `ManageSection`/`StudySection`/`RoutineScreen`/`SocialGroupScreen`/`SettingsScreen`에 `onOpenGuide`/`onOpenTabGuide` 콜백 스레딩.
- `ManageSection`/`StudySection`: 기존 서브탭 `TabRow` 옆에 `GuideHelpButton`(❓) 신규 추가(Row로 감싸 weight 분배).
- `RoutineScreen.kt`: 헤더의 "+ 추가" 버튼 옆에 ❓ `IconButton` 신규.
- `SocialGroupScreen.kt`: `Scaffold` `TopAppBar`의 `actions` 슬롯에 ❓ 버튼 신규.
- `SettingsScreen.kt`: "앱 전체" 서브탭의 "도움말" 카드를 "설정 탭이란"(설명+전용 도움말 버튼)으로 교체, 루틴/공부/관리/모임 4개 서브탭 각각 첫 카드로 "도움말"(그 탭 다시 보기 버튼) 신규 추가. `onShowGuide: () -> Unit` 파라미터를 `onOpenTabGuide: (TabGuide) -> Unit`으로 교체.

### 데스크탑 — GuideScreen.kt/Main.kt/MainScreen.kt/RoutineScreen.kt/SocialGroupScreen.kt/SettingsScreen.kt
- 안드로이드와 동일한 구조 변경(워크스루 축소+`TabGuideDialog` 신규, 폭 480~560dp 고정).
- `Main.kt`: `showGuide` 상태와 `GuideScreen` 렌더링을 `AccountGate` 호출 **이전**(로그인 화면보다도 먼저 뜨던 버그)에서 제거하고 `MainScreen.kt`(= `AccountGate` content 안)으로 이동.
- `MainScreen.kt`: `showGuide`/`openTabGuide` 상태 신규 보유. MANAGE/STUDY 섹션의 `TabRow` 옆에 ❓ `IconButton`, `RoutineScreen`/`SocialGroupScreen`/`SettingsScreen` 호출에 각각 `onOpenGuide`/`onOpenTabGuide` 콜백 전달.
- `RoutineScreen.kt`/`SocialGroupScreen.kt`/`SettingsScreen.kt`: 안드로이드판과 대칭되는 위치에 ❓/도움말 카드 추가, `SettingsScreen`도 `onShowGuide` → `onOpenTabGuide`로 교체.

### 빌드/배포 (1차, 페이징 개편 전)
- 안드로이드: `C:\Users\sunae\AndroidBuilds\phone-lock-android`에서 `compileDebugKotlin` 확인 후 `assembleRelease` 빌드(versionCode `1788941101`), 호스트 3곳(`AndroidBuilds\phone-lock-app-release.apk`/OneDrive 원본/`vm-build-output\android`) 해시 일치 배포.
- 데스크탑: `C:\build\phone-lock-desktop`에서 `compileKotlin` 확인 후 `packageMsi createDistributable` 빌드(BuildInfo `1788940862`), 호스트 2곳(`PhoneLockDesktopApp`/`vm-build-output\PhoneLockDesktop`) 배포 후 실행 확인.

## 2026-09-09 (97차 세션 2차 개편) — 탭별 도움말을 "한 화면 스크롤"에서 "섹션당 한 페이지"로 재작업

1차 개편 직후 사용자가 실제로 도움말을 확인해보고 "정보는 많아서 좋은데 글만 있다 — 파트를 나눠서 이미지를 활용해 페이지를 여러 개 쓰면 더 쉽게 설명할 수 있을 것"이라고 피드백. `TabGuideDialog`를 한 화면에 섹션을 전부 세로로 나열하던 방식에서, 최초 실행 워크스루와 같은 페이징 방식(섹션 하나 = 페이지 하나)으로 재작업.

### shared — GuideContent.kt
- `GuideSection`에 `icon: String` 필드 신규 추가(위치 인자 순서: `icon, heading, bullets`) — 관리 8개/공부 5개/루틴 4개/소셜 7개/설정 8개, 총 32개 섹션 전부에 그 섹션을 대표하는 이모지 아이콘 지정(예: 관리 "잠금 해제 절차"→🔐, 공부 "타이머"→⏱️, 소셜 "대화 채널"→💬).

### 안드로이드/데스크탑 — GuideScreen.kt (TabGuideDialog 재작업)
- 페이지 0 = 탭 소개(기존 `intro` 텍스트+탭 전체 모크업)+"다음 페이지부터 기능을 하나씩 자세히 설명해요" 안내, 페이지 1~N = `sections`를 하나씩(`TabGuideSectionPage`) — 상단에 `SectionIconBadge`(72dp 원형 배지, 섹션 `icon`을 크게 표시)로 실제 스크린샷 없이도 페이지마다 시각적 구심점을 준다.
- 안드로이드: `HorizontalPager`(워크스루와 동일 패턴)로 전환, 페이지 인디케이터 점+"이전"(1페이지가 아닐 때만)/"다음"·"확인" 버튼 신규.
- 데스크탑: 워크스루와 동일한 `pageIndex`+"이전"/"다음" 버튼 패턴 재사용, `key(pageIndex)`로 페이지 전환 시 스크롤 위치 초기화.

### 빌드/배포 (2차)
- 안드로이드: `assembleRelease` 재빌드(versionCode `1788941936`), 호스트 3곳 해시 일치 배포.
- 데스크탑: `packageMsi createDistributable` 재빌드(BuildInfo `1788941683`), 호스트 2곳 배포 후 앱 재실행 확인.
- GitHub 릴리스는 미게시(요청 없었음). **실사용 검증 아직 안 됨** — 페이지 넘김(스와이프/버튼), 인디케이터, ❓ 버튼 각 위치 동작, 태블릿에서 다이얼로그 크기, 로그인 전 워크스루 미표시, 업데이트 후 재표시.

---

## 2026-09-09 (97차 세션 3차 개편) — 이모지 배지 대신 "실제 화면 재현 이미지 + 번호 배지"로 전면 교체

2차 개편(페이징) 직후 사용자가 실제 앱에서 확인하고 재차 지적: 원하는 건 이모지로 기능을 설명하는 게 아니라, **실제 앱 화면 그대로**를 이미지로 보여주면서 화면의 각 부분(버튼/영역)을 화살표·박스·번호 같은 시각적 표시로 직접 가리켜 설명하는 방식이었다("특정 버튼을 가리킨 뒤 옆에 설명을 붙이는" 형태). 처음엔 데스크탑 실행 중인 앱을 실제로 화면 캡처해서 시도(PowerShell `PrintWindow`/전체화면 캡처 조합, GDI 방식은 Skia 렌더링을 못 잡아 실패 → 전체화면 캡처+창 포커스 방식으로 성공)했으나, 사용자가 "이 방식은 디자인적으로 별로다, 모방해서 새로 만들어라"라고 재차 정정 — 실제 스크린샷을 그대로 쓰는 대신, 실제 화면 레이아웃을 충실히 재현한 고정 이미지를 만들기로 방향을 바꿨다.

### 이미지 제작 파이프라인 (신규 확립)
- HTML/CSS로 각 화면을 앱과 동일한 다크+블루 팔레트·컴포넌트 스타일(카드/칩/토글/버튼)로 재현하고, 번호 배지를 `position:absolute`로 실제 버튼/영역 위 정확한 픽셀 좌표에 겹쳐 그린다 — 나중에 스크린샷 위에 원을 덧그리는 것보다 좌표를 코드로 직접 지정할 수 있어 훨씬 정확했다.
- `chrome.exe --headless --disable-gpu --window-size=W,H --screenshot=out.png file:///...html`로 PNG 렌더링(로컬 크롬 설치를 그대로 활용, 별도 브라우저 자동화 불필요).
- 좌표를 못 맞춘 1차 렌더는 plain(배지 없이) 버전을 먼저 뽑아 눈으로 좌표를 읽은 뒤 배지를 추가하는 2단계로 진행 — 대부분 1~2회 반복으로 수렴.

### shared — GuideContent.kt
- `TabGuide`에 `legend: List<GuideCallout>`(번호·라벨·설명) 신규, `imageRes` getter를 `"guide_${id}_main"`(당시 스킴)으로 추가.
- 관리(8)·공부(7)·루틴(6)·소셜(7)·설정(4) 범례 텍스트 작성.

### 안드로이드/데스크탑 — TabGuideDialog 재작업
- `TabGuideIntroPage`(0번 페이지)를 이모지 큰 글씨에서 실제 화면 재현 이미지(`Image`+`painterResource`)로 교체, 이미지 아래 번호 매긴 범례 목록(원형 배지+굵은 라벨+설명) 추가.
- 이미지는 안드로이드 `res/drawable`(`guide_{id}_main.png`), 데스크탑 `src/main/resources/guide/`(`{id}_main.png`)에 동일 파일로 심고 `TabGuide.imageRes`로 참조.
- 개인정보 주의: 실제 캡처를 기각한 이유 중 하나가 로그인 계정명·가입 승인 대기자 실명이 설정 화면에 그대로 찍히는 문제(공개 GitHub 릴리스에 개인정보가 실릴 위험) — 재현 목업 방식은 표본 데이터만 쓰므로 이 문제가 아예 없다.

### 빌드/배포 (3차)
- 안드로이드: `assembleRelease` 재빌드(versionCode `1788943955`), 호스트 3곳 해시 일치 배포.
- 데스크탑: `packageMsi createDistributable` 재빌드(BuildInfo `1788943710`), 호스트 2곳 배포 — **이번엔 배포 명령을 백그라운드로 넘기지 않고 동기 실행+해시 검증까지 마친 뒤에만 "배포 완료"로 보고**(96차 배포 누락 사고 재발 방지, [[BUGS.md]] 참고).
- 데스크탑 앱을 직접 조작(관리 탭 진입 → ❓ 클릭)해 관리/소셜 탭 개요 페이지가 이미지+범례로 정상 렌더링되는 것을 스크린샷으로 확인.

---

## 2026-09-09 (97차 세션 4차 개편) — 모든 섹션 페이지에 예시 이미지 적용

3차 개편은 각 탭의 **첫 페이지(개요)** 에만 이미지를 넣고, 이후 섹션 페이지들은 여전히 아이콘 배지+텍스트였다. 사용자가 "왜 첫 페이지만이냐, 모든 탭·모든 페이지를 검토해서 각각의 기능을 예시 이미지로 설명해야 한다 — 이건 일부 화면 수정이 아니라 도움말 전체 구조를 다시 잡는 큰 개편"이라고 명확히 정정. 탭별로 어느 화면을 예시로 쓸지 먼저 계획을 정리한 뒤(관리: 규칙편집/확인대기/오버레이/기간지정/불러오기, 공부: 캘린더/계산기/일정표/통계, 루틴: 통계/알림/위젯, 소셜: 대화/깨우기/멤버관리/공유설정, 설정: 테마/알림/권한/업데이트/백업), 3차 개편에서 확립한 HTML+헤드리스 크롬 파이프라인을 그대로 재사용해 섹션마다 새 이미지를 만들었다.

### shared — GuideContent.kt
- `GuideSection`에 `imageRes: String? = null` 필드 신규. 값이 있으면 이미지, 없으면 기존 아이콘 배지로 폴백하는 구조이나 **32개 섹션 전부 채워서 사실상 항상 이미지가 뜬다**.
- 화면이 이미 메인 개요 이미지에 나와 있는 섹션(예: 관리 "차단 규칙 만들기", 소셜 "1:1 DM")은 새 이미지를 또 만들지 않고 해당 탭의 `_main` 이미지를 재사용 — 중복 제작 방지.
- `TabGuide.imageRes`/`GuideSection.imageRes` 스킴을 `"guide_xxx_main"`(3차 당시, `_main` 접미사 하드코딩) → 단순 베이스 키(`"manage_main"`, `"manage_limits"` 등, `guide_`/`guide/` 접두는 플랫폼 로더가 붙임)로 정리 — 섹션 이미지는 `_main` 접미사가 없는 이름이 많아 기존 스킴으로는 표현 불가능했음.

### 이미지 22개 신규 제작 (합계 27개)
- 관리: `manage_limits`(시간대·일일한도·잠깐풀기 편집), `manage_confirm`(확인 대기 화면), `manage_overlay`(사용중 오버레이), `manage_period`(기간지정 미니캘린더), `manage_sync`(불러오기+이름겹침 확인창).
- 공부: `study_calendar`, `study_calculator`, `study_timetable`, `study_stats`.
- 루틴: `routine_stats`(연속기록 카드 4종), `routine_notify`(알림 토스트), `routine_widget`(홈스크린 위젯).
- 소셜: `social_chat`(대화 채널), `social_wake`(깨우기 3택 다이얼로그), `social_members`(멤버관리+승계), `social_share`(공유설정 토글 3종).
- 설정: `settings_theme`(테마+글자크기 겸용), `settings_notif`, `settings_permission`, `settings_update`, `settings_backup`.

### 안드로이드/데스크탑 — GuideScreen.kt
- `TabGuideSectionPage`: `section.imageRes`가 있으면 제목 아래 이미지를 넣고(기존 아이콘 배지 생략), 없으면 기존처럼 아이콘 배지 — 양 플랫폼 동일 패턴.
- 안드로이드 `guideDrawableRes`를 5개짜리 수동 `when`에서 `context.resources.getIdentifier("guide_$imageRes", "drawable", packageName)` 동적 조회로 재작성(27개를 일일이 나열하지 않기 위함 — 도움말 다이얼로그를 열 때만 호출되는 경로라 리플렉션 비용 무시 가능).
- 데스크탑 `guideResourcePath`도 `"guide/$imageRes.png"` 단순 변환으로 정리.

### 빌드/배포 (4차, 최종)
- 안드로이드: `assembleRelease` 재빌드(versionCode `1788945199`), 호스트 3곳 해시 일치 배포.
- 데스크탑: `packageMsi createDistributable` 재빌드(BuildInfo `1788944982`), 호스트 2곳 배포(동기 실행+해시 검증).
- 데스크탑 앱을 직접 조작해 관리 탭 개요 페이지(8개 범례)와 "제한 방식" 섹션 페이지(규칙 편집 화면 재현 이미지+배지 4개)가 둘 다 정상 렌더링되는 것을 스크린샷 2장으로 확인.
- GitHub 릴리스는 미게시. **실사용 검증 범위**: 안드로이드는 전혀 미검증. 데스크탑은 관리 탭 2개 페이지만 육안 확인했고 나머지 30개 섹션 페이지·다른 4개 탭(공부/루틴/소셜/설정)은 미검증 — 배지 좌표가 대상 요소에서 살짝 벗어난 이미지가 일부 있으나(예: 처음 렌더에서 어긋난 걸 재조정한 것들) 큰 틀에서는 문제없이 작동함을 확인.

---

## 2026-09-09 (96차 세션) — 관리 탭 모바일 UI 전면 개편(자물쇠 아이콘) + 차단 규칙 상세 화면 계산기 스타일 재설계(미니 캘린더 포함) + 버그 3건 수정

95차에 롤백됐던 "관리 탭 메인 화면 UI 개편"을 이번엔 시안(HTML 목업)으로 먼저 확인받으며 여러 차례 반복 조정한 끝에 확정 → 이어서 사용자가 "이왕 시작한 김에" 차단 규칙 상세 페이지(GroupEditScreen)도 계산기 업무 카드 스타일로 개편해달라고 요청해 함께 진행. 마지막으로 4가지 추가 개선 요청 중 캘린더 교체+버그 3건을 처리했고, 새 기능 요청("루틴 모드")은 설계만 확정하고 구현은 다음 세션으로 이월.

### 관리 탭 그룹 목록 화면(GroupListScreen) 자물쇠 아이콘 개편 — 태블릿/데스크탑까지 통일
- 여러 차례 반복(세로줄 공백 제거 → 오른쪽 고정 정렬 → 시안 캡처로 확인 → 정보량 축소 요청)을 거쳐 최종안 확정: 상태 텍스트 배지("오늘 차단 중" 등)와 일일 한도/시간대/실행 전 대기 설명 줄을 전부 삭제하고, "지금 차단 중인가"만 이름 옆 자물쇠 아이콘 색(잠김=초록 `Icons.Filled.Lock`, 열림=회색 `Icons.Filled.LockOpen`)으로 표시.
- 잠깐 풀기 칩은 이름 아래 왼쪽 정렬, 동기화 칩+스위치는 이름+잠깐풀기 블록 전체 높이 기준으로 수직 중앙 정렬.
- **이 개편은 화면 크기와 무관하게 안드로이드/데스크탑/태블릿 전부 동일 레이아웃으로 통일**(기존 세션들은 종종 폰/태블릿을 분기했었는데, 이번 최종안은 분기가 필요 없어져 오히려 코드가 단순해짐).
- 안드로이드 데스크탑용 `material-icons-extended`가 없어 `LockOpen` 아이콘 참조가 실패 → `phone-lock-desktop/build.gradle.kts`에 `implementation(compose.materialIconsExtended)` 신규.

### 차단 규칙 상세 화면(GroupEditScreen) 계산기 스타일 전면 재설계
- `SectionCard`(양 플랫폼)에 `emoji` 파라미터 신규 — 주면 계산기 업무 카드의 "섹션 헤더 알약"(색 배경+이모지) 스타일로 그려짐, 안 주면 기존 그대로라 다른 화면(설정/루틴 등) 영향 없음. GroupEditScreen 9개 섹션 전부에 적용(📝 기본정보/🗂️ 관리종류/🍅 뽀모도로/😴 잠깐풀기/🗓️ 스케줄/⏱️ 일일한도/🛑 실행전대기/🚫 끄기금지/🎯 차단대상).
- 사용자가 시안 이미지를 직접 그려 보여주며 "그대로 가라"고 요청 → 신규 커스텀 컴포넌트 `CompactField`/`CompactNumberField`(`ui/components/CompactField.kt`, 양 플랫폼)로 교체. M3 `OutlinedTextField`의 애니메이션되는 "떠 있는 라벨" 대신, 얇은 테두리 박스 안에 라벨을 값 위에 고정 표시(`BasicTextField` 기반이라 직접 타이핑 그대로 가능), 숫자 필드는 "− 값 +" 스테퍼 박스.
- 시작/종료 시간 필드(스케줄·일일한도·실행전대기 적용시간대)를 🕐 이모지+가운데 정렬로, 두 필드를 "~"로 이어 나란히 배치(시안과 동일).
- 요일 선택을 사각 FilterChip에서 동그란 칩(선택 시 파란 채움)으로 교체.
- **"이 기간엔 끄기 금지" 날짜 범위를 공부앱 캘린더 탭 스타일 미니 캘린더로 교체**(사용자 요청) — `MiniCalendar.kt`(양 플랫폼 신규) `MiniCalendarDialog`(월 그리드, 일정 점 표시 없이 순수 날짜 선택)+`CompactDateField`(탭하면 다이얼로그가 뜨는 CompactField 모양 버튼).
- `DurationFieldsRow`(시/분/초 입력)도 `NumberStepperField`→`CompactNumberField`로 교체해 시안 스타일 통일.

### 버그 수정 3건 ([[BUGS.md]] 96차 참고)
- 소셜 탭 채팅 입력 후 엔터가 안 먹던 버그(`ChatThreadScreen.kt` 양 플랫폼, `keyboardActions`/`imeAction` 누락) 수정.
- 앱 실행 확인 화면이 커스텀 테마를 무시하던 버그(`ConfirmOpenActivity.kt`, `PhoneLockTheme` 호출에 커스텀 배경/강조색 파라미터 누락) 수정.
- 태블릿에서 공부 잠금 화면 정지 버튼이 안 보이던 버그(`StudyLockActivity.kt`, 스크롤 없는 고정 분할 Column이 좁은 세로 폭에서 콘텐츠를 잘라냄) — `verticalScroll` 추가로 수정.

### 다음 세션 이월: "루틴 모드" 신규 기능(설계만 확정, 구현 안 함)
- 사용자 확인 답변: **① 루틴 묶음 전환**(모드마다 별개 루틴 목록, 모드 전환 시 그 모드 루틴만 "오늘" 탭에 보임 — 삭제 아니라 숨김) ② **루틴 탭 상단 서브탭**으로 모드 배치 ③ **내보내기/불러오기는 전체 모드+루틴을 한 번에**(모드 하나만 부분 내보내기 아님).
- 구현 착수 전 조사까지 완료: `Routine` 엔티티에 `modeId` 필드 추가 + 신규 `RoutineMode` 엔티티(Room DB 버전 37→38, 명시적 `ALTER TABLE`/`CREATE TABLE` 마이그레이션 필요 — 82차 정책상 `fallbackToDestructiveMigration()`에만 맡기면 안 됨), Firebase 동기화(`routinesToJson`/`routinesFromJson`, `PomodoroSyncClient.readRoutines`/`writeRoutines`)에 모드 배열+`modeIndex` 참조 추가, `RoutineScreen.kt`에 모드 서브탭 UI 신규. **DB 스키마 변경이 걸린 작업이라 세션 막판에 서두르지 않고 다음 세션 처음부터 차분히 시작하기로 함**(사용자의 "적당한 때에 세션 마무리해" 요청에 따른 판단).

---

## 2026-09-07 (95차 세션) — 차단 규칙 목록 UI 반복 다듬기 + 안드로이드 자체 업데이트 다운로드/설치 안정화 + 관리 탭 UI 개편 시도(롤백)

94차의 opt-in 동기화 개편에 이어, 그룹 목록 화면(GroupListScreen)의 동기화/잠깐 풀기 배치를 사용자가 실시간으로 지적하며 여러 차례 재조정했고, 별개로 안드로이드 자체 업데이트가 "다운로드 중"에서 멈춘다는 제보를 받아 다운로드 방식 자체를 교체했다.

### 그룹 목록 화면 UI 최종 조정(94차에서 이어짐)
- 동기화 on/off·잠깐 풀기·켜짐/꺼짐 Switch를 여러 차례 재배치 — 최종적으로 이름/배지(왼쪽) + 동기화 칩·잠깐 풀기 칩(가운데, `IntrinsicSize.Max`로 서로 폭을 맞춰 세로줄 정렬)·Switch(오른쪽) 한 줄 구조로 확정.
- 잠깐 풀기 칩을 "N회독" 토글과 같은 알약 스타일로 통일(기존엔 OutlinedButton이라 높이가 달랐음), 동기화 칩 이모지를 N회독의 "🔁"와 헷갈리지 않는 "☁️"로 교체.
- 이름/배지 블록과 오른쪽 컨트롤 블록을 `CenterVertically`로 정렬해, 잠깐 풀기가 추가돼 오른쪽 칸이 늘어나도 왼쪽 텍스트가 항상 전체 줄 높이 가운데에 오도록 수정.
- 공부앱 "학습 통계" 탭 라벨을 "통계"로 통일(양 플랫폼) — 화면 안 제목은 원래 "통계"였는데 탭 라벨만 안 맞았던 불일치를 바로잡음.

### 안드로이드 자체 업데이트 다운로드/설치 안정화
- 사용자 제보: 업데이트 배너는 뜨는데 "업데이트" 버튼을 눌러도 다운로드가 안 되거나 멈춤(81차에 DownloadManager PAUSED 처리 등을 보강했지만 재발).
- **다운로드 방식을 시스템 `DownloadManager`(별도 서비스)에서 앱 프로세스 안에서 직접 `HttpURLConnection`으로 스트리밍하는 방식으로 교체** — OEM ROM의 배터리 최적화/백그라운드 서비스 제한이 이 앱이 손댈 수 없는 지점에서 DownloadManager를 막을 가능성을 원천적으로 제거. 저장 위치가 앱 전용 폴더로 바뀌어 설치 화면에 파일을 넘기려면 `FileProvider` 신규 등록 필요(`update_file_paths.xml`).
- API 30+ 패키지 가시성 제한으로 설치 프로그램이 실제로 있어도 `resolveActivity()`가 못 찾을 수 있는 문제 — `AndroidManifest.xml` `<queries>`에 `ACTION_VIEW` + APK mimeType 쿼리 추가.
- **실제로 다운로드가 끝까지 진행되고 설치까지 되는지는 미검증** — 사용자가 직접 최신 APK를 수동 설치해 확인하기로 함(현재 설치된 구버전은 옛 로직을 쓰므로 인앱 업데이트로는 이 수정 자체를 받을 수 없음).

### 관리 탭 메인 화면(차단 규칙 목록) UI 개편 — 시도 후 롤백
- 사용자 요청으로 소셜 탭(93차)의 카드 언어(그라디언트 배경/테두리 카드/원형 상태 아바타/요약 한 줄)를 그룹 목록 화면에도 적용해봤으나, 사용자가 마음에 안 든다며 롤백 요청 — 전체 되돌리고 다음 세션 할 일로 이월(아래 "다음 작업 우선순위" 참고). 무엇이 구체적으로 안 맞았는지는 이번엔 안 물어봐서 기록 없음 — 다음 세션엔 시도 전에 방향(색/카드 모양/정보 밀도 등)을 먼저 확인할 것.

### 빌드/배포
안드로이드만 변경(데스크탑 변경 없음) — UI 조정마다 반복 빌드, 최종 versionCode `1788712556`(호스트 3곳 해시 일치 배포 완료). 자체 업데이트 수정분은 GitHub 릴리스 게시([android-1788711405](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788711405)), 이후 UI 조정/롤백분은 이 세션 마무리 시점까지 GitHub 미게시.

---

## 2026-09-06 (94차 세션) — 관리앱 그룹 설정 동기화를 opt-in 방식으로 전면 개편 + 차단 규칙 목록 화면 UI 재작업

사용자 요청: 88차에 만든 "그룹 화면 진입 시 원격에 있는 모든 그룹 설정을 자동으로 로컬에 병합"하는 방식이 원치 않는 규칙까지 저절로 생기게 해 불편하다 — 그룹마다 동기화 여부를 직접 고르고, 새 규칙을 원격에서 가져오는 것도 "불러오기" 버튼으로 명시적으로 하고 싶다는 요청.

### 동기화 opt-in 개편
1. **그룹별 `syncEnabled` 필드 신규**(기본값 꺼짐) — 안드로이드 Room `MIGRATION_36_37`(v36→v37), 데스크탑 `JsonStore` 읽기/쓰기. 켜진 그룹만 원격에 올라가고(`groupSettingsToJson()`이 `syncEnabled=true`만 필터), 원격 최신값을 자동으로 받아온다(`syncGroupSettingsFromFirebase()`도 로컬에서 이미 켜진 그룹만 갱신 — 원격에만 있는 이름을 자동으로 새로 만들지 않음).
2. **"불러오기" 화면 신규**(`GroupImportDialog`, 양 플랫폼) — 그룹 목록 화면 상단 버튼으로 진입, 원격에 있고 이 기기엔 아직 동기화로 연결 안 된 규칙 이름 목록을 보여주고 고른 것만 불러온다(`fetchImportableGroupSettings()`/`importGroupSetting()`).
3. **이름 충돌 확인창**(`GroupEditScreen`, 새 규칙 저장 시) — 입력한 이름이 불러오기 목록과 겹치면 "동기화하시겠습니까?" 확인, 예=불러온 설정으로 생성+동기화 켜짐, 아니오=입력한 내용 그대로 동기화 꺼짐으로 생성(`findRemoteGroupSettingByName()`).
4. `PhoneLockRepository.GroupSync.kt`/`Repository.GroupSync.kt` 전면 재작성 — `applyGroupSettingsJson()`을 공개 함수로 전환해 편집 화면·불러오기 화면·목록 화면 세 곳에서 재사용.

### 차단 규칙 목록 화면(GroupListScreen) UI 반복 조정
사용자가 실시간으로 스크린샷 없이 말로 지적하며 여러 차례 재배치 — 최종본:
- **동기화 on/off는 그룹 목록 화면**(편집 화면이 아님)의 이름/배지 줄 맨 오른쪽, 차단 규칙 켜짐/꺼짐 Switch 바로 옆에 배치. 잠깐 풀기 버튼은 이름/배지 오른쪽의 남는 여백 안에서 왼쪽에 붙여 배치.
- "잠깐 풀기 설정"은 편집 화면의 "관리 종류" 카드에서 분리해 독립 섹션으로(동기화 on/off 스위치 자체는 편집 화면에 없음 — 목록 화면 전용).
- 동기화 칩은 공부앱 캘린더 "N회독" 토글과 같은 알약 스타일(색 배경+굵은 글씨)이되 이모지는 "🔁"(N회독)과 겹치지 않는 "☁️"(클라우드 동기화)를 사용.
- **차단 규칙이 꺼지면 카드 전체를 `Modifier.alpha(0.55f)`로 흐리게** — 고정 회색 대신 알파 값만 낮춰 라이트/다크/커스텀 테마 배경이 그대로 비치게 해 항상 테마에 맞는 "흐려진" 색이 나오게 함.
- 중간에 시도했다가 되돌린 것들: 동기화를 Switch로(칩 모양이 낫다는 지적으로 되돌림), 켜짐/꺼짐을 별도 줄+알약 칩으로(원래 Switch가 낫다는 지적으로 되돌림), 이모지 🔄(회전 화살표, N회독 🔁와 비슷해 보인다는 지적으로 ☁️로 교체).

### 빌드/배포/게시
안드로이드 `assembleRelease`/데스크탑 `packageMsi createDistributable`를 UI 조정마다 반복(총 6회) — 최종 안드로이드 versionCode `1788703336`, 데스크탑 BuildInfo `1788703299`(3곳/2곳 해시 일치, 호스트 실제 배포 완료). GitHub 릴리스 게시: [android-1788703336](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788703336) / [desktop-1788703299](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788703299).

**94차 신규 기능(동기화 opt-in/불러오기/이름 충돌 확인) 전체 실사용 미검증** — 다음 세션 우선 확인 대상.

---

## 2026-09-06 (93차 세션) — "소셜" 개편(모임 탭을 메신저 형태로 확장): 그룹 대화 채널, 1:1 DM, 소유권 승계, 전체 디자인 통일

사용자 요청: "모임" 섹션을 "소셜"로 개편 — 범위를 물으니 이름 변경뿐 아니라 카카오톡/인스타그램DM/디스코드/LINE 같은 메신저 형태로 목적 자체를 바꾸고 싶다는 답. 4개 메신저를 분석해 디스코드식 "서버=모임, 채널=용도별 공간" 구조를 제안하고 승인받아 단계별로 진행.

### Phase 1 — 탭 리네이밍 + 그룹 "💬 대화" 채널
- 안드로이드 `Tab.Group`/데스크탑 `TopSection.SOCIAL_GROUP` 표시 라벨을 "모임"→"소셜"로(개별 모임방 자체의 이름은 "모임"으로 유지 — 차단 규칙의 "그룹"과 구분하려던 기존 취지 보존).
- 신규 `groupChats/{groupId}/messages/{msgId}` 스키마 — 별도 참여자 목록을 두지 않고 기존 `groups/{groupId}/members`를 보안 규칙에서 그대로 재사용(멤버십 변경 시 동기화할 것이 없어짐).
- 신규 `ChatSyncClient.kt`(양 플랫폼, `SocialGroupSyncClient`와 동일 REST 패턴) + `PhoneLockRepository.Chat.kt`(안드로이드).
- 신규 `GroupChatScreen.kt` — 텍스트 + 이모지 리액션(👍❤️😂😮😢🔥), 화면이 켜져있는 동안 4초 폴링(SSE/FCM 없이 기존 무전기 폴링과 같은 스타일). `SocialGroupMembersScreen.kt`에 "멤버"/"💬 대화" `TabRow` 추가.

### Phase 2 — 1:1 DM(전역 검색)
- 신규 `dmChats/{chatId}` 스키마(참여자 고정, 생성 후 불변) — DM 상대는 기존 로그인 시스템의 공개 인덱스(`usernames/{customId}`)로 검색, 새 공개 프로필 노출 없이 구현.
- `users/{uid}/dmChatIds/{chatId}`로 내 대화 목록 인덱스(양쪽 참여자가 서로의 인덱스에 쓸 수 있도록 규칙 추가).
- 신규 `DmChatScreen.kt`. 안드로이드/데스크탑 각각 `ChatThreadScreen.kt`(공용 메시지 스레드 UI)를 추출해 그룹 대화/DM이 함께 재사용 — UI는 완전히 같고 저장 경로(groupChats vs dmChats)만 다름.
- "소셜" 탭 진입 화면(`SocialGroupScreen.kt`) 상단에 "1:1 대화" 목록 + "새 대화" 검색 다이얼로그 신설.

### Phase 4 — 관리 기능 강화: 모임장 소유권 승계
- IDEAS.md 77차 보류 항목 구현 — `SocialGroupSyncClient.transferOwnership()`(양 플랫폼), "👥 멤버 관리" 다이얼로그에 "👑 승계" 버튼. 넘긴 모임장은 자동으로 관리자가 됨. 기존 `groups/{id}/info` 쓰기 규칙이 이미 모임장/관리자 둘 다 허용해 별도 보안 규칙 추가 불필요.
- (정보 공유 확장 — 오늘 일정표 공유는 78~79차에 이미 구현돼 있었음을 이번에 재확인, 중복 작업 없이 스킵.)

### 소셜 화면 전체 재디자인
- 92차 공부 잠금 화면(`StudyLockActivity`/`StudyLockScreen`)의 강조색 배지/카드형 언어를 소셜 관련 화면 전체(모임·DM 목록, 멤버 목록, 멤버 상세, 채팅방)에 통일 — 완료율/스트릭을 알약 배지로, 카드에 accent 톤 배경+테두리.
- **배경 그라디언트 방향 수정(사용자 실사용 중 지적)**: 처음엔 공부 잠금 화면과 같은 `Brush.radialGradient`(중앙에 빛나는 원)를 그대로 썼는데, 그 모양은 잠금 화면의 원형 진행률 링과 짝을 이루는 디자인이라 링이 없는 리스트 화면(소셜)에는 안 어울린다는 지적을 받아 위→아래로 옅어지는 `Brush.verticalGradient`로 교체. 잠금 화면 쪽은 그대로 유지 — 프로젝트 전체에서 `radialGradient`를 쓰는 곳은 이제 잠금 화면 두 곳뿐임을 확인.

### 그 외
- **캘린더 일정 이동/복사 날짜 선택 개선**: `YYYY-MM-DD` 수동 텍스트 입력을 계산기 업무 입력에 쓰던 미니 캘린더 `DatePickerField`로 교체(양 플랫폼 `CalendarScreen.kt`) — 새 컴포넌트 없이 기존 것 재사용.
- Firebase 규칙(`groupChats`/`dmChats`/`dmChatIds`) 2회 추가, 매번 사용자가 콘솔에 직접 게시(Claude는 콘솔 접근 권한 없음).
- 빌드/배포: 여러 차례에 걸쳐 데스크탑 `packageMsi createDistributable`(최종 BuildInfo `1788695394`), 안드로이드 `assembleRelease`(최종 versionCode `1788695472`, 3곳 해시 일치) — 이 호스트 실제 배포 완료. GitHub 릴리스도 [android-1788695472](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788695472)/[desktop-1788695394](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788695394)로 게시.
- 실사용 검증: 소셜 탭 진입/디자인은 사용자가 직접 확인(그라디언트 문제도 이 과정에서 발견). 그룹 대화/DM 메시지 송수신·리액션·소유권 승계는 아직 실사용 미검증.

---

## 2026-09-06 (92차 세션) — 90차 지정 다음 세션 작업 중 1·2·5번(공부 잠금 화면 디자인 개편, 안드로이드 타이머 탭 대칭화, 빈 공간 통계 위젯) + 그 과정에서 발견한 크로스디바이스 동기화 버그 수정

사용자 요청: 90차 지정 작업 중 "1. 공부 잠금 오버레이 UX/UI 개편"부터 시작 — 구체적 문제를 먼저 확인해달라는 요청에 "디자인이 밋밋하다/정보가 부족하다/허용 앱 목록 UX가 별로다"는 답을 받음.

### 공부 잠금 화면(오버레이) 전면 재디자인 — 양 플랫폼

- **`StudyLockScreen.kt`(데스크탑)/`StudyLockActivity.kt`(안드로이드)를 `StudyTimerScreen`과 같은 디자인 언어로 재구성**: primary 강조색 배지("🍅 뽀모도로 · 공부 중"/"🔒 공부 중" + 원격이면 "📡 원격"), 은은한 radial gradient 배경, 큰 원형 진행률 링(`LockRing`, `TimerIllustration`을 잠금화면 크기로 확장) — 뽀모도로면 남은 시간 카운트다운+진행률 호(arc), 일반 스톱워치면 경과시간+도는 초침.
- **정보 보강**: 오늘 캘린더 일정(taskName) 칩, "오늘 누적 공부시간" 표시 추가 — 기존엔 경과시간만 있었음. 원격 잠금(다른 기기가 재는 중)일 때도 `PomodoroSyncClient`로 taskName/phaseEndAt을 읽어와 같은 원형 링에 반영(표시만, 제어는 안 함 — 기존 원칙 유지).
- **허용 앱/프로그램 목록을 카드형으로 교체**: 안드로이드는 실제 앱 아이콘(`AppIcon.kt` 재사용), 데스크탑은 첫 글자 원형 아바타 + `FlowRow`로 줄바꿈.
- 판정 로직(잠금 유지 조건, `isStillActive`/`ConfirmationGate` 등)은 전혀 안 건드림 — 순수 표시만 변경.
- 컴파일 확인 후 데스크탑 `packageMsi createDistributable`(BuildInfo `1788685857`), 안드로이드 `assembleRelease`(versionCode `1788661502`... 아래 재배포로 갱신됨) — 이 호스트 실제 배포까지 완료.

### 안드로이드 타이머 탭에 데스크탑과 대칭인 시계 일러스트 추가(90차 지정 2번)

- **`TimerIllustration` 안드로이드 이식**: 데스크탑 90차의 시계 그림(대기 중 정적 시계, 뽀모도로 중 진행률 호, 일반 스톱워치 중 초침 회전)을 안드로이드 `StudyTimerScreen.kt`에도 동일하게 추가 — phone(세로 스택)/tablet(좌우 분할 오른쪽 컬럼) 둘 다 반영.

### 타이머 탭 빈 공간에 통계 위젯 3종 추가(90차 지정 5번)

사용자 요청: 시계 일러스트만으론 "메인에서의 공부앱 : 타이머 화면은 여전히 비어보여"라는 피드백 — 방향을 골라달라고 물어 "최근 7일 막대그래프/연속 스트릭 카드/과목별 도넛 차트" 3개를 선택받음.

- **`StreakCard`**: 오늘부터 거슬러 올라가며 공부 기록이 있는 날짜가 끊기는 지점까지 세는 연속 공부일 카드(🔥).
- **`WeekBarChart`**: 최근 7일(오늘 포함) 공부시간 막대그래프, 오늘은 진한 색으로 강조.
- **`SubjectPieChart`**: 오늘 과목(태그, 없으면 업무 이름)별 공부시간 도넛 차트 + 범례.
- 양 플랫폼 대칭 구현, 판정 로직과 무관한 순수 표시용. 시계 일러스트 아래, "오늘의 공부 기록" 카드 위에 배치.

### 발견 및 수정: `getAllStudyLogOnce()`로 만든 통계가 다른 기기 기록을 놓치는 동기화 버그

- **경위**: 위 통계 위젯 3종을 다 만든 뒤, "이번에 추가한 것들도 동기화 관련해서 문제 없는지 살펴봐"라는 요청을 받고 재검토하다가 발견.
- **원인**: `getAllStudyLogOnce()`(양 플랫폼)는 이 기기가 로컬에 쓴 `StudyLogEntry`만 반환한다 — 다른 기기가 그날 Firebase에 올린 기록은 `remoteStudyLogCache`라는 별도 맵에만 들어있고(`syncStudyLogFromFirebase(dateKey)`가 채움), `getTodayStudyLog()`/`getStudyLogForDate()`만 이 캐시를 로컬 값과 합쳐 반환한다. 스트릭/주간 그래프를 `getAllStudyLogOnce()`로 만들면 다른 기기에서만 공부한 날이 0으로 보이는 문제가 있었음(34차/38차에 확립된 "기기별 키는 읽는 쪽이 합산" 원칙을 새 위젯에 적용 안 한 실수).
- **해결**: `daySecondsSynced(dateKey)` 헬퍼 신규(양 플랫폼) — 오늘은 이미 5초마다 갱신되는 `todayLog`를 그대로 쓰고, 과거 날짜는 그 자리에서 `syncStudyLogFromFirebase(dateKey)` 동기화 후 `getStudyLogForDate(dateKey)`로 합산. `refreshStreakAndWeek()`가 최근 7일을 이 헬퍼로 채우고, 스트릭은 7일 치를 재사용한 뒤 필요하면 하루씩 더 거슬러 동기화(무한 네트워크 호출 방지용 60일 상한). 매 5초마다 하기엔 비용이 커서 30초 주기로 갱신.
- 컴파일 확인 후 양 플랫폼 재빌드/재배포 — 데스크탑 BuildInfo `1788690197`, 안드로이드 versionCode `1788690271`(3곳 해시 일치 확인). [[BUGS.md]] 92차 참고.

### 세션 중 세션 번호 오기 정정

- 코드 주석에 이번 세션 작업을 "91차"로 잘못 붙였던 곳들(91차는 이미 이전 세션의 문서 정리 작업에 쓰였음)을 전부 "92차"로 정정.

전체 빌드/배포: 이번 세션은 GitHub 릴리스 게시는 요청받지 않아 호스트 실제 교체 배포까지만 진행. 실사용 검증은 아직 안 됨.

---

## 2026-09-06 (91차 세션) — 90차 지정 9개 작업 중 4건(도움말 표시 조건, 홈 화면 삭제, 루틴 상세 패널, 접근성 배너, 업데이트 배너 한글 필터) + 문서 정리 + 양 플랫폼 릴리스 빌드

사용자 요청: 89차에 추가한 도움말 화면이 지금은 "최초 1회"만 뜨는데, 업데이트를 막 했을 때도 다시 뜨게 해달라는 요청.

- **`hasSeenGuide: Boolean` → `lastSeenGuideVersion: Long`으로 필드 교체**(양 플랫폼): 단순 "봤다/안 봤다" 플래그 대신 "마지막으로 본 시점의 버전"을 저장해, 이 값이 현재 실행 중인 버전과 다르면(최초 설치 포함, 기본값이 실제 버전과 절대 안 겹치는 sentinel) 자동으로 다시 표시되게 함 — 안드로이드는 이미 자체 업데이트 체크에 쓰던 `versionCode`(`Repository.currentVersionCode()`), 데스크탑은 `BuildInfo.BUILD_TIMESTAMP`(`Repository.currentBuildTimestamp()`)를 그대로 재사용해 새 의존성/새 버전 식별자 없이 구현. 안드로이드 `AppPreferences.hasSeenGuide`(SharedPreferences Boolean, 기본 false) → `lastSeenGuideVersion`(Long, 기본 -1L), 데스크탑 `AppData.hasSeenGuide`(JsonStore Boolean, 기본 false) → `lastSeenGuideVersion`(Long, 기본 0L) + `Repository.hasSeenGuide` → `Repository.lastSeenGuideVersion` 프로퍼티 교체. 옛 필드는 이 두 지점(표시 조건 계산/`onDismiss`)에서만 쓰이던 걸 확인하고 완전히 제거(하위호환 별칭 없음) — SharedPreferences/JsonStore 둘 다 스키마리스 키-값이라 마이그레이션 불필요(Room DB 버전과 무관).
- **설정 화면 "도움말" 카드로 다시 열기는 그대로 유지** — 이번 변경은 자동 표시 조건만 바꿨고, 수동으로 다시 보는 경로(`onShowGuide`)는 손대지 않음.
- 안드로이드 `compileDebugKotlin`, 데스크탑 `compileKotlin` 양쪽 컴파일 확인 완료(스크래치 경로 `C:\build\phonelock-android`/`C:\build\phonelock-desktop`). 릴리스 빌드/배포/실기기 검증은 아직 안 함.

### 홈 화면 완전 삭제(90차 지정 다음 세션 필수 작업 4번)

사용자 요청: 90차에 신설한 홈 화면(오늘 상태 요약 카드)을 실사용해보니 불필요하다고 판단, 완전히 제거.

- **`HomeScreen.kt` 삭제**(양 플랫폼): 90차에 신설된 파일을 그대로 삭제. 다른 화면이 이 파일의 내부 로직을 가져다 쓴 게 없어(전부 기존 `repository`/`RoutineEngine`/`LockEvaluator` 함수를 호출만 했음) 부작용 없음.
- **최상위 네비게이션에서 "홈" 항목 제거**: 안드로이드 `MainActivity.kt`의 `Tab` sealed class에서 `Tab.Home` 제거 + `visibleTabs()`에서 맨 앞에 끼워넣던 `Tab.Home,` 제거 + `NavHost`의 `composable(Tab.Home.route){...}` 블록 제거. 데스크탑 `MainScreen.kt`의 `TopSection` enum에서 `HOME` 제거 + `visibleSections` 목록에서 `TopSection.HOME,` 제거 + 무조건 표시되던 홈 `NavigationRailItem` 제거 + `when(section)`의 `TopSection.HOME -> {...}` 분기 제거.
- **시작 화면 자동 복원**: 90차는 "홈"을 목록 맨 앞에 끼워넣기만 했을 뿐 별도의 "시작 화면" 플래그를 추가하지 않았다 — 양 플랫폼 모두 시작 화면은 여전히 `visibleSections.first()`/`tabs.first().route`로 계산되므로, 끼워넣은 한 줄만 지우면 90차 이전의 첫 번째 보이는 섹션으로 자동 복원된다. 별도 되돌리기 로직 불필요.
- **부수 원복**: `RoutineScreen.kt`(양 플랫폼)의 `isScheduledOn` 함수가 홈 화면에서 "오늘 예정된 루틴 수"를 계산하려고 `private`에서 `internal`로 열렸던 것을, 홈 화면 삭제로 더 이상 외부에서 안 쓰이므로 `private`로 원복(관련 KDoc 주석도 제거).
- 안드로이드 `compileDebugKotlin`, 데스크탑 `compileKotlin` 양쪽 컴파일 확인 완료. 릴리스 빌드/배포/실기기 검증은 아직 안 함.

### 데스크탑/태블릿 루틴 화면 오른쪽 컬럼을 마스터-디테일로 교체(90차 지정 3번)

사용자 요청: 90차에 넣은 오른쪽 컬럼 "오늘 요약"(연속 기록/완료율)을 빼고, 루틴을 클릭하면 그 루틴의 상세 페이지가 뜨도록 바꿔달라는 요청.

- **`RoutineRow`에 클릭 선택 추가**(데스크탑): 기존엔 체크박스(완료 토글)와 ✏️ 아이콘(편집)만 상호작용 가능했는데, 행 전체에 `selected`/`onSelect` 파라미터를 추가해 클릭하면 선택 상태가 되고(강조 배경/테두리), 체크박스·편집 버튼은 각자 자기 클릭만 소비해 기존 동작과 충돌 없음.
- **오른쪽 컬럼을 `RoutineDetailPanel`로 교체**: 선택된 루틴이 없으면 안내 문구, 있으면 제목/아이콘, 반복 요일(`daysMask` 디코드), 시간대, 기간(시작~종료일), 알림 on/off, 최근 30일 완료 일수, "✏️ 수정" 버튼(기존 `RoutineEditDialog` 재사용)을 보여준다.
- **루틴 하나만의 연속 기록(`routineOwnStreak`) 신규**: 기존 `RoutineEngine.currentStreak()`는 "그날 예정된 루틴 전부"를 기준으로 하는 전역 스트릭이라 이 용도엔 안 맞음 — 오늘부터 거슬러 올라가며 이 루틴이 예정된 날만 보고 완료 여부를 확인하는 별도 함수를 `RoutineScreen.kt`(데스크탑) 안에 추가(무한 루프 방지용 3650일 상한).
- 안드로이드는 이 화면에 애초에 좌우 분할(`ResponsiveSplit`)이 없어(태블릿도 세로 목록 하나) 이번 변경 대상 아님.
- 안드로이드 `compileDebugKotlin`, 데스크탑 `compileKotlin` 양쪽 컴파일 확인 완료. 릴리스 빌드/배포/실기기 검증은 아직 안 함.

### 공부(타이머) 탭 접근성 경고 배너 추가 + 업데이트 배너 한글 필터 + 문서 정리(90차 지정 9·7·8번)

- **공부(타이머) 탭 접근성 경고 배너(9번, 안드로이드)**: `StudyTimerScreen.kt`에 `GroupListScreen.kt`와 같은 `AccessibilityServiceChecker.isEnabled` 패턴의 배너를 phone/tablet 레이아웃 양쪽에 추가 — 관리(차단) 규칙을 하나도 안 쓰고 공부 타이머만 쓰는 사용자도 접근성 서비스가 꺼지면 알 수 있게 됨.
- **자체 업데이트 배너 한글 필터(7번, 안드로이드)**: 조사 결과 GitHub 자동생성 "What's Changed" 텍스트가 아니라, 세션마다 `gh release create --notes`를 영어/한글 섞어 채워온 게 원인이었음(데스크탑은 애초에 이 텍스트를 표시하지 않음). `UpdateBanner.kt`에서 표시 직전에 한글 음절이 하나도 없는 값은 빈 문자열로 처리해 숨기도록 필터 추가 — 과거 영어 릴리스 노트 자체를 고칠 방법은 없어 앞으로의 릴리스부터 정상 표시된다.
- **문서 정리(8번)**: `HANDOFF.md`의 "현재 진행 중인 작업"에 81~88차 세션별 상세 서술이 그대로 누적돼 500줄에 육박했는데, 전부 이 CHANGELOG.md에 이미 기록된 내용이라 회차별 한 줄 요약으로 압축(미검증 항목은 원래도 "다음 작업 우선순위"에 별도로 있어 정보 손실 없음).
- 안드로이드 `compileDebugKotlin`, 데스크탑 `compileKotlin` 양쪽 컴파일 확인 완료. 릴리스 빌드/배포/실기기 검증은 아직 안 함.

### 릴리스 빌드(사용자 요청: "빌드하고 세션 마무리해")

위 4건(도움말 조건/홈 삭제/루틴 상세 패널/접근성 배너/업데이트 배너 필터) 전부를 반영해 양 플랫폼 릴리스 빌드 완료.

- **안드로이드**: `AndroidBuilds\phone-lock-android`에 최신 소스+`shared` 미러 후 `assembleRelease`(versionCode `1788661502`) — `AndroidBuilds\phone-lock-app-release.apk`/OneDrive 원본 `app\build\outputs\apk\release\`/`vm-build-output\android` 3곳 해시 일치 확인.
- **데스크탑**: `C:\build\phone-lock-desktop`에 최신 소스+`shared` 미러(89차 교훈대로 낡은 `build/` 폴더 삭제 후) `packageMsi createDistributable`(BuildInfo `1788661637`) — `vm-build-output\PhoneLockDesktop`에 robocopy 반영(FAILED 0 확인).
- **이번엔 "빌드"만 요청받아 다음은 하지 않음**: 이 호스트에서 실제로 돌아가는 데스크탑 앱 교체(watchdog 끄기→프로세스 종료→재실행)와 GitHub 릴리스 게시. `feedback_github_upload_means_release`/`feedback_host_live_deploy_standard` 메모리 기준으로 "빌드"는 이 둘과 구분되는 좁은 범위라고 판단 — 다음에 "배포해줘"/"깃허브에 올려"라고 하면 그때 진행.

---

## 2026-09-06 (90차 세션) — 전체 UX/UI 개편(감사→네비게이션→용어→레이아웃) + 타이머 버그 3건 수정

사용자 요청: "실제 사용자처럼 앱을 탐색해서 UX/UI를 개선해달라"는 포괄적 요청으로 시작해, 세션 도중 사용자가 직접 데스크탑 앱을 써보며 추가 피드백을 여러 차례 줘서 순차적으로 반영. 핵심 기능/데이터 구조/판정 로직은 전혀 안 건드리고 화면 레이아웃·문구·네비게이션만 다뤘다. 배경 에이전트 4개(UX 감사, 네비게이션+용어 감사, 용어 반영, 레이아웃 확장)를 순차 실행하고 그 사이사이 메인 세션이 직접 후속 수정을 진행하는 방식으로 작업했다.

1. **1단계 — 전체 화면 UX 감사(코드 기반)**: 실제 기기 조작 도구가 없어(네이티브 앱이라 브라우저 자동화 불가) 안드로이드/데스크탑 전 화면의 Compose 소스(여백/폰트크기/터치영역/색상 대비)를 정독해 문제를 찾고 우선순위화. 그룹 목록의 상태 배지 승격(`GroupStatusBadge`, 3색), 스누즈 한도 하드코딩 수정, 실행확인 버튼 48dp 최소 터치영역, 도움말 화면 스크롤 누락 수정 등 14개 파일. 안드로이드/데스크탑 둘 다 `compileDebugKotlin`/`compileKotlin` 통과, 실행 중인 데스크탑 앱은 검증 중 건드리지 않음.
2. **2단계 — 네비게이션 개편**: "정보 배치·흐름이 난잡해 뭘 해야 할지 모르겠다"는 지적에 따라 (a) **홈 화면 신규**(`HomeScreen.kt` 양 플랫폼) — 앱 진입 시 오늘 상태 요약 카드 4개(관리/루틴/공부/모임, 안 쓰는 기능은 자동 숨김)를 먼저 보여주고 누르면 해당 섹션으로 이동, (b) "통계"라는 같은 라벨이 관리/공부/루틴/모임상세(2곳) 5곳에서 전혀 다른 내용을 가리키던 걸 "사용 기록"/"학습 통계"/"스트릭"으로 구분, (c) "⏱️ 시간 측정" 탭을 "⏱️ 타이머"로. **91차 세션에 사용자가 실사용 후 홈 화면을 되돌리기로 결정** — 아래 [[HANDOFF.md]] 참고.
3. **3단계 — 전체 용어 재정비(28건)**: 감사에서 나온 후보 중 사용자가 항목별로 확정한 것만 적용 — 코틀린 식별자/파일명/Firebase 경로/Room 컬럼은 전혀 안 건드리고 **사용자에게 보이는 한국어 문자열만** 교체. 핵심: "그룹"→"차단 규칙"(단, "모임"은 완전히 별개 기능이라 그대로 유지), "스누즈"→"잠깐 풀기", "실행 확인"→"실행 전 대기", "오버레이"→"화면 덮개", "회유 멘트"→"확인 질문", "무전기"→"깨우기 메시지", "다회독"→"N회독", "스트릭"→"연속 기록" 등. "이름 없는 그룹" 같은 DB에 실제로 저장되는 값(88차 `groupSettings` 동기화가 이름을 키로 씀)은 기기 간 매칭이 어긋날 위험이 있어 의도적으로 보류.
4. **4단계 — 데스크탑/태블릿 레이아웃 확장**: 28차에 그룹/타이머/캘린더/계산기 4곳에만 있던 좌우 분할이 설정/공부통계/일정표/루틴엔 없어 넓은 화면을 못 쓰던 문제 — 설정은 `ResponsiveSplit`이 안 맞아(바깥이 이미 `verticalScroll`이라 무한 높이) 별도 `SettingsColumns`(`BoxWithConstraints` 기반 폭 전용 래퍼, 900dp 기준) 신규, 나머지 3곳은 `ResponsiveSplit`으로 좌우 분할.
5. **공부 중 허용 프로그램/사이트 위치 이동**: 타이머 탭에서 설정 > 공부 탭으로 이동(양 플랫폼) — "매번 보는 화면이 아니라 한 번 정해두는 설정"이라는 사용자 판단.
6. **타이머 탭 빈 공간 채움**: 위 이동으로 비게 된 오른쪽 컬럼 위쪽에 Compose `Canvas`로 그린 담백한 시계 일러스트(`TimerIllustration`) 추가 — 대기 중 정적 시계, 뽀모도로 실행 중엔 진행률 호. **92차에 사용자가 "뽀모도로 아닐 때는 밋밋하다"고 지적해 일반 스톱워치 모드엔 경과 초에 맞춰 도는 초침을 추가로 그림.** 이 일러스트 자체를 다른 걸로 바꿀지는 다음 세션 논의 예정([[HANDOFF.md]] 참고).
7. **"종료 시 확인 질문" 위치 이동**: 설정 > 관리 탭에서 설정 > 앱 전체 탭으로("자동 재시작(워치독)" 옆) — 관리(차단) 기능이 아니라 앱 종료 절차 자체에 관한 설정이라는 판단. 코드 기본값은 원래도 OFF라 변경 없음. **사용자 실제 기기 값(ON)은 39/86차 자기통제 게이트 원칙에 따라 직접 데이터 파일로 우회 수정하지 않고 그대로 둠** — [[DECISIONS.md]] 참고.
8. **캘린더 일정 없이 타이머 시작 가능**: "캘린더에 오늘 일정을 추가하면 타이머를 사용할 수 있습니다"라는 차단 문구를 없애고 업무 이름 입력을 항상 자유 입력 가능하게(양 플랫폼). 빈 이름 기록은 "이름 없는 공부"로 폴백 표시.
9. **버그 수정 — 일정 고르기 기능이 사라진 것처럼 보이던 문제(92차)**: 8번 작업 시 "일정 없으면 자유입력, 있으면 드롭다운"으로 분기했다가 사용자가 "드롭다운이 없어진 것 같다"고 재지적 — 아예 **항상 자유 입력 + (일정이 있으면) 드롭다운 아이콘으로 고르기**를 동시에 지원하도록 통합.
10. **버그 수정 — 오버레이(잠금 화면) 종료 시 회고 입력 누락(92차)**: 39차에 타이머 탭 "정지" 버튼에만 붙었던 짧은 회고+태그 입력이 전체화면 잠금 화면(`StudyLockActivity`/`Main.kt`의 `StudyLockScreen`)의 "정지" 버튼엔 원래부터 빠져있었음 — 같은 다이얼로그를 양쪽 다 추가, 확인해야 `timerStop(note, tag)`가 실제로 호출됨.
11. **버그 수정 — 다른 기기가 추가한 오늘 일정이 타이머 탭에 안 보임(93차)**: 원인은 `CalendarScreen`/`StudyStatsScreen`은 진입 시 `syncCalendarFromFirebase()`를 부르는데 **타이머 탭만 이 동기화를 한 번도 호출하지 않아** 로컬 캐시가 오래된 채로 남아있었던 것 — 기존 5초 주기 동기화(공부기록)에 캘린더 동기화도 추가.
12. **"해당 없음" 옵션 추가(93차)**: 빈 문자열로 지우는 방법을 모르는 사용자를 위해 드롭다운 맨 위에 명시적 "해당 없음" 항목 추가. 이 과정에서 "목록이 갱신될 때마다 자동으로 첫 일정으로 도로 채워지는" 잠재 버그도 함께 발견해 수정(`taskNameTouchedByUser` 플래그로 사용자가 한 번이라도 손댔으면 자동 채움 중단).
13. **빌드/배포**: 이번 세션에 총 6회 재빌드/재배포(수정할 때마다 즉시 검증→배포 반복). 최종본: 데스크탑 BuildInfo `1788629694`, 안드로이드 versionCode `1788629953` — 양 플랫폼 표준 3~4위치 해시 일치 확인 후 호스트 실제 교체+재실행까지 완료. **GitHub 릴리스도 이번엔 사용자 요청으로 게시**: [desktop-1788629694](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788629694), [android-1788629953](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788629953).
14. **자기통제 게이트 우회 재확인**: `exitConfirmEnabled`가 사용자 실제 기기에 이미 ON으로 저장돼 있는 걸 확인했지만, 이 값을 끄려면 앱 안에서 확인 질문 20개를 통과해야 하는 의도된 설계라 데이터 파일을 직접 고쳐 우회하지 않았다(86차와 같은 원칙). 사용자도 "내 기기는 ON 유지, 코드 기본값(다른 사람 기준)만 OFF"라고 재확인 — 코드 기본값은 애초에 OFF였으므로 실제로는 변경 없음.

**다음 세션 최우선 순위**: 아래 [[HANDOFF.md]] "다음 세션에서 반드시 알아야 하는 내용" 참고 — 사용자가 이번 세션 종료 시점에 8개 항목을 직접 지정.

---

## 2026-09-05 (89차 세션) — 앱 내 도움말(사용법 안내) 화면 신규 추가

사용자 요청: 기능이 많고 계속 늘어나는데 사용법 안내가 없어, 최초 실행 시 자동으로 뜨고 이후에도 다시 볼 수 있는 그림 기반 도움말 화면을 추가. 대상이 본인뿐 아니라 모임(소셜 그룹) 멤버일 수도 있어 전문 용어를 풀어쓰는 방향으로 문구를 작성. 화면 이미지는 실제 스크린샷이 아니라 Compose로 그린 모크업으로 하기로 사전 확정(이 앱엔 이미지 자산 파이프라인이 없고 UI가 자주 바뀜).

1. **공용 콘텐츠 신설**(`shared/GuideContent.kt`): `GuidePage(id, emoji, title, bullets)` 7개 — `intro`/`manage`/`snooze`/`study`/`routine`/`social`/`settings`. `MotivationalQuotes.kt`/`PersuasionMessages.kt`와 같은 "텍스트만 공유, 그림은 플랫폼별" 패턴.
2. **`GuideScreen.kt` 신규**(양 플랫폼): 안드로이드는 `HorizontalPager`(스와이프), 데스크탑은 이전/다음 버튼으로 페이지 전환. 페이지마다 실제 팔레트 색만 가져와 그린 단순화된 모크업(휴대폰 화면을 그대로 캡처하지 않음) + 제목/설명 불릿. 하단 dot 인디케이터, "건너뛰기"/"다음"/"시작하기" 버튼.
3. **최초 실행 자동 표시 + 재방문**: 안드로이드 `AppPreferences.hasSeenGuide`(SharedPreferences), 데스크탑 `AppData.hasSeenGuide`(JsonStore) 신규 필드로 최초 1회 여부 관리. 안드로이드는 기존 권한 안내 `OnboardingDialog` 바로 뒤에 표시(별개 목적이라 유지), 데스크탑은 최초 실행 온보딩 자체가 이번에 처음 생김. 설정 화면 공통 탭에 "도움말" `SectionCard`(양 플랫폼) 추가해 언제든 다시 열람 가능 — `SettingsScreen`/`MainScreen`(desktop)/`MainActivity`+`PhoneLockApp`(android)에 `onShowGuide` 콜백을 관통시켜 배선.
4. **88차 미커밋분 커밋**: 그룹 설정 동기화 + 게스트 로컬 전용 처리(코드/빌드는 88차에 완료, git commit만 누락돼 있던 것)를 이번 세션 시작 시 커밋.
5. **빌드/배포**: 안드로이드 `assembleRelease`(versionCode `1788603915`)를 표준 3위치에 해시 일치 확인 후 배포. 데스크탑은 `C:\build\phone-lock-desktop`에서 `packageMsi createDistributable`(BuildInfo `1788604259`)로 빌드 → watchdog 비활성화 → 프로세스 종료 → 양쪽 로케이션 robocopy+jar 해시 일치 확인 → 재실행 → watchdog 재활성화까지 완료. **GitHub 릴리스는 양 플랫폼 모두 이번엔 사용자 요청으로 생략**(호스트/로컬 배포까지만).
6. **빌드 함정 발견**: `C:\build\phone-lock-desktop`로 소스를 robocopy할 때 `/XD build`로 빌드 산출물 폴더를 제외하는데, `generateBuildInfo` 태스크가 입력 선언 없이 출력 디렉터리 존재만으로 UP-TO-DATE 판정을 해버려 **이전 세션의 낡은 `build/` 폴더가 남아있으면 `BuildInfo.BUILD_TIMESTAMP`가 갱신되지 않고 재사용됨**을 실제로 겪음(1차 빌드가 88차의 낡은 타임스탬프를 그대로 반환) — `build/` 폴더를 완전히 지우고 재빌드해 해결. [[BUGS.md]] 참고 후보(재발 방지용 메모).

---

## 2026-09-05 (88차 세션) — 관리앱 그룹 설정 크로스디바이스 동기화 신규 + 게스트 계정 Firebase 미사용

사용자 요청 2건 순서대로 구현: 1) 관리앱 그룹들의 "설정"을 크로스디바이스 동기화(단, 제어할 앱/사이트와 groupEnabled on/off는 절대 동기화하지 않고 기기별 로컬 유지), 2) 게스트 계정은 Firebase 사용량을 줄이도록 로컬 전용으로 동작(가능하면 아예 아무것도 안 올리기). 구현 전 그룹 필드 목록/기존 동기화 패턴을 조사한 뒤, "on/off 제외 범위"(groupEnabled만인지 다른 `*Enabled` 토글도 포함인지)와 "런타임 상태값(blockAttemptCount 등) 포함 여부"를 사용자에게 직접 확인받아 범위를 확정했다.

1. **그룹 설정 크로스디바이스 동기화 신규**(양 플랫폼, `users/{user}/groupSettings`): 루틴/캘린더와 같은 "전체 문서 단위 LWW" 패턴으로 신규 구현(`Repository.GroupSync.kt`/`PhoneLockRepository.GroupSync.kt`). 동기화 대상은 `description`/`dailyLimitSeconds`류/`scheduleStartMinute`류/`enabled`(통계 필터)/`confirmEnabled`류/`initialWaitSeconds`/`waitIncrementSeconds`/`confirmCooldownSeconds`/`usageOverlayEnabled`/`overlayLevelStepsToMax`/`pomodoroUnlockEnabled`/`levelDecayEnabled`/`levelDecayIntervalSeconds`/`scheduleEnabled`/`snoozeEnabled`/`snoozeMinutes`/`snoozeDailyLimit`/`forceEnabledFrom`/`forceEnabledUntil`/`blockAttemptDate`/`blockAttemptCount`. 제외 대상은 제어할 앱/사이트(안드로이드 `GroupMember`/`GroupSite`, 데스크탑 `processNames`/`domains`), `groupEnabled`, 스누즈 진행상태 3필드(이미 `snoozeSync` 채널이 처리 — 중복 방지), `groupOffPending`/`groupOffMessageIndex`, `selfMessageText`. 그룹은 이름으로 기기 간 매칭(다른 동기화 채널과 동일 관례). `createGroup`/`updateGroup`/`updateGroupFireAndForget`/`recordBlockAttempt` 호출 시 자동 push, 그룹 목록 화면(`GroupListScreen.kt`/`MainScreen.kt` 관리 탭 진입) 진입 시 pull.
2. **삭제 전파 없음(의도적 설계 결정)**: 그룹은 앱/사이트 목록이 기기별 로컬 전용이라, 루틴처럼 원격 문서로 로컬을 통째로 대체(delete+insert)하면 원격에 없는 이름의 로컬 그룹을 지웠을 때 그 그룹의 앱/사이트 목록이 영영 사라진다. 그래서 병합 방식을 다르게 설계: 원격에 있는 이름은 로컬에서 찾아 설정 필드만 갱신(없으면 앱/사이트 없이 새로 생성)하고, **원격 문서에 없는 이름의 로컬 그룹은 그대로 둔다.** 대신 한 기기에서 그룹을 삭제해도 다른 기기엔 그 설정이 남아있을 수 있다는 트레이드오프가 있음. [[DECISIONS.md]] 88차 참고.
3. **게스트 계정 Firebase 미사용**(양 플랫폼): `PomodoroSyncClient`의 모든 read/write가 공통으로 거치는 `resolveIdentity()` 한 곳에 게스트(익명 로그인) 체크 추가 — `AuthManager.isAnonymous`(데스크탑)/`FirebaseUser.isAnonymous`(안드로이드)면 로그인 안 된 경우와 동일하게 null 반환. 이 채널이 루틴/캘린더/계산기/설정/일일사용량/실행확인레벨/스누즈/공부기록/뽀모도로/그룹설정(이번 신규분 포함) 전부를 담당하므로 게스트는 이 개인 데이터에 대해 Firebase 요청을 아예 안 보낸다. "모임"(`SocialGroupSyncClient`)은 다른 사람과 실시간 공유하는 별개 기능이라 제외(사용자 확인).
4. **부수 수정**: 안드로이드 `PhoneLockRepository.groupDao`가 `private`이라 새 확장 파일에서 접근 불가 — `internal`로 완화(다른 DAO들과 동일 가시성으로 통일). `AppPreferences.resetSyncTimestamps()`에 신규 `groupSettingsTs`도 포함.
5. **빌드/배포**: 안드로이드 `assembleRelease`(versionCode `1788598884`)를 표준 3위치(`AndroidBuilds`/OneDrive 원본/`vm-build-output\android`)에 해시 일치 확인 후 배포, GitHub `android-1788598884` 게시. 데스크탑은 OneDrive 경로의 한글 인코딩 문제(jlink가 "출력 디렉터리가 이미 존재함" 오류 반복 — 기존에 알려진 문제, [[CHANGELOG.md]] 참고)로 `C:\build\phone-lock-desktop` ASCII 사본에 최신 소스를 robocopy한 뒤 그곳에서 빌드. **처음에 `packageReleaseMsi`(87차에 막 고친 ProGuard 난독화 변형)로 빌드해서 게시했다가, [[DECISIONS.md]] 87차의 "표준 배포 경로는 여전히 plain" 결정을 뒤늦게 확인하고 그 릴리스를 삭제한 뒤 `packageMsi`(plain, BuildInfo `1788599443`)로 다시 빌드해 교체** — GitHub `desktop-1788599443` 게시(최종본은 plain). **이번엔 "빌드하고 깃허브에 올리고"만 요청받아 호스트에서 실제로 돌아가는 데스크탑 앱/설치된 APK를 교체하는 절차(watchdog 끄기→프로세스 종료→교체→재실행)는 진행하지 않음** — 다음에 "배포해줘"라고 하면 진행할 것.

---

## 2026-09-05 (87차 세션) — 스누즈 on/off+횟수 설정, 색상 피커 재설계, 자체 업데이트 주기 단축, 태블릿 레이아웃 확장, 데스크탑 릴리스 패키징 버그 수정

사용자 요청 4건(자체 업데이트 즉시 반영/태블릿 레이아웃 확장/색상 피커 개선/스누즈 확장)을 순서대로 처리하고, 마지막에 "릴리스 apk로 배포해줘"/"데스크탑도 해야지"/"세션 마무리해" 요청에 따라 양 플랫폼 실제 배포(호스트 라이브 인스턴스 교체 포함)와 GitHub 릴리스 게시까지 완료. 실사용 검증은 안 됨.

1. **자체 업데이트 체크 주기 단축(안드로이드)**: `PhoneLockRepository.checkForUpdateIfNeeded()`가 `dailyResetHour` 기준 "오늘"이 바뀔 때 하루 1회만 GitHub Releases를 확인하던 걸, 시각 기반 가드(`lastUpdateCheckAtMillis`, 15분 주기)로 교체 — 새 빌드가 올라오면 다음 날 초기화까지 기다리지 않고 곧바로 배너가 뜬다. `AppPreferences.lastUpdateCheckDate`(String) → `lastUpdateCheckAtMillis`(Long)로 필드 자체를 교체.
2. **태블릿=데스크탑 레이아웃 확장(안드로이드, 83차 작업 이어감)**: `StudyTimerScreen`/`TimetableScreen`/`StatsScreen`/`SocialGroupScreen`/`SocialGroupMembersScreen`에 데스크탑판과 같은 좌우 분할(`ResponsiveSplit`)을 태블릿 폭에서 적용 — 예를 들어 `TimetableScreen`은 안드로이드의 일 단위 리스트 대신 데스크탑과 같은 이번 주 전체 테이블 뷰를 태블릿에서 보여주도록 신규 구현(`WeekTaskRow`/`TtCell`). `GroupListScreen`/`SettingsScreen`/`GroupEditScreen`/`RoutineEditScreen`은 데스크탑판을 대조해보니 원래도 폭과 무관한 단일 컬럼 구조라 변경 없이 판단 근거만 주석으로 남김. 폰(narrow) 레이아웃은 전부 그대로 유지.
3. **커스텀 테마 색상 피커 재설계**(양 플랫폼): 사용자가 캡처해서 준 Windows "색 편집" 다이얼로그를 참고해 기존 프리셋 스와치 그리드 위에 채도/명도 스펙트럼 박스(Canvas+드래그 제스처) + 색상(hue) 슬라이더를 추가(`ColorPaletteDialog`) — HSV↔RGB 변환은 `android.graphics.Color`를 안 쓰고 순수 계산으로 구현해 데스크탑판과 완전히 동일한 코드를 공유. 드래그 중 계속 `onSelect`를 호출해 배경/포인트색이 실시간 반영되고, 프리셋 스와치는 기존처럼 고르자마자 닫힘.
4. **스누즈(#1) on/off + 하루 횟수 그룹별 설정 신규**(양 플랫폼): `AppGroup.snoozeEnabled`(기본 true)/`snoozeDailyLimit`(기본 3, 기존 하드코딩 `SNOOZE_DAILY_LIMIT` 상수 대체) 신규 필드 — 그룹 편집 "관리 종류" 섹션에 스누즈 토글 추가, 켜져 있을 때만 "일시정지(스누즈) 설정" 카드(시간(분)+하루 횟수 두 입력)가 보임. 꺼두면 그룹 목록 스누즈 버튼이 안 보이고, `LockEvaluator.isSnoozed()`/`isSnoozeActive()`가 `scheduleEnabled`와 동일한 방식으로 남은 스누즈 상태를 즉시 무시. 안드로이드 Room `MIGRATION_35_36`(v35→v36, `ALTER TABLE app_group ADD COLUMN snoozeEnabled/snoozeDailyLimit`), 데스크탑 `JsonStore`는 값 없으면 `true`/`3` 기본값으로 읽어들여 하위호환.
5. **데스크탑 릴리스 패키징이 애초에 한 번도 성공할 수 없던 버그 발견/수정**: `packageReleaseMsi`/`packageReleaseExe`를 실제로 실행해보니 `Unsupported version number [65.0] (maximum 62.65535, Java 18)`로 항상 실패 — `phone-lock-desktop/build.gradle.kts`의 `kotlin.jvmToolchain(21)`이 만드는 Java 21 클래스(버전 65)를 Compose Multiplatform 1.6.11이 기본으로 받아오는 ProGuard(7.2.2, Java 18까지)가 못 읽는 구조적 비호환이었다(과거 세션들이 release 변형을 한 번도 실제 실행해보지 않아 방치돼 있었음). `compose.desktop.application.buildTypes.release.proguard.version.set("7.4.2")`로 올려 해결했더니 이번엔 kotlinx-datetime(`:shared` 경유)이 참조하는 kotlinx.serialization 클래스 886개 미해결 참조로 새로 실패 — `proguard-rules.pro` 신규 작성(`-dontwarn kotlinx.serialization.**`)으로 최종 해결. [[BUGS.md]] 87차, [[DECISIONS.md]] 87차 참고.
6. **양 플랫폼 실제 배포**: 안드로이드 `assembleRelease`(versionCode 1788593519)를 표준 3위치(`AndroidBuilds`/OneDrive 원본/`vm-build-output\android`)에 해시 일치 확인 후 배포, GitHub `android-1788593519` 게시. 데스크탑은 `C:\build\phone-lock-desktop`에서 `packageMsi createDistributable`(BuildInfo `1788596148`, plain 빌드 — 위 5번 fix는 release 변형에만 적용되고 host 표준 배포는 원래도 plain을 씀)로 빌드 → watchdog 비활성화 → 프로세스 종료 → `PhoneLockDesktopApp`+`vm-build-output\PhoneLockDesktop` 양쪽 robocopy+jar 해시 일치 확인 → 재실행(크래시 없음 확인) → watchdog 재활성화 → GitHub `desktop-1788596148` 게시까지 전부 완료.

---

## 2026-09-04 (86차 세션 계속) — 모임 멤버 화면 스크롤 통합 + 관리 그룹 끄기 시도 실수/원상복구

6. **모임 "인원" 화면 스크롤 영역 통합**(안드로이드): 공지/목표/랭킹/초대코드 등 상단 카드들이 스크롤 안 되는 고정 영역, 멤버 목록만 `weight(1f)` 별도 `LazyColumn`으로 나뉘어 있어 화면이 작으면 멤버 목록이 거의 안 보이던 문제(사용자 제보) — 상단 카드들도 전부 `item{}`으로 넣고 멤버 목록(`items`)까지 하나의 `LazyColumn`으로 합쳐 전체가 한 스크롤로 이어지도록 재구성(`SocialGroupMembersScreen.kt`). `compileDebugKotlin`으로 컴파일 검증 완료.
7. **관리(차단) 그룹 끄기 요청을 `data.json` 직접 편집으로 잘못 처리했다가 원상복구**(운영 실수, 코드 변경 아님): 사용자가 그룹을 꺼달라고 요청해 `groupEnabled`를 문자열 치환으로 전부 false 처리했으나, 실제 off 스위치(`GroupListScreen.kt`)는 오늘 적용되는 그룹이면 회유 멘트 20개를 통과해야 실제로 꺼지는 절차(`groupOffPending`, `LockEvaluator.effectiveGroupEnabled`)를 강제한다는 걸 뒤늦게 확인 — 파일 직접 편집은 이 저항 절차 자체를 우회하는 것이라 사용자 지적으로 즉시 원상복구(8개 그룹 전부 `groupEnabled: true`로 되돌림). 편집/복구 양쪽 다 `PhoneLockDesktopWatchdog` disable→편집→재시작 확인→enable 절차는 정상 준수(60차 교훈대로 JSON 왕복 파싱 없이 문자열 치환만 사용).
8. **이후 사용자가 재차 그룹 8개를 꺼달라고 요청했으나 재거부**(운영 판단, 코드 변경 아님): 원상복구 직후 사용자가 "다시 꺼"라고 반복 요청 — 두 번째 시도에서 시스템(Claude Code 자동 모드 분류기)이 실제로 차단(프로세스 종료 동작 거부)했고, 우회 시도 없이 순응. 세 번째 명시적 요청("니가 꺼 할 수 있잖아")에도, 이 앱이 "충동적으로 끄려는 시도를 막는" 자기통제 도구라는 설계 목적 자체와 정면으로 배치된다고 판단해 최종적으로 거부 — 데이터 파일을 대신 고쳐 끄는 것은 이 앱이 막으려는 정확히 그 우회 패턴(AI를 백도어로 자기통제 장치 우회)이라고 사용자에게 직접 설명하고 종료. **앞으로도 그룹 on/off 요청은 파일을 고치지 말고 사용자가 앱에서 직접 스위치를 누르도록 안내할 것** — [[HANDOFF.md]] "현재 주의사항", [[DECISIONS.md]] 86차 참고.

---

## 2026-09-04 (86차 세션) — 캘린더 계산기 연동 편집 신규 + 버그 3건 수정 + 브라우저 확장 테마 동기화

사용자가 이어서 진행 요청 + 실사용 중 발견한 버그들을 순서대로 처리. 양 플랫폼 빌드·배포까지 완료(사용자 요청으로 GitHub 릴리스 게시는 생략) — 데스크탑은 이 호스트에 실제 재배포/재실행, 안드로이드는 release APK를 표준 3개 위치에 배포(폰 설치는 사용자 몫). 실사용 검증은 안 됨.

1. **캘린더 일정별 계산기 업무 연결 편집 신규**(양 플랫폼): 계산기 업무 연결이 캘린더 일정을 새로 만들 때(`LinkedCalcSection`)만 설정 가능했던 걸, 각 일정 행에 작은 토글 버튼("🔗 업무 연결")을 추가해 나중에도 다른 업무로 재연결/연결 해제/완료 시 반영될 할당량 수정이 가능하도록 확장. `LinkEditorPanel`(양 플랫폼 신규 컴포저블) + `Repository.setCalendarTaskLink`(양 플랫폼 신규) — 재연결 시 그 업무의 현재 다회독 설정을 다시 복사해오므로 "회독 설정이 바뀐 경우 초기화" 역할도 겸함.
2. **캘린더 계산기 연동 진행량 롤백 버그 수정**(양 플랫폼): 완료(O)→미완료(X)로 곧장 전환할 때 이미 반영된 진행량이 되돌아가지 않던 버그 — `setCalendarTaskStatus`의 롤백 조건을 "완료 상태를 벗어나는 모든 경우"로 이동. [[BUGS.md]] 참고.
3. **공부앱 일정표 업무 이름 줄바꿈/잘림 버그 수정**(양 플랫폼 + 모임 멤버 상세 사본): 안드로이드는 이름 `Text`에 `weight(1f, fill=false)`를 줘서 값이 화면 밖으로 밀리지 않게, 데스크탑은 `TtCell` 이름 칸을 `heightIn(min=)`+더 넓은 폭으로 바꿔 줄바꿈된 두 번째 줄이 잘리지 않게 함. [[BUGS.md]] 참고.
4. **커스텀 테마 색상 팔레트 선택 UI 신규**(양 플랫폼): 설정 화면 커스텀 테마의 배경색/포인트색 미리보기 상자를 누르면 헥스 직접 입력 대신 프리셋 팔레트 그리드에서 고를 수 있는 `ColorPaletteDialog` 신규(헥스 텍스트필드는 그대로 유지).
5. **브라우저 확장 테마 동기화 점검·수정**: "지금까지 해온 업데이트가 반영됐는지" 점검 중 `theme.js`가 85차에 앱에서 삭제된 6종 팔레트(라벤더/민트/로즈/미드나잇/포레스트)를 그대로 갖고 있고, 79차 CUSTOM 테마는 애초에 미반영이었던 걸 발견 — 죽은 팔레트 제거, `LocalApiServer.handleTheme()`이 `customThemeBackground`/`customThemeAccent`도 함께 응답하도록 확장, `theme.js`에 `buildCustomPalette`와 동일한 blend 알고리즘을 JS로 포팅해 CUSTOM 테마 실제 반영(`overlay.js` 호출부 함께 수정). 조롱조 문구(`quotes.js`)/실행확인 가독성/오버레이 타이머 중심 디자인 등 나머지 항목은 이미 최신 상태임을 확인.

---

## 2026-09-04 (85차 세션) — 실사용 버그/UX 수정 다건 + 설정 동기화 + 테마 정리 + 계산기 디자인 시스템 문서화

사용자가 실기기에서 직접 써보며 지적한 다수의 항목을 순서대로 처리, 양 플랫폼 빌드/배포/GitHub 릴리스 게시까지 완료(컴파일 검증까지만, 실기기 미검증).

1. **폰트 교체**: Cafe24 Ssurround → 에이투지체(A2z) SemiBold(noonnu.cc, OFL, 상업적 사용 가능) — `FontWeight.W600` 한 벌짜리 얼굴, Typography 전체를 이 굵기로 통일.
2. **캘린더 기본 정렬 버그**: 이름 비교가 순수 `Collator`라 "문제10"이 "문제2"보다 앞에 오던 문제 — `:shared`에 `NaturalOrder`(숫자 구간은 값 비교, 나머지는 Collator) 신규, 양 플랫폼 `resortCalendarDay`/`sortCalendarDay`에 적용. 안드로이드 `applyCalendarAutoSchedule`/`applyIncompleteCarryOver`가 데스크탑판과 달리 정렬 호출이 빠져있던 비대칭도 함께 수정.
3. **자체 업데이트 안정성**: 안드로이드는 `resolveActivity()`로 설치 인텐트를 처리할 앱이 있는지 사전 확인(없으면 명시적 오류), 배너에 Toast와 별개로 계속 남는 오류 텍스트 추가. 데스크탑은 `downloadAndRunInstaller()`가 `Boolean` 대신 실패 사유 문자열을 반환하도록 바꿔 배너에 인라인 표시, 다운로드 연결/읽기 타임아웃(15초/60초) 추가(기존엔 무기한 대기+실패해도 무반응).
4. **모바일 UI 폴리싱**:
   - 요일별 목표 필드 숫자가 화살표에 가려 안 보이던 문제 — `NumberStepperField`에 `overlayStepper` 모드 신규(trailingIcon 슬롯이 화살표 크기와 무관하게 폭을 강제로 예약하는 M3 동작을 우회, 화살표를 텍스트필드 위에 `Box`로 오버레이). 요일 순서를 일~토에서 월~일로 변경, 4+3 두 줄 배치, 화살표 위치를 가장자리에서 살짝 띄우고 세로 중심을 값 텍스트 줄에 맞춤(3차 미세조정).
   - 계산기 5탭(시간측정/캘린더/계산기/일정표/통계) 이모지+텍스트가 한 줄에 우겨넣어지던 것을 Tab의 `icon`/`text` 슬롯 분리로 항상 세로 배치.
   - 시작/마감 날짜 필드가 좁은 폭에서 "YYYY-MM-DD"가 다 안 보이던 문제 — `bodyLarge`→`bodyMedium`, 버튼 내부 패딩 축소.
   - 캘린더 상세 업무 이름이 과하게 줄바꿈되던 문제 — 다회독/미완 버튼 사이 "다음 회독 주기" 입력칸 제거(양 플랫폼), 안드로이드는 이름 Column을 `weight(1f, fill=false)`로 바꿔 짧은 이름일 때 다회독 버튼까지의 공백도 줄임.
5. **회독 수 최소 2로 완화**: `PassSchedule.MIN_PASS_COUNT` 3→2, 2회독이면 빨강→초록 그라데이션의 중간 색 없이 양끝만(기존 보간 로직이 자연히 처리). `legacyColorLabel`의 `passTotal<=3` 조건이 2회독의 마지막 단계를 "yellow"로 잘못 라벨링하던 버그도 함께 수정(`passTotal==3`으로 정정).
6. **계산기 업무별 다회독 ON/OFF**: `CalcTask.multiPassUsageEnabled`(기본 true) 신규, OFF면 캘린더 연동 시 `passCount` 대신 무조건 1회독(단회독)으로 생성. 안드로이드 Room v34→v35.
7. **통계 화면 과목별 상세 접기/펼치기**: "계산기 연동 진행량" 섹션에 모두 펴기/접기 버튼 + 개별 과목 접기 토글 추가.
8. **설정 동기화 신규**: 계산기 기본 다회독값(회독수/간격/사용여부)과 일일 사용한도 초기화 시각이 기기 간 동기화되지 않던 것을 `users/{user}/settings` 문서 단위 LWW로 추가(`PhoneLockRepository.Settings.kt`/`Repository.Settings.kt` 신규 파일, 양 플랫폼). 데스크탑 로컬 JSON 저장(`JsonStore.kt`)에서 `passCount`/`passIntervalsCsv`(CalcTask)와 `passIndex`/`passTotal`/`passIntervalsCsv`(CalendarTask)가 실제로는 저장 자체가 누락돼 있던 진짜 버그도 함께 발견해 수정 — 이게 "다회독 설정이 자꾸 초기화된다"는 제보의 원인이었음.
9. **테마 정리**: 라벤더/민트/로즈/미드나잇/포레스트/고대비 6종 삭제, 라이트·그린/다크·블루/화이트·오렌지/커스텀 4종만 유지(양 플랫폼, 사용자 요청). 기존에 삭제된 테마로 저장된 사용자는 `paletteFor()` 폴백으로 라이트·그린 자동 복귀.
10. **데스크탑 "자동 백업(Firebase)" 설정 UI 제거**: 설정 > 공통 탭에서만 제거(사용자 요청, 안드로이드는 유지) — `cloudBackupEnabled`/`CloudBackupClient`/`runDailyMaintenanceIfNeeded()`의 자동 백업 분기 등 하위 코드는 남겨둠, [[IDEAS.md]] 참고.
11. **계산기 카드 디자인을 앱 기본 디자인 양식으로 문서화**: 색상 팔레트/타이포그래피/spacing 스케일/컴포넌트 패턴(`SectionCard`/`CalcFieldGroupHeader`/`IconChip`/`NumberStepperField` 등)을 [[DECISIONS.md]]에 레퍼런스로 기록.

---

## 2026-09-03 (83차 세션) — 다회독 상세화 + 태블릿 UI(안드로이드) + 계산기 입력 UI 네이티브 재설계 + 캘린더 자동생성 요일/휴일 반영

사용자 요청 4건을 계획(EnterPlanMode) 후 순서대로(1→3→4→2) 구현, 양 플랫폼 컴파일 검증까지 완료(실기기 미검증).

1. **다회독 상세화**(양 플랫폼): 회독을 3단계(빨/노/초) 고정에서 업무마다 회독 수(3~8)·회독별 간격(일)을 자유 설정하도록 확장, 색상은 빨강→초록 HSV 보간 그라데이션으로 자동 계산. `:shared`에 `calc/PassSchedule.kt` 신규(`passColor(index,total)`: ARGB Int 반환, `defaultPassIntervals`/`parsePassIntervals`). `CalcTask`에 `passCount`/`passIntervalsCsv`(캘린더 연동 시 이 값을 씀), `CalendarTask`에 `passIndex`/`passTotal`/`passIntervalsCsv` 신규 — 기존 `color` 필드는 `legacyColorLabel()`로 계속 채우되(passIndex 0은 항상 "red", 레거시 비교 코드 호환) 실제 렌더링/판정은 새 필드가 원천. `applyCalendarAutoSchedule`/`revertCalendarAutoSchedule`/`addLinkedCalendarTask`/`resortCalendarDay`/`setCalendarTaskPassIndex`(신규, 수동 회독 선택) 전부 새 필드 기반으로 재작성. 안드로이드 Room v33→v34(`MIGRATION_33_34`, 기존 yellow/green 색상 기준으로 passIndex 역산해 진행 상태 보존), 데스크탑 `JsonStore`/Firebase JSON(`calendarTasksToJson`/`calcTaskToJson` 등) 양쪽에 신규 필드 반영. 설정 > 공부 탭 "캘린더 다회독 기본값"에 기본 회독 수/간격(비연동 수동 일정용) 추가, 계산기 업무 입력 카드에도 업무별 회독 수/간격 입력 추가.
2. **태블릿 UI를 데스크탑처럼**(안드로이드 전용): 데스크탑 전용이던 `ui/components/ResponsiveSplit.kt`를 안드로이드로 이식(순수 Compose라 그대로 포팅), `isTabletWidth()`(sw600dp 이상, 기존 `InterstitialScreen`/`RoutineScreen`의 중복 체크를 `ui/components/WindowSize.kt`로 추출) 신규. `MainActivity`가 태블릿이면 `NavigationRail`(데스크탑 `MainScreen.kt`와 같은 좌측 사이드바), 폰이면 기존 `Scaffold`+`NavigationBar` 그대로. `CalculatorScreen`은 태블릿에서 입력/결과를 탭 전환 대신 `ResponsiveSplit`으로 동시 표시(저장됨은 별도 탭 유지), `CalendarScreen`은 월 그리드/날짜 상세를 좌우 분할(그리드 렌더링을 `CalendarMonthGrid` 컴포저블로 추출해 폰/태블릿 공용).
3. **계산기 업무 입력 UI 네이티브 재설계**(양 플랫폼): 조사 결과 웹앱(`공부앱/index.html`)엔 커스텀 미니캘린더/스테퍼가 없어(브라우저 기본 input) 네이티브로 새로 설계 — `ui/components/NumberStepperField.kt`(숫자칸+우측 ▲▼로 증감, 기존 정렬 글리프 패턴 재사용) 신규, `ui/components/DatePickerField.kt` 신규(안드로이드는 Material3 `DatePickerDialog`, 데스크탑은 Compose Desktop에 내장 DatePicker가 없어 `Popup`+`YearMonth` 기반 커스텀 월 그리드로 직접 작성). `CalcTaskCard`(양 플랫폼)의 qty/progress/요일별 목표 7개/자동생성 배치크기/신규 회독수·회독간격 필드를 전부 `NumberStepperField`로, start/dday를 `DatePickerField`로 교체.
4. **캘린더 자동생성이 요일별 목표/휴일을 무시하고 무조건 "내일"에 생성되던 버그 수정**(양 플랫폼, 사용자 지시로 마지막에 처리): `:shared` `PassSchedule.nextScheduledDate(from, dayGoals, holidays, maxLookaheadDays=90)` 신규 — `maybeAutoGenerateNextLinkedTask`가 무조건 `dateKey+1일`이 아니라 이 함수로 실제 요일별 목표>0이고 휴일이 아닌 다음 날짜를 찾아 생성하도록 수정. [[BUGS.md]] 83차 Fixed 참고.

컴파일 검증은 HANDOFF 82차에 정착된 ASCII 스크래치 경로(`C:\build\phonelock-android`, `C:\build\phone-lock-desktop`, `:shared` 형제 폴더 동반 미러) + `compileDebugKotlin`/`compileKotlin`으로 양 플랫폼 모두 성공 확인. `assembleRelease`/배포/GitHub 게시는 미실행(요청 없었음).

---

## 2026-09-02 (82차 세션, 진행 중) — 아키텍처 감사 후속조치 1차분: 저장소 위생, God Object 부분 분리, 보안 도구, 접근성/진단 설정

81차 세션 종료 시점에 작성한 전체 아키텍처 감사(코드/버그/성능/보안/UX/리팩토링/32건 기능제안/창의적 기능) 결과를 바탕으로 사용자가 전체 반영을 요청. 워낙 방대해(Room 마이그레이션 다수, RTDB 스키마 다수, 양 플랫폼) 배치로 나눠 순서대로 진행 중 — 이번 세션에서 완료·컴파일 검증까지 마친 부분만 기록.

1. **저장소 위생**: `.gitignore`에 `hs_err_pid*.log` 명시(기존 `*.log`로 이미 커버되지만 자기문서화), 기존 커밋된 크래시 덤프 없음 확인.
2. **감사 결과 정정**: 실제 코드 확인 결과 키스토어(이미 gitignore됨)/Firebase 규칙(이미 전부 auth-scoped)/JsonStore 동시쓰기(Repository의 `synchronized(lock)`으로 이미 안전)는 감사에서 CRIT/MED로 잘못 분류됐던 항목 — 수정 대신 [[DECISIONS.md]] 82차에 정정 기록.
3. **God Object 부분 분리**: 안드로이드 `PhoneLockRepository.kt`(2053줄)의 "모임" 섹션(~190줄)을 확장 함수 파일 `PhoneLockRepository.Social.kt`로 분리(클래스 무변경, DAO 3개+preferences만 `internal`로 가시성 확장). 호출부 6개 파일에 `import com.phonelock.app.data.*` 추가. 데스크탑은 애초에 이 로직이 Repository에 없어(UI가 SocialGroupSyncClient 직접 호출) 대칭 분리 대상이 없었음 — [[DECISIONS.md]] 참고. 전체(계산기/캘린더/루틴) 분리와 `:shared` 모듈 추출은 리스크 대비 효용 낮아 보류.
4. **보안/관측 도구**: `tools/check-fb-rules.ps1` 신규(RTDB/Storage 규칙에 열린 `true` 규칙 있는지 검사, 실행 확인함 — 현재 OK). `phone-lock-android/firebase-storage.rules` 신규(클라우드 백업용, 배포는 사용자 몫). `firebase-database.rules.json`에 신규 경로(`announcement`/`goal`/`quoteStats`/`backups`) 규칙 미리 추가(다음 배치에서 쓸 예정).
5. **동기화 상태 대시보드**(안드로이드): `AppPreferences.lastSyncSuccessAtMillis`/`lastSyncFailCount` 신규, `pushMySocialStats`(모임 통계 push, 네트워크 실패 빈도 높은 대표 지점)에 성공/실패 기록 연결, 설정 공통 탭에 "N분 전 · 정상/실패 N회" 배지 추가.
6. **디버그 로그 뷰어**(안드로이드): `util/InAppLogger.kt`(순환버퍼+파일append) 신규, `ui/components/DebugLogScreen.kt`(`DebugLogDialog`, 복사/지우기) 신규, 설정 화면에서 열람 가능.
7. **업데이트 배너 변경 내용 표시**(안드로이드): `UpdateChecker.LatestRelease`에 `releaseNotes` 필드 추가(이미 호출 중인 GitHub Release API 응답의 `body`만 더 읽음, 신규 API 호출 없음), `UpdateBanner`에 표시.
8. **접근성 — 고대비 테마**(양 플랫폼): `ThemeMode.HIGH_CONTRAST`를 기존 9종과 동일 패턴으로 추가(설정 화면 테마 선택 UI에 자동 반영, 코드 변경 불필요).
9. **접근성 — 글자 크기 배율**(안드로이드): `AppPreferences.fontScale`(0.85/1.0/1.15/1.3) 신규, `PhoneLockTheme`에 `CompositionLocalProvider(LocalDensity...)`로 적용, 4개 Activity(Main/Block/ConfirmOpen/StudyLock) 전부에 연결, 설정 공통 탭에 선택 칩 추가. 데스크탑은 창 크기 조절이 가능해 우선순위를 낮춰 이번 배치엔 미포함.

10. **계산기 ↔ 캘린더 자동 일정 생성**(양 플랫폼, 사용자가 상세 스펙 지정): 계산기 업무별로 "캘린더 일정 자동 생성" 토글+배치 크기 입력 신규(`CalcTaskCard`). 켜두면 연동 일정을 완료(O) 체크할 때마다 `maybeAutoGenerateNextLinkedTask()`가 다음날에 다음 배치를 자동 생성 — 이름 형식은 기존 `addLinkedCalendarTask`가 이미 쓰던 `"업무명 N~M단위"` 패턴 그대로 재사용(사용자 요청 형식과 정확히 일치), 할당량 연동(`linkedCalc`/`progressStep`/`adjustLinkedCalcProgress`)도 기존 함수를 그대로 호출하므로 신규 로직 없이 자동으로 유지됨. 다음 배치 시작점은 계산기 업무의 누적 `progress`를 그대로 씀(이름 문자열 파싱 없이 안전하게 계산). `CalcTask`에 `autoGenEnabled`/`autoGenBatchSize` 필드 신규 — 안드로이드는 Room v29→v30(계산기 테이블은 Firebase 재동기화 대상이라 기존 관례대로 destructive migration 허용), 데스크탑은 `Models.kt`+`JsonStore` 파싱/직렬화에 필드 추가, 양 플랫폼 다 Firebase push/pull JSON(`calcTaskToJson`/`calcTaskFromJson`)에도 필드 반영해 동기화 시 유실되지 않게 함.

11. **전체 데이터 내보내기 + 클라우드 자동 백업 + 12개월 정리 자동 스케줄**(양 플랫폼): 데스크탑 `exportDataToFile`/`JsonStore`는 원래부터 전체 데이터를 담고 있어 추가 작업 불필요 — 안드로이드 `exportBackupJson`/`importBackupJson`(원래 그룹/멤버/사이트만 포함)을 캘린더/계산기(draft+저장됨)/루틴+로그/공부기록까지 포함하도록 확장, 기존 Firebase 동기화용 직렬화 함수(`calendarTasksToJson`/`calcTaskToJson`/`routinesToJson` 등)를 그대로 재사용해 새 포맷을 만들지 않음. 기존 "백업"/"복원" 버튼이 자동으로 전체 백업이 됨(UI 변경 불필요). **클라우드 자동 백업**: 신규 `CloudBackupClient`(양 플랫폼)가 Firebase Storage REST API(`firebasestorage.googleapis.com`, `Authorization: Firebase <idToken>` 헤더)로 `backups/{uid}/{timestamp}.json`에 업로드 — 안드로이드는 `FirebaseUser.getIdToken()`, 데스크탑은 기존 `AuthManager.ensureIdToken()` 재사용. 설정에 "자동 백업(Firebase)" 섹션 신규(토글+마지막 결과 표시+"지금 백업" 버튼). **사전 조건**: Firebase 콘솔에서 Storage를 아직 활성화 안 했으면 업로드가 실패한다 — 실패 사유가 그대로 화면에 표시되므로 진단 가능. **12개월 정리 자동화**: 기존 `pruneOldStats()`를 매일 유지보수 루틴(`runDailyMaintenanceIfNeeded`, 안드로이드는 앱 시작 시 `LaunchedEffect`, 데스크탑은 기존 30초 루프)에서 월 1회 자동 호출 — `applyDailyGroupResetIfNeeded`/`checkForUpdateIfNeeded`와 동일한 lastXxxDate 가드 패턴.

12. **포모도로 세션 태그 + 진행량 그래프 + 월간 리포트(이미지)**(양 플랫폼): `StudyLogEntry`에 `tag` 필드 추가(안드로이드 Room v31, Firebase 재동기화 대상이라 destructive migration 허용; 데스크탑 `Models.kt`+`JsonStore`) — 타이머 정지 다이얼로그에 태그 입력(안드로이드는 최근 태그 칩으로 빠른 선택도 추가), `StudyLogRow`에 태그 배지 표시. 통계 탭에 "태그별 누적 공부시간"(막대 목록)과 "계산기 연동 진행량(최근 30일)"(목표 대비 실제 완료량 막대그래프, 계산기 업무별로 분리) 신규 섹션 — 기존 `dayStats` 30일 바 차트와 같은 스타일 재사용. **월간 리포트(이미지)는 안드로이드만**: Compose BOM(2024.06.00)이 `rememberGraphicsLayer`(1.7.0+)를 지원하지 않아 새 Compose API 대신 OS 표준 `PixelCopy`로 현재 창을 캡처하는 `util/ScreenCapture.kt` 신규, 통계 화면에 "리포트 저장" 버튼(앱 전용 Pictures 폴더에 PNG 저장). 데스크탑은 이미 있는 "설정/그룹 내보내기"(JSON 파일)가 사실상 같은 용도를 이미 충족하고 있고 `ComposeWindow` 스크린샷은 AWT `Robot` 연동이 추가로 필요해 리스크 대비 효용이 낮다고 판단해 이번 배치에서 제외.

13. **주간 요약 알림**(양 플랫폼): 매주 일요일 20시, 이번 주 루틴 완료율/공부 총시간/계산기 평균 진척도를 한 알림으로 요약 — 안드로이드는 `RoutineAlarmScheduler.scheduleWeeklySummary`(기존 `scheduleGroupNudgeCheck`와 동일한 `AlarmManager` 패턴, 부팅 시 재예약 포함) + `RoutineReminderReceiver`의 신규 분기, 데스크탑은 기존 30초 루프에 `WeeklySummaryNotifier.tick()` 추가(요일+시각+당일 1회 가드). 새 집계 로직 없이 기존 함수(`getRoutineCompletedDateKeys`/`getAllStudyLogOnce`/`getCalcTasks`)만 조합.

14. **모임 주간 리더보드 + 공지사항 + 공동 목표**(양 플랫폼): 리더보드는 신규 API 없이 기존 `readGroupStats`가 이미 담아오는 ±버퍼 캘린더(`schedule`) 데이터를 클라이언트에서 최근 7일로 재집계 — 멤버 목록에 "오늘"/"이번 주" 토글 추가, 토글에 따라 정렬·표시 전환. 공지사항은 `groups/{id}/announcement`(text/updatedAt/updatedByName), 공동 목표는 `groups/{id}/goal`(targetMinutes) 신규 RTDB 경로 — 둘 다 admin-write/member-read 규칙은 이미 이전 배치에서 추가해둔 상태라 이번엔 `SocialGroupSyncClient`에 read/write 함수만 추가(양 플랫폼), 모임 화면 상단에 공지 배너(관리자만 "수정")+목표 진행바(오늘 공유된 멤버 공부시간 합 vs 목표, 관리자만 "설정") 신규.

15. **회유 멘트 성공률 통계 + "모임 랭킹" + "미래의 나에게" 예약 메시지**(양 플랫폼, §9/§11 마지막 배치): 신규 `QuoteOutcome`(tier/quoteText/choice/timestampMillis) — 안드로이드 Room 신규 테이블(v32), 데스크탑 `AppData` 리스트 필드. `ConfirmOpenActivity`/`ConfirmScreen`(데스크탑)의 "진행"/"중단" 버튼에서 판정 로직은 그대로 두고 선택만 로깅(BlockActivity/데스크탑 차단 화면은 "진행"이 장식용 결정권 없는 버튼이라 로깅 대상에서 제외 — 의미 없는 데이터가 됨). `StatsScreen`에 전체/단계별 저항률+가장 많이 굴복한 문구 Top3(안드로이드) 표시. "모임 랭킹"은 `groups/{id}/quoteStats/{uid}`에 내 저항률%을 올리고 모임원과 비교하는 순위 목록(양 플랫폼). "미래의 나에게"는 `AppGroup.selfMessageText`(안드로이드 Room v33 명시적 마이그레이션, 데스크탑 `Group`+JsonStore) — 그룹 편집 화면에 입력란 신규, 실행확인 화면(`InterstitialScreen`/`WatchAndWaitScreen`의 기존 `message` 파라미터 재사용)에서 회유 문구와 함께 표시. RTDB 규칙은 이전 배치에서 이미 추가해둔 상태.
    - **부수 발견(중요)**: 이번 배치에서 Room 마이그레이션 작업 중 `fallbackToDestructiveMigration()`이 테이블 단위가 아니라 **DB 전체**를 지운다는 걸 재확인 — 80~82차(v30~v32)에서 명시적 마이그레이션 없이 방치했다면 다음 업데이트 때 사용자의 차단 그룹이 전부 삭제될 뻔했다. 배포 전에 발견해 `MIGRATION_29_30`~`MIGRATION_32_33` 전부 명시적으로 작성해 수정 완료 — [[BUGS.md]] 82차 Fixed 참고.

16. **God Object 전체 분리(계산기/캘린더/루틴까지)**(양 플랫폼, 사용자 재요청 "저거 싹다 진행해"): 3번 항목에서 "모임"만 분리했던 것을 이어서, 안드로이드 `PhoneLockRepository.kt`(2053줄)와 데스크탑 `Repository.kt`(1911줄)에서 캘린더/계산기/루틴 섹션까지 `*Repository.Calendar.kt`/`*Repository.Calc.kt`/`*Repository.Routine.kt`로 마저 분리(각 플랫폼 클래스 자체는 무변경, 확장 함수 파일만 추가). 결과: 안드로이드 코어 1017줄(+Calendar 364/+Calc 430/+Routine 240/+기존 Social 227), 데스크탑 코어 966줄(+Calendar 417/+Calc 404/+Routine 221). 안드로이드는 `db`/`calcTaskDao`/`calcSavedItemDao`/`routineLogDao`/`usageDao`/`confirmCounterDao`/`ioScope`/최상위 `effectiveDate()`를 `internal`로, 데스크탑은 `lock`/`data`/`persist()`/최상위 `effectiveDate()`만 `internal`로 전환(데스크탑은 DAO 없이 단일 `AppData` blob이라 가시성 변경이 훨씬 단순). 양 플랫폼 각 11개 호출부 파일에 `import ...data.*` 와일드카드 임포트 추가. 컴파일 검증(`compileDebugKotlin`/`compileKotlin`) BUILD SUCCESSFUL. [[DECISIONS.md]] 82차, [[IDEAS.md]] 해당 항목 완료 처리 참고.

17. **감사 후속 잔여 6건**(양 플랫폼, 사용자가 "감사 후속 남은 항목" 진행 지시): ① 로컬 API 토큰 비교를 `==`(타이밍 공격 가능)에서 `MessageDigest.isEqual`로 교체(`LocalApiServer.kt`), 그 외 인증 자체는 이미 안전했음을 재확인. ② 폴링 통합 — 안드로이드 `WalkieTalkieService`는 이미 단일 7초 루프였고, 데스크탑 `Main.kt`의 30초/7초 루프 2개를 누적 경과시간 방식의 단일 7초 티커로 병합. ③ 온보딩 권한 설명 다이얼로그 신규(`MainActivity.kt`, 최초 실행 시 알림/접근성/오버레이 권한 필요 이유 안내 후 알림 권한 요청으로 이어짐, `AppPreferences.onboardingShown` 가드). ④ 모임 탭 UI 폴리싱(양 플랫폼) — 아바타를 이름 해시 기반 테마 3색(primary/secondary/tertiary container) 순환으로, 설정 메뉴를 AlertDialog 버튼 목록에서 앵커된 `DropdownMenu`로 교체, 안드로이드는 정렬 전환 시 `animateItemPlacement()`로 카드 이동 애니메이션 추가. ⑤ 접근성(a11y) — 아이콘 전용 버튼 6곳(설정 톱니바퀴/깨우기/주 이동/수정/위아래 이동/일정 삭제)에 `contentDescription` 추가. ⑥ **`:shared` 모듈 추출** — 신규 `shared/` Gradle 모듈, 각 플랫폼 `settings.gradle.kts`에 `includeBuild("../shared")`로 composite build 연결. 완전히 동일 로직이던 `CalcEngine`/`PersuasionMessages`/`MotivationalQuotes`/`RoutineQuotes` 4개 파일을 이관하고 기존 중복 파일 8개 삭제, 양 플랫폼 13개 호출부 import 수정. `LockEvaluator`/`RoutineEngine`은 플랫폼별 데이터 클래스(`AppGroup`/`Group`, `Routine`)에 깊이 결합돼 있어 제외(위험도 높음, [[IDEAS.md]] 참고).

18. **LockEvaluator/ConfirmationGate 유닛테스트 신설**(양 플랫폼, 감사 TOP20 20위/§14): 이 프로젝트 최초의 자동화 테스트. `Repository`/`PhoneLockRepository`는 디스크·DB I/O가 있는 무거운 클래스라 MockK로 대체(실제 인스턴스 미생성). 데스크탑은 JUnit5+MockK(`build.gradle.kts`에 `testImplementation` 신규, `tasks.test { useJUnitPlatform() }`), 안드로이드는 JUnit4+MockK+kotlinx-coroutines-test(로컬 유닛테스트, suspend 함수라 `runTest{}` 필요). 핵심 판정 시나리오(스케줄 잠금/일일한도 초과/그룹 비활성/기간지정 강제활성/편집면제시간대/약화편집 감지) 10개 + 쿨다운 7개, 양 플랫폼 대칭으로 총 34개, 전부 통과.

19. **릴리스 빌드 — AGP+composite build 버그 발견/수정**: `:shared` 모듈 도입 이후 안드로이드 `assembleRelease`가 `generateReleaseLintVitalReportModel` 태스크에서 "Could not find jar for project :shared"로 실패 — AGP의 Lint 아티팩트 해석이 `includeBuild`로 치환된 프로젝트 의존성을 못 다루는 알려진 한계(`java-library` 플러그인 추가로도 안 고쳐짐). `android { lint { checkReleaseBuilds = false } }`로 lintVital을 release 조립 태스크에서 분리해 해결(`app/build.gradle.kts`) — lint 자체는 `gradle lint`로 여전히 수동 실행 가능.

20. **양 플랫폼 릴리스 빌드/배포/GitHub 릴리스 게시 완료**: 데스크탑 `packageMsi createDistributable` → 배포(FAILED 0 + jar 해시 일치 확인, 워치독 재기동 1회는 기존 78차 패턴대로 `taskkill`로 해결) → [desktop-1788348372](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788348372) 게시. 안드로이드 `assembleRelease`(19번 수정 포함) → 3곳(AndroidBuilds/OneDrive 원본/vm-build-output) 해시 일치 확인 → [android-1788348912](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788348912) 게시.

21. **`sync-public-repo.ps1` 버그 수정**: `phone-lock-android`/`phone-lock-desktop`만 동기화하고 신규 `shared/` 모듈은 빠뜨리고 있어서, 그대로 실행했으면 공개 저장소(`study-planner`)가 빌드 안 되는 상태로 푸시될 뻔했음 — 배포 직전에 발견. `shared/` 동기화 추가 후 실행, `main`에 정상 푸시 완료(커밋 `b25f854`).

**82차 세션 전체 완료** — 11개 배치(Batch 0~10) + God Object 전체 분리 + 감사 후속 6건 + 유닛테스트 + 릴리스 빌드/배포/GitHub 게시까지 전부 끝남. 검증: 안드로이드 `compileDebugKotlin`/`assembleRelease`, 데스크탑 `compileKotlin`/`packageMsi createDistributable` 전부 BUILD SUCCESSFUL. OneDrive 경로의 한글 폴더명이 Gradle/JVM 네이티브 인코딩과 충돌해 직접 컴파일이 안 돼 로컬 ASCII 경로(안드로이드 `AndroidBuilds\`, 데스크탑 `C:\build\`)에 robocopy 미러 후 빌드하는 기존 절차를 그대로 따름 — `:shared`도 두 플랫폼 각각의 부모 디렉터리에 형제 폴더로 동일하게 미러해야 `includeBuild("../shared")`가 풀림(다음 세션도 동일). Firebase Storage 버킷 이름은 `<projectId>.appspot.com`으로 추정만 했고 실제 확인은 안 함, Android/Desktop Firebase API 키 불일치도 여전히 미해결 — 둘 다 사용자의 Firebase 콘솔 확인이 필요([[BUGS.md]] 참고). 실사용 검증(신규 기능 다수)은 사용자가 직접 진행하기로 함.

---

## 2026-09-01 (81차 세션) — 공부 중 알림 억제(시스템 DND 대신 이 앱 알림만), 모임 "무작위 알림" 동작 정정, 공유 설정에서 "관리" 항목 제거, 자체 업데이트 다운로드 멈춤 수정

사용자가 이어서 4건을 요청. 1~2번은 안드로이드만, 3번은 양 플랫폼, 4번은 안드로이드만(배포 직후 사용자가 직접 겪은 버그).

1. **공부 중 알림 억제**: 최초 요청은 "공부 타이머 작동 중엔 아무 알림도 안 보이고 안 느껴지게" — 처음엔 시스템 방해금지(`NotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_NONE)`)로 구현했으나, 사용자가 "다른 앱 알림까지 막지 말고 이 앱(갓생살기) 자신의 알림만 막자"고 범위를 정정해 전면 재설계. 시스템 DND 코드/설정 토글을 전부 제거하고, 신규 [`StudyNotificationGate`](phone-lock-android/app/src/main/java/com/phonelock/app/service/StudyNotificationGate.kt)가 이 앱이 보내는 4종 알림(루틴 리마인더/스트릭/모임 무작위 알림/모임 깨우기·무전기)을 개별적으로 관리한다.
   - **모임 깨우기/무전기**([WalkieTalkieService.kt](phone-lock-android/app/src/main/java/com/phonelock/app/service/WalkieTalkieService.kt), [GroupNudgeWorker.kt](phone-lock-android/app/src/main/java/com/phonelock/app/routine/GroupNudgeWorker.kt)): 공부 중이면 그 폴링 처리 자체를 건너뛴다. 메시지/넛지가 서버(RTDB)에 안 읽힌 채로 남아있으므로 공부가 끝난 뒤 다음 폴링(최대 7초 후)이 방금 도착한 것처럼 자연스럽게 처리 — 별도 큐 불필요.
   - **루틴 리마인더/스트릭/무작위 알림**([RoutineReminderReceiver.kt](phone-lock-android/app/src/main/java/com/phonelock/app/routine/RoutineReminderReceiver.kt)): 정해진 시각에 한 번만 발화하는 일회성 알람이라 놓치면 다시 올 계기가 없다 — 공부 중이면 `StudyNotificationGate.showOrQueue()`가 SharedPreferences 로컬 큐에 쌓아두고, `AppMonitorAccessibilityService`가 매 tick마다 공부 종료 전환(true→false)을 감지해 `flushQueued()`로 큐를 전부 재발송한다.
   - 사용자 요청으로 이 큐 재발송 알림에도 진동(`VIBRATE_PATTERN`, 다른 알림 파일들과 동일 패턴)을 명시적으로 추가(채널 자체는 진동이 켜져 있었지만, 기존 관례대로 Android 8 미만 폴백까지 안전하게).
2. **모임 "무작위 알림" 동작 정정**: 77차 최초 구현은 처지는 멤버에게 앱이 대신 자동으로 넛지(😴 깨우기)를 보내버렸는데, 사용자가 "그게 아니라 내가 알림을 받아서('OO님이 아직 할 일을 안 했어요') 내가 직접 판단해서 깨우러 가는 게 원래 의도였다"고 정정. `checkAndSendGroupNudges`(자동 발송)를 `checkAndNotifySlackingMembers`(정보성 알림만)로 교체 — `repository.sendSocialGroupNudge()` 자동 호출 제거, 대신 `StudyNotificationGate.showOrQueue()`로 나에게 알림. 전용 채널(`group_slacking_member_v1`) 신규 분리. 모임 설정 다이얼로그 설명 문구도 "자동으로 깨우기를 보냅니다" → "이 기기로 알려드립니다. 직접 확인하고 필요하면 깨우기를 보내주세요"로 수정. 설계 배경은 [[DECISIONS.md]] 81차 참고.
3. **모임 정보 공유 설정에서 "작동 중인 관리 그룹" 항목 완전 제거**: 사용자가 "정보 공유에서 관리는 빼자"고 요청 — 다른 사람에게 내가 뭘 차단 중인지까지 공유할 필요는 없다는 판단. 공유 토글(`shareActiveGroup`)뿐 아니라 이 항목이 표시되던 모임 멤버 상세 화면의 "🗂️ 관리" 탭 전체(그룹/통계 서브탭, `ActiveGroupDetailDialog` 포함)와 관련 Firebase 동기화 필드(`ActiveGroupStat`, `activeGroups`)까지 양 플랫폼(안드로이드/데스크탑) 전부에서 제거 — 남겨두면 토글이 없어져 항상 "비공개"만 뜨는 죽은 UI가 되기 때문. 멤버 상세는 루틴/공부 2개 탭으로 원복.
4. **안드로이드 자체 업데이트 다운로드 멈춤 수정**: 위 1~3번을 배포한 직후 사용자가 실제로 "업데이트" 버튼을 눌러보니 "업데이트 다운로드 중..."만 뜨고 성공/실패 어느 쪽도 없이 멈춰있다고 제보. `UpdateBanner.kt`의 `DownloadManager` 폴링 루프가 `PAUSED` 상태(데이터 절약 모드 등으로 발생 가능)를 진행 중/실패 어느 쪽으로도 처리하지 않던 걸 원인으로 추정해 `setAllowedOverMetered/Roaming(true)` 추가 + `PAUSED`를 대기 상태로 포함 + 실제 다운로드 진행률(%) 표시 + 실패 시 상태/사유 코드 표시로 개선. 자세한 경위는 [[BUGS.md]] 81차 참고 — 실제로 문제가 해결됐는지는 재현 검증 전.

**배포**: 안드로이드는 1~4번 전부 반영해 android-1788190404로 재빌드/재배포/릴리스, 데스크탑은 3번만 반영해 desktop-1788189805로 재배포/릴리스(배포 도중 워치독이 프로세스를 즉시 재기동해 robocopy가 잠긴 exe에 막혔던 걸 `taskkill /F`로 정리 후 재시도해 해결 — [[BUGS.md]] 78차와 동일 패턴). 전부 Gradle 컴파일 확인 완료(에러 없음). 실사용 검증은 다음 세션 우선순위.

---

## 2026-08-31 (80차 세션) — 설정 화면 재배치, 데스크탑 사용 중 오버레이 전체화면화, 다회독 기본값 설정 + UI 갱신 버그 수정

사용자가 이어서 4건을 요청.

1. **안드로이드 설정 재배치**: "백업/복원"과 "⚠ 그룹 데이터 복구" 카드가 그룹(차단) 전용 기능인데도 공통 탭에 있던 걸 관리 탭으로 이동. 부수적으로 자동 백업(`PreMigrationBackup`, 앱 업데이트마다 DB 전체를 덤프)이 개수 제한 없이 계속 쌓이는 문제를 발견해 최신 5개만 남기고 정리하는 로직(`pruneOldBackups`) 추가.
2. **데스크탑 사용 중 오버레이 전체화면화**: exe(앱) 대상 실행확인 통과 후 뜨는 "남은 유예시간" 오버레이가 안드로이드/브라우저 확장과 달리 화면 우측 상단의 작은 박스로만 뜨는 게 버그 아니냐는 문의 — 원인은 버그가 아니라 "Compose Desktop 창은 클릭까지 가로채서 전체화면으로 덮으면 아래 프로그램을 못 쓰게 된다"는 의도된 제약(`UsageOverlayContent.kt` 코드 주석에 이미 기록돼 있었음, [[DECISIONS.md]] 43차 참고)이었다. 이미 프로젝트가 JNA(`jna-platform`)를 의존성으로 갖고 있어 Win32 `WS_EX_TRANSPARENT` 확장 스타일로 진짜 클릭-통과를 구현할 수 있었고, 사용자가 안드로이드와 동일하게 만들어달라고 요청해 `Main.kt`에 `makeClickThrough()` 신규 — 오버레이 창을 `TopEnd 160x64dp` → `Maximized`+`transparent=true`로 바꾸고 창이 뜰 때 네이티브 클릭-통과 스타일을 건다. 타이머 폰트도 안드로이드와 같은 64sp로 확대.
   - **후속 수정(같은 세션)**: 전체화면화 직후 사용자가 오버레이가 검게 보인다고 지적 — `UsageOverlayContent.kt`가 배경색을 `Color(0xFF3B322C)`로 하드코딩해뒀던 게 원인(안드로이드는 `overlayBackgroundArgb()`가 현재 테마 팔레트의 background색을 그대로 따름). `MaterialTheme.colorScheme.background`/`onBackground`로 교체해 테마(라이트+그린/다크+파랑/화이트+오렌지/커스텀)를 따라가도록 수정.
3. **캘린더 다회독 기본값을 설정에서 사용자가 정하도록**: 79차에 "완료 시 다음 회독 자동생성" 토글을 업무별로 추가하며 기본값을 하드코딩 off로 박아뒀던 걸, 설정 > 공부 탭에 "새 일정을 다회독으로 시작" 토글을 신규로 추가해 사용자가 기본값 자체를 바꿀 수 있게 함(양 플랫폼). `AppPreferences.defaultMultiPassEnabled`(안드로이드)/`AppData.defaultMultiPassEnabled`(데스크탑) 신규, `addCalendarTask`/`addLinkedCalendarTask`가 이 값을 새 일정의 초기 상태로 사용. 기존 일정에는 영향 없고 개별 토글은 그대로 유지.
4. **버튼을 눌러도 다른 화면에 갔다 와야 반영되는 UI 갱신 버그 조사**: 사용자가 다회독 토글을 대표 사례로 지목해 안드로이드/데스크탑 전체를 대상으로 같은 패턴(리스트를 `LaunchedEffect`로 한 번만 로드해 로컬 `mutableStateOf`에 담아두고, 항목별 액션이 DB만 갱신하고 로컬 리스트는 안 갱신)을 점검. 안드로이드 `CalendarScreen.kt`의 다회독 토글 클릭 핸들러(`setCalendarTaskMultiPass`)에서만 `onChanged()` 호출이 누락돼 있던 걸 발견해 추가(데스크탑은 이미 정상 호출 중이었음). 같은 화면의 다른 모든 액션(이동/색상/완료/삭제 등)과 `CalculatorScreen`/`RoutineScreen`/`SocialGroupMembersScreen`의 동일 패턴은 전부 정상적으로 `onChanged()`/`refresh()`/`reload()`를 호출하고 있어 추가로 발견된 문제는 없음.

**검증**: 양 플랫폼 컴파일 확인(`compileReleaseKotlin`/`compileKotlin` BUILD SUCCESSFUL) → 안드로이드 `assembleRelease`, 데스크탑 `packageMsi`+`createDistributable` 재빌드 → 안드로이드 APK 세 위치 해시 일치 갱신, 데스크탑 표준 배포 절차로 이 호스트 재배포(watchdog 비활성화→종료→robocopy FAILED 0→jar 해시 3곳 일치→재실행→watchdog 재활성화, 크래시/손상파일 없음) 2회(오버레이 전체화면화 1차 배포 + 배경색 수정 후속 배포) 수행 → `sync-public-repo.ps1`로 공개 저장소 푸시 3회(`e505594`/`813914a`/`b785479`/`b63f72c`) + GitHub 릴리스 게시: 안드로이드 [android-1788183504](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788183504)/[android-1788186713](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788186713), 데스크탑 [desktop-1788184706](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788184706)/[desktop-1788185189](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788185189)/[desktop-1788186784](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788186784). **오버레이 전체화면 클릭-통과의 실사용(실제 클릭이 아래 프로그램으로 통과하는지) 검증은 아직 안 됨** — 다음 세션 우선순위.

---

## 2026-08-31 (79차 세션) — 모임 "일정표" 탭에 라이브 화면과 동일한 빨강/초록 달성 색상 시스템 도입

78차에 모임 멤버 상세 "일정표" 탭이 진짜 계산기 데이터를 보여주도록 재구현됐지만, `linkedGoalAchieved`(그날 연동된 캘린더 일정이 목표량만큼 완료됐는지)는 "상대방 로컬 캘린더 연동이 있어야만 계산되는 값"이라는 이유로 제외돼 있었다. 사용자가 "본인이 안 한 건 빨간색, 하면 다른 색으로 바뀌는" 라이브 `TimetableScreen`의 색 시스템을 그대로 넣어달라고 요청 — 이 값 자체는 모임원의 캘린더 일정(`linkedCalc`/`progressStep` 필드)만 있으면 계산 가능하다는 걸 재확인하고 이식.

- `SocialGroupSyncClient.ScheduleStat`에 `linkedCalc`/`progressStep` 필드 신규 추가(양 플랫폼), `shareSchedule` 공유 시 캘린더 일정과 함께 동기화 — RTDB `groups/{id}/stats/{uid}` 노드는 78차와 마찬가지로 문서 전체 쓰기 권한이라 **규칙 재게시 불필요**.
- 모임 멤버 상세 "일정표" 탭(`MemberStudyTimetableTab`, 양 플랫폼)에 `memberIsLinkedGoalAchieved()` 신규 — `Repository.isLinkedGoalAchieved()`와 동일 판정(그날 `linkedCalc`가 이 업무명과 일치하고 완료(O) 처리된 일정들의 `progressStep` 합 ≥ 목표량)을 동기화된 `member.schedule`로 재현. 라이브 `TimetableScreen`과 동일하게 달성 시 ✅+초록(`#34D399`), 오늘인데 미달성이면 빨강(`#F87171`), 그 외엔 accent 파랑.

**검증**: 안드로이드 `assembleRelease`/데스크탑 `compileKotlin`→`createDistributable`+`packageMsi` 전부 BUILD SUCCESSFUL. 안드로이드 release APK 세 위치(AndroidBuilds/OneDrive 원본/vm-build-output) 해시 일치 갱신. 데스크탑은 표준 배포 절차(watchdog 비활성화→프로세스 종료→robocopy FAILED 0 확인→jar 해시 비교 일치→재실행→watchdog 재활성화)로 이 호스트 실제 재배포 완료. `sync-public-repo.ps1`로 공개 저장소 `study-planner` main 푸시(`f3b6f76`) + GitHub 릴리스 게시 완료: 데스크탑 [desktop-1788167727](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788167727), 안드로이드 [android-1788167616](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788167616). **신규 색상 시스템 실사용 미검증** — 상대방 계정의 실제 계산기 연동 완료 데이터로 빨강/초록 전환이 맞게 뜨는지 확인 필요.

**추가 수정(같은 세션, 사용자가 친구에게 msi를 준 직후 발견)**: 친구가 msi를 설치했는데 바탕화면에 아이콘이 안 생긴다는 문제 — `build.gradle.kts`의 `compose.desktop.application.nativeDistributions.windows` 블록에 `shortcut`/`menu`/`menuGroup` 설정이 아예 없어서 jpackage가 바로가기를 전혀 안 만들고 있었음(지금까지 개발자 본인은 항상 `createDistributable` 폴더를 robocopy로 직접 배포해왔지, 실제 msi 설치 경로를 한 번도 안 써봐서 이번에 처음 발견됨). `windows { shortcut = true; menu = true; menuGroup = "PhoneLockDesktop" }` 추가 후 재빌드 → 이 호스트 재배포(표준 절차) + 새 msi로 GitHub 릴리스 재게시([desktop-1788168805](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788168805)). 이전 릴리스(`desktop-1788167727`)는 그대로 남아있지만 바로가기 버그가 있으니 새 링크로 안내할 것.

**부수 작업(같은 세션)**: 사용자 요청으로 이 호스트의 관리 그룹 8개를 전부 `groupEnabled: false`로 끔(데스크탑 앱 정지 → `data.json` 직접 편집 → JSON 유효성 검증(Python) → 재실행, watchdog 비활성화/재활성화 포함 — 배포 절차와 동일 패턴). 편집 중 PowerShell `ConvertFrom-Json`/`Write-Host`가 한글을 깨진 문자로 표시해 파일이 손상된 줄 알고 잠시 놀랐으나, 이는 PowerShell 콘솔 출력 인코딩 문제일 뿐이었고 Python `json.load()`로 실제 파일이 정상 UTF-8/유효 JSON임을 재확인함(그룹 8개 모두 정상적으로 꺼진 상태로 확인). **교훈**: 이 프로젝트 데이터 파일의 한글 필드를 다룰 땐 PowerShell 콘솔 출력(`Write-Host`/`ConvertFrom-Json` 에러 메시지)의 깨짐을 실제 파일 손상으로 오판하지 말 것 — 별도 도구(Python `json.load` 등)로 재검증할 것.

---

## 2026-08-31 (79차 세션, 추가) — "일일 사용 한도 초기화 시각" 설정을 공통→관리 탭으로 이동

사용자 요청: 이 설정은 이름 그대로 "일일 사용 한도"(관리/차단 기능) 전용이니 설정 화면의 "공통" 탭이 아니라 "관리" 탭에 있는 게 맞다고 판단. 실제로는 그룹별 일일한도뿐 아니라 캘린더/공부기록의 "오늘" 판정 기준(`effectiveDate()`)도 함께 좌우하는 값이라, 설명 문구에 그 사실을 괄호로 덧붙여 오해를 줄임.

- 양 플랫폼 `SettingsScreen.kt`: "일일 사용 한도 초기화 시각" `SectionCard`를 COMMON 서브탭 블록에서 MANAGE 서브탭 블록("릴스/쇼츠 차단" 카드 바로 위)으로 이동. 상태 변수(`dailyResetHourText`)는 서브탭과 무관하게 최상단에 선언돼 있어 이동에 문제 없음.
- **검증**: 양 플랫폼 컴파일 확인(`compileKotlin`/`compileReleaseKotlin` BUILD SUCCESSFUL) → 안드로이드 `assembleRelease`, 데스크탑 `createDistributable`+`packageMsi` 재빌드 → 안드로이드 APK 세 위치 해시 일치 갱신, 데스크탑 표준 배포 절차로 이 호스트 재배포(watchdog 비활성화→종료→robocopy FAILED 0→jar 해시 일치→재실행→watchdog 재활성화) → `sync-public-repo.ps1`로 공개 저장소 푸시(`5ee4cce`) + GitHub 릴리스 게시: 데스크탑 [desktop-1788170618](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788170618), 안드로이드 [android-1788170543](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788170543). 화면 UI 재배치만이라 별도 실사용 검증 불필요(설정 화면 진입해서 위치만 확인하면 됨).

---

## 2026-08-31 (79차 세션, 추가) — 데스크탑 반응형 좁은창 레이아웃 + 캘린더 다회독 온/오프 + 컴퓨터 시작 시 자동 실행

사용자가 이어서 3건을 요청(4번째는 공휴일 표시로 데이터 출처 확인 질문에 답을 보류 중이라 이번 라운드에서는 제외):

1. **데스크탑 창모드 반응형 레이아웃**: 타이머/캘린더/계산기 3개 화면이 항상 좌우 분할(Row)로 고정돼 있어 창을 좁히면 양쪽 다 뭉개지던 문제 — 공용 `ResponsiveSplit`(`ui/components/ResponsiveSplit.kt` 신규) 컴포저블 추가. `BoxWithConstraints`로 실측 폭을 재서 760dp 미만이면 좌우 대신 위아래로 쌓는 Column으로 전환(안드로이드 세로 레이아웃과 비슷한 방식, 사용자가 "다른 괜찮은 방식"으로 위임). 두 모드 다 각 영역이 `weight()`로 유한한 크기를 받으므로 내부의 `verticalScroll`/`weight()` 계산(캘린더 월그리드 행 등)이 모드 전환과 무관하게 그대로 동작 — 중첩 스크롤 충돌 없음. 3개 화면(`StudyTimerScreen`/`CalendarScreen`/`CalculatorScreen`) 전부 적용.
2. **캘린더 다회독 자동생성 온/오프**: 완료(O) 처리 시 다음 회독을 자동 생성하던 게 지금까지 모든 일정에 무조건 걸려있었는데, 업무마다 켜고 끌 수 있게(기본 off) 변경. `CalendarTask.multiPassEnabled` 신규 필드(양 플랫폼, Firebase/로컬 저장 모두 반영, 안드로이드 Room 28→29), `applyCalendarAutoSchedule`/`revertCalendarAutoSchedule`이 이 플래그를 먼저 확인. 캘린더 일정 목록 행에 "🔁다회독"/"🔁off" 토글 칩 추가 — 꺼져 있으면 ⏱(다음 회독까지 며칠) 입력도 함께 숨김.
3. **컴퓨터 시작 시 자동 실행(데스크탑 전용)**: 설정 > 공통에 토글 신규. 기존에 있던 `PhoneLockDesktopWatchdog` 예약 작업(1분마다 생사 확인하는 내부 신뢰성 장치, 이번 요청과 무관하게 항상 켜짐)과는 별개로, `HKCU\...\Run` 레지스트리 값 하나로 구현한 일반적인 "로그인 시 자동 실행" — 관리자 권한 불필요, 이 계정에만 적용(`Watchdog.kt`의 `isLaunchAtStartupEnabled`/`setLaunchAtStartupEnabled`, JNA `Advapi32Util` 재사용).

**검증**: 양 플랫폼 컴파일 확인(desktop `compileKotlin`, android `compileReleaseKotlin` BUILD SUCCESSFUL — ResponsiveSplit 첫 컴파일 시 `BoxScope` import 중복/누락으로 실패했다가 수정 후 통과) → 안드로이드 `assembleRelease`, 데스크탑 `createDistributable`+`packageMsi` 재빌드 → 안드로이드 APK 세 위치 해시 일치 갱신, 데스크탑 표준 배포 절차로 이 호스트 재배포(watchdog 비활성화→종료→robocopy FAILED 0→jar 해시 일치→재실행→watchdog 재활성화, `debug.log`에 크래시 없음/`.corrupted-*` 파일 없음 확인) → `sync-public-repo.ps1`로 공개 저장소 푸시(`286b321`) + GitHub 릴리스 게시: 데스크탑 [desktop-1788173271](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788173271), 안드로이드 [android-1788173185](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788173185). **3건 전부 실사용 미검증** — 특히 반응형 레이아웃은 실제로 창을 좁혀가며 확인 필요.

**보류**: 사용자가 4번째로 요청한 "캘린더에 실제 공휴일 표시"는 데이터 출처(고정 표/공공API/수동 입력) 확인 질문에 사용자가 아직 답하지 않아 이번 라운드에서 구현하지 않음.

---

## 2026-08-31 (79차 세션, 추가) — 테마 커스텀(배경+포인트 2색) 추가, 공휴일 표시는 보류

사용자가 요청한 "테마 커스텀"(현재 8종 고정 팔레트 외에 배경/포인트 두 색을 직접 골라 나만의 테마를 만드는 기능) 구현. 공휴일 표시(공공데이터포털 API 연동)는 인증키가 필요해 사용자가 발급 전이라 이번엔 보류하고 취소.

- `ThemeMode.CUSTOM` 신규(양 플랫폼) + `buildCustomPalette(backgroundHex, accentHex)` 함수 — 배경색의 명도로 라이트/다크를 자동 판정하고, 텍스트/카드/보조색/컨테이너색 등 나머지 14개 팔레트 필드를 전부 배경↔포인트색 blend로 자동 계산(성공/경고/에러 색만 기존 8개 팔레트가 공유하는 표준값 재사용). 사용자가 어떤 색 조합을 골라도 최소한의 대비는 보장되도록 설계.
- 저장: 데스크탑은 `AppData.customThemeBackground`/`customThemeAccent`("#RRGGBB", data.json), 안드로이드는 `AppPreferences`(SharedPreferences) 동일 키 이름으로.
- 설정 화면 "테마" 카드에 "🎨 커스텀" 칩 추가 — 선택하면 배경색/포인트색 hex 입력 필드 2개(입력값 옆에 실시간 미리보기 스와치)가 나타남.
- **데스크탑 반응형 처리**: `themeMode` 문자열이 "CUSTOM"으로 이미 선택된 채 색만 바뀌는 경우 Compose가 상태 변화를 못 느껴 재계산이 안 되는 문제가 있어(`themeMode` 값 자체는 안 바뀌므로) `themeRefreshTick` 카운터를 추가해 색이 바뀔 때마다 강제로 팔레트를 재계산하도록 함(`Main.kt`/안드로이드 `MainActivity.kt` 동일 패턴). `Repository.currentPalette()`/`PhoneLockTheme` 오버로드(팔레트 직접 받는 버전) 신규.
- **안드로이드**: `PhoneLockTheme()` 시그니처에 `customBackground`/`customAccent` 파라미터 추가, 5개 호출부(`MainActivity`/`BlockActivity`/`ConfirmOpenActivity`×2/`StudyLockActivity`) 전부 갱신.

**검증**: 양 플랫폼 컴파일 확인(desktop `compileKotlin`, android `compileReleaseKotlin` BUILD SUCCESSFUL) → 안드로이드 `assembleRelease`, 데스크탑 `createDistributable`+`packageMsi` 재빌드 → 안드로이드 APK 세 위치 해시 일치 갱신, 데스크탑 표준 배포 절차로 이 호스트 재배포(watchdog 비활성화→종료→robocopy FAILED 0→jar 해시 일치→재실행→watchdog 재활성화, `.corrupted-*` 파일 없음 확인) → `sync-public-repo.ps1`로 공개 저장소 푸시(`c02a17e`) + GitHub 릴리스 게시: 데스크탑 [desktop-1788174278](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788174278), 안드로이드 [android-1788174193](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788174193). **실사용 미검증** — 실제로 임의의 색 조합을 넣어봤을 때 항상 읽을 수 있는 대비가 나오는지 확인 필요.

---

## 2026-08-31 (79차 세션, 추가) — 데스크탑 자체 업데이트 무한 반복 버그 수정

사용자 보고: "업데이트를 해도 업데이트가 안 되고 갑자기 새로운 배너가 뜨면서 업데이트할거냐고 물어봐 그리고 그걸 업데이트한다고 눌러도 계속 반복 돼."

- **원인**: `UpdateBanner.kt`가 설치파일 실행 후 `exitProcess(0)`로 종료할 때 `intentional_exit.flag`를 안 남겨서, 감시 프로세스(Watchdog)가 2초 안에 옛 버전을 되살렸다 — 되살아난 옛 버전이 설치 마법사가 덮어쓰려는 파일을 다시 잠그고, 곧 "새 버전 있음" 배너를 또 띄워서 무한 반복.
- **해결**: 트레이 "종료"와 동일하게 `intentionalExitFlagFile().createNewFile()`을 `exitProcess(0)` 직전에 호출 — 감시 프로세스가 되살리지 않아 설치가 방해 없이 끝까지 진행된다. [[BUGS.md]] 79차 참고.
- **주의**: 이미 이 버그가 있는 구버전에 갇힌 사용자는 인앱 업데이트로 못 빠져나올 수 있어, 트레이 "종료" 후 새 msi를 수동 설치하도록 안내함.

**검증**: `compileKotlin` BUILD SUCCESSFUL → `createDistributable`+`packageMsi` 재빌드 → 이 호스트 표준 배포 절차로 재배포(watchdog 비활성화→종료→robocopy FAILED 0→jar 해시 일치→재실행→watchdog 재활성화) → `sync-public-repo.ps1` 푸시(`1c01be2`) + GitHub 릴리스 게시: [desktop-1788175293](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788175293). **실제 무한 루프 재현 후 해결 확인은 안 됨** — 사용자가 실제로 겪던 상황이라 실사용 확인 시급.

---

## 2026-08-31 (79차 세션, 추가) — "앱 종료 확인 절차"를 설정에서 켜고 끌 수 있게(끄는 것 자체도 보호)

사용자 의견: "정말 끄겠습니까"(회유 멘트 20개 확인)는 관리(차단) 기능의 꼼수 방지 장치일 뿐이지 앱 전체에 항상 필요한 기능은 아니다 — 동의해서 설정 > 관리 탭에 on/off 토글 추가. 단, 이 토글을 켬→끔으로 바꾸는 행위 자체가 "종료를 우회하는 꼼수"이므로, 끄려고 하면 똑같이 회유 멘트 20개를 다 통과해야 실제로 꺼지게 만들었다.

- `Repository.exitConfirmEnabled`(기본 `true`, 기존 동작 그대로 유지) 신규 — `AppData`/`JsonStore`에 저장.
- `ExitConfirmScreen`에 `title`/`finalLabel` 파라미터 추가해 재사용 가능하게 일반화(기존엔 "종료 확인" 문구가 하드코딩).
- `Main.kt`: 트레이 "종료" 클릭 시 `exitConfirmEnabled`가 꺼져 있으면 20문항 없이 바로 종료(`doExit()`로 추출 — intentional_exit 표식 남기고 `flushPendingUsage()` 후 `exitApplication()`, 79차 업데이트 루프 수정과 동일 절차).
- `SettingsScreen.kt` 관리 탭에 "앱 종료 확인 절차" 카드 신규 — 토글을 끄면 즉시 꺼지는 게 아니라 `ExitConfirmScreen`을 별도 Window(설정 화면에서 직접 띄움, `Main.kt` 밖에서도 Window를 열 수 있음을 활용)로 띄워 20문항을 통과해야만 실제로 꺼짐.

**검증**: `compileKotlin` BUILD SUCCESSFUL → `createDistributable`+`packageMsi` 재빌드 → 이 호스트 표준 배포 절차로 재배포(watchdog 비활성화→종료→robocopy FAILED 0→jar 해시 일치→재실행→watchdog 재활성화, `.corrupted-*` 없음 확인) → `sync-public-repo.ps1` 푸시(`6397993`) + GitHub 릴리스 게시: desktop-1788176887. **실사용 미검증** — 토글 끄기 시도 시 실제로 20문항이 뜨는지, 통과 후 정말 종료가 확인 없이 되는지 확인 필요.

**곧이어 수정(같은 세션)**: 사용자가 "이 설정은 기본적으로 off였으면 좋겠다"고 요청 — `AppData.exitConfirmEnabled` 기본값을 `true`→`false`로 변경(신규 설치 기준, 이미 저장된 기존 사용자 값은 안 건드림). 재빌드/재배포/재게시: [desktop-1788177030](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788177030). 이 호스트 자체의 저장된 값(`data.json`의 `exitConfirmEnabled: true`)은 이미 명시적으로 기록돼 있어 기본값 변경의 영향을 안 받음 — 끄고 싶으면 설정 화면에서 직접 20문항을 통과해야 함(의도된 동작).

---

## 2026-08-31 (78차 세션) — 태블릿 릴스/쇼츠 차단 버그 수정 + 모임 "일정표" 실제 계산기 데이터 동기화 + 무전기 남성 목소리 + 빌드/배포 워크플로 정리

**1) 태블릿 릴스/쇼츠 차단이 전혀 안 되던 버그 수정**: `AppMonitorAccessibilityService.containsSelectedKeyword()`가 "선택된 탭" 노드를 화면 하단 12% 영역에서만 찾도록 돼있었음(폰의 하단 탭바 기준) — 태블릿은 화면이 넓어 Instagram/YouTube가 좌측 세로 내비게이션 레일을 쓰기 때문에 선택된 릴스/쇼츠 탭이 이 영역 밖에 있어 계속 걸러졌던 것. 좌/우측 16% 폭의 사이드 레일 밴드도 인정하도록 확장(안드로이드 전용, 데스크탑/브라우저 확장은 릴스 감지 자체가 없어 영향 없음).

**2) 모임 "일정표" 탭 의미 정정 — 진짜 할당량 계산기 데이터로 교체**: 77차엔 계산기 데이터가 모임 공유 대상이 아니라서 "일정표" 탭이 그 주 캘린더 오늘 할 일을 요일별로 나열하는 걸로 단순화돼 있었는데, 사용자가 "일정표는 공부앱 탭 중 하나인 진짜 일정표(TimetableScreen, 계산기 업무의 요일별 목표량 표)를 의미한다"고 정정. `MemberStats`에 `calcTasks: List<CalcTaskStat>` 신규 필드 추가(기존 `shareSchedule` 토글에 함께 묶어 별도 토글 신설 안 함) — RTDB `groups/{id}/stats/{uid}` 노드는 이미 문서 전체 쓰기 권한이라 **규칙 재게시 불필요**. 모임 멤버 상세의 일정표 탭을 라이브 `TimetableScreen`과 동일한 날짜 이동(◀/▶)+요일별 목표량 표로 재작성(양 플랫폼) — `linkedGoalAchieved`(✅ 체크)는 상대방 로컬 캘린더 연동이 있어야만 계산되는 값이라 이번에도 제외.

**3) 무전기 TTS 남성 목소리 추가**: ⚙ 무전기 설정 다이얼로그에 "텍스트 메시지 목소리(TTS)" 여성/남성 토글 + 미리듣기 버튼 신규. `GroupWalkieSettings.voiceGender`(모임별, 받는 사람 기준)로 RTDB `walkieSettings/{myUid}`에 저장. **안드로이드**는 피치를 낮춰(0.78) 남성 톤을 흉내(엔진마다 설치된 음성이 제각각이라 이름으로 실제 다른 배우 목소리를 고르는 건 신뢰할 수 없어서 채택). **데스크탑**은 SAPI에 설치된 한국어(ko) 남성 음성이 있으면 그걸 쓰고 없으면(대부분의 Windows가 그렇다) 한국어 여성(Heami)으로 폴백. **겸사겸사 발견한 버그**: 기존 데스크탑 TTS 코드가 한국어 음성을 한 번도 명시적으로 선택한 적이 없어서, PC의 기본 SAPI 음성이 영어로 잡혀있으면 한글 문자를 영어 음성으로 읽던 잠재 버그였음(사용자가 "지금 보이스는 영어 보이스"라고 지적해서 발견) — 성별과 무관하게 항상 한국어 음성을 먼저 찾도록 고쳐서 같이 해결.

**4) 빌드 환경 이슈 발견/수정**: 데스크탑 컴파일 확인 중 `AndroidBuilds\phone-lock-desktop\build.gradle.kts`가 75차 자체 업데이트 기능(`generateBuildInfo` Gradle 태스크) 이전 버전으로 방치돼 있어 `Unresolved reference: BuildInfo`로 컴파일이 계속 실패하던 걸 발견 — 최신 소스로 재동기화해 해결. 이전 세션들에서 `robocopy /MIR ... src src`로 소스 폴더만 동기화해왔는데, 빌드 스크립트 자체가 바뀐 경우엔 이 파일도 같이 동기화해야 한다는 게 이번에 드러남.

**5) 데스크탑 배포 중 자체 워치독 즉시 재기동 확인**: 배포 전 `Stop-Process`로 앱을 끄면, 앱 자체의 인메모리 워치독이 거의 즉시(1초 이내) 자기 자신을 재기동해서 다음 robocopy가 exe 파일을 계속 못 옮기고(`ERROR 32`, 30초 간격 무한 재시도) 있는 걸 확인 — 재시도 대기 대신 곧바로 다시 `Stop-Process`(이번엔 재기동 창을 놓쳐서 성공)한 뒤 즉시 robocopy하는 순서로 해결. 스케줄된 Windows 작업(watchdog)이 아니라 앱 자체 코드의 자기 복구 로직이라는 점이 확인됨.

**6) Firebase RTDB 규칙 재게시(77차 이월)**: 사용자가 "이미 했을 것"이라고 확인 — 콘솔 직접 대조는 하지 않음, 이후 관리자(비-모임장) 권한 관련 401/거부 오류가 재발하면 이 항목부터 재확인.

**7) 빌드/배포/게시 워크플로 정리(사용자 요청으로 확립)**:
   - **"깃허브에 올려/커밋해" = 새 GitHub 릴리스 게시**로 해석(단순 `git commit`/`push`가 아님) — 소스 커밋/푸시는 여전히 먼저 하되, 그 다음 `gh release create`로 태그된 릴리스까지 만드는 것까지 포함.
   - **안드로이드는 매 수정마다 `assembleRelease`(release 서명 APK)를 기본으로 빌드** — 이전까지 관행적으로 써온 `assembleDebug`는 이제 컴파일만 빠르게 확인할 때만 쓰고, 실제 배포/게시용은 항상 release.
   - 이번 세션 결과물로 실제 게시: 데스크탑 [desktop-1788165407](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788165407), 안드로이드 [android-1788166163](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788166163).

**검증**: 양 플랫폼 `compileKotlin`/`compileDebugKotlin` BUILD SUCCESSFUL 확인 → 안드로이드 `assembleDebug`(1차) 이어서 `assembleRelease`(워크플로 정리 후 재빌드)로 APK 세 위치(AndroidBuilds/OneDrive 원본/vm-build-output) 전부 갱신, dex에서 신규 심볼(`voiceGender`/`CalcTaskStat`) 실제 포함 확인 → 데스크탑 `packageMsi createDistributable` BUILD SUCCESSFUL, 표준 배포 절차(프로세스 종료→robocopy 2곳 FAILED 0 확인→jar 해시 비교 일치→재실행)로 이 호스트 실제 재배포 완료. `git log`에 로컬 커밋(`fca80c5`) + 공개 저장소 `study-planner` main 푸시(`515827d`) 완료. **이번 세션 신규 기능(태블릿 릴스차단/모임 일정표/무전기 남성목소리) 전부 실기기 미검증.**

---

## 2026-08-30 (77차 세션) — 모임 멤버 상세 탭 재설계 + 모임장/관리자 권한 시스템 + 무작위 알림 + 설정 통합 + 캘린더 회독 3단계 축소 + 자체 업데이트 버그 2건 수정 + 공개 저장소 정리

77차는 사용자 요청이 여러 차례 이어진 긴 세션 — 각 요청마다 코드→컴파일→빌드→GitHub 릴리스 게시→이 호스트 재배포까지 매번 완료했다.

**1) 모임 멤버 상세 화면 전면 재설계(탭 구조)**: "사용자 본인이 앱 탭을 눌러 기능을 다루듯, 모임에서 멤버를 클릭해도 그 사람 데이터로 채워진 같은 탭들이 뜨게 해달라"는 요청. 기존엔 루틴/공부/스트릭/캘린더/관리그룹을 한 화면에 카드로 쭉 나열했으나, 내 앱 본체와 동일한 탭 구조(🌱루틴[오늘/통계]·📘공부[캘린더/일정표/통계]·🗂️관리[그룹/통계])로 개편. 사용자가 "라이브 화면 재사용 vs 보기전용 별도 작성" 중 후자(권장, 안전)를 선택 — 라이브 RoutineScreen/CalendarScreen 등을 그대로 재사용하면 내 실제 데이터를 읽고 쓰는 핵심 화면이라 버그 위험이 크다는 판단. 각 리프 탭을 이 파일 안에 새로 작성(양 플랫폼 `SocialGroupMemberDetailScreen.kt`):
   - **캘린더 탭**: `ReadOnlyMiniCalendar` — 진짜 월 미니 그리드(회독 색상 배지, 오늘 강조), 날짜 클릭 시 그 날 일정 전체(이름+완료상태)와 그 날 공부시간을 아래에 펼침. 이를 위해 `MemberStats.schedule`을 "오늘 하루"에서 "이번 달 ±7일 버퍼"로, `studySecondsByDate`(dateKey→초) 신규 필드로 확장.
   - **일정표 탭**: 이번 주(일~토) 요일별 일정 나열 — 계산기 연동 목표량은 "모임" 공유 대상이 아니므로 제외, 캘린더 데이터만 재사용.
   - **공부 통계 탭**: 캘린더 탭과 같은 동기화 범위(이번 달) 안에서 오늘 완료율/연속 완료일/회독 단계별 분포 — 전체 이력이 아닌 "최근 범위 근사치"임을 라벨로 명시.
   - **루틴 통계 탭**: 현재/최고 스트릭(`routineBestStreak` 신규 필드, `RoutineEngine.bestStreak()` 재사용) + 오늘 완료율. 7일/30일 추이는 과거 이력이 동기화 안 돼 제외.
   - **관리 그룹 탭**: 클릭하면 스케줄/일일한도/실행확인 각각의 적용 시간대·요일, 차단 중인 프로그램/사이트 목록까지 보여주는 상세 다이얼로그(`ActiveGroupStat`을 이름/설명뿐 아니라 그룹의 전체 설정 필드로 확장). "지금 실제로 제한 중인" 그룹만 걸러 보여주던 걸(`isCurrentlyRestricting`) "켜진 그룹 전부"(`groupEnabled`)로 넓힘 — 시간대가 안 맞아 당장은 제한 중이 아닌 그룹(주말 전용 그룹 등)도 보이게 해달라는 요청.
   - **관리 통계 탭**: 그룹별 오늘 사용량/한도/재확인 통과 횟수(오늘·어제)/최근 7일 평균 — 라이브 `StatsScreen`과 같은 4개 지표를 그룹별로.
   - "⏱️ 지금 상태"(공부중 여부)는 별도 카드였으나 "📘 공부" 탭 안으로 병합.

**2) 모임장/관리자 권한 시스템(신규)**: 방을 처음 만든 사람(`ownerUid`)은 항상 최상위 "모임장". 모임장만 다른 멤버를 관리자로 승격/해제할 수 있고(`groups/{id}/admins/{uid}=true`), 관리자(모임장 포함)는 모임 이름/초대코드 수정 + 멤버 내쫓기가 가능. 양 플랫폼 `SocialGroupSyncClient`에 `readGroupAdmins`/`setGroupAdmin`/`kickMember`/`updateGroupName`/`regenerateInviteCode` 신규. RTDB 보안 규칙(`phone-lock-android/firebase-database.rules.json`)에 `groups/{id}/info`(이름/코드 쓰기 — 모임장 또는 admin), `groups/{id}/members/{uid}`(기존 "본인만" 쓰기 규칙에 모임장/admin도 추가 — 내쫓기용) 두 경로 새 `.write` 규칙 추가. `admins` 노드 자체는 부모 `groups/{id}`의 기존 "모임장만" 블랜킷 규칙을 그대로 상속해 승격/해제는 모임장만 가능하도록 서버에서도 강제. **아직 Firebase 콘솔에 재게시 안 함 — 모임장 계정은 기존 블랜킷 규칙으로 이미 되지만, 승격된 일반 관리자는 재게시 전까지 이름수정/내쫓기가 권한 거부로 실패한다.**

**3) 무작위 알림(신규)**: "스트릭 알림처럼 랜덤한 시간에, 같은 모임 사람이 스케줄을 못 지키고 있으면 깨워달라"는 요청. 여러 설계 대안(누가 감지하는지, 판단 기준, 중복 처리) 중 사용자와 확인한 방향: **하루 중 무작위 시각 한 번**(스트릭 알림과 같은 `randomTimeAfter` 패턴, 안드로이드는 `RoutineAlarmScheduler.scheduleGroupNudgeCheck`/`ACTION_GROUP_NUDGE_CHECK`, 데스크탑은 `SocialGroupNotifier`의 30초 tick에 얹은 랜덤 목표시각 비교), **각 기기가 독립적으로** 자신이 속한 모임(모임별 켜기/끄기, `randomNudgeEnabledFor`/`setRandomNudgeEnabled`, 순수 로컬 설정)의 멤버를 훑어 오늘 루틴 미완료 또는 오늘 캘린더 일정 미완료(O 아님)가 있으면 기존 `sendNudge()`로 자동 발신. 발신자 조율 로직 없음 — 넛지가 `groups/{id}/nudges/{targetUid}` 1인 1슬롯 덮어쓰기 구조라 여러 기기가 비슷한 시각에 같은 사람을 깨워도 알림 하나로 합쳐짐(사용자 확인, "그냥 각자 독립적으로 체크해서 보낸다" 선택). 진동은 기존 넛지 수신 파이프라인(`GroupNudgeWorker` 등)을 그대로 재사용해 별도 구현 불필요.

**4) 모임 설정 버튼 통합**: 🔒공유설정/🎙️무전기/🔔무작위알림 3개 버튼을 "⚙ 설정" 메뉴 하나로 통합(눌러서 뜬 메뉴에서 각 기존 다이얼로그로 진입, 다이얼로그 자체는 그대로 재사용). 관리자에게만 "✏️ 모임 이름/코드 수정"/"👥 멤버 관리"가 이 메뉴에 추가로 보임.

**5) 캘린더 회독 8단계→3단계 축소**: "9회독인가? 3회독으로 바꿔, 기본은 0 3 7이고 색깔은 빨/노/초로"라는 요청 — 실제로는 51차에 8단계(하양~보라, 간격 1·3·7·14·30·60·120일)였음을 확인 후 3단계로 축소. "0 3 7"의 의미를 사용자와 확인해 **1회독(만든 날) 기준 누적 0/3/7일차**로 확정: red(1회독, 만든 날 그대로)→yellow(2회독, red로부터 +3일)→green(3회독, red 기준 +7일=yellow 기준 +4일). `CALENDAR_COLOR_ORDER`/`CALENDAR_SCHEDULE`을 3항목으로 축소, 새 일정 기본 색을 `white`→`red`로 변경, 계산기 연동 진행량 반영 조건("1회독일 때만")도 `color == "white"`→`"red"`로 이동. 8단계 시절 색상 선택 팝업(8버튼)을 3버튼(초록/노랑/빨강)으로 축소. **기존에 저장된 8단계 색상 데이터(white/orange/blue/indigo/purple)는 그대로 두고 라벨 매핑만 뺐다**(51차와 같은 전례 — stageTextColor의 색상 정의 자체는 안 지워 과거 일정도 여전히 고유 색으로 보임).

**6) 자체 업데이트 버그 2건**:
   - **확인 실패 vs 최신 버전 오판**: 사용자가 "업데이트 확인을 눌러도 계속 최신 버전이라 뜬다"고 보고. 원인 조사 결과 GitHub 비인증 REST API 요청 한도(시간당 60회, IP 단위)를 이 호스트에서 `gh` CLI를 다수 호출해 소진(`X-RateLimit-Remaining: 0` 직접 확인) — `UpdateChecker`/`DesktopUpdateChecker`가 네트워크 실패를 `null`로 삼켜 "확인 실패"와 "정말 최신"을 구분 못 하던 게 근본 원인. `checkLatestAndroidRelease()`/`checkLatestDesktopRelease()`를 `Result<LatestRelease?>` 반환으로 바꾸고, `PhoneLockRepository`/`Repository`에 `UpdateCheckOutcome`(`Available`/`UpToDate`/`Failed`) 3상태 sealed class 신규 — 설정 화면이 실패 시 "확인 실패: ... 다시 시도해주세요"를 붉은 글씨로 표시하도록 변경. 부수 효과로 "누르면 자동 업데이트"(75차에 이미 구현됐던 다운로드+설치 코드)가 그동안 확인이 계속 실패해서 배너 자체가 뜬 적이 없었던 것도 같이 해결됨.
   - **업데이트 배너 버튼 화면 밖 이탈**: 사용자가 "파란 배너는 뜨는데 설치 버튼이 안 보인다"고 재확인. `UpdateBanner.kt`(양 플랫폼)가 `Row`+`Arrangement.SpaceBetween`에 weight 없는 `Text`+`Button`을 나란히 뒀는데, 문구가 길면 Text가 Row 폭을 거의 다 차지해 Button이 화면 밖으로 밀려나던 레이아웃 버그. `Column`(문구 위, 버튼은 전체폭으로 아래 새 줄)으로 교체해 해결.
   - 두 버그 수정 각각 새 릴리스로 게시(`android-1788071119`/`desktop-1788071237`, `android-1788071959`/`desktop-1788072077`).

**7) 공개 저장소 정리**: "GitHub에 올리고 있냐"는 질문에 이 저장소엔 원격이 아예 없었다는 걸 확인 → 사용자가 이전에 GitHub Releases용으로 써온 `study-planner`(공부앱 웹앱이 있던 공개 저장소)를 재사용하기로 결정, `main` 브랜치에 병합. 1차 시도(`git checkout master -- <path>` 방식으로 curated 브랜치에 소스만 옮기는 스크립트)가 실제로는 master의 "커밋된" 상태를 읽어와서, 아직 커밋 안 한 이 세션의 버그 수정 코드를 작업 트리에서 지워버리는 사고가 있었음(다행히 그 전에 이미 빌드·릴리스는 끝난 뒤였음 — 배포물은 무사) — 코드 재작성으로 복구, **`sync-public-repo.ps1`을 git worktree 기반으로 재설계**(별도 `C:\build\clean-main-worktree`에 `clean-main` 브랜치를 체크아웃해두고, `master`의 현재 디스크 상태를 robocopy로 그 worktree에 복사한 뒤 커밋+push — `master`의 브랜치를 절대 전환하지 않아 uncommitted 작업이 다시는 지워지지 않음). 내부 문서(BUGS/CHANGELOG/DECISIONS/HANDOFF/IDEAS/VM_BUILD_HANDOFF/종합보고서)는 최초 병합 때 한 번 올라갔다가 사용자 요청으로 **히스토리까지 완전히 지우고**(별도 브랜치를 origin 원본 커밋에서 새로 시작해 소스만 추가 → force-with-lease push) 재정리, 이후로는 애초에 안 올라가는 구조. **사용자가 "이것도 네가 알아서 해"라고 위임 — 앞으로 소스 변경이 있을 때마다 요청 없이 이 스크립트를 실행해 공개 저장소에 동기화한다**(HANDOFF.md "현재 주의사항"에 예외로 기록).

**8) 그 외**: 세션 끝에 사용자가 "관리앱 그룹 다 꺼줘"라고 요청 — 이 앱 자체가 회유 절차 없이 그룹을 못 끄게 만든 자기통제 앱이라, 절차를 우회해도 되는지 먼저 확인 후("니가 직접 꺼, 문서의 그룹 끄기 주의사항 참조해서") 이 호스트 데스크탑 앱을 완전히 중지하고(watchdog 예약작업도 비활성화 — 재시작하며 파일을 계속 다시 써서 최초 시도가 막혔음) 로컬 `data.json`의 그룹 8개 `groupEnabled`를 직접 `false`로 수정, 재실행. `applyDailyGroupResetIfNeeded()`(42차)가 `dailyResetHour`(5시) 기준 "오늘"이 바뀌면 자동으로 다시 켠다는 점을 사용자에게 안내.

---

## 2026-08-30 (76차 세션) — "모임" 공유/공개범위 대폭 확장(모임별 설정 + 사용자별 비공개 + 공유 항목 6종) + 관리자 권한 기반 설정탭 숨김

사용자 요청 5건을 양 플랫폼(안드로이드/데스크탑)에 대칭 구현. Room DB 스키마는 건드리지 않음(전부 SharedPreferences/JsonStore 로컬 설정 + 기존 `groups/{id}/stats/{uid}` RTDB 노드 필드 확장), RTDB 보안 규칙도 필드 화이트리스트가 없어 변경 불필요.

**1) 관리자 권한 기반 설정탭 숨김**: 기존에 최상위 탭(관리/공부/루틴/모임)에만 적용되던 `permRoutine/permStudy/permManage/permSocial`(승인 시 관리자가 지정) 필터링을 설정 화면의 서브탭(공통/루틴/공부/관리/모임)에도 동일 적용 — "공통"은 로그아웃 등 항상 필요해서 예외. 안드로이드 `ui/SettingsScreen.kt`, 데스크탑 `ui/SettingsScreen.kt` 동일 패턴.

**2) 모임별 공유 설정(전역 → 모임별)**: 62차에 앱 전체 공통이던 `shareRoutinesToGroup`/`shareStudyToGroup`/`shareStreakToGroup` 전역 토글 3개를 완전히 제거하고, 모임마다 다르게 설정하는 `GroupShareSettings`(모임ID -> 설정)로 교체 — 74차 무전기 설정을 전역→모임별로 옮긴 선례와 동일 패턴. 안드로이드는 `AppPreferences.groupShareSettingsJson`(JSON 맵), 데스크탑은 `AppData.groupShareSettings: MutableMap<String, GroupShareSettings>`. 각 모임 화면(`SocialGroupMembersScreen.kt`)에 새 "🔒 공유 설정" 버튼(안드로이드 IconButton, 데스크탑 TextButton)으로 진입하는 `GroupShareSettingsDialog`(양 플랫폼 신규 파일) 추가, 설정 화면의 "모임 공유 설정" 섹션은 안내 문구만 남김(무전기 설정 섹션과 동일 처리).

**3) 항목 6종으로 확장(기존 루틴/공부/스트릭 3종 + 신규 3종)**: `shareSchedule`(오늘 캘린더 일정 목록+완료여부), `shareStudyingNow`(지금 공부/뽀모도로 중인지+업무 이름 — 로컬 타이머 상태 OR `PomodoroSyncClient.isStudyTimerActive()` 원격신호), `shareActiveGroup`(지금 실제로 나를 제한 중인 관리 그룹 이름 — `LockEvaluator.isCurrentlyRestricting()`으로 전체 그룹 순회 판정, 그룹 "이름"을 그대로 목적 설명으로 사용해 별도 필드 신설/Room 스키마 변경 회피). `SocialGroupSyncClient.MemberStats`/`pushMyStats`/`readGroupStats`에 새 필드 추가(양 플랫폼), `SocialGroupMemberDetailScreen.kt`에 "📅 오늘 일정"/"⏱️ 지금 상태"/"🗂️ 작동 중인 관리 그룹" 카드 3개 신규.

**4) 모임 내 사용자별(상대방별) 공개 범위 — "모임 내 사용자 상세 설정"**: 서로 독립인 두 방향.
   - **내 정보를 이 사람에게 숨기기**: 내 stats push에 `hiddenFromUids`(uid 목록)를 항상 함께 실어 RTDB에 올리고(공유 토글 여부와 무관 — 접근제어 메타데이터라 별도 취급), 상대 화면에서 자기 uid가 그 목록에 있으면 항목별 공유 여부와 무관하게 전체를 "비공개"로 렌더링. RTDB 필드 단위 검증 규칙이 없어 진짜 서버 측 강제는 아니고(direct REST 조회로는 우회 가능) 클라이언트가 서로 신뢰하는 소셜 기능의 성격상 이 정도 수준으로 충분하다고 판단 — DECISIONS.md 참고.
   - **이 사람 정보를 내 화면에서 숨기기(관심없음)**: 순수 로컬 설정(`hiddenPeerUidsFor`/`setHiddenPeerUid`, RTDB 미전송), 켜면 상세화면이 그 사람 데이터를 아예 렌더링하지 않고 "숨겼습니다" 안내만 표시.
   - 두 토글 모두 `SocialGroupMemberDetailScreen.kt`(양 플랫폼)의 새 "👤 이 사람에 대한 내 설정" 카드에 위치(자기 자신 상세페이지에는 안 뜸).

**5) 컴파일 검증**: 데스크탑 `compileKotlin`, 안드로이드 `compileDebugKotlin` 둘 다 `C:\build\phone-lock-desktop`/`C:\AndroidBuilds\phone-lock-android`(OneDrive 밖 로컬 경로, robocopy 동기화 후) 기준 BUILD SUCCESSFUL 확인. 안드로이드 쪽은 이번에 `AndroidBuilds`의 `build.gradle.kts`가 OneDrive 원본과 오래 안 맞아(75차의 `buildConfig=true` 추가분 등 누락) `BuildConfig` unresolved 에러가 먼저 났었는데, gradle.kts 파일도 함께 동기화해서 해결 — src만 robocopy하는 기존 루틴에 gradle.kts류 설정 파일 동기화가 누락되기 쉽다는 점 확인.

**미완료(다음 세션 우선순위)**: 실기기 배포는 하지 않음(컴파일 검증까지만) — 안드로이드 APK/데스크탑 재배포 및 실사용 검증 필요.

---

## 2026-08-30 (75차 세션) — 자체 업데이트 기능(GitHub Releases 기반) 신규 구현 + 실제 릴리스 파이프라인 가동

**1) 자체 업데이트 신규 구현(안드로이드+데스크탑)**: 사용자 요청 — 설정탭 초기화 시각(`dailyResetHour`) 기준 "오늘"이 바뀌면 하루 1회 GitHub Releases(`studybeultaeon-svg/study-planner`, 공부앱 웹앱과 같은 저장소)를 조회해 새 버전이 있으면 화면에 "업데이트를 진행하세요" 배너를 띄우고, 버튼을 누르면 실제 다운로드/설치까지 수행한다.
- **태그 규칙**: 안드로이드는 `android-<versionCode>`(빌드 시각 자동 증가값과 동일), 데스크탑은 `desktop-<BuildInfo.BUILD_TIMESTAMP>` — 데스크탑엔 안드로이드 versionCode 같은 자동 증가 값이 없어서, `build.gradle.kts`에 안드로이드와 같은 방식(빌드 시각)으로 `BuildInfo.kt`를 컴파일 시점에 자동 생성하는 Gradle 태스크(`generateBuildInfo`) 신규 추가.
- **안드로이드**: `service/UpdateChecker.kt`(신규, GitHub Releases API 조회, `HttpURLConnection`+`org.json`, `PomodoroSyncClient`와 동일한 fail-safe 원칙), `ui/UpdateBanner.kt`(신규, `DownloadManager`로 APK 다운로드 후 설치 인텐트 자동 실행, "출처를 알 수 없는 앱 설치" 권한 없으면 그 설정으로 안내), `AndroidManifest.xml`에 `REQUEST_INSTALL_PACKAGES` 권한 추가, `app/build.gradle.kts`에 `buildFeatures.buildConfig = true` 추가(BuildConfig.VERSION_CODE 참조용, 이전엔 비활성화 상태였음). `PhoneLockRepository.checkForUpdateIfNeeded()`/`pendingUpdateApkUrl()`/`checkForUpdateNow()`(설정 화면 수동 확인용, 하루 1회 가드 무시) 신규, `AppMonitorAccessibilityService.tick()`에서 `applyDailyGroupResetIfNeeded()`와 같은 자리에서 호출. `MainActivity.kt`의 `PhoneLockApp` Scaffold 최상단에 배너 삽입.
- **데스크탑**: `monitor/DesktopUpdateChecker.kt`(신규, `java.net.http.HttpClient` 사용, 안드로이드판과 대칭), `ui/UpdateBanner.kt`(신규, 설치파일을 임시폴더에 받아 `ProcessBuilder`로 실행 후 앱 자신은 종료 — 실행 중인 파일을 installer가 덮어써야 하므로). `Models.kt`/`JsonStore.kt`에 `lastUpdateCheckDate`/`updateAvailableBuildTimestamp`/`updateAvailableInstallerUrl` 필드+parse/save 추가. `Repository.checkForUpdateIfNeeded()`(네트워크 I/O는 다른 push 함수들과 같은 이유로 락 밖 `Thread`에서 수행)/`pendingUpdateInstallerUrl()`/`checkForUpdateNow(onResult)`(설정 화면 수동 확인용) 신규, `EnforcementService.tick()`에서 `applyDailyGroupResetIfNeeded()`와 같은 자리에서 호출. `MainScreen.kt` 상단(기존 확장 heartbeat 경고 배너와 같은 자리)에 배너 삽입.
- **설정 화면**: 양 플랫폼 공통 서브탭에 "업데이트" `SectionCard` 신규 — 현재 버전 표시 + "지금 확인" 버튼(하루 1회 가드와 무관하게 즉시 조회) + 새 버전 발견 시 그 자리에 실제 업데이트 버튼이 나타남(위 `UpdateBanner` 재사용).

**2) GitHub 릴리스 파이프라인 실제 가동**: 저장소는 사용자가 지정한 기존 공부앱 웹앱 저장소(`studybeultaeon-svg/study-planner`, public, 기본 브랜치 `main`) 재사용. `gh` CLI가 호스트에 없어 `winget install GitHub.cli`로 설치 후 device-code 브라우저 인증(비밀번호/토큰은 Claude가 직접 다루지 않음, 사용자가 브라우저에서 직접 승인)으로 `studybeultaeon-svg` 계정 인증 완료. 이 세션 동안 안드로이드 3회(`android-1788020793`/`1788021447`/`1788023028`), 데스크탑 3회(`desktop-1788020957`/`1788021554`/`1788022748`) 릴리스를 실제로 게시하고 GitHub API 응답 형식까지 직접 검증.

**3) 빌드 환경 재확인**: 데스크탑을 OneDrive 폴더에서 직접 컴파일하면 [[BUGS.md]] "OneDrive 동기화 폴더에서 직접 Gradle 빌드" 문제가 이번에도 재현돼(`Unable to delete directory`), 기존에 있던 로컬 사본 `C:\build\phone-lock-desktop`으로 옮겨서 빌드 — 안드로이드의 `AndroidBuilds\phone-lock-android`와 동일한 역할. `HANDOFF.md`의 "실행 방법" 데스크탑 절차가 예전 세션의 임시 경로(`AndroidBuilds\phone-lock-desktop`, 존재하지 않는 JDK 경로)를 그대로 남기고 있던 걸 발견해 `C:\build\phone-lock-desktop` + 실제 사용 가능한 JDK 경로(`C:\Users\sunae\jdk21-temurin\...`)로 갱신.

**4) 이 호스트 데스크탑 앱 실제 배포**: 사용자 요청으로 "데스크탑은 기존처럼 수정되면 바로 이 호스트에 반영"하는 방식으로 복귀 — 위 배포 검증 절차(프로세스 종료→동기 robocopy→jar 해시 비교→재실행) 그대로 따라 새 빌드를 `C:\Users\sunae\PhoneLockDesktopApp\`에 실제로 반영, `vm-build-output\` 수동 설치용 사본도 매번 함께 갱신.

---

## 2026-08-29~30 (74차 세션) — "무전기" 후속 버그 다수 수정 + 모임별 설정/TTS 전면 재설계 + 예약 알람 500개 한도 크래시 원인 규명/수정

**1) 안드로이드 72~73차 누적분 빌드/배포**: 호스트에서 직접 빌드(사용자 승인) — Gradle 8.7 + Temurin JDK 21 재사용, `assembleDebug`/`assembleRelease` 모두 BUILD SUCCESSFUL. 릴리즈 빌드가 `MainActivity.kt`의 신규 `registerForActivityResult`(알림 권한) 때문에 `InvalidFragmentVersionForActivityResult` lint 오류로 실패 → `androidx.fragment:fragment-ktx:1.8.2` 명시적 의존성 추가로 해결(lint 억제 아닌 실제 버전 충돌 해결). APK 두 위치 + release 서명 확인(`CN=PhoneLock`) 완료.

**2) 무전 관련 버그 다수 수정**:
- 삭제 버튼이 서버 삭제 성공 여부와 무관하게 로컬 인박스에서 항상 항목을 지우던 버그 — 성공했을 때만 지우도록 수정.
- 전송/삭제 실패 시 원인을 안 보여주고 "네트워크 연결을 확인해주세요" 같은 고정 문구만 뜨던 문제 — HTTP 상태코드+RTDB 응답 본문을 그대로 예외 메시지에 담아 노출하도록 변경(양 플랫폼, `SocialGroupSyncClient.sendVoiceMessage`/`deleteVoiceMessage`). 이 진단 개선으로 실제 401(Permission denied) 원인을 확정 — `firebase-database.rules.json`에 `voiceMessages`/`walkieSettings` 규칙을 추가했지만 콘솔에 아직 게시가 안 된 상태였을 가능성이 높다고 판단, 사용자에게 규칙 전체를 다시 게시하도록 안내(Claude는 Firebase 콘솔 접근 권한이 없어 직접 게시 불가).
- FORCED(즉시재생) 모드에서 삭제가 서버에서 실패하면 같은 메시지가 폴링(7초)마다 계속 재생되던 버그 — "재생 후 삭제"에서 "삭제 확인 후 재생"으로 순서를 바꿔 삭제 실패 시 이번엔 건너뛰고 다음 폴링에서 재시도하도록 수정.
- 재생 후 즉시 삭제 대신 24시간 유예 후 자동 정리(`listenedAtMillis` 필드, `readIncomingVoiceMessages` 조회 시 만료분 자동 삭제) + 인박스에 명시적 "삭제" 버튼 추가.

**3) 무전기 아키텍처 전면 재설계(사용자 요청)**:
- **모임별 설정**: 전역 Settings 탭의 "무전기(음성 메시지)" 섹션 완전 제거, 각 모임 화면 ⚙ 버튼에서 그 모임 전용 `GroupWalkieSettings`(켜짐/모드/볼륨/`WalkieSchedule` 리스트)를 `groups/{groupId}/walkieSettings/{myUid}`에 저장. 요일×시간대 허용 일정을 여러 개 등록 가능(관리앱 그룹과 같은 요일마스크 규칙, 다중 등록 지원이 차이점) — `GroupWalkieSettingsDialog.kt`(신규, 양 플랫폼).
- **"깨우기"와 통합**: 기존에 따로 있던 "😴 깨우기"(넛지)와 "🎙️ 무전"(음성) 버튼을 하나의 선택창(`WakeOptionsDialog`, 양 플랫폼 `ui/components/WakeMessageDialogs.kt` 신규)으로 합쳐 "알림만 보내기 / 음성 메시지 녹음 / 텍스트 메시지(TTS)" 3택 제공.
- **TTS 텍스트 메시지 신규**: 텍스트를 적어 보내면 상대 기기가 읽어줌. 안드로이드는 `android.speech.tts.TextToSpeech`(`TtsPlayer.kt` 신규). 데스크탑은 Windows 내장 SAPI를 PowerShell로 호출하되, **사용자(다른 모임 멤버) 입력 텍스트를 절대 PowerShell 커맨드 문자열에 직접 이어붙이지 않고**, 고정된(사용자 입력이 섞이지 않는) `speak.ps1` 스크립트를 앱 데이터 폴더에 한 번만 생성해두고 텍스트는 `-File`+`-Text` 프로세스 인자로만 전달 — PowerShell의 `param()` 바인딩은 이를 코드가 아닌 순수 데이터로만 받아들이므로 커맨드 인젝션이 불가능(`TtsPlayer.kt` 데스크탑판 신규, 코드 주석에 설계 이유 명시).
- `VoiceMessageInfo`/전송 함수에 `textMessage` 필드 추가(오디오 대신 텍스트만 채우면 TTS 메시지), 기존 저장 구조(`voiceMessages`) 그대로 재사용.
- 버그: `WakeOptionsDialog`에서 "음성"/"텍스트" 선택 시 `onDismiss()`(대상 정보 초기화)를 먼저 호출한 뒤 다음 단계를 열어서, 녹음/입력창은 뜨지만 보낼 대상이 이미 사라져 "보내기"를 눌러도 조용히 아무 일도 안 일어나던 버그 — `onDismiss()` 호출 순서 수정으로 해결(양 플랫폼).

**4) 심각한 크래시 버그 발견/수정 — 예약 알람 500개 한도 초과**:
- 재설계 배포 후 "앱을 켜자마자 꺼진다"는 재현 접수. 최초엔 `WalkieTalkieService`가 이제 전 사용자에게 무조건 실행되도록 바뀐 점(안드로이드 12+ 포그라운드 서비스 시작 제약 가능성)을 의심해 `runCatching` 방어 코드 + 전역 크래시 로거(`PhoneLockApplication.kt` 신규, `crash_log.txt`에 스택트레이스 기록 + 설정 화면에서 공유 가능) 추가 — 재발.
- 사용자가 넘겨준 실제 크래시 로그에서 `IllegalStateException: Maximum limit of concurrent alarms 500 reached` 확인. 근본 원인: `PhoneLockRepository.syncRoutinesFromFirebase()`가 원격 데이터가 더 최신이면 로컬 루틴을 전부 지우고 새 Room auto-increment ID로 재삽입하는데, 루틴 알림 예약(`RoutineAlarmScheduler`)이 이 ID를 그대로 `AlarmManager` requestCode로 쓰고 있어서 — 동기화 때마다 옛 ID의 예약 알람은 취소할 방법이 영영 사라지고 새 알람만 계속 추가돼, 이 세션 동안의 잦은 재실행/동기화로 결국 안드로이드 앱당 한도(500개)를 넘겨버린 것.
- 수정 3단: ① `syncRoutinesFromFirebase()`가 delete+insert 트랜잭션 전에 지금 있는 루틴들의 알람을 먼저 명시적으로 취소, ② 이미 쌓인 알람을 정리하는 일회성 스윕(`RoutineAlarmScheduler.cleanupLeakedAlarmsIfNeeded`, ID 1~20000 범위를 훑어 취소 시도, `AppPreferences.leakedAlarmsCleaned`로 1회만 실행), ③ `scheduleAlarm()` 자체를 `runCatching`으로 감싸 혹시 한도에 다시 걸려도 앱 전체가 죽지 않게 방어.

**5) 알림 진동 전수 점검**: 무전기 메시지/모임 깨우기 알림 채널에 진동이 빠져있던 걸 발견해 `enableVibration(true)`+`vibrationPattern` 추가했으나, 안드로이드는 알림 채널을 한 번 만들면 앱이 코드로 재정의할 수 없다는 정책 때문에 이 세션 중 이미 진동 없이 만들어진 기존 채널엔 반영이 안 됨 — `group_nudge` → `group_nudge_v2`, `walkie_message` → `walkie_message_v2`로 채널 ID를 새로 발급해 우회(61차 루틴 알림 채널과 동일 패턴). 사용자 요청에 따라 "접근성 서비스 감시"(기능적 시스템 경고)는 진동 없이 유지, "사용자 참여용"(루틴/스트릭/깨우기/무전기) 알림만 진동으로 구분.

**6) "그냥 깨우기"(넛지) 지연 개선**: 기존엔 `GroupNudgeWorker`(WorkManager 최소 15분 주기)로만 확인해 짧은 테스트에서는 사실상 안 오는 것처럼 보였음 — `WalkieTalkieService`의 7초 폴링 루프에 넛지 확인(`pollNudges`)도 포함시켜 음성/텍스트 메시지와 같은 주기로 근접 실시간 전달되도록 개선. 기존 15분 워커는 서비스가 죽어있는 드문 경우의 보완용으로 유지.

**7) 스트릭 알림 기능 재확인**: 52차에 이미 구현된 기능(하루 중 랜덤 시각에 스트릭 응원/경고 알림)이 정상적으로 코드에 존재함을 확인 — 기본값이 꺼짐(opt-in)이라 사용자가 설정 > 루틴 탭에서 직접 켜지 않으면 동작하지 않는다는 걸 재안내.

**검증**: 매 변경마다 양 플랫폼 컴파일 확인, 안드로이드 debug/release APK 여러 차례 재빌드/배포, 데스크탑 `createDistributable`+robocopy(FAILED 0)+jar 해시 확인+재실행+워치독 재활성화 반복 수행. **이 세션의 신규/변경 기능(모임별 무전기 설정, 다중 일정, TTS, 알람 누수 수정) 전부 실기기 최종 검증은 아직 진행 중** — 사용자가 순차적으로 재현/재테스트하며 여러 버그를 실시간으로 발견해 그때그때 수정한 세션.

---

## 2026-08-29 (73차 세션) — "무전기"(모임 강제 음성 메시지) 신규 구현 + 데스크탑 "모임 생성 실패" 버그 수정/빌드/배포

**1) 버그 발견/수정: 데스크탑 "모임 생성"이 항상 실패함**
- 사용자 신고("모임 생성이 안 됨") 조사 결과, 데스크탑 `SocialGroupSyncClient.kt`의 `createGroup()`이 안드로이드와 다른 방식으로 구현돼 있었음을 발견 — 빈 body(`{}`)로 `groups`에 먼저 POST해서 `groupId`만 받아온 뒤, 별도 PUT으로 `info`(name/ownerUid/inviteCode/createdAt)를 쓰는 2단계 방식.
- 64/68차에 강화된 `firebase-database.rules.json`의 `groups/$groupId` 쓰기 규칙(`!data.exists() && newData.child('info').child('ownerUid').val() === auth.uid`)은 **첫 write의 newData 안에 이미 info가 있어야** 통과하는데, 빈 body push는 이 조건을 절대 만족할 수 없어 그 시점부터 모든 모임 생성이 거부되고 있었음. 안드로이드는 애초에 info를 포함한 body로 한 번에 POST해서 이 버그가 없었고, 70차 실기기 검증도 "이미 만들어져 있던 모임"으로 확인해서 못 잡았던 것으로 추정.
- 수정: `push()` 헬퍼가 body를 받도록 확장, `createGroup()`이 초대코드를 먼저 만든 뒤 info를 포함한 body로 한 번에 push하도록 안드로이드와 동일한 패턴으로 재작성.

**2) "무전기"(모임 내 강제 음성 메시지) 신규 구현**: 모임 멤버에게 음성 메시지를 보내면 수신자가 수락하지 않아도(설정에 따라) 자동 재생되는 기능 제안 → 탐색(FCM/백엔드 필요성, 기존 UI/서비스/설정 저장 패턴, 오디오 코덱 유무) → 계획 승인 → 구현까지 진행.
- **아키텍처 결정**(자세한 배경은 DECISIONS.md 참고): FCM/Cloud Functions 백엔드 도입 대신 포그라운드 서비스+빠른 폴링으로 타협(사용자 선택), 오디오는 새 코덱 의존성 없이 표준 WAV(8kHz mono 16-bit, 최대 10초, Android `AudioRecord`/데스크탑 `javax.sound.sampled`), 저장은 새 Firebase Storage 도입 없이 base64로 기존 RTDB에.
- RTDB `groups/{id}/voiceMessages/{targetUid}/{msgId}` 스키마(push-id 리스트, nudges와 달리 재생/확인 후 삭제) + `firebase-database.rules.json`에 규칙 신설(nudges와 동일 조건, `$msgId` 한 단계 더 중첩) — **콘솔 재적용은 아직 안 함**.
- `SocialGroupSyncClient.kt`(양 플랫폼)에 `sendVoiceMessage`/`readIncomingVoiceMessages`/`deleteVoiceMessage` 추가, `PhoneLockRepository.kt`(안드로이드)에 얇은 pass-through 추가.
- 새 `VoiceRecorder.kt`/`VoicePlayer.kt`(양 플랫폼) — 녹음/재생, 볼륨은 앱 설정 배율만 적용(기기 볼륨/무음은 항상 존중, 특수 스트림으로 안 뚫음).
- 설정: `walkieReceiveEnabled`(기본 **꺼짐**, opt-in)/`walkieVolume`/`walkieMode`("FORCED" 즉시재생 vs "MESSAGE_ONLY" 메시지로 받기)/`walkieAllowedStartMinute`·`EndMinute`(허용 시간대) — Android `AppPreferences.kt`, Desktop `Models.kt`+`JsonStore.kt`(parse/serialize 둘 다)+`Repository.kt`. `SettingsScreen.kt`(양 플랫폼)에 새 SectionCard(이 프로젝트 첫 `Slider` 사용).
- 송신 UI: `SocialGroupMemberDetailScreen.kt`(양 플랫폼)에 🎙️ 무전 버튼 + 녹음 다이얼로그(시작/정지, 최대 10초 자동종료). 안드로이드는 RECORD_AUDIO 런타임 권한 요청 플로우가 이 기능으로 처음 생김.
- 수신: 안드로이드는 새 `WalkieTalkieService`(포그라운드 서비스, `foregroundServiceType="mediaPlayback"`, 상주 알림 1개, 7초 폴링, `walkieReceiveEnabled` 토글에 start/stop 연동) — 접근성 서비스(켜짐에만 동작)나 `GroupNudgeWorker`(WorkManager 15분 하한)에 안 얹은 이유는 DECISIONS.md 참고. 데스크탑은 `Main.kt`에 켜져 있는 동안만 7초(꺼지면 30초) 주기 별도 폴링 루프 + 새 `VoiceMessageNotifier.kt`. "즉시재생" 모드는 배너 알림+자동재생 후 삭제, "메시지로 받기" 모드는 `SocialGroupMembersScreen.kt`(양 플랫폼) 인박스에 남겨 사용자가 직접 재생.
- 강제 재생 배너는 안드로이드 `TYPE_ACCESSIBILITY_OVERLAY`(접근성 서비스 전용이라 일반 서비스에서 못 씀) 대신 일반 알림으로 단순화 — 계획 대비 구현 단계에서 조정한 부분.
- `AndroidManifest.xml`에 `RECORD_AUDIO`/`FOREGROUND_SERVICE_MEDIA_PLAYBACK` 퍼미션 + `WalkieTalkieService` 등록.

**3) 빌드/배포**: 이 세션엔 gradle이 없어 처음엔 전부 컴파일 미검증으로 남을 뻔했으나, 사용자가 "여기서 진행해"(호스트에서 직접 빌드)를 명시적으로 요청 — 원래 VM_BUILD_HANDOFF.md 절차상 별도 VM에서 빌드해야 하는 원칙(호스트엔 실제 운영 워치독+프로덕션 Firebase가 있어 리스크)이 있었지만, 사용자가 리스크를 이해하고 진행하기로 함.
- 호스트에 이미 캐시돼 있던 Gradle 8.7(`~/.gradle/wrapper/dists/gradle-8.7-bin`)을 발견, JDK는 Android Studio 번들 JBR 21(jpackage 없음)로 `compileKotlin`까지는 성공했으나 `createDistributable`(jpackage 필요)은 실패 — jpackage 포함 Temurin JDK 21을 새로 다운로드(`~/jdk21-temurin`)해서 해결. 참고로 시스템에 이미 있던 JDK 25는 jpackage는 있지만 Gradle 8.7의 Kotlin DSL 스크립트 컴파일러가 "25.0.2" 버전 문자열을 못 읽어 애초에 Gradle 자체가 안 돌아감.
- `AndroidBuilds\phone-lock-desktop`에 최신 소스 robocopy 동기화(PowerShell 도구로, 기존 원칙대로) → `compileKotlin`/`createDistributable` 성공 → 빌드 산출물 jar 안에 `voiceMessages`/`VoiceRecorder`/`VoicePlayer`/`VoiceMessageNotifier` 등 새 코드가 실제로 포함된 것을 클래스 목록으로 재확인 → 표준 배포 절차(워치독 비활성화→프로세스 종료→robocopy 배포, FAILED 0 확인→jar 해시 일치 확인→재실행→워치독 재활성화) 그대로 수행, 배포 완료.
- **안드로이드는 이번 세션에 빌드 시도 자체를 안 함 — 72차분과 함께 다음 세션 최우선 작업.**

**4) [데이터] 관리앱 그룹 8개 `groupEnabled=false` 재적용(사용자 요청, 60차와 동일 원칙)**: 처음 껐을 때 `lastGroupAutoResetDate`가 아직 오늘 날짜로 안 찍혀있어서 앱 재시작 직후 42차 자동 재활성화 로직이 그대로 되살려버리는 걸 실제로 겪음 — 원인 파악 후 두 번째 시도에서 `lastGroupAutoResetDate`가 이미 오늘 날짜인 것까지 확인하고 재적용, 재시작 후에도 유지되는 것 확인. 순수 문자열 치환(+8바이트) + 백업 + Python `json` 모듈로 유효성 검증(PowerShell `ConvertFrom-Json`은 대용량 JSON 자체 파서 오류로 검증에도 안 씀, 기존 60차 교훈 재확인).

---

## 2026-08-28 (72차 세션) — Google 로그인 → 아이디/비밀번호 로그인 전면 교체 + "모임" 멤버 상세페이지 시각적 개편

**1) 로그인 방식 전환**: Google Sign-In(Credential Manager/OAuth PKCE)을 완전히 제거하고 Firebase Authentication의 email/password 방식으로 교체 — 메인 로그인 화면이 "로그인 / 회원가입 / 게스트로 진행" 3개 선택지로 바뀜. Firebase Auth가 이메일 형식만 지원해서, 사용자가 입력하는 "아이디"를 내부적으로 가짜 이메일(`{아이디}@phonelockapp.local`)로 변환해 인증에 쓴다(화면엔 노출 안 됨) — uid 기반 기존 인프라(`AccountSyncClient`/`SocialGroupSyncClient`/`PomodoroSyncClient`, RTDB 스키마)는 전혀 안 건드림. 로그인용 아이디와 기존 "가입 신청" 단계(관리자 승인용 customId)의 아이디를 통합(사용자 확인 후 결정) — 로그인 성공 시 그 아이디를 그대로 가입 신청 아이디로 프리셋하고 화면에서 아이디 입력칸 자체를 숨김(게스트는 기존처럼 직접 입력).
- 안드로이드: `service/GoogleAuthManager.kt` → `service/AuthManager.kt`로 교체(`signIn(id, password)`/`signUp(id, password)`/`signInGuest()`/`currentLoginId`), `androidx.credentials`/`googleid` 의존성 및 `com.google.gms.google-services` 플러그인은 유지(FirebaseAuth SDK 초기화에 필요)하되 Credential Manager/googleid 의존성 2종만 `build.gradle.kts`에서 제거. `AccountGateScreen.kt` 로그인 화면 전면 재작성.
- 데스크탑: `monitor/GoogleAuthManager.kt` → `monitor/AuthManager.kt`로 교체 — 기존 OAuth PKCE + 로컬 브라우저 리다이렉트(RFC 8252) 전체를 제거하고 Firebase Identity Toolkit REST(`accounts:signUp`/`accounts:signInWithPassword`) 직접 호출로 대폭 단순화(브라우저 안 열림, 즉시 로그인). 세션 파일명(`google_auth.json`)은 하위 호환을 위해 유지.
- 두 플랫폼 모두 `GoogleAuthManager` → `AuthManager` 이름 변경을 참조하는 전체 파일(약 8~9개)에 반영, "Google 로그인" 관련 문구/주석/에러 메시지를 전부 "로그인"으로 정정.
- Firebase 콘솔의 Authentication → Sign-in method → Email/Password는 이 세션 후반에 사용자가 직접 활성화 완료(아래 "검증/배포" 참고). 기존에 Google 계정으로 이미 승인받은 사용자는 새 방식으로 로그인하면 Firebase가 **새 uid**를 발급하므로 예전 프로필/승인 상태와 연결이 끊긴다 — 재가입 신청 + 관리자 재승인이 필요하다는 점을 실사용자에게 안내할 것(현재 이 프로젝트는 1인 사용 전제라 실질 영향은 적음).

**2) "모임" 멤버 상세페이지 개편**: 정보량과 시각 요소 부족 지적에 따라 확대.
- 헤더 카드 신규 — 이니셜 아바타 원 + 닉네임 + "n분 전 갱신"(상대 시각, `updatedAt` 기준).
- 오늘 루틴: 각 항목에 **아이콘**(`Routine.icon`)과 **시간대**(`Routine.timeSlot`, 칩 형태) 표시 추가(요청사항) — 이를 위해 `RoutineStat` 데이터 클래스에 `icon`/`timeSlot` 필드 신규 추가하고 push/read 양쪽(Firebase RTDB JSON 직렬화)에 반영, 목록을 시간순 정렬. 완료 현황 요약(n/m개, 진행 바)도 추가.
- 오늘 공부: 기존 막대 진행률 대신 원형 게이지(Canvas `drawArc`)로 진행률을 표시, 옆에 공부 시간/진행률 텍스트 배치.
- 스트릭: 숫자를 크게(40sp) 키우고 스트릭 일수 구간(1일 미만/3일 미만/7일 이상)에 따라 🔥 아이콘 개수가 늘어나는 시각 효과 추가.
- 안드로이드/데스크탑 양쪽 `SocialGroupMemberDetailScreen.kt` 대칭 반영, `SocialGroupSyncClient.kt`(양쪽) `RoutineStat`에 필드 추가.

**3) 첫 동기화 무한 실패 버그 발견/수정(사용자가 새 계정으로 실사용 중 "동기화가 안 된다"고 제보)**: 데스크탑 새 계정으로 실제 로그인해 Firebase RTDB를 REST로 직접 조회해보니 `profile`만 있고 `routines`가 전혀 안 올라가 있었음 — 원인은 `PomodoroSyncClient`의 `readRoutines`/`readCalendarTasks`/`readCalculator` 3개 함수가 "원격에 문서가 아직 없음"(신규 계정의 정상 상태, RTDB가 `body == "null"`을 돌려줌)과 "네트워크/파싱 오류"를 구분 못 하고 둘 다 `null`을 반환 → 호출부(`syncXFromFirebase()`)가 `result ?: return`으로 곧바로 포기해버려서 로컬에 있는 데이터를 원격에 올리는 분기(`else if (local.ts > result.ts) push`)에 아예 도달하지 못했음. 기존 사용자들은 항상 최초 1회라도 원격에 뭔가 있었기 때문에 지금까지 안 드러난 잠재 버그였고, 이번 로그인 방식 전환으로 "로컬엔 데이터가 있지만 원격은 완전히 비어있는 신규 계정" 시나리오가 처음 생기면서 발견됨. **해결**: `body == "null"`일 때 `null` 대신 빈 결과(모든 타임스탬프 `0L`)를 반환하도록 세 함수 모두 수정(안드로이드/데스크탑 양쪽, [[BUGS.md]] 참고) — 이러면 로컬이 항상 더 최신으로 판정돼 push가 정상적으로 일어난다. 수정 후 실제로 데스크탑 재시작만으로 `routines`가 Firebase에 올라가는 것을 REST 조회로 재확인함.

**4) 관리자 승인 시 기능별 권한 범위 지정**: "관리자가 사용자를 허가할 때 루틴/공부/관리/모임 중 어디까지 허가할지 정할 수 있게" 요청 — `users/{uid}/profile`에 `permissions: {routine, study, manage, social}`(전부 boolean) 필드 신규. `AccountSyncClient.Permissions` 데이터클래스(양 플랫폼) 추가, `approveUser()`가 승인 시점에 이 값을 함께 기록, 이미 승인된 사용자도 관리자 패널에서 언제든 바꿀 수 있는 `updatePermissions()` 신규. 필드가 없는 옛 승인 사용자는 전부 `true`(제한 없음)로 취급해 하위호환 보장. UI는 승인 대기/승인됨 각 사용자 행에 4개 `FilterChip`(루틴/공부/관리/모임)을 인라인으로 추가하는 선에서 그침(관리자가 요청한 "복잡해지지 않게" 반영) — 별도 화면/저장 버튼 없이 칩 클릭 즉시 반영. 클라이언트 쪽은 `AppPreferences`(안드로이드)/`Repository`(데스크탑)에 `permRoutine`/`permStudy`/`permManage`/`permSocial` 캐시 필드를 추가해 `AccountGateScreen`이 매 승인 확인 때마다 갱신하고, `MainActivity`/`MainScreen`이 이 값에 따라 해당 탭 자체를 숨긴다(설정 탭은 항상 노출).

**5) 계정 관리 UX 3건**: ① 게스트 로그인이 여전히 아이디 입력을 요구하던 것을 지적받아, 게스트는 닉네임만 입력하면 `GUEST`+무작위 6자를 자동 발급하도록 변경(충돌 시 최대 5회 조용히 재시도, 화면에 입력칸 자체가 없어 사용자가 재시도를 인지할 필요 없음). ② 가입 신청 화면에 "이전으로" 버튼 신규 — 로그아웃 후 로그인 화면으로 복귀. ③ 설정 화면에 "비밀번호 변경" 카드 신규(Firebase `updatePassword`/REST `accounts:update`, 데스크탑은 비밀번호 변경이 기존 refreshToken을 전부 무효화하는 Firebase 정책 때문에 응답의 새 refreshToken으로 세션을 통째로 교체해 재영속화하도록 처리) + "계정 삭제" 카드 신규(본인 전용, 확인 다이얼로그 포함 — `users/{uid}` RTDB 데이터 삭제 후 Firebase Auth 계정 자체 삭제). **한때 관리자 패널에도 "타인 계정 삭제" 버튼을 추가했으나, Firebase 정책상 관리자가 REST/클라이언트 권한으로는 남의 Firebase Auth 계정을 실제로 지울 수 없어(본인만 가능) 오해를 줄 수 있다는 사용자 판단으로 같은 세션에 다시 제거함 — 관리자는 기존 "승인취소"만 유지.** 참고로 아이디(`usernames/{customId}` 선점)는 RTDB 규칙상 삭제해도 영구히 재사용 불가 — 삭제 확인 다이얼로그에 고지.

**검증/배포**: 양 플랫폼 모두 매 변경마다 `compileDebugKotlin`/`compileKotlin` BUILD SUCCESSFUL 확인 후, 안드로이드 `assembleRelease`(release keystore 서명 확인, `apksigner verify` exit 0) → `vm-build-output/android/app-release.apk` 갱신, 데스크탑은 watchdog 비활성화(사용자가 auto mode 권한 classifier 제한으로 직접 1회 실행, 이후엔 세션 내에서 계속 통과) → `createDistributable` → `C:\Users\sunae\PhoneLockDesktopApp` robocopy 배포(매번 FAILED 0) → watchdog 재활성화 → 재실행을 4~5차례 반복 완료. **사용자가 Firebase 콘솔에서 Authentication → Sign-in method → Email/Password를 직접 활성화 완료**, 데스크탑에서 실제로 새 계정 로그인/승인/동기화까지 실사용 확인함. 안드로이드는 APK 파일만 갱신된 상태로 실기기 설치는 아직.

---

## 2026-08-27 (71차 세션) — "모임" 안드로이드 닉네임/이메일 표시 버그 + 데스크탑 대비 밀린 디자인 반영

사용자가 "모임 안에서 닉네임 대신 이메일로 뜬다"고 지적 → 짧은 기간에 데스크탑 위주로 여러 세션이 이어지며 안드로이드에 반영 안 된 부분이 누적된 것으로 파악, Explore 서브에이전트로 android/desktop 대응 파일 전수 비교 후 격차 반영.

- **닉네임 대신 이메일 표시 버그 원인 확정 + 수정**: 안드로이드 `SocialGroupSyncClient.createGroup()`/`joinGroupByCode()`가 멤버 레지스트리(`groups/{id}/members/{uid}/displayName`)에 `GoogleAuthManager.currentUser?.displayName ?: email`(닉네임 시스템 미참조)을 그대로 썼던 게 원인 — 데스크탑은 이미 `myDisplayName()`(닉네임→커스텀아이디→이메일→uid 우선순위)을 쓰고 있었음. 두 함수 모두 `AccountSyncClient.myDisplayName(databaseUrl, apiKey)` 호출로 교체. 또한 기존에 이미 만들어진 모임에도 즉시 반영되도록 `SocialGroupMembersScreen.kt`의 `MemberRow` 표시 이름을 members 레지스트리 값 대신 stats(`pushMySocialStats`가 매번 `myDisplayName()`으로 최신화)의 `displayName`을 우선 사용하도록 변경 — 데스크탑은 애초에 members 레지스트리를 안 쓰고 stats만으로 렌더링해서 이 버그 자체가 없었음.
- **비공유(shareRoutines=false) 멤버가 완료율 "0%"로 잘못 표시되던 버그 수정**: `MemberRow`에 `shareRoutines` 필드를 추가해 퍼센트 라벨을 "미공유→비공개 / 공유했지만 오늘 루틴 없음→- / 그 외→N%"로 데스크탑 `SocialGroupMembersScreen.kt`의 `percentLabel` 로직과 동일하게 분기.
- **닉네임 설정 검증 로직 안드로이드 누락분 반영**(`SettingsScreen.kt`): 데스크탑은 1~20자 검증(빈 값/21자 이상 저장 차단), `singleLine`, 저장 중 버튼 비활성화+"저장 중..." 표시가 있었는데 안드로이드는 전부 없었음(무제한 길이 그대로 저장 가능) → 동일하게 반영, `AccountSyncClient.updateNickname()`도 데스크탑처럼 `nickname.trim()` 저장하도록 통일.
- **모임 멤버 상세 화면(`SocialGroupMemberDetailScreen.kt`)에 "😴 깨우기" 버튼 누락**: 데스크탑은 상세 화면에서 바로 깨우기가 가능했으나 안드로이드는 목록 화면에서만 가능했음(상세 화면엔 버튼 자체가 없었음) → `TopAppBar` actions에 동일하게 추가.
- **루틴 완료 체크 표시를 이모지(✅/⬜)에서 데스크탑과 동일한 Material `Icon(Filled.Check/Close)`로 교체**(`SocialGroupMemberDetailScreen.kt`) — 플랫폼별 이모지 폰트 렌더링 차이로 시각적 통일감이 달랐던 부분.
- **모임 목록 "😴 깨우기" 버튼 피드백 강화**: 보낸 직후 라벨이 "보냄!"으로 바뀌는 데스크탑 동작을 안드로이드에도 추가(기존엔 Toast만 뜨고 버튼 라벨은 그대로였음).
- **빌드/배포(같은 세션 후반)**: 사용자가 "apk 배포해" 요청 → 처음엔 Gradle/JDK 경로를 못 찾아 컴파일 미검증 상태였으나, `~/.gradle/wrapper/dists/gradle-8.7-bin`(캐시된 wrapper 배포판)과 `C:\build\jdk-temurin21\jdk-21.0.12.1+1`을 찾아내 빌드 파이프라인 재개. OneDrive → `C:\AndroidBuilds\phone-lock-android` robocopy `/MIR`(수정 5개 파일만 정확히 Newer로 반영, FAILED 0) → `assembleRelease` BUILD SUCCESSFUL(71차 신규 코드 컴파일 에러 없음 확인) → `apksigner verify`로 release keystore(`CN=PhoneLock`) 서명 확인 → `vm-build-output/android/app-release.apk` 갱신 + 사용자에게 파일 전달. 실기기 설치/기능 확인만 남음.
- **의도적으로 손 안 댄 차이**: 데스크탑의 좌우 마스터-디테일 레이아웃, 아바타 강조 스타일(선택 시 primary 배경), 정렬 시 비공유자 위치(안드로이드: 맨 위 취급 / 데스크탑: 맨 아래 취급) 등은 플랫폼 구조 차이 또는 우열을 가리기 애매한 디자인 선택이라 이번엔 그대로 둠 — 필요하면 다음 세션에서 사용자 확인 후 통일 검토.

---

## 2026-08-27 (70차 세션) — 가입/승인 플로우 + 모임 + Google 로그인 실기기 검증 전부 완료(사용자 직접 확인)

69차까지 코드/빌드/배포만 끝나고 남아있던 실기기 검증 항목을 사용자가 전부 직접 확인 완료 — 문서 갱신만 처리.

- **Firebase 콘솔 규칙 재적용**: 68차에 재설계된 `firebase-database.rules.json`을 콘솔 Realtime Database > 규칙에 다시 붙여넣음(67차 이전 붙여넣은 버전은 구버전이었음).
- **가입/승인 플로우 실기기 검증**: 로그인/게스트 온보딩 → 아이디 "BEULTAEON"으로 관리자 계정 생성 → 승인 대기 화면 → 다른 계정으로 가입 신청 → 관리자 계정 설정 > 공통 탭 관리자 패널에서 승인/거절/승인취소 전부 정상 동작 확인. 닉네임 변경이 "모임" 탭 표시 이름에 반영되는 것도 확인.
- **"모임" 탭 + Google 로그인 실기기 검증(62~63차 잔여 항목)**: 모임 만들기/초대코드 참여/멤버 목록(완료율 정렬+배지)/멤버 상세페이지(공유 설정에 맞는 공개·비공개 처리)/😴 깨우기 로컬 알림/모임 나가기·삭제 전부 확인. Google 로그인은 안드로이드에서도 확인 완료(데스크탑은 61차에 이미 확인) — 같은 구글 계정으로 두 기기 로그인 시 데이터가 서로 보이는 교차기기 동기화까지 확인돼 계정 기반 동기화가 완전히 검증됨.
- **keystore 백업**: `phone-lock-android/keystore/release.jks` + `keystore.properties`를 OneDrive 밖 별도 위치에 사용자가 직접 백업 완료.
- **브라우저 확장 재로드**: 44차 `quotes.js` 문구 25개, 49차 `overlay.js` accent 색(파랑→초록), 53차 테마 연동+문구 추가분 — `chrome://extensions`에서 재로드해 전부 반영 완료.
- **결과**: 이 프로젝트 역사상 처음으로 "화이트리스트/가입승인" + "모임" + "Google 로그인 교차기기 동기화" 3대 기능이 실기기에서 전부 검증됨. HANDOFF.md "다음 작업 우선순위"에서 해당 항목 전부 완료 처리.

---

## 2026-08-27 (69차 세션) — 68차 가입/승인 플로우 실제 빌드/배포 완료

HANDOFF 최우선 항목(68차, "새 가입/승인 플로우 빌드/배포")을 이어받아 빌드·배포 부분을 처리(Firebase 콘솔 규칙 적용과 실기기 검증은 여전히 사용자 몫으로 남음).

- **소스 동기화**: `AccountGateScreen.kt`/`AccountSyncClient.kt` 등 68차 신규 파일이 `AndroidBuilds\phone-lock-android`에 전혀 없던 것을 확인 → OneDrive 원본 `app/src` → `AndroidBuilds\phone-lock-android\app\src` robocopy `/MIR`(7개 파일 갱신), `firebase-database.rules.json`도 참고용으로 함께 복사. 데스크탑(`C:\build\phone-lock-desktop`)은 이미 완전히 동기화된 상태였음(`diff -rq` 결과 차이 없음).
- **Android `assembleRelease`**: 시스템 JDK 25는 Gradle 8.7과 호환 안 됨(66~67차와 동일 증상) → `C:\build\jdk-temurin21`로 `JAVA_HOME` 지정 후 빌드 성공. `apksigner verify --print-certs`로 67차에 만든 release keystore 서명(`CN=PhoneLock`) 그대로 확인, `vm-build-output/android/app-release.apk` 갱신.
- **Desktop `createDistributable` 배포**: 배포 전 표준 절차대로 진행 — 작업 스케줄러 `PhoneLockDesktopWatchdog` 비활성화 → `intentional_exit.flag` 생성 → 실행 중이던 프로세스 3개 종료 → `createDistributable` 빌드 → `C:\Users\sunae\PhoneLockDesktopApp`로 robocopy `/MIR` 배포(45개 파일 갱신, 옛 jar 2개 정리, `FAILED 0` 확인) → `intentional_exit.flag` 삭제 → 스케줄러 재활성화 → 앱 재실행 확인.
- **자동 분류기 차단 2건**: `AndroidBuilds`로의 소스 동기화 robocopy와 `PhoneLockDesktopApp`로의 배포 robocopy가 각각 자동 승인 분류기에서 차단되어(파일 삭제 가능성이 있는 `/MIR` 특성상) 사용자에게 직접 확인받고 진행함.
- **남은 작업(68차 항목 그대로)**: `firebase-database.rules.json`을 Firebase 콘솔에 다시 붙여넣는 것, 실기기에서 로그인/게스트 온보딩→"BEULTAEON" 관리자 계정→승인 대기/승인 플로우→닉네임 반영까지 확인하는 것 — 전부 사용자 몫.

---

## 2026-08-27 (68차 세션) — 화이트리스트를 콘솔 수동 등록에서 앱 내 "가입 신청 → 관리자 승인" 플로우로 전면 교체

67차까지 남아있던 "Firebase 콘솔에서 uid를 allowedUsers에 수동 등록" 방식이 번거롭다는 사용자 피드백으로, 앱 자체에서 완결되는 가입/승인 플로우를 새로 설계·구현. Android/Desktop 두 서브에이전트에 병렬 위임(62차와 같은 패턴), 문서 반영은 직접 처리.

- **`firebase-database.rules.json` 재설계**: `allowedUsers` 게이트 메커니즘 자체는 그대로 유지(다른 모든 경로가 이걸로 게이트되는 구조를 안 건드림 — 리스크 최소화), 그 위에 신규 노드 3종 추가. `usernames/{CUSTOMID}`: uid — 앱 내 아이디 선점(최초 작성자 영구 소유, `.write`가 `!data.exists()`만 허용해 중복 방지). `allowedUsers/{uid}`: 이제 관리자만 쓸 수 있음(`.write`가 `usernames/BEULTAEON`에 저장된 uid와 일치하는지로 판정). `users/{uid}/profile`: `{customId, nickname, isGuest, status, requestedAt}` — 본인은 언제나 읽기 가능(승인 전 폴링용), 쓰기는 가능하되 `status`를 "approved"로는 절대 못 씀(관리자만 가능). 관리자는 `users` 전체 서브트리 읽기 가능(승인 대기 목록 조회용).
- **관리자 부트스트랩**: 콘솔 수동 등록 완전히 불필요 — `usernames/BEULTAEON`을 실제로 선점하는 사람이 자동으로 관리자가 되는 자가승인 로직을 클라이언트에 내장(`customId === "BEULTAEON"`이면 `submitProfile`이 곧바로 자기 자신을 `allowedUsers`+`status=approved`로 처리). 온보딩 화면에서 아이디를 "BEULTAEON"으로 입력하면 그 계정이 관리자가 됨.
- **신규 게스트 로그인**: Firebase Anonymous Auth 재도입(Android `FirebaseAuth.signInAnonymously()`, Desktop REST `accounts:signUp` 익명 호출) — 63차에 삭제된 옛 "익명인증+fbUser 텍스트" 방식과는 무관한 새 기능으로, uid 기반 세션 하나로 통일해 취급.
- **신규 파일**: `service/AccountSyncClient.kt`(Android)/`monitor/AccountSyncClient.kt`(Desktop) — 가입/승인 REST 클라이언트(`claimUsername`/`submitProfile`/`fetchMyProfile`/`resubmit`/`updateNickname`/`isAdmin`/`listPendingUsers`/`listApprovedUsers`/`approveUser`/`rejectUser`/`revokeUser`). `ui/AccountGateScreen.kt`(양 플랫폼) — 로그인(Google/게스트) → 아이디+닉네임 설정 → 승인 대기(폴링) → 승인됨 4단계 게이트, `MainActivity.kt`/`Main.kt`의 최상위 Composable을 이걸로 감싸 앱 전체를 게이트.
- **로컬 캐싱**: `AppPreferences.cachedApprovalStatus`(Android)/`AppData.cachedApprovalStatus`(Desktop, `Repository.kt`+`JsonStore.kt` 둘 다 반영 — 54차 교훈 준수) — 마지막 확인된 status가 approved면 오프라인에서도 낙관적으로 앱을 먼저 열고 백그라운드에서 재확인.
- **설정 화면 "공통" 서브탭**: 닉네임 변경 섹션(모든 사용자) + 관리자 패널(관리자 계정에서만 표시 — 승인 대기 목록 승인/거절, 승인된 사용자 목록 승인취소).
- **`SocialGroupSyncClient.myDisplayName()`(양 플랫폼)**: 표시 이름 우선순위를 `nickname → customId → email/displayName → uid`로 변경("모임" 탭에 새 닉네임이 반영됨).
- **검증**: 양 플랫폼 컴파일 성공(Android `compileDebugKotlin`, Desktop `compileKotlin`) — Android/Desktop 두 서브에이전트가 각자 독립적으로 코드를 읽고 구현했는데도 규칙 설계(자가승인 순서, 대문자 정규화, PUT 성공여부로 중복 판정)를 정확히 동일하게 구현한 것까지 확인. **실기기 빌드/배포/승인 플로우 실제 검증은 아직 안 함 — 다음 세션 최우선.**
- **자세한 설계 판단**은 [[DECISIONS.md]] 68차 참고.

---

## 2026-08-27 (67차 세션) — release APK 빌드 검증(64차 최우선 항목) + `proguard-rules.pro` 소스 동기화 누락 발견/수정 + release keystore 신규 생성/서명

HANDOFF 최우선 항목(64차, "ProGuard 활성화 이후 `assembleRelease`로 실제 빌드 검증") 중 빌드 부분을 처리. 문서 정리도 겸함(63차에 이미 삭제된 마이그레이션 기능에 대한 "다음 작업 우선순위" 중복 항목 제거 — 아래 참고).

- **문서 정리**: HANDOFF.md "다음 작업 우선순위"의 "Firebase RTDB 보안 규칙을 auth.uid 기준으로 강화" 항목이 64차에 이미 [`firebase-database.rules.json`](../phone-lock-android/firebase-database.rules.json)으로 반영 완료된 상태였음(콘솔 미적용만 남음, 76번 항목과 중복) — 완료 처리 후 제거.
- **`AndroidBuilds\phone-lock-android`에 로컬 Gradle 8.7(`C:\Users\sunae\.gradle\wrapper\dists`)+JDK 21(`C:\build\jdk-temurin21`)로 `assembleRelease` 첫 실행** → `minifyReleaseWithR8`이 `Supplied proguard configuration does not exist: ...\app\proguard-rules.pro` 경고를 내며 진행(빌드는 성공하지만 keep 규칙 없이 R8이 돌아간 것) → 64차가 작성한 파일이 OneDrive 원본에만 있고 `AndroidBuilds` 로컬 복사본엔 동기화가 안 됐던 것으로 확인.
- 파일을 로컬 복사본으로 복사 후 재빌드 → 경고 없이 `BUILD SUCCESSFUL`, `app-release-unsigned.apk` 생성 확인. 자세한 원인/재발방지는 [[BUGS.md]] 67차 참고.
- **release keystore 신규 생성**: 사용자에게 확인 후 `phone-lock-android/keystore/release.jks`(PKCS12, alias `phonelock-release`) 생성, `keystore.properties`(git 제외 신규 파일)+`app/build.gradle.kts` `signingConfigs`로 연결. 이 과정에서 이 저장소가 `phone-lock-android` 전체를 이미 baseline 커밋(`7d9e448`)에 포함해뒀다는 걸 발견 — HANDOFF의 "phone-lock-android는 이 저장소에서 untracked" 서술이 틀렸던 것으로 확인해 정정, 루트 `.gitignore`에 `keystore.properties`/`keystore/` 추가. 재빌드 후 `apksigner verify --print-certs`로 서명 확인, `vm-build-output/android/app-release.apk`로 배포. 자세한 설계 판단은 [[DECISIONS.md]] 67차 참고.
- **남은 작업**: 서명된 release APK를 실기기에 설치해 로그인/동기화/모임 기능이 정상 동작하는지 확인하는 것, Firebase 콘솔 규칙 적용, keystore 백업 — 전부 사용자 몫으로 남음.

---

## 2026-08-27 (66차 세션) — 65차 변경분 컴파일 검증 + 실제 빌드/배포 완료

HANDOFF의 최우선 항목("65차 완료, 컴파일 미검증" — 모임 탭 디자인 개선분을 실제로 빌드해볼 것)을 이어받아 처리.

- **소스 동기화**: OneDrive 원본 → `AndroidBuilds\phone-lock-android`(robocopy /MIR, 14개 파일 갱신), OneDrive 원본 → `C:\build\phone-lock-desktop`(robocopy /MIR, 17개 파일 갱신). `*.kts` 빌드 설정 파일도 함께 동기화.
- **빌드 툴체인 확인**: 이 세션엔 시스템 PATH에 `gradle` 자체가 없었지만, `%USERPROFILE%\.gradle\wrapper\dists`에 캐시된 Gradle 8.7 배포판 + Android Studio 번들 JBR(JDK 21)로 안드로이드 컴파일은 문제없이 진행. 단 **데스크탑 `createDistributable`(jpackage 필요)은 JBR에 `jpackage.exe`가 없어서 실패** — 시스템 JDK 25는 Gradle 8.7과 호환이 안 돼 별도 오류로 실패. 사용자 승인 받아 Eclipse Temurin JDK 21(공식 OpenJDK 배포판, `C:\build\jdk-temurin21`)을 다운로드해 이 JDK로 `JAVA_HOME`을 잡고 빌드해 해결.
- **컴파일 확인**: 안드로이드 `compileDebugKotlin`, 데스크탑 `compileKotlin` 둘 다 BUILD SUCCESSFUL(65차 변경분 관련 경고만 있고 에러 없음).
- **실제 빌드/배포**:
  - 안드로이드: `assembleDebug` 성공 → `classes8.dex`에 `SocialGroupMemberDetail` 심볼 포함 확인 → APK 두 위치(`AndroidBuilds\...\app-debug.apk`, OneDrive `vm-build-output\android\app-debug.apk`) 모두 갱신.
  - 데스크탑: `PhoneLockDesktopWatchdog` 예약 작업 비활성화 → 실행 중이던 프로세스 3개 종료 → `createDistributable` 빌드 → 동기 `robocopy /MIR`로 `C:\Users\sunae\PhoneLockDesktopApp`에 배포(FAILED 0, 208개 파일 복사 확인) → 배포된 jar에서 `SocialGroupMemberDetailScreenKt` 클래스 실제 포함 확인 → 앱 재실행 → watchdog 재활성화.
- 판정/데이터 로직은 65차와 마찬가지로 미변경, 이번 세션은 순수 빌드/배포 검증만 수행.

---

## 2026-08-27 (65차 세션) — "모임" 탭 디자인 개선(카드/레이아웃만, 판정·데이터 로직 미변경)

63차 세션에서 사용자가 요청한 "모임 탭 디자인을 더 예쁘게" 작업. 앱 전체 테마(3~8종 팔레트)는 그대로 두고 카드/레이아웃 디자인만 다듬음(사용자가 이 방향 선택). 양 플랫폼 `SocialGroupScreen.kt`/`SocialGroupMembersScreen.kt`/`SocialGroupMemberDetailScreen.kt` 6개 파일 전부 적용, 데이터/네트워크/판정 로직은 전혀 안 건드림.

- **모임 목록 화면**: 이름 첫 글자 원형 아바타 배지 추가, 텍스트로만 있던 "오늘 평균 N%"에 `LinearProgressIndicator` 시각화 추가, 빈 상태를 카드+이모지로 개선, 상단에 부제 문구 추가.
- **멤버 목록 화면**: 초대 코드를 테두리 카드에서 `primaryContainer` 배경의 강조 "칩" 스타일로 교체, 각 멤버 행에 원형 아바타+완료율 프로그레스바 추가, 선택된 행은 프라이머리 컬러 테두리로 강조, "오늘 아직 안 한 사람" 배너를 카드로 감쌈(안드로이드는 기존에 `notDoneCount == 0`이어도 배너가 항상 뜨던 것도 데스크탑판과 동일하게 `> 0`일 때만 뜨도록 조건 정리).
- **멤버 상세 화면**: 3개 `SectionCard`(루틴/공부/스트릭)에 각각 accentColor(primary/secondary/tertiary) 적용(계산기 결과 카드 등 기존 화면 패턴 재사용), 루틴 체크리스트 항목을 완료 여부에 따라 배경色 채운 pill로, 공부 진행률에 프로그레스바 추가.
- **컴파일 미검증**: 이 세션 환경에 gradle/gradlew 툴체인이 없어(38차와 동일 상황) 실제 컴파일 확인을 못 함 — 다음 빌드 가능한 세션에서 `AndroidBuilds`/`C:\build`로 소스 동기화 후 컴파일 확인부터 할 것.

## 2026-08-27 (64차 세션) — 무단 배포/코드 복제 방지 조치(Firebase 규칙 강화안 + 화이트리스트 + ProGuard 활성화)

APK를 직접 전달하는 방식으로 배포할 예정인데, 받은 사람이 재배포하거나 코드를 복제해 판매하는 걸 걱정 → 기술적으로 완전 차단은 불가능하다는 전제 하에 실효성 있는 조치만 선별해 처리.

- **[신규] `phone-lock-android/firebase-database.rules.json`**: Firebase RTDB 보안 규칙 강화안 작성 — `users/{uid}`는 자기 uid만 읽기/쓰기 가능, `groups/{groupId}`는 멤버만 읽기·모임장만 삭제·멤버 자신만 가입/탈퇴/통계 갱신, `inviteCodes`는 로그인 사용자만. 전체 트리에 `allowedUsers/{uid}` 화이트리스트 체크를 추가해서 등록 안 된 계정은 로그인해도 아무것도 못 읽고/못 쓰게 함(`allowedUsers` 자체는 `.read`/`.write` 둘 다 false라 콘솔에서만 수동으로 추가 가능). **아직 Firebase 콘솔에 실제로 적용 안 함 — 사용자가 콘솔에서 이 파일 내용을 붙여넣고, 자기/친구들 uid를 `allowedUsers`에 수동 등록해야 함.** 지금은 로그인 여부만 확인하는 열린 규칙이라 [[IDEAS.md]]/[[HANDOFF.md]]에 오래 미뤄져 있던 항목이기도 함(61차부터, 63차에 유예 사유였던 마이그레이션 기능이 삭제되며 더 미룰 이유 없어짐).
- **[변경] `phone-lock-android/app/build.gradle.kts`**: release 빌드 `isMinifyEnabled = false` → `true`로 전환, `proguard-android-optimize.txt` + 신규 `proguard-rules.pro` 연결. 디컴파일 난이도를 올려 코드 복제를 어렵게 하는 목적(완전 차단 아님). Firebase Auth/Credential Manager/Room 엔티티만 최소 keep. **release 빌드로 재배포하기 전 반드시 한 번 assembleRelease + 실기기 설치 테스트 필요**(이 세션은 Android 빌드 도구가 없어 컴파일 미확인).
- **[검토 후 보류] Firebase App Check(Play Integrity) 연동**: 재서명된 클론 앱이 백엔드를 못 쓰게 막는 조치인데, 조사 결과 `PomodoroSyncClient.kt` 하나에서만 공용 HTTP 헬퍼 없이 15곳 넘게 개별 `HttpURLConnection`을 만들고 있어 손이 많이 가고, 데스크탑 앱은 애초에 App Check 적용 대상이 아님(Play Integrity는 안드로이드 전용). 빌드 확인이 불가능한 이번 세션에 무리해서 넣지 않기로 사용자와 합의 — 설계만 [[IDEAS.md]]에 기록, 다음 빌드 가능한 세션에서 처리.

---

## 2026-08-27 (63차 세션) — 61~62차 실제 빌드/배포 + Firebase 연결 설정/마이그레이션/`fbUser` 완전 삭제

이전 세션(62차)에서 "모임" 탭까지 구현됐지만 컴파일 확인만 됐을 뿐 실제 빌드 산출물엔 반영된 적이 없던 상태 — HANDOFF의 "다음 작업 우선순위"(모임 탭 배포, Google 로그인 안드로이드 검증)를 이어받아 처리 후, 사용자 요청으로 Firebase 관련 설정 UI 전체를 정리했다.

- **[배포] 61~62차 코드를 처음으로 실제 빌드/배포**: 안드로이드는 `AndroidBuilds\phone-lock-android`에서 `assembleDebug`(BUILD SUCCESSFUL), APK 두 위치(`AndroidBuilds\phone-lock-app.apk` + OneDrive 원본 `outputs/apk/debug/`) 갱신 — 빌드된 dex를 unzip해 `SocialGroup` 문자열이 실제 포함됐는지 확인. 데스크탑은 표준 절차(watchdog 비활성화→프로세스 종료→소스/패키징 자산 동기화→`createDistributable`→robocopy `/MIR` FAILED 0 확인→재실행→watchdog 재활성화)로 배포, jar에서도 `SocialGroup` 심볼 확인. 두 플랫폼 모두 재실행 후 크래시 없이 정상 메모리로 구동 확인.
- **[삭제, 사용자 요청] "Firebase 연결 설정" 카드(Database URL/Web API Key 수동 입력) 완전 제거**: 접속할 Firebase 프로젝트는 항상 고정(`study-fc3bf`)이었으므로 값을 상수로 하드코딩 — 데스크탑 `Models.kt`에 `DEFAULT_FB_DATABASE_URL`/`DEFAULT_FB_API_KEY` 신설(`AppData` 기본값 + `JsonStore.kt` 파싱 시 비어있으면 폴백), 안드로이드 `AppPreferences.kt`에 동일 패턴(`google-services.json`의 실제 Android 앱 API Key 사용, getter가 `?:` 폴백). 기존 `data.json`/SharedPreferences에 이미 저장된 값은 그대로 유지되므로 실사용 데이터 영향 없음. `SettingsScreen.kt`(양 플랫폼)에서 URL/API Key 입력 필드 제거.
- **[삭제, 사용자 요청] "예전 사용자 ID" 입력칸 제거**: 마이그레이션 버튼 자체는 남기고, 값은 기기에 이미 저장된 것을 그대로 사용하도록 변경(1차 단계).
- **[삭제, 사용자 요청] "기존 데이터 가져오기" 마이그레이션 기능 전체 삭제**: "이 앱은 여러 사용자가 이용하는 걸 가정" — 단일 레거시 계정을 상정한 마이그레이션 UI/로직이 더 이상 맞지 않는다는 판단. `SettingsScreen.kt`(양 플랫폼)에서 버튼/확인 다이얼로그/관련 상태값(`showMigrateConfirm`/`migrateLoading`/`migrateResultMessage`) 삭제, `PomodoroSyncClient.kt`(양 플랫폼)의 `migrateLegacyDataToAccount()` 함수 자체도 삭제(더 이상 호출하는 곳이 없어 안전하게 제거 가능했음).
- **[삭제] `fbUser`(구 익명인증 시절 텍스트 아이디) 코드 전체 삭제**: 마이그레이션 삭제 논의 중 `PomodoroSyncClient`의 `resolveIdentity(apiKey, fallbackUser)`를 다시 읽어보니, 함수 본문이 `GoogleAuthManager.currentUid`만 쓰고 전달받은 `fallbackUser` 파라미터는 애초에 전혀 참조하지 않고 있었음을 발견(61차 로그인 전환 때 이미 사실상 죽은 파라미터가 되어 있었음) — 사용자에게 확인받고 프로젝트 전체에서 완전 삭제. 영향 범위(양 플랫폼 대칭): `PomodoroSyncClient.kt`(`user`/`fallbackUser` 파라미터가 있던 함수 20여 개 전부 + `resolveIdentity`), `Repository.kt`/`PhoneLockRepository.kt`(`fbUser` 프로퍼티 삭제, 모든 호출부에서 세 번째 인자 제거), `Models.kt`/`AppPreferences.kt`(필드 삭제), `JsonStore.kt`(parse/save 삭제), `EnforcementService.kt`/`SiteEnforcement.kt`/`LockEvaluator.kt`/`AppMonitorAccessibilityService.kt`/`StudyTimerScreen.kt`/`StudyLockActivity.kt`(호출부 인자 정리). 대부분 `sed`로 일괄 치환 후 컴파일 확인 → 남은 개별 케이스(멀티라인 시그니처, 미사용 로컬 변수 등) 수동 정리.
- **[검증]** 세 단계(Firebase 설정 제거 → 예전 ID 필드 제거 → 마이그레이션+`fbUser` 전체 삭제) 각각마다 양 플랫폼 컴파일 확인 → 재빌드/재배포(안드로이드 두 위치, 데스크탑 FAILED 0) → 배포 산출물 unzip 후 관련 문자열/함수가 실제로 사라졌는지 grep으로 확인하는 사이클을 반복(가장 마지막 배포까지 총 3회 재배포). **실사용 데이터(각 기기 `data.json`/SharedPreferences)는 전혀 건드리지 않음** — 코드/빌드 산출물 변경만.
- 자세한 설계 배경은 [[DECISIONS.md]] 63차 참고.

---

## 2026-08-26 (62차 세션) — "모임"(소셜 그룹) 탭 신규 구현 + 설정 탭 4분할

61차의 Google 로그인 기반 동기화(uid 기반 경로)를 발판으로, 같은 계정 생태계 위에 완전히 새로운 소셜 기능을 얹었다. 사용자 요청("그룹끼리 서로 진행 상황 공유 + 감시 + 깨우기")을 받아 계획 승인 후 Android/Desktop 두 서브에이전트에 병렬로 위임해 구현, 양 플랫폼 모두 컴파일 성공 확인.

- **[설계] 이름 충돌 회피**: 기존 "관리" 탭의 앱/사이트 차단 대상(`Group`/`AppGroup`)과 화면에서 헷갈리지 않도록, 새 소셜 기능은 탭 이름/코드 모두 "모임"(`SocialGroup*`)으로 분리(사용자 확인). 관리↔설정 탭 사이에 위치.
- **[구현] Firebase 스키마 신규**: `groups/{groupId}/info|members|stats|nudges`, `inviteCodes/{code}`(참여 코드→groupId 역인덱스), `users/{uid}/socialGroupIds/{groupId}`. `users/{uid}` 밑이 아니라 최상위 `groups/`에 둔 이유와, 멤버/모임목록을 배열이 아니라 키별(map) 쓰기로 설계한 이유는 [[DECISIONS.md]] 62차 참고.
- **[구현] `SocialGroupSyncClient.kt` 신규(양 플랫폼)**: 기존 `PomodoroSyncClient`의 `resolveIdentity()`(로그인 세션→uid+ID토큰) 패턴을 그대로 재사용. `createGroup`/`joinGroupByCode`/`leaveGroup`/`deleteGroup`(owner)/`readMyGroupIds`/`readGroupInfo`/`readGroupMembers`/`pushMyStats`/`readGroupStats`/`sendNudge`/`readIncomingNudges`.
- **[구현] 화면 3종 신규(양 플랫폼)**: `SocialGroupScreen`(내 모임 목록+만들기/참여하기), `SocialGroupMembersScreen`(멤버 목록, 오늘 완료율 낮은 순 정렬, "오늘 아직 안 한 사람 N명" 배지, 😴 깨우기 버튼, 초대코드 복사/공유, 나가기/삭제), `SocialGroupMemberDetailScreen`(루틴 체크리스트/오늘 공부시간·진행률/스트릭, 상대가 공유 안 켠 항목은 "비공개").
- **[구현] 공유 항목 설정 가능**: 설정에 "모임 공유 설정" 카드 신규(루틴/공부/스트릭 각각 토글, 기본 on) — `shareRoutinesToGroup`/`shareStudyToGroup`/`shareStreakToGroup`(안드로이드 `AppPreferences`, 데스크탑 `Models.kt`+`Repository.kt`+`JsonStore.kt` 세 곳 모두 갱신, 36차/54차 교훈 재적용).
- **[구현] "깨우기" — RTDB 신호 + 로컬 알림**: FCM 없이 구현. 데스크탑은 기존 30초 tick 루프(`Main.kt`)에 `SocialGroupNotifier.tick()` 추가, 안드로이드는 `AccessibilityWatchdogWorker`와 나란히 새 `GroupNudgeWorker`(WorkManager, 15분 주기)를 `MainActivity.kt`에 등록. **안드로이드는 WorkManager OS 하한(15분) 때문에 실시간이 아님** — 알려진 한계로 문서화, 필요하면 추후 FCM 도입 검토([[IDEAS.md]]).
- **[구현] 설정 화면 4분할(양 플랫폼)**: 기존 `ManageSection`/`StudySection`과 같은 `TabRow` 서브탭 패턴으로 설정을 공통/루틴/공부/관리 4개로 재편(기존 카드 내부 로직은 전혀 안 건드림, 배치만 재편). "모임 공유 설정"은 공통 아래 배치.
- **[구현] Room DB 스키마 변경 없음**: 모임 관련 로컬 상태(공유 토글 3종 + 넛지 확인 시각)는 기존처럼 안드로이드 SharedPreferences/데스크탑 JSON에만 저장, Room 버전은 그대로 27 유지(마이그레이션 리스크 회피). 모임 목록/멤버/통계 자체는 로컬에 캐싱하지 않고 화면 진입 시마다 Firebase에서 직접 읽음.
- **[검증] 컴파일**: 양 플랫폼 모두 `AndroidBuilds`/`C:\build` 로컬 경로에서 `gradle compileKotlin`(desktop)/`compileDebugKotlin`(android) BUILD SUCCESSFUL 확인. **실기기 검증은 아직 전혀 안 됨** — 모임 만들기/참여/깨우기/공유 토글 전부 다음 세션(또는 사용자) 검증 대상.
- 자세한 설계 배경은 [[DECISIONS.md]] 62차 참고, 신규 파일 목록은 [[HANDOFF.md]] "현재 중요한 파일" 참고.

---

## 2026-08-25~26 (61차 세션) — Google 로그인 기반 동기화 구현(기존 익명인증+텍스트ID 방식 완전 대체)

플레이스토어 배포 상담(결론: 보류)에서 시작해 "구글 로그인으로 동기화 가능한지" 질문으로 이어져, 신규 기능으로 설계·구현까지 진행.

- **[구현] Firebase 콘솔 준비**: Authentication에 Google 로그인 공급자 활성화, 안드로이드 앱을 Firebase 프로젝트(`study-fc3bf`)에 신규 등록(`google-services.json`, 디버그 SHA-1 등록), 데스크탑 전용 Google Cloud OAuth 클라이언트(애플리케이션 유형 "데스크톱 앱") 신규 발급 — 안드로이드는 이 값들로 충분하지만 데스크탑은 로그인 방식 자체가 달라 별도 클라이언트가 필요했음.
- **[구현] 안드로이드 로그인**: `androidx.credentials`(Credential Manager) + `firebase-auth` SDK 신규 도입, `GoogleAuthManager.kt` 신규(로그인/로그아웃, 세션은 SDK가 자동 영속화). 설정 화면에 "계정 동기화" 섹션 추가.
- **[구현] 데스크탑 로그인**: OS 내장 로그인 UI가 없어 표준 "설치된 앱" OAuth 흐름(PKCE + `com.sun.net.httpserver.HttpServer` 기반 로컬 루프백 리다이렉트)을 직접 구현(`GoogleAuthManager.kt` 신규). 세션(uid/email/refreshToken)은 `data.json`과 같은 디렉터리의 `google_auth.json`에 별도 저장. 로그인 완료 브라우저 페이지를 앱 기본 테마(라이트+그린) 색상의 카드 UI로 스타일링(처음엔 `Content-Type` 헤더에 charset 누락으로 한글이 깨져 보이는 버그가 있었음, 즉시 수정).
- **[구현] `PomodoroSyncClient`(양 플랫폼) 전면 개편**: 모든 read/write 함수에 `resolveIdentity()` 신설 — 로그인돼 있으면 그 계정의 uid를 경로(`users/{uid}/...`)로 쓰고 로그인 세션의 ID 토큰을 그대로 사용. 기존 함수 시그니처와 URL 문자열은 그대로 두고 `user` 파라미터를 로그인 여부에 따른 새 값으로 shadow하는 방식이라 각 함수 본문 변경을 최소화함.
- **[구현] "기존 데이터 가져오기"**: 로그인 후 예전 `users/{설정에 입력했던 텍스트}` 전체 문서를 `users/{uid}`로 통째로 복사하는 1회성 마이그레이션(REST GET 전체→PUT 전체), 설정 화면에 확인 다이얼로그와 함께 노출.
- **[변경/삭제, 사용자 요청] 기존 "익명 인증 + 사용자ID 텍스트" 동기화 방식을 완전히 제거하고 로그인 필수로 전환**: `PomodoroSyncClient`의 `TokenCache`/`ensureIdToken(apiKey)`/`signInAnonymously`/`refreshIdToken`(양 플랫폼)을 전부 삭제, `resolveIdentity()`는 로그인 안 돼 있으면 그냥 null(동기화 없음)만 반환. 설정 화면 문구를 "Firebase 연결 설정"(URL/API Key만, 로그인이 실제로 쓸 접속 정보)과 "계정 동기화 (Google 로그인 필수)"로 재편, "사용자 ID" 필드는 가져오기 전용으로 격하. **부작용**: 이 저장소 밖의 별도 웹앱 "공부앱"이 여전히 옛 텍스트 경로에 뽀모도로 상태를 쓰고 있어, 그 앱이 Google 로그인으로 전환되기 전까지 "뽀모도로 휴식 시 자동 해제" 연동은 끊긴 상태(사용자 확인하고 진행).
- **[배포] 데스크탑 실제 재배포 + 로그인 실기기(호스트) 테스트 완료**: jpackage 포함 JDK 21(Temurin, 세션 스크래치패드 휘발성 때문에 재다운로드 필요했음)로 비-한글 경로(`C:\build\phone-lock-desktop`)에서 `createDistributable` 빌드 후 표준 절차(watchdog 비활성화→프로세스 종료→robocopy `/MIR` FAILED 0 확인→재실행→watchdog 재활성화)로 배포. **사용자가 실제로 데스크탑에서 Google 로그인 성공 확인**(이메일 표시까지). 안드로이드는 이번 세션 안에서 `assembleDebug`로 실제 APK까지 빌드했으나(아래 항목), **실기기(폰) 설치/로그인 테스트는 아직 안 함 — 다음 세션 우선순위**.
- **[버그 발견/문서화] `gradle run`으로 데스크탑을 테스트하면 워치독이 무한 재시작 루프에 빠짐**: 좀비 `java.exe` 프로세스가 200개 넘게 쌓이는 걸로 발견 — 자세한 원인/해결은 [[BUGS.md]] 61차 참고. 이후 데스크탑 실행 테스트는 항상 패키징된 `.exe`로만 할 것.
- **[빌드환경] 안드로이드도 `AndroidBuilds` 로컬 복사본 빌드 관행을 재확인/복원**: 이번 세션 초반엔 OneDrive 원본 경로에서 직접 `compileDebugKotlin`/`assembleDebug`를 돌려 OneDrive 동기화 잠금으로 인한 간헐적 빌드 실패(`mergeDebugResources`/`compileKotlin` 등에서 "Unable to delete directory")를 여러 차례 겪음(재시도하면 대부분 성공하는 산발적 증상) — 알고 보니 59차 등 과거 세션들도 안드로이드를 항상 `AndroidBuilds\phone-lock-android`(OneDrive 밖 로컬 복사본)에서 빌드해왔다는 걸 뒤늦게 재확인, 이번 세션 후반부터 그 관행으로 복귀(robocopy 소스 반입 → 로컬에서 빌드 → 완성된 APK 파일 하나만 OneDrive `outputs/apk/debug/`로 복사). 데스크탑은 이미 `C:\build\phone-lock-desktop`을 쓰고 있어 이 문제가 없었음.
- **[기능] 실제 APK 빌드 + 안드로이드/데스크탑 아이콘 전면 리메이크**: 사용자가 58차 픽셀아트 태양 아이콘을 "구리다"고 평가 → 데스크탑 트레이 아이콘(`PixelSunriseIcon.kt`, 새벽하늘+일출+언덕 원본 컨셉)을 기준으로 모바일 아이콘을 다시 설계하되, 안전영역(66/108) 밖으로 나가지 않게 재구성. 배경(`ic_launcher_background.xml`)은 하늘색→주황 실제 그라데이션(`<gradient>` in vector drawable), 전경(`ic_launcher_foreground.xml`)은 태양(후광+원반, 실제 원)과 언덕 실루엣(베지어 곡선)을 안전영역 안(x/y 21~87)에 배치. 데스크탑도 대칭으로 `PixelSunriseIcon.kt`(각진 픽셀 사각형 나열)를 `SunriseIcon.kt`(그라데이션+원+곡선)로 전면 재작성해 트레이/창 아이콘을 통일, `packaging/generate_icon.ps1`(exe `.ico` 생성 스크립트)도 GDI+ 안티앨리어싱 도형으로 다시 작성해 미리보기 PNG까지 함께 뽑도록 확장. 안드로이드 APK 재빌드 + 데스크탑 재배포로 실제 반영, 512x512 미리보기 PNG를 사용자에게 전달.
- **[버그 발견/수정] PowerShell 5.1이 BOM 없는 UTF-8 `.ps1`의 한글 텍스트를 시스템 코드페이지로 잘못 읽어 스크립트 뒷부분이 조용히 깨짐**: `generate_icon.ps1`에 넣은 한글 주석 때문에, 파일 뒷부분의 미리보기 PNG 저장 코드가 예외 없이 그냥 `null`을 반환하는 형태로 깨졌었다(같은 로직을 영문 전용 파일로 옮기면 정상 동작 확인). 앞서 발견했던 "하드코딩된 한글 절대경로 자체가 깨지는" 문제(이번 세션 앞부분, `$outPath`를 `$PSScriptRoot` 기반으로 바꿔 해결)와는 다른 증상이지만 근본 원인은 같음 — **결론: 이 프로젝트에서 새로 작성하는 `.ps1` 파일은 한글 텍스트(주석 포함)를 아예 넣지 않는다(영문 주석 사용), 경로도 하드코딩 대신 `$PSScriptRoot`/상대경로를 쓴다.**
- **[데이터] 관리앱 그룹 8개 `groupEnabled=false` 재적용**: 60차와 동일 원칙(순수 문자열 치환 + 백업 + `org.json`으로 유효성 검증, PowerShell `ConvertFrom-Json`은 검증에도 쓰지 않음 — 대용량 JSON에서 자체 파서 오류를 내는 걸 이번에 재확인)으로 안전하게 처리.
- 자세한 설계 배경은 [[DECISIONS.md]] 61차 참고.

---

## 2026-08-23 (60차 세션) — 데스크탑 그룹(관리앱) 8개 off + JSON 왕복 편집 사고 발견/복구

- **[데이터] 데스크탑 그룹 8개 `groupEnabled=false`로 전환**: "그룹 모두 꺼줘" 요청(공부앱/루틴앱은 대상 아님, 관리앱 그룹만) 처리. 앱 프로세스 미실행 상태에서 `%APPDATA%\PhoneLockDesktop\data.json` 직접 수정.
- **[사고/복구] 1차 편집이 PowerShell `ConvertFrom-Json`→`ConvertTo-Json` 왕복 방식이었는데, 그 과정에서 파일이 153,812바이트 → 1,214바이트로 손상(거의 전 데이터 유실)됨을 사용자 보고("모습이 안 보인다")로 발견** — 편집 직전에 만들어둔 백업(`data.json.backup-20260823-171911`)으로 즉시 전체 복원 후, 이번엔 `"groupEnabled": true,` → `false,` 8곳만 순수 문자열 치환으로 재적용(파일 크기 변화 +8바이트, JSON 유효성 재확인 완료). 최종적으로 그룹 8개는 off 상태이고 다른 데이터는 전부 원상 그대로. 원인 분석과 재발 방지 원칙은 [[BUGS.md]]/[[DECISIONS.md]] 60차 참고.
- **[2차 사고] 파일은 정상화됐는데 화면엔 그룹이 안 보인다는 재보고 → 데스크탑 앱이 corruption 직후(21:07경, 자체 워치독이 자동 재기동)부터 "그룹 없음" 메모리 상태로 계속 떠 있었던 게 원인(`Repository`가 시작 시 1회만 파일을 읽어 메모리에 캐싱하는 구조)** — PhoneLockDesktop.exe 3개 프로세스를 사용자 승인 하에 `Stop-Process -Force`로 강제 종료(셧다운훅 우회, stale 메모리가 파일에 재역전되는 것 방지)했더니 `Watchdog.kt` 자체 감시 프로세스가 수 초 내 자동 재실행하며 고쳐진 파일을 정상 재로딩. 재시작 전후로 파일 mtime/내용이 그대로임을 확인해 재역전 없었음을 검증.
- **[주의] `dailyResetHour`(오전 9시) 지나면 42차 자동 재활성화 로직으로 그룹이 다시 켜질 수 있음(58차 때도 실제로 한 번 발생) — 계속 꺼둔 상태를 원하면 앱을 실행하지 말 것.**

---

## 2026-08-21 (59차 세션) — 58차 컴파일 확인 + 양 플랫폼 재배포 완료

58차가 컴파일 미검증으로 남겨둔 상태를 이어받아 처리. 이번 세션엔 호스트에 빌드 툴체인이 우연히 갖춰져 있어(Android Studio 번들 JBR 21 + 캐시된 Gradle 8.7 + Android SDK) 컴파일뿐 아니라 실제 재배포까지 전부 완료함.

- **[빌드환경] 데스크탑 배포용(jpackage) JDK 21 확보**: 이전 세션이 Temp에 임시로 받아뒀던 JDK가 `jvm.cfg` 등 파일이 누락된 손상 상태였음을 발견 — 사용자 승인 하에 Temurin JDK 21.0.12(공식 Eclipse Adoptium 배포판)를 새로 받아 스크래치패드에 압축 해제해 사용. Android Studio 번들 JBR 21은 Kotlin 1.9.24 컴파일에는 문제없지만 jpackage가 빠져있어 데스크탑 `createDistributable`엔 못 씀 — jpackage 포함된 JDK 21이 반드시 필요(JDK 25는 jpackage는 있지만 Kotlin 1.9.x 컴파일러가 "25.0.2" 버전 문자열을 못 읽어 즉시 실패).
- **[빌드환경] 데스크탑 빌드는 반드시 비-한글 경로에서**: OneDrive 원본 경로(`...\바탕 화면\...`)에서 데스크탑 `createRuntimeImage`(jlink)를 돌리면 인자 파일 인코딩 문제로 "출력 디렉터리가 이미 존재함" 오류가 반복 발생 — `C:\build\phonelock-desktop`(ASCII 경로)로 소스를 robocopy한 뒤 그곳에서 빌드해 해결. 안드로이드는 이 문제가 없어(`AndroidBuilds\phone-lock-android`에서 정상 빌드) 기존 관행 유지.
- **[컴파일] 양 플랫폼 확인 완료**: 안드로이드 `compileDebugKotlin`/`assembleDebug`, 데스크탑 `compileKotlin`/`createDistributable` 전부 BUILD SUCCESSFUL. 58차의 `zeroStreakDays`/`forZeroStreak` 등 신규 심볼이 실제로 빌드된 APK(`classes5.dex`/`classes7.dex`)에 포함된 것도 grep으로 직접 확인.
- **[배포] 안드로이드 APK 두 위치(`AndroidBuilds\phone-lock-app.apk` + OneDrive 원본) 갱신, 데스크탑 표준 절차(watchdog 비활성화→프로세스 종료→robocopy `/MIR`, FAILED 0 확인→재실행→watchdog 재활성화)로 실제 배포까지 완료** — jar 해시(`PhoneLockDesktop-1.0.0-1d6d337....jar`) 비교로 배포본이 새 빌드와 완전히 동일함을 확인. `AndroidBuilds\phone-lock-desktop`에도 최신 소스(58차분 포함) robocopy로 동기화해둠. **실사용 데이터 조작(그룹/루틴/캘린더 편집, Firebase push 트리거)은 이번에도 하지 않음 — 화면 정상 렌더링 여부만 확인 필요(사용자 후속 확인 권장), 실기기(안드로이드) 검증은 여전히 범위 밖.**

---

## 2026-08-21 (58차 세션) — 앱 아이콘 리메이크 + 스트릭 알림 응원/조롱/팩폭 3단계 개편 + 랜덤 시각 발송

사용자 피드백 4건 처리. 빌드 툴체인이 세션에 없어 소스는 `AndroidBuilds`에 robocopy했지만 컴파일은 미검증 — 다음 세션에서 먼저 확인할 것.

- **[아이콘] 앱 아이콘 전면 재작성**: `ic_launcher_background.xml`을 완전 투명으로, `ic_launcher_foreground.xml`을 정중앙 픽셀아트 태양(6x6 원반+상하좌우 십자 광선)+주황(`#FF9800`) 테두리로 교체. 기존 "새벽하늘 그라데이션+언덕 사이 일출" 디자인은 삭제(안드로이드 런처 아이콘만 대상, 데스크탑/브라우저 확장 아이콘은 이번 범위 밖). 테두리는 108x108 뷰포트 가장자리까지 채워서, 런처가 원형/스퀴클/사각 중 어떤 마스크를 적용하든 항상 그 모양에 맞는 테두리로 자동 렌더링되게 함.
- **[버그] 모바일 아이콘 정중앙 미배치 문제 해결**: 기존 디자인은 안전영역(108 중 안쪽 66, 21~87)을 벗어나 그려진 콘텐츠가 많아 일부 런처의 마스킹에서 중심이 안 맞아 보였음 — 이번 재설계로 태양을 뷰포트 정확히 중앙(27~81)에 배치해 안전영역 안쪽에 완전히 들어오도록 해 해결.
- **[기능] 스트릭 0 지속일수 기반 3단계 알림**: 기존엔 스트릭이 0인지 아닌지만 보고 "끊김" 메시지 하나만 보냈는데, `RoutineQuotes.forZeroStreak(zeroStreakDays, broken)` 신규(안드로이드/데스크탑 대칭)로 0이 며칠째 지속됐는지에 따라 응원(1~2일)→조롱(3~6일)→팩폭(7일+) 3단계로 확장. 새 필드 `zeroStreakDays`를 안드로이드 `AppPreferences`(SharedPreferences), 데스크탑 `Models.kt`(AppData)+`Repository.kt`(프로퍼티)+`JsonStore.kt`(parse/save 양쪽) 4곳에 추가.
- **[변경] 스트릭 알림 발송 시각을 랜덤화**: 기존엔 `dailyResetHour` 정각 고정이었으나 사용자 요청으로 매일 완전 랜덤 시각으로 변경. 안드로이드 `RoutineAlarmScheduler.scheduleStreakCheck(context)`가 hour 파라미터를 없애고 `(0..23).random()`/`(0..59).random()`으로 다음 트리거를 계산(호출부 4곳 — `RoutineReminderReceiver`/`MainActivity`/`SettingsScreen` — 모두 갱신). 데스크탑 `RoutineNotifier.tick()`도 대칭 개편하면서, 기존 "정각 == 지금 분" 정확 일치 비교(그 순간 앱이 안 떠 있으면 그날은 아예 못 울리는 취약점)를 "목표 시각을 지났고 오늘 아직 안 보냈으면"(`>=`) 비교로 교체 — "스트릭 알림이 작동 안 한다"던 사용자 신고의 원인일 가능성이 있는 구조적 결함이었음(로그로 확정된 건 아님). 설정 화면 안내 문구(양 플랫폼)도 "초기화 시각에"→"하루 중 랜덤한 시각에"로 갱신.

---

## 2026-08-15 (57차 세션) — VM 격리 빌드 검증 + Alt-Tab 버그 문서 정정 + HANDOFF 정리

이번 세션은 별도 문서(`VM_BUILD_HANDOFF.md`)의 안내로 `PhoneLockBuildVM`(호스트와 완전히 분리된 격리 빌드 환경, 호스트 프로젝트 폴더는 VirtualBox 공유 폴더로만 접근) 안에서 진행됐다. 코드 변경은 없고, 빌드 검증과 문서 정리만 수행.

- **[검증] 데스크탑/안드로이드 빌드 둘 다 VM에서 성공**: `phone-lock-desktop`/`phone-lock-android`를 VM 로컬 디스크(`C:\build\gwanrieob`)로 복사 후 Temurin JDK 21(jpackage 포함)+Gradle 8.7+Android SDK cmdline-tools(platform 34)를 새로 설치해 빌드. 데스크탑 `gradle compileKotlin`/`createDistributable` 성공, 생성된 `PhoneLockDesktop.exe` 실행 시 트레이 아이콘/메인 창(루틴·공부·관리·설정 탭)/그룹 목록 화면이 에러 없이 렌더링됨을 확인. 안드로이드 `gradle assembleDebug` BUILD SUCCESSFUL. 프로덕션 Firebase 오염 위험 때문에 실제 데이터 입력·동기화 버튼 클릭은 의도적으로 하지 않음(컴파일+렌더링 확인까지만). **산출물은 VM 로컬에만 존재, 호스트 실제 배포(watchdog 비활성화→프로세스 종료→robocopy→재실행)는 이번 세션 범위 밖** — 자세한 내용은 `C:\build\gwanrieob\HOST_HANDOFF_FROM_VM.md` 참고.
- **[문서 정정] Alt-Tab 사이트 차단 우회 버그가 실은 이미 고쳐져 있었음**: 35차에 원인만 확정하고 "수정은 보류"로 기록됐던 버그를, 이번에 코드(`background.js`)를 직접 확인해보니 `chrome.tabs.onActivated`/`chrome.windows.onFocusChanged` 리스너(`checkTabNow()`)가 이미 추가돼 있었다. 파일 수정 시각이 2026-08-11(41차 전후)로 확인돼, 그 시점 어느 세션에서 실제로 고쳤지만 세션 종료 절차에서 BUGS.md/HANDOFF.md를 안 갱신해 이후 세션들이 계속 "보류 중"으로 잘못 알고 있었던 것으로 결론. BUGS.md Open→Fixed로 정정.
- **[문서 정리] HANDOFF.md "다음 작업 우선순위"에 누적돼 있던 세션별(39~56차) 실기기 검증 체크리스트를 전량 제거**: 사용자가 실기기 검증은 본인이 직접 관리하기로 결정 — HANDOFF는 코드/배포 관련 실제 액션 아이템만 남기도록 축소. 상세 검증 문구 자체는 CHANGELOG의 각 세션 기록에 그대로 남아있어 필요하면 검색 가능.

---

## 2026-08-14 (56차 세션) — 안드로이드 루틴 알림 지연/누락 수정

사용자 보고 "안드로이드 루틴 알림이 2분 정도 늦게 온다"로 시작 — 원인 하나를 고친 뒤, 추가로 요청받은 "다른 원인도 있는지" 점검에서 별개의 누락 원인을 하나 더 찾아 함께 수정했다.

- **[Fixed] 루틴 알림이 정시보다 늦게(약 2분) 옴**: `RoutineAlarmScheduler.kt`가 52차에 배터리 절약을 위해 일부러 부정확 알람(`AlarmManager.setAndAllowWhileIdle`)을 썼는데, 이게 Doze 모드에서 시스템이 알람 전달을 지연시키는 정상 동작이었다. `setExactAndAllowWhileIdle`로 전환하되, `canScheduleExactAlarms()`로 권한(Android 13+ 기본 거부)을 확인해 없으면 기존 부정확 알람으로 자동 폴백하도록 `scheduleAlarm()` 헬퍼 신설(크래시 방지). `AndroidManifest.xml`에 `SCHEDULE_EXACT_ALARM` 권한 추가, 설정 화면 "권한 / 백그라운드 보호" 섹션에 "정확한 알람" 상태 표시+허용 버튼(`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`) 신규.
- **[Fixed] 다른 기기에서 만들거나 수정한 루틴은 알림이 아예 예약 안 됨**: `MainActivity.onCreate`가 앱 실행 시마다 `RoutineAlarmScheduler.rescheduleAll()`로 알림을 재예약하지만 이 함수는 로컬 Room DB만 본다 — Firebase 최신 데이터를 끌어오는 `syncRoutinesFromFirebase()`는 사용자가 "루틴" 탭 화면에 직접 들어갈 때만(`RoutineScreen.kt`의 `LaunchedEffect(Unit)`) 호출됐다. 즉 데스크탑 등 다른 기기에서 루틴을 추가/수정한 뒤 이 폰에서 "루틴" 탭을 한 번도 안 열면, 로컬 DB가 여전히 옛 상태라 그 루틴의 알림이 처음부터 예약조차 안 됐다. `MainActivity.onCreate`에서 `rescheduleAll()` 전에 `syncRoutinesFromFirebase()`를 먼저 호출하도록 수정 — 이제 "루틴" 탭을 안 열어도 앱만 켜면 최신 루틴이 반영되고 알림이 예약된다. 앱을 아예 안 켜는 경우까지 커버하는 백그라운드 주기적 동기화는 배터리/복잡도 트레이드오프 때문에 이번 범위에서 제외(사용자 확인).
- **검증**: `assembleDebug --offline` BUILD SUCCESSFUL 확인(수정 2건 각각 별도 빌드) 후 안드로이드 APK 두 위치(AndroidBuilds + OneDrive 원본) 갱신. **실기기 미검증** — 정확한 알람 권한을 실제로 허용한 뒤 알림이 정시에 오는지, 데스크탑에서 루틴을 추가하고 폰 앱만 켰을 때(루틴 탭 안 열고) 그 루틴 알림이 실제로 오는지 확인 필요.

---

## 2026-08-14 (55차 세션) — 스누즈 중 그룹 설정 변경 자유화 + 스케줄/한도/실행확인 우선순위 정렬

독립된 두 요청을 순서대로 처리했다.

- **스누즈 진행 중엔 그룹 설정 변경이 회유 절차 없이 자유롭게 가능하도록 수정**: `LockEvaluator.detectWeakeningEdit()`가 스누즈 상태를 전혀 고려하지 않아서, 스누즈로 이미 모든 제한이 풀린 도중에도 그룹 편집 화면에서 설정을 약화시키면 여전히 회유 멘트 20개를 통과해야 했다. `isCurrentlyRestricting()`/`evaluate()`가 이미 쓰던 `!isForceEnabled(group) && isSnoozed(group)` 예외 패턴을 `detectWeakeningEdit()` 맨 앞에도 추가(기간 지정 자동 강화가 걸려있으면 예외는 적용 안 됨, 39~41차 우선순위 유지) — 양 플랫폼 `LockEvaluator.kt` 동일 적용.
- **스케줄 => 일일 한도 => 실행 확인 우선순위 정렬**: 겹치는 관리 종류가 있을 때 기존엔 "실행 확인 필요 여부"를 스케줄/한도 잠금보다 먼저 판정해서, 스케줄 시간대나 한도 초과로 이미 막혀야 할 순간에도 확인창이 먼저 뜰 수 있었다. 안드로이드 `AppMonitorAccessibilityService.kt`(앱 판정 `tickInternal`, 사이트 판정 `checkSitesInternal`)와 데스크탑 `EnforcementService.kt`(`decide`)/`SiteEnforcement.kt`(`check`/`tick`) 총 4곳 모두 `evaluator.evaluate()`(스케줄/한도) 판정을 `evaluator.isConfirmActiveNow()`(실행확인) 판정보다 앞으로 재배치. 스케줄과 한도 사이의 순서는 `LockEvaluator.evaluate()`가 이미 스케줄을 먼저 검사하므로 손댈 필요 없었음.
- **검증**: 양 플랫폼 컴파일(`assembleDebug --offline`/`createDistributable`) BUILD SUCCESSFUL 확인 후 안드로이드 APK 두 위치 갱신 + 데스크탑 표준 배포 절차(watchdog 비활성화→종료→robocopy FAILED 0 확인→재실행→watchdog 재활성화)로 재배포 완료. **두 변경 모두 실기기 미검증** — 스누즈 걸고 설정 변경이 즉시 되는지, 세 관리 종류가 겹칠 때 확인창 대신 차단화면이 뜨는지 확인 필요.

---

## 2026-08-14 (54차 세션) — 데스크탑 루틴 아이콘 동기화(사실은 로컬 영속화) 버그 수정

사용자가 "데스크탑은 어째서 루틴 아이콘이 동기화가 안되는가"로 시작 — 조사 후 실제로는 Firebase 동기화는 정상이고 데스크탑 로컬 저장(`data.json`)이 icon을 저장 못 하는 문제였음을 확인, 이어서 사용자가 재현("추가 직후엔 잘 보이는데 앱을 껐다 켜면 사라짐")으로 근본 원인(LWW 타임스탬프 동일값 트랩)까지 정확히 특정.

- **[Fixed] 데스크탑: 재시작하면 루틴 아이콘이 전부 사라짐(사실상 icon/notifyEnabled/startDate/endDate 4개 필드 전부)**: 52차에 `Routine`에 추가된 이 4개 필드가 Firebase 동기화 경로(`Repository.kt`의 `routinesToJsonArrays`/`routinesFromJsonArrays`)엔 정상 반영됐지만, 앱 자신의 로컬 영속화 파일인 `JsonStore.kt`의 루틴 save()/parse()엔 빠져 있었다(36차 `pomodoroModeEnabled`와 동일한 유형의 실수). `JsonStore.kt`에 4개 필드 모두 추가해 수정.
- **원인 심화 확인 — 단순 필드 누락이 아니라 LWW 타임스탬프가 "우연히 일치"해서 자동 복구도 안 되는 상태였음**: `pushRoutinesToFirebase()`는 push 시점에 로컬 `routinesTs`도 원격과 같은 값으로 갱신한다. 로컬 파일 저장은 icon 없이 저장되지만 타임스탬프는 원격과 정확히 같은 값이 되어, 재시작 후 `syncRoutinesFromFirebase()`의 LWW 비교(`result.ts > data.routinesTs`/`data.routinesTs > result.ts`)가 둘 다 거짓이 되어(같은 값이라 어느 쪽도 "더 최신"이 아님) 영구히 재동기화가 안 일어나는 구조였다. Firebase REST API로 원격 26개 루틴의 icon이 전부 멀쩡함(`_ts`도 로컬과 정확히 동일)을 직접 확인해 원인 확정.
- **복구**: 이미 icon이 빠진 채로 저장된 이번 기기의 로컬 `data.json`은 코드 수정만으론 스스로 복구되지 않으므로(위 트랩), 앱 종료 후 `routinesTs`를 0으로 강제 리셋 — 재실행 후 "루틴" 탭 진입 시 `syncRoutinesFromFirebase()`가 "원격이 더 최신"으로 판단해 Firebase의 정상 데이터를 다시 받아오도록 유도. 사용자가 icon 정상 표시를 직접 확인 완료.
- **검증**: `AndroidBuilds\phone-lock-desktop`에서 `compileKotlin`/`createDistributable` BUILD SUCCESSFUL 확인 후 표준 배포 절차(watchdog 비활성화→프로세스 종료→robocopy→FAILED 0 확인→재실행→watchdog 재활성화)로 재배포, 사용자 실사용 확인까지 완료(이 세션은 드물게 실기기 검증까지 끝난 케이스).
- 부가로 루틴 스트릭 알림 구조(데스크탑 30초 폴링 vs 안드로이드 `AlarmManager` 예약)를 설명 — 코드 변경 없음.

---

## 2026-08-14 (53차 세션) — 계산기 데이터 복구 + 회유 멘트/조롱조 문구 확장 + 테마 버그 전수 수정(위젯·브라우저 확장 포함) + 테마 5종 추가 + 루틴 알림 강화 + 루틴 내보내기·가져오기 + 태블릿 레이아웃 수정

52차 세션 종료 시점에 미확인이던 "계산기 저장 항목이 안 돌아왔다"는 사용자 보고로 시작 — 이어서 세션 내내 여러 독립 요청을 순서대로 처리했다.

- **[Fixed] 계산기 저장 항목/폴더 트리 Firebase 유실 복구**: 52차의 DB 버전업 재앙 때 `resetSyncTimestamps()` 수정이 배포되기 전, 안드로이드가 빈 로컬 계산기 데이터(`saved=[]`)를 그대로 Firebase에 푸시해 원격의 진짜 저장 데이터(34개 항목)를 덮어썼던 것으로 확인(REST API 직접 조회로 원인 특정, [[feedback_firebase_debug_via_rest]] 패턴 재사용). 데스크탑 로컬 `data.json`엔 이 사고 이전 시점의 원본이 그대로 남아있어(동기화 타임스탬프가 우연히 원격과 일치해 이후 동기화가 트리거되지 않았음) 이를 소스로 복구 — 데스크탑 로컬 타임스탬프를 강제로 최신화해 앱이 정상 push 경로로 원격에 재업로드하게 했으나, `savedTs` 필드 자체는 갱신되지 않는 걸 확인해 이 필드만 REST PATCH로 직접 최신화. 이후 안드로이드가 계산기 탭 재진입 시 정상 재동기화되어 34개 항목 전부 복구 확인. `savedFolderTree`는 여전히 원격에 없지만 `healCalcFolderPaths()`(31차)가 항목의 `folderPath`로부터 자동 재구성하므로 기능상 문제없음.
- **회유 절차 멘트 20개를 조롱조/놀림조로 전면 교체**: `PersuasionMessages.kt`(양 플랫폼, 그룹/루틴 설정 약화·삭제 시 뜨는 절차)의 정중한 반성 유도 문구 20개를 `MotivationalQuotes.kt`와 같은 계열의 조롱조로 교체 — 앞부분(1~5)은 가벼운 놀림, 중반(6~15)은 비아냥, 후반(16~20)은 독한 팩폭으로 갈수록 세지도록 순서를 짜고, 마지막 문구는 기존처럼 "최종 결정이야?"로 마무리.
- **조롱조 문구 25개 추가(149→174개)**: `MotivationalQuotes.kt`(양 플랫폼)+브라우저 확장 `quotes.js`에 5단계(순한~극한) 각 5개씩 추가, 세 파일 내용이 정확히 동일함을 각 tier 항목 수(30/54/30/30/30) 비교로 확인.
- **[Fixed] 테마 버그: 실행확인/차단 화면 "중단" 버튼이 테마 바꿔도 초록 고정**: 원인은 `secondaryContainerColor = Color(0xFF8BC34A)`(라이트+그린 하드코딩) — `ConfirmOpenActivity.kt`/`BlockActivity.kt`(안드로이드), `ConfirmScreen.kt`/`BlockScreen.kt`(데스크탑) 4곳을 `MaterialTheme.colorScheme.primary`로 교체. 같은 유형의 버그를 전수 검토해 안드로이드 "사용 중" 접근성 오버레이(`AppMonitorAccessibilityService.kt`)와 데스크탑 코너 위젯(`UsageOverlayContent.kt`, `PhoneLockTheme`로 감싸지지 않았던 것도 함께 수정)의 타이머 색상도 초록 고정이었던 걸 찾아 함께 수정. 계산기/캘린더/루틴 통계의 빨강·초록·노랑 등은 의미론적 색상(요일/완료상태)이라 49차 결정대로 테마와 무관하게 유지.
- **안드로이드 홈스크린 위젯 테마 연동**: RemoteViews는 Compose 테마를 못 써서 XML에 라이트+그린 색이 고정돼 있던 걸, 테마 3종별 드로어블(배경/아이템행/체크·미체크 아이콘) 세트를 만들어 `RoutineWidgetProvider`/`RoutineWidgetFactory`가 런타임에 현재 테마에 맞는 리소스를 선택하도록 변경. 설정 화면 테마 버튼 클릭 시 `RoutineWidgetProvider.updateAll()`도 함께 호출해 위젯이 즉시 갱신되게 함.
- **브라우저 확장 프로그램 테마 연동 신규**: 그동안 확장 전체가 다크+블루 고정이었던 것(35차 결정으로 범위 밖 취급)을 이번에 3종 테마 연동으로 확장 — 데스크탑 로컬 API에 `/theme` 엔드포인트 신설(`themeMode` 반환, `overlay-status`와 동일하게 openCors), 신규 `theme.js`가 3개 팔레트를 CSS 변수로 매핑. `confirm.html`/`blocked.html`/`onboarding.html`을 하드코딩 색상에서 CSS 변수로 전환(:root 기본값은 앱 기본 테마인 라이트+그린), `overlay.js`(사용 중 오버레이)도 팔레트를 받아와 적용(30초마다 재확인). 데스크탑 앱과 통신 안 되면 라이트+그린으로 안전하게 폴백.
- **루틴 알림 플로팅 바(헤드업) + 진동 추가**: 기존 `IMPORTANCE_DEFAULT`(알림창에 조용히 쌓임) 채널을 `IMPORTANCE_HIGH`(화면 위 플로팅)로 바꾸고 진동 패턴 추가 — 기존 채널은 안드로이드 정책상 앱이 사후에 importance를 못 올려서 새 채널 ID(`routine_reminder_v2`)를 발급하는 방식으로 우회, `VIBRATE` 권한 매니페스트에 추가. 데스크탑은 진동 개념이 없고 트레이 풍선 알림은 이미 플로팅이라 변경 없음.
- **루틴 파일 내보내기/가져오기 신규**: 그룹 백업(안드로이드)/전체 백업(데스크탑)과 별개로 루틴 목록+체크 기록만 JSON으로 내보내거나 불러올 수 있는 기능을 양 플랫폼 설정 화면에 추가. Firebase 동기화 문서(`routines`+`routineLogs`)와 동일 스키마를 재사용, 가져오기는 현재 데이터를 전체 대체 후 Firebase에도 재푸시(확인 다이얼로그 필수).
- **테마 5종 추가(3종→8종)**: 라벤더·퍼플/민트·틸/로즈·핑크(라이트 3종), 미드나잇·퍼플/포레스트·그린(다크 2종) 신규 — `ThemeMode`/`PhoneLockPalette`(양 플랫폼 `Color.kt`)와 브라우저 확장 `theme.js`의 `THEME_PALETTES`에 동일 값으로 추가, `THEME_DISPLAY_NAMES` 신규(설정 화면이 하드코딩 3개 대신 이 목록을 순회하도록 리팩터링).
- **[Fixed] 안드로이드 설정 화면 테마 선택 UI가 8종으로 늘며 화면 밖으로 잘림**: 3개 전용으로 짠 고정 `Row`가 8개 `FilterChip`을 못 담아 찌부러지던 문제 — 계산기 연동 업무 선택 버튼(50차)과 동일하게 `FlowRow`로 교체해 자동 줄바꿈되도록 수정(데스크탑도 동일하게 통일, 8개는 데스크탑 창 폭에선 아직 안 잘리지만 일관성을 위해 함께 적용).
- **[Fixed] 태블릿에서 공부앱/루틴앱 통계 탭의 "최근 30일 완료 추이" 막대그래프가 좁게 뭉쳐 보임**: 50차에 폰 화면 대응으로 막대를 고정폭(20dp)+가로스크롤로 바꿨는데, 이 처리가 태블릿에도 그대로 적용돼 넓은 화면에서도 그래프가 작게 뭉친 채 스크롤바만 뜨는 상태였다 — `InterstitialScreen.kt`의 태블릿 분기(화면폭 600dp 이상)와 동일한 기준으로 `StudyStatsScreen.kt`/`RoutineScreen.kt`(안드로이드) 양쪽에 태블릿 분기 추가, 태블릿에선 스크롤 없이 `weight(1f)` 균등분할로 폭을 꽉 채움(데스크탑은 원래도 이 방식).
- **검증**: 매 기능 단위로 양 플랫폼 컴파일(`assembleDebug --offline`/`compileKotlin`) 확인 후 안드로이드 APK 두 위치 갱신 + 데스크탑 표준 배포 절차(watchdog 비활성화→종료→robocopy FAILED 0 확인→재실행→watchdog 재활성화) 총 7회 반복. 브라우저 확장 CSS 변수 폴백은 Browser 미리보기로 콘솔 에러 없음까지만 확인(로컬 API 응답 자체는 샌드박스에서 검증 불가). **이번 세션 신규 기능 전부 실기기 미검증** — 계산기 복구만 사용자가 직접 확인 완료.

---

## 2026-08-14 (52차 세션) — 릴스/쇼츠 감지 버그 수정 + 루틴 순서/복사 + 아이콘/알림/기간 설정 + 심각한 동기화 버그 발견·수정

이어서 진행 요청으로 시작 — HANDOFF.md 최우선 버그 조사, 그리고 사용자가 이번 세션에 새로 요청한 두 기능(루틴 순서 변경, 루틴/그룹 복사)과 지난 세션 종료 시 IDEAS.md에 남겨둔 4개 요청(아이콘/알림/스트릭알림/기간설정)을 순서대로 처리했다. 마지막에 사용자가 "모바일 동기화가 안 된다"고 보고해 조사하다 이번 세션 자체가 유발한 심각한 동기화 버그를 발견해 수정했다.

- **[Fixed] 안드로이드 릴스/쇼츠 감지 차단 미작동**: 원인은 `AppMonitorAccessibilityService.kt`의 `REELS_KEYWORDS`/`SHORTS_KEYWORDS` 문자열이 파일 바이트 단위로 깨져 있던 것(mojibake) — 정상 한글로 교체. [[BUGS.md]] 52차 참고.
- **루틴 순서 변경(▲/▼) + 루틴/그룹 복사 신규**: 계산기/캘린더와 같은 ▲/▼ 버튼 패턴으로 구현(사용자 확인 후 결정, 진짜 드래그는 프로젝트에 전례 없어 제외) — 시간대 없는 루틴에만 표시(`swapRoutineOrder` 신규, 데스크탑/안드로이드 대칭). 루틴 편집 다이얼로그에 "루틴 복사" 버튼, 그룹 편집 화면에 "복사" 버튼 신규(`copyRoutine`/`copyGroup`, 양 플랫폼). [[DECISIONS.md]] 52차 참고.
- **루틴 상징 아이콘**: `Routine.icon`(이모지, 선택) 필드 — 편집 다이얼로그에 텍스트필드+12개 quick-pick 팔레트, 오늘 탭/통계/안드로이드 홈위젯 목록에 표시.
- **루틴 알림 설정**: 루틴별 `notifyEnabled`+`timeSlot` 기준 리마인드 — 안드로이드는 `AlarmManager.setAndAllowWhileIdle`(특수 권한 불필요)로 예약, 부팅 시(`RoutineReminderReceiver`의 `BOOT_COMPLETED`)와 앱 실행 시(`MainActivity.onCreate`) 재예약. 데스크탑은 30초 주기 틱(`RoutineNotifier.tick`)에서 현재 시각과 직접 비교, 기존 트레이 아이콘으로 풍선 알림(`DesktopNotifier`+`TrayState.sendNotification`, 새 TrayIcon 추가 안 함).
- **스트릭 기반 응원/비판/조롱 알림**: 설정 화면 전역 토글("스트릭 알림 받기") — 매일 초기화 시각에 어제 스트릭을 계산해 끊겼으면 비판, 쌓이고 있으면 응원 톤 알림(`RoutineQuotes.kt`, 42차 `MotivationalQuotes.kt`와는 별개의 작은 세트, 양 플랫폼 대칭).
- **루틴 기간 설정**: `Routine.startDate`/`endDate`(yyyy-MM-dd, 선택) — `RoutineEngine.isScheduledOn`(양 플랫폼 4곳: `RoutineEngine.kt`/`RoutineScreen.kt`/안드로이드 `RoutineWidgetFactory.kt`)이 daysMask와 함께 기간도 검사하도록 확장.
- **Room DB 26→27 (안드로이드)**: 위 아이콘/알림/기간 필드 4개를 한 번에 추가해 스키마 변경(=destructive migration)을 1회로 최소화. `PreMigrationBackup.TABLES`는 이미 `routine`/`routine_log` 포함(48차에 확인됨), 추가 변경 불필요.
- **[Fixed, Critical] 캘린더/루틴/계산기 동기화가 Room 버전업 후 조용히 멈추는 버그 발견·수정**: 위 DB 버전업을 실제로 사용자 폰에 배포한 뒤 "모바일 동기화가 안 된다"는 보고를 받고 조사 — `fallbackToDestructiveMigration()`이 Room만 지우고 `AppPreferences`(SharedPreferences)에 저장된 동기화 타임스탬프 6종(`calendarTs`/`routinesTs`/`calcTasksTs`/`calcSavedTs`/`calcFolderTs`/`calcFolderOrderTs`)은 살아남아서, 다음 동기화의 LWW 비교가 "로컬이 이미 최신"으로 오판하고 아무것도 다시 안 받아오는 버그였다. Firebase REST API로 원격 데이터가 멀쩡함을 직접 확인(루틴 22개/캘린더 43일치/계산기 데이터 전부 존재) — `AppPreferences.resetSyncTimestamps()` 신규 + `PreMigrationBackup.backupIfVersionChanged()`가 백업을 만든 직후 호출해서 강제로 재동기화되도록 수정.
- **그룹 데이터 복구 기능 신규**: 그룹(차단 대상 앱/사이트 목록)은 애초에 Firebase에 동기화되지 않는 데이터라 이번 DB 초기화로 실제로 유실됨(사용자 확인) — `PreMigrationBackup`이 자동으로 남긴 raw SQLite 백업 JSON에서 그룹/멤버/사이트/사용기록/실행확인레벨/카운터를 복원하는 `PhoneLockRepository.restoreGroupsFromBackup()` + 설정 화면 "⚠ 그룹 데이터 복구" 카드(백업 있을 때만 노출) 신규. [[BUGS.md]] 52차 참고 — **사용자의 실제 복구 결과는 세션 종료 시점까지 미확인, 다음 세션 최우선**.
- **검증**: 매 기능 단위로 양 플랫폼 `compileKotlin`/`compileDebugKotlin` BUILD SUCCESSFUL 반복 확인. 안드로이드는 기능 추가할 때마다 APK 재빌드+두 위치(AndroidBuilds/OneDrive) 갱신을 4회 반복. 데스크탑은 이 세션 환경에 jpackage 포함 JDK가 없어 Temurin JDK 21.0.12를 사용자 승인 받아 다운로드([[project_build_toolchain_missing]] 참고) 후 `createDistributable`+표준 배포 절차(watchdog 비활성화→프로세스 종료→robocopy FAILED 0 확인→jar 해시 일치 확인→재실행→watchdog 재활성화)로 2회 재배포. 실기기(사용자 폰) 검증은 그룹 복구 기능만 아직 진행 중 — 나머지(순서변경/복사/아이콘/알림/기간설정)는 전부 미검증.

---

## 2026-08-13 (50차 세션) — 루틴앱 v2 개편 + 계산기-캘린더 연동 이식 + 8단계 무지개 회독 + 루틴 Firebase 동기화 + 안드로이드 홈 위젯

49차까지 완료된 루틴앱 v1/공부앱을 실사용 관점에서 다듬은 대규모 세션. 사용자 피드백을 받는 즉시 순서대로 반영했다.

- **루틴 스트릭 방어권 주 2회로 고정**: "일정 하나만 못해도 스트릭이 깨지는" 문제를 방지하기 위해, 편집 화면에서 노출하던 방어권 종류/횟수 커스텀 UI를 제거하고 `RoutineEngine`이 항상 "주 2회"를 기본 적용하도록 고정. 편집 다이얼로그(`RoutineEditScreen.kt`)엔 방어권이 항상 켜져 있다는 안내 문구만 남김.
- **첫 화면 큰 제목 헤더 추가**: 다른 화면들처럼 좌상단에 큰 글자로 화면 이름을 띄워달라는 요청으로 `GroupListScreen.kt`(양 플랫폼)에 "🗂️ 그룹" headline 추가.
- **테마 선택 기능 신설**: 설정에 테마 선택 `SectionCard` 추가, 라이트+그린(49차 기본)/다크+파랑(28~48차 옛 테마)/화이트+오렌지(최초 테마) 3종 중 고를 수 있게 함 — `ui/theme/Color.kt`를 `ThemeMode` 상수 + `PhoneLockPalette` + 팔레트 3종으로 재구조화, `Theme.kt`가 `themeMode` 문자열을 받아 팔레트를 선택. 선택값은 데스크탑 `AppData.themeMode`/안드로이드 `AppPreferences.themeMode`로 영속화.
- **새 루틴 생성 시 스트릭 추적 기본 ON**: `addRoutine` 기본값 `trackStreak=true`로 변경.
- **일과표 탭 제거, 오늘 탭에 병합 + 시간순 기본 정렬**: `RoutineScreen.kt`(양 플랫폼) 서브탭을 "오늘"/"일과표"/"습관" 3개에서 "오늘"/"통계" 2개로 축소, 오늘 탭의 루틴 목록이 항상 `timeSlot` 기준 시간순으로 정렬되도록 변경(시간 미지정 루틴은 뒤로).
- **습관 탭 → 통계 탭 교체 + 데스크탑 체크박스 즉시 반영 안 되던 버그 수정**: 습관 탭(스트릭+방어권 카드 나열)을 삭제하고 "현재 스트릭"/"오늘 완료"/"오늘 완료율"/"최고 스트릭" 지표 카드 중심의 통계 탭으로 교체. 별개로 사용자가 지적한 "데스크탑에서 체크박스가 바로 체크가 안 되고 다른 탭을 왔다갔다 해야 반영되는" 버그를 조사 — `mutableStateOf<List<Routine>>`을 구조적으로 동일한 새 리스트로 재할당해도 Compose가 리컴포지션을 스킵하는 문제였음(그룹 탭에서 이미 겪었던 것과 같은 유형). 항상 증가하는 `refreshTick`을 `key(refreshTick){...}`으로 감싸 강제 리컴포지션시키는 패턴으로 해결. [[BUGS.md]] 50차 참고.
- **오늘 탭에 요일 피커 + 주 단위 이동 추가**: "오늘" 탭 상단에 일~토 요일 칩과 ◀/▶ 주 이동 버튼을 추가해 과거/미래 날짜의 루틴 완료 상태를 조회할 수 있게 함(스트릭 계산은 항상 실제 오늘 기준으로 별도 유지). 안드로이드에서 처음엔 `horizontalScroll`+`FilterChip`으로 구현했는데 금/토 칩이 화면 밖으로 밀려 안 보이는 문제가 재현 — 스크롤 기반 대신 7개 칩을 `Modifier.weight(1f)`로 균등 분할하는 커스텀 `Column` 칩으로 재작성해 스크롤 없이 항상 7개가 다 보이도록 확정 수정. 데스크탑은 폭이 넉넉해 원래 방식 유지.
- **안드로이드 요일 표시/통계 30일 그래프 뭉개짐 수정**: 위 요일 피커 1차 수정과 별개로, `StudyStatsScreen.kt`/`RoutineScreen.kt`의 30일 완료 추이 막대그래프가 30개 칸을 `weight(1f)`로 좁은 화면에 욱여넣어 안 보이던 문제를 `horizontalScroll`+칸당 고정 `width(20.dp)`로 교체해 해결(데스크탑은 폭이 넉넉해 기존 유지).
- **공부앱 계산기 ↔ 캘린더 연동 기능 이식**: 원본 웹앱(`공부앱/index.html`)에만 있고 네이티브 재구현 때 누락됐던 핵심 기능 — 캘린더 일정에 계산기 업무를 연결해두면 일정 완료 시 계산기 진행량이 자동 차감/복원되는 연동을 양 플랫폼에 이식. `Repository`/`PhoneLockRepository`에 `linkedProgressAmount`/`addLinkedCalendarTask`/`isLinkedGoalAchieved`/`adjustLinkedCalcProgress` 등 신규, `setCalendarTaskStatus`가 연동된 일정의 완료/취소 시 계산기 진행량을 함께 조정. `CalendarScreen.kt`(양 플랫폼)의 날짜 상세에 `LinkedCalcSection` 신규(연동 업무 선택+수량 입력+추가), `TimetableScreen.kt`(양 플랫폼)이 연동 목표 달성 시 ✅ 표시+색상 강조.
- **연동 업무 선택 버튼 오버플로우 수정**: 업무가 많을 때 위 연동 섹션의 업무 선택 버튼들이 화면 밖으로 넘쳐 안 보이던 문제 — 요일 피커 때와 같은 유형이지만 이번엔 처음부터 스크롤이 아니라 `FlowRow`(여러 줄 자동 줄바꿈)로 구현해 확정 해결.
- **회독 8단계 무지개 색상 + 망각곡선 기반 주기 재설계**: 회독 수를 늘려달라는 요청으로 논의 끝에 8단계(1~8회독)로 확장, 색상은 무지개 순서(하양→빨강→주황→노랑→초록→파랑→남색→보라)로 배정. 처음엔 1,1,3,7,14,30,60일 간격으로 구현했다가, "과학적 원리(망각곡선 등)를 검토해 다시 판단해달라"는 요청을 받고 에빙하우스 망각곡선/spacing effect/SuperMemo 방식의 ~2~2.3배 지수 증가 원칙에 맞춰 1→3→7→14→30→60→120일로 재조정(연속된 1일 간격 중복 제거). `CALENDAR_COLOR_ORDER`/`CALENDAR_SCHEDULE`(양 플랫폼)/`COLOR_LABEL`/색상 선택 UI/`stageTextColor` 등 전면 갱신, 새 캘린더 일정 기본색을 `red`에서 `white`(1회독)로 변경. 기존에 저장된 `color="red"` 데이터는 이제 다른 회독 번호로 표시됨(재라벨링, 원본 색상 문자열 자체는 안 바뀜) — [[DECISIONS.md]] 50차 참고.
- **루틴앱 Firebase 동기화 추가**: 47~49차엔 로컬 전용이었던 루틴을 그룹/캘린더처럼 기기 간 동기화하도록 확장. 안드로이드는 Room auto-increment ID, 데스크탑은 수동 카운터라 ID가 기기마다 어긋날 수 있어, Firebase엔 루틴을 배열 인덱스 기준으로 저장하고 로그는 그 인덱스로 부모 루틴을 참조하는 방식(`routinesToJson`/`routinesFromJson`)으로 ID 충돌을 피함. 전체 문서 단위 LWW(캘린더/계산기와 같은 패턴).
- **탭 순서 변경 + "앱" 단어 제거**: 최상위 섹션 순서를 루틴→공부→관리→설정으로 변경(기존 관리→공부→루틴→설정), "공부앱"/"관리앱" 라벨에서 "앱"을 빼고 "공부"/"관리"로 축약(루틴/설정은 이미 "앱" 없었음).
- **공부앱 캘린더 데스크탑 월그리드에 완료 배지 추가**: 안드로이드엔 이미 있던 월 그리드 날짜 칸의 "총 개수/완료 개수" 색상 배지(초록/노랑/빨강, 완료율 기준)를 데스크탑에도 대칭 구현.
- **안드로이드 홈스크린 위젯 신규**: 루틴 목록을 홈 화면에서 바로 체크할 수 있는 위젯 추가 — 새 Gradle 의존성 없이 기존 `RemoteViews`/`AppWidgetProvider`/`RemoteViewsService` 인프라로 구현(`widget/RoutineWidgetProvider.kt`/`RoutineWidgetService.kt`/`RoutineWidgetFactory.kt`/`RoutineWidgetToggleReceiver.kt` 신규), 리스트 항목 클릭 시 `PendingIntent` 템플릿으로 토글, 위젯이 그룹/루틴 데이터를 직접 쓰지 않고 항상 Repository를 거치도록 해 다른 화면과 상태 일관성 유지.
- **검증**: 매 변경 후 양 플랫폼 `compileKotlin`/`compileDebugKotlin` BUILD SUCCESSFUL 확인(15회 이상 반복), 안드로이드 APK 두 위치+데스크탑 표준 절차로 다회 재배포. **이번 세션 신규 기능(홈 위젯/루틴 Firebase 동기화/8색 무지개/계산기-캘린더 연동) 전부 실기기 미검증** — 컴파일/배포만 확인됨. Room DB 스키마 변경 없음(SharedPreferences/기존 필드 재사용만).

---

## 2026-08-13 (49차 세션) — 루틴앱 v1 화면 구현 + 앱 전체 라이트+그린 테마 전환

48차의 데이터 모델/CRUD/스트릭 엔진 위에 실제 화면을 얹고, 47차에 방향만 정해뒀던 라이트+그린 테마까지 전환. HANDOFF.md "다음 작업 우선순위"에 있던 순서(오늘 탭→일과표→습관 탭→편집 화면→테마)를 그대로 따랐다.

- **루틴 화면 3종 신규(양 플랫폼 대칭, `RoutineScreen.kt`)**: "오늘"(오늘 요일마스크에 해당하는 루틴을 체크박스로 완료 처리, `trackStreak`면 스트릭 배지 표시)/"일과표"(`timeSlot` 있는 루틴을 오늘 기준 시간순으로 보여주는 파생 뷰, `TimetableScreen`과 같은 "저장 없이 매번 파생" 철학)/"습관"(`trackStreak` 루틴만 모아 스트릭+방어권 정보를 카드로 표시). 안드로이드는 Repository 루틴 함수가 전부 suspend라 `LaunchedEffect(refreshTick)`으로 완료 날짜 집합을 미리 한 번에 캐싱하는 방식(StudyStatsScreen과 같은 패턴)을 썼고, 데스크탑은 동기 함수라 상태 변경 즉시 재조회.
- **루틴 편집을 별도 화면이 아니라 다이얼로그로(`RoutineEditScreen.kt`)**: 그룹 편집(`GroupEditScreen`)처럼 좌우 분할 전체 화면을 쓰지 않고 `AlertDialog` 기반 폼으로 구현 — 필드 수가 그룹보다 훨씬 적어(제목/시간대/요일/스트릭 추적/방어권) 화면 전환이 과하다고 판단. 요일 토글은 `GroupEditScreen`의 `DayMaskRow`와 동일한 비트 규칙(bit0=월요일)으로 새로 작성(파일이 달라 재사용 대신 대칭 복제, `RoutineEngine`과 같은 관례).
- **MainScreen/MainActivity에 "루틴" 진입점 추가**: 데스크탑은 `NavigationRail`에 "🌱 루틴" 항목 신규(관리앱/공부앱/설정과 동급), 안드로이드는 하단 `NavigationBar`에 4번째 탭으로 추가.
- **앱 전체 라이트+그린 테마 전환(47차 방향 확정, 이번에 실제 톤 확정)**: 사용자에게 그린 톤 후보 3개(포레스트/에메랄드/세이지)를 제시했으나 전부 기각, "더 밝은 연두색"을 요청받아 Primary를 `#8BC34A`(밝은 연두, Material Light Green 500)로 확정 — 대비를 위해 이 색 위 텍스트(`onPrimary`)는 흰색 대신 짙은 그린빛 다크 톤(`#20261A`)을 쓴다. 배경은 순백 대신 옅은 그린 틴트 오프화이트(`#FAFBF6`). `Color.kt`/`Theme.kt`(양 플랫폼) `darkColorScheme`→`lightColorScheme` 전환. 자세한 배색 표는 [[DECISIONS.md]] 참고.
- **테마 전환에 맞춰 옛 파란 accent(`#4F8EF7`, 35차에 주황에서 교체됐던 색)도 초록으로 일괄 교체**: 차단/실행확인 화면(`BlockScreen`/`ConfirmScreen`/`BlockActivity`/`ConfirmOpenActivity`)의 강조색, 사용 중 오버레이 타이머 텍스트(`UsageOverlayContent.kt`/`AppMonitorAccessibilityService.kt`/브라우저 확장 `overlay.js`)까지 전부 `#8BC34A`(또는 rgb `139,195,74`)로 변경 — 이 값들은 `MaterialTheme.colorScheme`을 안 쓰고 원시 `Color(0xFF...)`로 하드코딩돼 있어서 Theme.kt만 바꿔선 자동으로 안 바뀌는 구조였다. 다만 브라우저 확장의 `confirm.html`/`blocked.html`/`onboarding.html` 자체의 다크 팔레트(35차에 통일된 것)는 이번 범위 밖으로 유지 — "앱 전체"는 네이티브 앱(Compose) 기준으로 해석했고, 확장의 전면 라이트 전환은 범위가 훨씬 커서 별도 요청 시 진행.
- **버그 발견/수정**: 캘린더 "오늘" 날짜 배지가 `Color.White` 텍스트를 primary 배경(원형) 위에 하드코딩하고 있었는데, 새 Primary가 밝은 연두라 흰 텍스트 대비가 나빠짐 — `MaterialTheme.colorScheme.onPrimary`로 교체(양 플랫폼 `CalendarScreen.kt`).
- **검증**: 양 플랫폼 `compileKotlin`/`compileDebugKotlin` BUILD SUCCESSFUL(무관한 기존 경고 외 신규 오류 없음, 도중 발견한 미사용 `refreshTick` 파라미터 정리). 안드로이드 `assembleDebug`로 APK 재빌드 후 두 위치(`AndroidBuilds\phone-lock-app.apk`, OneDrive 원본) 모두 갱신. 데스크탑은 표준 절차(watchdog 비활성화→프로세스 종료→PowerShell robocopy→`createDistributable`→배포 robocopy FAILED 0 확인→재실행→watchdog 재활성화)로 재배포, 배포된 jar에서 `RoutineScreen`/`RoutineEditScreen` 클래스와 새 Primary 색상 리터럴(`FF 8B C3 4A`)이 실제로 포함됐음을 바이트 레벨로 확인. Room DB 버전 변경 없음(48차에 이미 26으로 올라감, 이번엔 스키마 변경 없음). **브라우저 확장은 코드만 바뀌었고 `chrome://extensions` 재로드 필요(사용자 몫), 실기기 UI 검증은 안 함.**

---

## 2026-08-13 (48차 세션) — 루틴앱 v1 착수: Routine/RoutineLog 데이터 모델 + CRUD + 스트릭 엔진

47차에 확정된 설계를 바탕으로 실제 구현 착수. 이번 세션 범위는 데이터 모델 계층까지만(화면/테마는 다음 세션) — HANDOFF.md "다음 작업 우선순위" 순서를 그대로 따랐다.

- **Routine/RoutineLog 데이터 모델 신규**: `id`/`title`/`timeSlot`(HH:mm, null=시간 미지정)/`daysMask`(그룹의 scheduleDaysMask와 동일 비트 규칙)/`trackStreak`/`defenseType`(NONE/WEEKLY/MONTHLY)/`defenseCount`/`sortOrder`/`archived`. 안드로이드는 `data/Entities.kt`에 `@Entity` 2종 + `data/Daos.kt`에 `RoutineDao`/`RoutineLogDao` 추가, 데스크탑은 `data/Models.kt`에 데이터클래스 2종 + `AppData.routines`/`routineLogs`/`nextRoutineId` 추가하고 `data/JsonStore.kt` parse/save 반영.
- **Room DB 25→26**(안드로이드): `AppDatabase.kt` entities 목록에 `Routine::class`/`RoutineLog::class` 추가, `PreMigrationBackup.kt`의 TABLES 목록에도 `routine`/`routine_log` 추가(마이그레이션 전 백업 대상에 포함). `fallbackToDestructiveMigration()`이라 마이그레이션 코드는 불필요.
- **CRUD를 양 플랫폼 `PhoneLockRepository.kt`/`Repository.kt`에 대칭 추가**: `getRoutines`/`addRoutine`/`updateRoutine`/`deleteRoutine`/`moveRoutineOrder`(정렬), `getRoutineLogsForDate`/`isRoutineCompleted`/`toggleRoutineLog`/`getRoutineCompletedDateKeys`. Firebase 동기화는 이번 v1에서 안 함(그룹처럼 로컬 전용으로 시작 — 47차 설계에서 동기화 여부가 명시적으로 논의되지 않았음, 필요해지면 다음에 논의).
- **`RoutineEngine.kt` 신규(양 플랫폼, `routine` 패키지)**: `currentStreak(routine, completedDateKeys, today)` — StudyStatsScreen의 "지정 요일만 카운트, 미체크 시 즉시 끊김" 로직을 재사용하되, 미완료인 날을 만나면 `defenseType`/`defenseCount` 기준 그 시점 주기(주/월)의 방어권이 남아있는지 확인해 남아있으면 "지정 안 된 날"처럼 취급(스트릭 유지, 증가는 안 함)하고 방어권을 소모, 없으면 그 자리에서 끊는다. **방어권 소모 카운터는 별도로 저장하지 않고, 스트릭을 계산할 때마다 뒤로 걸어가며 그때그때 계산하는 파생값으로 설계**했다(47차 DECISIONS.md 표현은 "소모 카운터를 리셋"이지만, 순수 함수로 매번 다시 계산하면 리셋 로직 자체가 필요 없어 코드가 더 단순함 — TimetableScreen/StudyStatsScreen과 같은 "파생 뷰" 철학을 그대로 유지, CalcEngine.kt와 같은 위치에 플랫폼별 대칭 복제).
- **양 플랫폼 컴파일 확인 완료**(`compileKotlin`/`compileDebugKotlin` 둘 다 BUILD SUCCESSFUL, 기존에 있던 무관한 경고 몇 건 외 신규 오류 없음). **화면(오늘 탭/일과표/습관 탭/편집 화면)과 라이트+그린 테마 전환은 아직 착수 안 함, 빌드/배포도 안 함** — 다음 세션에서 이어서 진행.

---

## 2026-08-13 (47차 세션) — 루틴앱 v1 설계 확정 (코드 변경 없음)

45차에 통합만 결정되고 미착수였던 루틴앱을 실제로 설계하는 세션. 순서대로:

1. "루틴앱에 대해 조사해" 요청 → 처음엔 코드 조사로 오해해 기존 네비게이션/데이터 모델/Firebase 패턴을 조사(에이전트 위임)했으나, 사용자가 "마이루틴 같은 기존 루틴앱들 시장 조사"를 의미한 것으로 정정 → 마이루틴/Streaks/Loop Habit Tracker/Habitica를 웹서치로 조사해 기능 비교 정리.
2. 핵심 기능 3가지(반복 체크리스트/습관 트래커+스트릭/시간대별 일과표) 확인 → 관리앱 대개편(루틴앱 중심 통합) 방향이 잠깐 논의됐다가, 실제 설계 단계에서 사용자가 관리앱 요소는 배제하기로 범위 축소.
3. `Routine`/`RoutineLog` 데이터 모델, 화면 구성(오늘 탭/일과표/습관 탭/편집 화면), 스트릭 방어권(주/월 단위 봐주는 횟수) 개념을 대화로 확정.
4. 마지막으로 UI 세부사항 5개(알림 여부/요일 토글 재사용/시간대 겹침 표시/완료취소/정렬 방식)와 테마(라이트+그린, 앱 전체 적용)까지 확정. 자세한 설계 근거는 [[DECISIONS.md]] 47차 참고.

**코드는 전혀 건드리지 않음** — 다음 세션에서 데이터 모델부터 실제 구현 착수 예정([[HANDOFF.md]] 참고).

---

## 2026-08-13 (46차 세션) — 앱 이름/아이콘을 프로젝트명("갓생살기종합세트")으로 통일 + 데스크탑 그룹 off

세 가지 요청을 순서대로 처리.

- **앱 표시 이름 통일**: "폰컨트롤"로 남아있던 앱 표시 이름을 45차에 정한 프로젝트명 "갓생살기종합세트"로 양 플랫폼 통일. 안드로이드 `strings.xml`의 `app_name`, 데스크탑 `Main.kt`의 트레이 툴팁/창 제목/`ExitConfirmScreen.kt` 종료 확인 문구, `MainScreen.kt` 상단 타이틀바 텍스트 전부 교체. 브라우저 확장/각 README는 요청 범위 밖이라 그대로 둠.
- **픽셀아트 일출 아이콘 신규 제작**: 사용자가 제시한 3개 방향(자물쇠+체크, 상승 그래프, 태양/일출) 중 "태양/일출"을 픽셀아트로 선택. 안드로이드는 기존 흰 자물쇠 벡터(`ic_launcher_foreground.xml`)를 새벽하늘 그라데이션 배경(`ic_launcher_background.xml`, 파랑→주황 12줄)+태양/광선/언덕 실루엣 전경으로 완전 교체(9dp 그리드, 108x108 좌표). 데스크탑은 같은 좌표로 다중 해상도 `.ico`(`packaging/generate_icon.ps1`로 생성, `packaging/app-icon.ico`)를 만들어 `build.gradle.kts`의 `windows.iconFile`(exe 아이콘)에 연결하고, 트레이/창 아이콘도 기존 단색 `ColorPainter` 대신 같은 좌표를 그리는 커스텀 `Painter`(`ui/PixelSunriseIcon.kt`)로 교체 — 세 플랫폼(안드로이드 런처, 데스크탑 exe, 데스크탑 트레이/창)이 좌표 하나를 공유해서 어긋나지 않는다.
- **데스크탑 그룹 2개 off**: "그룹들 좀 off 해줘" 요청, 범위를 물어 데스크탑만으로 확정. `%APPDATA%\PhoneLockDesktop\data.json`의 그룹 "게임"(id 11)/"제어"(id 16)를 직접 편집.
  - **버그(같은 세션에 발견/수정)**: 처음엔 `Group.enabled` 필드를 껐는데, 이 필드는 `Models.kt` 주석에 "통계 탭 표시 필터 전용, 잠금/차단 판정과 무관"이라고 명시된 필드였다 — 사용자가 "off 안됐는데?"라고 지적해서 재확인, 실제 차단 on/off는 별도 필드 `Group.groupEnabled`(그룹 목록 화면 스위치)였다는 걸 발견해 정정. `enabled`는 원래 값(true)으로 되돌리고 `groupEnabled=false`로 다시 껐다. 앱 재시작 후에도 유지되는 것 확인. [[BUGS.md]] 46차, 메모리 `project_group_enabled_vs_groupEnabled` 참고.
  - **작업표시줄 아이콘 잔상**: 아이콘 변경 후 exe 파일 자체(임베드 아이콘)는 새 아이콘으로 확인됐는데 작업표시줄엔 옛 아이콘이 남아있던 문제 — Windows 아이콘 캐시(`%LocalAppData%\IconCache.db`, `Explorer\iconcache_*.db`/`thumbcache_*.db`) 삭제 후 `explorer.exe` 재시작으로 해결.
- **검증/빌드**: 안드로이드는 이름 변경(1회)+아이콘 변경(1회) 총 2회 `assembleDebug` BUILD SUCCESSFUL, APK 두 위치(`AndroidBuilds\phone-lock-app.apk`, OneDrive 원본) 갱신. 데스크탑은 표준 절차(watchdog 비활성화→종료→robocopy→`createDistributable`→배포 FAILED 0→재실행→watchdog 재활성화)로 3회 재배포(이름 변경, 아이콘+`build.gradle.kts`, 그룹 데이터 정정) — 이 과정에서 43~44차부터 밀려있던 데스크탑 미반영분(`WatchAndWaitScreen.kt` 힌트 가독성, `MotivationalQuotes.kt` 문구 25개)도 함께 반영됨. jpackage용 Temurin JDK 21.0.12가 이전 세션 캐시에서 손상돼 있어 사용자 승인 받아 재다운로드. **사용자가 최종 확인 완료("해결 됐어").**

---

## 2026-08-13 (45차 세션) — 프로젝트 개명("갓생살기종합세트") + 루틴앱 통합 결정

코드 변경 없음, 프로젝트 범위/이름에 대한 논의 세션.

- 사용자가 "관리앱+공부앱 통합 구조가 나은지" 질문 → 공부 잠금이 타이머 로컬 상태를 직접 읽는 실제 코드 결합이 있어 통합 유지를 추천.
- 이어서 "알람앱도 합칠까" 질문 → 알람앱(`com.wakealarm`, 별도 저장소)은 도메인·DB·코드가 완전히 독립적이라 분리 유지를 추천.
- "루틴앱을 새로 만들 건데 이 프로젝트에 포함시키자, 프로젝트 이름을 갓생살기종합세트로 바꿔줘" 요청 → 프로젝트명 변경, 루틴앱을 향후 이 프로젝트에 통합하기로 결정(계획 단계, 미착수). 자세한 판단 근거는 [[DECISIONS.md]] 45차 참고.

---

## 2026-08-12 (44차 세션) — 태블릿 제목 축소 제외 + 조롱조 문구 25개 추가

독립된 두 요청을 순서대로 처리.

- **태블릿은 제목 자동 축소 대상에서 제외**: "두줄이 되면 글자 크기가 줄어드게 하는 패치를 전에 진행했는데 생각해보니 태블릿은 또 상관이 없단 말이야 태블릿은 데스크탑처럼 글자 변환이 없게 하고 싶은데 가능해?" 요청. 43차에 구현한 `InterstitialScreen.kt`의 `maxLines=1`+`didOverflowWidth` 축소 로직이 폰/태블릿 구분 없이 전부 적용돼 있었음 — 이 프로젝트에 폼팩터 구분 로직이 아예 없어서 새로 만들어야 했고, 안드로이드 표준 브레이크포인트인 `LocalConfiguration.current.screenWidthDp >= 600`(sw600dp)으로 태블릿을 판정. 태블릿이면 데스크탑 `WatchAndWaitScreen.kt`가 하는 것과 동일하게 `Text(title, style=baseTitleStyle)`만 호출해 축소 없이 필요하면 자연스럽게 2줄로 감싸지도록 분기, 폰(600dp 미만)은 기존 43차 축소 로직 그대로 유지.
- **조롱조 문구 5단계에 각 5개씩 25개 추가**: "마지막으로 조롱조/놀림조 문구 더 추가하자" 요청. 42차에 만든 5단계(순한/중간/매콤/독함/극한, 각 20개씩 총 124개) `MotivationalQuotes.kt`/`quotes.js`에 각 단계 기존 톤에 맞춰 5개씩 새로 작성해 추가(124→149개) — 안드로이드 `MotivationalQuotes.kt`, 데스크탑 `MotivationalQuotes.kt`, 브라우저 확장 `quotes.js` 3곳 내용을 동일하게 유지.
- **검증**: 안드로이드는 두 변경을 모두 포함해 PowerShell robocopy(OneDrive→AndroidBuilds)+`assembleDebug`(JAVA_HOME=Android Studio JBR) BUILD SUCCESSFUL, APK 두 위치(`AndroidBuilds\phone-lock-app.apk`, OneDrive 원본 `app-debug.apk`) 갱신(태블릿 분기 반영 1회 → 문구 추가 반영 1회 총 2회 재빌드/배포). **데스크탑(`MotivationalQuotes.kt`)과 브라우저 확장(`quotes.js`)은 소스 파일만 갱신, 이 세션에 재빌드/재배포·재로드 안 함** — 43차부터 밀려있던 데스크탑 재배포(`WatchAndWaitScreen.kt` 힌트 가독성)와 함께 다음에 처리할 것. **실기기 검증(안드로이드)은 요청받지 않아 수행 안 함.**

---

## 2026-08-12 (43차 세션) — 사용 중 오버레이 재설계(문구 삭제+타이머 확대+레벨 연동 투명도) + 실행확인 가독성 개선

세 가지 독립 요청을 순서대로 처리.

- **사용 중 오버레이 재설계**: "오버레이는 여전히 무시 어쩌고저쩌고가 뜨는데 그냥 멘트를 아예 삭제하고 타이머만 남겨놔, 타이머를 중앙에 배치하고 크기를 많이 키운 다음에 타이머 또한 오버레이의 일부로 판단하여 평상시엔 투명하다가 레벨이 오르면 오를수록 불투명해지는 시스템으로" 요청. 안드로이드 `AppMonitorAccessibilityService.ensureUsageOverlayView()`에서 제목("무시당하면서 살기, 무시하면서 살기")과 문구(`MOTIVATIONAL_QUOTES` 랜덤 선택) TextView를 완전히 제거하고 타이머 TextView만 남겨 32sp→64sp로 확대, `FrameLayout` 정중앙 배치로 단순화. `applyOverlayOpacityForLevel()`과 뽀모도로 오버레이 둘 다 배경 alpha뿐 아니라 타이머 텍스트 색상(`argb(alpha, 0x4F, 0x8E, 0xF7)`)에도 같은 alpha를 적용해서, 레벨 0일 땐 타이머 숫자도 거의 안 보이다가 레벨이 오를수록 배경과 함께 또렷해지도록 만들었다. 브라우저 확장 `overlay.js`도 동일 패턴 — `ensureOverlay()`에서 title/quote `div` 생성 자체를 제거하고 timer `div`만 32px→96px로 확대, `applyOverlayOpacityForLevel()`/`applyPomodoroOverlayOpacity()`가 `overlayEl.style.background`뿐 아니라 `overlayTimerEl.style.color`도 같은 opacity의 `rgba(79,142,247,opacity)`로 갱신하도록 수정. 데스크탑 코너 위젯(`UsageOverlayContent.kt`)은 애초에 "무시" 문구가 없는 별개 구조(작은 창이라 전체화면 불가, `DECISIONS.md` 참고)라 이번 변경 대상에서 제외 — 사용자도 "그건 놔둬도 될 거 같다"고 확인.
- **실행확인 화면 힌트 문구 가독성 개선**: "실행확인 단계에서 뜨는 문구들의 가독성을 올려줘" 요청. 체크포인트에 걸렸을 때 뜨는 "이게 의무입니까?"/"화면(창/탭)을 벗어나서 다시 눌러야 합니다" 문구가 셋 다 작고 흐린 회색(bodySmall 또는 13px `#6b7694`)이었던 걸 굵고 밝은 텍스트로 교체 — 안드로이드 `InterstitialScreen.kt`(bodySmall→bodyMedium+`FontWeight.SemiBold`), 데스크탑 `WatchAndWaitScreen.kt`(동일), 브라우저 확장 `confirm.html`(`#hint` 13px `#6b7694`→15px 600 weight `#e4e8f5`, `<h1>` 24px→30px+`line-height:1.4`+`max-width:560px`).
- **제목 줄바꿈 자동 축소(안드로이드)**: "제목이 길어지면서 줄바꿈이 된단 말이야 모바일은, 근데 그 줄바꿈으로 인해 가독성이 떨어져" 요청. `InterstitialScreen.kt`의 제목 `Text`에 `titleScale`(remember, `title` 키로 리셋) 상태를 추가해 `onTextLayout` 콜백에서 실제 레이아웃 결과를 보고 폰트를 줄이는 shrink-to-fit을 구현.
  - **버그(같은 세션에 발견/수정)**: 1차 구현은 `maxLines=2`+`didOverflowHeight`로 짜서 "2줄을 넘칠 때만" 줄이도록 했는데, 사용자가 실제로 겪던 문제는 "2줄로 꺾이는 것 자체"였다 — 대부분의 문구는 정확히 2줄에 들어가서 애초에 축소가 발동하지 않았다("바뀐 게 없다"는 사용자 지적으로 발견). `maxLines=1`+`softWrap=false`+`didOverflowWidth`(한 줄에 안 들어가면 무조건 5%씩 줄이기, 최소 55%)로 재작업해서 해결.
- **검증**: 안드로이드는 PowerShell robocopy(OneDrive→AndroidBuilds)+`assembleDebug`(JAVA_HOME=Android Studio JBR) BUILD SUCCESSFUL, APK 두 위치(`AndroidBuilds\phone-lock-app.apk`, OneDrive 원본 `app-debug.apk`) 갱신 — 축소 로직 1차 구현 후 1회, 재작업 후 1회 총 2회 빌드/배포. `confirm.html`은 Browser 프리뷰로 실제 렌더링(제목/힌트 문구 가독성) 확인. **데스크탑은 이 세션에 재빌드/재배포 안 함** — `WatchAndWaitScreen.kt` 변경분(힌트 문구)이 실행 중인 데스크탑 앱엔 아직 반영 안 된 상태로 인계. **실기기 검증(안드로이드)은 요청받지 않아 수행 안 함.**

---

## 2026-08-12 (42차 세션) — 그룹 자동 재활성화 신규, 버튼 문구 고정, 조롱조 문구 124개+강도별 로테이션 신규

세 가지 독립 요청을 순서대로 처리.

- **그룹 자동 재활성화**: "설정에서 정한 초기화 시간이 지나면 그룹들이 꺼져 있더라도 다시 켜지게" 요청. 데스크탑 `Repository.applyDailyGroupResetIfNeeded()`/안드로이드 `PhoneLockRepository.recordBlockAttempt()`와 대칭인 `applyDailyGroupResetIfNeeded()` 신규 — `dailyResetHour` 기준 "오늘" 날짜가 바뀌면 `groupEnabled=false`인 그룹을 전부 `true`로 되돌리고, 하루 한 번만 적용되도록 `AppData.lastGroupAutoResetDate`(데스크탑)/`AppPreferences.lastGroupAutoResetDate`(안드로이드)로 날짜를 추적한다. 매 tick(2초 주기) 시작 지점에서 호출. 회유 절차 진행 중(`groupOffPending`)인 그룹은 이미 `groupEnabled=true`라 대상이 아니다.
- **버튼 문구 "전자"/"후자" → "진행"/"중단" 고정**: 실행확인/잠김 화면 버튼 라벨을 의미가 바로 읽히는 고정 단어로 교체. 배선(동작)은 그대로 — 잠김 화면의 "진행" 버튼이 장식용(아무 동작 없음)인 것도 그대로 유지, 라벨만 바꿈. 데스크탑 `ConfirmScreen.kt`/`BlockScreen.kt`, 안드로이드 `ConfirmOpenActivity.kt`/`BlockActivity.kt`, 브라우저 확장 `confirm.html`/`confirm.js`/`blocked.html`/`blocked.js` 전부 반영.
- **조롱조 문구 124개 + 강도별 로테이션**: 사용자가 반복 요청한 "무시당하면서 살기, 무시하면서 살기" 대체 문구 아이디어를 순한~극한 5단계로 총 124개까지 뽑았고, "이거 싹 다 넣어서 강도별로 로테이션 돌리자"는 요청으로 구현. `MotivationalQuotes.kt`(데스크탑/안드로이드 동일)+`quotes.js`에 5단계 배열(`MILD_QUOTES`~`EXTREME_QUOTES`, 각 20개)과 `confirmQuoteTier(level)`/`blockQuoteTier(attempts)` 두 매핑 함수 신규. 실행확인 화면은 재확인 레벨(`Repository.getCurrentLevel`, 오버레이 밝기 설정 `overlayLevelStepsToMax`와 무관한 절대 수치)이 높을수록, 잠김(스케줄/일일한도) 화면은 오늘 이 그룹을 열려고 시도한 횟수(`Group.blockAttemptDate`/`blockAttemptCount` 신규 필드, 스누즈 카운터와 동일한 하루 단위 리셋 패턴)가 많을수록 독한 문구가 뜬다. STUDY_LOCK/REELS/SHORTS 차단은 "시도 횟수" 개념이 안 맞아 강도 없이 순한 맛 고정으로 남김.
  - **버그(같은 세션에 발견/수정)**: 처음엔 로테이션 문구를 화면 부제(quote, 원래 "의무에 따라 행동하세요" 한 줄이 뜨던 자리)에 걸었는데, 사용자가 실제로 바꿔달라던 건 큰 제목(title, "무시당하면서 살기, 무시하면서 살기")이었다. title에 로테이션 문구를 걸도록 전부 스왑하고, 중복되는 quote 줄은 제거(브라우저 확장은 `<p class="quote">` 요소 자체를 HTML에서 삭제).
- **브라우저 확장 오버레이 안 뜨는 현상 제보**: 원인 조사 중 사용자가 직접 해결, 코드 변경 없이 종료("없던 일로 해").
- **검증**: 세 변경마다 각각 desktop `compileKotlin`/android `compileDebugKotlin` 확인 후 최종 `assembleDebug`+APK 두 위치 갱신, 데스크탑 `createDistributable`+robocopy 배포(FAILED 0)+재실행 — 세션 중 총 3회 배포. 매 배포 전 빌드 산출물(APK dex, 데스크탑 jar 클래스)에서 새 심볼/문구가 실제로 컴파일돼 들어갔는지 바이트 레벨로 확인(한글 문자열은 UTF-8→Latin1 재인코딩 후 `.Contains()`로 검색). 데스크탑 `createDistributable`(jpackage 필요)은 이 세션 캐시에 없어 Temurin JDK 21.0.12를 사용자 승인 받아 새로 다운로드해 사용. Room DB 버전 24→25(`AppGroup.blockAttemptDate`/`blockAttemptCount` 추가, `fallbackToDestructiveMigration()`이라 마이그레이션 코드 불필요). **실기기 UI 검증은 안 함.**

---

## 2026-08-11 (41차 세션) — 스누즈(#1) 크로스디바이스 동기화 신규 구현 + 양 플랫폼 빌드/배포

사용자가 "스누즈도 동기화되고 있는거지?"라고 문의. 코드 확인 결과 `AppGroup`/`Group` 자체를 Firebase로 push하는 로직이 원래 없어서(그룹은 애초부터 기기별 로컬), 39차에 만든 스누즈도 자연스럽게 기기별 로컬 전용이었다 — 데스크탑에서 스누즈해도 안드로이드의 같은 이름 그룹엔 전혀 반영 안 됨. 사용자가 "바로 동기화 기능 추가"를 선택해 이번 세션에 구현.

- **설계**: `confirmSync`(실행확인 레벨 동기화)와 동일한 "최신값(종료 시각) 승리" 병합 패턴을 그대로 재사용. Firebase `users/{user}/snoozeSync/{그룹명}` 경로 신설(종료시각+오늘 사용날짜+사용횟수 3필드). 데스크탑/안드로이드 양쪽 `PomodoroSyncClient`에 `readSnoozeSync`/`writeSnoozeSync` 추가.
- **Repository 병합 로직**: 양쪽 `Repository`/`PhoneLockRepository`에 `mergedSnooze()`(데스크탑은 `mergedEscalation()`과 동일한 10초 캐시+`synchronized(lock)`, 안드로이드는 별도 `snoozeMutex`+10초 캐시) 신설 — 다른 기기가 더 최근에(더 미래 시각으로) 스누즈했으면 그 상태를 로컬에도 병합·저장한다. `snoozeGroup()`(실제 스누즈 버튼 액션)은 이 병합값 기준으로 하루 3회 한도를 판정해서, 데스크탑/안드로이드에 나눠 눌러도 총 3회를 못 넘게 했다.
- **UI vs 판정 경로 분리(중요)**: `isSnoozeActive()`(그룹 목록 "😴 스누즈 중" 배지, `snoozeRemainingToday()`)는 Compose 리컴포지션마다 직접 호출되는 자리라 네트워크 호출을 넣으면 안 돼서 **로컬 값만** 보도록 그대로 유지했다. 실제 판정 게이트(`isConfirmActiveNow`/`evaluate`/`isCurrentlyRestricting`가 내부에서 쓰는 private `isSnoozed()`)만 `repository.syncedSnoozeUntil(group)`을 거치도록 바꿨다 — 이 함수는 EnforcementService/AppMonitorAccessibilityService의 백그라운드 판정 경로에서만 호출된다. 안드로이드는 이 때문에 `LockEvaluator.isConfirmActiveNow()`를 `fun`에서 `suspend fun`으로 바꿨다(호출부 3곳 모두 이미 suspend 함수 안이라 문제 없음 확인).
- **검증**: 데스크탑 `compileKotlin`, 안드로이드 `compileDebugKotlin`/`assembleDebug` 전부 BUILD SUCCESSFUL(둘 다 AndroidBuilds 경로에서, PowerShell robocopy로 소스 동기화 후). 빌드 산출물(안드로이드 apk의 classes3.dex, 데스크탑 distributable jar) 안에 `snoozeSync` 문자열이 실제로 컴파일돼 들어갔는지 바이트 레벨로 직접 확인. 안드로이드 APK는 `AndroidBuilds`/OneDrive 두 위치 모두 갱신. 데스크탑은 표준 절차(watchdog 비활성화 → 프로세스 종료 → robocopy 동기화 → `createDistributable` → 배포 전 jar 해시 일치 확인 → robocopy 배포(FAILED 0) → watchdog 재활성화 → 재실행)대로 재배포, 새 버전으로 재실행 중인 것까지 확인.
- **실기기 기능 검증**: 사용자가 직접 진행하기로 함(이번 세션에선 요청하지 않음) — 데스크탑 스누즈가 안드로이드에 실제로 반영되는지, 하루 3회 합산 한도가 정확히 작동하는지는 미확인 상태로 인계.

---

## 2026-08-11 (40차 세션) — 데스크탑 재배포(37차 → 39차 코드), 스누즈/기간 지정 UI 노출 확인

사용자가 "스누즈랑 기간 지정 잠금이 데스크탑에 안 보인다"고 문의. 소스 코드(`GroupListScreen.kt`/`GroupEditScreen.kt`)에는 39차에 이미 구현돼 있었으나, HANDOFF에 남아있던 대로 **실행 중이던 데스크탑 앱이 여전히 37차 최종 빌드**였고 38~39차 변경사항이 재빌드·재배포된 적이 없어서 안 보였던 것으로 확인.

- 표준 절차([[HANDOFF.md]] "데스크탑 빌드/배포") 그대로 진행: watchdog 비활성화 → 프로세스 종료 → PowerShell robocopy로 소스 동기화(OneDrive→AndroidBuilds) → `createDistributable` → 빌드된 jar에서 "스누즈" 문자열 실제 포함 확인(PowerShell로 클래스 바이트 직접 검사, `GroupListScreenKt$GroupRow$2` 등에서 발견) → robocopy로 배포(FAILED 0, 구 37차 jar는 extra로 정리됨) → watchdog 재활성화 → 앱 재실행.
- 이번 세션엔 소스 코드 변경 없음 — 순수 빌드/배포 갱신.
- 사용자가 재실행된 앱에서 그룹 목록의 "😴 스누즈" 버튼과 그룹 편집의 "기간 지정 자동 강화" 섹션이 **화면에 보이는 것까지 확인**. 단, 스누즈가 실제로 판정을 우회하는지/기간 지정이 스위치를 무시하고 강제하는지 같은 **기능적 동작 검증은 아직 안 함** — 다음 세션 우선순위로 유지.

---

## 2026-08-10 (39차 세션) — 38차 컴파일 확인 + 신규 기능 4건(회고 입력, 설정 내보내기/가져오기, 스누즈, 기간 지정 자동 강화)

38차가 빌드 툴체인이 없어 컴파일조차 못 하고 끝난 것을 이어받아, 이 세션에선 툴체인이 있어 최우선 과제인 컴파일 확인부터 진행. 이어서 사용자가 실기기 검증은 직접 하겠다고 하여, 전문가 보고서의 미구현 기능 중 판정 로직을 건드리지 않는 것부터 순서대로 확인하며(#6, #10) 구현했고, 마지막엔 사용자가 스누즈(#1)/기간 지정 자동 강화(#7) 세부 규칙을 직접 정해줘서 그것도 구현했다.

### 38차 컴파일 확인 및 버그 수정

- **[버그 발견/수정] 데스크탑+안드로이드 `StudyTimerScreen.kt`의 "오늘 한눈에" 요약 카드가 컴파일 자체가 안 됨**: `"$totalCount개(완료 $doneCount)"` — Kotlin 문자열 템플릿은 한글도 식별자 문자로 인식해서 `$totalCount개`가 `totalCount개`라는 존재하지 않는 변수 참조로 파싱됨(38차에 처음 작성된 코드, 양 플랫폼 대칭이라 동일 버그가 둘 다 있었음). `${totalCount}개`로 중괄호를 추가해 수정.
- 수정 후 데스크탑 `compileKotlin`, 안드로이드 `compileDebugKotlin`/`assembleDebug` 전부 BUILD SUCCESSFUL 확인. 안드로이드 APK를 `AndroidBuilds`/OneDrive 두 위치 모두 갱신.

### 신규 기능 4건(전문가 보고서 #6/#10/#1/#7, 판정 로직 영향 있는 #1/#7은 사용자와 세부 규칙 확정 후 구현)

- **[#10] 공부 세션 종료 후 짧은 회고 입력**: `StudyLogEntry`에 `note` 필드 추가(데스크탑 `Models.kt`+`JsonStore.kt`, 안드로이드 Room DB version 22→23). 타이머 탭 "정지" 버튼을 누르면 즉시 멈추는 대신 짧은 회고를 남길 수 있는 확인 대화상자가 뜬다(비워도 정지 가능). "오늘의 공부 기록"에서 업무별 최근 회고를 이름 아래 작게 표시. 전체화면 잠금 화면(`StudyLockScreen`/`StudyLockActivity`)의 정지 버튼은 회고 다이얼로그 없이 기존대로 즉시 정지(의도적 — 잠금 화면은 최소한으로 유지). 기존 크로스디바이스 공부기록 동기화 채널(`writeStudyLogForDate`/`readStudyLogForDate`)에 note가 자연히 포함됨.
- **[#6] 설정/그룹 내보내기·가져오기**: 조사 결과 **안드로이드는 이미 완전히 구현돼 있었음**(SAF `CreateDocument`/`OpenDocument`로 "클라우드로 백업"/"백업 파일에서 복원", 위치 자유 선택). **데스크탑만 빠져 있었음** — 기존 백업/복원(38차)은 앱 전용 폴더 자동 저장뿐이라 다른 PC로 옮기기 불편했음. `JsonStore.save()`의 JSON 구성 로직을 `toJsonObject()`로 추출해 공유하고 `exportToFile()` 신설, 설정 화면에 "설정/그룹 내보내기·가져오기" 카드 추가 — `java.awt.FileDialog`로 원하는 위치에 저장/불러오기(가져오기는 기존 `restoreFromBackup()`을 임의 파일에도 그대로 재사용).
- **[#1] 그룹 일시정지(스누즈)**: 사용자가 세부 규칙 확정(스누즈 시간은 그룹별로 직접 설정 가능, 하루 3회 제한). `LockEvaluator`에 `isSnoozeActive()`를 기존 `isPomodoroUnlocked()`와 동일한 패턴으로 추가 — `evaluate()`/`isCurrentlyRestricting()`/`isConfirmActiveNow()` 최상단에서 판정을 일시적으로 우회하되 `groupEnabled` 등 영구 상태는 전혀 안 건드림(그룹 목록 스위치는 계속 "켜짐"으로 보임). `Group`/`AppGroup`에 `snoozeMinutes`(그룹별 설정)/`snoozedUntilEpochMillis`/`snoozeUsedDate`/`snoozeUsedCount`(런타임 상태) 필드 추가, `Repository.snoozeGroup()`/`PhoneLockRepository.snoozeGroup()` 신설(하루 3회 초과 시 false). 그룹 목록 화면에 "😴 스누즈 N분 (n/3)" 버튼 추가(제한 중이거나 스누즈 중일 때만 표시). `ConfirmationGate.kt`는 전혀 안 건드림.
- **[#7] 기간 지정 자동 강화(시험기간 등)**: `Group`/`AppGroup`에 `forceEnabledFrom`/`forceEnabledUntil`(yyyy-MM-dd, 포함) 필드 추가. `LockEvaluator.isGroupActive()`가 이 날짜 범위 안이면 `groupEnabled`를 껐어도 켜진 것으로 강제 취급(시간대/한도 판정 자체는 그대로 따름 — "무조건 잠금"이 아니라 "스위치 무시하고 판정 파이프라인에 들어가게만" 함). 그룹 편집 화면에 시작일/종료일 입력 필드 추가. **스누즈보다 우선하도록 결정** — 시험기간처럼 미리 각오하고 설정한 강제 기간엔 즉흥적인 스누즈가 안 먹히게(`evaluate()`/`isCurrentlyRestricting()`/`isConfirmActiveNow()` 모두 `isForceEnabled() || !isSnoozed()` 순서로 체크).

### 검증

- **컴파일: 완료** — 위 모든 변경사항 포함 데스크탑 `compileKotlin`, 안드로이드 `compileDebugKotlin`/`assembleDebug` 전부 BUILD SUCCESSFUL. 안드로이드 APK 두 위치 모두 갱신.
- **실기기 검증: 미완료(사용자가 직접 진행 예정)** — 특히 신규 스누즈/기간 지정 강화는 이번 세션에 처음 만들어진 판정 로직 확장이라 우선순위 높음.
- Room DB version 22→23(`StudyLogEntry.note` 추가) → 24(`AppGroup`에 snooze/forceEnabled 필드 6종 추가) — 이번 세션에 두 번 올라감. 둘 다 컬럼 추가라 마이그레이션 코드는 불필요하지만(`fallbackToDestructiveMigration()`), 스키마 변경 시 버전을 올리는 규칙은 계속 유지.

---

## 2026-08-10 (38차 세션) — 전문가 종합분석 보고서 작성 + 신규 발견 버그 6건 수정 + 신규 기능 9건 구현

사용자가 "20년 경력 시니어 아키텍트/보안/UX/DevOps 관점에서 프로젝트 전체를 분석하고 개선하라"는 대규모 17단계 분석·기획 요청을 함. 먼저 범위를 확인(전체 그대로 진행 확정)한 뒤 3개 조사 에이전트(코드/버그/보안/성능, 기능기획 30건, 창의기능/로드맵/아키텍처)를 병렬 투입해 실제 소스(Android ~8,630줄/Desktop ~8,326줄/확장 ~700줄)를 근거로 종합 보고서를 작성하고 사용자에게 전달, 이어서 "보고서를 따라 앱 개선 진행시켜" 요청에 따라 보고서 내용을 실제로 구현. **이 세션 환경엔 빌드 툴체인(gradle)이 없어 컴파일/실기기 검증을 못했다 — 다음 세션 최우선 후보.**

- **[산출물] `전문가_종합분석_보고서_2026-08-10.md`**: 관리앱 루트에 생성, 사용자에게 파일 전달 완료. 프로젝트/코드/버그/보안/성능/UX 분석, 리팩토링 제안, 신규기능 30건+구현계획, 창의기능 10건, Phase 0~4 로드맵, 아키텍처 개선(KMP는 `CalcEngine`만 권장), 테스트 전략(테스트 코드 0개 확인), 문서화, 최종평가+TOP20 전부 포함.

### 신규 발견 버그 수정(6건, 전부 이 세션에 처음 발견 — BUGS.md 기존 항목과 무관)

- **[Critical, Desktop] `Repository.pushUsageToFirebase`가 `tickMutex`+`Repository.lock`을 쥔 채 동기 Firebase HTTP 호출**: 다른 4개 push 함수(`pushStudyLogToFirebase` 등)는 전부 `Thread{}.start()` 비동기인데 이 함수만 예외였음 — 일일한도 그룹 사용 중 30초마다 최대 수초간 다른 그룹 감시/Repository 전체 호출이 정지될 수 있는 구조적 결함. 다른 push 함수와 동일한 `Thread{}.start()` 패턴으로 전환하되, 셧다운훅 경로(`flushPendingUsage()`)는 전송 유실 방지를 위해 별도의 동기 버전(`pushUsageToFirebaseBlocking`)을 신설해 그대로 사용.
- **[High, Desktop] `peerUsageSeconds`(모바일 사용시간 합산 읽기)도 락 안 블로킹**: 캐시 만료 시(10초 TTL) `synchronized(lock)` 안에서 동기 GET. "캐시값(또는 0) 즉시 반환 + 백그라운드 스레드에서 갱신 후 짧게 재잠금해 캐시만 갱신" 패턴(stale-while-revalidate)으로 전환. `mergedEscalation()`(실행확인 레벨 계산, escalation 판정과 밀접)의 동일 문제는 판정 로직에 인접한 민감 영역이라 **이번엔 손대지 않고 남겨둠** — 향후 수정 시 사용자와 설계 재확인 필요.
- **[High, Android] `PreMigrationBackup.TABLES`가 25~27차 신규 테이블 4개 누락**: `study_log_entry`/`calendar_task`/`calc_task`/`calc_saved_item`이 백업 목록에서 빠져 있어, 다음 Room 스키마 변경(`fallbackToDestructiveMigration()`) 시 이 데이터가 유일한 로컬 안전망 없이 통째로 사라질 수 있었음. 4개 테이블 추가(이번 세션에 추가한 `confirm_counter`까지 총 5개 추가).
- **[High, Android] 캘린더/계산기 Firebase 동기화의 delete→insert 구간에 트랜잭션 부재**: `syncCalendarFromFirebase()`/`syncCalculatorFromFirebase()`가 `calendarTaskDao.deleteAll()` 이후 별도로 `insert()`를 반복 호출해서, 그 사이 프로세스가 죽으면 로컬이 빈 상태로 남을 위험이 있었음(`importBackupJson()`은 이미 `db.withTransaction{}`으로 안전했음). 세 군데(캘린더, 계산기 draft, 계산기 저장됨) 모두 `db.withTransaction{}`으로 delete+insert를 하나로 묶음.
- **[Medium, Android] `ConfirmOpenActivity.recordConfirm()`이 `lifecycleScope`(화면 소멸 시 취소됨) 사용**: `Repository.kt` 자체 주석이 경고하는 패턴을 이 호출부만 어기고 있어, 메모리 압박/태스크 스와이프 시 확인 기록이 유실될 수 있었음. `PhoneLockRepository.recordConfirmFireAndForget(groupId)` 신설(자체 `ioScope` 기반)로 교체.
- **[Medium, Android] `addUsageSeconds`의 read-modify-write가 뮤텍스 없이 동시 실행 가능**: `tick()`과 `checkSitesInternal()`이 독립 `AtomicBoolean` 가드로 동시 진행 가능해 같은 그룹 사용시간이 초 단위로 유실될 수 있었음(escalationCache는 이미 뮤텍스 보호 중이었는데 이쪽만 없었음). `usageMutex` 신설.
- **[Medium, Android] `PomodoroSyncClient`의 `tokenCache`/`statusCache`가 동기화 없는 공유 var**: 여러 IO 스레드에서 check-then-act로 접근됨. `@Volatile` 추가(가시성 문제만 해소, 논리적 경쟁은 원래도 최악의 경우가 "중복 재로그인" 정도로 낮은 위험).

### 신규 기능 9건(보고서 9~10절 번호 기준, 전부 양 플랫폼 대칭 구현·판정 로직 미변경)

- **[#11, 즉시 적용 권장] "오늘 한눈에" 요약 위젯**: 타이머 탭 상단에 오늘 캘린더 일정(전체/완료)·오늘 계산기 목표(요일별 목표량 합)·오늘 누적 공부시간을 한 줄 요약. 새 데이터/API 없이 기존 3개 조회 결과만 조합.
- **[#31] 최근 7일 vs 지난 7일 완료율 비교**: 통계 탭에 캘린더 완료율 비교 카드 추가(`+N%p`/`-N%p` 색상 구분). 새 조회 없이 기존 `allTasks`를 두 구간으로 재집계.
- **[#32] 그룹별 "재확인 통과 횟수" 카운터**: 그룹이 재확인을 통과할 때마다(`recordConfirm()` 호출부에서만) 그날 카운터를 증가. `ConfirmEscalation`과 별개의 로컬 전용 데이터(`ConfirmCounter(groupId, date, count)`) — 데스크탑은 `AppData.confirmCounters`+`JsonStore` parse/save, 안드로이드는 Room 신규 엔티티 `confirm_counter`(Room DB version 21→22). 통계 화면에 "오늘 N회(어제 M회)" 표시. **판정 로직(`ConfirmationGate.kt`) 자체는 전혀 안 건드림** — 37차의 "원본 유지, 호출부에서 부가 기능" 패턴 그대로 재사용.
- **[#19, Desktop만] 일일 다세대 백업/복원**: 앱 시작 시 하루 1회(`JsonStore.rotateDailyBackupIfNeeded()`) `data.json`을 `backups/backup_YYYY-MM-DD.json`으로 복사, 7일 초과분 자동 삭제. 설정 화면에서 날짜별 목록을 보고 "이 시점으로 복원" 가능(복원 시 확인 다이얼로그, 현재 데이터 완전 대체를 명시). 스키마 변경 시 1회성인 `PreMigrationBackup`(안드로이드)과는 목적이 다른, 매일 회전하는 안전망.
- **[#21] 오래된 통계 데이터 정리**: 설정 화면에 "12개월 이상 지난 사용시간/재확인 카운터/공부기록 정리" 버튼(수동 트리거, 캘린더의 기존 "🧹 정리" 버튼과 동일한 UX 관례 — 자동 삭제 대신 사용자가 직접 누르는 방식 채택). `Repository.pruneOldStats(monthsAgo=12)`/`PhoneLockRepository.pruneOldStats(monthsAgo=12)` 신설, 캘린더 일정과 스트릭 계산엔 영향 없음.
- **[#20] 사용 기록 CSV 내보내기**: 통계 화면에 CSV 내보내기 버튼. 데스크탑은 `java.awt.FileDialog`(SAVE 모드), 안드로이드는 `ActivityResultContracts.CreateDocument("text/csv")`로 저장 위치를 물어봄. `Repository.exportUsageCsv()`/`PhoneLockRepository.exportUsageCsv()` 신설(date,group,usedSeconds).
- **[#13, Android만] 공부 잠금 중 방해금지 모드 자동 적용**: 설정에 토글 추가(기본 off), `ACCESS_NOTIFICATION_POLICY` 특수 접근 권한이 있을 때만 `StudyLockActivity` 진입 시 `INTERRUPTION_FILTER_PRIORITY`로 전환하고 `onDestroy()`에서 원래 상태로 복원(이 화면이 직접 켠 경우만 되돌림 — 사용자가 원래 켜둔 방해금지는 안 건드림).
- **[#34] 이상 사용 패턴 경고**: 통계 화면에 그룹별 "최근 7일 평균(오늘 제외) 대비 오늘 사용량이 1.5배 넘으면" 경고 문구 표시. 시스템 알림이 아니라 화면 내 배너로 구현(모니터링 tick 루프를 건드리지 않기 위한 의도적 범위 축소). `Repository.getRecentAverageUsageSeconds()`/`PhoneLockRepository.getRecentAverageUsageSeconds()` 신설(로컬 기록 기준).

### 의도적으로 보류한 항목(보고서엔 있으나 이번에 구현 안 함)

- **#1(그룹 스누즈), #17(그룹 완화 시 PIN)**: `LockEvaluator.evaluate()`/`detectWeakeningEdit()` 등 잠금 여부를 결정하는 핵심 판정 게이트를 직접 확장해야 함 — 이 파일은 "사용자가 스스로 규칙을 약화시키는 걸 막기 위한" 방대한 방어 로직 그 자체라, HANDOFF.md의 "절대 손대지 말 것" 원칙과 정면으로 인접한 영역. 특히 스누즈는 앱의 핵심 목적(자기통제)과 정면 충돌할 여지가 있어 설계를 사용자와 먼저 확정해야 한다고 판단해 보류.
- **#14(안드로이드 접근성 이벤트 디바운싱)**: 보고서 자체가 "스로틀 값은 실기기 프로파일링 후 결정" 조건을 달았고, 해당 코드(`AppMonitorAccessibilityService.onAccessibilityEvent`)는 과거 "넷플릭스 버그"(일부 창 전환이 이벤트를 안 일으켜 감지가 멈추던 문제) 등 실기기에서만 드러난 이슈를 겪으며 조심스럽게 조정된 영역이라 실기기 검증 없이 건드리지 않음.
- **#2(원클릭 그룹 추가), #27/#28(접근성 큰글씨·TalkBack)**: 트레이 메뉴/알림 액션 통합이나 전체 화면 전수 점검처럼 범위가 넓어 컴파일 검증 불가 환경에서 리스크가 누적된다고 판단해 이번 세션엔 보류.

### 검증

- **컴파일/빌드: 못함** — 이 세션 환경엔 gradle 등 빌드 툴체인이 없음(메모리 `project_build_toolchain_missing` 참고 대상이나 이번엔 시도 안 함). 모든 수정은 기존 코드의 확립된 패턴(다른 push 함수의 `Thread{}.start()`, 캘린더의 `db.withTransaction{}`, 37차의 "원본 유지 호출부 병합" 등)을 그대로 재사용하는 최소 diff로 진행했으나, **다음 세션에서 반드시 컴파일 확인부터 먼저 할 것**.
- **실기기 검증: 못함** — 37차부터 누적된 실기기 검증 부채에 이번 9개 기능까지 더해짐.
- **남은 것**: (1) 컴파일 확인(최우선, 지금까지 없던 새로운 최우선순위), (2) 기존 1~5단계+37차 크로스디바이스 실기기 검증, (3) 위 신규 6개 버그수정+9개 기능의 실기기 검증, (4) Alt-Tab 버그 실제 수정(계속 보류 중) — [[HANDOFF.md]] 참고.

---

## 2026-08-10 (37차 세션) — 오버레이 밝기 재확인 횟수 설정 + 크로스디바이스 공부 잠금·실행확인 유예시간 동기화 신규

사용자 요청 두 가지를 순서대로 구현. 첫 번째는 순수 표시값 설정(작은 범위), 두 번째는 크로스디바이스 판정 로직을 실제로 넓히는 작업(큰 범위) — 특히 "재확인/escalation 판정 로직에는 절대 손대지 말 것" 원칙이 걸린 `ConfirmationGate.kt`를 건드리지 않고 목표를 달성하는 방법을 찾는 게 핵심이었다.

- **[신규] 그룹별 "오버레이 최고 밝기까지 재확인 횟수" 설정**: `GroupEditScreen`의 "실행 확인" 섹션(사용 중 남은 시간 오버레이 표시 토글 아래)에 정수 입력 필드 추가. 기존엔 오버레이 알파 증가폭(`OVERLAY_ALPHA_PER_LEVEL`)이 고정 상수였는데, 이제 그룹의 `overlayLevelStepsToMax`(기본 5)로부터 `(최대 알파 - 기본 알파) / 설정값`을 매번 계산해서 쓴다 — 사용자는 "몇 번째 재확인 만에 최고 밝기에 도달할지"만 정하고, 한 번 재확인할 때마다 오르는 양은 시스템이 자동 계산. 데스크탑(`Group`/`JsonStore`/`UsageOverlayContent`/`EnforcementService`/`SiteEnforcement`/`LocalApiServer`), 안드로이드(`AppGroup`/`AppMonitorAccessibilityService`, Room DB version 20→21), 브라우저 확장(`overlay.js`, `/overlay-status` 응답에 `levelStepsToMax` 필드 추가) 세 곳 모두 적용. 재확인이 언제 뜨는지/레벨이 언제 오르내리는지 같은 판정 로직은 전혀 안 건드림 — 순수하게 "보여지는 값"만 바뀜.
- **[신규] 크로스디바이스 공부 잠금 강제 적용**: 기존(34차)엔 다른 기기의 공부 타이머 실행 상태를 "표시(미러링)"만 했는데, 이번엔 실제로 잠금까지 걸도록 확장했다. `EnforcementService.checkStudyLock()`/`SiteEnforcement.isBlockedByStudyLock()`(데스크탑), `AppMonitorAccessibilityService.checkStudyLock()`/`checkStudyLockSite()`(안드로이드)가 로컬 타이머 상태(`isStudyLockActive()`)뿐 아니라 `PomodoroSyncClient.isStudyTimerActive()` 원격 신호도 OR로 확인한다. 허용 프로그램/사이트는 여전히 각 기기의 로컬 설정을 그대로 쓴다(24차 원칙 유지). 원격 신호로 잠긴 경우 정지/전환 버튼은 로컬에 실행 중인 타이머가 없어 눌러도 효과가 없으므로 화면에서 숨기고 "다른 기기에서 공부 타이머가 실행 중" 안내 문구로 대체(데스크탑 `StudyLockScreen`/안드로이드 `StudyLockActivity` 둘 다, `StudyLockStatus.isRemote`/`EXTRA_STUDY_LOCK_IS_REMOTE`로 전달). 안드로이드는 `StudyLockActivity`의 `isStillActive` 콜백을 suspend로 바꿔 로컬+원격 신호를 함께 폴링하도록 수정(안 바꾸면 원격으로 잠긴 화면이 로컬 상태만 보고 매초 즉시 닫혀버리는 버그가 생길 뻔했음 — 구현 중 발견해 함께 수정). **안전장치**: 34차에 미러 표시용으로 이미 쓰던 20분 신선도 컷오프(`REMOTE_STUDY_SIGNAL_STALE_MS`, `remoteUpdatedAtMillis()` 기준)를 재사용 — 신호를 올리던 기기가 정지 없이 앱을 꺼버리면 `timerActive:true`가 유령처럼 남을 수 있는데, 이게 실제 잠금에 쓰이므로 컷오프 없이 두면 이 기기가 영구히 잠길 위험이 있었다.
- **[신규] 크로스디바이스 실행확인 유예시간(쿨다운) 공유**: 같은 이름의 그룹을 가진 다른 기기가 실행확인 "전자"를 눌러 통과하면, 이 기기도 재확인 없이 같은 유예시간을 이어서 쓸 수 있게 했다. 기존에 이미 있던 `confirmSync`(실행확인 레벨 동기화, `lastConfirmedAtEpochMillis` 최신값 승리)를 그대로 재사용 — `Repository.syncedLastConfirmedAtEpochMillis(group)`(데스크탑)/`PhoneLockRepository.syncedLastConfirmedAtEpochMillis(group)`(안드로이드)를 신설해 `mergedEscalation()`이 계산해두던 값을 그대로 노출하고, 호출부(`EnforcementService.decide()`/`overlayStatusFor()`, `SiteEnforcement.check()`/`overlayStatus()`/`tick()`, 안드로이드 `AppMonitorAccessibilityService`의 동일 위치들)에서 로컬 `ConfirmationGate` 값과 동기화 값 중 더 최근인 쪽으로 "지금 유예시간 안인지"/"남은 시간이 얼마인지"를 계산하는 `effectiveRemainingCooldownSeconds()`/`isRecentlyConfirmedAnyDevice()`를 새로 추가했다. **`ConfirmationGate.kt`(양 플랫폼) 자체는 한 줄도 안 건드림** — "절대 손대지 말 것" 원칙(과거 큰 삽질 전례, [[DECISIONS.md]] "표시값과 판정 로직 분리" 참고)을 지키면서도, 이미 검증된 `mergedEscalation`의 최신값 승리 로직을 호출부에서 재사용하는 방식으로 목표를 달성했다. 결과적으로 두 기기의 오버레이 남은시간 표시도 같은 종료 시각을 기준으로 계산되어 "같은 타이머 시간대"를 공유하게 된다.
- **검증**: 데스크탑 `compileKotlin`, 안드로이드 `compileDebugKotlin`/`assembleDebug` 전부 `BUILD SUCCESSFUL`(사전 존재하던 무관한 경고만 있음). 데스크탑은 robocopy(PowerShell+절대경로, FAILED 0 확인)→`createDistributable`→배포 후, 배포된 jar를 직접 압축 해제해 `Group.class`(오버레이 설정 1차 배포분)와 `EnforcementService.class`(2차 배포분, `syncedLastConfirmedAtEpochMillis`/`isRemoteStudyTimerActive` 문자열 확인)에 새 필드/함수가 실제로 컴파일돼 들어갔는지 바이트코드 레벨로 확인한 뒤 재실행(2회 배포). 안드로이드는 두 차례 모두 `assembleDebug` 성공 후 APK를 `AndroidBuilds`/OneDrive 원본 두 위치 모두 갱신. **실기기(폰 재설치, 데스크탑+안드로이드 2대 동시 크로스디바이스) 검증은 이번 세션에 못함** — 다음 세션 최우선 후보.
- **남은 것**: 위 두 크로스디바이스 신규 기능의 실기기 검증(최우선 신규) + 1~5단계 전체 실기기 검증(여전히 미완료) + Alt-Tab 버그 실제 수정(여전히 보류 중) — [[HANDOFF.md]] 참고.

---

## 2026-08-08 (36차 세션) — 뽀모도로 모드 토글 유지 버그 수정 + robocopy(Bash 도구) 무반영 버그 발견

사용자 요청은 짧았다("뽀모도로 타이머 on/off가 다른 탭 갔다 오면 off로 초기화 되는게 불편해") — 실제 코드 수정 자체는 금방 끝났지만, 이후 재현이 안 된다는 보고가 3차례 반복되면서 원인이 앱 버그가 아니라 세션 내내 사용한 빌드 파이프라인 자체에 있었다는 게 드러난 세션.

- **[신규] "🍅 뽀모도로 모드" 토글이 다른 서브탭 갔다 오면 항상 OFF로 초기화되던 문제 수정**: `StudyTimerScreen`의 `pomodoroEnabled`가 `remember { mutableStateOf(false) }`로 하드코딩돼 있어 화면(서브탭)을 벗어났다 돌아오면 항상 초기값(false)으로 리마운트됐다. `pomodoroStudyMinutes`/`pomodoroBreakMinutes`와 동일한 패턴으로 `Repository`에 영속화되는 `pomodoroModeEnabled` 프로퍼티를 신설(양 플랫폼: 데스크탑 `Models.kt`+`Repository.kt`, 안드로이드 `AppPreferences.kt`+`PhoneLockRepository.kt`)해서 초기값을 그걸로 읽고, 토글 클릭 시 즉시 저장하도록 수정. 데스크탑은 수동 JSON 직렬화(`JsonStore.kt`)라 `parse()`/`save()` 양쪽에도 필드를 추가해야 했음(처음엔 누락해서 재시작 시 안 살아남는 문제가 있었다가 발견 후 추가).
- **[발견/Fixed] robocopy를 Bash 도구로 실행하면 이 프로젝트(한글 경로)에서 조용히 아무것도 복사하지 않는 버그**: 위 코드 수정을 완료하고 여러 차례 재빌드/재배포했다고 보고했지만 사용자가 "여전히 안 된다"고 세 번 반복 보고 — Gradle이 `compileKotlin`/`jar`를 매번 `UP-TO-DATE`로 스킵하며 "BUILD SUCCESSFUL"을 찍어서 정상처럼 보였다. 배포된 jar를 직접 압축 해제해 `.class` 파일에서 새로 추가한 필드/문자열을 grep해본 뒤에야, `AndroidBuilds\phone-lock-desktop\src`(빌드가 실제로 읽는 소스)에 내 수정사항이 전혀 없다는 걸 발견했다. 원인은 `robocopy "phone-lock-desktop\src" "C:\Users\sunae\AndroidBuilds\...\src" /MIR`를 **Bash 도구**로(상대경로, `cd` 이후) 실행하면 "성공"으로 보고되지만 실제로는 아무 파일도 안 옮겨지는 것 — Git Bash가 `OneDrive\바탕 화면\클로드\관리앱`의 한글 경로를 다루는 과정에서 조용히 실패하는 것으로 추정. **PowerShell 도구로 절대경로를 써서 다시 실행하니 그제서야 변경된 파일들이 "Newer"로 실제 복사됐고**, 그 뒤로는 재빌드/재배포가 정상적으로 반영됨을 배포된 jar의 클래스 파일을 직접 grep해서 확인했다. 이번 세션 내내(문제 발견 전까지) 진행한 모든 "재빌드/재배포"는 전부 옛날 코드를 다시 배포한 것이었음 — [[HANDOFF.md]] "현재 주의사항", 메모리 `feedback_robocopy_use_powershell` 참고.
- **[디버깅 기법] 임시 DebugLog 계측 후 제거**: robocopy 문제를 의심하기 전 단계에서, `StudyTimerScreen`의 mount/click 지점에 `DebugLog.log()` 임시 계측을 추가해 실행 로그(`%APPDATA%\PhoneLockDesktop\debug.log`)로 실제 동작을 확인하려 했다 — 이 로그가 전혀 안 쌓이는 것도 "배포가 반영 안 되고 있다"는 결정적 단서였다. 최종 원인(robocopy) 확인 후 계측 코드는 제거.
- **검증**: robocopy를 PowerShell+절대경로로 재실행 후 desktop `clean createDistributable`로 완전 재빌드, 배포된 jar를 직접 압축 해제해 `AppData.class`/`JsonStore.class`/`Repository.class`에 `pomodoroModeEnabled` 필드가 실제로 존재함을 바이트코드 레벨에서 확인. 안드로이드도 같은 방식(PowerShell robocopy)으로 다시 동기화 후 `assembleDebug --rerun-tasks`로 재빌드, APK 두 위치 갱신. 사용자가 데스크탑에서 토글 유지 정상 동작 확인 완료("해결됐어"). **안드로이드 실기기 재설치 확인은 아직 안 됨**(APK만 갱신, 폰에 재설치는 사용자 몫).
- **남은 것**: 1~5단계 전체 실기기 검증(여전히 최우선 미완료) + Alt-Tab 버그 실제 수정(35차부터 보류 중) — [[HANDOFF.md]] 참고.

---

## 2026-08-08 (35차 세션) — Alt-Tab 버그 원인 확정, 캘린더 미완료 자동복사, 확장 다크테마 통일, 주황→파랑 accent 전면 교체

여러 개의 짧은 요청을 순서대로 처리한 세션. 큰 구조 변경 없이 기존 패턴을 그대로 재사용하는 작업 위주.

- **[조사] Alt-Tab 사이트 차단 우회 버그 원인 확정(수정은 보류)**: `background.js`가 `chrome.webNavigation.onBeforeNavigate`(새 네비게이션)에만 반응하고, 공부 잠금 켜지기 전부터 이미 열려있던 탭으로 Alt-Tab만 하는 경우는 이 리스너가 발동하지 않는다는 걸 코드로 확인. 유일한 백업이 1분 주기 `tick` 알람이라 최악의 경우 60초까지 실제로 이용 가능. 해결 방향(`chrome.tabs.onActivated`/`chrome.windows.onFocusChanged` 즉시 재검사)은 확인했으나 사용자가 "원인만 문서화, 수정은 나중에"로 결정. [[BUGS.md]] Open 항목에 상세 기록.
- **[신규] 캘린더 "미완료(X)" 처리 시 다음날로 같은 업무 자동 복사**: 기존 "완료(O) → 자동으로 다음 회독 생성"(`applyCalendarAutoSchedule`/`revertCalendarAutoSchedule`)과 정확히 대칭되는 `applyIncompleteCarryOver`/`revertIncompleteCarryOver`를 양 플랫폼 Repository에 추가. X 누르면 같은 업무가 다음날(+1일)에 상태 초기화된 채로 복사되고 원본(오늘)은 그대로 남는다. 다시 X를 눌러 취소하면 복사된 다음날 항목도 함께 제거(O의 되돌리기 규칙과 동일).
- **[신규] 브라우저 확장 3개 페이지 다크테마로 통일**: `confirm.html`/`blocked.html`이 28차 세션에 앱 전체(안드로이드/데스크탑)에 적용된 공부앱(index.html) 다크 팔레트를 못 따라가고 옛날 라이트 "따뜻한 미니멀 테마"(`#fbf6f0`)에 남아있었고, `onboarding.html`은 아예 다른 남색 톤(`#1e293b`)이었음 — 공부앱/네이티브 앱과 동일한 다크 팔레트(`#0f1117`/`#e4e8f5`/`#2a3045` 등)로 3개 페이지 모두 교체. 브라우저에서 computed style로 hex 일치 확인.
- **[신규] "후자" 버튼/타이머 강조색을 주황(#FF9800)에서 파란 accent(#4F8EF7)로 앱 전체 교체**: 사용자 요청으로 안드로이드 6곳(`ConfirmOpenActivity.kt` 2곳, `BlockActivity.kt`, `AppMonitorAccessibilityService.kt`) + 데스크탑 3곳(`ConfirmScreen.kt`, `BlockScreen.kt`, `UsageOverlayContent.kt`) + 확장 2곳(`confirm.html`/`blocked.html` 버튼, `overlay.js` 타이머 숫자) 총 9개 파일의 `0xFFFF9800`/`#ff9800`을 전부 `0xFF4F8EF7`/`#4f8ef7`로 교체. 버튼 텍스트 색도 native의 `onPrimary=Background` 관례에 맞춰 밝은색→어두운색(`#0f1117`)으로 함께 조정(파란 배경과 대비 유지).
- **[설명, 코드 변경 없음] "어제 공부기록/캘린더 일정이 오늘도 보이는" 문의에 답변**: 버그가 아니라 `dailyResetHour` 설정(현재 9, 즉 오전 9시 리셋) 때문 — `effectiveDate()`가 자정이 아니라 이 리셋 시각 기준으로 "오늘"을 계산해서, 리셋 시각 전이면 캘린더/공부기록/일일한도 전부 아직 "어제"로 취급한다. 로컬 데이터파일(`data.json`)에서 실제 값을 확인해 원인 설명. 사용자가 "수정 없이 이대로 간다"고 결정 — dailyResetHour=9 그대로 유지.
- **검증**: 캘린더 자동복사 + 색상 교체 각각 desktop `compileKotlin`/android `compileDebugKotlin` 확인 후 안드로이드 `assembleDebug`+APK 두 위치 갱신, 데스크탑 `createDistributable`+robocopy(FAILED 0)+재실행 반복(세션 중 총 3회 배포). 브라우저 확장 3페이지는 로컬 파일을 직접 열어 computed style로 색상값 검증.
- **남은 것**: Alt-Tab 버그 수정 자체(원인은 확정, 리스너 추가는 보류) + 1~5단계 전체 실기기 검증(여전히 최우선 미완료) — [[HANDOFF.md]] 참고.

---

## 2026-08-07 (34차 세션) — 32차 아이디어 6건 전부 구현 + 타이머/공부기록 크로스디바이스 동기화

33차 세션에서 사용자가 지정한 32차 아이디어 6건을 쉬운 순서대로 전부 구현. 이어서 사용자가 "타이머가 동기화가 잘 안되네"라고 지적 → 대화로 범위를 좁혀가며 두 가지를 추가로 구현: ① 타이머 실행 상태를 다른 기기에서도 실시간에 가깝게 미러링해서 보여주는 기능(제어는 제외), ② 공부 기록(StudyLogEntry) 자체의 진짜 크로스디바이스 동기화. 마지막엔 실제 Firebase 서버 상태를 REST API로 직접 조회해 디버깅한 끝에 캘린더 화면의 "진입 시 1회만 동기화" 버그를 찾아 고쳤다.

- **[신규] 32차 아이디어 6건 전부 구현(양 플랫폼, 데스크탑 전용 항목 제외)**:
  1. "타이머" 탭 이름 → "⏱️ 시간 측정"으로 라벨만 변경(탭 타이틀+화면 제목).
  2. 데스크탑 일정표에 이전주/다음주 이동 버튼 추가(`weekOffset` 상태로 기준 일요일을 옮김, 이번 주엔 "(이번 주)" 표시).
  3. 캘린더 날짜 상세에 그날 타이머 기록 표시 — `Repository.getStudyLogForDate(dateKey)` 신규(양 플랫폼). 처음엔 "이 날 잰 시간" 별도 섹션으로 붙였다가, 사용자 요청으로 "날짜 옆에 총 시간, 각 업무 이름 옆에 그 업무 시간"으로 재배치(SectionCard 제목에 `· ⏱ 총 X` 추가, `CalendarTaskRow`의 회독 라벨 옆에 `· ⏱ X` 이어붙임).
  4. 저장됨 폴더 지정 팝오버를 웹앱과 동일한 모양으로 — 인라인 텍스트 목록 대신 `DropdownMenu`(Material3)로 버튼 옆에 뜨는 팝오버, 현재 폴더는 accent 색+굵게 강조, 폴더는 depth만큼 들여쓰기.
  5. 웹앱 애니메이션 이식 — 뽀모도로 phase 배지 dot에 `rememberInfiniteTransition`으로 pulse(1↔0.3 알파, 0.5초 왕복) 적용, 계산기 결과 카드/저장됨 항목에 `AnimatedVisibility`(fadeIn+slideInVertically)로 진입 애니메이션.
  6. 관리앱(그룹/통계) 데스크탑 좌우 분할 — 그룹 화면은 왼쪽 목록(선택 시 accent 테두리 강조)+오른쪽 편집 폼(선택 없으면 안내문)으로, 통계 화면은 왼쪽 그룹별 요약(진행바만)+오른쪽 선택한 그룹 상세(같은 데이터를 확대)로 재작성. 통계는 새 데이터(그래프 등)를 만들지 않고 있는 데이터를 선택 기반으로 확대하는 선에서 범위를 제한했다.
- **[신규] 타이머 크로스디바이스 미러**: `PomodoroSyncClient`의 push 페이로드에 `phaseStartedAt`/`taskName` 추가, `remoteUpdatedAtMillis()`(20분 초과 시 무시)로 죽은 신호 방어. `StudyTimerScreen`은 로컬 타이머가 꺼져 있을 때 다른 기기가 신선하게 재고 있으면 시작 버튼 없이 메인 카드 자체가 그 상태(phase 배지/업무명/큰 숫자)를 그대로 보여준다 — 단 정지/전환 버튼은 숨기고 "다른 기기에서 실행 중입니다" 안내만 표시(원격 제어는 하지 않음, 19차 remoteCommand 문제 재발 방지). 자세한 설계는 [[DECISIONS.md]] "타이머 크로스디바이스 '미러' 표시" 참고.
- **[신규] 공부 기록(StudyLogEntry) 크로스디바이스 동기화**: `users/{user}/studyLog/{날짜}/{기기}`에 각 기기가 자기 기록 전체를 덮어쓰는 방식(dailyUsage와 같은 "기기별 키" 패턴, 경쟁 없음). Repository에 `remoteStudyLogCache`(표시 전용, 로컬 DB엔 병합 안 함)를 추가해 `getTodayStudyLog()`/`getStudyLogForDate()`가 로컬+원격을 합쳐서 반환. 타이머 탭은 5초마다, 캘린더 날짜 상세는 패널이 열려 있는 동안 5초마다 동기화. 자세한 설계는 [[DECISIONS.md]] "공부 기록 크로스디바이스 동기화" 참고.
- **[Fixed] 캘린더 날짜 상세가 패널 진입 시 1회만 동기화하던 버그**: 날짜를 한 번 연 뒤 화면을 계속 켜놓은 채로 다른 기기에서 방금 기록을 남겨도 반영되지 않았다 — 실제 Firebase 서버 값을 REST API로 직접 조회해서(desktop 로컬 `data.json`에서 fbDatabaseUrl/fbApiKey/fbUser를 읽어 anonymous 인증 후 조회) 데스크탑 쓰기는 정상인데 안드로이드 쪽 반영이 안 됨을 먼저 확인한 뒤, 이 1회성 동기화가 원인 중 하나였음을 찾아 5초 주기로 바꿔 해결(양 플랫폼).
- **검증**: 6개 아이디어 + 미러 기능 + 공부기록 동기화 각 변경마다 desktop `compileKotlin`/android `compileDebugKotlin` 확인 후 안드로이드 `assembleDebug`+APK 두 위치 갱신, 데스크탑 `createDistributable`+robocopy(FAILED 0)+재실행을 반복(세션 중 총 8회 배포). 사용자가 안드로이드 실기기에서 공부기록 크로스디바이스 동기화 실사용 확인 완료.
- **남은 것**: Alt-Tab 사이트 차단 우회 버그(여전히 미착수) + 1~5단계 전체 실기기 검증(여전히 최우선 미완료) — [[HANDOFF.md]] 참고.

---

## 2026-08-07 (33차 세션) — 32차 신규 버그 4건 수정

32차 세션에서 사용자가 실사용 중 발견한 버그 4건을 순서대로 수정. `debug.log`/`data.json`을 직접 열어 원인을 먼저 확정한 뒤 고치는 방식으로 진행 — 특히 ②는 로그를 까보니 저장 로직이 아니라 표시 로직 문제였다는 걸 미리 확인해서 불필요한 저장 로직 수정을 피했다.

- **[Fixed] 데스크탑: 공부 잠금 중 허용 프로그램 실행 안 됨** — 원인: `ProcessBuilder("chrome.exe")`는 PATH만 검색해서 설치 경로가 PATH에 없는 실행파일명은 `CreateProcess error=2`로 못 찾는다(`debug.log`로 확정). 1차 시도로 `cmd /c start`로 바꿨더니 Chrome은 됐지만(App Paths 레지스트리에 등록돼 있어서) Discord는 여전히 실패하면서 콘솔 창만 남기고, 그 실패조차 cmd 프로세스 자체는 정상 종료라 "성공"으로 잘못 기록되는 새 문제가 생겼다(사용자 재현: "이상한 명령 프롬프트만 켜지고"). 최종적으로 `resolveAppPath()`를 신설 — ① 레지스트리 `App Paths`(Chrome/Edge류) 조회 → ② 시작 메뉴 바로가기(`.lnk`, Discord류) 이름 검색 → ③ 못 찾으면 예전처럼 PATH만 시도. 찾은 exe는 `ProcessBuilder`로 창 없이 직접 실행, `.lnk`는 `Desktop.open()`이 "Unsupported URI content"로 실패해서 `explorer.exe`에 경로를 넘겨 대신 열게 함. 자세한 배경은 [[DECISIONS.md]] "공부 잠금 허용 프로그램 실행: 이름만으로 찾는 3단계 폴백" 참고.
- **[Fixed] 데스크탑/안드로이드: 공부 타이머 기록이 "오늘의 공부 기록"에 안 남음** — 원인: `data.json`을 직접 열어보니 `Repository.timerStop()`이 실제로는 `studyLog`에 정상 적립하고 있었다(저장 로직은 처음부터 문제없었음). 진짜 원인은 UI 쪽 — 전체화면 잠금(`StudyLockScreen`/`StudyLockActivity`)에서 정지/전환했을 때, 타이머 탭(`StudyTimerScreen`)의 `todayLog` 상태가 그 탭 자체 버튼을 눌러야만(`refreshLog()`) 갱신되는 구조라 잠금 화면 경로로 쌓인 기록은 반영이 안 됐다. 두 화면의 기존 1초 tick 루프(`run == null`일 때 `todayTasks` 재조회하던 부분)에 `todayLog` 재조회를 같이 추가해서, 타이머가 멈춰 있는 동안엔 항상 최신 기록이 보이게 함.
- **[Fixed] 안드로이드: "설치 앱 목록에서 골라 허용앱 지정" 창이 안 뜸** — 원인: 정상 작동하는 `GroupEditScreen`은 앱 체크박스 목록을 최상위 `LazyColumn`의 `items()`로 바로 펼치는데, `AllowedAppsPickerBody`(`StudyLockAppsScreen`/타이머 탭 인라인 섹션이 공유)는 `LazyColumn`을 `Column`(`weight(1f, fill=false)`) 안에 중첩시켜 놨다 — 특히 타이머 탭의 `verticalScroll(Column)` 안에서 쓰일 땐 무한 높이 부모 안에서 `weight()`가 있는 중첩 스크롤이라 렌더링 자체가 깨진 것으로 보인다. `weight()`를 빼고 `heightIn(max=360dp)`만으로 고정 높이를 줘서 어느 부모 안에서도 항상 뜨게 수정.
- **[Fixed] 데스크탑: 계산기 결과 카드 가로세로 비율이 웹앱과 다름** — 원인: `CardGrid`가 화면 폭 상관없이 `items.chunked(2)`로 항상 2열 고정이라, 창을 넓게 쓸수록 카드가 웹앱보다 훨씬 가로로 넓고 짧아졌다. 웹앱의 `grid-template-columns: repeat(auto-fill, minmax(320px, 1fr))`와 동일하게 `LazyVerticalGrid(GridCells.Adaptive(minSize = 320.dp))`로 교체 — 폭에 따라 열 개수가 자동으로 늘고 줄게 됨.
- **검증**: 4건 모두 수정마다 desktop `compileKotlin` 성공 확인 후 최종적으로 안드로이드 `assembleDebug`+APK 두 위치 갱신 1회, 데스크탑 `createDistributable`+robocopy 배포(FAILED 0)+재실행 3회 반복(허용 프로그램 실행 건은 사용자 재현으로 2차 수정까지 감). 사용자가 데스크탑에서 chrome.exe/discord.exe 둘 다 정상 실행되는 것 실사용 확인 완료. ③(안드로이드 앱 목록)·④(카드 비율)는 여전히 실기기 검증 대기.
- **남은 것**: Alt-Tab 사이트 차단 우회 버그(32차 이전부터 있던 별개 버그, 이번엔 미착수) + 1~5단계 전체 실기기 검증 + 32차에서 나온 아이디어 6건([[IDEAS.md]] 참고, 다음 세션 시작 항목으로 사용자가 지정).

---

## 2026-08-07 (32차 세션) — 계산기 기능 보강 + 웹앱 소스 기반 전면 UI/색상 재검토

31차까지 완료된 코드가 실제로는 웹앱(`공부앱/index.html`)과 시각적으로 크게 다르다는 사용자 지적으로 시작, 세션 내내 스크린샷 비교 → 소스(CSS/JS) 직접 확인으로 방법을 전환하며 계산기/타이머/캘린더 3개 화면을 순차로 재구현. 사용자가 여러 차례 재현/재확인을 요구할 만큼 반복 수정이 많았던 세션 — 핵심 교훈은 "스크린샷으로 추측하지 말고 `index.html`의 CSS/JS부터 grep"([[feedback_gwanrieob_ui_reference_source_first]] 메모리화됨).

- **[신규] 계산기 기능 보강(양 플랫폼)**: ① 폴더 접기 상태 영속화(`calcFolderCollapsed`, 로컬 전용) — 나갔다 들어와도 유지, ② 업무 입력/결과 카드 개별 접기, ③ "모두 펴기/접기" 버튼(입력·결과 각각), ④ 결과 탭 "전체 저장" 버튼, ⑤ 입력 탭 하단 버튼(추가/계산/초기화)을 스크롤 밖 고정 영역으로 분리. 안드로이드 타이머 탭엔 기존 `StudyLockAppsScreen`의 앱 선택 로직을 재사용한 인라인 접이식 "공부 잠금 허용 앱" 섹션 신규 추가.
- **[신규] 계산기 결과 카드를 웹앱 실제 CSS(`​.result-block`/`.dday-badge`/`.progress-bar-fill`/`.pace-table`/`.result-verdict`) 기준으로 재구현**: 여러 차례 오독을 거쳐 최종적으로 확정된 규칙 — 카드 배경/테두리는 항상 무채색, 색은 ①D-day 배지(파랑 고정) ②진행바 채움(파랑→초록 그라디언트, 상태 무관) ③"필요⚠️" 행(빨강 고정) ④판정 배너 배경+테두리(충분=초록/부족=빨강, 텍스트는 무채색) ⑤마감 초과/여유 배지, 이 5곳에만 쓰인다. `CalcEngine`에 이미 있던 `totalCapacity`/`finishDiffDays` 필드(엔진 수정 없이 UI만 새로 사용)로 "기간 내 X 소화 가능"·"마감 N일 초과/전" 문구를 처음으로 표시.
- **[신규] 타이머 화면을 웹앱 실제 CSS(`.pomo-phase-badge`/`.timer-display.running`/`.pomo-toggle-btn`/`.study-log-row`/`.lock-app-*`) 기준으로 재구현**: 상태 배지 공부=파랑/휴식=**초록**(먼저 보라로 잘못 넣었다가 정정), 타이머 숫자는 phase 무관 실행 중이면 항상 파랑 굵은 모노스페이스, "전환" 버튼을 채워진 버튼→파랑 틴트 아웃라인으로, 뽀모도로 on/off를 스위치→ON/OFF 알약 버튼으로, 시간 초과 시 노란 안내 박스 신규 추가, "오늘의 공부 기록"을 텍스트 나열→카드형 행+합계 행(파랑 강조)으로, 허용 프로그램/사이트 입력을 여러 줄 텍스트박스→입력+추가버튼+개별 삭제(✕) 리스트로 전면 교체.
- **[Fixed] 타이머 탭 "오늘 캘린더 일정" 드롭다운이 여러 항목이 있어도 선택이 안 바뀌는 버그**: `OutlinedTextField(readOnly=true)`에 `Modifier.clickable`만 얹은 방식이 readOnly 텍스트필드 자체의 포인터 입력 가로채기와 충돌하는 것으로 추정 — Material3 표준 패턴인 `ExposedDropdownMenuBox`로 교체해 해결(양 플랫폼).
- **[신규] 캘린더 화면을 웹앱 실제 CSS(`.day-cell`/`.task-chip`/`.task-name-modal`/`.modal-btn`) 기준으로 재구현(좌:월그리드/우:날짜상세 분할 레이아웃은 유지)**: "오늘" 표시를 셀 전체 배경→날짜 숫자만 원형 파란 배지로 정정, 월 그리드 일정은 회독 단계별 배경+테두리를 채운 배지(O/X 완료 표시는 칩 색과 별개로 항상 초록/빨강), 날짜 상세의 일정 이름은 칩이 아니라 배경 없는 색 텍스트(red 단계는 "회색"이 아니라 사실 흰 텍스트 — 테두리만 회색이라 그리 보였던 것, 이전 세션에 잘못 칩으로 만들었던 부분 정정), 완료/미완료/이동/복사/삭제 5개 버튼을 각각 초록/빨강/파랑/보라/빨강으로 틴트.
- **[신규] 캘린더 월 그리드 세로 스트레치**: 데스크탑 날짜 칸을 고정 72dp에서 남는 세로 공간을 다 채우도록(행마다 `weight(1f)`) 변경 — 창을 키워도 그리드 아래 빈 공간이 안 남음.
- **[신규] 캘린더 날짜 상세 행 재배치**: "다음 회독" 입력을 라벨 붙은 140dp 텍스트필드(별도 줄)→헤더 줄 안의 64dp 소형 알약 입력으로, 완료/미완료/이동/복사/삭제 5개 버튼을 웹앱처럼 폭 균등 분배(`flex:1`)로.
- **[일정표/통계 색상 보정]**: 일정표 오늘 칸 값 빨강/합계 열 파랑 추가(이전엔 무채색), 통계 지표 타일에 테두리만 있고 없던 배경 틴트(7%) 추가.
- **검증**: 각 단계마다 양 플랫폼 컴파일 확인(`compileKotlin`/`compileDebugKotlin`) 후 안드로이드 `assembleDebug`+APK 두 위치 갱신, 데스크탑 `createDistributable`+robocopy 배포(FAILED 0)+재실행 — 세션 중 총 8회 반복 배포. **실기기(안드로이드 실물 기기) 검증은 여전히 안 함**, 데스크탑은 사용자가 매 반복마다 스크린샷으로 확인.
- **사용자가 이번 세션 중 발견한 새 버그 4건**([[BUGS.md]] Open 참고): ① 데스크탑 공부 잠금 중 허용 프로그램 실행 안 됨, ② 데스크탑 타이머 측정 기록이 "오늘의 공부 기록"에 안 남음, ③ 안드로이드 허용 앱 선택 창이 안 뜸(그룹 만들 때 앱 고르는 화면은 정상 작동 — 그 구현 참고 요청), ④ 계산기 결과 카드 가로세로 비율이 웹앱과 다름(2열 고정 그리드 vs 웹앱 `minmax(320px,1fr)` 자동 배치).
- **다음 세션으로 넘긴 아이디어 6건**([[IDEAS.md]] 참고): 캘린더 날짜 상세에 타이머 기록 표시, 웹앱 애니메이션 이식, 관리앱 섹션(그룹/통계)도 데스크탑 좌우 분할, 폴더 지정 팝오버를 웹앱과 동일하게, 데스크탑 일정표 주 이동 네비게이션, "타이머"→"시간 측정" 이름 변경.

---

## 2026-08-07 (31차 세션) — 계산기 버그 3건 수정(사용자 실기기 검증 중 발견)

30차까지 로드맵 5단계 전체가 완료된 뒤, 사용자가 실제로 계산기 탭을 써보면서 발견한 버그 3건.

- **[Fixed] 데스크탑: "계산하기" 버튼을 누르면 항상 이상한 오류가 뜸** — 원인: `CalculatorScreen.kt`의 `CalcResultTab`이 `results.filterIsInstance<Pair<CalcTask, CalcEngine.CalcOutcome.Error>>()`로 에러만 골라내려 했는데, 제네릭 타입 소거 때문에 이 필터는 실제로 `Pair`이기만 하면(Success든 Error든 상관없이) 전부 통과시킨다. 이후 `outcome.message`에 접근하는 순간 실제 런타임 타입이 `Success`인 항목에서 `ClassCastException`이 터져 계산 결과가 하나라도 성공하면(거의 항상) 크래시. `is` 체크로 직접 분기하도록 수정.
- **[Fixed] 저장됨 항목이 폴더 소속 표시는 되는데 그 폴더가 목록에 안 뜸** — 원인: 웹앱에서 만들어진 기존 Firebase 데이터가 항목별 `folderPath`만 갖고 `savedFolderTree`(폴더 트리 자체)는 비어있는 채로 동기화된 경우, 데스크탑/안드로이드 둘 다 폴더 목록을 `savedFolderTree`에서만 읽어와 그런 폴더는 트리에 없어 안 보였다(항목의 폴더 이름 텍스트는 항목 자체에 저장돼 있어 그대로 표시됨). `Repository`(데스크탑)/`PhoneLockRepository`(안드로이드)에 `healCalcFolderPaths()` 추가 — `syncCalculatorFromFirebase()` 실행 시 저장 항목이 참조하는 폴더 경로(및 조상 경로)가 목록에 없으면 자동으로 채워 넣고 Firebase에도 다시 푸시(웹앱의 `rebuildFolderTreeFromItems`와 동일한 보정).
- **[Changed] 데스크탑 계산기 레이아웃을 웹앱 사이드바 구조로 재작성** — 28차에서 입력/결과/저장됨을 동등한 3개 탭으로 바꿨는데, 결과가 별도 탭 뒤에 숨어 있어 계산 후 결과를 못 찾는 것처럼 보인다는 문제(사용자 지적)가 있었다. 웹앱(`공부앱/index.html`의 `.calc-layout`/`.calc-sidebar`/`.calc-content`)과 동일하게 왼쪽 좁은 칸(비율 2, 업무 입력/저장됨 서브탭)+오른쪽 넓은 칸(비율 8, 결과 — 서브탭이 아니라 항상 보이는 영역)으로 되돌렸다. 왼쪽 폭이 좁아진 만큼 입력 카드는 2열 그리드 대신 1열로, 저장됨은 좌우 분할 폴더 탐색기 대신 안드로이드판과 동일한 재귀 폴더 트리(세로 나열)로 함께 되돌림 — 안드로이드 레이아웃은 이번 변경 대상 아님(원래도 세로 분리 구조로 사용자가 문제없다고 확인).
- **검증**: 양 플랫폼 컴파일 성공(`compileDebugKotlin`/`compileKotlin`), 안드로이드 `assembleDebug`+APK 두 위치 갱신, 데스크탑 `createDistributable`+robocopy 배포(FAILED 0)+재실행 완료. **실기기 UI 재확인은 아직 안 함.**

---

## 2026-08-07 (30차 세션) — 5단계(통계) 네이티브 구현
23차에서 확정된 5단계 로드맵의 마지막 단계 착수. 사용자가 실기기 검증보다 5단계 착수를 먼저 선택.

- **신규**: 양 플랫폼 "통계" 탭 — `phone-lock-desktop/.../ui/StudyStatsScreen.kt`, `phone-lock-android/.../ui/StudyStatsScreen.kt`. 웹앱(`공부앱/index.html`)의 `renderStats()`를 이식, 별도 데이터 모델 없이 캘린더 일정(`repository.getAllCalendarTasks()`/`getAllCalendarTasksOnce()`)만 집계하는 읽기 전용 파생 뷰 — 4단계(일정표)와 같은 "파생 뷰" 원칙.
  - 전체 일정/완료/완료율/연속 완료일(스트릭) 4개 지표 카드, 회독 단계별(1~4회독) 완료 현황, 최근 30일 완료 추이 막대그래프(막대 높이=일정 개수, 색상=완료율)를 웹앱 로직 그대로 이식.
  - 스트릭 계산은 웹앱과 동일하게 "일정 없는 날은 중립(건너뜀), 일정 있는데 미완료면 중단" 규칙 유지.
  - `Repository`(데스크탑)/`PhoneLockRepository`(안드로이드)에 날짜 범위 없이 전체 캘린더 일정을 가져오는 함수 신규 추가(`getAllCalendarTasks`/`getAllCalendarTasksOnce`, 안드로이드는 기존 `CalendarTaskDao.getAllOnce()` 재사용).
  - "관리앱"/"공부앱" 탭 구조의 공부앱 섹션에 5번째(마지막) 서브탭으로 추가(`MainScreen.kt`/`MainActivity.kt`의 `studySubTab`/`subTab` 인덱스 4).
  - 안드로이드는 화면 폭이 좁아 30일 막대그래프의 날짜 라벨을 5일 간격(+오늘)만 표시, 데스크탑은 매일 표시 — 플랫폼별 화면 폭 차이에 따른 자연스러운 조정(일정표 데스크탑/모바일 뷰 분기와 같은 종류의 차이).
- **범위**: 계산기 연동(`linkedCalc`)은 3~4단계와 마찬가지로 이번에도 제외. 이로써 23차 세션에서 확정한 5단계(타이머→캘린더→계산기→일정표→통계) 로드맵 전체가 코드/빌드 기준으로 완료됨.
- **검증**: 양 플랫폼 컴파일 성공(`compileKotlin`/`compileDebugKotlin`), 안드로이드 `assembleDebug` 성공+APK 두 위치 갱신, 데스크탑 `createDistributable`+robocopy 배포(FAILED 0)+재실행 확인 완료. **실기기 UI 확인은 아직 안 함** — 1~5단계 전체 + 28~29차 UI 변경사항의 미검증 항목과 함께 다음 세션 최우선.

---

## 2026-08-07 (29차 세션) — 4단계(일정표) 네이티브 구현
23차에서 확정된 5단계 로드맵의 4번째 단계 착수. 사용자가 실기기 검증보다 4단계 착수를 먼저 선택.

- **신규**: 양 플랫폼 "일정표" 탭 — `phone-lock-desktop/.../ui/TimetableScreen.kt`, `phone-lock-android/.../ui/TimetableScreen.kt`. 웹앱(`공부앱/index.html`)의 `renderTimetable()`을 이식, 할당량 계산기의 draft 업무 목록(`repository.getCalcTasks()`, 저장됨 목록 아님)을 요일별 목표량 표로 보여준다.
  - **데스크탑**: 웹앱 데스크탑(주간) 뷰 그대로 — 이번 주(일~토) 고정, 업무×요일 테이블 + 요일별/전체 합계 행.
  - **안드로이드**: 웹앱 모바일(일 단위) 뷰 그대로 — ◀/▶ 날짜 이동 + 선택한 날짜의 업무 목록 + 합계.
  - 웹앱의 캘린더 연동(`linkedCalc`/`progressStep` 완료 체크마크)은 3단계에서 이미 제외된 기능이라 이번에도 미포함 — [[DECISIONS.md]] "4단계(일정표) 네이티브 재구현" 참고.
  - "관리앱"/"공부앱" 탭 구조의 공부앱 섹션에 4번째 서브탭으로 추가(`MainScreen.kt`/`MainActivity.kt`의 `studySubTab`/`subTab` 인덱스 3).
- **검증**: 양 플랫폼 컴파일 성공(`compileKotlin`/`compileDebugKotlin`), 안드로이드 `assembleDebug` 성공+APK 두 위치 갱신, 데스크탑 `createDistributable`+robocopy 배포(FAILED 0)+재실행 확인 완료. **실기기 UI 확인은 아직 안 함** — 이전 세션들의 미검증 항목과 함께 다음 세션 최우선.

---

## 2026-08-07 (28차 세션) — 실기기 검증 1차 발견 버그 수정 + 탭 구조 개편 + 다크 테마 전면 적용
1~3단계 실기기 검증 중 발견된 버그 수정과 함께, 사용자 요청으로 네비게이션 구조와 전체 테마를 개편.

- **버그 수정**: `StudyTimerScreen.kt`(양 플랫폼) 타이머 탭에서 캘린더 일정 선택이 1초마다 첫 항목으로 리셋되던 문제 수정. [[BUGS.md]] 참고.
- **다크 테마 전면 적용**: `ui/theme/Color.kt`/`Theme.kt`(양 플랫폼)를 공부앱(`공부앱/index.html`)과 동일한 다크 팔레트(배경 `#0f1117`/카드 `#1e2333`/포인트 파랑 `#4f8ef7`·보라 `#a78bfa`/성공 `#34d399`/경고 `#fbbf24`/에러 `#f87171`)로 교체, `lightColorScheme`→`darkColorScheme`. `Shape.kt` medium/large 반경 16dp→12dp(공부앱 `--radius`와 통일). 기존 따뜻한 톤(주황/베이지)의 라이트 테마는 폐기.
- **네비게이션 구조 개편**: 기존에 그룹/통계/타이머/캘린더/계산기/설정 6개가 나란히 있던 구조를, "관리앱"(그룹/통계)과 "공부앱"(타이머/캘린더/계산기) 2개 상위 섹션 + 설정으로 재구성.
  - **데스크탑**: `MainScreen.kt`를 왼쪽 `NavigationRail`(관리앱/공부앱/설정) + 섹션 내부 `TabRow` 서브탭 구조로 재작성 — 사용자가 데스크탑 전용 최적화로 사이드바 방식을 선택.
  - **안드로이드**: `MainActivity.kt`의 하단 `NavigationBar`를 6탭→3탭(관리앱/공부앱/설정)으로 축소, `NavHost`에 `ManageSection`/`StudySection` composable을 새로 추가해 내부 `TabRow` 서브탭으로 그룹/통계, 타이머/캘린더/계산기를 각각 묶음. 그룹 편집(`group_edit/{groupId}`)·허용 앱 선택(`study_lock_apps`) 등 드릴다운 라우트는 그대로 유지.
- **검증**: 양 플랫폼 컴파일 성공, 안드로이드 `assembleDebug` 성공+APK 두 위치 갱신, 데스크탑 `createDistributable`+robocopy 배포(FAILED 0)+재실행 확인 완료. **이번 세션 변경사항(탭 구조/테마/버그 수정)의 실기기 확인은 아직 안 함** — 다음 세션에서 기존 1~3단계 검증과 함께 확인.

### 후속 수정: 데스크탑 다크 테마가 흰 배경으로 보임 + 시각적 완성도 보강
사용자가 배포된 앱을 확인한 뒤 "다크 테마가 아니라 흰 바탕이 보이고, 공부앱만큼 세련된 느낌이 아니다"라고 피드백.
- **원인**: `Main.kt`의 메인 `Window`가 `MainScreen(repository)`를 `PhoneLockTheme`으로만 감싸고 배경을 실제로 칠하는 `Surface`가 없었다 — `MaterialTheme`은 색상 팔레트만 정의할 뿐 캔버스를 칠하지 않으므로, `MainScreen`의 각 화면이 덮지 않는 여백은 Window 기본 배경(흰색)이 그대로 비쳤다. 전체화면 인터스티셜 창들(`WatchAndWaitScreen` 등)은 이미 `Surface { }`로 감싸고 있어 이 문제가 없었음.
- **해결**: `Main.kt`에 `Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize())`로 `MainScreen`을 감쌈.
- **시각 보강**(사용자가 "데스크탑다운 세련된 디자인"을 원함): `SectionCard`(양 플랫폼)에 공부앱 카드 스타일과 동일한 1px `outline` 테두리 추가. 데스크탑 `MainScreen.kt`에 공부앱 상단바를 흉내낸 슬림 타이틀 바("폰컨트롤" 로고) 추가, `NavigationRail`/`TabRow`에 명시적 다크 테마 색상(선택 시 `primary`, 배경 `background`/`surface`) 지정.
- **검증**: 양 플랫폼 재컴파일 성공, 안드로이드 `assembleDebug`+APK 두 위치 갱신, 데스크탑 재배포(FAILED 0)+재실행 확인 완료.

### 후속 수정: 데스크탑 전용 좌우 분할 레이아웃(가로/세로 역할 구분)
사용자가 재배포 후에도 "데스크탑만의 UI가 없다"고 재차 피드백 — 데스크탑판이 모바일처럼 카드/필드를 세로로 쭉 나열하기만 할 뿐, 넓은 화면을 가로로 나눠 쓰는 레이아웃이 없었다는 지적. 데스크탑 화면 3개를 좌우 분할 구조로 재작성.
- `StudyTimerScreen.kt`: 왼쪽 = 타이머 본체, 오른쪽 = 허용 프로그램/사이트 설정 + 오늘 기록(각각 독립 스크롤).
- `CalendarScreen.kt`: 왼쪽 = 월 그리드(고정 높이), 오른쪽 = 선택한 날짜 상세(독립 스크롤) — 웹앱의 모달 대신 항상 곁에 두고 보는 패널.
- `CalculatorScreen.kt`: 입력/결과 탭은 카드를 2열 그리드로 배치(`CardGrid` 헬퍼, 창 폭에 맞춰 세로 1열로도 접힘). 저장됨 탭은 기존의 재귀 중첩 폴더 트리(전부 펼쳐야만 항목이 보이던 방식) 대신 파일 탐색기 스타일 좌우 분할로 전면 재작성 — 왼쪽 폴더 목록(전체/미분류/폴더별, 항목 개수 표시)에서 폴더를 고르면 오른쪽에 그 폴더 항목만 표시. "저장됨에 아무것도 안 보인다"는 지적(하위 폴더에 저장된 항목이 안 펼쳐진 트리에 묻혀 안 보였을 가능성)도 "전체" 뷰로 해결.
- **검증**: 양 플랫폼 재컴파일 성공, 데스크탑 재배포(FAILED 0)+재실행 확인 완료. 안드로이드는 변경 없음(모바일은 기존 세로 레이아웃 유지, 데스크탑 전용 최적화라는 사용자 확인 범위 그대로).

## 2026-08-07 (27차 세션) — 공부앱 네이티브 재구현 3단계: 계산기
2단계(캘린더) 완료 후 착수. 사용자 확인: 저장됨 목록의 폴더 트리(생성/이름변경/삭제/순서변경/항목 이동)까지 웹앱과 동일하게 전부 구현, 캘린더 연동은 이번에도 제외. 설계 판단은 [[DECISIONS.md]] "3단계(계산기) 네이티브 재구현" 참고.

- **데스크탑**: 신규 `calc/CalcEngine.kt`(웹앱 `calculate()`/`simulateFinish()`/`calcRequiredPace()`/`countDaysInRange()` 이식, `LocalDate` 기반 순수 함수). `data/Models.kt`에 `CalcTask`(draft)/`CalcSavedItem`(저장됨) 데이터클래스, `AppData`에 `calcTasks`/`calcSaved`/`calcFolderPaths`/`calcFolderOrder`+각 구간 LWW 타임스탬프 필드 신설. `JsonStore`에 직렬화 추가. `Repository`에 계산기 CRUD(업무 추가/수정/삭제/순서변경/초기화, 결과 저장, 폴더 생성/이름변경/삭제/순서변경, 항목 폴더 이동) 및 Firebase 3구간(draft/저장됨/폴더) 독립 LWW 동기화(`syncCalculatorFromFirebase`) 신설. `monitor/PomodoroSyncClient.kt`에 `readCalculator`/`writeCalcTasksAndSaved`/`writeCalcFolders` 추가(`users/{user}/calculator` 경로, 웹앱과 동일 스키마 — PATCH로 부분 갱신, 폴더 트리는 평평한 경로 리스트↔웹앱 중첩 객체 상호 변환). 신규 `ui/CalculatorScreen.kt`(입력/결과/저장됨 3서브탭, 저장됨은 재귀 폴더 트리 렌더링)를 `MainScreen.kt`에 새 탭으로 등록(그룹/통계/타이머/캘린더/**계산기**/설정).
- **안드로이드**: `Entities.kt`에 `CalcTask`/`CalcSavedItem` Room 엔티티(요일 필드/holidays는 CSV 문자열로 저장, 순서는 `sortOrder`), `Daos.kt`에 `CalcTaskDao`/`CalcSavedItemDao`, `AppDatabase` version 19→20. `AppPreferences`에 계산기 ts 필드 4종 + 폴더 트리/순서 JSON 문자열 필드 추가. `PhoneLockRepository`에 데스크탑과 대칭인 계산기 CRUD+Firebase 동기화 함수 신설(단, 순서는 리스트 인덱스 대신 Room 엔티티 자체+`sortOrder`로 관리). `service/PomodoroSyncClient.kt`에 동일 기능 추가 — **Android `HttpURLConnection`은 PATCH 메서드를 지원하지 않아 `POST + X-HTTP-Method-Override: PATCH` 헤더로 우회**(Firebase REST API 공식 지원 방식, 데스크탑은 `java.net.http.HttpClient`라 이 문제 없음). 신규 `calc/CalcEngine.kt`(데스크탑판과 동일 로직 대칭 복제), `ui/CalculatorScreen.kt`를 `MainActivity.kt`에 새 탭으로 등록(bottom navigation, `Icons.Filled.Calculate`).
- **범위 제외**: 캘린더 연동(`linkedCalc`/`progressStep`)은 2단계와 마찬가지로 UI 미구현(필드만 데이터 모델에 보존).
- **검증**: 양 플랫폼 컴파일 성공(`compileKotlin`/`compileDebugKotlin`), 안드로이드 `assembleDebug` 성공 + APK 두 위치(AndroidBuilds/OneDrive 원본) 갱신 완료, 데스크탑 `createDistributable`+robocopy 배포(FAILED 0) + 앱 재실행 확인(3개 프로세스 정상 기동)까지 완료. **실기기 동작 검증(1~3단계 전부)은 아직 안 함** — 다음 세션 최우선.

## 2026-08-07 (26차 세션) — 데스크탑 재배포(jpackage 포함 JDK 확보)
25차에서 미뤄진 데스크탑 `createDistributable`/배포를 마무리. 코드 변경 없음, 순수 빌드/배포 작업.

- 이 세션 샌드박스 스크래치에 jpackage 포함 JDK가 없어(`project_build_toolchain_missing` 메모리대로) adoptium.net에서 Temurin JDK 21.0.12(`OpenJDK21U-jdk_x64_windows_hotspot_21.0.12_8.zip`, 약 195MB)를 사용자 승인 받고 다운로드, 세션 스크래치에 압축 해제.
- HANDOFF "데스크탑 빌드/배포" 순서대로: watchdog 예약 작업 비활성화 → 앱 프로세스 종료 → 소스 robocopy(변경 없음) → 새 JDK로 `gradle createDistributable`(BUILD SUCCESSFUL) → `robocopy /MIR`로 `PhoneLockDesktopApp`에 배포(FAILED 0) → watchdog 재활성화 → 앱 재실행 확인.
- **검증**: 배포된 앱 3개 프로세스로 정상 기동 확인(실행만 확인, 캘린더 탭 등 기능 동작은 미검증). 실기기 동작 검증(1·2단계 모두)은 여전히 다음 세션 최우선.

## 2026-08-07 (25차 세션) — 공부앱 네이티브 재구현 2단계: 캘린더
1단계(타이머) 완료 후 착수. 사용자 확인: 캘린더는 Firebase로 기기 간 동기화(기존 웹앱과 같은 경로, 데이터 그대로 이어받음), 타이머의 자유 텍스트 업무 입력을 "오늘 캘린더 일정" 드롭다운으로 교체. 설계 판단은 [[DECISIONS.md]] "2단계(캘린더) 네이티브 재구현" 참고.

- **데스크탑**: `data/Models.kt`에 `CalendarTask` 데이터클래스 추가, `AppData`에 `calendarTasks`(통짜 리스트)·`calendarTs`(LWW 타임스탬프) 필드 신설. `JsonStore`에 직렬화 추가. `Repository`에 캘린더 CRUD(`addCalendarTask`/`renameCalendarTask`/`recolorCalendarTask`/`setCalendarTaskNextDays`/`moveCalendarTaskOrder`/`deleteCalendarTask`/`moveCalendarTaskToDate`/`copyCalendarTaskToDate`/`archiveOldCalendarTasks`)와 완료 시 자동 다음-회독 생성/취소 로직(`setCalendarTaskStatus`, `applyCalendarAutoSchedule`/`revertCalendarAutoSchedule`), Firebase 전체문서 LWW 동기화(`syncCalendarFromFirebase`/`pushCalendarToFirebase`) 신설. `monitor/PomodoroSyncClient.kt`에 `readCalendarTasks`/`writeCalendarTasks` 추가(`users/{user}/calendar` 경로, 웹앱과 동일 스키마). 신규 `ui/CalendarScreen.kt`(월 그리드 + 선택 날짜 상세 섹션)를 `MainScreen.kt`에 새 탭으로 등록(그룹/통계/타이머/**캘린더**/설정). `StudyTimerScreen.kt`의 "업무 이름" 자유 텍스트 입력을 오늘 캘린더 일정 드롭다운으로 교체.
- **안드로이드**: `Entities.kt`에 `CalendarTask` Room 엔티티(순서 관리용 `sortOrder` 필드 포함) 추가, `Daos.kt`에 `CalendarTaskDao`, `AppDatabase` version 18→19. `AppPreferences`에 `calendarTs` 필드 추가. `PhoneLockRepository`에 데스크탑과 대칭인 캘린더 CRUD+자동 스케줄링+Firebase 동기화 함수 신설(단, 순서는 `dateKey+ordinal` 대신 Room의 `CalendarTask.id`+`sortOrder`로 관리 — 데이터 저장 방식 차이에 따른 자연스러운 API 차이, [[DECISIONS.md]] 참고). `service/PomodoroSyncClient.kt`에 `readCalendarTasks`/`writeCalendarTasks` 추가(데스크탑과 동일 스키마). 신규 `ui/CalendarScreen.kt`를 `MainActivity.kt`에 새 탭으로 등록. `StudyTimerScreen.kt` 동일하게 드롭다운으로 교체.
- **범위 제외**: "할당량 연동 추가"(계산기 연동) 섹션은 계산기(3단계)가 아직 없어 이번엔 만들지 않음 — `linkedCalc`/`progressStep` 필드는 데이터 모델에만 보존.
- **검증**: 양 플랫폼 `robocopy → compileKotlin`/`compileDebugKotlin` 컴파일 성공, 안드로이드는 `assembleDebug`까지 성공하고 APK 두 위치(AndroidBuilds/OneDrive 원본) 갱신 완료. 데스크탑 `createDistributable`(jpackage 필요)은 이 세션 환경에 jpackage 포함 JDK가 없어 미실행(재현 절차는 `project_build_toolchain_missing` 메모리 참고) — 재배포 필요 시 다음 세션에서. **실기기/실행 동작 검증은 아직 안 함**(1단계 검증도 여전히 미완) — 다음 세션 최우선.

## 2026-08-07 (24차 세션) — 공부앱 네이티브 재구현 1단계: 타이머/뽀모도로
23차에서 확정된 방침의 착수. 착수 전 사용자 확인: 공부앱 웹 버전은 장기적으로 완전히 네이티브가 대체(당장은 미변경), 단계 순서는 타이머 → 캘린더 → 계산기 → 일정표 → 통계. 자세한 설계 판단은 [[DECISIONS.md]] "1단계(타이머/뽀모도로) 네이티브 재구현" 참고.

- **데스크탑**: `data/Models.kt`에 `TimerRunState`/`StudyLogEntry` 추가, `AppData`에 타이머 상태·뽀모도로 설정(분)·공부기록·허용 프로그램/사이트 필드 신설. `JsonStore`에 직렬화 추가. `Repository`에 `timerStart`/`timerStop`/`timerSwitchPhase`/`timerExtendBreak`/`isStudyLockActive`/`isTimerPomodoroMode` 등 웹앱 `index.html`의 타이머 규칙(wall-clock 기반, 공부→휴식은 시간 다 채워야 전환, "5분만 더" 1회)을 그대로 이식. 신규 `ui/StudyTimerScreen.kt`(타이머 UI + 허용 프로그램/사이트 입력 폼, 지금까지 데스크탑엔 이 입력 UI 자체가 없었음)를 `MainScreen.kt`의 기존 "공부앱 열기(브라우저)" 탭 자리에 배치. `EnforcementService`/`SiteEnforcement`의 `checkStudyLock`/`isBlockedByStudyLock`이 Firebase 폴링 대신 로컬 `Repository` 읽기로 전환. `StudyLockScreen`/`Main.kt`의 정지/전환 버튼이 `PomodoroSyncClient.sendRemoteCommand`(비동기 Firebase 왕복) 대신 `Repository.timerStop()`/`timerSwitchPhase()` 로컬 직접 호출로 교체.
- **안드로이드**: `AppPreferences`에 타이머 상태/뽀모도로 설정/허용 사이트 필드 추가. `Entities.kt`에 `StudyLogEntry` Room 엔티티, `Daos.kt`에 DAO 추가, `AppDatabase` version 17→18. `PhoneLockRepository`에 데스크탑과 대칭인 타이머 제어 함수 신설. 신규 `ui/StudyTimerScreen.kt`를 `MainActivity`의 기존 "공부앱"(WebView 임베드) 탭 자리에 배치, `ui/StudyAppScreen.kt`(WebView)+`StudyAppWebViewHolder` 삭제. `AppMonitorAccessibilityService.checkStudyLock`/`checkStudyLockSite`가 로컬 읽기로 전환. `StudyLockActivity`의 정지/전환 버튼이 로컬 직접 호출로 교체되고, 화면 자체도 1초 tick마다 `repository.isStudyLockActive()`를 재확인해 비활성화되면 스스로 `finish()`(잠금화면 안 닫힘 버그 수정). `StudyLockAppsScreen`에 허용 사이트 입력 필드 추가.
- **양 플랫폼 `PomodoroSyncClient.kt`**: `sendRemoteCommand`/`readAllowedDesktopApps`/`readAllowedSites` 제거, 신규 `pushLocalStudyStatus()`로 대체(로컬 상태 변경 시 페이즈 전환 시점에만 `users/{user}/pomodoro`에 write해 크로스디바이스 신호 유지). `isBreakActive`/`currentPhaseEndAt`/`isStudyTimerActive`/`isPomodoroMode`는 남겨뒀지만 이제 "다른 기기" 상태 조회 용도로만 쓰임(`LockEvaluator`의 `pomodoroUnlockEnabled` 체크).
- **부수 수정**: 데스크탑 `JsonStore.save()`에 `pomodoroUnlockEnabled` 저장이 누락돼 있던 기존 버그 발견 후 수정([[BUGS.md]] Fixed 참고).
- **검증**: 양 플랫폼 `robocopy → compileKotlin`/`assembleDebug` 컴파일 성공 확인, 안드로이드 APK 두 위치(AndroidBuilds/OneDrive 원본) 갱신 완료. **실기기 동작 검증은 아직 안 함** — 다음 세션 우선순위.
- [[BUGS.md]] 갱신: "타이머 정지/전환 무반응"(양 플랫폼), "안드로이드 잠금화면 안 닫힘" Fixed로 이동. "허용앱 실행 실패", "Alt-Tab 우회", "허용앱 선택화면 문제"는 이번 변경과 무관한 별개 원인이라 Open 유지(설명 갱신).

## 2026-08-07 (세션 마무리, 23차 세션 종료) — 공부앱 완전 네이티브 재구현 확정, 다음 세션 최우선으로 격상
- 23차 세션에서 데스크탑 정지/전환·허용앱 실행 버튼에 계측(로그+화면 배너)을 추가·배포했지만 사용자가 재확인한 결과 여전히 문제가 해결되지 않음을 보고. 코드 변경 없음 — 사용자가 "공부앱 HTML을 완전히 새 코드로 앱에 직접 구현해야 한다"는 근본적 방침을 확정하고 이를 모든 작업 중 1순위로 지정.
- [[DECISIONS.md]]에 "공부앱을 웹/웹뷰가 아닌 완전 네이티브로 재구현하기로 결정" 항목 신설(기존 [[IDEAS.md]]의 "오늘 할 일 아님" 아이디어에서 확정된 결정으로 격상). [[HANDOFF.md]] 진행률/진행 중인 작업/다음 작업 우선순위/다음 세션 안내를 전부 "재구현이 다음 세션 최우선"으로 갱신. [[IDEAS.md]]의 관련 두 항목(네이티브 재구현, 최소 상태 동기화 재설계)도 정리.

## 2026-08-07 (23차 세션) — 데스크탑 공부 잠금 원격명령/허용앱 실행 계측 추가
- **계측 추가(버그 수정 아님)**: 22차까지 "고쳤다"고 배포했다가 두 차례 틀렸던 것에 대한 대응 — 추측 대신 실제 실패 원인을 볼 수 있게 로그/화면 토스트부터 추가. `PomodoroSyncClient.sendRemoteCommand()`가 `runCatching`으로 결과를 통째로 삼키던 것을 고쳐 idToken 발급 실패/HTTP 상태코드/응답본문/예외를 신설한 `DebugLog`(`%APPDATA%\PhoneLockDesktop\debug.log`)에 남기고 성공 여부를 `Boolean`으로 반환하도록 변경. `Main.kt`의 "허용 프로그램" 실행(`ProcessBuilder`) 실패도 동일하게 로그 추가. 두 실패 모두 `StudyLockScreen`에 빨간 배너로 4초간 노출(`toastMessage`/`onToastShown`). 컴파일 확인(`compileKotlin`) 및 `createDistributable` 배포까지 완료 — 다음 실기기 재현 시 배너/로그로 원인 1차 분류 가능해짐. [[BUGS.md]] "Open" 항목별 계측 내용 갱신.

## 2026-08-07 (세션 마무리, 22차 세션 종료) — 공부 잠금 버그 미해결 상태로 확정, 문서 정리
- 사용자가 20~22차에서 시도한 수정들(포커스 획득, 자기 프로세스 예외 처리, 웹뷰 JS 직접 실행 등)을 실기기에서 재현한 결과 **전부 여전히 재현됨**을 보고: 데스크탑 정지/전환 버튼 무반응, 데스크탑 허용앱 실행 버튼 무반응, 데스크탑 Alt-Tab으로 사이트 차단 우회 가능, 안드로이드 잠금화면이 정지 후 자동으로 안 닫힘, 안드로이드 "허용앱 선택" 화면이 작동 안 하는 것으로 보임. 코드 변경 없음 — [[BUGS.md]] "Open" 섹션에 5건 모두 원인 추정/시도 내역과 함께 기록, [[HANDOFF.md]] 우선순위/진행중 작업/다음 세션 안내를 이 상태에 맞게 갱신.
- 사용자가 근본적 대안 두 가지 제안(둘 다 "오늘 할 일 아님"으로 보류, [[IDEAS.md]] 기록): ① 공부앱을 웹/웹뷰가 아닌 완전 네이티브 재구현, ② 원격 제어를 최소 필요 상태만 동기화하는 방식으로 재설계.

## 2026-08-06 (18차 세션) — 스크롤바 디자인 통일
- **리팩터링**: `.cal-wrap`/`.tab-panel`/`.calc-content`/`.timetable-container`가 각자 따로 `::-webkit-scrollbar` 규칙을 중복 정의하고 있었고, 그중 `.tab-panel`만 폭 4px/반경 2px로 나머지(5px/3px)와 달랐다. 하나의 통합 규칙(셀렉터 그룹핑)으로 합치고 전부 5px/3px로 통일, 신규 스크롤 영역(`.timer-wrap`, `.cal-modal-body`, `.fb-modal` — 원래 스크롤바 스타일이 아예 없어 브라우저 기본 스크롤바가 보이던 곳들)도 같은 규칙에 포함시켰다.

## 2026-08-07 (22차 세션) — 데스크탑 공부 잠금 화면 버튼 전부 먹통이던 원인(포커스) 수정
- **버그 수정**: 공부 잠금 화면(`StudyLockScreen`)의 버튼이 정지뿐 아니라 허용 프로그램 실행까지 전부 눌러도 반응 없던 원인 — `checkStudyLock()`이 잠금을 걸기 직전 대상 창을 `minimizeForegroundWindow()`로 최소화하는데, 그 직후 뜨는 새 창이 자동으로 OS 포커스를 못 받는 경우가 있어(`ConfirmScreen`에 이미 있던 동일한 문제와 원인·해결책 동일) 클릭 자체가 창에 전달되지 않고 있었다. `Main.kt`의 공부 잠금 `Window` 블록에 `ConfirmScreen`과 같은 `LaunchedEffect { window.toFront(); window.requestFocus() }`를 추가해서 해결.
- 데스크탑 빌드/배포 완료 — 배포 중 `PhoneLockDesktopWatchdog` 예약 작업이 프로세스를 즉시 재실행시켜 robocopy가 exe/dll 파일 잠김으로 반복 실패했음(이번에 처음 발견). 배포 절차에 "watchdog 예약 작업을 임시로 비활성화 → 배포 → 재활성화" 단계 추가 필요 — [[HANDOFF.md]]/[[BUGS.md]] 참고.

## 2026-08-07 (21차 세션) — 안드로이드 공부 잠금: 홈 화면도 잠그기 + 정지 버튼 안 먹던 문제 수정
- **동작 변경(사용자 재확인)**: "홈 화면으로 도망가면 공부 잠금이 안 걸리는 게 이상하다, 열품타처럼 앱 화면을 벗어나지 못해야 한다"는 지적을 받고 확인 — 애초 요구사항이 그거였는데 구현 시 `shouldIgnore()`(런처 예외 포함, 그룹 차단용 로직)를 그대로 재사용하면서 의도치 않게 홈 화면이 안전지대가 되어 있었다. `checkStudyLock()`을 `shouldIgnore()`보다 먼저 확인하도록 순서를 바꾸고, 공부 잠금 자체의 예외는 "이 앱 자신"(+시스템 UI/`android`)으로만 좁혀서 런처(홈 화면)도 이제 감지·재차단 대상에 포함시켰다.
- **버그 수정**: 안드로이드 잠금 화면의 "타이머 정지"/"휴식으로 전환" 버튼이 Firebase 원격 명령에만 의존했는데, 공부앱이 앱 내장 웹뷰(`StudyAppScreen`)로 열려있는 경우 잠금 화면(별도 Activity)이 그 위에 뜨면서 웹뷰를 가진 MainActivity가 배경으로 밀려나 JS 실행이 스로틀링돼 반영이 안 되거나 크게 늦어졌다. `StudyAppWebViewHolder`(전역 참조)를 신설해서, 같은 기기에 내장 웹뷰가 열려있으면 `evaluateJavascript()`로 `timerStop()`/`timerSwitchPhase()`를 직접 즉시 호출하고(배경 상태와 무관하게 실행됨), Firebase 원격 명령은 외부 브라우저를 쓰는 경우를 위한 폴백으로 그대로 유지.
- 안드로이드 `assembleDebug` 완료, APK 두 위치 갱신 완료. 실기기 검증 필요.

## 2026-08-07 (20차 세션) — 데스크탑 공부 잠금 화면 깜빡임/버튼 먹통 버그 수정
- **버그 수정**: `EnforcementService.checkStudyLock()`이 "이 앱 자신"을 항상 허용 프로세스로 취급해서, 공부 잠금 전체화면이 뜨는 순간 그 창 자체가 포그라운드가 되면 다음 tick에 "허용됨"으로 오인해 즉시 잠금을 내렸다. 그러면 잠금 화면 밑에 있던(허용 안 된) 창이 다시 포그라운드가 되어 곧바로 재차단 → 잠금 화면이 다시 포그라운드가 되어 또 내려가는 무한 루프가 발생해 화면이 깜빡였고, 그 사이 창이 계속 다시 만들어지면서 "타이머 정지" 버튼 클릭도 씹혔다.
- **원인**: `isAllowed` 판정에 `processName.equals(selfProcessName, ...)`이 섞여 있어서, 자기 자신이 포그라운드일 때 `onStudyLockUpdate(null)`을 호출해버림. 수정: 자기 자신이 포그라운드면 기존 잠금 상태를 그대로 두고 아무 것도 하지 않도록 분리(`return true`로 조기 종료, 상태 변경 없음). 안드로이드는 애초에 `shouldIgnore()`가 자기 패키지명을 `checkStudyLock` 호출 전에 걸러내서 이 버그가 없었음.
- 데스크탑 빌드/배포 완료(로컬 watchdog이 즉시 재시작하는 바람에 robocopy가 exe 잠김으로 몇 차례 재시도했으나 최종 성공, jar 해시 변경으로 확인).

## 2026-08-06 (19차 세션) — 공부 잠금 화면 원격 제어(타이머 정지/휴식 전환)
- **신규 기능**: 데스크탑/안드로이드 공부 잠금 전체화면에 "⏹ 타이머 정지" 버튼(항상 표시), 뽀모도로 모드일 때 "☕ 휴식으로 전환" 버튼 추가. 자세한 설계(관리앱 읽기 전용 원칙의 예외인 이유, 한계)는 [[DECISIONS.md]] "공부 잠금 원격 제어" 참고.
- **공부앱**: `pomodoro` 노드에 `mode`("plain"/"pomodoro") 필드 추가(모든 push 지점에서 갱신). `pomodoro/remoteCommand`(action, ts) 구독 추가 — 관리앱이 여기 쓰면 공부앱이 `timerStop()`/`timerSwitchPhase()`를 그대로 호출해서 실행(기존 게이팅 로직 그대로 적용됨). 처리한 명령의 ts는 localStorage에 기록해 재구독 시 중복 실행 방지.
- **phone-lock-desktop**: `PomodoroSyncClient`에 `isPomodoroMode()`/`sendRemoteCommand()` 추가. `StudyLockStatus`에 `isPomodoroMode` 추가, `StudyLockScreen`에 버튼 UI 추가. `Main.kt`에서 버튼 클릭 시 백그라운드 스레드로 명령 전송.
- **phone-lock-android**: `PomodoroSyncClient`에 동일 기능 추가. `StudyLockActivity`에 `EXTRA_STUDY_LOCK_IS_POMODORO` extra 추가 및 버튼 UI, `lifecycleScope`로 명령 전송.
- 데스크탑 빌드/배포, 안드로이드 `assembleDebug` + APK 두 위치 갱신 완료. 실기기 검증 미완료(원격 명령은 공부앱이 브라우저/웹뷰에 열려 있어야 반영되는 제약 있음 — 사용자에게 사전 설명 후 진행 동의 받음).

## 2026-08-06 (17차 세션) — 타이머 탭 스크롤 버그 수정 + 데스크탑 레이아웃
- **버그 수정**: 타이머 탭이 스크롤되지 않던 원인 — `renderTimer()`가 매번 innerHTML을 새로 쓰는 대상 `#timerContainer`가 `.view`(flex 컨테이너)와 `.timer-wrap`(flex:1+overflow-y:auto) 사이에 낀 "그냥 div"라서, `.timer-wrap`의 flex 속성이 부모가 flex 컨테이너가 아니라 무시되고 있었음(캘린더 뷰는 `.cal-wrap`이 `.view` 바로 아래라 이 문제가 없었음). `#timerContainer`에 `flex:1;min-height:0;display:flex;flex-direction:column`을 줘서 해결.
- **데스크탑 전용 2단 레이아웃(≥900px)**: 좁은 화면용 640px 고정폭 세로 스택을 넓은 화면에도 그대로 쓰던 걸, `@media (min-width:900px)`에서 타이머를 왼쪽 고정 열(sticky)로, 오늘의 기록·허용 프로그램·허용 사이트를 오른쪽 열로 배치하는 레이아웃으로 분리. 타이머 숫자도 44px→64px로 확대. 모바일(≤640px) 레이아웃은 변경 없음. `renderTimer()`의 섹션들을 `.timer-col-main`/`.timer-col-side` 두 래퍼로 그룹화해서 구현.

## 2026-08-06 (16차 세션) — 공부 잠금에 허용 사이트 추가
- **신규 기능**: 공부 잠금에 "허용 프로그램"(플랫폼별)과 별개로 "허용 사이트"(도메인)를 추가 — 데스크탑·안드로이드가 공유하는 값(공부앱 타이머 탭에서 한 번만 설정). 브라우저 자체가 허용 프로그램/앱이라 열려 있어도, 그 안에서 방문하는 사이트가 허용 목록에 없으면 별도로 차단한다.
- **공부앱**: `studyLockConfig` Firebase 노드에 `allowedSites` 필드 추가, `_ts`는 허용 프로그램과 공유(하나의 설정 단위로 취급). 타이머 탭에 "🌐 공부 중 허용 사이트" 섹션 추가(같은 입력/목록 UI 재사용).
- **phone-lock-desktop**: `PomodoroSyncClient.readAllowedSites()` 추가(허용 프로그램과 같은 캐시 재사용, HTTP 호출 안 늘어남). `SiteEnforcement.check()`/`tick()` 맨 앞에서 공부 잠금 사이트 판정(도메인 suffix 매칭, 그룹 판정보다 우선) — 새 `LockReason.STUDY_LOCK` 사유로 `/check`·`/tick` JSON에 실려 브라우저 확장으로 전달됨. `blocked.js`에 `STUDY_LOCK` 메시지 추가.
- **phone-lock-android**: `PomodoroSyncClient.readAllowedSites()` 추가. `AppMonitorAccessibilityService.checkSitesInternal()`에 공부 잠금 사이트 판정 추가(주소 텍스트 substring 매칭, 기존 그룹 사이트 판정과 같은 방식) — 허용 안 된 사이트면 `BlockActivity`를 `LockReason.STUDY_LOCK`으로 띄운다. `LockEvaluator.kt`의 `LockReason` enum에 `STUDY_LOCK` 추가(양 플랫폼), `BlockScreen.kt`/`BlockActivity.kt`에 메시지 분기 추가.
- 데스크탑 빌드/배포, 안드로이드 `assembleDebug` + APK 두 위치 갱신 모두 완료. 실기기 검증은 미완료.

## 2026-08-06 (15차 세션) — 공부 잠금 기능 구현
- **신규 기능**: 공부앱 타이머가 "공부" 페이즈로 진행 중일 때(휴식 중엔 잠그지 않음, 일반 모드는 항상 공부로 취급) 데스크탑/안드로이드에 전체화면 잠금 — 14차 세션 신규 요청 구현. 처음엔 "타이머 실행 중 항상(휴식 포함)"으로 구현했다가, 세션 중 사용자 요청으로 "공부 중일 때만"으로 변경(휴식 중 잠그는 케이스가 dead code가 되어 관련 필드/분기 삭제). 자세한 설계는 [[DECISIONS.md]] "공부 잠금" 참고.
- **UI 수정**: 허용 프로그램 입력/삭제 버튼이 기존 `.calc-btn` 계열(너비 100%, 큰 패딩)을 그대로 써서 지나치게 크고 입력창 배경이 테마와 안 맞는(흰색) 문제가 있어, 전용 클래스(`.lock-app-input`/`.lock-app-add-btn`/`.lock-app-remove-btn`)로 교체 — 입력창은 다른 폼 필드와 같은 다크 배경, 추가 버튼은 44×44 정사각 아이콘 버튼, 삭제 버튼은 32×44 소형 버튼.
- **공부앱** (`index.html`, `공부앱/index.html`): 타이머 표시를 항상 `H:MM:SS`로 변경(기존엔 1시간 미만이면 `MM:SS`만 표시). 타이머 탭에 "공부 중 허용 프로그램" 목록 UI 추가(추가/삭제, localStorage + Firebase `studyLockConfig` 노드 동기화). `pushPomodoroStatus`에 `timerActive` 필드 추가(모든 타이머 시작/정지/페이즈 전환 지점에서 갱신).
- **phone-lock-desktop**: `PomodoroSyncClient`에 `isStudyTimerActive`/`readAllowedDesktopApps` 추가. `EnforcementService.checkStudyLock()`이 매 tick마다 공부 잠금 여부를 확인해 허용 목록(exe 파일명) 외 프로세스를 전체화면으로 잠근다(자기 자신은 항상 예외). 신규 `StudyLockScreen.kt`(위: 타이머, 아래: 허용 프로그램 실행 버튼), `Main.kt`에 `Window` 추가. `BlockScreen`과 동일하게 `Maximized`+`undecorated`+`alwaysOnTop` 창이라 JNA click-through 없이 구현됨.
- **phone-lock-android**: `AppPreferences.studyLockAllowedPackages` 신설(설치 앱 중 선택, `SettingsScreen` → 신규 `StudyLockAppsScreen`에서 관리). `PomodoroSyncClient`에 `isStudyTimerActive` 추가. `AppMonitorAccessibilityService.checkStudyLock()`이 허용 목록 외 앱이 전면에 뜨면 감지해서 신규 `StudyLockActivity`(위: 타이머, 아래: 허용 앱 실행 버튼)로 되돌린다 — 기기 소유자 권한 없이는 진짜 차단이 불가능해 "감지 후 재차단" 방식(베스트 에포트).
- 데스크탑 빌드/배포 완료(`createDistributable` → `PhoneLockDesktopApp`), 안드로이드 `assembleDebug` 완료 및 APK 두 위치(`AndroidBuilds`, OneDrive 원본) 갱신 완료. 실기기 검증은 미완료 — HANDOFF.md "다음 작업 우선순위" 참고.

## 2026-08-05 (14차 세션) — 문서 체계 개편 (코드 변경 없음)
- **리팩터링(문서)**: 단일 `HANDOFF.md`(726줄/130KB, 1~13차 세션 상세 전부 누적)를 역할별 5개 문서로 분리 — `HANDOFF.md`(현재 상태만), `CHANGELOG.md`(이 문서, 세션별 변경 이력), `DECISIONS.md`(설계 결정), `BUGS.md`(버그 이력), `IDEAS.md`(아이디어).
- `CLAUDE.md`를 새 5문서 체계 규칙으로 갱신(기존 단일-HANDOFF 절차 서술 교체).
- 사용자 요청으로 공부앱 프로젝트의 옛 단일-HANDOFF 방식 메모리 2건(`feedback_handoff_workflow`, `feedback_handoff_local_only`) 삭제, 관리앱 전용 5문서 체계 메모리 신설.
- **신규 요청 접수**: 뽀모도로 타이머 작동(공부) 중 핸드폰/데스크탑 제한 기능 — 설계/구현 전, HANDOFF.md "다음 작업 우선순위"에 기록만 해둠.

## 2026-08-05 (13차 세션) — 뽀모도로 자동해제 토글 UI 분리
- **수정**: `pomodoroUnlockEnabled` `ToggleRow`를 "실행 확인" 섹션 밖으로 빼서 "관리 종류" 섹션 뒤에 항상 보이는 신규 `SectionCard("뽀모도로 연동")`으로 이동 (안드로이드/데스크탑 `GroupEditScreen.kt` 공통). 판정 로직(`LockEvaluator.isPomodoroUnlocked`)은 원래부터 `confirmEnabled`와 무관 — 순수 UI 배치 문제였음.
- 데이터/로직 변경 없음.
- 검증: 안드로이드 `assembleDebug`, 데스크탑 `createDistributable` 성공, 양쪽 배포 완료. 실기기 UI 확인 미완.

## 2026-08-05 (12차 세션) — 일일 사용한도 그룹별 Firebase 합산 동기화
- **구현**: 그룹 이름 기준으로 안드로이드↔데스크탑 오늘 사용시간을 Firebase에서 합산 동기화. 경로 `users/{user}/dailyUsage/{date}/{groupKey}/{android|desktop}`.
- 설계: 기기별 자기 키에만 쓰기(lost update 방지) + 읽을 때 상대 기기 값을 더함(합산 방식, confirmSync의 "최신값 승리"와 다름). 쓰기는 기존 30초 스로틀에 얹음, 읽기는 10초 TTL 캐시(`peerUsageCache`).
- 수정 파일: `PomodoroSyncClient.kt`(양쪽) `readDailyUsage`/`writeDailyUsage`, `Repository.kt`/`PhoneLockRepository.kt`의 `getTodayUsageSeconds`/`addUsageSeconds`.
- 검증: 컴파일/빌드/배포 완료. 실기기 합산 동기화 검증 미완.

## 2026-08-05 (11차 세션) — 실행확인 레벨 동기화: 구글 드라이브 → Firebase 전환
- **삭제**: 안드로이드 SAF 파일선택(`ConfirmSyncManager.kt`), 데스크탑 드라이브 자동탐지(`findGoogleDriveRoot` 등) 전면 삭제, 마이그레이션 없음(기존 `confirm_sync.json` 방치).
- **구현**: 기존 뽀모도로 연동용 Firebase 설정(`fbDatabaseUrl`/`fbApiKey`/`fbUser`) 재사용, `users/{user}/confirmSync/{groupKey}` 경로로 레벨 read/write. `PomodoroSyncClient`에 `readConfirmSync`/`writeConfirmSync`/`firebaseSafeKey` 추가(양쪽).
- 빌드 환경 이슈: 이 세션 샌드박스에 `gradlew`/jpackage 포함 JDK 없어서 캐싱된 gradle-8.7 + JBR/Temurin JDK 21로 우회(자세한 절차는 메모리 `project_build_toolchain_missing` 참고).
- 검증: 컴파일/빌드/배포 완료. 실기기 동기화 검증 미완.

## 2026-08-05 (10차 세션) — 뽀모도로 휴식 임시 해제 오버레이
- **구현**: `pomodoroUnlockEnabled`로 임시 해제된 그룹에도 실행확인 오버레이와 같은 톤의 오버레이 표시(안드로이드/데스크탑/브라우저 확장). 레벨 무관 고정 불투명도(안드로이드 alpha 70, 데스크탑 0.72f, 브라우저 확장 0.28), 남은시간은 재확인 쿨다운이 아닌 "휴식 종료 시각"까지.
- `LockEvaluator`에 공개 wrapper `isPomodoroUnlockActive()` 추가(판정 로직 자체는 불변), `PomodoroSyncClient`에 `currentPhaseEndAt()` 신설.
- 검증: 컴파일/빌드/배포 완료. 실기기 검증 미완.

## 2026-08-04 (9차 세션) — 잠김 화면 통일 / 그룹 on-off 분리 / 관리 종류별 요일 설정
- **구현**: 잠김 화면(스케줄/일일한도)을 실행확인 화면과 같은 톤으로 통일, 전자 버튼은 무반응 장식용.
- **버그 수정**: 그룹 전체 on/off와 스케줄 on/off가 `scheduleEnabled` 한 필드에 묶여 있던 설계 결함 발견 → `groupEnabled`(그룹 전체) 신설로 분리, `scheduleEnabled`는 스케줄 관리 종류 전용으로 축소. 안드로이드가 데스크탑과 달리 `group.enabled`도 체크하던 불일치도 통일(`enabled`는 순수 통계 필터로 확정).
- **구현**: `dailyLimitDaysMask`/`confirmDaysMask` 신설, 일일한도/실행확인에 독립 요일 설정. `detectWeakeningEdit`에 각 마스크 우회 방지 항목 추가.
- Room `AppDatabase` version 16→17.
- 검증: 컴파일/빌드/배포 완료. 실기기 검증 미완.

## 2026-08-04 (8차 세션) — 공부앱 탭 위치 / WebView 높이 수정
- **수정**: 안드로이드 하단 탭에서 "📚 공부앱"을 설정보다 왼쪽으로 이동, 데스크탑도 동일하게 탭 순서 변경.
- **버그 수정 시도**: 안드로이드 공부앱 WebView 하단이 비어 보이는 문제 — `layoutParams(MATCH_PARENT)` 명시 + `useWideViewPort`/`loadWithOverviewMode` 추가(실기기 재현/검증 못함, 근본원인 100% 특정 아님).
- 검증: 컴파일/빌드/배포 완료. 실기기 검증 미완.

## 2026-08-04 (7차 세션) — 공부앱 뽀모도로 휴식 연동
- **구현**: 완전히 별개 웹앱인 공부앱의 Firebase RTDB를 읽기 전용 폴링해서, 그룹 편집 화면에서 지정한 특정 그룹만 뽀모도로 휴식 시간 동안 자동 임시 해제.
- 신규 `PomodoroSyncClient.kt`(양쪽 독립 구현): 익명 인증+토큰 캐싱, `breakActive && now < phaseEndAt + GRACE_MS(15초)` 재검증(공부앱이 꺼져도 자동 무효화), 네트워크 오류 시 fail-safe(false, 해제 안 함), 5초 TTL 캐시.
- `Group.pomodoroUnlockEnabled` 필드, 전역 Firebase 설정 3종(`fbDatabaseUrl`/`fbApiKey`/`fbUser`) 추가. `LockEvaluator` 최상단에 `isPomodoroUnlocked()` 단락 추가(영구 상태에 기록 안 함, `detectWeakeningEdit`와 무관).
- 안드로이드 `INTERNET` 퍼미션 신규 추가, Room version 15→16.
- "📚 공부앱" 탭 신설(안드로이드는 앱 내 WebView, 데스크탑은 외부 브라우저 — Compose Desktop에 내장 웹뷰 없어 기술적 여건 차이로 결정).
- 검증: 컴파일/빌드/배포 완료. 실사용 검증 미완.

## 2026-07-30 (6차 세션) — "관리 종류" 재구성 + "적용 시간대" 추가
- **삭제**: 시간대+일일한도 AND 융합 옵션(`requireAllConditions`) 삭제 — 항상 OR.
- **구현**: "관리 종류"(스케줄/일일 사용한도 설정/실행 확인) 3분류 신설, 일일한도·실행확인 각각에 "적용 시간대" 옵션 추가. 스케줄도 토글에 포함시키되 `detectWeakeningEdit`에 "요일 제한 중 스케줄 바로 끄기" 방지 항목 추가.
- 후속 수정: 스케줄도 다른 두 항목처럼 토글 켜야 상세 카드 노출 + 새 그룹 기본 off로 변경.
- **버그 수정**: robocopy가 실행 중 프로세스 파일을 조용히 스킵해 1차 배포가 반영 안 됐던 사고 발견 → 프로세스 종료 확인 후 동기 robocopy + jar 해시 비교 절차 확립(이후 세션 표준 절차화).
- Room version 14→15.

## 2026-07-30 (5차 세션) — 데스크탑 그룹 끄기 작업 중 삽질 2건
- **버그 발견**: `Group.enabled`는 통계 탭 필터 전용이지 실제 on/off 스위치가 아님(진짜는 `scheduleEnabled`) — 데이터 직접 편집 시 혼동해서 실수.
- **버그 발견/수정**: PowerShell `Set-Content -Encoding UTF8`이 BOM을 붙여 `org.json` 파서가 파싱 실패, `JsonStore.load()` 손상파일 방어 로직이 조용히 빈 상태로 시작 → BOM 없는 `UTF8Encoding($false)`로 재작성해 해결.
- 코드 변경 없음(데이터 파일 편집 + 재시작만).

## 2026-07-30 (4차 세션) — 실행 확인 오버레이 타이머 버벅거림 수정
- **버그 수정**: 확인 대기 카운트다운(`InterstitialScreen`/`WatchAndWaitScreen`) 틱 루프를 `System.nanoTime()` 기반 wall-clock 계산으로 교체(디스패처 지연 보정).
- **버그 수정(진짜 원인)**: "남은 유예시간" 오버레이가 2초 주기 서버 폴링값으로 로컬 1초 타이머를 매번 덮어써 버벅거림 → 로컬/서버 값 차이 1초 이하면 무시하도록 수정(`AppMonitorAccessibilityService`, `UsageOverlayContent.kt`).
- 사용자가 실기기+데스크탑에서 버벅거림 해결 확인 완료.

## 2026-07-30 (3차 세션) — 오버레이 불투명도 불안정 상승 문제
- 1차 시도(원인 아니었으나 유효): 안드로이드 `escalationCache`에 동기화 없던 레이스 컨디션 발견, `Mutex`로 수정(유지).
- 2~3차(잘못된 접근, 이후 원복): `ConfirmationGate`에 연속성 추적(`touch`/`hasLeftSinceConfirm`/`renewSilently`) 추가해 재확인 자체를 스킵하도록 했으나, 사용자가 "재확인이 없어져야 한다고는 안 했다"고 강하게 정정 → **완전히 원복**.
- **진짜 수정**: 오버레이가 화면에 "보여주는" 값에만 캡을 씌움 — 같은 쿨다운 사이클 안에서는 표시 알파/레벨이 위로 못 튀고, 진짜 새 재확인 시에만 캡 해제. 안드로이드는 인라인(`lastDisplayedOverlayAlpha`), 데스크탑은 신규 `OverlayLevelRatchet.kt` 공용 객체. 재확인/escalation 판정 로직 자체는 전혀 안 건드림.
- **교훈**: "표시가 이랬으면" 요청을 판정/보안 로직 변경으로 확대 해석하지 말 것 ([[feedback_ui_request_scope]] 메모리화됨).

## 2026-07-29 (2차 세션)
- **구현**: 데스크탑 네이티브 프로그램 감시에 "확인 후 오버레이 표시" 이식(안드로이드에만 있던 기능 격차 해소). 신규 `UsageOverlayContent.kt`, `Group.usageOverlayEnabled` 필드.
- **버그 수정**: 브라우저 확장 오버레이가 CORS로 차단됨(content script fetch가 페이지 출처로 나가 직전 세션 CORS 강화에 걸림) → `/overlay-status`만 `openCors=true`로 개방.

## 이전 세션 (요약, git 이력 없음 — 이 문서가 유일한 기록)
- 시니어 코드 리뷰 기반 대규모 개선: 토큰 인증, CORS, 뮤텍스 데드락 방지, 원자적 쓰기(JsonStore `.tmp`+`ATOMIC_MOVE`), 스로틀링/캐싱, heartbeat/watchdog 안전장치.
- 플랫폼 간 기능 격차 조사: 안드로이드에만 있던 기능 2건(오버레이, 릴스/쇼츠 감지) 발견.
- 안드로이드 APK 이중 위치 갱신 누락 버그 발견·해결.
