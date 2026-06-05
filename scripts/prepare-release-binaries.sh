#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
VERSION="${MACDROID_VERSION:-}"
if [ -z "$VERSION" ]; then
  VERSION="$(sed -n 's/.*static let number = "\(.*\)".*/\1/p' "$ROOT_DIR/Sources/MacDroidNotifyMac/AppVersion.swift")"
fi

RELEASE_DIR="${MACDROID_RELEASE_DIR:-$ROOT_DIR/artifacts/release-binaries}"
TEST_DIR="${MACDROID_TEST_BUILD_DIR:-$ROOT_DIR/artifacts/test-builds}"
ANDROID_SOURCE="$TEST_DIR/android/MacDroidNotify-debug.apk"
MAC_APP_SOURCE="$TEST_DIR/mac/MacDroid Notify.app"
ANDROID_DEST="$RELEASE_DIR/MacDroidNotify-android-$VERSION.apk"
MAC_ZIP_DEST="$RELEASE_DIR/MacDroidNotify-mac-$VERSION.zip"
CHECKSUM_DEST="$RELEASE_DIR/SHA256SUMS.txt"
DRY_RUN=0
SKIP_BUILD=0

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

case "$RELEASE_DIR" in
  "$ROOT_DIR"/artifacts/release-binaries | "$ROOT_DIR"/artifacts/release-binaries/*) ;;
  *)
    echo "Refusing to write release binaries outside $ROOT_DIR/artifacts/release-binaries: $RELEASE_DIR" >&2
    exit 1
    ;;
esac

print_plan() {
  echo "Version: $VERSION"
  echo "Release binary directory: $RELEASE_DIR"
  echo "Android APK: $ANDROID_DEST"
  echo "Mac app zip: $MAC_ZIP_DEST"
  echo "Checksums: $CHECKSUM_DEST"
}

if [ "$DRY_RUN" = "1" ]; then
  print_plan
  exit 0
fi

if [ "$SKIP_BUILD" != "1" ]; then
  JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}" "$ROOT_DIR/scripts/package-test-builds.sh"
fi

if [ ! -f "$ANDROID_SOURCE" ]; then
  echo "Android APK를 찾지 못했습니다: $ANDROID_SOURCE" >&2
  exit 1
fi
if [ ! -d "$MAC_APP_SOURCE" ]; then
  echo "Mac 앱 번들을 찾지 못했습니다: $MAC_APP_SOURCE" >&2
  exit 1
fi

rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

cp "$ANDROID_SOURCE" "$ANDROID_DEST"
COPYFILE_DISABLE=1 ditto -c -k --norsrc --keepParent "$MAC_APP_SOURCE" "$MAC_ZIP_DEST"

(
  cd "$RELEASE_DIR"
  shasum -a 256 "$(basename "$ANDROID_DEST")" "$(basename "$MAC_ZIP_DEST")" > "$CHECKSUM_DEST"
)

print_plan
echo "릴리즈 업로드용 바이너리 준비 완료: $RELEASE_DIR"
