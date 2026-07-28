# HANDOFF — Study Planner 프로젝트

## 0. 최신 세션 요약 (코드 리뷰 기반 개선 작업)

이번 세션에서 전체 코드(index.html, 3172줄)를 심층 리뷰한 뒤, 계획한 항목을 전부 구현 완료했다.

**버그 수정**
- `undoMarkStatus`가 커스텀 `nextDays`를 무시하고 항상 기본 간격(1/3/7일)으로 다음 회독 항목을 찾던 버그 수정 → 완료 취소 시 정확한 날짜의 항목이 삭제됨
- 계산기 화면에서 브라우저 리사이즈(데스크탑↔모바일 폭 전환) 시 사이드바/결과 영역이 모두 사라지던 버그 수정 → `resize` 리스너 추가 (`initCalcMobileState`)

**성능 개선**
- `autoSaveDraft`를 200ms 디바운스로 분리(`autoSaveDraftNow`) — 매 keystroke마다 전체 카드 순회 + localStorage 쓰기가 발생하던 문제 완화
- 캘린더 "🧹 정리" 버튼 추가 — 6개월 이전 오래된 일정을 사용자 확인 후 일괄 삭제 (`confirmArchiveOldCalTasks`)

**코드 정리**
- `.task-name-modal`의 죽은 CSS 선언(`font-size:4px`) 제거
- Firebase 계산기 동기화의 저장목록/드래프트 LWW 비교 로직을 `applyIfNewer()` 공통 헬퍼로 통일 (폴더트리/폴더순서는 특수 분기가 많아 유지)
- localStorage 읽기/쓰기 실패 시 조용히 무시하던 catch에 `showToast` 알림 추가
- `saveFirebaseConfig`의 `alert()` → `showToast`, `removeCalcTask`의 `confirm()` → `openConfirm` 커스텀 모달로 통일 (토스트가 모달 위에도 보이도록 `#toast` z-index 999→2100 상향)
- 저장 항목 메타(`saved-item-meta`)의 `qty`/요일별 목표값에도 `esc()` 일관 적용

**신규 기능**
- 휴일 제외 UI 완성: task-card에 "휴일 제외 날짜" 입력 필드 추가, `calculate()`가 실제로 반영(이전엔 `holidays=[]` 하드코딩으로 죽어있던 기능). 드래프트 저장(`autoSaveDraftNow`)에도 `holidays` 필드 영속화 추가
- 계산 결과 "📋 결과 복사" 버튼 — 클립보드 복사(Clipboard API + execCommand 폴백)
- "📈 통계" 탭 신규 추가 — 전체/완료/완료율/연속 완료일(스트릭), 회독 단계별 완료 현황, 최근 30일 막대 그래프 (`renderStats()`)

**검증**: 브라우저 프리뷰에서 JS 문법 오류 없음 확인, undo 버그 재현 테스트로 수정 검증, 리사이즈 이벤트 강제 발생시켜 레이아웃 복구 확인, 홀리데이 입력→계산→저장 전체 흐름 확인, 통계 뷰 집계 로직 검증. 전체 페이지 새로고침 후 드래프트(휴일 필드 포함) 정상 복원 확인.

**의도적으로 보류한 항목** (계획서에서 우선순위 "하"로 분류했던 것들)
- Firebase Realtime Database push를 `.set()` 전체 전송 대신 부분 `.update()`로 바꾸는 것은 동기화 프로토콜 자체를 건드리는 위험도가 높아 보류 — 대신 "정리" 기능으로 데이터 크기 자체를 억제하는 방향을 택함
- `prompt()` 네이티브 다이얼로그(새 폴더 이름 입력 등)는 텍스트 입력용 커스텀 모달이 없어 그대로 둠 — 별도 입력 모달 컴포넌트를 새로 만드는 건 과도한 리팩토링으로 판단
- Firebase API Key 저장 방식(localStorage 평문)은 설계상 정상 범위로 판단해 미조치

---

## 1. 프로젝트 목적

**이 프로젝트가 무엇인지**
- 단일 HTML 파일(`index.html`) 기반의 학습 플래너 웹앱
- Firebase Realtime Database(compat SDK)로 데이터 동기화
- 계산기(할당량 계산), 캘린더(일정 관리), 일정표(timetable) 세 가지 기능으로 구성

**현재 목표**
- 모바일 UX 개선 (캘린더 모달 디자인, 버튼 크기, 글자 가독성)
- 계산기 ↔ 캘린더 연동 (linkedCalc + progressStep 기반 달성 여부 표시)

---

## 2. 현재까지 완료한 작업

### 이번 세션까지 구현된 기능
- **시간표 → 일정표** 이름 변경 전체 적용
- **일정표 달성 체크 로직 개선**: `isCalTaskLinkedDone()` — `linkedCalc === calcTaskName` && `progressStep` 합계 ≥ 일일 할당량
- **일정표 타임존 버그 수정**: `toISOString()` 대신 `calDateKey()`로 로컬 날짜 사용
- **모바일 캘린더 셀**: 일정 미리보기 제거 → 개수 뱃지만 표시 (기본/미완료 빨간색, 부분 노란색, 완료 초록색)
- **캘린더 날짜 모달 → 모바일 바텀시트** 리디자인
- **모달 닫기 버튼** `flex: none !important`로 크기 고정
- **폴더 플리커 버그 수정**: 클릭 시 DOM 직접 토글, 전체 재렌더 방지
- **결과 블록 접기 동기화**: 계산 시점에만 task card와 동기화 (이후 독립 동작)
- **모두 접기/펴기 버튼** 분리: 입력 패널과 결과 패널 각각 독립
- **삭제 확인 다이얼로그** 추가
- **모든 업무 저장** 버튼 추가
- **모바일 결과탭 topbar** 레이아웃 수정
- **일정표 모바일**: 달성 텍스트 제거, 체크 표시만
- **캘린더 모달 컴팩트화** (이번 세션 주요 작업):
  - 순서 버튼(▲▼) 전체 축소 (9px, 모바일 전용)
  - 일정 이름 폰트 축소 + 줄바꿈 (모바일 4px + `white-space:normal`)
  - 데스크탑도 이름 줄바꿈 적용 (13px 유지)
  - 진척도 입력칸(`step-input-modal`) 축소 + 스피너 제거 + 가운데 정렬
  - 완료/미완료/이동/복사/삭제 버튼 → 9px, 3열 wrap (모바일)
  - 할당량 연동 추가 버튼(`modal-section-toggle`) 폰트 축소 (모바일 9px)
  - 모바일 task-item 수직 정렬 `center`로 통일 (줄바꿈 칸과 비줄바꿈 칸 대칭)
  - **데스크탑/모바일 분리**: 모든 크기 축소는 `@media (max-width: 640px)` 안에만 적용

---

## 3. 현재 프로젝트 상태

**정상 작동하는 부분**
- 계산기 전체 기능 (할당량 계산, 저장/불러오기, 폴더 관리)
- 캘린더 전체 기능 (일정 추가/수정/삭제, 이동/복사, 연동 추가)
- 일정표 달성 체크 (linkedCalc + progressStep 기반)
- 모바일 바텀시트 모달
- Firebase 데이터 동기화

**확인이 필요한 부분**
- 모바일에서 4px 폰트 일정 이름 가독성 (사용자 확인 중)
- 모바일 완료/미완료/이동/복사/삭제 3열 버튼 레이아웃 실제 기기 확인

**현재 발생 중인 오류/문제**
- 없음 (마지막 push 기준 정상)

---

## 4. 변경한 파일 목록

| 파일 | 변경 내용 |
|------|-----------|
| `index.html` | 전체 앱 — CSS 및 JS 수정 (단일 파일 앱) |

### 이번 세션 주요 CSS 변경 위치
- **전역 (데스크탑 포함)**
  - `.task-name-modal`: `white-space:normal; word-break:break-word` (줄바꿈)
  - `.step-input-modal`: `width:40px; text-align:center; 스피너 제거`
- **`@media (max-width: 640px)` 내부**
  - `.order-btn`, `.task-name-modal`, `.task-item-modal`, `.next-days-btn`
  - `.modal-section-toggle`, `.modal-actions`, `.modal-btn`
  - `.cal-link-btn`, `.step-input-modal`
- **JS**: `stepInput`에 `class='step-input-modal'` 추가 (인라인 스타일 최소화)

---

## 5. 아직 남은 작업

현재 명시적으로 남은 요청 없음. 사용자가 확인 후 추가 피드백 예정.

**잠재적 개선 가능 항목**
- 모바일 일정 이름 4px는 너무 작을 수 있음 → 사용자 피드백 대기
- 모바일 바텀시트 drag-to-dismiss 기능 (아직 미구현)

---

## 6. 다음 세션에서 바로 시작할 작업

**가장 먼저 할 일**
- 사용자가 모바일 확인 후 추가 피드백을 주면 그에 맞게 수정

**주의할 점**
- **절대 `git push` 먼저 하지 말 것** — 사용자가 "올려"라고 명시할 때만 push
- 크기/스타일 변경 시 데스크탑은 `@media` 밖(전역), 모바일 전용은 `@media (max-width: 640px)` 안
- `step-input-modal`은 JS에서 `className`으로 클래스 부여 — 인라인 스타일로 `width/font-size` 지정하면 미디어쿼리 override가 안 됨
- Firebase push는 항상 `origin master:main`
- `calDateKey(y, m, d)`는 0-based month 사용 (m+1 처리 내부에서 함)
