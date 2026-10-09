#!/bin/bash
# Build bluetoothd with album-art support for the car (AVRCP 1.6 cover art)
# and switch the Bluetooth service to it. Same BlueZ version as installed,
# plus extras/bluez-cover-art.patch (advertise cover art on PSM 0x1025, pass
# the player's image handle to the car). tb-cover serves the images.
# Takes ~15-20 min on a Pi 4 and needs internet once.
#   sudo extras/build-bluetoothd-cover.sh          build + switch to it
#   sudo extras/build-bluetoothd-cover.sh --undo   back to the stock bluetoothd
set -e
[ "$(id -u)" = 0 ] || { echo "Run with sudo"; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd)
BIN=/usr/local/libexec/bluetoothd-tb
DROPIN=/etc/systemd/system/bluetooth.service.d/tb-cover-art.conf

if [ "$1" = "--undo" ]; then
  rm -f "$DROPIN"
  systemctl daemon-reload
  systemctl restart bluetooth
  echo "Back on the stock bluetoothd (album art off)."
  exit 0
fi

VER=$(dpkg-query -W -f='${Version}' bluez | sed 's/-.*//')   # e.g. 5.82
echo "Building bluetoothd from BlueZ $VER with cover art"
apt-get install -y build-essential pkg-config wget xz-utils \
  libglib2.0-dev libdbus-1-dev libical-dev libreadline-dev libudev-dev

SRC=/usr/local/src/teslabridge
mkdir -p $SRC && cd $SRC
[ -f bluez-$VER.tar.xz ] || wget -q https://www.kernel.org/pub/linux/bluetooth/bluez-$VER.tar.xz
rm -rf bluez-$VER-tb && mkdir bluez-$VER-tb && tar xf bluez-$VER.tar.xz -C bluez-$VER-tb --strip-components=1
cd bluez-$VER-tb
patch -p1 < "$HERE/bluez-cover-art.patch"

# Same paths as Debian's build: pairings in /var/lib/bluetooth, config in
# /etc/bluetooth. Getting these wrong would lose every pairing.
./configure --prefix=/usr --sysconfdir=/etc --localstatedir=/var --libexecdir=/usr/libexec \
  --disable-manpages --disable-cups --disable-midi --disable-mesh --disable-monitor \
  --disable-client --disable-tools --disable-obex --disable-sixaxis --disable-udev \
  --disable-systemd --disable-datafiles >/dev/null
grep -q '#define STORAGEDIR "/var/lib/bluetooth"' config.h || { echo "Wrong storage dir; stopping"; exit 1; }
mkdir -p lib/bluetooth
for f in lib/*.h; do ln -sf "$PWD/$f" "lib/bluetooth/$(basename "$f")"; done
make src/builtin.h >/dev/null
make -j3 src/bluetoothd
install -D -m 755 src/bluetoothd "$BIN"

# Start it exactly like the stock one (same arguments), plus the cover-art PSM.
STOCK=$(systemctl cat bluetooth.service | sed -n 's/^ExecStart=\(\/[^ ]*bluetoothd\)\(.*\)$/\1\2/p' | head -1)
ARGS=${STOCK#* }; [ "$ARGS" = "$STOCK" ] && ARGS=""
mkdir -p "$(dirname "$DROPIN")"
cat > "$DROPIN" <<CONF
# teslabridge: bluetoothd with AVRCP cover art. Undo: build-bluetoothd-cover.sh --undo
[Service]
Environment=TB_COVER_ART_PSM=0x1025
ExecStart=
ExecStart=$BIN $ARGS
CONF
systemctl daemon-reload
systemctl enable tb-cover >/dev/null 2>&1 || true
systemctl restart bluetooth
sleep 3
systemctl --no-pager --lines=0 status bluetooth | head -4
echo
echo "Done. bluetoothd now advertises album art; tb-cover serves it."
echo "The car only reads this on a fresh pairing or reconnect: turn its Bluetooth off and on."
echo "To undo: sudo $0 --undo"
