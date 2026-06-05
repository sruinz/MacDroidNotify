# MacDroid Notify

개인용 Android ↔ Mac mini 알림 및 클립보드 브리지입니다.

- 현재 버전: `0.1.0`
- Android 앱 ID: `dev.svrx.macdroidnotify`
- macOS 번들 ID: `dev.svrx.macdroidnotify.mac`
- 라이선스: MIT

## 기능

- Android 알림을 macOS 로컬 알림으로 표시합니다.
- Mac 메뉴 막대에서 페어링 QR을 보여주고, Android 앱에서 QR 스캔으로 페어링 정보를 저장합니다.
- Android 앱에서 연결 상태, 핑 RTT, 테스트 알림 전송 결과를 확인합니다.
- Mac 메뉴 막대에서 Mac 텍스트 클립보드를 Android로 보냅니다.
- Android 앱 또는 빠른 설정 타일에서 Android 텍스트 클립보드를 Mac으로 보냅니다.
- 같은 Wi-Fi 안에서 TCP NDJSON 프로토콜과 랜덤 페어링 토큰을 사용합니다.

## 하지 않는 것

- ADB를 사용하지 않습니다.
- 화면 공유를 하지 않습니다.
- 클라우드 서버를 거치지 않습니다.
- 빠른 답장이나 알림 해제 동기화를 하지 않습니다.
- Android 클립보드를 백그라운드에서 자동 감시하지 않습니다.

## 테스트 산출물 만들기

실기기 테스트에 필요한 APK와 Mac 앱은 아래 명령으로 한 폴더에 모읍니다.

```sh
scripts/package-test-builds.sh
```

생성 위치:

```text
artifacts/test-builds/
├── README.txt
├── android/
│   └── MacDroidNotify-debug.apk
└── mac/
    └── MacDroid Notify.app
```

`artifacts/test-builds/`는 테스트용 빌드 결과물이므로 git에 커밋하지 않습니다.

패키징 전에 어떤 파일을 만들지 확인하려면:

```sh
scripts/package-test-builds.sh --dry-run
```

## 공개 업로드 스냅샷 만들기

개발용 로컬 Git 이력을 그대로 옮기지 않고, GitHub에 올릴 소스 파일만 별도 폴더로 모읍니다.

```sh
scripts/prepare-github-upload.sh
```

생성 위치:

```text
artifacts/github-upload/
├── README.txt
└── MacDroidNotify-0.1.0/
```

`MacDroidNotify-0.1.0/`에는 현재 git이 추적하는 소스, 설정, 테스트, 스크립트, `LICENSE`만 들어갑니다. `.git`, 빌드 결과물, APK, Mac `.app`, Android SDK, Gradle 캐시, `local.properties`는 포함하지 않습니다.

0.1.0을 GitHub에 단일 커밋으로 올릴 때는 이 폴더 안에서 새 git 저장소를 만들면 됩니다.

```sh
cd artifacts/github-upload/MacDroidNotify-0.1.0
git init
git add .
git commit -m "Release 0.1.0"
```

## Android SDK 준비

처음 한 번만 Android CLI SDK를 설치합니다.

```sh
scripts/setup-android-sdk.sh
```

현재 환경에서 `JAVA_HOME`이 비어 있거나 잘못되어 있으면 다음처럼 실행합니다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk scripts/setup-android-sdk.sh
```

## 개별 빌드

macOS 앱만 빌드:

```sh
SWIFTPM_CACHE_PATH=/private/tmp/macdroid-swiftpm-cache \
CLANG_MODULE_CACHE_PATH=/private/tmp/macdroid-clang-cache \
swift build
```

macOS 앱을 SwiftPM에서 바로 실행:

```sh
SWIFTPM_CACHE_PATH=/private/tmp/macdroid-swiftpm-cache \
CLANG_MODULE_CACHE_PATH=/private/tmp/macdroid-clang-cache \
swift run MacDroidNotifyMac
```

Android 앱 검증 및 APK 빌드:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug
```

테스트용 Mac 앱은 `scripts/package-test-builds.sh`가 `.app` 번들을 만든 뒤 ad-hoc 코드서명을 적용합니다. 이 서명이 있어야 macOS 알림 설정에 `dev.svrx.macdroidnotify.mac` 번들로 등록됩니다.

## Android 상시 알림

Android foreground service는 실행 중인 동안 상태 알림이 필요합니다. 이 알림을 완전히 숨기면 시스템이 더 큰 백그라운드 실행 경고를 표시할 수 있으므로, 앱은 `조용한 연결 상태` 알림 채널을 사용해 소리, 진동, 배지를 끄고 낮은 중요도로 표시합니다.

Android 앱의 `상시 알림 설정` 버튼에서 이 채널 설정을 바로 열 수 있습니다. One UI에서 더 줄이고 싶다면 해당 채널을 무음/최소화로 두면 됩니다.

## Android 실기기 테스트 순서

1. `scripts/package-test-builds.sh`를 실행합니다.
2. Mac에서 `artifacts/test-builds/mac/MacDroid Notify.app`을 실행합니다.
3. Mac 메뉴 막대에서 `페어링 QR 보기`를 눌러 QR 창을 엽니다.
4. Android 기기에 `artifacts/test-builds/android/MacDroidNotify-debug.apk`를 설치합니다.
5. Android 앱에서 `QR로 페어링`을 누르고 Mac의 QR을 스캔합니다.
6. 상태 카드에 `페어링 정보 저장됨`과 Mac IP/포트가 보이는지 확인합니다.
7. Android 앱에서 `서비스 시작`을 누르고 알림 표시 권한을 허용합니다.
8. `알림 접근 설정 열기`에서 `MacDroid Notify` 알림 접근 권한을 허용합니다.
9. One UI 설정에서 이 앱의 배터리 사용량을 `제한 없음`으로 둡니다.
10. 상태 카드가 `연결됨`으로 바뀌는지 확인합니다.
11. `핑 테스트`를 눌러 `핑 RTT: ...ms`가 표시되는지 확인합니다.
12. Mac 메뉴 막대의 `Mac 알림 상태 확인`에서 권한이 `허용됨`인지 확인합니다.
13. Mac 메뉴 막대의 `Mac 테스트 알림 보내기`로 macOS 알림 표시를 먼저 확인합니다.
14. Android의 `테스트 알림 보내기`를 눌러 Mac 알림이 뜨는지 확인합니다.
15. 실패가 반복되면 `디버그 로그 복사`를 눌러 복사된 내용을 이 작업 스레드에 붙여넣습니다.

debug APK 설치 중 `출처를 알 수 없는 앱` 경고가 나오는 것은 개인용 sideload APK에서 정상입니다. 이번 MVP에서는 배포 서명이나 스토어 배포를 하지 않습니다.

## 클립보드 테스트

- Mac → Android: Mac에서 텍스트를 복사한 뒤 메뉴 막대의 `Mac 클립보드를 Android로 보내기`를 누르고, Android에 뜬 `Mac 클립보드 수신됨` 알림을 탭합니다.
- Android → Mac: Android 앱의 `Android 클립보드를 Mac으로 보내기` 버튼을 누르거나 빠른 설정 타일을 추가해 사용합니다. 앱이 화면 포커스를 얻은 뒤 클립보드를 읽고, 자동 읽기가 막히면 열린 입력칸에 붙여넣은 뒤 보냅니다.
- Android 10 이상은 포커스 없는 앱의 클립보드 읽기를 제한하므로, 클립보드 자동 감시는 하지 않습니다.

## 현재 연결 확인 흐름

- Mac의 QR 창에는 현재 Mac 리스너 상태와 페어링 URL이 함께 표시됩니다.
- Mac 메뉴의 `포트 변경...`에서 리스너 포트를 바꿀 수 있습니다. 포트를 바꾸면 Mac 리스너가 재시작되고 Android 앱에서 새 QR로 다시 페어링해야 합니다.
- Mac 메뉴의 `Mac 알림 상태 확인`은 macOS 알림 권한, 배너/소리/알림 센터 설정, 실행 번들 경로를 보여줍니다.
- Mac 메뉴의 `Mac 테스트 알림 보내기`는 Android 없이 macOS 알림 표시만 따로 검증합니다.
- Android는 `hello`를 보낸 직후가 아니라 Mac의 `pairing.accepted`를 받은 뒤에만 `연결됨`으로 표시합니다.
- `핑 테스트`는 payload가 있는 `ping`/`pong` 메시지로 왕복 시간을 표시합니다.
- `테스트 알림 보내기`는 실제 알림 미러링과 같은 `notification.posted` 경로를 사용합니다.
- `디버그 로그 복사`는 최근 서비스/네트워크 이벤트와 상태를 복사합니다. 페어링 토큰은 원문 대신 마스킹되어 포함됩니다.

## 보안 메모

이 MVP는 개인용 같은 Wi-Fi 환경을 전제로 합니다. 페어링 토큰으로 인증하지만 TLS는 사용하지 않으므로, 알림과 클립보드 내용은 로컬 네트워크 안에서 평문으로 전송됩니다.

## 라이선스

MIT 라이선스를 사용합니다. 자세한 내용은 `LICENSE` 파일을 확인하세요.
