# BUGS

현재 알려진 버그 관리. 해결되면 Fixed로 유지하거나 제거.

---

## Fixed (2026-09-16, 124차) — 당겨서 새로고침 표시가 손을 뗀 뒤에도 사라지지 않고 남음(106차 재발)

- **설명**: 당겨서 새로고침을 하면 새로고침 표시(화살표 원)가 새로고침 위치에 멈춘 채 사라지지 않는다. 106차에 "라이브러리 버그"로 보고 material3 1.3.0으로 올려 고쳤다고 기록했지만, 실제로는 다른 원인이 남아 있었다.
- **원인**: material3 1.3.0 `PullToRefreshBox`는 임계값을 넘겨 손을 떼면 표시를 새로고침 위치에 세워두고, `isRefreshing`이 true→false로 바뀌는 것을 **재구성에서 관찰해야** 숨긴다(라이브러리 소스 `PullToRefreshModifierNode.onRelease`/`update`로 확인). 오프라인이거나 동기화 함수가 일시 중단 없이 바로 끝나면 우리 래퍼(`PullToRefreshBox.kt`)가 한 프레임 안에 true→false로 되돌려 변화가 관찰되지 않았다. 온라인에서는 네트워크 대기 동안 프레임이 지나가서 대개 정상이라 재현이 들쑥날쑥했다.
- **상태**: Fixed
- **해결**: 래퍼가 새로고침 표시를 최소 500ms 유지(`MIN_REFRESH_INDICATOR_MS`). 모든 탭이 같은 래퍼를 써서 한 곳 수정으로 전부 적용.
- **검증**: 같은 코드를 쓰는 출시용 앱을 Android 16 에뮬레이터에서 재현(수정 전 4초+ 잔존) → 수정 후 잔존 없음 확인. 개인용 릴리스 `android-1789554677` 배포. **실기기 확인은 아직.**

---

## Fixed (2026-09-16, 123차) — 브라우저 확장 오버레이가 그룹의 "사용 중 남은 시간 표시" 끔 설정을 무시함

- **설명**: 그룹 편집 화면에서 "사용 중 남은 시간 표시"(`usageOverlayEnabled`)를 꺼도 데스크탑 코너 위젯은 안 뜨지만 브라우저 확장(`overlay.js`) 오버레이는 계속 떴다.
- **원인**: 브라우저 확장이 호출하는 `/overlay-status` API의 `SiteEnforcement.overlayStatus(hostname)`(데스크탑)이 `usageOverlayEnabled` 필드를 전혀 확인하지 않았다 — 같은 판정을 하는 데스크탑 자체 위젯용 `EnforcementService.overlayStatusFor`는 이미 이 필드를 확인하고 있어 새는 경로만 브라우저 확장이었다. 안드로이드는 처음부터 정상.
- **상태**: Fixed
- **해결**: `SiteEnforcement.kt`의 `overlayStatus()` — 실행확인 후보/뽀모도로 임시해제 조건 둘 다에 `it.usageOverlayEnabled` 추가.
- **검증**: 컴파일/빌드/배포(호스트 2곳 jar 해시 일치)/GitHub 릴리스(`desktop-1789549507`)까지 완료. **브라우저 확장 실사용(설정 끄고 실제로 사이트에서 오버레이 안 뜨는지) 미검증.**

---

## Fixed (2026-09-16, 122차) — 모임 탭에서 레벨이 사라지고 칭호 버튼형 배지가 평문으로 바뀜(121차 수정의 요구 오해)

- **설명**: 121차 이후 멤버 목록/상세/대화/DM에서 `Lv.N`이 빠지고 칭호가 닉네임 앞 평문("새싹 홍길동")으로 바뀌었다. 사용자가 원한 건 버튼형 배지는 그대로 두고 배지·닉네임의 영역 분리만 없애는 것이었다.
- **원인**: "영역 분리로 닉네임이 여러 줄로 깨진다"는 문제를 배지 스타일 자체의 문제로 해석했다.
- **상태**: Fixed
- **해결**: `MemberDisplayName`이 배지를 `InlineTextContent`로 닉네임과 같은 `Text` 안에 넣는다(레벨 전달도 4곳 × 양 플랫폼 복원). 폰 폭(360dp) 렌더링으로 긴 닉네임/고레벨 긴 칭호/공유 안 한 사람/채팅 1줄 라벨 확인.

## Fixed (2026-09-16, 122차) — 태블릿 홈에서 레벨/경험치 HUD가 화분과 꾸미기 소품을 가림

- **설명**: 하단 전폭 HUD(최대 420dp)가 화면 비율상 화분(높이 62% 지점)과 땅 위 소품을 덮었다.
- **원인**: 씬은 화면 전체 높이 기준으로 화분 위치를 계산하는데 HUD가 그 위에 겹쳐 떠 있었고, 태블릿 세로에서는 HUD 높이 비율이 화분 위치보다 컸다.
- **상태**: Fixed
- **해결**: 폭 기준 3단 반응형 레이아웃(840dp+ 사이드 패널 / 600dp+ 위아래 분리 / 폰 오버레이 + `GroundScene(contentBottomInset)`). 800×1280, 1280×800, 960×600, 390×844 렌더링으로 화분·소품이 전부 보이는 것 확인.

## Fixed (2026-09-16, 121차) — Wi-Fi 일시 단절만으로 승인된 사용자가 가입 신청 화면으로 떨어짐(사실상 강제 로그아웃)

- **설명**: 인터넷이 잠깐 끊기거나 Firebase 요청이 비-2xx로 실패하면, 이미 승인된 사용자가 "가입 신청" 화면(`ID_SETUP`)으로 전환되고 로컬 승인 캐시(`cachedApprovalStatus`)까지 지워졌다. 실제 로그인 세션(`AuthManager`)은 멀쩡한데 화면만 로그아웃된 것처럼 보였다. 양 플랫폼 공통.
- **원인**: `AccountSyncClient.getRaw`(안드로이드) / `get`(데스크탑)이 네트워크 예외와 비-2xx 응답을 전부 조용히 `null`로 반환 → `fetchMyProfile`이 `Result.success(null)`("프로필 아직 없음")을 돌려줌 → 호출부가 `onSuccess` 가지의 `else -> ID_SETUP`을 탐. 106차에 같은 증상을 고쳤지만 그때 손댄 건 `onFailure` 경로뿐이라, 정작 대부분의 네트워크 장애가 통과하는 `onSuccess(null)` 경로는 그대로였다.
- **상태**: Fixed
- **해결**: 두 GET 헬퍼가 통신 실패/비-2xx에서 예외를 던지도록 수정(이제 `null`은 "서버가 2xx로 빈 값을 줬다"만 의미). 더해서 안드로이드 `AccountGate`는 오프라인이면 재확인 시도 자체를 건너뛰고, `NetworkMonitor.isOnline`이 복구되는 순간 자동으로 다시 확인하며, 실패 시에는 인증 세션을 건드리지 않고 캐시된 승인을 낙관적 표시로 유지한다. 자세한 것은 [[DECISIONS.md]] 121차 참고.

## Fixed (2026-09-16, 121차) — 커스텀 테마인데 홈스크린 위젯과 접근성 오버레이만 기본 초록색

- **설명**: 설정에서 커스텀 테마를 골라도 홈스크린 루틴 위젯(배경/날짜 글자/체크 아이콘)과 "사용 중 남은 시간" 오버레이(배경/타이머 숫자)는 라이트+그린 기본 팔레트로 남았다. 어두운 배경 커스텀 테마에서는 화면 전환 순간 창 바탕의 흰색도 그대로 비쳤다.
- **원인**: `paletteFor(themeMode: String)`의 `when`이 `else -> LightGreenPalette`로 `ThemeMode.CUSTOM`까지 삼킴. Compose 화면은 `PhoneLockTheme`이 커스텀 두 색을 따로 받아 처리했지만, Compose 밖에서 이 함수를 직접 부르던 위젯/오버레이는 커스텀 선택을 아예 못 봤다. 창 배경은 별개 원인 — `Theme.PhoneLock`이 `android:Theme.Material.Light`를 상속해 항상 흰색.
- **상태**: Fixed
- **해결**: 커스텀까지 해석하는 `paletteFor(themeMode, bg, accent)` 오버로드와 `AppPreferences.currentPalette()`를 유일한 창구로 만들어 모든 호출부를 교체. 위젯은 커스텀일 때만 배경 틴트(API 31+)/단색(그 아래)과 런타임 비트맵 체크 아이콘을 쓴다. `Activity.applyThemeWindowBackground()`로 창 배경도 테마 색으로 덮는다.

## Fixed (2026-09-16, 121차) — 홈(식물) 탭의 EXP/레벨/장식이 실제 저장값과 어긋남

- **설명**: 다른 탭이나 백그라운드에서 EXP가 쌓여도 홈 탭 표시가 따라오지 않았고(포인트 잔액만 Room Flow라 즉시 반영), 앱을 재설치하거나 다른 기기에서 쓰면 "포인트는 살아 돌아왔는데 나무만 Lv.1"이 됐다.
- **원인**: ① 성장값은 Room이 아니라 `AppPreferences`/`AppData` 스칼라라 `refreshTick`이 바뀔 때만 다시 읽혔다(화면이 떠 있는 동안 자동 갱신 경로 없음). ② 성장값 자체가 Firebase 동기화 대상이 아니었는데, 그 값을 만들어내는 포인트 원장은 동기화되고 있었다.
- **상태**: Fixed
- **해결**: 성장 스칼라 전용 `growthTick`을 2초 주기로 자동 증가시켜 화면이 저장값을 따라가게 하고, `users/{uid}/growth` 전체 문서 단위 LWW 동기화를 신설(앱 시작/홈 탭 진입 시 풀, 값 변경 시 푸시). 덮어쓰기 완화책은 [[DECISIONS.md]] 121차 참고.

## Fixed (2026-09-16, 121차) — 모임 멤버 목록에서 칭호 배지와 닉네임이 서로 뭉개짐(안드로이드)

- **122차 메모**: 아래 해결 방식(배지 제거)은 요구 오해였고, 122차에 배지를 유지한 채 인라인 요소로 합치는 방식으로 다시 고쳤다(위 122차 항목 참고).

- **설명**: 이름 왼쪽의 "Lv.N 칭호" 알약 배지와 닉네임이 같은 `Row` 안에서 서로 밀어내며, 둘 다 길면 글자가 겹치거나 잘려 읽을 수 없었다. 안드로이드 폰 폭에서 특히 심했다.
- **원인**: 칭호(`Surface` 배지)와 닉네임(`Text`)이 각각 독립된 레이아웃 박스라, 폭이 모자랄 때 Compose가 텍스트 단위가 아니라 박스 단위로 나눠 가져 어느 쪽도 제대로 줄여지지 않았다.
- **상태**: Fixed
- **해결**: 둘을 하나의 `Text`(AnnotatedString)로 합치는 `MemberDisplayName`으로 교체 — "새싹 홍길동"처럼 한 덩어리로 그려지고 `maxLines`+`Ellipsis`가 통째로 적용된다.

---

## Fixed (2026-09-12, 111차 세션) — "식물"(홈) 탭 배경이 데스크탑/태블릿 NavigationRail을 가림

- **설명**: 식물 탭 배경(`GroundScene`)이 지나치게 확장되어 데스크탑/태블릿에서 왼쪽 탭 바를 가리거나 접근하기 어려워짐. 사용자 확인: "탭 내의 배경화면이 원래 벗어나면 안 될 범위를 벗어나서 탭을 가림".
- **원인**: 양 플랫폼 `PlantScreen.kt`의 `GroundScene`이 `Canvas(modifier)`에 `clipToBounds()`를 걸지 않아, tier2+ 장식/흔들림 효과가 레이아웃상 할당된 영역 밖으로 그려질 수 있었음(Compose는 그리기 연산을 부모 경계에 자동 클리핑하지 않음).
- **상태**: Fixed. `Canvas(modifier.clipToBounds())`로 수정.
- **해결 방법**: [[CHANGELOG.md]]/[[DECISIONS.md]] 111차 참고.

---

## Open (2026-09-12, 111차 세션 이월) — DM 미리보기 라벨이 상대의 아이디 변경을 반영하지 못함

- **설명**: 111차에 "아이디 변경" 기능을 추가했는데, `ChatSyncClient`의 DM 목록이 상대의 customId를 `peerLabel`로 스냅샷 캐싱(`dmChatIds/{chatId}/peerLabel`)하고 있어서, 상대가 아이디를 바꿔도 내 DM 목록에는 옛 아이디가 그대로 남는다(메시지 내용 자체는 매번 최신 닉네임을 다시 조회해 쓰므로 영향 없음 — 목록 라벨만 스테일).
- **원인**: DM 생성 시점(`ensureDmChat()`)에 상대 customId를 문자열로 그대로 박아 저장하는 기존 구조 — 아이디 변경 기능이 새로 생기기 전까지는 문제될 일이 없었음.
- **상태**: Open. 다음 세션에서 DM 목록 렌더링 시 `users/{peerUid}/profile.customId`를 라이브로 재조회(또는 닉네임처럼 아예 실시간 조회로 전환)하는 수정이 필요.
- **해결 방법**: 미정 — [[DECISIONS.md]] 111차 참고.

---

## Fixed (2026-09-11, 107차 세션) — 관리자 패널에서 회원가입 사용자까지 전부 "게스트"로 표시됨

- **설명**: 관리자 패널(승인 대기/승인된 사용자 목록)에서 관리자 본인을 제외한 거의 모든 사용자가 실제 로그인 방식과 무관하게 "(게스트)"로 표시됨.
- **원인**: 안드로이드 `AccountGateScreen.kt`의 가입 신청 제출 코드(`onSubmit`)가 `AccountSyncClient.submitProfile(...)` 호출 시 위에서 이미 올바르게 계산해둔 지역변수 `isGuest`(`AuthManager.currentUser?.isAnonymous == true`)를 쓰지 않고 `isGuest = true`를 하드코딩해서 넘기고 있었음 — 회원가입/정상 로그인 사용자도 전부 게스트로 서버에 기록됨. 데스크탑 `AccountGateScreen.kt`는 처음부터 지역변수를 올바르게 넘기고 있어 이 버그 없음.
- **상태**: Fixed. 신규 가입 신청부터는 정상 기록됨. 기존에 잘못 저장된 사용자는 `AccountSyncClient.fixGuestFlagIfNeeded()`(신규)가 다음 로그인 시 자동 교정 — 관리자 개입 불필요.
- **해결 방법**: [[CHANGELOG.md]] 107차 참고.

---

## Fixed (2026-09-11, 107차 세션) — 불안정한 인터넷 연결에서 정상 로그인 사용자가 로그아웃된 것처럼 보임(데스크탑)

- **설명**: 인터넷 연결이 불안정한 상태에서 서버 동기화가 필요한 기능을 쓰면, 로그아웃한 적 없는 사용자가 갑자기 가입 신청 화면으로 떨어져 로그아웃된 것처럼 보임.
- **원인**: 실제로 `AuthManager.signOut()`이 자동 호출되는 경로는 어디에도 없었음(전부 사용자가 직접 로그아웃 버튼을 누른 경우만). 진짜 원인은 데스크탑 `ui/AccountGateScreen.kt`의 `refreshProfile()` — 프로필 조회가 네트워크 오류로 실패해도 `serverChecked = true`를 무조건 세팅해서, 이미 승인된 사용자가 7초 폴링 중 단 한 번만 실패해도 "낙관적으로 계속 보여주기" 조건을 벗어나고 `serverStatus`는 여전히 null이라 곧장 가입 신청 화면(`UsernameStep`)으로 떨어짐. 실제 Firebase 세션/로컬 `google_auth.json`은 전혀 안 건드려졌음 — 화면 상태만의 문제. 안드로이드의 동일 지점은 이미 올바르게 구현돼 있었음(참고용으로 그 패턴을 그대로 이식).
- **상태**: Fixed(코드 추적으로 원인 확정 및 수정 완료). 실제 네트워크 단절 환경에서의 재현 검증은 미완료 — 이번 세션 빌드 환경에 네트워크 단절을 재현할 장치가 없었음. 다음 세션에서 실제로 인터넷을 껐다 켰다 하며 재현 확인 필요.
- **해결 방법**: [[CHANGELOG.md]] 107차 참고.

---

## Fixed (2026-09-11, 105차 세션) — 안드로이드 릴리스가 빌드 경로 오타로 구버전 그대로 배포됨

- **경위**: 105차에서 "레벨업+식물 성장" 통합 시스템을 구현한 뒤 GitHub에 `android-1789051210` 릴리스를 게시했는데, 사용자가 앱 자체 업데이트로 설치해보니 여전히 구버전(식물 탭 없음, 소셜 탭만)이었다고 제보.
- **원인**: 소스 동기화 robocopy 명령에서 안드로이드 공식 빌드 경로를 `C:\Users\sunae\AndroidBuilds\phone-lock-android`가 아니라 `C:\AndroidBuilds\phone-lock-android`(`\Users\sunae\` 빠짐)로 잘못 입력 — 이 둘은 심볼릭 링크가 아니라 **완전히 다른 실제 디렉터리**(`fsutil reparsepoint query`로 심볼릭 링크가 아님을 확인)다. robocopy 자체는 그 잘못된 경로로 "정상 복사됨"을 보고했지만, 그 직후 `gradle assembleRelease`는 **올바른(캐노니컬) 경로**에서 실행했기 때문에, 동기화가 전혀 안 된 옛 소스 그대로 빌드되어 버렸다. 컴파일 확인(`compileDebugKotlin`)은 별도의 `C:\build\phonelock-android-check` 스크래치 경로를 썼기 때문에 거기서는 정상적으로 새 코드가 반영돼 통과했고, 그래서 "컴파일 성공"만 보고 실제 릴리스 빌드도 맞게 됐을 거라고 착각했다.
- **발견/확정 방법**: 빌드된 APK(`app-release.apk`)를 `unzip`으로 풀어 `classes.dex`를 `grep -a`로 열어, 새로 추가한 문자열 리터럴(`growth_exp_total`, `jinseok_tralalero` 등 SharedPreferences 키/`illustrationId` 값)이 실제로 포함돼 있는지 확인 — 전혀 없었다(반면 기존에 있던 `study-planner`, `permRoutine` 같은 문자열은 정상적으로 검출돼, grep 방법 자체는 유효함을 먼저 검증). 데스크탑은 이런 이름 충돌 경로가 없어(`C:\build\phone-lock-desktop` 하나만 사용) 영향 없음.
- **해결**: 올바른 경로로 재동기화(재확인: `Select-String`으로 `growthExpTotal` 필드가 실제로 들어있는지 확인) → 재빌드 → APK의 `classes.dex`에서 새 문자열이 실제로 존재하는지 먼저 검증 → 3위치 재배포 → 기존 잘못된 릴리스(`android-1789051210`)는 `gh release delete`로 제거하고 올바른 빌드로 새 릴리스(`android-1789082482`) 게시.
- **재발 방지**: [[HANDOFF.md]] "현재 주의사항"에 경로 혼동 경고 추가. 앞으로 릴리스용 빌드를 만들 때는 robocopy의 "복사됨" 로그를 믿지 말고, **빌드 산출물(APK/jar)에서 이번에 추가한 문자열이 실제로 들어있는지 직접 확인하는 단계를 항상 거칠 것** — 이번처럼 "컴파일은 통과했으니 빌드도 맞을 것"이라는 추론이 틀릴 수 있다(컴파일 확인과 릴리스 빌드가 서로 다른 소스 디렉터리를 쓰는 구조이기 때문).

---

## Fixed (2026-09-10, 99차 세션)

### 소셜 채팅을 쳐서 보내도 메시지가 안 올라감 (96차 최초 제보 → 98차 원인 미확정 → 99차 해결)
- **경위**: 96차 "엔터 전송" 수정 후에도 재발, 98차엔 정적 코드/Firebase 규칙 검토로 원인을 못 찾고 실패 사유가 화면에 뜨도록 계측만 추가한 채로 세션 종료.
- **원인(99차 확정)**: Firebase Realtime Database가 `orderBy=%22sentAtMillis%22` 조회에 `.indexOn` 색인이 없다는 이유로 **읽기 요청 자체를 HTTP 400으로 거부**하고 있었다. 메시지 전송(쓰기)은 색인과 무관해 항상 성공했고(그래서 입력창은 비워짐), 목록 조회(읽기)만 이 색인 누락으로 매번 실패했는데, 실패가 조용히 빈 목록으로 덮어써지고 있어 "쓰기는 되는데 안 보인다"는 증상만 남아있었다. 98차에 추가한 계측(`sendMessage`의 `Result.failure` 표시)은 애초에 쓰기가 아니라 읽기가 실패하는 케이스라 아무 도움이 안 됐다.
- **발견 경위**: 98차에 추가한 전송 실패 계측으로는 아무 에러가 안 떴다는 사용자 재현("입력창은 비워지는데 채팅 목록에는 안 뜨음")을 받고, 쓰기가 성공하는데 읽기만 실패하는 시나리오로 좁힌 뒤 읽기 쪽에도 동일한 방식의 실패 사유 표시 계측을 추가해 재현을 요청 — 사용자가 실제로 뜬 에러 메시지(`400: Index not defined, add ".indexOn": "sentAtMillis", for path "/groupChats/.../messages"`)를 그대로 전달해줘서 확정됨.
- **해결**: `phone-lock-android/firebase-database.rules.json`의 `groupChats/{groupId}/messages`와 `dmChats/{chatId}/messages`에 `.indexOn: ["sentAtMillis"]` 추가. 사용자가 Firebase 콘솔에 재게시.
- **검증**: 사용자가 실사용으로 메시지가 정상적으로 목록에 뜨는 것 확인 완료(**해결 확정**).
- **교훈**: Firebase RTDB REST API는 데이터 크기와 무관하게 `.indexOn` 색인이 없는 필드로 `orderBy` 쿼리를 걸면 즉시 400을 반환한다 — SDK 사용 시엔 "경고만 뜨고 동작은 한다"는 통념이 있어 이번에도 처음엔 배제했었는데, REST 직접 호출 경로에선 그렇지 않았다. 앞으로 `orderBy`를 쓰는 새 쿼리를 추가할 땐 `.indexOn`을 항상 같이 추가할 것.

---

## Fixed (2026-09-10, 98차 세션)

### 다회독 완료 후 다음 회독 생성 시 계산기 연동이 끊김
- **경위**: 사용자 제보 — "N회독 완료 후 다음 회독 일정이 만들어질 때 업무 연결이 끊긴다".
- **원인**: `applyCalendarAutoSchedule()`(양 플랫폼)이 다음 회독 `CalendarTask`를 새로 만들 때 원본 task의 `linkedCalc`/`progressStep`을 이어받지 않고 기본값(null)으로 만들었음.
- **해결**: 새 `CalendarTask` 생성 시 `linkedCalc = task.linkedCalc, progressStep = task.progressStep` 추가.
- **검증**: 컴파일 확인, 실사용 미검증(다회독 연동 업무를 실제로 여러 회독 완료시켜 연동이 계속 유지되는지 확인 필요).

### 앱 전반 "상태 변화가 바로 안 보이고 다른 화면 갔다 와야 반영됨" 버그 3건 (계산기/소셜/차단규칙편집)
- **경위**: 사용자가 계산기 "저장됨 불러오기" 사례를 지목하며 "이런 현상을 가진 부분이 정말 많다"고 전체 감사 요청 — 안드로이드/데스크탑 `ui/` 전체를 Explore 에이전트로 훑어 같은 패턴 3건 발견.
- **① 계산기**: "저장됨" 탭에서 불러오기(`loadCalcSavedItemAsDraft`)해도 "입력" 탭의 draft 목록(`tasks`)이 안 갱신 — 형제 탭의 `onChanged` 콜백이 자기 탭 상태만 새로고침하고 상위 화면의 `onChanged()`는 호출 안 함.
- **② 소셜 공유 설정**: `SocialGroupMembersScreen.kt`의 공유 설정 다이얼로그가 통계만 서버에 올리고 화면의 멤버별 통계(`rows`/`stats`)는 재조회 안 함.
- **③ 차단 규칙 편집**(더 심각): `GroupEditScreen.kt`가 화면 진입 시점 스냅샷(`originalGroup`)을 저장 시 그대로 써서, 편집하는 동안 왼쪽 목록에서 켜짐/스누즈/차단시도 등이 바뀌어도 "저장"을 누르면 그 변경을 통째로 되돌려버림.
- **해결**: ①②는 좁은 콜백이 상위/전체 refresh 함수도 함께 호출하도록 수정. ③은 저장 직전 `repository.getGroup(id)`로 최신 상태를 다시 읽어 그 값(`groupEnabled`/`groupOffPending`/`groupOffMessageIndex`/`snoozed*`)을 쓰도록 수정.
- **③ 조사 중 발견한 별개 버그**: `blockAttemptDate`/`blockAttemptCount`(잠김 화면 조롱 문구 강도 계산용) 필드가 이 편집 폼 자체에 아예 없어서, 이름만 바꾸는 사소한 편집을 해도 저장할 때마다 이 카운터가 0으로 리셋되고 있었음 — 같이 수정.
- **검증**: 전부 컴파일 확인, 실사용 미검증.

### 데스크탑 `generateBuildInfo` 태스크가 같은 build/ 디렉터리에서 재빌드해도 며칠 전 타임스탬프를 그대로 씀
- **경위**: 98차 세션에 데스크탑 릴리스를 두 번 빌드했는데, 첫 번째 빌드에서 생성된 `BuildInfo.BUILD_TIMESTAMP`가 97차 세션 값(`1788946046`)과 정확히 일치하는 걸 발견 — 실제로는 재실행이 안 되고 있었음.
- **원인**: `build.gradle.kts`의 `generateBuildInfo` 태스크가 `outputs.dir(...)`만 선언하고 `inputs`가 전혀 없어서, Gradle이 "이전에 이미 이 출력으로 실행한 적 있음"으로 판단해 UP-TO-DATE로 건너뜀 — 같은 `C:\build\phone-lock-desktop` 디렉터리에서 세션이 바뀌어도 계속 재사용.
- **영향**: 자체 업데이트 기능이 이 타임스탬프로 버전을 비교하므로, 실제로는 새 코드가 들어간 빌드인데도 GitHub 릴리스 태그가 예전 버전과 같아져 자체 업데이트가 오작동할 위험(예: "이미 최신 버전"으로 잘못 판정).
- **해결**: `outputs.upToDateWhen { false }` 추가 — 항상 재실행해서 매 빌드마다 새 타임스탬프를 굽도록 강제.
- **검증**: 수정 후 재빌드해서 새 타임스탬프(`1789000490`)가 실제로 다르게 나오는 것 확인 완료.

### (원인 미확정, 96차부터 이월) 소셜 채팅 전송이 안 됨
- 아래 "Open" 섹션 참고.

---

## Fixed (2026-09-09, 97차 세션)

### 데스크탑 배포가 실제로 반영 안 됨 — 도움말 페이징 개편 배포 직후 사용자가 "잘 안 보인다"고 제보
- **경위**: 97차 2차 개편(도움말 페이징) 배포 직후 사용자가 데스크탑에서 확인했는데 이상하다고 제보. 화면을 스크린샷으로 직접 확인해보니 페이징 UI가 아니라 개편 **이전**(1차 개편, 한 화면 스크롤 나열) 화면이 떠 있었다.
- **원인**: 2차 개편 배포 단계의 `robocopy`+`Start-Process`가 PowerShell 도구 120초 타임아웃에 걸려 백그라운드로 넘어갔는데, 그 백그라운드 실행이 파일 복사를 끝내기 전에(또는 도중에) 종료된 것으로 보인다 — 새로 빌드된 jar(`PhoneLockDesktop-1.0.0-1c3bbf38...`, 5:16:35 PM)가 아니라 그 이전(1차 개편) jar(`...ff33d7d5...`, 5:03:56 PM)가 여전히 `C:\Users\sunae\PhoneLockDesktopApp`에 남아 실행되고 있었다. 배포 명령의 결과를 실제로 확인(해시 비교)하지 않고 "배포 완료"로 보고한 게 근본 원인.
- **해결**: 프로세스를 확실히 종료 후 `robocopy /MIR`를 타임아웃 없이(백그라운드 아님) 다시 실행해 새 jar가 실제로 복사됐는지 파일 목록/해시로 확인하고 재실행 — 호스트 2곳(`PhoneLockDesktopApp`/`vm-build-output\PhoneLockDesktop`) jar 해시 일치 확인 완료.
- **검증**: 스크린샷으로 페이징 UI(아이콘 배지+인디케이터+이전/다음 버튼) 실제 렌더링 확인 완료.
- **교훈**: 배포 명령이 타임아웃으로 백그라운드로 넘어가면, 완료 알림을 기다리지 않고 다음 단계로 진행하거나 "완료"라고 보고하면 안 됨 — 반드시 결과(파일 존재/해시)를 직접 재확인한다.

---

## Fixed (2026-09-09, 96차 세션)

### 소셜 탭 채팅을 치고 엔터를 눌러도 메시지가 안 올라감
- **경위**: 사용자 제보 — "채팅 치고 엔터 치는데 채팅이 안올라감", 안드로이드/데스크탑 둘 다.
- **원인**: `ChatThreadScreen.kt`(그룹 대화/DM 공용, 양 플랫폼)의 입력 `OutlinedTextField`에 키보드 전송 액션(`imeAction`/`keyboardActions`)이 전혀 연결돼 있지 않았다 — `singleLine=true`라 엔터를 눌러도 줄바꿈도 안 되고 전송도 안 되고 아무 일도 안 일어났음. 전송 버튼(➤)만 동작했음.
- **해결**: `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)` + `keyboardActions = KeyboardActions(onSend = { sendCurrentInput() })` 추가(양 플랫폼).
- **검증**: 컴파일/빌드/배포까지 완료, 실사용 미검증(엔터로 실제 전송되는지 확인 필요).
- **98차 추가 기록**: 이 수정만으로는 부족했음 — 98차에 사용자가 전송 버튼(➤)으로도 여전히 안 된다고 재제보. 위 "Open" 섹션 "소셜 채팅을 쳐서 보내도 메시지가 안 올라감" 참고, 근본 원인은 아직 미확정.

### 앱 실행 확인(`ConfirmOpenActivity`) 화면이 커스텀 테마를 무시함
- **경위**: 사용자 제보 — "커스텀 테마 미적용 부분(실행 확인 등)".
- **원인**: `ConfirmOpenActivity.kt`의 앱(사이트 아닌) 실행 확인 경로가 `PhoneLockTheme(themeMode)`만 호출하고 `customThemeBackground`/`customThemeAccent`/`fontScale`을 안 넘겨서, 커스텀 테마 사용자에게 이 화면만 기본 프리셋 팔레트로 보였다. 사이트 확인 경로(같은 파일 45줄)는 이미 제대로 다 넘기고 있었음 — 복붙 누락으로 추정.
- **해결**: 앱 확인 경로도 `AppPreferences`에서 커스텀 필드를 읽어 동일하게 전달하도록 수정.
- **검증**: 컴파일 완료, 실사용 미검증(커스텀 테마 설정 후 앱 실행 확인 화면 색 확인 필요).

### 태블릿에서 공부 잠금 화면의 "⏹ 타이머 정지" 버튼이 안 보임
- **경위**: 사용자 제보.
- **원인**: `StudyLockActivity.kt`가 화면을 `weight(1.1f)`/`weight(1f)` 두 `Column`으로 고정 분할하는데 스크롤이 없었다 — 태블릿(특히 가로 모드처럼 세로 폭이 좁은 화면)에서 배지+타이머 원형(220dp 고정)+태스크 칩+정지 버튼까지 다 그리기엔 위쪽 Column의 세로 공간이 부족해, 넘치는 만큼 그냥 잘려서 정지 버튼이 화면 밖으로 밀려났다.
- **해결**: 위/아래 두 `Column` 모두에 `verticalScroll(rememberScrollState())` 추가 — 잘리지 않고 스크롤해서라도 항상 버튼에 닿을 수 있게 함.
- **검증**: 컴파일 완료, 실사용 미검증(실제 태블릿/가로 모드에서 버튼이 보이고 스크롤되는지 확인 필요).

---

## Fixed (2026-09-06, 93차 세션)

### 소셜 화면 배경이 공부 잠금 화면과 같은 "중앙에 빛나는 원" 디자인을 써서 겹쳐 보임
- **경위**: 93차에 소셜 화면 전체를 92차 잠금 화면 디자인 언어로 재디자인하면서 배경도 그대로 `Brush.radialGradient`를 복사했는데, 사용자가 실사용 중 "공부앱 오버레이에만 쓰이던 디자인이 소셜에도 쓰였다"고 지적.
- **원인**: 재사용해야 했던 건 강조색 배지/카드형 같은 범용 언어였는데, 잠금 화면의 원형 진행률 링과 짝을 이루는 "중앙 원형 글로우"라는 화면 특정적 디테일까지 통째로 복사했음.
- **해결**: 소셜 화면(양 플랫폼 `SocialGroupScreen.kt`의 `socialGradientBackground()`)만 위→아래로 옅어지는 `Brush.verticalGradient`로 교체, 잠금 화면 쪽은 원형 그대로 유지. [[DECISIONS.md]] 93차 참고.
- **검증**: 컴파일/빌드/배포/GitHub 릴리스 게시까지 완료(데스크탑 BuildInfo `1788695394`, 안드로이드 versionCode `1788695472`).

---

## Fixed (2026-09-06, 92차 세션)

### 타이머 탭 신규 통계 위젯(스트릭/최근 7일 그래프)이 다른 기기가 올린 공부 기록을 놓침
- **경위**: 같은 세션에서 막 추가한 위젯들을 "동기화 관련해서 문제 없는지 살펴봐"라는 요청으로 재검토하다가 발견.
- **원인**: `getAllStudyLogOnce()`(양 플랫폼)가 이 기기 로컬 `StudyLogEntry`만 반환 — 다른 기기가 올린 기록은 `remoteStudyLogCache`(날짜별로 `syncStudyLogFromFirebase(dateKey)`가 채우는 별도 맵)에만 있고, `getTodayStudyLog()`/`getStudyLogForDate()`만 그 캐시를 합쳐 반환한다. 새 위젯이 `getAllStudyLogOnce()`를 그대로 써서, 다른 기기에서만 공부한 과거 날짜가 항상 0으로 집계됐음.
- **해결**: `daySecondsSynced(dateKey)`(양 플랫폼 신규) — 오늘은 5초마다 갱신되는 `todayLog`를 재사용하고, 과거 날짜는 그 자리에서 `syncStudyLogFromFirebase(dateKey)` 후 `getStudyLogForDate(dateKey)`로 합산. 스트릭/주간 그래프 전용 갱신 함수(`refreshStreakAndWeek()`)를 30초 주기로 호출(5초 주기는 네트워크 비용이 너무 큼, 스트릭은 60일 상한으로 무한 호출 방지). [[CHANGELOG.md]] 92차 참고.

---

## Fixed (2026-09-05, 87차 세션)

### 데스크탑 release 패키징(`packageReleaseMsi`/`packageReleaseExe`)이 애초에 한 번도 성공한 적 없는 상태였음
- **경위**: "릴리스 apk로 배포해줘"/"데스크탑도 해야지" 요청으로 `packageReleaseMsi`를 처음 실제로 실행해봄 — 여태까지 이 프로젝트의 표준 배포 절차(HANDOFF.md "실행 방법")는 항상 plain(`packageMsi`/`createDistributable`)만 써왔다.
- **원인**: `phone-lock-desktop/build.gradle.kts`의 `kotlin.jvmToolchain(21)`이 만드는 Java 21 클래스(class file 버전 65)를, Compose Multiplatform 1.6.11이 release 빌드 타입에 기본으로 받아오는 ProGuard(7.2.2, 최대 지원 버전 62=Java 18)가 못 읽어 `Unsupported version number [65.0] (maximum 62.65535, Java 18)`로 즉시 실패. 이 project는 릴리스 변형 태스크를 처음부터 한 번도 실행해본 적이 없어(문서화된 표준 경로가 plain이라) 오래 방치됐던 것으로 보인다.
- **해결**: `compose.desktop.application.buildTypes.release.proguard.version.set("7.4.2")`로 업그레이드. 이어서 드러난 2차 실패(kotlinx-datetime이 참조만 하고 실제로는 안 쓰는 kotlinx.serialization 클래스 886개에 대한 미해결 참조를 ProGuard 7.4.2가 경고가 아니라 하드 실패로 취급)는 신규 `phone-lock-desktop/proguard-rules.pro`(`-dontwarn kotlinx.serialization.**`)로 해결. [[DECISIONS.md]] 87차 참고 — 다만 이 세션의 실제 배포는 여전히 plain 경로를 표준으로 유지했다(release 변형은 옵션을 되살린 것뿐).

---

## Fixed (2026-09-04, 86차 세션)

### 캘린더 계산기 연동 일정을 "미완료"로 바꿔도 이미 반영된 진행량이 롤백되지 않음
- **경위**: 사용자가 실사용 중 "계산기 연동 일정을 완료했다가 미완료 버튼으로 바꾸면 진행량이 자동으로 늘었던 게 취소돼야 하는데 그게 안 된다"고 제보.
- **원인**: `setCalendarTaskStatus`(양 플랫폼)의 진행량 롤백 코드가 "완료(O) 버튼을 다시 눌러 취소하는 경우"(`task.status == targetStatus` 분기)에만 있었다 — 완료(O)에서 미완료(X) 버튼으로 곧장 전환하는 경우(`else` 분기, targetStatus="X")는 자동생성된 다음 회독만 되돌리고 계산기 진행량 반영분은 그대로 남겨뒀다.
- **해결**: 롤백 조건을 "완료(O) 상태를 벗어나는 모든 경우"로 옮겨서, 목적지가 어디든(재클릭으로 null이 되든, 미완료(X)로 바뀌든) 항상 먼저 되돌리도록 수정(안드로이드 `PhoneLockRepository.Calendar.kt`, 데스크탑 `Repository.Calendar.kt`). "완료 버튼을 다시 눌러 취소"하는 경로는 기존에도 정상이었음(재확인 완료).

### 캘린더 일정별 계산기 업무 연결을 나중에 바꾸거나 해제할 방법이 없음
- **경위**: 사용자 요청 — 계산기 업무 연결은 캘린더 일정을 새로 만들 때(`LinkedCalcSection`)만 설정할 수 있어서, 이미 만든 일정의 연결을 나중에 바꾸거나 해제하거나 연동 대상 계산기 업무의 회독 설정이 바뀐 경우 다시 맞출 방법이 없었음. 완료 시 반영될 할당량(progressStep)도 생성 시 [from,to] 범위로만 정해지고 나중에 수정 불가능했음.
- **해결**: 각 캘린더 일정 행에 공간을 거의 안 차지하는 작은 토글 버튼("🔗 업무 연결" 또는 연결된 업무 이름) 신규 — 누르면 계산기 업무 선택/할당량 입력/적용/연결 해제 4가지를 처리하는 작은 패널이 펼쳐진다. `Repository.setCalendarTaskLink`(양 플랫폼) 신규 — 연결 대상을 바꾸면 그 업무의 현재 다회독 설정(회독 수/간격)을 다시 복사해 오므로, 같은 업무를 다시 선택하는 것만으로 "회독 설정이 바뀐 경우의 초기화" 역할도 겸한다. 이미 완료 처리된 상태에서 연결을 바꾸면 기존 반영분을 먼저 되돌리고 새 연동으로 다시 반영.

### 공부앱 일정표 업무 이름이 길면 값이 화면 밖으로 밀리거나(안드로이드) 줄바꿈된 두 번째 줄이 잘림(데스크탑)
- **경위**: 사용자가 "일정표에서 업무 이름이 줄바꿈되면서 다 보이지 않는다"고 제보.
- **원인**: 안드로이드 `TimetableScreen.kt`(+모임 멤버 상세의 동일 사본 `SocialGroupMemberDetailScreen.kt`)가 이름/값 두 `Text`를 `weight` 없이 `Arrangement.SpaceBetween`으로만 배치해, 이름이 길면 오른쪽 값 텍스트가 화면 폭 밖으로 밀려 안 보였다. 데스크탑 `TimetableScreen.kt`의 주간 표는 업무명 칸(`TtCell`)이 `width=140dp`+고정 `height=44dp`라, 이름이 길어 줄바꿈되면 두 번째 줄이 고정 높이 밖으로 잘렸다.
- **해결**: 안드로이드 2곳은 이름 `Text`에 `weight(1f, fill=false)`를 줘서 값 쪽 자연폭을 먼저 확보하고 이름은 남는 폭에서 줄바꿈되게 함. 데스크탑은 이름 칸 폭을 180dp로 넓히고 `TtCell`의 `height`를 `heightIn(min=)`으로 바꿔 긴 이름이 있는 행만 자연스럽게 늘어나도록 함.

### 커스텀 테마/브라우저 확장 배경·포인트색이 6종 삭제된 옛 팔레트 그대로 남아있고 CUSTOM 테마는 아예 미지원
- **경위**: "브라우저 확장 프로그램도 지금까지 해온 업데이트 반영됐는지 확인" 요청으로 점검.
- **원인**: `browser-extension/theme.js`가 85차에 앱 쪽에서 삭제된 라벤더/민트/로즈/미드나잇/포레스트 6종 팔레트를 그대로 갖고 있었고, 79차에 신규였던 CUSTOM(배경/포인트 직접 지정) 테마는 애초에 반영된 적이 없어 CUSTOM 선택 시 확장 페이지는 기본값(라이트·그린)으로 폴백됐다.
- **해결**: 죽은 팔레트 6종 제거, `LocalApiServer.handleTheme()`이 `customThemeBackground`/`customThemeAccent`도 함께 응답하도록 확장, `theme.js`에 `Color.kt`의 `buildCustomPalette`와 동일한 blend 알고리즘을 JS로 포팅해 CUSTOM 테마를 실제로 반영(`overlay.js` 호출부도 새 파라미터 전달하도록 수정). 아울러 설정 화면의 커스텀 테마 미리보기 상자를 누르면 헥스 직접 입력 대신 프리셋 팔레트에서 고를 수 있는 `ColorPaletteDialog`(양 플랫폼) 신규.

---

## Fixed (2026-09-04, 85차 세션)

### 캘린더 기본 정렬이 숫자를 문자 단위로만 비교해 "문제10"이 "문제2"보다 앞에 옴
- **경위**: 사용자가 "새로 추가만 한 일정의 기본 정렬(사전식+숫자순+회독 내림차순)이 제대로 안 되는 것 같다"고 제보.
- **원인**: 회독 내림차순 정렬 자체는 정상 동작했지만, 이름 비교에 순수 `Collator`만 써서 문자 단위 사전식 비교라 "문제10" < "문제2"(문자 '1' < '2')로 잘못 정렬됨. 추가로 안드로이드판 `applyCalendarAutoSchedule`/`applyIncompleteCarryOver`(자동 다음 회독 생성/미완료 다음날 이월)가 데스크탑판과 달리 `resortCalendarDay` 호출이 아예 빠져있어 정렬 없이 그냥 맨 뒤에 추가됐음(플랫폼 비대칭).
- **해결**: `:shared`에 숫자 구간은 값으로, 나머지는 Collator로 비교하는 `NaturalOrder` 신설, 양 플랫폼 정렬 비교자를 이걸로 교체. 안드로이드 `applyCalendarAutoSchedule`/`applyIncompleteCarryOver`에 누락된 `resortCalendarDay` 호출 추가(데스크탑판과 대칭).

### 자체 업데이트가 실패해도 사용자에게 아무 피드백 없이 조용히 멈춤
- **경위**: 사용자가 "업데이트 버튼을 눌러도 제대로 안 되는 경우가 흔하다(안드로이드는 설치 창이 안 뜨고 배너만 반복, 데스크탑은 원인 불명)"고 제보.
- **원인**: 안드로이드는 다운로드 후 설치 인텐트를 처리할 앱이 없어도(일부 커스텀 롬/관리형 기기) `startActivity`가 예외 없이 조용히 아무 일도 안 함, 실패 시 Toast만(짧게 사라져 놓치기 쉬움) 뜨고 배너 자체엔 남는 표시가 없었음. 데스크탑은 다운로드/설치 실행 함수(`downloadAndRunInstaller`)가 `Boolean`만 반환하고 실패 사유를 `getOrDefault(false)`로 완전히 삼켜버려 실패해도 버튼만 조용히 다시 눌리는 상태가 됐고, 다운로드 스트림에 타임아웃도 없어 네트워크가 멈추면 무기한 대기.
- **해결**: 안드로이드는 설치 인텐트 `resolveActivity` 사전 확인(못 열면 명시적 오류) + 배너 안에 계속 남는 오류 텍스트 추가(Toast와 별개). 데스크탑은 실패 사유를 문자열로 반환하도록 바꾸고 배너에 인라인으로 표시, 다운로드에 연결/읽기 타임아웃(15초/60초) 추가.
- **알려진 한계(미해결)**: 안드로이드는 업데이트 배너가 `MainActivity`(앱 진입 시 1회 읽음)와 `SettingsScreen`(별도 "지금 확인" 버튼)에 각각 독립된 상태로 떠 있어, 한쪽에서 다운로드/설치를 시작해도 다른 쪽 배너 상태는 갱신되지 않는다 — 근본적으로 고치려면 두 배너가 상태를 공유하도록 리팩터링 필요(이번 세션 범위 밖). 데스크탑은 30초 폴링이라 이 문제는 없음.

### 데스크탑에서 계산기 업무의 다회독 설정(회독 수/간격)이 재시작하면 초기화됨
- **경위**: 사용자가 "회독 설정을 해놓은 게 자꾸 초기화된다"고 제보.
- **원인**: 데스크탑 로컬 저장 파일(`JsonStore.kt`의 `toJsonObject()`, `%APPDATA%\PhoneLockDesktop\data.json`에 쓰는 실제 로컬 영속화 경로)이 `CalcTask.passCount`/`passIntervalsCsv`와 `CalendarTask.passIndex`/`passTotal`/`passIntervalsCsv`를 저장할 때 `put()`을 빠뜨리고 있었다 — Firebase 동기화 쪽(`Repository.Calc.kt`/`Repository.Calendar.kt`의 `calcTaskToJson`/`calendarTasksToJson`)은 이미 정상 포함돼 있어서 헷갈리기 쉬웠지만, 로컬 저장/재시작 시 읽어들이는 경로가 별개였고 그쪽이 누락돼 있었다. 그래서 앱을 껐다 켤 때마다(또는 로컬 파일이 다시 쓰여질 때마다) 이 필드들이 기본값(3회독, "3,4")으로 되돌아갔다.
- **해결**: `JsonStore.kt`의 `toJsonObject()`(저장 쪽)에 두 필드 세트의 `put()` 추가. 안드로이드는 Room이 컬럼을 직접 관리해 이 종류의 버그가 구조적으로 발생할 수 없음(로컬 저장 경로 확인 결과 이상 없었음).

---

## Fixed (2026-09-03, 83차 세션)

### 캘린더 일정 자동 생성이 요일별 목표량/휴일 설정을 무시하고 무조건 "내일"에 생성됨
- **경위**: 사용자가 "캘린더 일정 자동 생성 기능이 명확해 보이지 않는다"고 지적 → 조사 결과 기능 자체는 82차에 이미 구현돼 있었음(계산기 연동 업무의 1회독 완료 시 다음 배치를 자동으로 다음날에 생성)이 확인됐고, 그 "다음날" 계산이 요일/휴일을 무시하는 게 실제 문제였음.
- **원인**: `maybeAutoGenerateNextLinkedTask`(양 플랫폼)가 `nextScheduleDateKey(dateKey, 1)`(무조건 dateKey+1일)만 썼다 — `TimetableScreen`/계산기 결과 화면이 이미 존중하는 `CalcTask.mon~sun`(요일별 목표)·`holidaysCsv`/`holidays`(휴일 제외)를 자동생성 경로만 빼먹고 있었다.
- **해결**: `:shared`에 `PassSchedule.nextScheduledDate(from, dayGoals, holidays, maxLookaheadDays=90)` 신규(요일별 목표>0이고 휴일이 아닌 첫 날짜를 찾음, 90일 내 없으면 null로 무한루프 방지) — `maybeAutoGenerateNextLinkedTask`(양 플랫폼)가 이 함수로 실제 예정된 다음 날짜를 찾아 그날에 생성하도록 수정.

---

## Fixed (2026-09-02, 82차 세션, 감사 후속/릴리스 빌드)

### 로컬 API 토큰 비교가 타이밍 공격에 취약(데스크탑, LOW)
- **경위**: 감사 후속 항목("net/ApiToken.kt 인증 범위 재확인") 점검 중 발견.
- **원인**: `LocalApiServer.isAuthorizedStateChange()`가 토큰을 `token == ApiToken.value`(Kotlin/Java의 일반 `String.equals`, 앞에서부터 문자가 다르면 즉시 반환)로 비교 — 이론적으로 응답 시간차를 재서 토큰을 한 글자씩 추측하는 타이밍 공격이 가능. 루프백 전용 서버라 실질 위험은 낮았음.
- **해결**: `MessageDigest.isEqual()`(상수 시간 비교)로 교체. 그 외 인증 로직(모든 상태변경 엔드포인트가 POST+토큰 필수인지, 확장의 CORS 출처 제한이 실제로 걸려있는지)은 재확인 결과 문제 없었음.

### AGP Lint가 composite build(`:shared`) 의존성을 못 찾아 안드로이드 release 빌드가 실패함
- **경위**: 82차에 `:shared` composite build 모듈을 도입한 뒤 `assembleRelease`가 `generateReleaseLintVitalReportModel`에서 "Could not find jar for project :shared"로 실패.
- **원인**: AGP(8.5.2)의 Lint 아티팩트 해석기가 `includeBuild`로 치환된 프로젝트 의존성의 jar 경로를 못 찾는 알려진 한계.
- **해결**: `app/build.gradle.kts`에 `android { lint { checkReleaseBuilds = false } }` 추가 — lintVital을 release 조립 태스크에서 분리. `gradle lint`로 수동 실행은 여전히 가능. 자세한 배경은 [[DECISIONS.md]] "`:shared` composite build 모듈 도입" 참고.

---

## Fixed (2026-09-02, 82차 세션)

### Room 마이그레이션이 fallbackToDestructiveMigration()의 실제 동작을 오해하고 있었음(안드로이드, 잠재적 데이터 손실 버그)
- **경위**: 82차 작업 중 calc_task/study_log_entry/quote_outcome 스키마 변경(v30~v32)에 명시적 마이그레이션 없이 `fallbackToDestructiveMigration()`에 의존하면서, 주석에 "Firebase 동기화 테이블만 파괴적으로 마이그레이션된다"고 적어뒀던 게 스스로 틀렸음을 발견.
- **원인**: Room의 `fallbackToDestructiveMigration()`은 **테이블 단위가 아니라 데이터베이스 전체**를 지우고 새로 만든다. 즉 calc_task나 study_log_entry처럼 Firebase에 재동기화되는 테이블의 스키마만 바뀌어도, **동기화 안 되는 app_group(사용자가 직접 만든 차단 그룹) 등 모든 테이블이 함께 삭제**된다 — HANDOFF.md가 명시적으로 경고해온 "그룹 데이터를 파괴적 마이그레이션으로 날리면 안 된다"는 원칙과 정면으로 충돌하는 실수였다.
- **영향**: v29(기존 배포본)에서 v30~v32(이번 세션 신규 코드)로 업데이트했다면, 다음 실행 시 사용자의 모든 차단 그룹이 삭제될 뻔했다. **실제 배포 전에 발견해 수정** — 아직 사용자에게 영향 없음.
- **해결**: `AppDatabase.kt`에 `MIGRATION_29_30`/`MIGRATION_30_31`/`MIGRATION_31_32`/`MIGRATION_32_33`을 전부 명시적 `ALTER TABLE`/`CREATE TABLE`로 작성해 `addMigrations()`에 등록. `fallbackToDestructiveMigration()`은 이 마이그레이션 경로를 벗어난 미지의 버전 점프에 대한 최후 폴백으로만 남김.
- **교훈**: 앞으로 Room 엔티티 스키마를 바꿀 때마다(신규 테이블 포함) **반드시 명시적 Migration을 작성할 것** — "이 테이블은 Firebase 동기화 대상이라 괜찮다"는 판단은 틀렸다.

---

## Open

### 안드로이드 자체 업데이트 다운로드가 멈춤/실패 (81차 최초, 95차 재발)
- **경위**: 81차에 DownloadManager PAUSED 처리/메터드 네트워크 허용 등을 보강했는데도, 95차에 사용자가 "업데이트 배너는 뜨는데 눌러도 다운로드가 안 되거나 멈춘다"고 재차 제보.
- **95차 조치**: 다운로드 방식 자체를 시스템 `DownloadManager`에서 앱 프로세스 안 직접 HTTP 스트리밍으로 교체(OEM ROM 배터리 최적화가 DownloadManager를 조용히 막는 경로를 원천 차단하려는 의도), API 30+ 패키지 가시성 문제로 설치 화면을 못 찾을 수 있는 매니페스트 `<queries>` 누락도 함께 수정. 자세한 설계는 [[DECISIONS.md]] 95차 참고.
- **상태**: **실사용 미검증** — 로그(logcat)를 직접 보지 못하고 코드 추정만으로 고쳤다. 현재 설치된 구버전은 이 수정 자체를 못 받으므로(업데이트 로직 자체가 고쳐지는 대상이라 순환 의존) 사용자가 GitHub 릴리스([android-1788711405](https://github.com/studybeultaeon-svg/study-planner/releases/tag/android-1788711405) 이상)에서 APK를 수동 설치해 확인하기로 함. 그래도 재발하면 이번엔 정확히 어느 단계(진행률이 안 오르는지/몇 %에서 멈추는지/에러 문구)에서 멈추는지부터 확인할 것.

### 안드로이드/데스크탑 Firebase Web API Key가 서로 다른 값 (82차 감사 발견)
- **경위**: 82차 아키텍처 감사에서 `AppPreferences.kt`(안드로이드)의 `DEFAULT_FB_API_KEY`와 `Models.kt`(데스크탑)의 `DEFAULT_FB_API_KEY`가 다른 문자열임을 발견.
- **원인**: 미확인 — 같은 Firebase 프로젝트라면 Web API Key가 보통 하나뿐이라 의도적 분리가 아니라면 한쪽이 오래된/다른 프로젝트 키일 가능성.
- **영향**: 둘 중 하나가 만료되거나 다른 프로젝트를 가리키면 그 플랫폼만 조용히 동기화 실패 — "이 기기만 동기화 안 됨" 유형 문의로 나타날 수 있음.
- **상태(2026-09-02, 사용자 판단으로 보류)**: 콘솔 확인 방법을 안내했으나, 두 플랫폼 간 동기화가 실제로는 잘 작동해왔다는 점에서 버그가 아니라 Firebase가 원래 안드로이드용/웹·REST용 키를 다르게 발급하는 정상 케이스일 가능성이 높다고 판단 — 클라우드 백업(기본 꺼짐, MED 등급)에만 영향 있는 낮은 우선순위 항목이라 지금은 확인하지 않기로 함. "이 기기만 동기화 이상함" 같은 실제 증상이 생기거나 클라우드 백업을 쓰고 싶을 때 재검토할 것. 코드는 건드리지 않음(잘못 통일하면 오히려 동기화를 깨뜨릴 위험).

### 안드로이드 당겨서 새로고침이 스크롤과 겹쳐서 잘 안 됨 / 새로고침 아이콘이 안 사라짐 (99차 원인 미확정 → 106차 Fixed)
- **경위**: 사용자 제보 — 새로고침 제스처가 스크롤 기능과 겹쳐져서 잘 트리거되지 않는다 / 새로고침 아이콘이 화면에서 안 사라지고 그대로 남는다.
- **99차 조사**: `PullToRefreshBox.kt`의 구조(`Box.nestedScroll(state.nestedScrollConnection)`로 감싸고 내부에 `LazyColumn`/`Column.verticalScroll` 배치) 자체는 Google 공식 샘플과 동일한 표준 패턴이라 구조적 결함은 못 찾음 — 근본 원인은 현재 쓰는 Material3 `rememberPullToRefreshState`/`PullToRefreshContainer`가 `@ExperimentalMaterial3Api`(compose-bom 2024.06.00 기준 material3 1.2.x대)라서 제스처 인식이 상대적으로 불안정한 것으로 추정된다(확정 아님).
- **106차 해결**: compose-bom을 2024.06.00→2024.09.00(material3 1.3.0), Kotlin을 1.9.24→1.9.25, compose 컴파일러 확장을 1.5.14→1.5.15로 올려 `PullToRefreshBox.kt`를 material3 1.3.0의 안정화된 `PullToRefreshBox` API(`isRefreshing`/`onRefresh`만 넘기면 인디케이터 표시·수축을 라이브러리가 전부 관리)로 교체. 이 API도 여전히 `@ExperimentalMaterial3Api`이지만, 1.2.x의 수동 `rememberPullToRefreshState`+`endRefresh()` 관리 방식 자체가 원인이었던 "임계값 근처에서 손을 떼면 인디케이터가 수축 못 함" 버그는 1.3.0 재작성으로 해결됨. 컴파일/릴리스 빌드 확인 완료.
- **상태**: Fixed(코드), **실사용 미검증** — [[HANDOFF.md]] "다음 작업 우선순위" 106차 항목 참고.

---

## Fixed (2026-09-01, 81차 세션)

### 안드로이드 자체 업데이트 다운로드가 "다운로드 중"에서 멈추고 아무 반응이 없음
- **경위**: 사용자가 방금 배포된 새 버전으로 "업데이트" 버튼을 눌렀는데 "업데이트 다운로드 중..."만 뜨고 성공도 실패도 없이 계속 그 상태라고 제보.
- **원인(추정, 실제 재현으로 확인된 건 아님)**: `UpdateBanner.kt`가 `DownloadManager`로 다운로드를 걸고 상태(`RUNNING`/`PENDING`)를 1초 간격으로 최대 5분 폴링하는데, 데이터 절약 모드 등으로 다운로드가 `STATUS_PAUSED`에 들어가면 이 상태를 진행 중으로도 실패로도 처리하지 않아 루프를 즉시 빠져나가면서도 `STATUS_SUCCESSFUL`이 아니라서 실패 토스트가 뜨긴 했어야 했다 — 그런데도 "아무 반응 없다"는 제보라 다른 원인(느린 네트워크로 5분을 다 채우기 전, 또는 토스트를 놓침)일 가능성도 남아있다.
- **해결**: `DownloadManager.Request`에 `setAllowedOverMetered(true)`/`setAllowedOverRoaming(true)` 추가(데이터 절약 모드로 인한 정지 방지), `PAUSED` 상태도 "대기 중"으로 취급해 계속 폴링, 실제 진행률(%)을 배너 문구에 표시, 실패 시 상태 코드/사유(`COLUMN_REASON`)를 토스트에 그대로 노출(진단용).
- **검증**: 코드 컴파일/빌드/GitHub 릴리스 게시(android-1788190404) 완료. **실제로 다운로드가 끝까지 진행되는지는 미검증** — 여전히 안 되면 GitHub 릴리스 페이지에서 apk 수동 설치로 우회 가능하다고 안내할 것.

---

## Fixed (2026-08-31, 80차 세션)

### 안드로이드 캘린더 "다회독" 토글을 눌러도 화면에 바로 반영되지 않고 다른 화면 갔다 와야 바뀜
- **경위**: 사용자가 "버튼을 눌러도 바로 안 바뀌다가 다른 창을 갔다가 돌아와야 변하는 현상이 여럿 있다"고 보고, 다회독 토글을 대표 사례로 지목.
- **원인**: `CalendarScreen.kt`의 `DayDetailSection`이 그날 일정 목록을 `LaunchedEffect(dateKey)`로 한 번만 읽어 로컬 `mutableStateOf` 리스트에 담아두는 구조라, 항목별 액션이 DB만 갱신하고 이 로컬 리스트를 다시 안 읽어오면 화면엔 그대로 남는다. 같은 화면의 다른 모든 액션(이동/색상/완료/삭제 등)은 전부 뒤에 `onChanged()`를 호출해 `refreshDay()`로 다시 읽어오는데, 다회독 토글 클릭 핸들러(`setCalendarTaskMultiPass` 호출부)에만 `onChanged()` 호출이 빠져 있었다 — `dateKey`가 바뀌는 시점(다른 날짜 선택, 화면 재진입 등)에 `LaunchedEffect`가 다시 돌 때만 우연히 반영됐다.
- **해결**: 해당 클릭 핸들러에 `onChanged()` 호출 추가. 데스크탑판 `CalendarScreen.kt`은 애초에 정상 호출 중이었음(안드로이드만의 문제).
- **점검 범위**: `CalculatorScreen`/`RoutineScreen`/`SocialGroupMembersScreen`의 동일 패턴(리스트 1회 로드 + 항목별 액션)도 함께 점검 — 전부 정상적으로 `onChanged()`/`refresh()`/`reload()`를 호출하고 있어 추가로 발견된 문제는 없었음.
- **검증**: 컴파일/빌드/GitHub 릴리스 게시 완료, 실사용(토글 클릭 시 화면이 즉시 바뀌는지) 재현 검증은 미검증.

---

## Fixed (2026-08-31, 79차 세션, 추가)

### 데스크탑 자체 업데이트가 무한 반복됨 — "업데이트" 눌러도 안 되고 배너가 계속 다시 뜸
- **경위**: 사용자가 "업데이트를 해도 안 되고 배너가 또 뜨고, 눌러도 계속 반복된다"고 보고.
- **원인**: `UpdateBanner.kt`의 "업데이트" 버튼이 설치파일을 다운로드해 실행시킨 뒤 `exitProcess(0)`로 앱을 종료하는데, 이때 트레이 "종료"와 달리 `intentional_exit.flag`를 남기지 않았다. 감시 프로세스(Watchdog)는 이 표식이 없으면 "비정상 종료"로 보고 2초 안에 옛 버전 exe를 즉시 되살리는데, 그 시점엔 사용자가 설치 마법사를 다 클릭하기도 전이라 옛 버전이 새로 설치될 파일들을 다시 잠가버린다 — 결과적으로 설치가 제대로 안 끝나고, 되살아난 옛 버전이 곧 "새 버전 있음" 배너를 또 띄우면서 영원히 반복.
- **해결**: 설치파일 실행 직후 `exitProcess(0)` 전에 `intentionalExitFlagFile().createNewFile()` 호출 추가(트레이 "종료"와 동일 절차) — 감시 프로세스가 되살리지 않으므로 설치 마법사가 방해 없이 끝까지 진행되고, 설치된 새 버전이 다음에 켜지면 이 표식은 자동으로 지워진다(`Main.kt`의 정상 시작 절차).
- **주의**: 이 버그를 가진 "구버전"이 이미 설치돼서 반복 루프에 빠진 사용자는, 그 구버전의 업데이트 버튼 자체가 여전히 이 레이스 컨디션을 타므로 인앱 업데이트로는 못 빠져나올 수 있다 — 트레이 "종료"로 완전히 끈 뒤 [최신 릴리스](https://github.com/studybeultaeon-svg/study-planner/releases)의 msi를 수동으로 다운로드/설치하는 것을 권장.
- **검증**: 컴파일/빌드/이 호스트 재배포 완료, 실사용(실제 반복 루프 재현 후 해결 확인)은 미검증.

## Fixed (2026-08-31, 79차 세션)

### 데스크탑 msi 설치 후 바탕화면/시작메뉴에 아이콘이 안 생김
- **원인**: `build.gradle.kts`의 `compose.desktop.application.nativeDistributions.windows` 블록에 `shortcut`/`menu`/`menuGroup` 설정이 없어서 jpackage가 바로가기를 생성하지 않음. 개발자 본인은 지금까지 `createDistributable` 결과물을 robocopy로 직접 배포해왔지 실제 msi 설치 경로를 써본 적이 없어서 발견이 늦어짐 — 사용자가 친구에게 msi를 준 뒤 "바탕화면에 앱이 없다"는 보고를 받고서야 확인됨.
- **해결**: `windows { shortcut = true; menu = true; menuGroup = "PhoneLockDesktop" }` 추가 후 재빌드, 이 호스트 재배포 + 새 msi로 GitHub 릴리스 재게시([desktop-1788168805](https://github.com/studybeultaeon-svg/study-planner/releases/tag/desktop-1788168805)).
- **주의**: 이전 릴리스(`desktop-1788167727`)는 바로가기가 안 생기는 구버전 msi이므로, 앞으로 다른 사람에게 배포용 링크를 안내할 땐 항상 최신 태그인지 확인할 것.

## Fixed (2026-08-31, 78차 세션)

### Firebase RTDB 보안 규칙 재게시 — 사용자 확인
- **상황**: 77차에 추가한 모임장/관리자 권한 시스템(`groups/{id}/info` 쓰기 규칙, `members/{uid}` 쓰기 규칙 확장)이 파일엔 반영됐지만 Firebase 콘솔에 재게시가 안 된 상태였음.
- **해결**: 사용자가 78차 세션에 "이미 게시했을 것"이라고 확인. **주의**: Claude는 Firebase 콘솔 접근 권한이 없어 콘솔 화면과 직접 대조하지는 못했음 — 관리자(비-모임장)의 이름수정/내쫓기/무전기가 이후에도 권한 거부(401 등)로 계속 실패하면 이 항목부터 재점검할 것.

### 태블릿에서 릴스/쇼츠 차단이 전혀 안 됨
- **원인**: `AppMonitorAccessibilityService.containsSelectedKeyword()`가 "선택된 탭" 노드를 화면 하단 12% 영역에서만 찾음(폰의 하단 탭바 기준) — 태블릿은 Instagram/YouTube가 좌측 세로 내비게이션 레일을 쓰기 때문에 선택된 릴스/쇼츠 탭이 이 영역 밖에 있어 계속 걸러짐.
- **해결**: 하단 밴드 조건에 좌/우측 16% 폭의 사이드 레일 밴드도 OR로 추가. [[DECISIONS.md]] 78차 참고.
- **검증**: 컴파일/빌드/배포 완료. **실기기(태블릿) 검증 아직 안 됨.**

### 데스크탑 무전기 TTS가 PC 기본 음성에 따라 한글을 영어 음성으로 읽을 수 있었음
- **원인**: `TtsPlayer.kt`(desktop)가 `SpeechSynthesizer.Speak()`를 호출하기 전 한 번도 명시적으로 음성을 선택한 적이 없어서, 그 PC의 SAPI 기본 음성이 영어(예: Microsoft Zira)로 설정돼 있으면 한국어 텍스트를 영어 음성 엔진으로 읽었음. 사용자가 "지금 보이스는 영어 보이스"라고 지적해서 발견(빌드 호스트 자체는 기본값이 한국어라 재현 안 됐었음).
- **해결**: 성별 설정과 무관하게 항상 한국어(`ko`) 문화권 음성을 먼저 찾아 선택하도록 변경, 한국어 음성이 하나도 없을 때만 시스템 기본값 사용. [[DECISIONS.md]] 78차 참고.
- **검증**: PowerShell로 격리 테스트(설치된 Heami/Zira/David 중 항상 Heami 선택됨) 확인 + 컴파일/배포 완료.

### `AndroidBuilds\phone-lock-desktop\build.gradle.kts`가 75차 이전 버전으로 방치돼 데스크탑 컴파일 실패
- **원인**: `robocopy /MIR`로 `src/` 폴더만 반복 동기화해왔는데, 75차에 추가된 `generateBuildInfo` Gradle 태스크는 `build.gradle.kts`(src 밖) 안에 있어서 그동안 한 번도 AndroidBuilds 사본에 반영되지 않았음 — `compileKotlin`이 `Unresolved reference: BuildInfo`로 계속 실패.
- **해결**: OneDrive 원본의 `build.gradle.kts`를 AndroidBuilds 사본에 직접 복사. **앞으로 빌드 스크립트 자체가 바뀌는 세션 이후엔 `src/` 뿐 아니라 `build.gradle.kts`/`settings.gradle.kts` diff도 같이 확인할 것.**
- **검증**: 재동기화 후 `compileKotlin` BUILD SUCCESSFUL 확인.

### (버그는 아니지만 절차 문서화) 데스크탑 앱은 `Stop-Process` 직후 자기 자신을 즉시 재기동한다
- **상황**: 배포 전 `Stop-Process -Name PhoneLockDesktop`으로 종료해도, 앱 내부의 인메모리 워치독이 1초 이내에 스스로 재기동해서 이후 robocopy가 실행 중인 exe를 못 옮기고(`ERROR 32`) 30초 간격으로 무한 재시도에 빠질 수 있음. Windows 예약 작업(스케줄된 태스크) 기반이 아니라 앱 코드 자체의 자기 복구 로직임을 확인.
- **대응**: 재시도를 기다리지 말고 즉시 `Stop-Process`를 한 번 더 실행(이번엔 재기동 타이밍을 놓쳐 완전히 죽음)한 뒤 곧바로 robocopy할 것. [[HANDOFF.md]] "현재 주의사항" 참고.

---

## Fixed(빌드/배포까지 완료, 77차) (2026-08-30, 77차 세션 — 사용자 제보)

### 안드로이드/데스크탑: 업데이트 확인을 눌러도 새 버전이 있는데 계속 "최신 버전"으로 뜸
- **경위**: 사용자가 "안드로이드에서 업데이트 확인해도 최신 버전이라고 진행 안 한다"고 제보.
- **원인**: `UpdateChecker.checkLatestAndroidRelease()`/`DesktopUpdateChecker.checkLatestDesktopRelease()`가 네트워크 실패·비2xx 응답을 전부 `null`로 삼켰고, 상위 로직이 이 `null`을 "확인해봤는데 새 버전 없음"과 동일하게 취급했다. 실제 원인은 GitHub 비인증 REST API 요청 한도(IP당 시간당 60회)를 이 호스트의 `gh` CLI 다수 호출로 소진한 것 — `curl`로 직접 조회해 `X-RateLimit-Remaining: 0`(403)을 확인해 확정.
- **해결**: 두 체커 함수가 `Result<LatestRelease?>`를 반환하도록 변경(성공+없음 vs 실패를 구분), `Repository`/`PhoneLockRepository`에 `UpdateCheckOutcome`(`Available`/`UpToDate`/`Failed(reason)`) 3상태 신설, 설정 화면이 실패 시 "확인 실패: ... 잠시 후 다시 시도해주세요"를 붉은 글씨로 별도 표시. [[DECISIONS.md]] 77차 참고.
- **검증**: 컴파일/빌드/새 릴리스 게시(`android-1788071119`/`desktop-1788071237`)까지 완료. 실제 요청 한도가 풀린 뒤 "확인" 버튼을 눌러 정상적으로 새 버전을 찾는지는 사용자 실사용 확인 필요.

### 안드로이드/데스크탑: 업데이트 배너는 뜨는데 "업데이트" 설치 버튼이 안 보임
- **경위**: 사용자가 처음엔 "배너 자체가 문구만 뜨고 안 뜬다"고 했다가 "배너는 뜨는데 버튼만 안 뜬다"로 정정.
- **원인**: `UpdateBanner.kt`(양 플랫폼)가 `Row(Arrangement.SpaceBetween)`에 weight 없는 긴 한글 `Text`와 `Button`을 나란히 배치 — 문구가 길면 Text가 Row 폭을 거의 다 차지해 Button이 화면 밖으로 밀려나 안 보였음.
- **해결**: `Column`으로 교체(문구 → `Spacer(8.dp)` → `Button(fillMaxWidth())`) — 문구 길이·화면 폭과 무관하게 버튼이 항상 자기 줄에서 전체폭으로 보이게 함.
- **검증**: 컴파일/빌드/새 릴리스 게시(`android-1788071959`/`desktop-1788072077`)까지 완료. 실기기에서 배너+버튼이 실제로 함께 보이는지는 사용자 실사용 확인 필요.

### (도구 사용 문제, 앱 버그 아님) sync-public-repo.ps1 1차 구현이 커밋 안 한 소스 코드를 통째로 지움
- **경위**: `study-planner` 공개 저장소에 소스만 골라 동기화하는 스크립트를 처음 만들어 실행한 직후, 방금 작성한 업데이트 확인 버그 수정 코드가 작업 트리에서 사라진 것을 발견(`clean-main` diff가 예상과 달리 "변화 없음"으로 나와 감지).
- **원인**: 스크립트가 `git checkout clean-main`(메인 디렉터리 브랜치 전환) 후 `git checkout master -- <path>`로 파일을 가져왔는데, 이 명령은 master의 "커밋된" 상태를 읽는다 — 이 프로젝트는 관행상 매번 커밋하지 않으므로, 아직 커밋 안 한 방금 작성한 코드가 브랜치를 한 번 왔다갔다 하는 사이 통째로 옛 커밋 상태로 덮어써짐.
- **해결**: 영향받은 6개 파일을 대화 맥락 기억으로 정확히 복원(각 파일 `grep`/`Read`로 복원 결과가 원래 작성한 내용과 정확히 일치하는지 확인) 후 재컴파일로 회귀 없음 확인. 스크립트를 `git worktree` 기반으로 재설계(메인 디렉터리 브랜치는 절대 전환하지 않음) — 자세한 내용은 [[DECISIONS.md]] 77차 참고.
- **재발 방지**: 이 프로젝트처럼 커밋 안 한 상태가 일상인 워크플로우에서 "다른 브랜치의 파일을 가져오는" 종류의 git 작업(`checkout -- <path>`, `stash`로 브랜치 전환 등)을 스크립트화할 땐, 항상 "현재 디스크 상태"가 대상인지 "마지막 커밋"이 대상인지를 먼저 명확히 구분하고, 전자가 필요하면 브랜치 전환 없이 파일 복사(robocopy 등)만으로 해결할 방법을 우선 검토할 것.

---

## Fixed(빌드/배포까지 완료, 74차) (2026-08-29~30, 74차 세션 — 사용자 제보)

### 앱을 켜자마자 즉시 종료됨(크래시 루프) — 예약 알람 500개 한도 초과
- **경위**: 74차 무전기 재설계 배포 직후 사용자가 "업데이트하니 앱이 켜지자마자 꺼진다"고 제보. 처음엔 `WalkieTalkieService`가 이제 전 사용자에게 무조건 실행되도록 바뀐 점(포그라운드 서비스 시작 제약)을 의심해 방어 코드+전역 크래시 로거(`PhoneLockApplication.kt`)를 추가했으나 재발.
- **원인**: 사용자가 넘겨준 실제 크래시 로그에서 `IllegalStateException: Maximum limit of concurrent alarms 500 reached` 확인. `PhoneLockRepository.syncRoutinesFromFirebase()`가 원격이 더 최신이면 로컬 루틴을 전부 지우고 새 Room auto-increment ID로 재삽입하는데, 루틴 알림 예약(`RoutineAlarmScheduler`)이 이 ID를 그대로 `AlarmManager` requestCode로 써서 — 동기화 때마다 옛 ID의 예약 알람은 취소할 방법이 사라지고 새 알람만 계속 쌓여 결국 안드로이드 앱당 한도(500개)를 초과.
- **해결**: ① `syncRoutinesFromFirebase()`가 delete+insert 전에 지금 루틴들의 알람을 먼저 명시적으로 취소(재발 방지), ② 이미 쌓인 알람을 정리하는 일회성 스윕(`RoutineAlarmScheduler.cleanupLeakedAlarmsIfNeeded`, ID 1~20000 범위 취소 시도, `AppPreferences.leakedAlarmsCleaned`로 1회만), ③ `scheduleAlarm()`을 `runCatching`으로 감싸 한도 초과 자체가 다시 발생해도 앱이 죽지 않게 방어.
- **검증**: 컴파일/빌드/배포 완료. 실기기에서 재발 안 하는지는 다음 실행 때 확인 필요(HANDOFF.md 다음 우선순위 참고).
- 자세한 내용은 [[CHANGELOG.md]]/[[DECISIONS.md]] 74차 참고.

### 무전기: 음성/텍스트 메시지 선택 후 아무 반응 없이 조용히 실패
- **경위**: 사용자가 "무전기 테스트하다가 아예 작동을 안 한다"고 제보 — 선택창은 뜨는데 옵션을 골라도 토스트도 안 뜨고 아무 일도 안 일어남.
- **원인**: `WakeOptionsDialog`에서 "음성"/"텍스트" 버튼이 `onDismiss()`(깨우기 대상 정보를 완전히 초기화)를 먼저 호출한 뒤 다음 단계(녹음/텍스트 입력창)를 열고 있었음 — 그 결과 입력창은 뜨지만 "누구에게 보낼지"가 이미 사라져서, 보내기를 눌러도 대상이 없어 코드가 조용히 건너뛰던 것.
- **해결**: 음성/텍스트로 전환하는 버튼에서는 `onDismiss()`를 호출하지 않도록 수정 — 상태(`wakeStep`)가 바뀌면 선택창은 조건부 렌더링으로 자연히 사라지므로 별도로 닫을 필요가 없었음.
- **검증**: 컴파일 확인 후 양 플랫폼 빌드/배포 완료.

### 무전기: FORCED(즉시재생) 모드에서 같은 메시지가 계속 반복 재생됨
- **경위**: 사용자가 "무전 자동 재생 모드에서 들어온 무전이 계속 재생된다"고 제보.
- **원인**: "재생 → 삭제" 순서였는데 삭제 요청의 HTTP 응답 코드를 확인하지 않아, 삭제가 서버에서 실패(권한 등)해도 성공으로 간주하고 넘어갔음 — 메시지가 RTDB에 계속 남아 7초마다 폴링할 때마다 다시 재생됨.
- **해결**: "삭제 확인 → 성공했을 때만 재생" 순서로 변경(양 플랫폼) — 삭제 실패 시 이번엔 재생을 건너뛰고 다음 폴링에서 재시도.

### 무전기: 삭제 버튼이 서버 실패 여부와 무관하게 항상 "성공한 것처럼" 동작
- **원인**: 삭제 버튼이 서버 삭제 결과를 확인하지 않고 무조건 로컬 인박스 목록에서 항목을 지우고 있었음 — 서버에서 실패해도 화면상으론 지워진 것처럼 보이다가 다음 조회 때 그대로 남아있어 "삭제가 안 된다"는 혼란을 줌.
- **해결**: 서버 삭제가 실제로 성공했을 때만 목록에서 제거하도록 수정, 실패 시 실제 원인(HTTP 상태코드+RTDB 응답 본문)을 토스트/텍스트로 노출.

### 무전기: "그냥 깨우기"(넛지)가 사실상 안 오는 것처럼 느껴짐
- **원인**: 안드로이드에서 넛지는 `GroupNudgeWorker`(WorkManager 최소 주기 15분)로만 확인했음 — 음성/텍스트 무전(7초 폴링)에 비해 훨씬 느려서 짧은 테스트 시간 안엔 거의 항상 "안 온" 것처럼 보임.
- **해결**: `WalkieTalkieService`의 7초 폴링에 넛지 확인도 포함시켜 근접 실시간으로 개선. 기존 15분 워커는 서비스가 죽어있는 드문 경우의 보완용으로 유지.

### 알림 진동 누락 + 채널 재정의 불가
- **원인**: 무전기 메시지/모임 깨우기 알림 채널에 `enableVibration`이 없었음(기본값 false). 코드로 추가해도, 안드로이드는 알림 채널을 한 번 만들면 앱이 나중에 설정을 재정의할 수 없어 이미 생성된 채널엔 반영이 안 됨.
- **해결**: 채널 진동 설정 추가 + `group_nudge_v2`/`walkie_message_v2`로 채널 ID를 새로 발급해 우회(61차 루틴 알림 채널과 동일 패턴). 사용자 요청에 따라 "접근성 서비스 감시"(기능적 경고)는 진동 없이 유지.

---

## Fixed(빌드/배포까지 완료, 73차) (2026-08-29, 73차 세션 — 사용자 제보)

### 데스크탑: "모임 생성"이 항상 실패함
- **경위**: 사용자가 "모임 생성이 안 됨"이라고 제보.
- **원인**: 데스크탑 `SocialGroupSyncClient.kt`의 `createGroup()`이 안드로이드와 다르게 2단계로 구현돼 있었음 — 빈 body(`{}`)로 `groups`에 먼저 POST해서 `groupId`만 받아온 뒤, 별도 PUT으로 `info`(name/ownerUid/inviteCode/createdAt)를 씀. 64/68차에 강화된 `firebase-database.rules.json`의 `groups/$groupId` 쓰기 규칙(`!data.exists() && newData.child('info').child('ownerUid').val() === auth.uid`)은 첫 write의 `newData` 안에 이미 `info`가 있어야 통과하는데, 빈 body push는 이 조건을 절대 만족할 수 없어 그 시점부터 모든 모임 생성이 규칙 위반으로 거부되고 있었음. 안드로이드는 처음부터 info를 포함한 body로 한 번에 POST해서 이 버그가 없었고, 70차 실기기 검증은 "이미 만들어져 있던 모임"으로 확인해서 못 잡았던 것으로 추정.
- **해결**: `push()` 헬퍼가 body를 받도록 확장, `createGroup()`이 초대코드를 먼저 만든 뒤 info를 포함한 body로 한 번에 push하도록 안드로이드와 동일한 패턴으로 재작성.
- **검증**: 빌드된 jar에 수정된 코드가 실제로 포함된 것을 클래스 목록으로 확인 + 표준 절차로 실제 배포까지 완료. 사용자가 새 모임을 실제로 만들어서 되는지는 아직 최종 확인 전(다음 우선순위 참고).
- 자세한 내용은 [[CHANGELOG.md]]/[[DECISIONS.md]] 73차 참고.

---

## Fixed (2026-08-28, 72차 세션 — 사용자 실사용 중 제보)

### 완전히 새 계정(원격 데이터 0)에서는 루틴/캘린더/계산기가 영원히 동기화 안 됨
- **경위**: 사용자가 새 아이디/비번 계정으로 로그인 후 "동기화가 잘 되고 있냐"고 질문 → 데스크탑 로컬 세션의 refreshToken으로 idToken을 직접 얻어 Firebase RTDB를 REST로 조회해보니 `users/{uid}` 아래 `profile`만 있고 `routines`가 없었음.
- **원인**: `PomodoroSyncClient.kt`(안드로이드/데스크탑 둘 다)의 `readRoutines`/`readCalendarTasks`/`readCalculator` 3개 함수가 HTTP 200 + `body == "null"`(=RTDB에 그 경로 자체가 없다는 정상 응답, 신규 계정이면 항상 이렇게 옴)을 실제 네트워크 오류·파싱 실패와 똑같이 `null` 반환으로 처리하고 있었음. 이 `null`을 받는 `Repository.syncXFromFirebase()`가 `result ?: return`으로 즉시 함수를 빠져나가서, "원격이 로컬보다 오래됐으면 로컬을 원격에 올린다"는 LWW push 분기 자체에 도달하지 못했음 — 로컬에 데이터가 아무리 많아도 첫 동기화가 조용히 실패하고 다시는 시도되지 않음(에러 메시지도 없음).
- **왜 지금까지 안 걸렸는지**: 이전까지는 모든 사용자가 Google 로그인으로 최소 한 번은 각 데이터를 원격에 올린 이력이 있었기 때문에 "원격이 완전히 비어있는" 상태 자체가 발생한 적이 없었음. 이번에 아이디/비번 로그인이 추가되면서 "로컬엔 실사용 데이터가 있는데 원격 uid는 완전히 새것"인 시나리오가 처음 생겼고, 그 순간 이 잠재 버그가 드러남.
- **해결**: `body == "null"`일 때 `null` 대신 빈 결과 객체(모든 타임스탬프 `0L`, 빈 배열/객체)를 반환하도록 세 함수 모두 수정 — 이러면 로컬 타임스탬프가 항상 `0`보다 크므로 정상적으로 push 분기를 타게 됨. 네트워크 오류/비2xx 응답 시엔 여전히 `null`을 반환해 "오류"와 "정상적으로 비어있음"을 구분함.
- **검증**: 데스크탑에서 실제로 앱만 재시작했더니 `users/{uid}/routines`가 Firebase에 새로 생기는 것을 REST 조회로 확인.
- **영향 범위**: 루틴/캘린더/계산기 3개 문서 단위 LWW 동기화 전부(신규 계정에서만 발생, 기존 계정은 이미 원격에 데이터가 있어 영향 없음).

---

## Fixed(빌드/배포 완료, 실기기 미검증) (2026-08-27, 71차 세션 — 사용자 제보)

### 안드로이드: "모임"에서 닉네임 대신 이메일/구글 실명이 표시됨
- **경위**: 사용자가 "모임 내에서 닉네임으로 뜨지 않고 이메일로 뜬다"고 제보.
- **원인**: 안드로이드 `SocialGroupSyncClient.kt`의 `createGroup()`/`joinGroupByCode()`가 멤버 레지스트리(`groups/{id}/members/{uid}/displayName`)를 쓸 때 `GoogleAuthManager.currentUser?.displayName ?: email`을 그대로 써서, 앱 내부 닉네임 시스템(`AccountSyncClient.myDisplayName()`, 닉네임→커스텀아이디→이메일→uid 우선순위)을 전혀 참조하지 않았음. 데스크탑은 이미 이 우선순위 로직을 쓰고 있어서 문제가 없었음(플랫폼 간 구현 불일치).
- **해결**: 두 함수 모두 `AccountSyncClient.myDisplayName(databaseUrl, apiKey)` 호출로 교체. 기존에 이미 생성된 모임도 즉시 반영되도록 `SocialGroupMembersScreen.kt`의 멤버 표시 이름을 members 레지스트리 대신 stats(매번 최신 닉네임으로 갱신됨)의 값을 우선 쓰도록 변경.
- **상태**: 코드 수정 + `assembleRelease` 빌드/서명/배포까지 같은 세션에서 완료(`vm-build-output/android/app-release.apk`) — 실기기 설치 확인만 남음.

---

## Fixed (2026-08-27, 67차 세션 — Claude 작업 중 발견)

### 안드로이드: `AndroidBuilds\phone-lock-android`에 `proguard-rules.pro`가 누락돼 release 빌드가 64차에 작성한 keep 규칙 없이 R8을 돌리고 있었음
- **경위**: HANDOFF 최우선 항목(64차, "release APK를 `assembleRelease`로 빌드해 검증")을 이어받아 실제로 빌드해봄 — 첫 `assembleRelease` 실행에서 `minifyReleaseWithR8` 단계가 `Supplied proguard configuration does not exist: C:\AndroidBuilds\phone-lock-android\app\proguard-rules.pro` 경고를 내며 진행됨(빌드 자체는 이 경고로도 성공하기 때문에 놓치기 쉬움).
- **원인**: 64차가 OneDrive 원본에 Firebase Auth/Room 리플렉션 클래스를 보존하는 `app/proguard-rules.pro`를 새로 작성했지만, 이후 세션들(65~66차)의 소스 동기화(robocopy)가 이 신규 파일을 `AndroidBuilds\phone-lock-android`로 반입하지 않았음 — `app/build.gradle.kts`가 이 파일을 참조하도록 64차에 이미 고쳐져 있었는데도 파일 자체가 없어서, 지금까지 만들어진 release APK가 있었다면 전부 기본 `proguard-android-optimize.txt`만 적용된 채(64차가 의도한 keep 규칙 없이) 빌드됐을 것.
- **해결**: OneDrive 원본의 `proguard-rules.pro`를 `AndroidBuilds\phone-lock-android\app\`로 복사 후 재빌드 — 경고 없이 `BUILD SUCCESSFUL`, `minifyReleaseWithR8` 정상 적용 확인.
- **후속(같은 세션)**: `app-release-unsigned.apk`는 서명이 안 돼 실기기 설치가 불가했음 — 사용자 확인 후 release keystore를 새로 생성해 서명 설정까지 완료([[DECISIONS.md]] 67차 참고), 서명된 `app-release.apk`를 `vm-build-output/android/`에 배포. 실기기 설치/기능 검증만 남음.
- **재발 방지**: 소스 동기화(robocopy) 후 신규/누락 파일이 있는지 `git status`나 파일 목록 비교로 한 번 더 확인할 것 — 36차의 "robocopy가 조용히 스킵" 교훈과 같은 계열의 문제.

---

## Fixed/문서화 (2026-08-25~26, 61차 세션 — Claude 작업 중 발견)

### 데스크탑: `gradle run`으로 테스트하면 워치독이 5초마다 무한 재시작하며 좀비 `java.exe` 프로세스가 200개 넘게 쌓임
- **경위**: Google 로그인 기능을 실제로 테스트하려고 `gradle run`으로 개발 빌드를 띄웠는데, 몇 분 뒤 시스템에 `java.exe` 프로세스가 수십~200개 넘게 계속 쌓이는 걸 발견.
- **원인**: `Watchdog.kt`의 `exeLauncherPath()`(현재 프로세스를 실행시킨 실행파일 경로를 구해서 재시작에 쓰는 함수)가 정상 배포 환경(`PhoneLockDesktop.exe`)에서는 그 exe 경로를 정확히 반환하지만, `gradle run`으로 실행하면 이 경로가 순수 `java.exe`가 된다. `startWatchdogSupervisor()`가 5초(`SUPERVISOR_POLL_MS`)마다 감시 프로세스 생사를 확인해서 없으면 `ProcessBuilder(exe, "--watchdog").start()`로 재기동하는데, 인자 없는 `java.exe --watchdog`는 실행되자마자 즉시 죽어서 감시 프로세스가 다시 "없음"으로 판정되고 → 5초 뒤 또 재시도 → 무한 루프.
- **해결**: 이 버그 자체는 코드를 고치지 않음(정상 배포 환경에선 발생하지 않고, `gradle run`은 개발자 전용 진입점이라 워치독이 자기 자신을 재시작할 이유가 없음) — 대신 **앞으로 데스크탑 실행/테스트는 항상 패키징된 `.exe`로만 할 것**을 원칙으로 확정. 발생한 좀비 프로세스는 `gradle --stop` + 모든 `java`/`javaw` 프로세스 강제 종료로 정리(0개로 유지되는 것 확인).
- 자세한 배경은 [[HANDOFF.md]]/[[CHANGELOG.md]] 61차 참고.

### 도구 사용 문제(앱 버그 아님): PowerShell 5.1이 BOM 없는 `.ps1`의 한글을 시스템 코드페이지로 잘못 읽어 스크립트가 조용히 깨짐
- **경위**: 데스크탑 트레이 아이콘 생성 스크립트(`packaging/generate_icon.ps1`)에 한글 주석 + 한글 하드코딩 절대경로를 넣고 실행했더니, 하드코딩 경로는 "Illegal characters in path" 예외로 바로 드러났지만(수정 후에도) 이후 이어지는 미리보기 PNG 저장 코드가 예외 하나 없이 그냥 결과가 `$null`이 되는 형태로 조용히 깨짐 — 같은 코드를 영문 전용 파일로 옮기면 정상 동작.
- **원인**: BOM 없이 저장된 UTF-8 `.ps1` 파일을 Windows PowerShell 5.1이 열 때, 시스템 기본 코드페이지(한국어 Windows는 CP949)로 잘못 해석 — 60차/이전 세션들이 겪은 "PowerShell이 데이터 파일을 잘못 인코딩해서 깨뜨리는" 문제와 같은 근본 원인이 이번엔 **Claude가 직접 작성한 `.ps1` 스크립트 자체**에서도 재현된 것.
- **해결**: `generate_icon.ps1`에서 한글 주석을 전부 영문으로 교체, 하드코딩 경로도 `$PSScriptRoot` 기반 상대경로로 교체.
- **재발 방지**: **이 프로젝트에서 새로 작성하는 `.ps1` 파일은 한글 텍스트(주석 포함)를 절대 넣지 않는다.** 경로도 하드코딩 대신 `$PSScriptRoot`나 상대경로를 쓴다. [[DECISIONS.md]] 60차(데이터 파일 관련)와는 별개로, 이번 건은 "스크립트 파일 자체"의 인코딩 문제라는 점에서 구분해서 기억할 것.

### 도구/환경 문제(앱 버그 아님): OneDrive 동기화 폴더에서 직접 Gradle 빌드하면 간헐적으로 빌드 실패
- **경위**: 이번 세션 여러 차례 `compileDebugKotlin`/`assembleDebug`/`compileKotlin`이 `mergeDebugResources`/`compileKotlin` 등에서 "Unable to delete directory"류 오류로 실패했다가, `build` 폴더를 지우고 재시도하면 대부분 성공.
- **원인**: 프로젝트 경로가 OneDrive 동기화 대상(`OneDrive\바탕 화면\...`)이라, Gradle이 `build/` 안에 수천 개의 임시 파일을 빠르게 생성/삭제하는 동안 OneDrive 클라이언트가 그중 일부를 동기화 목적으로 잠깐 잠가버려서 생기는 간헐적 충돌.
- **해결**: 59차 등 과거 세션에서 이미 안드로이드도 `AndroidBuilds\phone-lock-android`(OneDrive 밖 로컬 복사본)에서 빌드해왔다는 걸 재확인 — 이번 세션 후반부터 그 관행으로 복귀(robocopy로 소스만 반입 → 로컬에서 빌드 → 완성된 산출물만 OneDrive로 복사). 데스크탑은 처음부터 `C:\build\phone-lock-desktop`을 써서 이 문제가 없었음.
- **재발 방지**: **이 프로젝트에서 Gradle 빌드(특히 `assembleDebug`/`compileKotlin`류)는 항상 OneDrive 밖의 로컬 복사본에서 실행할 것** — 소스 수정 자체는 OneDrive 원본에서 하되, 빌드 직전 로컬로 robocopy, 빌드 후 최종 산출물만 다시 OneDrive로 복사.

---

## Fixed (2026-08-23, 60차 세션 — Claude 작업 중 발생/직접 복구)

### 데스크탑: `data.json`을 PowerShell `ConvertFrom-Json`/`ConvertTo-Json`으로 왕복 저장하면 파일이 거의 전부 유실됨
- **경위**: "그룹 모두 꺼줘" 요청을 처리하며 `%APPDATA%\PhoneLockDesktop\data.json`을 PowerShell로 파싱(`ConvertFrom-Json`) → `groupEnabled` 필드만 고쳐서 → 재직렬화(`ConvertTo-Json -Depth 100`)해서 저장했음. 결과물이 153,812바이트 → **1,214바이트**로 쪼그라들며 그룹 8개를 포함해 계산기/캘린더/루틴/공부기록 등 거의 모든 데이터가 통째로 사라짐 — 앱을 켜면 그룹 목록이 전부 안 보이는 것도 이 때문이었을 가능성이 높음(꺼짐 표시가 아니라 데이터 자체 소실).
- **원인**: Windows PowerShell 5.1의 `ConvertFrom-Json`/`ConvertTo-Json` 왕복은 중첩 배열(특히 원소 1개짜리 배열이 스칼라로 풀리는 등)과 깊은 구조를 안정적으로 보존하지 못하는 알려진 함정 — `-Depth 100`을 줘도 근본적으로 막히지 않음. `JsonStore.kt`의 `load()`가 파싱 실패 시 `AppData()`(빈 값)로 통째로 대체하는 구조라, 일부 구조가 깨지면 파일 전체가 사실상 초기화된 것처럼 보일 수 있음.
- **해결**: 편집 직전에 만들어둔 백업(`data.json.backup-20260823-171911`)으로 즉시 복원 후, 이번엔 JSON을 파싱하지 않고 `"groupEnabled": true,` → `"groupEnabled": false,` 8곳만 **순수 문자열 치환**으로 수정(치환 전후 개수 일치 확인 + 파일 크기 변화가 정확히 +8바이트인 것도 확인). 복원된 파일이 유효한 JSON인지도 재확인 완료.
- **재발 방지**: **앞으로 `data.json`처럼 앱이 직접 관리하는 저장 파일은 절대 PowerShell `ConvertFrom-Json`/`ConvertTo-Json` 왕복으로 통째로 재직렬화하지 말 것.** 특정 필드 값만 바꿀 땐 원본 텍스트 포맷을 그대로 보존하는 문자열/정규식 치환만 쓰고, 치환 전 항상 백업부터 만들 것. [[DECISIONS.md]] 60차 참고.
- **2차 여파(파일은 고쳤는데 화면엔 안 보임)**: `Repository`가 `data: AppData`를 앱 시작 시 딱 한 번만 `JsonStore.load()`로 읽어 메모리에 들고 있는 구조(`Repository.kt`)라, 데스크탑 앱이 corruption 직후(21:07경, 워치독이 자동 재기동한 것으로 추정) 이미 "그룹 없음" 상태로 메모리에 떠 있었던 상태에서 파일만 고쳐서는 화면에 반영 안 됨 — 사용자가 "안 돌아왔는데?"로 재보고해서 발견. `PhoneLockDesktop.exe` 프로세스 3개(메인+워치독 등)를 강제 종료(`Stop-Process -Force`, JVM 셧다운훅을 우회해 메모리의 stale 상태가 파일에 다시 덮어써지는 걸 방지)했더니 `Watchdog.kt`의 자체 감시 프로세스가 수 초 내 자동 재실행하며 고쳐진 파일을 정상적으로 다시 읽어들임(재시작 후 파일 mtime/내용 불변 확인 완료). **교훈**: 이 앱처럼 실행 중 메모리에 전체 상태를 캐싱하는 구조에서 데이터 파일을 외부에서 직접 수정했을 땐, 그 시점에 앱이 이미 실행 중이었는지 항상 확인하고 필요하면 재시작까지 시켜야 실제로 반영된다.

---

## In Progress (2026-08-21, 58차 세션 발견, 59차에 컴파일+배포 완료)

### 안드로이드/데스크탑: 루틴 스트릭 알림이 작동하지 않음(사용자 신고)
- **상태**: In Progress — 구조적 결함 하나를 발견해 수정, 59차에 컴파일 확인 + 실제 배포(APK 두 위치 + 데스크탑 표준 절차)까지 완료. 신고된 증상의 확정 원인인지는 여전히 실기기 로그로 검증 안 됨.
- **발견한 결함(데스크탑)**: `RoutineNotifier.tick()`이 `now.hour == dailyResetHour && now.minute == 0`처럼 "정확히 그 순간"만 비교했음 — 30초 주기 틱이 그 1분 창을 못 맞추거나(타이밍 드리프트), 그 순간 앱이 꺼져 있으면 그날은 영영 못 울리는 구조였음.
- **해결**: 발송 시각을 dailyResetHour 정각 고정에서 하루 중 랜덤 시각으로 바꾸면서(사용자 요청, item 4), 비교도 "목표 시각을 지났고 오늘 아직 안 보냈으면"(`>=`)으로 교체 — 안드로이드 `AlarmManager` 기반 폴백만큼 안정적인 구조로 변경. 안드로이드는 애초에 `AlarmManager` 예약이라 이 유형의 결함은 없었지만 랜덤화는 동일하게 적용.
- **다음 확인**: 실기기에서 스트릭 알림이 실제로 오는지 며칠 관찰 필요(빌드/배포는 59차에 끝났으니 이제 순수 실사용 검증만 남음). [[CHANGELOG.md]] 58~59차 참고.

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

## Fixed (문서 반영은 2026-08-15, 57차 세션 — 실제 코드 수정은 그 이전 어느 시점)

### 데스크탑: Alt-Tab으로 허용된 브라우저에 진입해서 허용 안 된 사이트를 실제로 이용 가능
- **원인 확정(35차 세션, 코드 조사)**: `background.js`의 차단 판정은 `chrome.webNavigation.onBeforeNavigate`(새 네비게이션 발생 시)에만 걸려있어, 공부 잠금이 켜지기 전부터 이미 로드돼 있던 차단 대상 사이트 탭으로 Alt-Tab만 해서 돌아오는 경우는 이 리스너가 발동하지 않았다. 유일한 백업은 1분 주기 tick 폴링이라 최악의 경우 최대 60초간 실제로 이용 가능했다.
- **해결**: `background.js`에 `chrome.tabs.onActivated`/`chrome.windows.onFocusChanged` 리스너(`checkTabNow()`)가 이미 추가되어 있음을 57차 세션에서 코드 확인 — 탭 전환·창 포커스 변경 시 즉시 재검사해서 지연을 사실상 없앤다. **파일 수정 시각이 2026-08-11(41차 전후)로 확인되는데 그 이후 세션들에서 BUGS.md/HANDOFF.md가 계속 "보류/미착수"로 잘못 기록돼 있었음 — 코드는 진작 고쳐졌으나 문서 갱신이 누락된 케이스**. 재발 방지: 코드를 수정한 세션은 반드시 그 세션 종료 절차에서 BUGS.md/HANDOFF.md를 그 자리에서 갱신할 것(작업 원칙 참고).
- **남은 것**: 브라우저 확장은 `chrome://extensions` 재로드가 필요(빌드 불필요) — 아직 재로드했는지 미확인이므로 실사용 확인은 사용자 몫.

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
