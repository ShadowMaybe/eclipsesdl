#!/bin/sh
#
# eclipsesdl — verify the exported symbol surface of a built library.
#
# Two things break consumers silently: an export disappearing (JNI_OnLoad gone
# means nothing in the AAR can ever bind) or one appearing (a helper leaking
# into the ABI, or the version script being dropped). Both are cheaper to catch
# here than on a device.
#
# Usage: tools/check_exports.sh <library.so> <allow-regex> [<required-symbol>...]
#
# Every *defined* dynamic symbol must match <allow-regex>; every required
# symbol must be present. Undefined imports are ignored — the Android linker
# resolves those at load time.
#
# NM may be set to any nm-like tool. On CI that is the NDK's llvm-nm, which
# reads every ABI; a host binutils usually only reads its own architecture.
set -eu

if [ "$#" -lt 2 ]; then
    echo "usage: tools/check_exports.sh <library.so> <allow-regex> [<symbol>...]" >&2
    exit 2
fi

file=$1
allow=$2
shift 2
NM=${NM:-nm}

if [ ! -f "$file" ]; then
    echo "check_exports: $file does not exist" >&2
    exit 1
fi

symbols=$("$NM" -D --defined-only "$file" 2>/dev/null | awk '{
    n = $NF
    # The SDL version script names a version (SDL3_0.0.0), so nm prints
    # JNI_OnLoad@@SDL3_0.0.0; the eclipseexec script has no version name at
    # all. Strip the suffix so both compare as plain symbol names — an ELF
    # symbol can never contain "@", so the first one ends the name.
    sub(/@.*$/, "", n)
    print n
}' | sort -u)

status=0

for required in "$@"; do
    if ! printf '%s\n' "$symbols" | grep -qx "$required"; then
        echo "MISSING export in $file: $required" >&2
        status=1
    fi
done

unexpected=$(printf '%s\n' "$symbols" | grep -Ev "$allow" || true)
if [ -n "$unexpected" ]; then
    echo "UNEXPECTED exports in $file (the version script should hide these):" >&2
    printf '%s\n' "$unexpected" | sed 's/^/  /' >&2
    status=1
fi

if [ "$status" -eq 0 ]; then
    count=$(printf '%s\n' "$symbols" | grep -c . || true)
    echo "exports OK: $file ($count defined, none outside the allow-list)"
fi

exit "$status"
