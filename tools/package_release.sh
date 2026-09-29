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
# The binaries are taken from $STAGE — the directory build_native.sh strips
# and Gradle packages — so the zip and the AAR for one tag are the same bytes,
# not two opinions about what the tag contains.
#
# Usage: TAG=v0.1.0 sh tools/package_release.sh
set -eu

TAG=${TAG:?set TAG to the release tag, e.g. TAG=v0.1.0}
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/dist"
STAGE=${STAGE:-jni_bindings/src/main/jniLibs}
case $STAGE in /*) ;; *) STAGE=$ROOT/$STAGE ;; esac
AAR="$ROOT/jni_bindings/build/outputs/aar/jni_bindings-release.aar"

command -v zip >/dev/null 2>&1 || {
    echo "package_release: 'zip' is not installed (apt install zip / apk add zip)" >&2
    exit 2
}

if [ ! -d "$STAGE" ]; then
    echo "package_release: $STAGE is missing — run tools/build_native.sh first" >&2
    exit 1
fi
if [ ! -f "$AAR" ]; then
    echo "package_release: $AAR is missing — run ./gradlew :jni_bindings:assembleRelease first" >&2
    exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    if [ ! -f "$STAGE/$abi/libSDL3.so" ] || [ ! -f "$STAGE/$abi/libeclipseexec.so" ]; then
        echo "package_release: $abi is not staged (need libSDL3.so and libeclipseexec.so)" >&2
        exit 1
    fi
    # arm64-v8a also carries libeclipsehook.so; the other ABIs ship without it
    # and the launcher copes, so the zip simply holds whatever was staged.
    (cd "$STAGE/$abi" && zip -q "$OUT/eclipsesdl-$TAG-$abi.zip" ./*)
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
