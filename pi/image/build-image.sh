#!/bin/bash
# Build the AuxLink SD-card image: Raspberry Pi OS Lite (64-bit) with AuxLink
# installed and its two BlueZ extras (contacts server, album-art bluetoothd)
# already compiled. Flash it with Raspberry Pi Imager; the first boot only
# finishes the setup for the user made there (pi/firstboot.sh).
#
# Runs as root on an arm64 Linux host (GitHub's ubuntu-24.04-arm runner), so
# the image's programs run natively in a chroot - no emulation.
#   sudo AUXLINK_VERSION=1.0.0 pi/image/build-image.sh auxlink-pi.img
set -euo pipefail
OUT=${1:-auxlink-pi.img}
VERSION=${AUXLINK_VERSION:-dev}
PI_DIR=$(cd "$(dirname "$0")/.." && pwd)
URL=${RPIOS_URL:-https://downloads.raspberrypi.com/raspios_lite_arm64_latest}
GROW=${GROW:-2G}
WORK=$(mktemp -d)
MNT=$WORK/root
LOOP=""

cleanup() {
  set +e
  for d in boot/firmware dev/pts dev proc sys; do
    mountpoint -q "$MNT/$d" && umount -l "$MNT/$d"
  done
  mountpoint -q "$MNT" && umount -l "$MNT"
  [ -n "$LOOP" ] && losetup -d "$LOOP"
}
trap cleanup EXIT

echo "== Raspberry Pi OS Lite (64-bit): $URL"
curl -fL --retry 3 -o "$WORK/os.img.xz" "$URL"
xz -d -c "$WORK/os.img.xz" > "$OUT"
rm -f "$WORK/os.img.xz"

echo "== Making room to build in (+$GROW)"
truncate -s "+$GROW" "$OUT"
parted -s "$OUT" resizepart 2 100%
LOOP=$(losetup -fP --show "$OUT")
e2fsck -pf "${LOOP}p2" || true
resize2fs "${LOOP}p2"

echo "== Mounting"
mkdir -p "$MNT"
mount "${LOOP}p2" "$MNT"
mount "${LOOP}p1" "$MNT/boot/firmware"
mount --bind /dev "$MNT/dev"
mount --bind /dev/pts "$MNT/dev/pts"
mount -t proc proc "$MNT/proc"
mount -t sysfs sys "$MNT/sys"
# Name resolution for apt and the BlueZ download, restored afterwards.
if [ -e "$MNT/etc/resolv.conf" ] || [ -L "$MNT/etc/resolv.conf" ]; then
  mv "$MNT/etc/resolv.conf" "$MNT/etc/resolv.conf.auxlink"
fi
cp -L /etc/resolv.conf "$MNT/etc/resolv.conf"

echo "== Copying AuxLink to /opt/auxlink"
rm -rf "$MNT/opt/auxlink"
mkdir -p "$MNT/opt/auxlink"
cp -r "$PI_DIR/." "$MNT/opt/auxlink/"
find "$MNT/opt/auxlink" -name __pycache__ -prune -exec rm -rf {} +
chmod +x "$MNT"/opt/auxlink/*.sh "$MNT"/opt/auxlink/bin/* "$MNT"/opt/auxlink/user-bin/* \
  "$MNT"/opt/auxlink/extras/* "$MNT"/opt/auxlink/image/*.sh

echo "== Checking every file the install scripts use is there"
bash "$PI_DIR/image/check-files.sh" "$MNT/opt/auxlink"

echo "== Installing inside the image"
chroot "$MNT" env AUXLINK_VERSION="$VERSION" /bin/bash /opt/auxlink/image/customize.sh

rm -f "$MNT/etc/resolv.conf"
if [ -e "$MNT/etc/resolv.conf.auxlink" ] || [ -L "$MNT/etc/resolv.conf.auxlink" ]; then
  mv "$MNT/etc/resolv.conf.auxlink" "$MNT/etc/resolv.conf"
fi

echo "== Zeroing free space (smaller download)"
dd if=/dev/zero of="$MNT/zero.fill" bs=4M status=none 2>/dev/null || true
rm -f "$MNT/zero.fill"
cleanup
LOOP=""

echo "== Shrinking the root partition back down"
LOOP=$(losetup -fP --show "$OUT")
e2fsck -pf "${LOOP}p2" || true
resize2fs -M "${LOOP}p2"
BLOCKS=$(dumpe2fs -h "${LOOP}p2" 2>/dev/null | awk -F: '/^Block count/ {gsub(/ /,"",$2); print $2}')
BSIZE=$(dumpe2fs -h "${LOOP}p2" 2>/dev/null | awk -F: '/^Block size/ {gsub(/ /,"",$2); print $2}')
START=$(partx -g -o START -n 2 "$OUT" | tr -d ' ')
losetup -d "$LOOP"; LOOP=""
# Filesystem size plus 64 MB of slack; the Pi grows it to the whole card on first boot.
SECTORS=$(( BLOCKS * BSIZE / 512 + 131072 ))
echo "${START},${SECTORS}" | sfdisk -q -N 2 --no-reread "$OUT"
truncate -s $(( (START + SECTORS) * 512 )) "$OUT"

echo "== Done: $OUT ($(du -h "$OUT" | cut -f1)), AuxLink $VERSION"
