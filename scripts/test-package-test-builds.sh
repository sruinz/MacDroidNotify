#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OUTPUT="$("$ROOT_DIR/scripts/package-test-builds.sh" --dry-run)"
MAC_APP="$ROOT_DIR/artifacts/test-builds/mac/MacDroid Notify.app"

case "$OUTPUT" in
  *"artifacts/test-builds/android/MacDroidNotify-debug.apk"*) ;;
  *)
    echo "Expected Android APK destination in dry-run output" >&2
    echo "$OUTPUT" >&2
    exit 1
    ;;
esac

case "$OUTPUT" in
  *"artifacts/test-builds/mac/MacDroid Notify.app"*) ;;
  *)
    echo "Expected Mac app destination in dry-run output" >&2
    echo "$OUTPUT" >&2
    exit 1
    ;;
esac

case "$OUTPUT" in
  *"README.txt"*) ;;
  *)
    echo "Expected Korean test README destination in dry-run output" >&2
    echo "$OUTPUT" >&2
    exit 1
    ;;
esac

if [ -d "$MAC_APP" ]; then
  BUNDLE_ID="$(/usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" "$MAC_APP/Contents/Info.plist")"
  ICON_FILE="$(/usr/libexec/PlistBuddy -c "Print :CFBundleIconFile" "$MAC_APP/Contents/Info.plist")"
  ICON_NAME="$(/usr/libexec/PlistBuddy -c "Print :CFBundleIconName" "$MAC_APP/Contents/Info.plist" 2>/dev/null || echo "없음")"
  if [ "$BUNDLE_ID" != "dev.svrx.macdroidnotify.app" ]; then
    echo "Expected CFBundleIdentifier=dev.svrx.macdroidnotify.app, got $BUNDLE_ID" >&2
    exit 1
  fi
  if [ "$ICON_FILE" != "MacDroidNotify" ]; then
    echo "Expected CFBundleIconFile=MacDroidNotify, got $ICON_FILE" >&2
    exit 1
  fi
  if [ "$ICON_NAME" != "AppIcon" ]; then
    echo "Expected CFBundleIconName=AppIcon, got $ICON_NAME" >&2
    exit 1
  fi
  if [ ! -f "$MAC_APP/Contents/Resources/MacDroidNotify.icns" ]; then
    echo "Expected MacDroidNotify.icns in Mac app bundle" >&2
    exit 1
  fi
  if [ ! -f "$MAC_APP/Contents/Resources/Assets.car" ]; then
    echo "Expected Assets.car with AppIcon in Mac app bundle" >&2
    exit 1
  fi
  if [ "$(cat "$MAC_APP/Contents/PkgInfo")" != "APPL????" ]; then
    echo "Expected Mac app PkgInfo=APPL????" >&2
    exit 1
  fi
fi

echo "package-test-builds dry-run OK"
