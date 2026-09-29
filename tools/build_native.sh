#!/bin/sh
#
# eclipsesdl — build the native libraries for one or more Android ABIs.
#
# Used by .github/workflows/ci.yml and release.yml, and runnable locally with
# the same variables. Nothing here is CI-specific on purpose: if the script
# only works on a runner, the runners are lying about what they build.
#
# Two projects are built, because the handover is a run-time one:
#
#   libSDL3.so         this tree, configured with -DECLIPSE_EXEC_ROOT so the
#                      shim in src/core/android/SDL_eclipse.c can find
#                      eclipseexec.h at compile time and libeclipseexec.so
#                      with dlopen() at run time. SDL never links against it,
#                      so a device without eclipseexec still loads.
#   libeclipseexec.so  the pinned eclipseexec release, fetched rather than
#                      vendored — SDL sees only its header, and the two projects
#                      version independently.
#   libeclipsehook.so  comes with eclipseexec, arm64-v8a only.
#
# Everything built is copied into $STAGE/<abi>/ so that Gradle packages the
# libraries into the AAR without knowing anything about CMake.
#
#   ANDROID_NDK     NDK root.  Default: $ANDROID_NDK_HOME, then $ANDROID_HOME/ndk/<pinned>.
#   ECLIPSE_EXEC    an existing eclipseexec checkout.  Default: cloned at the tag.
#   CMAKE           cmake executable.  Default: cmake from PATH.
#   OUT             build tree.       Default: build/native
#   STAGE           jniLibs directory. Default: jni_bindings/src/main/jniLibs
#   STRIP           strip executable.  Default: the NDK's llvm-strip, then PATH.
#                   'none' keeps the debug info; anything else missing is an error.
#
# Usage: tools/build_native.sh [abi ...]
set -eu

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)

NDK_VERSION=${NDK_VERSION:-28.2.13676358}
ECLIPSE_EXEC_TAG=${ECLIPSE_EXEC_TAG:-v0.1.0}
ECLIPSE_EXEC_REPO=${ECLIPSE_EXEC_REPO:-https://github.com/ShadowMaybe/eclipseexec.git}

ANDROID_NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-${ANDROID_HOME:+$ANDROID_HOME/ndk/$NDK_VERSION}}}
if [ -z "${ANDROID_NDK:-}" ] || [ ! -f "$ANDROID_NDK/build/cmake/android.toolchain.cmake" ]; then
    echo "tools/build_native.sh: no NDK found. Set ANDROID_NDK to the NDK root." >&2
    echo "  (looked for ANDROID_NDK, ANDROID_NDK_HOME, ANDROID_HOME/ndk/$NDK_VERSION)" >&2
    exit 2
fi

CMAKE=${CMAKE:-cmake}
PLATFORM=${ANDROID_PLATFORM:-android-21}
BUILD_TYPE=${BUILD_TYPE:-Release}
GENERATOR=${GENERATOR:-Ninja}

# Relative paths are relative to the repository, not to wherever the caller
# happened to be standing — the same script runs from CI's workspace and from
# somebody's shell.
OUT=${OUT:-build/native}
case $OUT in /*) ;; *) OUT=$ROOT/$OUT ;; esac
STAGE=${STAGE:-jni_bindings/src/main/jniLibs}
case $STAGE in /*) ;; *) STAGE=$ROOT/$STAGE ;; esac

# The NDK toolchain file adds -g to every build type, so a "Release" tree still
# carries full debug info — and AGP does not strip what it puts in an AAR, only
# the APK a consumer builds later. Staged copies are therefore stripped here,
# once, at the moment they become a shipped artefact: the debug info stays
# where it is useful, in $OUT, and the AAR, the release zips and the export
# check all read the same stripped files.
# The tool find_strip settles on: the NDK's own llvm-strip, then anything on
# PATH. $ANDROID_NDK is known to be set and real by the time this runs — the
# script exits earlier if it is not. STRIP is applied by the block below,
# not inside this function.
find_strip() {
    for host in linux-x86_64 darwin-x86_64 darwin-arm64; do
        if [ -x "$ANDROID_NDK/toolchains/llvm/prebuilt/$host/bin/llvm-strip" ]; then
            printf '%s\n' "$ANDROID_NDK/toolchains/llvm/prebuilt/$host/bin/llvm-strip"
            return 0
        fi
    done
    command -v llvm-strip || command -v strip || return 1
}

# A tool that is simply missing is an error, not a shrug. Nothing downstream
# can tell the 4 MB AAR that ships from the 15 MB one that would ship
# unstripped: both build, both package, both pass the export check, and the
# only trace is a line in a log nobody reads. STRIP=none is the deliberate
# way to keep debug info, for the debugging session that wants it.
if [ "${STRIP:-}" = "none" ]; then
    STRIP_TOOL=
elif [ -n "${STRIP:-}" ]; then
    STRIP_TOOL=$STRIP
else
    STRIP_TOOL=$(find_strip || true)
    if [ -z "$STRIP_TOOL" ]; then
        echo "tools/build_native.sh: no strip tool found." >&2
        echo "  (set STRIP=none to keep the debug info deliberately)" >&2
        exit 3
    fi
fi

if [ "$#" -eq 0 ]; then
    set -- arm64-v8a armeabi-v7a x86 x86_64
fi

ECLIPSE_EXEC=${ECLIPSE_EXEC:-$ROOT/build/deps/eclipseexec}
if [ ! -f "$ECLIPSE_EXEC/include/eclipseexec.h" ]; then
    echo "eclipseexec: cloning $ECLIPSE_EXEC_TAG into $ECLIPSE_EXEC"
    rm -rf "$ECLIPSE_EXEC"
    mkdir -p "$(dirname "$ECLIPSE_EXEC")"
    git clone --quiet --depth 1 --branch "$ECLIPSE_EXEC_TAG" "$ECLIPSE_EXEC_REPO" "$ECLIPSE_EXEC"
fi

echo "NDK:         $ANDROID_NDK"
echo "cmake:       $($CMAKE --version | head -1)"
echo "ABIs:        $*"
echo "eclipseexec: $ECLIPSE_EXEC ($ECLIPSE_EXEC_TAG)"
echo "output:      $OUT"
echo "stage:       $STAGE"
echo "strip:       ${STRIP_TOOL:-none (staged libraries keep their debug info)}"
echo

# Start the staging directory from nothing: an ABI built last week and not
# built now must not ride along into the AAR, and nothing else would notice.
rm -rf "$STAGE"

for abi in "$@"; do
    echo "--- $abi: eclipseexec ---"
    "$CMAKE" -S "$ECLIPSE_EXEC" -B "$OUT/eclipseexec/$abi" \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="$PLATFORM" \
        -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
        -G "$GENERATOR"
    "$CMAKE" --build "$OUT/eclipseexec/$abi"

    echo "--- $abi: SDL3 ---"
    "$CMAKE" -S "$ROOT" -B "$OUT/$abi" \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="$PLATFORM" \
        -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
        -DECLIPSE_EXEC_ROOT="$ECLIPSE_EXEC" \
        -DSDL_SHARED=ON \
        -DSDL_STATIC=OFF \
        -DSDL_TESTS=OFF \
        -DSDL_EXAMPLES=OFF \
        -DSDL_INSTALL=OFF \
        -G "$GENERATOR"
    "$CMAKE" --build "$OUT/$abi"

    # The shared library is written where SDL's target says it is, which is
    # stable today but not a contract; locate it rather than assume a path.
    sdl_so=$(find "$OUT/$abi" -name 'libSDL3.so' -type f ! -path '*/CMakeFiles/*' | head -1)
    if [ -z "$sdl_so" ]; then
        echo "tools/build_native.sh: no libSDL3.so was produced for $abi" >&2
        exit 1
    fi

    exec_so="$OUT/eclipseexec/$abi/libeclipseexec.so"
    if [ ! -f "$exec_so" ]; then
        echo "tools/build_native.sh: $exec_so is missing" >&2
        exit 1
    fi

    mkdir -p "$STAGE/$abi"
    cp "$sdl_so" "$exec_so" "$STAGE/$abi/"
    # arm64-v8a only: the Turnip interposer is built nowhere else, and the
    # launcher is expected to cope with its absence on the other ABIs.
    if [ -f "$OUT/eclipseexec/$abi/libeclipsehook.so" ]; then
        cp "$OUT/eclipseexec/$abi/libeclipsehook.so" "$STAGE/$abi/"
    fi

    if [ -z "$STRIP_TOOL" ]; then
        echo "build_native.sh: STRIP=none; staged libraries for $abi keep their debug info" >&2
    else
        for so in "$STAGE/$abi"/*.so; do
            "$STRIP_TOOL" --strip-unneeded "$so"
        done
    fi
    echo
done

echo "staged into $STAGE:"
find "$STAGE" -name '*.so' -type f | sort
