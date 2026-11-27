#!/usr/bin/env sh
# Lightweight Gradle bootstrapper. Downloads the pinned Gradle distribution without requiring wrapper JAR.
set -eu
VERSION=8.7
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/vrtual-bootstrap/gradle-$VERSION"
if [ ! -x "$BASE/bin/gradle" ]; then
  mkdir -p "$(dirname "$BASE")"
  ZIP="$(dirname "$BASE")/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  else echo 'curl or wget is required to bootstrap Gradle' >&2; exit 1; fi
  unzip -q "$ZIP" -d "$(dirname "$BASE")"
fi
exec "$BASE/bin/gradle" "$@"
