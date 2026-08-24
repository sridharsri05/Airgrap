#!/usr/bin/env bash
# Install the SDK packages themselves.
#
# JAVA_HOME and the SDK root MUST use 8.3 short paths. sdkmanager.bat invokes
# %JAVA_HOME%\bin\java unquoted, so a user profile containing a space (here,
# "PADMA PRIYA") splits the command and cmd reports that 'C:\Users\PADMA' is
# not a recognised command. The short name is the same folder without a space.
set -u
SHORT="C:\Users\PADMAP~1\AppData\Local"
export JAVA_HOME="$SHORT\Programs\jdk17"
export ANDROID_HOME="$SHORT\Android\Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
SDKMANAGER="$LOCALAPPDATA/Android/Sdk/cmdline-tools/latest/bin/sdkmanager.bat"

say() { echo "[$(date +%H:%M:%S)] $*"; }

say "Accepting licenses..."
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses 2>&1 | tail -3

for pkg in "platform-tools" "platforms;android-36" "build-tools;36.0.0"; do
  say "Installing $pkg ..."
  "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "$pkg" 2>&1 | tail -2
done

say "--- verification ---"
[ -x "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ] \
  && say "adb         : $("$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" version | head -1)" \
  || say "adb         : MISSING"
say "platforms   : $(ls "$LOCALAPPDATA/Android/Sdk/platforms" 2>/dev/null | tr '\n' ' ')"
say "build-tools : $(ls "$LOCALAPPDATA/Android/Sdk/build-tools" 2>/dev/null | tr '\n' ' ')"
say "size        : $(du -sh "$LOCALAPPDATA/Android/Sdk" 2>/dev/null | cut -f1)"
say "DONE"
