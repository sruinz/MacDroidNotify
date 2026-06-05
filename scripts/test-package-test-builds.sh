#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OUTPUT="$("$ROOT_DIR/scripts/package-test-builds.sh" --dry-run)"

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

echo "package-test-builds dry-run OK"
