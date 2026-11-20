#!/usr/bin/env bash
# Builds the mGBA Android APK.
#
# Usage: SDL2_SRC=/path/to/SDL2 ./build-apk.sh [--release]
#
# Prerequisites:
#   - ANDROID_HOME with build-tools 34, platform android-34, NDK 26.3.11579264, cmake 3.22.1
#   - JDK 17
#   - Gradle 8.7 (or a compatible gradlew wrapper in this directory)
#   - Optional release signing: create keystore.properties (see app/build.gradle)
set -euo pipefail

SDL2_SRC="${SDL2_SRC:?Set SDL2_SRC to the path of the SDL2 source tree}"
BUILDTYPE=debug
if [ "${1:-}" = "--release" ]; then
	BUILDTYPE=release
fi

if [ ! -f "$SDL2_SRC/CMakeLists.txt" ] || [ ! -f "$SDL2_SRC/include/SDL.h" ]; then
	echo "SDL2 source not found at $SDL2_SRC" >&2
	exit 1
fi

SDL2_SRC="$(cd "$SDL2_SRC" && pwd)"
cd "$(dirname "$0")"

GRADLE_CMD=gradle
if [ -x gradlew ]; then
	GRADLE_CMD=./gradlew
fi

MGBA_VERSION="${MGBA_VERSION:-$(sed -nE 's/^set\(LIB_VERSION_(MAJOR|MINOR|PATCH) ([0-9]+)\)$/\2/p' ../../../version.cmake | paste -sd. -)}"
"$GRADLE_CMD" -PSDL2_SRC="$SDL2_SRC" -PMGBA_VERSION="$MGBA_VERSION" "assemble${BUILDTYPE^}"

output="app/build/outputs/apk/$BUILDTYPE/app-$BUILDTYPE.apk"
if [ "$BUILDTYPE" = release ] && [ ! -f keystore.properties ]; then
	output="app/build/outputs/apk/release/app-release-unsigned.apk"
fi
printf '\nAPK: %s/%s\n' "$PWD" "$output"
