#!/bin/bash
# Unattended first-boot install of teslabridge (release 2).
# Copy the whole tb2 folder onto the SD card's boot partition (bootfs), so it
# appears as /boot/firmware/tb2, and have cloud-init run:
#     bash /boot/firmware/tb2/firstboot.sh
# Needs internet once (Ethernet cable, or the Wi-Fi set in Raspberry Pi Imager,
# e.g. your phone's hotspot). Progress is written to
#     bootfs/teslabridge-install.log   (readable from Windows)
# and the Pi reboots by itself when it is done.
LOG=/boot/firmware/teslabridge-install.log
DONE=/var/lib/teslabridge-installed
exec >>"$LOG" 2>&1
[ -e "$DONE" ] && { echo "$(date) already installed; nothing to do"; exit 0; }
echo "===== $(date) teslabridge first-boot install ====="

# The FAT boot partition has no permissions: work from a copy on the root fs.
rm -rf /opt/tb2 && cp -r /boot/firmware/tb2 /opt/tb2 && chmod -R a+rX /opt/tb2 && chmod +x /opt/tb2/*.sh /opt/tb2/bin/* /opt/tb2/user-bin/* /opt/tb2/extras/*

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
bash /opt/tb2/install.sh || { echo "install.sh failed"; exit 1; }

echo "--- contacts server (file-based obexd, ~20 min)"
UID_U=$(id -u "$U")
runuser -u "$U" -- env HOME=/home/"$U" XDG_RUNTIME_DIR=/run/user/$UID_U \
  bash /opt/tb2/extras/build-obexd-dummy.sh || echo "contacts server build failed (calls and music still work)"

echo "--- install.sh (2/2: picks up the contacts server)"
bash /opt/tb2/install.sh

touch "$DONE"
echo "===== $(date) done; rebooting. Then open the setup Wi-Fi 'teslabridge-setup' -> http://10.42.0.1 ====="
sync
systemctl reboot
