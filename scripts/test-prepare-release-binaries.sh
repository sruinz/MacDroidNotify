#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OUTPUT="$("$ROOT_DIR/scripts/prepare-release-binaries.sh" --dry-run)"
RELEASE_DIR="$ROOT_DIR/artifacts/release-binaries"

case "$OUTPUT" in
  *"MacDroidNotify-android-0.2.0.apk"* ) ;;
  *)
    echo "Expected versioned Android APK in dry-run output" >&2
    echo "$OUTPUT" >&2
    exit 1
    ;;
esac

case "$OUTPUT" in
  *"MacDroidNotify-mac-0.2.0.zip"* ) ;;
  *)
    echo "Expected versioned Mac app zip in dry-run output" >&2
    echo "$OUTPUT" >&2
    exit 1
    ;;
esac

"$ROOT_DIR/scripts/prepare-release-binaries.sh" --skip-build >/dev/null

if [ ! -f "$RELEASE_DIR/MacDroidNotify-android-0.2.0.apk" ]; then
  echo "Expected release Android APK" >&2
  exit 1
fi

if [ ! -f "$RELEASE_DIR/MacDroidNotify-mac-0.2.0.zip" ]; then
  echo "Expected release Mac app zip" >&2
  exit 1
fi

if [ ! -f "$RELEASE_DIR/SHA256SUMS.txt" ]; then
  echo "Expected release checksums" >&2
  exit 1
fi

if ! unzip -l "$RELEASE_DIR/MacDroidNotify-mac-0.2.0.zip" | grep "MacDroid Notify.app/Contents/Info.plist" >/dev/null; then
  echo "Expected Mac app bundle inside release zip" >&2
  exit 1
fi

if unzip -l "$RELEASE_DIR/MacDroidNotify-mac-0.2.0.zip" | grep '/\._\| \._' >/dev/null; then
  echo "Release Mac app zip must not contain AppleDouble metadata files" >&2
  exit 1
fi

echo "prepare-release-binaries OK"
