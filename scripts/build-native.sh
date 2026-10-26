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

"$REPO_ROOT/scripts/prepare-pm3.sh"

SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" && -f "$REPO_ROOT/local.properties" ]]; then
    SDK="$(grep -E '^sdk\.dir=' "$REPO_ROOT/local.properties" | head -n1 | cut -d'=' -f2 | tr -d '\r')"
fi
if [[ -z "$SDK" ]]; then
    for candidate in "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
        if [[ -d "$candidate" ]]; then
            SDK="$candidate"
            break
        fi
    done
fi

if [[ -n "$SDK" ]]; then
    SDK="$(echo "$SDK" | tr '\\' '/' | sed -e 's|//|/|g')"
    if command -v cygpath >/dev/null 2>&1; then
        SDK="$(cygpath -u "$SDK")"
    fi
fi

NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
CMAKE_VERSION="${CMAKE_VERSION:-3.22.1}"
if [[ -n "${ANDROID_NDK:-}" ]]; then
    NDK="$ANDROID_NDK"
    if command -v cygpath >/dev/null 2>&1; then
        NDK="$(cygpath -u "$NDK")"
    fi
else
    SDK="${SDK:?ANDROID_SDK or ANDROID_SDK_ROOT must be set (or set ANDROID_NDK)}"
    NDK="$SDK/ndk/$NDK_VERSION"
fi
if [[ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
    echo "NDK not found at $NDK" >&2
    exit 1
fi

if [[ -n "$SDK" ]]; then
    CMAKE="$SDK/cmake/$CMAKE_VERSION/bin/cmake"
    NINJA="$SDK/cmake/$CMAKE_VERSION/bin/ninja"
    if [[ ! -x "$CMAKE" || ! -x "$NINJA" ]]; then
        echo "CMake $CMAKE_VERSION not found in $SDK/cmake" >&2
        exit 1
    fi
else
    CMAKE="$(command -v cmake || true)"
    NINJA="$(command -v ninja || true)"
    if [[ -z "$CMAKE" || -z "$NINJA" ]]; then
        echo "CMake and Ninja are required when ANDROID_SDK_ROOT is not set" >&2
        exit 1
    fi
fi

echo ">> configure (ndk=$NDK)"
mkdir -p "$BUILD_DIR"
# Configure every time: CMake is incremental and this makes flag changes
# (NDK version, 16 KB page support, ...) take effect on existing build dirs.
"$CMAKE" -S "$SRC_DIR" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release

echo ">> external project archives (bzip2, lz4)"
"$CMAKE" --build "$BUILD_DIR" --target pm3client/bzip2 pm3client/lz4

echo ">> client library"
"$CMAKE" --build "$BUILD_DIR" --target pm3rrg_rdv4 -j4

echo ">> install into jniLibs"
mkdir -p "$OUT_DIR"
cp -f "$BUILD_DIR/pm3client/libpm3android.so" "$OUT_DIR/libpm3android.so"
ls -lh "$OUT_DIR/libpm3android.so"
