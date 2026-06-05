# MacDroid Notify

개인용 Android ↔ Mac 알림 및 클립보드 브리지입니다.

- 현재 버전: `0.2.0`
- Android 앱 ID: `dev.svrx.macdroidnotify`
- macOS 번들 ID: `dev.svrx.macdroidnotify.app`
- 라이선스: MIT

## 기능

- Android 알림을 macOS 로컬 알림으로 표시합니다.
- Mac 메뉴 막대에서 페어링 QR을 보여주고, Android 앱에서 QR 스캔으로 페어링 정보를 저장합니다.
- 0.2.0 보안 페어링은 Mac 자체 TLS 인증서 fingerprint를 QR에 담아 저장합니다.
- 같은 Wi-Fi에서 Mac IP나 포트가 바뀌어도 Bonjour/mDNS로 기존 Mac을 다시 찾습니다.
- 첫 페어링 성공 후 Mac 로그인 시 자동 실행을 기본으로 켜고, 메뉴에서 끌 수 있습니다.
- Android 앱을 다시 열면 이전에 켜둔 서비스가 자동으로 다시 연결을 시도합니다.
- Android 앱에서 연결 상태, 핑 RTT, 테스트 알림 전송 결과를 확인합니다.
- Mac 메뉴 막대에서 Mac 텍스트 클립보드를 Android로 보냅니다.
- Android 앱 또는 빠른 설정 타일에서 Android 텍스트 클립보드를 Mac으로 보냅니다.
- 같은 Wi-Fi 안에서 TLS가 적용된 TCP NDJSON 프로토콜과 랜덤 페어링 토큰을 사용합니다.

## 하지 않는 것

- ADB를 사용하지 않습니다.
- 화면 공유를 하지 않습니다.
- 클라우드 서버를 거치지 않습니다.
- 빠른 답장이나 알림 해제 동기화를 하지 않습니다.
- Android 클립보드를 백그라운드에서 자동 감시하지 않습니다.

## 사용 준비

- Mac과 Android 기기는 같은 Wi-Fi에 있어야 합니다.
- Mac에서는 MacDroid Notify 앱을 실행해 메뉴 막대 아이콘을 띄웁니다.
- Android에서는 MacDroid Notify 앱을 설치하고 알림 표시 권한을 허용합니다.
- Android 알림을 Mac으로 보내려면 Android 설정에서 `MacDroid Notify`의 알림 접근 권한을 허용해야 합니다.
- 안정적인 연결을 원하면 Android 배터리 설정에서 이 앱을 제한하지 않도록 설정합니다.
- 0.1.0에서 업데이트했다면 0.2.0 QR로 한 번 다시 페어링해야 합니다.

## 사용 방법

1. Mac 메뉴 막대에서 `페어링 QR 보기`를 엽니다.
2. Android 앱에서 `QR로 페어링`을 누르고 Mac의 QR을 스캔합니다.
3. Android 앱에서 `서비스 시작`을 누릅니다.
4. 상태가 `연결됨`으로 바뀌는지 확인합니다.
5. `핑 테스트`로 연결 상태를 확인합니다.
6. `테스트 알림 보내기`로 Mac 알림 표시가 되는지 확인합니다.
7. 이후 Android에 도착한 알림이 Mac 알림 센터에 표시됩니다.

현재 0.2.0은 개인 사용을 기준으로 만든 초기 버전입니다. 범용 스토어 배포는 제공하지 않습니다.

## 소스에서 직접 빌드

릴리즈에 첨부된 바이너리 대신 직접 APK와 Mac 앱을 만들 수 있습니다. 빌드는 macOS에서 실행하는 것을 기준으로 합니다.

필요한 도구:

- Swift 빌드와 `actool` 실행이 가능한 Xcode
- JDK 17
- `curl`, `unzip`

Xcode를 처음 설치한 환경에서 `actool`이 초기화 오류를 내면 한 번만 다음 명령을 실행합니다.

```sh
xcodebuild -runFirstLaunch
```

처음 한 번 Android CLI SDK를 준비합니다. Homebrew로 설치한 OpenJDK를 사용한다면 다음처럼 실행할 수 있습니다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk scripts/setup-android-sdk.sh
```

그다음 Android APK와 Mac 앱을 한 번에 빌드합니다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk scripts/package-test-builds.sh
```

빌드 결과:

```text
artifacts/test-builds/
├── android/
│   └── MacDroidNotify-debug.apk
└── mac/
    └── MacDroid Notify.app
```

이 빌드는 개인 사용과 직접 테스트를 위한 debug APK 및 ad-hoc 서명 Mac 앱을 만듭니다. APK 설치 시 Android의 `출처를 알 수 없는 앱` 경고가 나올 수 있고, Mac 앱은 공증된 배포 앱이 아닙니다.

GitHub Release에 올리기 쉬운 파일명으로 APK와 Mac 앱 zip을 모으려면 다음을 실행합니다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk scripts/prepare-release-binaries.sh
```

결과는 `artifacts/release-binaries/`에 생성됩니다.

```text
MacDroidNotify-android-0.2.0.apk
MacDroidNotify-mac-0.2.0.zip
SHA256SUMS.txt
```

## Android 상시 알림

Android foreground service는 실행 중인 동안 상태 알림이 필요합니다. 이 알림을 완전히 숨기면 시스템이 더 큰 백그라운드 실행 경고를 표시할 수 있으므로, 앱은 `조용한 연결 상태` 알림 채널을 사용해 소리, 진동, 배지를 끄고 낮은 중요도로 표시합니다.

Android 앱의 `상시 알림 설정` 버튼에서 이 채널 설정을 바로 열 수 있습니다. One UI에서 더 줄이고 싶다면 해당 채널을 무음/최소화로 두면 됩니다.

## 클립보드 사용

- Mac → Android: Mac에서 텍스트를 복사한 뒤 메뉴 막대의 `Mac 클립보드를 Android로 보내기`를 누르고, Android에 뜬 `Mac 클립보드 수신됨` 알림을 탭합니다.
- Android → Mac: Android 앱의 `Android 클립보드를 Mac으로 보내기` 버튼을 누르거나 빠른 설정 타일을 추가해 사용합니다. 앱이 화면 포커스를 얻은 뒤 클립보드를 읽고, 자동 읽기가 막히면 열린 입력칸에 붙여넣은 뒤 보냅니다.
- Android 10 이상은 포커스 없는 앱의 클립보드 읽기를 제한하므로, 클립보드 자동 감시는 하지 않습니다.

## 연결 확인

- Mac의 QR 창에는 현재 Mac 리스너 상태와 페어링 URL이 함께 표시됩니다.
- Mac 메뉴의 `포트 변경...`에서 리스너 포트를 바꿀 수 있습니다. 포트를 바꾸면 QR과 mDNS 광고 포트가 함께 바뀌며, Android는 가능한 경우 mDNS로 새 포트를 자동 탐색합니다.
- Mac 메뉴의 `로그인 시 자동 실행`에서 로그인 항목 상태를 켜거나 끌 수 있습니다.
- Mac 메뉴의 `Mac 알림 상태 확인`은 macOS 알림 권한, 배너/소리/알림 센터 설정, 실행 번들 경로를 보여줍니다.
- Mac 메뉴의 `Mac 테스트 알림 보내기`는 Android 없이 macOS 알림 표시만 따로 검증합니다.
- Android는 `hello`를 보낸 직후가 아니라 Mac의 `pairing.accepted`를 받은 뒤에만 `연결됨`으로 표시합니다.
- `핑 테스트`는 payload가 있는 `ping`/`pong` 메시지로 왕복 시간을 표시합니다.
- `테스트 알림 보내기`는 실제 알림 미러링과 같은 `notification.posted` 경로를 사용합니다.
- Android와 Mac의 `디버그 로그 복사`는 최근 서비스/네트워크 이벤트와 상태를 복사합니다. 페어링 토큰은 원문 대신 마스킹되어 포함됩니다.

## 보안 메모

이 앱은 개인용 같은 Wi-Fi 환경을 전제로 합니다. 0.2.0부터 Mac 자체 서명 인증서와 QR에 포함된 SHA-256 fingerprint pinning으로 TLS 연결을 검증하고, TLS 연결 뒤에 페어링 토큰 HMAC 인증을 한 번 더 수행합니다. 공인 CA 인증서나 클라우드 서버는 사용하지 않습니다.

## 라이선스

MIT 라이선스를 사용합니다. 자세한 내용은 `LICENSE` 파일을 확인하세요.
