# VM 안 Claude를 위한 인수인계 문서

이 문서는 이 VM(`PhoneLockBuildVM`) 안에서 처음 실행되는 Claude Code 세션을 위해 작성됐다. 이전 대화 맥락은 전혀 공유되지 않으므로, 아래 내용을 처음부터 끝까지 읽고 시작할 것.

**주의: 이 문서는 관리앱 자체의 HANDOFF.md/CHANGELOG.md 등 5종 문서 체계와 다른 별개 문서다.** 이 파일을 그 5종 문서 규칙에 맞춰 갱신하거나 병합하지 말 것 — 이건 순수하게 "VM 격리 빌드 세션"을 위한 일회성 안내문이다.

---

## 1. 이 프로젝트가 뭔지

**갓생살기종합세트**(구 "관리앱")는 개인 자기통제(디지털 디톡스)용 앱/사이트 차단 시스템이다. 구성:
- `phone-lock-android`: Kotlin/Compose 안드로이드 앱
- `phone-lock-desktop`: **Kotlin/Compose Desktop 기반 Windows 앱 — 이번 VM 세션의 작업 대상**
- `phone-lock-desktop/browser-extension`: Chrome 확장(빌드 불필요, 소스만 존재)

지정한 앱/프로그램/사이트를 시간대·일일한도로 제한하고, 실행 확인(회유 절차)을 거치게 하는 게 핵심 기능이다. 자세한 기능 목록은 프로젝트 루트의 `HANDOFF.md`(공유 폴더로 읽기 전용 접근 가능)를 참고해도 되지만, 이번 작업엔 필수는 아니다.

## 2. 왜 이 VM이 존재하는지 (반드시 읽을 것)

호스트 PC(이 VM 밖의 실제 물리 PC)에서 `phone-lock-desktop`을 직접 빌드/테스트하면 두 가지 실제 위험이 있다:

1. **호스트 PC엔 이 앱의 실제 운영 인스턴스가 돌고 있고, `PhoneLockDesktopWatchdog`이라는 Windows 예약 작업이 앱이 꺼지면 자동으로 재실행시킨다.** 빌드 테스트 중 실수로 이 프로세스를 죽이거나 손상시키면 사용자 본인의 실제 자기통제 차단 시스템이 고장 난다.
2. **호스트의 실제 Firebase 프로덕션 데이터(캘린더/루틴/계산기 등)가 과거에 두 번 실제로 유실된 전적이 있다**(Room DB 마이그레이션 버그, 빈 데이터 push 사고). 빌드 검증 중 실수로 잘못된 데이터가 Firebase에 push되면 재발할 수 있다.

**그래서 이 VM은 호스트와 완전히 분리된 "일회용 빌드 검증 환경"이다.** 이 VM 안에서 프로세스를 자유롭게 죽이고 재시작해도 호스트에는 절대 영향이 없다.

## 3. 반드시 지킬 규칙

- **이 VM 안에 watchdog류 자동 재시작 메커니즘(예약 작업, 서비스 등록)을 절대 새로 만들지 말 것.** 호스트의 `PhoneLockDesktopWatchdog`을 흉내 내거나 복제하지 않는다 — 이 VM은 자유롭게 죽였다 켰다 할 수 있어야 의미가 있다.
- **Firebase에 데이터를 push하는 실사용 기능(캘린더 동기화, 루틴 동기화, 계산기 저장 등)을 실제로 눌러서 테스트하지 말 것.** `phone-lock-desktop` 소스에 Firebase 설정(RTDB URL 등)이 하드코딩돼 있어서, 이 VM에서 앱을 실행해도 **실제 프로덕션 Firebase에 연결된다**. 이번 라운드에선 별도 테스트용 Firebase 프로젝트나 네트워크 차단을 안 걸어뒀으므로(사용자 결정, 보류 상태), **컴파일 성공 + UI가 뜨는지 정도만 확인하고, 그룹/루틴/캘린더에 실제 값을 입력해서 저장하거나 동기화 버튼을 누르는 건 하지 말 것.**
- 공유 폴더 `\\VBOXSVR\gwanri-app`(호스트의 진짜 프로젝트 폴더, 읽기 전용으로 마운트됨)를 **직접 빌드 작업 디렉터리로 쓰지 말 것.** 반드시 로컬 디스크(예: `C:\build\gwanrieob`)로 통째로 복사한 뒤 그 사본에서 작업할 것 — 원본은 건드릴 수도 없지만(읽기 전용), 시도조차 하지 않는 게 안전하다.
- 이 VM 밖(호스트, 실제 배포, git push 등)으로 그 무엇도 자동으로 반영하지 말 것. 빌드 결과물(exe/msi/jar)만 만들어두면 되고, 그걸 실제 PC에 반영하는 건 **사용자가 직접, 이 VM 밖에서 판단**할 일이다.

## 4. 해야 할 일 (순서대로)

### 4.1 프로젝트 로컬 복사
1. `\\VBOXSVR\gwanri-app` 전체를 `C:\build\gwanrieob` 같은 로컬 경로로 복사(robocopy 또는 탐색기 복사).
2. 이번 세션에서 필요한 건 `phone-lock-desktop/` 폴더뿐이다(안드로이드는 이번 VM의 범위 밖 — 별도로 Docker/WSL 컨테이너에서 처리 중).

### 4.2 JDK 설치 (jpackage 포함 필수)
- `phone-lock-desktop`은 Kotlin JVM 프로젝트이고, Windows용 배포 실행파일(exe/msi)을 만들려면 **jpackage가 포함된 JDK**가 필요하다.
- 호스트 프로젝트의 과거 세션 기록(`project_build_toolchain_missing` 메모리)에 따르면 **Temurin JDK 21.0.12**(`OpenJDK21U-jdk_x64_windows_hotspot_21.0.12_8.zip`)를 써왔다. [adoptium.net](https://adoptium.net) 릴리스 페이지에서 이 버전(또는 최신 21.x, jpackage 포함 여부만 확인하면 버전 자체는 크게 안 중요)을 받아 압축 해제.
- 환경변수 `JAVA_HOME`을 이 JDK 경로로 설정.
- (참고: Android Studio가 이 VM에 없으므로 "Android Studio 내장 JBR" 같은 대체 경로는 없다 — 처음부터 jpackage 포함 JDK를 받는 게 맞다.)

### 4.3 Gradle 설치
- `phone-lock-desktop`엔 `gradlew`/`gradlew.bat` 래퍼가 리포에 없다(확인됨, 2026-08-15 기준). 시스템에 Gradle을 직접 설치해야 한다.
- `phone-lock-desktop/build.gradle.kts`가 요구하는 Gradle 버전은 **8.7**(과거 세션 기준). [gradle.org/releases](https://gradle.org/releases)에서 8.7 배포판(binary-only도 충분) 다운로드 → 압축 해제 → `bin` 폴더를 PATH에 추가하거나 절대경로로 직접 호출.
- Kotlin 플러그인 버전은 `1.9.24`(build.gradle.kts에서 확인됨) — Gradle 8.7과 JDK 21 조합이면 정상 동작해야 한다.

### 4.4 빌드
로컬로 복사한 `phone-lock-desktop` 폴더에서:
```
gradle compileKotlin
```
성공하면:
```
gradle createDistributable
```
`build/compose/binaries/main/app/` 아래에 실행 가능한 앱이 생성된다. (필요하면 `gradle packageMsi`로 설치파일까지 생성 가능하나, 이번 검증엔 `createDistributable`로 충분.)

### 4.5 실행 검증
- 생성된 실행파일을 직접 실행해서:
  - 트레이 아이콘이 뜨는지
  - 메인 창(그룹/공부/루틴/설정 탭)이 정상 렌더링되는지
  - 그룹 목록 화면이 에러 없이 뜨는지
- **위에서 금지한 대로, 실제 데이터 입력/저장/동기화 버튼은 누르지 말 것.** 화면이 뜨고 크래시가 없는지 정도만 확인.

### 4.6 완료 보고
- 컴파일 성공/실패 여부, 실행 시 크래시 여부, 화면 렌더링 이상 유무를 정리.
- 빌드 산출물 경로를 정리해서 사용자에게 알려줄 것 — **사용자가 직접 확인 후, 호스트 PC로 가져갈지 판단한다.** VM에서 호스트로 파일을 자동으로 복사하거나 배포 절차(watchdog 비활성화 등)를 대신 실행하지 말 것 — 그건 호스트 쪽 Claude 세션이 사용자 승인 하에 별도로 처리한다.

## 5. 안드로이드 빌드도 이 VM에서 진행한다 (변경됨)

원래는 안드로이드 빌드를 별도 Docker/WSL 컨테이너로 분리할 계획이었으나, **이미 이 VM이 완전 격리 환경이라 관리 포인트를 하나로 합치기로 결정했다.** 데스크탑 빌드(4장)를 끝낸 뒤 이어서 진행할 것.

무거운 Android Studio GUI 전체를 설치할 필요는 없다 — **커맨드라인 도구만으로 충분**하고 디스크 용량도 훨씬 적게 쓴다(VM 디스크가 60GB라 여유를 아끼는 게 좋음).

### 5.1 Android SDK 커맨드라인 도구 설치
1. [developer.android.com/studio#command-tools](https://developer.android.com/studio#command-tools)에서 "Command line tools only" (Windows) 다운로드 (약 150MB, 전체 Android Studio보다 훨씬 가벼움).
2. `C:\Android\cmdline-tools\latest\` 경로에 압축을 풀 것 (sdkmanager가 이 정확한 폴더 구조를 요구함 — `cmdline-tools\latest\bin\sdkmanager.bat`가 존재해야 함).
3. 환경변수 `ANDROID_HOME` (또는 `ANDROID_SDK_ROOT`)을 `C:\Android`로 설정.
4. `sdkmanager.bat`로 필요한 패키지 설치 (프로젝트가 요구하는 버전, `build.gradle.kts`에서 확인됨):
   ```
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```

### 5.2 빌드
1. 안드로이드 프로젝트도 `gradlew`/`gradlew.bat` 래퍼가 리포에 없다(확인됨) — 4.3에서 설치한 Gradle 8.7을 그대로 재사용.
2. `phone-lock-android/local.properties`는 호스트의 SDK 경로(`sdk.dir=C:\Users\sunae\AppData\Local\Android\Sdk`)가 들어있는 채로 복사돼 왔을 텐데, **이 VM의 SDK 경로로 덮어쓸 것**:
   ```
   sdk.dir=C:\\Android
   ```
   (이 파일은 `.gitignore`에 이미 포함돼 있어 호스트 원본에 되돌아갈 걱정은 없음 — 마음껏 고쳐도 된다.)
3. `JAVA_HOME`은 4.2에서 설치한 Temurin JDK 21을 그대로 재사용(안드로이드 빌드에 jpackage는 필요 없지만, JDK 21이면 AGP 8.5.2와 호환됨).
4. 로컬로 복사한 `phone-lock-android` 폴더에서:
   ```
   gradle assembleDebug
   ```
5. 성공하면 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됨.

### 5.3 검증
- `BUILD SUCCESSFUL` 확인이 전부다 — **이 VM엔 안드로이드 기기/에뮬레이터가 없으므로 실기기 설치·실행 검증은 할 수 없다.** 그건 범위 밖이다(호스트에서 사용자가 실제 폰에 설치해 확인할 몫).

---

## 6. 참고: 이번 VM 세션 범위 밖인 것

- 실제 배포(호스트의 watchdog 비활성화→프로세스 종료→robocopy→재실행 절차, 안드로이드 APK 두 위치 갱신) — 이건 항상 호스트에서, 사용자 승인 하에 이뤄진다.
- Firebase 데이터 관련 어떤 실사용 테스트도 — 3장 참고.
- 안드로이드 실기기/에뮬레이터 실행 검증 — 5.3 참고.
