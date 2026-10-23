#!/usr/bin/env bash
# Builds libpm3android.so with the NDK toolchain and deposits it into the
# module's jniLibs directory for packaging.
#
# The client's bzip2/lz4 dependencies are ExternalProjects whose archives are
# plain file inputs for ninja: the build must therefore happen in two steps
# (archives first, client target afterwards), otherwise ninja fails at load
# time with "missing and no known rule to make it".
# The second invocation is incremental: re-running this script only rebuilds
# what changed.
#
# Environment: ANDROID_SDK_ROOT (or ANDROID_SDK) must point to the SDK, which
# contains ndk/. Alternatively ANDROID_NDK points to the NDK directly.
#
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$REPO_ROOT/core/pm3/src/main/cpp"
BUILD_DIR="$REPO_ROOT/core/pm3/build/native"
OUT_DIR="$REPO_ROOT/core/pm3/src/main/jniLibs/arm64-v8a"

SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-}}"
if [[ -n "${ANDROID_NDK:-}" ]]; then
    NDK="$ANDROID_NDK"
else
    SDK="${SDK:?ANDROID_SDK or ANDROID_SDK_ROOT must be set}"
    NDK="$(ls -d "$SDK"/ndk/* | sort -V | tail -n1)"
fi
CMAKE="$(ls "$SDK"/cmake/*/bin/cmake | sort -V | tail -n1)"
NINJA="$(ls "$SDK"/cmake/*/bin/ninja | sort -V | tail -n1)"

echo ">> configure (ndk=$NDK)"
mkdir -p "$BUILD_DIR"
if [[ ! -f "$BUILD_DIR/build.ninja" ]]; then
    "$CMAKE" -S "$SRC_DIR" -B "$BUILD_DIR" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$NINJA" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-26 \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release
fi

echo ">> external project archives (bzip2, lz4)"
"$CMAKE" --build "$BUILD_DIR" --target pm3client/bzip2 pm3client/lz4

echo ">> client library"
"$CMAKE" --build "$BUILD_DIR" --target pm3rrg_rdv4 -j4

echo ">> install into jniLibs"
mkdir -p "$OUT_DIR"
cp -f "$BUILD_DIR/pm3client/libpm3android.so" "$OUT_DIR/libpm3android.so"
ls -lh "$OUT_DIR/libpm3android.so"
