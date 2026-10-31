#!/usr/bin/env bash
# Prepare the pinned proxmark3 submodule in third_party/ for building the
# client library:
#
#   1. materialise client/deps/linenoise/ from the vendored linenoise-ng zip
#      (the upstream script get_linenoise.sh needs `unzip`, which the build
#      host may not have), generating the same linenoise.cmake it would;
#   2. apply the upstream one-line autocompletion patch (done via sed instead
#      of `patch` so it is deterministic);
#   3. make the bzip2/lz4 ExternalProject steps portable, so the build also
#      works on Windows where cmd.exe is the shell.
#
# The deps extraction is ignored by client/deps/.gitignore, so the submodule
# stays clean for updates; step 3 edits two tracked CMakeLists.txt files and is
# idempotent.
#
# Usage: scripts/prepare-pm3.sh
set -euo pipefail

PM3_ROOT="$(cd "$(dirname "$0")/.." && pwd)/third_party/proxmark3"
DEPS="$PM3_ROOT/client/deps"
LND="$DEPS/linenoise"
ZIP="$DEPS/linenoise-ng-1.0.1.zip"

if [ ! -f "$ZIP" ]; then
    echo "vendored linenoise zip missing: $ZIP" >&2
    exit 1
fi

if [ ! -f "$LND/linenoise.cpp" ]; then
    echo ">> extracting linenoise-ng from vendored zip"
    mkdir -p "$LND"
    if python3 -c "import zipfile" 2>/dev/null; then
        python3 - "$ZIP" "$LND" <<'PY'
import sys, zipfile
zip_path, out_dir = sys.argv[1], sys.argv[2]
prefix = "linenoise-ng-1.0.1/"
keep = {
    "src/ConvertUTF.cpp": "ConvertUTF.cpp",
    "src/ConvertUTF.h": "ConvertUTF.h",
    "src/linenoise.cpp": "linenoise.cpp",
    "src/wcwidth.cpp": "wcwidth.cpp",
    "include/linenoise.h": "linenoise.h",
    "LICENSE": "LICENSE",
    "README.md": "README.md",
}
with zipfile.ZipFile(zip_path) as z:
    for member, name in keep.items():
        with z.open(prefix + member) as src, open(f"{out_dir}/{name}", "wb") as dst:
            dst.write(src.read())
print("   extracted 7 files")
PY
    elif python -c "import zipfile" 2>/dev/null; then
        python - "$ZIP" "$LND" <<'PY'
import sys, zipfile
zip_path, out_dir = sys.argv[1], sys.argv[2]
prefix = "linenoise-ng-1.0.1/"
keep = {
    "src/ConvertUTF.cpp": "ConvertUTF.cpp",
    "src/ConvertUTF.h": "ConvertUTF.h",
    "src/linenoise.cpp": "linenoise.cpp",
    "src/wcwidth.cpp": "wcwidth.cpp",
    "include/linenoise.h": "linenoise.h",
    "LICENSE": "LICENSE",
    "README.md": "README.md",
}
with zipfile.ZipFile(zip_path) as z:
    for member, name in keep.items():
        with z.open(prefix + member) as src, open(f"{out_dir}/{name}", "wb") as dst:
            dst.write(src.read())
print("   extracted 7 files")
PY
    elif command -v unzip >/dev/null 2>&1; then
        unzip -o -j "$ZIP" \
            "linenoise-ng-1.0.1/src/ConvertUTF.cpp" \
            "linenoise-ng-1.0.1/src/ConvertUTF.h" \
            "linenoise-ng-1.0.1/src/linenoise.cpp" \
            "linenoise-ng-1.0.1/src/wcwidth.cpp" \
            "linenoise-ng-1.0.1/include/linenoise.h" \
            "linenoise-ng-1.0.1/LICENSE" \
            "linenoise-ng-1.0.1/README.md" \
            -d "$LND"
    elif command -v bsdtar >/dev/null 2>&1; then
        TMP_DIR="$(mktemp -d)"
        bsdtar -xf "$ZIP" -C "$TMP_DIR"
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/ConvertUTF.cpp" "$LND/ConvertUTF.cpp"
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/ConvertUTF.h" "$LND/ConvertUTF.h"
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/linenoise.cpp" "$LND/linenoise.cpp"
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/wcwidth.cpp" "$LND/wcwidth.cpp"
        cp "$TMP_DIR/linenoise-ng-1.0.1/include/linenoise.h" "$LND/linenoise.h"
        cp "$TMP_DIR/linenoise-ng-1.0.1/LICENSE" "$LND/LICENSE"
        cp "$TMP_DIR/linenoise-ng-1.0.1/README.md" "$LND/README.md"
        rm -rf "$TMP_DIR"
    elif command -v powershell.exe >/dev/null 2>&1 || command -v powershell >/dev/null 2>&1; then
        PS_CMD="$(command -v powershell.exe || command -v powershell)"
        TMP_DIR="$(mktemp -d)"
        WIN_ZIP="$(cygpath -w "$ZIP" 2>/dev/null || echo "$ZIP")"
        WIN_TMP="$(cygpath -w "$TMP_DIR" 2>/dev/null || echo "$TMP_DIR")"
        "$PS_CMD" -NoProfile -Command "Expand-Archive -Path '$WIN_ZIP' -DestinationPath '$WIN_TMP' -Force"
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/ConvertUTF.cpp" "$LND/ConvertUTF.cpp"
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/ConvertUTF.h" "$LND/ConvertUTF.h"
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/linenoise.cpp" "$LND/linenoise.cpp"
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/wcwidth.cpp" "$LND/wcwidth.cpp"
        cp "$TMP_DIR/linenoise-ng-1.0.1/include/linenoise.h" "$LND/linenoise.h"
        cp "$TMP_DIR/linenoise-ng-1.0.1/LICENSE" "$LND/LICENSE"
        cp "$TMP_DIR/linenoise-ng-1.0.1/README.md" "$LND/README.md"
        rm -rf "$TMP_DIR"
    elif command -v jar >/dev/null 2>&1; then
        TMP_DIR="$(mktemp -d)"
        (cd "$TMP_DIR" && jar xf "$(cd "$DEPS" && pwd)/linenoise-ng-1.0.1.zip")
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/ConvertUTF.cpp" "$LND/ConvertUTF.cpp"
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/ConvertUTF.h" "$LND/ConvertUTF.h"
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/linenoise.cpp" "$LND/linenoise.cpp"
        cp "$TMP_DIR/linenoise-ng-1.0.1/src/wcwidth.cpp" "$LND/wcwidth.cpp"
        cp "$TMP_DIR/linenoise-ng-1.0.1/include/linenoise.h" "$LND/linenoise.h"
        cp "$TMP_DIR/linenoise-ng-1.0.1/LICENSE" "$LND/LICENSE"
        cp "$TMP_DIR/linenoise-ng-1.0.1/README.md" "$LND/README.md"
        rm -rf "$TMP_DIR"
    else
        echo "No suitable tool found to extract $ZIP (python, unzip, bsdtar, powershell, jar)" >&2
        exit 1
    fi

else
    echo ">> linenoise already extracted"
fi

# "Patch to get proper autocompletion of subcommands" (upstream diff).
# Do not rewrite an already-patched file: sed -i would bump its mtime and
# force an unnecessary native recompile on every build.
if grep -q "int startIndex = 0;" "$LND/linenoise.cpp"; then
    echo ">> autocompletion patch already applied"
else
    sed -i -e 's/^  int startIndex = pos;$/  int startIndex = 0;/' "$LND/linenoise.cpp"
    if ! grep -q "int startIndex = 0;" "$LND/linenoise.cpp"; then
        echo "autocompletion patch did not apply" >&2
        exit 1
    fi
fi

if [ ! -f "$DEPS/linenoise.cmake" ]; then
    echo ">> generating linenoise.cmake"
    cat > "$DEPS/linenoise.cmake" <<'EOF'
add_library(pm3rrg_rdv4_linenoise STATIC
        linenoise/ConvertUTF.cpp
        linenoise/linenoise.cpp
        linenoise/wcwidth.cpp
)

target_compile_definitions(pm3rrg_rdv4_linenoise PRIVATE NDEBUG)
target_include_directories(pm3rrg_rdv4_linenoise INTERFACE linenoise)
target_compile_options(pm3rrg_rdv4_linenoise PRIVATE -Wall -Werror -O3)
set_property(TARGET pm3rrg_rdv4_linenoise PROPERTY POSITION_INDEPENDENT_CODE ON)
EOF
fi

# Android JNI needs to drain the grabbed output while a command is still
# running, and to stop a running Lua script. The diff lives in patches/ and is
# idempotent so local builds and CI (fresh submodule checkout) stay aligned.
CONSOLE_PATCH="$(cd "$(dirname "$0")/.." && pwd)/patches/pm3-console.patch"
if [ -f "$CONSOLE_PATCH" ]; then
    if grep -q "grabber_take_copy" "$PM3_ROOT/client/src/ui.c"; then
        echo ">> console streaming/stop patch already applied"
    elif git -C "$PM3_ROOT" apply --ignore-whitespace "$CONSOLE_PATCH" 2>/dev/null; then
        echo ">> console streaming/stop patch applied"
    elif command -v patch >/dev/null 2>&1 && (cd "$PM3_ROOT" && patch -p1 --forward < "$CONSOLE_PATCH" >/dev/null 2>&1); then
        echo ">> console streaming/stop patch applied (patch)"
    else
        echo "console streaming/stop patch did not apply" >&2
        exit 1
    fi
fi

# Ninja hands custom commands to cmd.exe on Windows, where the upstream
# `mkdir -p` in the bzip2/lz4 configure steps is a syntax error. Use CMake's
# portable equivalent and read the archive from stdin (`-f -`): bsdtar, the
# tar shipped with Windows, has no implicit stdin.
portable_dep_step() {
    local file="$1"
    grep -q 'mkdir -p \${BZIP2_BUILD_DIR}' "$file" || return 0
    echo ">> portable bzip2/lz4 configure steps in ${file#$PM3_ROOT/}"
    sed -e 's#mkdir -p \${BZIP2_BUILD_DIR}#${CMAKE_COMMAND} -E make_directory ${BZIP2_BUILD_DIR}#' \
        -e 's#mkdir -p \${LZ4_BUILD_DIR}#${CMAKE_COMMAND} -E make_directory ${LZ4_BUILD_DIR}#' \
        -e 's#tar -C \${BZIP2_BUILD_DIR} -x#tar -C ${BZIP2_BUILD_DIR} -x -f -#' \
        -e 's#tar -C \${LZ4_BUILD_DIR} -x#tar -C ${LZ4_BUILD_DIR} -x -f -#' \
        "$file" > "$file.tmp"
    mv "$file.tmp" "$file"
}

portable_dep_step "$PM3_ROOT/client/CMakeLists.txt"
portable_dep_step "$PM3_ROOT/client/experimental_lib/CMakeLists.txt"

echo ">> proxmark3 submodule ready ($PM3_ROOT)"
