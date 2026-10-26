# Builds the Proxmark3 client (RfidResearchGroup/proxmark3, iceman fork) as an
# in-process library and exposes it to the Kotlin layer through JNI.
#
# The client is compiled straight from the pinned submodule, so the client and
# the bundled firmware always share the same commit and therefore the same
# CAPABILITIES_VERSION: the "flash the Proxmark with the same version as the
# client" refusal vanishes by construction.
#
# The upstream project (client/experimental_lib) is reused as-is; it already
# knows how to build the client as a library and carries the Android fixes
# (e.g. the lua getlocaledecpoint workaround). We only re-target its output:
# the JNI glue is compiled into the shared library itself, so the app ships a
# single .so instead of a library plus a shared one.

cmake_minimum_required(VERSION 3.22.1)
project(pm3android C CXX)

set(PM3_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/proxmark3")
get_filename_component(PM3_SRC "${PM3_SRC}" ABSOLUTE)

if(NOT EXISTS "${PM3_SRC}/client/experimental_lib/CMakeLists.txt")
    message(FATAL_ERROR
        "proxmark3 submodule not found at ${PM3_SRC}:\n"
        "  git submodule update --init --recursive")
endif()
if(NOT EXISTS "${PM3_SRC}/client/deps/linenoise/linenoise.cpp")
    message(FATAL_ERROR
        "linenoise not materialised in the submodule:\n"
        "  scripts/prepare-pm3.sh")
endif()

# --- client build options --------------------------------------------------
# SKIPREADLINE            : no readline/ncurses; the client uses linenoise.
# SKIPGD                  : libgd is only used by the (absent) Qt image viewer.
# SKIPBT                  : no bluez, transport Bluetooth is app-side.
# SKIPPYTHON              : no python scripting console.
# SKIPJANSSONSYSTEM       : use the bundled jansson.
# SKIPWHEREAMISYSTEM      : use the bundled whereami.
# SKIPPTHREAD             : Android libc has pthread; the NDK ships no libpthread.
# EMBED_BZIP2 / EMBED_LZ4 are forced on for cross builds by the upstream file
# and are left as-is: they are fetched from android.googlesource.com at build
# time (needed by cmdhfmfhard.c, the "hf mf hard" state files).
set(SKIPREADLINE 1 CACHE STRING "" FORCE)
set(SKIPGD 1 CACHE STRING "" FORCE)
set(SKIPBT 1 CACHE STRING "" FORCE)
set(SKIPPYTHON 1 CACHE STRING "" FORCE)
set(SKIPJANSSONSYSTEM 1 CACHE STRING "" FORCE)
set(SKIPWHEREAMISYSTEM 1 CACHE STRING "" FORCE)
set(SKIPPTHREAD 1 CACHE STRING "" FORCE)

add_subdirectory("${PM3_SRC}/client/experimental_lib" pm3client)

if(NOT TARGET pm3rrg_rdv4)
    message(FATAL_ERROR "upstream target pm3rrg_rdv4 not found")
endif()

# The Android NDK has newer toolchain defaults than the gcc used upstream, and
# upstream builds its targets with -Werror. Building the client with a different
# toolchain is a supported configuration, so the warnings are downgraded back
# to warnings.
get_property(_all_targets GLOBAL PROPERTY TARGETS)
foreach(_target IN LISTS _all_targets)
    if(_target MATCHES "^pm3rrg_rdv4")
        get_target_property(_copts ${_target} COMPILE_OPTIONS)
        if(NOT _copts STREQUAL "NOTFOUND" AND _copts)
            list(REMOVE_ITEM _copts "-Werror")
            set_target_properties(${_target} PROPERTIES COMPILE_OPTIONS "${_copts}")
        endif()
    endif()
endforeach()

# Only the shared variant is built: the static one duplicates the compile work
# and is not used by the app.
set_target_properties(pm3rrg_rdv4_static PROPERTIES EXCLUDE_FROM_ALL TRUE)

# The JNI glue lives in the same library so it can reach the client internals
# (g_session, OpenProxmark, CommandReceived) without a second .so on the
# linker's search path.
target_sources(pm3rrg_rdv4 PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/pm3_jni.c")
target_link_libraries(pm3rrg_rdv4 PRIVATE log)
target_compile_options(pm3rrg_rdv4 PRIVATE -Wno-error)
# Android 15+ devices may use 16 KB pages; keep the native library compatible.
target_link_options(pm3rrg_rdv4 PRIVATE "-Wl,-z,max-page-size=16384")

set_target_properties(pm3rrg_rdv4 PROPERTIES
        OUTPUT_NAME pm3android
        C_VISIBILITY_PRESET default
        CXX_VISIBILITY_PRESET default)
