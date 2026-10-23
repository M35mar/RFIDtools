#!/usr/bin/env bash
# Prepare the pinned proxmark3 submodule in third_party/ for building the
# client library:
#
#   1. materialise client/deps/linenoise/ from the vendored linenoise-ng zip
#      (the upstream script get_linenoise.sh needs `unzip`, which the build
#      host may not have), generating the same linenoise.cmake it would;
#   2. apply the upstream one-line autocompletion patch (done via sed instead
#      of `patch` so it is deterministic).
#
# Everything this script writes is ignored by client/deps/.gitignore, so the
# submodule stays clean for updates.
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
else
    echo ">> linenoise already extracted"
fi

# "Patch to get proper autocompletion of subcommands" (upstream diff)
sed -i -e 's/^  int startIndex = pos;$/  int startIndex = 0;/' "$LND/linenoise.cpp"
if ! grep -q "int startIndex = 0;" "$LND/linenoise.cpp"; then
    echo "autocompletion patch did not apply" >&2
    exit 1
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

echo ">> proxmark3 submodule ready ($PM3_ROOT)"
