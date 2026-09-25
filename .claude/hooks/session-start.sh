#!/bin/bash
# Installiert Flutter (stable), GTK 3 und das Android SDK in Claude-Code-on-the-web-Sitzungen.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

FLUTTER_DIR="$HOME/flutter"
ANDROID_DIR="$HOME/android-sdk"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-16111833_latest.zip"

# --- Flutter ---
if [ ! -x "$FLUTTER_DIR/bin/flutter" ]; then
  git clone --depth 1 -b stable https://github.com/flutter/flutter.git "$FLUTTER_DIR"
fi

# Baut das Flutter-Tool beim ersten Aufruf und lädt das Dart-SDK.
"$FLUTTER_DIR/bin/flutter" --version

# --- GTK 3 (Linux-Desktop) ---
if ! dpkg -s libgtk-3-dev >/dev/null 2>&1; then
  apt-get update -qq || true
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq libgtk-3-dev mesa-utils
fi

# --- Android SDK ---
# Braucht Netzwerkzugriff auf dl.google.com; schlägt das fehl, läuft der Rest trotzdem.
install_android_sdk() {
  local sdkmanager="$ANDROID_DIR/cmdline-tools/latest/bin/sdkmanager"

  if [ ! -x "$sdkmanager" ]; then
    local tmp
    tmp=$(mktemp -d)
    curl -sSfL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    mkdir -p "$ANDROID_DIR/cmdline-tools"
    rm -rf "$ANDROID_DIR/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$ANDROID_DIR/cmdline-tools/latest"
    rm -rf "$tmp"
  fi

  if [ ! -d "$ANDROID_DIR/platforms/android-36" ] || [ ! -d "$ANDROID_DIR/build-tools/37.0.0" ]; then
    yes | "$sdkmanager" --sdk_root="$ANDROID_DIR" --licenses >/dev/null 2>&1 || true
    "$sdkmanager" --sdk_root="$ANDROID_DIR" "platform-tools" "platforms;android-36" "build-tools;37.0.0" >/dev/null
  fi

  "$FLUTTER_DIR/bin/flutter" config --android-sdk "$ANDROID_DIR" >/dev/null
  yes | "$FLUTTER_DIR/bin/flutter" doctor --android-licenses >/dev/null 2>&1 || true
}

# Nicht in "if" aufrufen: dort würde Bash set -e in der Subshell abschalten.
set +e
(set -e; install_android_sdk)
android_status=$?
set -e
if [ "$android_status" -ne 0 ]; then
  echo "WARNUNG: Android SDK konnte nicht installiert werden (Netzwerkzugriff auf dl.google.com?)." >&2
fi

# Gradle lädt sonst zu viel parallel von Maven Central und bekommt "429 Too Many Requests".
mkdir -p "$HOME/.gradle"
if ! grep -q '^org.gradle.workers.max=' "$HOME/.gradle/gradle.properties" 2>/dev/null; then
  printf 'org.gradle.workers.max=1\norg.gradle.parallel=false\n' >> "$HOME/.gradle/gradle.properties"
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=\"$ANDROID_DIR\"" >> "$CLAUDE_ENV_FILE"
  echo "export PATH=\"$FLUTTER_DIR/bin:$ANDROID_DIR/platform-tools:\$PATH\"" >> "$CLAUDE_ENV_FILE"
fi
