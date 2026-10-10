#!/bin/bash
# Build BlueZ's obexd with the file-based ("dummy") phonebook backend and make
# your user's obex service use it. Contacts and call history are then served
# straight from ~/phonebook/telecom/{pb,ich,och,mch,cch}/N.vcf, which
# pbap-pull.py writes. Takes ~15-20 min on a Pi 3B+.
#   extras/build-obexd-dummy.sh            build + switch your obex service to it
#   extras/build-obexd-dummy.sh --image    (as root, image build) build + install only
set -e
IMAGE=0; [ "$1" = --image ] && IMAGE=1
SUDO=sudo; [ $IMAGE = 1 ] && SUDO=
VER=$(dpkg-query -W -f='${Version}' bluez | sed 's/-.*//')   # e.g. 5.82
echo "Building obexd from BlueZ $VER"

$SUDO apt-get install -y build-essential pkg-config wget xz-utils \
  libglib2.0-dev libdbus-1-dev libical-dev libreadline-dev libudev-dev

SRC=~/src; [ $IMAGE = 1 ] && SRC=/usr/local/src/auxlink
mkdir -p $SRC && cd $SRC
[ -f bluez-$VER.tar.xz ] || wget https://www.kernel.org/pub/linux/bluetooth/bluez-$VER.tar.xz
rm -rf bluez-$VER && tar xf bluez-$VER.tar.xz && cd bluez-$VER

./configure --prefix=/usr --with-phonebook=dummy \
  --disable-manpages --disable-cups --disable-midi --disable-mesh \
  --disable-monitor --disable-client --disable-tools --disable-datafiles \
  --disable-udev --disable-systemd
# Generated files a full `make` would create first.
mkdir -p lib/bluetooth
for f in lib/*.h; do ln -sf "$PWD/$f" "lib/bluetooth/$(basename "$f")"; done
make obexd/src/builtin.h
make -j3 obexd/src/obexd

$SUDO install -D -m 755 obexd/src/obexd /usr/local/libexec/obexd-dummy
# Image build: the user doesn't exist yet; install.sh points their obex
# service at it on first boot.
[ $IMAGE = 1 ] && { echo "obexd-dummy installed"; exit 0; }

mkdir -p ~/.config/systemd/user/obex.service.d
cat > ~/.config/systemd/user/obex.service.d/dummy-phonebook.conf <<'CONF'
[Service]
ExecStart=
ExecStart=/usr/local/libexec/obexd-dummy -P mas,mns
CONF
systemctl --user daemon-reload
systemctl --user restart obex
sleep 2
systemctl --user status obex --no-pager | head -8
echo
echo "Done. obex now runs /usr/local/libexec/obexd-dummy."
echo "To undo: rm ~/.config/systemd/user/obex.service.d/dummy-phonebook.conf && systemctl --user daemon-reload && systemctl --user restart obex"
