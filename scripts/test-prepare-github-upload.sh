#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OUTPUT="$("$ROOT_DIR/scripts/prepare-github-upload.sh" --dry-run)"
SNAPSHOT_DIR="$ROOT_DIR/artifacts/github-upload/MacDroidNotify-0.1.0"

case "$OUTPUT" in
  *"GitHub source snapshot:"*"artifacts/github-upload/MacDroidNotify-0.1.0"*) ;;
  *)
    echo "Expected GitHub upload destination in dry-run output" >&2
    echo "$OUTPUT" >&2
    exit 1
    ;;
esac

for expected in ".github/FUNDING.yml" ".gitignore" "LICENSE" "Package.swift" "README.md"; do
  case "$OUTPUT" in
    *"$expected"*) ;;
    *)
      echo "Expected tracked root file in dry-run output: $expected" >&2
      echo "$OUTPUT" >&2
      exit 1
      ;;
  esac
done

case "$OUTPUT" in
  *"local.properties"* | *"artifacts/test-builds"* | *".android-sdk"*)
    echo "Dry-run output included local or generated files" >&2
    echo "$OUTPUT" >&2
    exit 1
    ;;
  *) ;;
esac

echo "prepare-github-upload dry-run OK"

"$ROOT_DIR/scripts/prepare-github-upload.sh" >/dev/null

if [ ! -f "$SNAPSHOT_DIR/LICENSE" ]; then
  echo "Expected LICENSE in generated GitHub snapshot" >&2
  exit 1
fi

if [ ! -f "$SNAPSHOT_DIR/.github/FUNDING.yml" ]; then
  echo "Expected .github/FUNDING.yml in generated GitHub snapshot" >&2
  exit 1
fi

if find "$SNAPSHOT_DIR" -name .DS_Store -type f | grep . >/dev/null; then
  echo "Generated GitHub snapshot must not contain .DS_Store" >&2
  exit 1
fi

if find "$SNAPSHOT_DIR" -name local.properties -o -name .git -o -name .android-sdk -o -name '*.apk' -o -name '*.app' | grep . >/dev/null; then
  echo "Generated GitHub snapshot included local or generated files" >&2
  exit 1
fi

echo "prepare-github-upload generated snapshot OK"
