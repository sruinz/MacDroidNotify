#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
INSTALLED_APP="/Applications/MacDroid Notify.app"
TEST_APP="$ROOT_DIR/artifacts/test-builds/mac/MacDroid Notify.app"
APP_PATH=""
DRY_RUN=0
RESTART_SERVICES=1

if [ -d "$INSTALLED_APP" ]; then
  APP_PATH="$INSTALLED_APP"
else
  APP_PATH="$TEST_APP"
fi

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --no-restart-services) RESTART_SERVICES=0 ;;
    -*)
      echo "Unknown argument: $arg" >&2
      exit 2
      ;;
    *)
      APP_PATH="$arg"
      ;;
  esac
done

if [ ! -d "$APP_PATH" ]; then
  echo "Mac 앱 번들을 찾지 못했습니다: $APP_PATH" >&2
  exit 1
fi

PLIST="$APP_PATH/Contents/Info.plist"
ICON="$APP_PATH/Contents/Resources/MacDroidNotify.icns"
ASSETS_CAR="$APP_PATH/Contents/Resources/Assets.car"

plist_value() {
  /usr/libexec/PlistBuddy -c "Print :$1" "$PLIST" 2>/dev/null || echo "없음"
}

BUNDLE_ID="$(plist_value CFBundleIdentifier)"
ICON_FILE="$(plist_value CFBundleIconFile)"
ICON_NAME="$(plist_value CFBundleIconName)"

print_plan() {
  echo "MacDroid Notify 알림 아이콘 캐시 갱신"
  echo "앱 경로: $APP_PATH"
  echo "번들 ID: $BUNDLE_ID"
  echo "CFBundleIconFile: $ICON_FILE"
  echo "CFBundleIconName: $ICON_NAME"
  echo "아이콘 리소스: $ICON"
  echo "Assets.car: $ASSETS_CAR"
  echo "NotificationCenter/usernoted 재시작: $([ "$RESTART_SERVICES" = "1" ] && echo "예" || echo "아니오")"
}

if [ "$DRY_RUN" = "1" ]; then
  print_plan
  exit 0
fi

plutil -lint "$PLIST" >/dev/null
if [ ! -f "$ICON" ]; then
  echo "아이콘 리소스를 찾지 못했습니다: $ICON" >&2
  exit 1
fi
if [ "$ICON_NAME" != "없음" ] && [ ! -f "$ASSETS_CAR" ]; then
  echo "CFBundleIconName=$ICON_NAME 이지만 Assets.car를 찾지 못했습니다: $ASSETS_CAR" >&2
  exit 1
fi

touch "$APP_PATH"

LSREGISTER="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
if [ -x "$LSREGISTER" ]; then
  "$LSREGISTER" -u "$APP_PATH" >/dev/null 2>&1 || true
  "$LSREGISTER" -f -r "$APP_PATH" >/dev/null 2>&1 || echo "LaunchServices 재등록 경고: 수동 재실행 또는 로그아웃/로그인이 필요할 수 있습니다." >&2
fi

if command -v mdimport >/dev/null 2>&1; then
  mdimport "$APP_PATH" >/dev/null 2>&1 || true
fi

if [ "$RESTART_SERVICES" = "1" ]; then
  killall usernoted >/dev/null 2>&1 || true
  killall NotificationCenter >/dev/null 2>&1 || true
fi

print_plan
echo "MacDroid Notify 앱 아이콘 캐시 갱신 요청 완료: $APP_PATH"
echo "MacDroid Notify 앱을 종료 후 다시 실행하고 테스트 알림을 보내세요."
echo "알림 아이콘이 계속 공란이면 macOS 로그아웃/로그인 또는 번들 ID 변경 테스트가 필요합니다."
