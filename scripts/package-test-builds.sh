#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
ARTIFACT_DIR="${MACDROID_ARTIFACT_DIR:-$ROOT_DIR/artifacts/test-builds}"
ANDROID_DEST="$ARTIFACT_DIR/android/MacDroidNotify-debug.apk"
MAC_APP_DEST="$ARTIFACT_DIR/mac/MacDroid Notify.app"
MAC_ASSET_CATALOG="$ARTIFACT_DIR/mac/MacDroidNotify.xcassets"
MAC_ASSET_INFO="$ARTIFACT_DIR/mac/asset-info.plist"
MAC_EXECUTABLE="$ROOT_DIR/.build/arm64-apple-macosx/debug/MacDroidNotifyMac"
ANDROID_APK="$ROOT_DIR/android-app/build/outputs/apk/debug/android-app-debug.apk"
DRY_RUN=0

if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
fi

print_plan() {
  echo "Android APK: $ANDROID_APK -> $ANDROID_DEST"
  echo "Mac app: $MAC_EXECUTABLE -> $MAC_APP_DEST"
  echo "Guide: $ARTIFACT_DIR/README.txt"
}

if [ "$DRY_RUN" = "1" ]; then
  print_plan
  exit 0
fi

cd "$ROOT_DIR"

case "$ARTIFACT_DIR" in
  "$ROOT_DIR"/artifacts/test-builds | "$ROOT_DIR"/artifacts/test-builds/*) ;;
  *)
    echo "Refusing to replace artifact directory outside $ROOT_DIR/artifacts/test-builds: $ARTIFACT_DIR" >&2
    exit 1
    ;;
esac

SWIFTPM_CACHE_PATH="${SWIFTPM_CACHE_PATH:-/private/tmp/macdroid-swiftpm-cache}" \
CLANG_MODULE_CACHE_PATH="${CLANG_MODULE_CACHE_PATH:-/private/tmp/macdroid-clang-cache}" \
swift build

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}" ./gradlew testDebugUnitTest lintDebug assembleDebug

rm -rf "$ARTIFACT_DIR"
mkdir -p "$(dirname "$ANDROID_DEST")" "$MAC_APP_DEST/Contents/MacOS" "$MAC_APP_DEST/Contents/Resources"

cp "$ANDROID_APK" "$ANDROID_DEST"
cp "$MAC_EXECUTABLE" "$MAC_APP_DEST/Contents/MacOS/MacDroidNotifyMac"
chmod +x "$MAC_APP_DEST/Contents/MacOS/MacDroidNotifyMac"
CLANG_MODULE_CACHE_PATH="${CLANG_MODULE_CACHE_PATH:-/private/tmp/macdroid-clang-cache}" \
swift "$ROOT_DIR/scripts/render-mac-icon.swift" "$MAC_APP_DEST/Contents/Resources/MacDroidNotify.icns" "$MAC_ASSET_CATALOG/AppIcon.appiconset"
xcrun actool \
  --compile "$MAC_APP_DEST/Contents/Resources" \
  --platform macosx \
  --target-device mac \
  --minimum-deployment-target 15.0 \
  --app-icon AppIcon \
  --output-partial-info-plist "$MAC_ASSET_INFO" \
  "$MAC_ASSET_CATALOG" >/dev/null
rm -rf "$MAC_ASSET_CATALOG" "$MAC_ASSET_INFO"

cat > "$MAC_APP_DEST/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key>
  <string>MacDroidNotifyMac</string>
  <key>CFBundleIdentifier</key>
  <string>dev.svrx.macdroidnotify.app</string>
  <key>CFBundleName</key>
  <string>MacDroid Notify</string>
  <key>CFBundleDisplayName</key>
  <string>MacDroid Notify</string>
  <key>CFBundleIconFile</key>
  <string>MacDroidNotify</string>
  <key>CFBundleIconName</key>
  <string>AppIcon</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>0.2.1</string>
  <key>CFBundleVersion</key>
  <string>4</string>
  <key>LSMinimumSystemVersion</key>
  <string>15.0</string>
  <key>LSUIElement</key>
  <true/>
  <key>NSUserNotificationAlertStyle</key>
  <string>alert</string>
</dict>
</plist>
EOF
printf 'APPL????' > "$MAC_APP_DEST/Contents/PkgInfo"

plutil -lint "$MAC_APP_DEST/Contents/Info.plist" >/dev/null
codesign --force --deep --sign - "$MAC_APP_DEST"
codesign --verify --deep --strict "$MAC_APP_DEST"
LSREGISTER="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
if [ -x "$LSREGISTER" ]; then
  "$LSREGISTER" -f -r "$MAC_APP_DEST"
fi

cat > "$ARTIFACT_DIR/README.txt" <<EOF
MacDroid Notify 테스트 산출물

이 폴더는 실기기 테스트에 필요한 파일만 모아둔 폴더입니다.

1. Android APK
   - 파일: android/MacDroidNotify-debug.apk
   - 설치 예시:
     adb install -r android/MacDroidNotify-debug.apk

2. Mac 앱
   - 파일: mac/MacDroid Notify.app
   - Finder에서 실행하거나 터미널에서 다음처럼 실행할 수 있습니다:
     open "mac/MacDroid Notify.app"
   - 이 테스트 앱은 macOS 알림 등록을 위해 ad-hoc 코드서명되고 LaunchServices에 등록됩니다.

3. 테스트 순서
   - Mac 앱을 실행합니다.
   - 메뉴 막대에서 "페어링 QR 보기"를 눌러 QR 창을 엽니다.
   - Android 기기에서 APK를 설치하고 앱을 연 뒤 "QR로 페어링"으로 Mac QR을 스캔합니다.
   - Android 알림 접근 권한과 알림 표시 권한을 허용합니다.
   - Android 앱에서 "서비스 시작"을 누르고 상태가 "연결됨"으로 바뀌는지 확인합니다.
   - Mac 메뉴 막대에서 "Mac 알림 상태 확인"과 "Mac 테스트 알림 보내기"로 macOS 알림 권한을 확인합니다.
   - "핑 테스트"와 "테스트 알림 보내기"로 연결과 macOS 알림 표시를 확인합니다.

주의: 이 테스트 빌드는 같은 Wi-Fi 안에서만 동작하며, 0.2.x 보안 페어링은 Mac 자체 인증서 기반 TLS와 QR fingerprint pinning을 사용합니다.
EOF

print_plan
echo "테스트 산출물 준비 완료: $ARTIFACT_DIR"
