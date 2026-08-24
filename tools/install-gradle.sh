#!/usr/bin/env bash
set -u
DEST="$LOCALAPPDATA/Programs/gradle"
VER="8.13"
URL="https://services.gradle.org/distributions/gradle-${VER}-bin.zip"
WORK="$LOCALAPPDATA/Android/_download"
mkdir -p "$WORK" "$DEST"
say() { echo "[$(date +%H:%M:%S)] $*"; }

if [ -x "$DEST/gradle-$VER/bin/gradle.bat" ]; then
  say "Gradle $VER already installed"
else
  say "Downloading Gradle $VER..."
  curl -fL --retry 3 -s -o "$WORK/gradle.zip" "$URL" || { say "FAILED download"; exit 1; }
  say "Extracting ($(du -h "$WORK/gradle.zip" | cut -f1))..."
  unzip -q -o "$WORK/gradle.zip" -d "$DEST" || { say "FAILED unzip"; exit 1; }
fi
export JAVA_HOME="C:\Users\PADMAP~1\AppData\Local\Programs\jdk17"
say "gradle: $("$DEST/gradle-$VER/bin/gradle.bat" --version 2>&1 | grep -i '^Gradle' | head -1)"
say "DONE"
