#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-$ROOT_DIR/.android-sdk}"
TOOLS_URL="${ANDROID_COMMANDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip}"
TOOLS_ZIP="$ROOT_DIR/.gradle/android-commandline-tools.zip"
TOOLS_DIR="$SDK_ROOT/cmdline-tools/latest"

mkdir -p "$SDK_ROOT/cmdline-tools" "$ROOT_DIR/.gradle"

if [ ! -x "$TOOLS_DIR/bin/sdkmanager" ]; then
  curl -L -o "$TOOLS_ZIP" "$TOOLS_URL"
  rm -rf "$SDK_ROOT/cmdline-tools/latest" "$SDK_ROOT/cmdline-tools/cmdline-tools"
  unzip -q "$TOOLS_ZIP" -d "$SDK_ROOT/cmdline-tools"
  mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$TOOLS_DIR"
fi

yes | "$TOOLS_DIR/bin/sdkmanager" --sdk_root="$SDK_ROOT" --licenses
"$TOOLS_DIR/bin/sdkmanager" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0"

if [ -f "$SDK_ROOT/platforms/android-36/android-36/android.jar" ] && [ ! -f "$SDK_ROOT/platforms/android-36/android.jar" ]; then
  cp -R "$SDK_ROOT/platforms/android-36/android-36/." "$SDK_ROOT/platforms/android-36/"
fi

cat > "$ROOT_DIR/local.properties" <<EOF
sdk.dir=$SDK_ROOT
EOF

echo "Android SDK ready at $SDK_ROOT"
