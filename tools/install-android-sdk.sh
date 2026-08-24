#!/usr/bin/env bash
# Install the Android build toolchain without the IDE.
#
# Android Studio is ~10GB and is an editor; none of it is required to compile
# an APK. The JDK, the SDK command-line tools and Gradle are, and together
# they come to roughly 3GB. The IDE can be installed later on top of this SDK
# without redoing any of it.
set -u

JDK_URL="https://aka.ms/download-jdk/microsoft-jdk-17.0.13-windows-x64.zip"
CMDLINE_URLS=(
  "https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip"
  "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
  "https://dl.google.com/android/repository/commandlinetools-win-10406996_latest.zip"
)

BASE="$LOCALAPPDATA/Android"
SDK="$BASE/Sdk"
JDK_DIR="$LOCALAPPDATA/Programs/jdk17"
WORK="$BASE/_download"

mkdir -p "$WORK" "$SDK" "$JDK_DIR"

say() { echo "[$(date +%H:%M:%S)] $*"; }

# ---------------------------------------------------------------------- JDK

if [ -x "$JDK_DIR/bin/java.exe" ]; then
  say "JDK already present at $JDK_DIR"
else
  say "Downloading JDK 17..."
  if ! curl -fL --retry 3 -o "$WORK/jdk.zip" "$JDK_URL"; then
    say "FAILED: could not download the JDK"
    exit 1
  fi
  say "Extracting JDK ($(du -h "$WORK/jdk.zip" | cut -f1))..."
  rm -rf "$WORK/jdk-extract" && mkdir -p "$WORK/jdk-extract"
  unzip -q "$WORK/jdk.zip" -d "$WORK/jdk-extract" || { say "FAILED: unzip jdk"; exit 1; }
  inner="$(find "$WORK/jdk-extract" -maxdepth 1 -mindepth 1 -type d | head -1)"
  rm -rf "$JDK_DIR" && mv "$inner" "$JDK_DIR"
  say "JDK installed at $JDK_DIR"
fi

export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$PATH"
say "java: $("$JDK_DIR/bin/java.exe" -version 2>&1 | head -1)"

# ------------------------------------------------------- SDK command-line tools

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager.bat"

if [ -f "$SDKMANAGER" ]; then
  say "SDK command-line tools already present"
else
  got=""
  for url in "${CMDLINE_URLS[@]}"; do
    say "Trying $(basename "$url")..."
    if curl -fL --retry 2 -o "$WORK/cmdline.zip" "$url"; then got="$url"; break; fi
  done
  if [ -z "$got" ]; then
    say "FAILED: no command-line tools package could be downloaded"
    exit 1
  fi
  say "Extracting command-line tools..."
  rm -rf "$WORK/cmd-extract" && mkdir -p "$WORK/cmd-extract"
  unzip -q "$WORK/cmdline.zip" -d "$WORK/cmd-extract" || { say "FAILED: unzip tools"; exit 1; }
  # sdkmanager insists on living at cmdline-tools/latest/
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$WORK/cmd-extract/cmdline-tools" "$SDK/cmdline-tools/latest"
  say "Command-line tools installed"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

# ------------------------------------------------------------------ packages

say "Accepting licenses..."
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses > "$WORK/licenses.log" 2>&1
say "Licenses: $(grep -ac 'accepted' "$WORK/licenses.log" || echo 0) accepted"

say "Installing platform-tools, platform 36 and build-tools (this is the slow part)..."
"$SDKMANAGER" --sdk_root="$SDK" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" > "$WORK/packages.log" 2>&1
status=$?
tail -5 "$WORK/packages.log"

if [ $status -ne 0 ]; then
  say "Package install reported status $status; trying platform 35 as a fallback"
  "$SDKMANAGER" --sdk_root="$SDK" \
    "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
    >> "$WORK/packages.log" 2>&1
fi

# -------------------------------------------------------------------- verify

say "--- verification ---"
say "ANDROID_HOME : $SDK"
say "JAVA_HOME    : $JDK_DIR"
[ -x "$SDK/platform-tools/adb.exe" ] && say "adb          : $("$SDK/platform-tools/adb.exe" version | head -1)" || say "adb          : MISSING"
say "platforms    : $(ls "$SDK/platforms" 2>/dev/null | tr '\n' ' ')"
say "build-tools  : $(ls "$SDK/build-tools" 2>/dev/null | tr '\n' ' ')"
say "total size   : $(du -sh "$SDK" 2>/dev/null | cut -f1)"
say "DONE"
