#!/bin/bash
# First boot of AuxLink.
#
# 1) The prebuilt SD-card image (/etc/auxlink-image exists): everything is
#    already installed and compiled; this only finishes the setup for the
#    user made in Raspberry Pi Imager and reboots. No internet needed.
#    Started by auxlink-firstboot.service.
#
# 2) Installing onto a plain Raspberry Pi OS card, from bootfs:
# Copy the whole pi folder onto the SD card's boot partition (bootfs), so it
# appears as /boot/firmware/pi, and have cloud-init run:
#     bash /boot/firmware/pi/firstboot.sh
# Needs internet once (Ethernet cable, or the Wi-Fi set in Raspberry Pi Imager,
# e.g. your phone's hotspot). Progress is written to
#     bootfs/auxlink-install.log   (readable from Windows)
# and the Pi reboots by itself when it is done.
LOG=/boot/firmware/auxlink-install.log
DONE=/var/lib/auxlink-installed
exec >>"$LOG" 2>&1
[ -e "$DONE" ] && { echo "$(date) already installed; nothing to do"; exit 0; }
echo "===== $(date) auxlink first-boot install ====="

if [ -e /etc/auxlink-image ]; then
  echo "AuxLink image $(cat /etc/auxlink-image): finishing the setup"
  echo "Waiting for the user (set in Raspberry Pi Imager's settings)..."
  for i in $(seq 1 360); do getent passwd 1000 >/dev/null && break; sleep 5; done
  U=$(getent passwd 1000 | cut -d: -f1)
  if [ -z "$U" ]; then
    echo "No user yet: create one (Imager settings, or the console prompt), then reboot."
    exit 1
  fi
  loginctl enable-linger "$U"
  bash /opt/auxlink/install.sh || { echo "install.sh failed"; exit 1; }
  touch "$DONE"
  systemctl disable auxlink-firstboot >/dev/null 2>&1
  echo "===== $(date) done; rebooting. Then join the Wi-Fi 'AuxLink-setup' (password auxlink-setup) -> http://10.42.0.1 ====="
  sync
  systemctl reboot
  exit 0
fi

# The FAT boot partition has no permissions: work from a copy on the root fs.
rm -rf /opt/auxlink && cp -r /boot/firmware/pi /opt/auxlink && chmod -R a+rX /opt/auxlink && chmod +x /opt/auxlink/*.sh /opt/auxlink/bin/* /opt/auxlink/user-bin/* /opt/auxlink/extras/*

echo "Waiting for internet (up to 15 minutes)..."
for i in $(seq 1 90); do
  if curl -fsI --max-time 8 http://deb.debian.org >/dev/null 2>&1; then echo "online"; break; fi
  sleep 10
done
if ! curl -fsI --max-time 8 http://deb.debian.org >/dev/null 2>&1; then
  echo "NO INTERNET: plug in Ethernet or set Wi-Fi in Imager, then power-cycle to retry."
  exit 1
fi

U=$(getent passwd 1000 | cut -d: -f1); U=${U:-chris}
loginctl enable-linger "$U"
apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y full-upgrade

echo "--- install.sh (1/2)"
bash /opt/auxlink/install.sh || { echo "install.sh failed"; exit 1; }

echo "--- contacts server (file-based obexd, ~20 min)"
UID_U=$(id -u "$U")
runuser -u "$U" -- env HOME=/home/"$U" XDG_RUNTIME_DIR=/run/user/$UID_U \
  bash /opt/auxlink/extras/build-obexd-dummy.sh || echo "contacts server build failed (calls and music still work)"

echo "--- install.sh (2/2: picks up the contacts server)"
bash /opt/auxlink/install.sh

touch "$DONE"
echo "===== $(date) done; rebooting. Then open the setup Wi-Fi 'AuxLink-setup' -> http://10.42.0.1 ====="
sync
systemctl reboot
