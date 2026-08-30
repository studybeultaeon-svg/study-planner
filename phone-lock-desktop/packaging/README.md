# 폰컨트롤 데스크탑 — 설치 및 서비스 등록

## 1. 개발 중 바로 실행해보기 (설치 없이)
```
gradle run
```
트레이 아이콘이 생기고, 그룹에 등록한 프로그램을 켜면 확인/차단 창이 뜨는지 확인할 수 있습니다.

## 2. 설치 파일(exe/msi) 만들기
```
gradle packageMsi
```
`build/compose/binaries/main/msi/` 안에 설치 파일이 생성됩니다. 실행해서 설치하면 보통 `C:\Program Files\PhoneLockDesktop\PhoneLockDesktop.exe`에 설치됩니다.

## 3. Windows 서비스로 등록해서 삭제/종료 방지 걸기
1. [WinSW 릴리스 페이지](https://github.com/winsw/winsw/releases)에서 `WinSW-x64.exe`를 다운로드
2. 다운로드한 파일을 `PhoneLockDesktopService.exe`로 이름 변경
3. 이 폴더의 `winsw.xml`을 `PhoneLockDesktopService.xml`로 복사해서 `PhoneLockDesktopService.exe`와 같은 폴더에 두기
   - `winsw.xml`의 `<executable>` 경로가 실제 설치 경로와 다르면 수정
4. **관리자 권한** PowerShell을 열고 해당 폴더로 이동한 뒤:
   ```
   .\PhoneLockDesktopService.exe install
   .\PhoneLockDesktopService.exe start
   ```
5. 이제 `services.msc`에서 "PhoneLockDesktop" 서비스가 자동 시작으로 등록되어, 로그오프/재부팅에도 계속 실행되고 작업 관리자에서 그냥 꺼도 자동으로 다시 켜집니다.

## 서비스 중지/제거 (본인이 직접 끄고 싶을 때)
관리자 PowerShell에서:
```
.\PhoneLockDesktopService.exe stop
.\PhoneLockDesktopService.exe uninstall
```

## 4. 사이트 차단 켜기 (Chrome 확장프로그램)
데스크탑 앱은 프로그램(exe) 차단만 자체적으로 하고, 사이트(주소) 차단은 별도의 Chrome 확장프로그램이 담당합니다. 데스크탑 앱이 실행 중일 때(트레이 아이콘이 떠 있을 때)만 사이트 차단이 동작합니다.

1. Chrome 주소창에 `chrome://extensions` 입력
2. 우측 상단 **"개발자 모드"** 켜기
3. **"압축해제된 확장 프로그램을 로드"** 클릭 → 이 프로젝트의 `browser-extension` 폴더 선택
4. 그룹 편집 화면에서 "포함할 사이트" 섹션에 도메인(예: `youtube.com`)을 추가하면 그 사이트에 그룹의 한도/시간대/실행확인 규칙이 그대로 적용됩니다

### 사이트 차단 한계
- 데스크탑 앱과 확장프로그램은 `127.0.0.1`로만 통신하며, 앱이 꺼져 있으면 확장프로그램은 그냥 통과시킵니다(브라우징 자체를 막지는 않음)
- 사용시간 누적은 브라우저 알람 API의 최소 주기 제약으로 **1분 단위**로 체크합니다 (안드로이드/프로그램 차단보다 정밀도가 낮음)

## 한계
- 관리자 권한을 가진 사용자 본인이 위 명령으로 서비스를 직접 중지/삭제하는 것은 막을 수 없습니다 (안드로이드 버전에서 "강제종료 자체는 OS가 막고 있어 어떤 앱도 막을 수 없다"고 안내한 것과 같은 성격의 한계입니다).
