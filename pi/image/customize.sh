#!/bin/bash
# Runs inside the Raspberry Pi OS image (chroot), from build-image.sh: does
# everything that doesn't need the user yet. The user is made in Raspberry
# Pi Imager, so install.sh itself runs on the first boot (auxlink-firstboot),
# finds everything below already in place, and needs no internet.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
A=/opt/auxlink
BUILD_DEPS="build-essential pkg-config wget xz-utils libglib2.0-dev libdbus-1-dev
  libical-dev libreadline-dev libudev-dev"

# No services start inside the chroot.
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod +x /usr/sbin/policy-rc.d

echo "-- packages"
apt-get update
apt-get install -y $(grep -v '^#' $A/packages.txt)
apt-get install -y $BUILD_DEPS

echo "-- contacts server (obexd with the file phonebook)"
bash $A/extras/build-obexd-dummy.sh --image
echo "-- bluetoothd with album art"
bash $A/extras/build-bluetoothd-cover.sh --image

echo "-- tidying up"
apt-get purge -y build-essential libglib2.0-dev libdbus-1-dev libical-dev libreadline-dev libudev-dev
apt-get autoremove -y --purge
apt-get clean
rm -rf /var/lib/apt/lists/* /usr/local/src/auxlink

echo "-- first-boot setup"
install -m 644 $A/image/auxlink-firstboot.service /etc/systemd/system/
systemctl enable auxlink-firstboot
echo "${AUXLINK_VERSION:-dev}" > /etc/auxlink-image

rm -f /usr/sbin/policy-rc.d
echo "-- image customised"
