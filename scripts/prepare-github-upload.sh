#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
VERSION="${MACDROID_VERSION:-}"
if [ -z "$VERSION" ]; then
  VERSION="$(sed -n 's/.*static let number = "\(.*\)".*/\1/p' "$ROOT_DIR/Sources/MacDroidNotifyMac/AppVersion.swift")"
fi

UPLOAD_ROOT="${MACDROID_GITHUB_UPLOAD_DIR:-$ROOT_DIR/artifacts/github-upload}"
DEST_DIR="$UPLOAD_ROOT/MacDroidNotify-$VERSION"
DRY_RUN=0

if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
fi

case "$UPLOAD_ROOT" in
  "$ROOT_DIR"/artifacts/github-upload | "$ROOT_DIR"/artifacts/github-upload/*) ;;
  *)
    echo "Refusing to write GitHub upload snapshot outside $ROOT_DIR/artifacts/github-upload: $UPLOAD_ROOT" >&2
    exit 1
    ;;
esac

if ! git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "GitHub upload snapshot requires a git working tree." >&2
  exit 1
fi

print_plan() {
  echo "Version: $VERSION"
  echo "GitHub source snapshot: $DEST_DIR"
  echo "Files copied from: git ls-files"
}

clean_macos_metadata() {
  find "$DEST_DIR" -name .DS_Store -type f -delete
}

if [ "$DRY_RUN" = "1" ]; then
  print_plan
  git -C "$ROOT_DIR" ls-files
  exit 0
fi

rm -rf "$DEST_DIR"
mkdir -p "$DEST_DIR"

git -C "$ROOT_DIR" ls-files | while IFS= read -r file; do
  target_dir="$DEST_DIR/$(dirname "$file")"
  mkdir -p "$target_dir"
  cp "$ROOT_DIR/$file" "$DEST_DIR/$file"
done

clean_macos_metadata

cat > "$UPLOAD_ROOT/README.txt" <<EOF
MacDroid Notify GitHub 업로드 스냅샷

이 폴더는 GitHub에 올릴 소스 파일만 따로 모아둔 결과입니다.

- 버전: $VERSION
- 업로드 대상 폴더: MacDroidNotify-$VERSION
- 복사 기준: 현재 저장소의 git 추적 파일 목록
- 제외 대상: .git, 빌드 결과물, APK, Mac .app, Android SDK, Gradle 캐시, local.properties

0.1.0을 GitHub에 단일 커밋으로 올리는 예:

cd "MacDroidNotify-$VERSION"
git init
git add .
git commit -m "Release $VERSION"

이후 버전도 개발 저장소의 전체 이력을 옮기지 않고, 새 스냅샷 결과만 GitHub 저장소에 반영해 커밋하면 됩니다.
EOF

clean_macos_metadata
print_plan
echo "GitHub 업로드 스냅샷 준비 완료: $DEST_DIR"
