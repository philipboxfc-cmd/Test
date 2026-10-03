#!/bin/bash
#
# Builds KindleNotes for the Kindle Keyboard and packages it as Kindle update
# files that are installed from "Settings -> Menu -> Update Your Kindle".
#
# Output (in dist/):
#   update_kindlenotes_<ver>_<device>_install.bin
#   update_kindlenotes_<ver>_<device>_uninstall.bin
#
# Needs:
#   - a C cross compiler for ARMv6/musl. Default: zig ("pip install ziglang"
#     or a zig binary in PATH). Override with CC_ARM="arm-linux-musleabi-gcc".
#   - kindletool (https://github.com/NiLuJe/KindleTool). Override with KINDLETOOL=...
#   - optional: qemu-arm-static, to run the test-suite against the ARM binary.
#
# DEVICE=k3w (default, Kindle 3 Wi-Fi) | k3g (Wi-Fi+3G US) | k3gb (Wi-Fi+3G EU)
#
set -euo pipefail
cd "$(dirname "$0")"

DEVICE=${DEVICE:-k3w}
KINDLETOOL=${KINDLETOOL:-kindletool}
VERSION=$(sed -n 's/^#define NOTESD_VERSION "\(.*\)"/\1/p' src/notesd.c)

if [ -z "${CC_ARM:-}" ]; then
    if command -v zig >/dev/null 2>&1; then
        CC_ARM="zig cc"
    elif python3 -c 'import ziglang' 2>/dev/null; then
        CC_ARM="python3 -m ziglang cc"
    else
        echo "no ARM compiler: install zig (pip install ziglang) or set CC_ARM" >&2
        exit 1
    fi
fi

ARM_FLAGS="-target arm-linux-musleabi -mcpu=arm1136jf_s -mfloat-abi=soft -static -fno-PIE -no-pie"
case "$CC_ARM" in
    *zig*) ;;
    *) ARM_FLAGS="-march=armv6 -mfloat-abi=soft -static -fno-PIE -no-pie" ;;
esac

mkdir -p build dist payload/notes/bin

echo "== notesd $VERSION: ARMv6 build"
# shellcheck disable=SC2086
$CC_ARM $ARM_FLAGS -Os -std=gnu99 -Wall -Wextra -o payload/notes/bin/notesd src/notesd.c 2>&1 \
    | grep -v -e 'deprecated-non-prototype' -e 'dlstart.c' -e 'rcrt1.c' -e '^ *[0-9]* |' -e '^ *|' -e '^ *\^' -e 'warning generated' || true
[ -s payload/notes/bin/notesd ] || { echo "ARM build failed" >&2; exit 1; }
file payload/notes/bin/notesd | grep -q "ARM" || { echo "not an ARM binary?" >&2; exit 1; }

echo "== native build (for tests)"
cc -std=gnu99 -Wall -Wextra -O2 -o build/notesd-native src/notesd.c

echo "== payload"
STAGE=build/stage
rm -rf "$STAGE"
mkdir -p "$STAGE/notes/bin" "$STAGE/rootfs/etc/kindlenotes" "$STAGE/rootfs/etc/upstart"
cp payload/notes/bin/notesd payload/notes/bin/notes.sh "$STAGE/notes/bin/"
cp payload/notes/config payload/notes/README.txt "$STAGE/notes/"
cp rootfs/etc/kindlenotes/launch.sh "$STAGE/rootfs/etc/kindlenotes/"
cp rootfs/etc/upstart/kindlenotes.conf "$STAGE/rootfs/etc/upstart/"
chmod 755 "$STAGE"/notes/bin/* "$STAGE"/rootfs/etc/kindlenotes/launch.sh
tar --owner=0 --group=0 --numeric-owner -czf build/kindlenotes-payload.tgz -C "$STAGE" notes rootfs

echo "== packages for $DEVICE"
PKG=build/pkg
rm -rf "$PKG"
mkdir -p "$PKG/install" "$PKG/uninstall"
cp package/install.sh build/kindlenotes-payload.tgz "$PKG/install/"
cp package/uninstall.sh "$PKG/uninstall/"
OUT_I="dist/update_kindlenotes_${VERSION}_${DEVICE}_install.bin"
OUT_U="dist/update_kindlenotes_${VERSION}_${DEVICE}_uninstall.bin"
(cd "$PKG/install" && "$KINDLETOOL" create ota -d "$DEVICE" install.sh kindlenotes-payload.tgz "../../../$OUT_I")
(cd "$PKG/uninstall" && "$KINDLETOOL" create ota -d "$DEVICE" uninstall.sh "../../../$OUT_U")

ls -la "$OUT_I" "$OUT_U"
echo "== done"
