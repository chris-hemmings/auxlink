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
# Keep every library the two compiled programs use (e.g. libical for the
# contacts server) when the build tools are removed below.
BINS="/usr/local/libexec/obexd-dummy /usr/local/libexec/bluetoothd-auxlink"
keep=$(for lib in $(ldd $BINS | awk '/=> \//{print $3}' | sort -u); do
         dpkg -S "$lib" 2>/dev/null || dpkg -S "$(readlink -f "$lib")" 2>/dev/null || dpkg -S "/usr$lib" 2>/dev/null || true
       done | sed 's/: .*//' | tr ',' '\n' | sed 's/^ *//' | sort -u)
echo "keeping: $keep"
[ -z "$keep" ] || apt-mark manual $keep >/dev/null
apt-get purge -y build-essential libglib2.0-dev libdbus-1-dev libical-dev libreadline-dev libudev-dev
apt-get autoremove -y --purge
apt-get clean
rm -rf /var/lib/apt/lists/* /usr/local/src/auxlink
if ldd $BINS | grep -q "not found"; then
  ldd $BINS | grep "not found"; echo "a library the programs need was removed"; exit 1
fi

echo "-- user and hostname (Raspberry Pi Imager can't customise a custom image)"
# A ready user, so the card works with no Imager settings and no keyboard:
# auxlink / auxlink (change it with passwd). If Imager settings are used
# anyway, their user is made as well; AuxLink keeps using this one.
if ! getent passwd 1000 >/dev/null; then
  groups=""
  for g in sudo adm audio video plugdev netdev bluetooth dialout gpio i2c spi input render users; do
    getent group "$g" >/dev/null && groups="$groups,$g"
  done
  useradd -m -u 1000 -s /bin/bash -G "${groups#,}" auxlink
  echo "auxlink:auxlink" | chpasswd
  touch /etc/auxlink-default-password    # the setup page asks for a new one
fi
# Don't stop at the console asking for a new user on the first boot.
systemctl disable userconfig.service >/dev/null 2>&1 || true
systemctl mask userconfig.service >/dev/null 2>&1 || true
echo auxlink > /etc/hostname
if grep -q '^127\.0\.1\.1' /etc/hosts; then
  sed -i 's/^127\.0\.1\.1.*/127.0.1.1\tauxlink/' /etc/hosts
else
  printf '127.0.1.1\tauxlink\n' >> /etc/hosts
fi
echo "bootfs first-boot files:"; ls /boot/firmware | grep -iE "user-data|meta-data|network-config|firstrun|userconf" || true
# Raspberry Pi OS runs cloud-init from bootfs on the first boot: make its
# settings name the Pi auxlink too (else it may set "raspberrypi").
UD=/boot/firmware/user-data
if [ -f "$UD" ]; then
  echo "--- user-data as shipped:"; grep -v '^\s*#' "$UD" | grep -v '^\s*$' || true
  if grep -q '^hostname:' "$UD"; then
    sed -i 's/^hostname:.*/hostname: auxlink/' "$UD"
  else
    printf '\nhostname: auxlink\n' >> "$UD"
  fi
  grep -q '^manage_etc_hosts:' "$UD" || printf 'manage_etc_hosts: true\n' >> "$UD"
  # cloud-init only reads it as settings with this first line.
  head -1 "$UD" | grep -q '^#cloud-config' || sed -i '1i #cloud-config' "$UD"
fi

echo "-- first-boot setup"
install -m 644 $A/image/auxlink-firstboot.service /etc/systemd/system/
systemctl enable auxlink-firstboot
echo "${AUXLINK_VERSION:-dev}" > /etc/auxlink-image

rm -f /usr/sbin/policy-rc.d
echo "-- image customised"
