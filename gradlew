#!/usr/bin/env sh
set -eu

GRADLE_VERSION="9.4.1"
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
GRADLE_HOME="$ROOT_DIR/.gradle/gradle-$GRADLE_VERSION"
GRADLE_ZIP="$ROOT_DIR/.gradle/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$ROOT_DIR/.gradle"
  curl -L -o "$GRADLE_ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  unzip -q "$GRADLE_ZIP" -d "$ROOT_DIR/.gradle"
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
