#!/bin/sh
#
# eclipsesdl — assemble the files that go on a GitHub Release.
#
# One zip per ABI (so a consumer downloads one architecture, not four), the
# headers those binaries were built against, the AAR that already carries all
# of them, and a checksum file. Everything lands in dist/ and release.yml
# uploads dist/* — the Releases page is the artifact store, since Actions
# artifacts expire and count against quota.
#
# Usage: TAG=v0.1.0 sh tools/package_release.sh
set -eu

TAG=${TAG:?set TAG to the release tag, e.g. TAG=v0.1.0}
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/dist"
BUILT="$ROOT/build/native"
AAR="$ROOT/jni_bindings/build/outputs/aar/jni_bindings-release.aar"
NDK_VERSION=${NDK_VERSION:-28.2.13676358}

command -v zip >/dev/null 2>&1 || {
    echo "package_release: 'zip' is not installed (apt install zip / apk add zip)" >&2
    exit 2
}

if [ ! -d "$BUILT" ]; then
    echo "package_release: $BUILT is missing — run tools/build_native.sh first" >&2
    exit 1
fi
if [ ! -f "$AAR" ]; then
    echo "package_release: $AAR is missing — run ./gradlew :jni_bindings:assembleRelease first" >&2
    exit 1
fi

# The NDK's toolchain file injects -g into every build type, so a Release build
# still carries its debug sections — while AGP strips the same libraries inside
# the AAR. Without this, the zip and the AAR for one tag disagree about how big
# the code is. STRIP overrides; otherwise the NDK's own llvm-strip, then PATH.
find_strip() {
    if [ -n "${STRIP:-}" ]; then
        printf '%s\n' "$STRIP"
        return 0
    fi
    for root in "${ANDROID_NDK:-}" "${ANDROID_NDK_HOME:-}" "${NDK_DIR:-}" \
                "${ANDROID_HOME:-}/ndk/$NDK_VERSION" "${ANDROID_SDK:-}/ndk/$NDK_VERSION"; do
        [ -d "$root" ] || continue
        for host in linux-x86_64 darwin-x86_64 darwin-arm64; do
            if [ -x "$root/toolchains/llvm/prebuilt/$host/bin/llvm-strip" ]; then
                printf '%s\n' "$root/toolchains/llvm/prebuilt/$host/bin/llvm-strip"
                return 0
            fi
        done
    done
    command -v llvm-strip || command -v strip || return 1
}

STRIP_TOOL=$(find_strip || true)
if [ -z "$STRIP_TOOL" ]; then
    echo "package_release: no strip tool found; the zips will carry debug info" >&2
fi

rm -rf "$OUT"
mkdir -p "$OUT"

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    sdl_so="$BUILT/$abi/libSDL3.so"
    exec_so="$BUILT/eclipseexec/$abi/libeclipseexec.so"
    hook_so="$BUILT/eclipseexec/$abi/libeclipsehook.so"

    if [ ! -f "$sdl_so" ] || [ ! -f "$exec_so" ]; then
        echo "package_release: $abi is not fully built (need libSDL3.so and libeclipseexec.so)" >&2
        exit 1
    fi

    stage=$(mktemp -d)
    cp "$sdl_so" "$exec_so" "$stage/"
    # arm64-v8a only; the other ABIs ship without it and the launcher copes.
    if [ -f "$hook_so" ]; then
        cp "$hook_so" "$stage/"
    fi
    if [ -n "$STRIP_TOOL" ]; then
        # --strip-unneeded drops the debug sections and the local symbol table
        # and keeps .dynsym, which is the part dynamic linking still needs.
        for so in "$stage"/*.so; do
            "$STRIP_TOOL" --strip-unneeded "$so"
        done
    fi
    (cd "$stage" && zip -q "$OUT/eclipsesdl-$TAG-$abi.zip" ./*)
    rm -rf "$stage"
done

# The headers travel separately from the binaries: a consumer who wants to
# rebuild SDL against a different eclipseexec needs them without downloading
# four architectures first.
stage=$(mktemp -d)
mkdir -p "$stage/include"
cp -r "$ROOT/include/SDL3" "$stage/include/SDL3"
for licence in LICENSE.txt LICENSE COPYING; do
    if [ -f "$ROOT/$licence" ]; then
        cp "$ROOT/$licence" "$stage/"
        break
    fi
done
(cd "$stage" && zip -qr "$OUT/eclipsesdl-$TAG-headers.zip" ./*)
rm -rf "$stage"

cp "$AAR" "$OUT/eclipsesdl-$TAG.aar"

(cd "$OUT" && sha256sum eclipsesdl-* > SHA256SUMS.txt)

echo "release assets for $TAG:"
ls -l "$OUT"
echo
cat "$OUT/SHA256SUMS.txt"
