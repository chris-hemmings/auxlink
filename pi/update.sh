#!/bin/bash
# auxlink quick update: copy the programs and service files from this
# folder and restart the auxlink services. Run from this folder:
#   sudo ./update.sh
# No packages, no internet, no reboot, Bluetooth stays up (the car and phone
# links survive). Use install.sh for a first install or if a release says so.
set -e
[ "$(id -u)" = 0 ] || { echo "Run with sudo: sudo ./update.sh"; exit 1; }
[ -f /etc/auxlink.conf ] || { echo "Not installed yet: run sudo ./install.sh first"; exit 1; }

U=${SUDO_USER:-$(getent passwd 1000 | cut -d: -f1)}
H=$(getent passwd "$U" | cut -d: -f6)
UID_U=$(id -u "$U")
HERE=$(cd "$(dirname "$0")" && pwd)
asuser() { runuser -u "$U" -- env XDG_RUNTIME_DIR=/run/user/$UID_U "$@"; }

# Settings: keep every value, add any this release introduced.
added=""
while IFS= read -r line; do
  case "$line" in ''|\#*) continue ;; esac
  k=${line%%=*}
  grep -q "^$k=" /etc/auxlink.conf || { echo "$line" >> /etc/auxlink.conf; added="$added $k"; }
done < "$HERE/etc/auxlink.conf"
echo "Settings kept${added:+; added:$added}"

install -D -m 644 "$HERE/lib/common.sh" /usr/local/lib/auxlink/common.sh
install -D -m 644 "$HERE/lib/auxconf.py" /usr/local/lib/auxlink/auxconf.py
install -D -m 644 "$HERE/share/index.html" /usr/local/share/auxlink/index.html
install -D -m 644 "$HERE/share/cover-test.jpg" /usr/local/share/auxlink/cover-test.jpg
install -m 755 "$HERE"/bin/* /usr/local/bin/
install -o "$U" -g "$U" -m 755 "$HERE"/user-bin/* "$H/.local/bin/"
install -m 644 "$HERE"/systemd/system/*.service /etc/systemd/system/
install -o "$U" -g "$U" -m 644 "$HERE"/systemd/user/*.service "$H/.config/systemd/user/"
install -o "$U" -g "$U" -m 644 "$HERE"/wireplumber/*.conf "$H/.config/wireplumber/wireplumber.conf.d/"
echo "Programs and services copied"

systemctl daemon-reload
systemctl enable auxlink-cover auxlink-usb-gadget >/dev/null 2>&1 || true
systemctl restart auxlink-usb-gadget
systemctl restart auxlink-pairing auxlink-reconnect hfp-relay auxlink-media auxlink-web auxlink-cover
asuser systemctl --user daemon-reload
asuser systemctl --user restart auxlink-audio pbap-sync
echo "Services restarted. Done (no reboot needed)."
